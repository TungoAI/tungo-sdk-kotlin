package kz.tungo.sdk.kotlin

import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.fail
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

/** A local stand-in for the API (127.0.0.1, random port) and clients wired to it with a fake clock. */
internal class MockApi : Closeable {
    val server: MockWebServer = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }

    /** Every delay the SDK asked for, in milliseconds; nothing really sleeps. */
    val sleeps: MutableList<Long> = CopyOnWriteArrayList()

    @Volatile
    var now: Long = 1_800_000_000_000L

    private val clients = ArrayList<TungoClient>()

    val baseUrl: String get() = server.url("/").toString()

    fun client(block: TungoClientOptions.Builder.() -> Unit = {}): TungoClient {
        val client = TungoClient {
            apiKey = KEY
            baseUrl = this@MockApi.baseUrl
            companyId = "co_1"
            workspaceId = "ws_1"
            hooks = InternalHooks(
                sleep = { millis ->
                    sleeps.add(millis)
                    now += millis
                },
                random = { 1.0 },
                nowMillis = { now },
            )
            block()
        }
        clients.add(client)
        return client
    }

    fun enqueueJson(body: String, status: Int = 200, vararg headers: Pair<String, String>) {
        val response = MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)
        for ((name, value) in headers) response.setHeader(name, value)
        server.enqueue(response)
    }

    fun enqueueSse(raw: String, vararg headers: Pair<String, String>) {
        val response = MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream").setBody(raw)
        for ((name, value) in headers) response.setHeader(name, value)
        server.enqueue(response)
    }

    fun take(): RecordedRequest = server.takeRequest(10, TimeUnit.SECONDS) ?: fail("no request reached the mock API")

    override fun close() {
        for (client in clients) client.close()
        server.shutdown()
    }

    companion object {
        const val KEY: String = "tg_test_0123456789abcdef"
    }
}

/**
 * A raw event-stream server: it sends the first part, waits until it is released (or the client
 * goes away), sends the rest, and records whether the client closed the connection.
 */
internal class SseSocketServer(private val first: String, private val rest: String) : Closeable {
    private val socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    private val release = CountDownLatch(1)

    /** Counted down when the client closes its side of the connection. */
    val clientClosed: CountDownLatch = CountDownLatch(1)

    @Volatile
    var restSent: Boolean = false

    val baseUrl: String get() = "http://127.0.0.1:${socket.localPort}"

    private val worker = thread(isDaemon = true) {
        try {
            socket.accept().use { serve(it) }
        } catch (ignored: java.io.IOException) {
            // The test is over.
        }
    }

    private fun serve(connection: Socket) {
        val input = connection.getInputStream()
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val b = input.read()
            if (b < 0) return
            head.append(b.toChar())
        }
        val output = connection.getOutputStream()
        output.write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n" + first).toByteArray())
        output.flush()
        val watcher = thread(isDaemon = true) {
            try {
                while (input.read() >= 0) Unit
            } catch (ignored: java.io.IOException) {
                // A reset also means the client is gone.
            }
            clientClosed.countDown()
            release.countDown()
        }
        release.await(20, TimeUnit.SECONDS)
        if (clientClosed.count == 0L) return
        restSent = true
        output.write(rest.toByteArray())
        output.flush()
        connection.shutdownOutput()
        watcher.join(5000)
    }

    fun release() {
        release.countDown()
    }

    override fun close() {
        release.countDown()
        socket.close()
        worker.join(5000)
    }
}
