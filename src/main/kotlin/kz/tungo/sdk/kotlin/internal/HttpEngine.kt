package kz.tungo.sdk.kotlin.internal

import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kz.tungo.sdk.kotlin.ApiResponse
import kz.tungo.sdk.kotlin.RequestOptions
import kz.tungo.sdk.kotlin.ResponseHeaders
import kz.tungo.sdk.kotlin.TungoClientOptions
import kz.tungo.sdk.kotlin.TungoSdk
import kz.tungo.sdk.kotlin.errors.TungoApiException
import kz.tungo.sdk.kotlin.errors.TungoConfigException
import kz.tungo.sdk.kotlin.errors.TungoConnectionException
import kz.tungo.sdk.kotlin.errors.TungoDecodeException
import kz.tungo.sdk.kotlin.errors.TungoException
import kz.tungo.sdk.kotlin.errors.TungoTimeoutException
import kz.tungo.sdk.kotlin.errors.TungoValidationException
import kz.tungo.sdk.kotlin.streaming.ServerSentEvent
import kz.tungo.sdk.kotlin.streaming.StreamResponse
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink

/** A fully read response. */
internal class RawResponse(val status: Int, val headers: ResponseHeaders, val bytes: ByteArray) {
    fun text(): String = String(bytes, Charsets.UTF_8)
}

/**
 * How the events of a stream are told apart.
 *
 * A stream of one event type names its events ([eventName], for example "feed"): events of that
 * name (and unnamed ones) are decoded, an event named "error" ends the stream with the typed API
 * error, events of other names are skipped. A stream of a tagged union ([eventName] is null)
 * decodes every event by the tag inside its data; [malformedError] wraps the data of an event
 * named "error" that does not carry the error tag, so that it is still seen as an error.
 */
internal class StreamKind<T> private constructor(val eventName: String?, val malformedError: ((JsonObject) -> T)?) {
    companion object {
        fun <T> named(eventName: String): StreamKind<T> = StreamKind(eventName, null)

        fun <T> union(malformedError: ((JsonObject) -> T)?): StreamKind<T> = StreamKind(null, malformedError)
    }
}

/** Sends requests: authentication, headers, timeouts, retries, error mapping, decoding and streaming. */
internal class HttpEngine(val options: TungoClientOptions, baseClient: OkHttpClient) {
    private val hooks = options.hooks

    /** Ordinary calls: one total timeout per attempt, set on each call. */
    private val callClient: OkHttpClient = baseClient.newBuilder()
        .connectTimeout(options.connectTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        // A redirect is never followed, also with a client supplied by the caller: the API key
        // travels in a custom header that OkHttp does not remove when the redirect leads to
        // another host. A 3xx answer becomes a TungoApiException with that status.
        .followRedirects(false)
        .followSslRedirects(false)
        // The retry policy of the SDK is the only one. OkHttp would repeat a request by itself
        // after "408" and after "503 with Retry-After: 0"; both are disguised on the way in and
        // restored by statusOf and toHeaders. (A request that must not be repeated also has a
        // one-shot body, so OkHttp never re-sends it on a broken connection.)
        .addNetworkInterceptor(Interceptor { chain ->
            val response = chain.proceed(chain.request())
            val retryAfter = response.header(RETRY_AFTER)
            when {
                response.code == 408 -> response.newBuilder().code(DISGUISED_STATUS).header(HIDDEN_STATUS, "408").build()
                response.code == 503 && retryAfter != null ->
                    response.newBuilder().removeHeader(RETRY_AFTER).header(HIDDEN_RETRY_AFTER, retryAfter).build()
                else -> response
            }
        })
        .build()

    init {
        if (options.baseUrl.toHttpUrlOrNull() == null) {
            throw TungoConfigException("The base URL is not a valid http or https URL")
        }
    }

    /** The company id of a call: the argument, else the default of the client. */
    fun companyId(explicit: String?): String = explicit ?: options.companyId
        ?: throw TungoConfigException("No company id: pass companyId to the call or set it on the client (or TUNGO_COMPANY_ID)")

    /** The workspace id of a call: the argument, else the default of the client. */
    fun workspaceId(explicit: String?): String = explicit ?: options.workspaceId
        ?: throw TungoConfigException("No workspace id: pass workspaceId to the call or set it on the client (or TUNGO_WORKSPACE_ID)")

    /** Encodes a typed request body as JSON. */
    fun <T> jsonPayload(serializer: SerializationStrategy<T>, value: T): RequestPayload {
        val text = try {
            TungoJson.encoder.encodeToString(serializer, value)
        } catch (e: SerializationException) {
            throw TungoValidationException("The request body cannot be encoded as JSON: ${e.message}", e)
        }
        return RequestPayload(text.toByteArray(Charsets.UTF_8), JSON_MEDIA_TYPE)
    }

    /**
     * Fixes the idempotency key of a request that is described now and sent later (a cold flow):
     * the caller's key, else a random one chosen here, so that sending the request again repeats
     * the same key.
     */
    fun withIdempotencyKey(requestOptions: RequestOptions): RequestOptions =
        if (requestOptions.idempotencyKey != null) requestOptions else requestOptions.withIdempotencyKey(UUID.randomUUID().toString())

    suspend fun sleep(millis: Long) {
        val custom = hooks.sleep
        if (custom != null) custom(millis) else delay(millis)
    }

    /** Sends a request and decodes the JSON body of the response. */
    suspend fun <T> json(spec: RequestSpec, requestOptions: RequestOptions, deserializer: DeserializationStrategy<T>): ApiResponse<T> {
        val raw = execute(spec, requestOptions)
        val text = raw.text()
        val body = try {
            TungoJson.decoder.decodeFromString(deserializer, text)
        } catch (e: SerializationException) {
            throw decodeFailure(raw.status, raw.headers, text, e)
        } catch (e: IllegalArgumentException) {
            throw decodeFailure(raw.status, raw.headers, text, e)
        }
        return ApiResponse(body, raw.status, raw.headers)
    }

    /** Sends a request whose successful response has no body. */
    suspend fun empty(spec: RequestSpec, requestOptions: RequestOptions): ApiResponse<Unit> {
        val raw = execute(spec, requestOptions)
        return ApiResponse(Unit, raw.status, raw.headers)
    }

    /** Sends a request and reads the whole response; non-2xx statuses become exceptions. */
    suspend fun execute(spec: RequestSpec, requestOptions: RequestOptions): RawResponse {
        val prepared = prepare(spec, requestOptions, ACCEPT_JSON)
        val timeout = requestOptions.timeout ?: options.timeout ?: if (spec.longRunning) LONG_RUNNING_TIMEOUT else DEFAULT_TIMEOUT
        return withRetries(prepared, requestOptions) {
            val call = callClient.newCall(prepared.request)
            call.timeout().timeout(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            val response = try {
                call.awaitBuffered()
            } catch (e: IOException) {
                throw mapIo(e, timeout)
            }
            if (response.status in 200..299) Attempt.Done(response) else Attempt.Failed(response)
        }
    }

    /**
     * Opens an event stream and runs [block] with it. The request is retried only until the
     * response headers arrive. The connection is closed when the block ends.
     */
    suspend fun <T, R> stream(
        spec: RequestSpec,
        requestOptions: RequestOptions,
        deserializer: DeserializationStrategy<T>,
        kind: StreamKind<T>,
        block: suspend (StreamResponse<T>) -> R,
    ): R {
        val prepared = prepare(spec, requestOptions, ACCEPT_SSE)
        val idle = requestOptions.streamIdleTimeout ?: options.streamIdleTimeout
        val streamClient = callClient.newBuilder().readTimeout(idle.inWholeMilliseconds, TimeUnit.MILLISECONDS).build()
        var open: Response? = null
        var openCall: Call? = null
        withRetries(prepared, requestOptions) {
            val call = streamClient.newCall(prepared.request)
            val response = try {
                call.awaitResponse()
            } catch (e: IOException) {
                // Before the headers arrive either limit may have expired: connecting, or waiting for the first byte.
                throw mapIo(e, "The stream did not open in time (connect timeout ${options.connectTimeout}, idle timeout $idle)")
            }
            if (response.code in 200..299) {
                if (!isEventStream(response.header("Content-Type"))) {
                    // A success reply that is not an event stream (JSON, the HTML page of a proxy,
                    // no type at all) must not look like a stream that ended without events.
                    val headers = toHeaders(response.headers)
                    val text = try {
                        withContext(Dispatchers.IO) { response.use { it.body?.string().orEmpty() } }
                    } catch (e: IOException) {
                        currentCoroutineContext().ensureActive()
                        ""
                    }
                    val got = response.header("Content-Type") ?: "no Content-Type"
                    val requestId = headers["x-request-id"]
                    val suffix = if (requestId != null) " [request id $requestId]" else ""
                    throw TungoDecodeException(
                        "Expected a text/event-stream response but the server returned status ${response.code} with $got$suffix",
                        null, response.code, headers, text,
                    )
                }
                open = response
                openCall = call
                Attempt.Done(RawResponse(response.code, toHeaders(response.headers), EMPTY))
            } else {
                val bytes = try {
                    withContext(Dispatchers.IO) { response.use { it.body?.bytes() ?: EMPTY } }
                } catch (e: IOException) {
                    currentCoroutineContext().ensureActive()
                    throw mapIo(e, idle)
                }
                Attempt.Failed(RawResponse(statusOf(response), toHeaders(response.headers), bytes))
            }
        }
        val response = open ?: throw TungoConnectionException("The stream was not opened")
        val call = openCall ?: throw TungoConnectionException("The stream was not opened")
        try {
            val headers = toHeaders(response.headers)
            val events = eventFlow(call, response, idle, deserializer, kind, response.code, headers)
            return block(StreamResponse(response.code, headers, events))
        } finally {
            call.cancel()
            response.close()
        }
    }

    private fun <T> eventFlow(
        call: Call,
        response: Response,
        idle: Duration,
        deserializer: DeserializationStrategy<T>,
        kind: StreamKind<T>,
        status: Int,
        headers: ResponseHeaders,
    ): Flow<T> {
        val collected = AtomicBoolean(false)
        return flow {
            if (!collected.compareAndSet(false, true)) {
                throw IllegalStateException("The events of a stream can be collected only once")
            }
            val source = response.body?.source() ?: throw TungoConnectionException("The stream response has no body")
            val parser = SseParser()
            val pending = ArrayList<ServerSentEvent>()
            val chunk = ByteArray(8192)
            val finished = AtomicBoolean(false)
            coroutineScope {
                val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        awaitCancellation()
                    } finally {
                        if (!finished.get()) call.cancel()
                    }
                }
                try {
                    while (true) {
                        val count = try {
                            withContext(Dispatchers.IO) { source.read(chunk) }
                        } catch (e: IOException) {
                            currentCoroutineContext().ensureActive()
                            throw mapIo(e, idle)
                        }
                        if (count < 0) break
                        parser.feed(chunk, 0, count, pending)
                        for (event in pending) {
                            if (event.data.isEmpty()) continue // keep-alive (the "ping" event of the API)
                            if (!accepts(kind, event, status, headers)) continue
                            emit(decodeEvent(deserializer, kind, event, status, headers))
                        }
                        pending.clear()
                    }
                    parser.end(pending)
                    finished.set(true)
                } finally {
                    watcher.cancel()
                }
            }
        }
    }

    /**
     * Tells whether an event of a stream of one event type is to be decoded. An event named
     * "error" is not an item: it ends the stream with the typed API error.
     */
    private fun accepts(kind: StreamKind<*>, event: ServerSentEvent, status: Int, headers: ResponseHeaders): Boolean {
        val name = kind.eventName ?: return true
        if (event.event == ERROR_EVENT) throw Errors.fromStreamEvent(status, headers, event.data)
        return event.event == name || event.event == DEFAULT_EVENT
    }

    private fun <T> decodeEvent(
        deserializer: DeserializationStrategy<T>,
        kind: StreamKind<T>,
        event: ServerSentEvent,
        status: Int,
        headers: ResponseHeaders,
    ): T {
        val malformedError = kind.malformedError
        if (malformedError != null && event.event == ERROR_EVENT) {
            // An event the server calls "error" is an error whatever its data looks like.
            val json = Errors.parseObject(event.data) ?: JsonObject(mapOf("message" to JsonPrimitive(event.data)))
            if (json.stringOrNull("type") != ERROR_EVENT) return malformedError(json)
        }
        return try {
            TungoJson.decoder.decodeFromString(deserializer, event.data)
        } catch (e: SerializationException) {
            throw decodeFailure(status, headers, event.data, e)
        } catch (e: IllegalArgumentException) {
            throw decodeFailure(status, headers, event.data, e)
        }
    }

    private fun decodeFailure(status: Int, headers: ResponseHeaders, body: String, cause: Exception): TungoDecodeException {
        val requestId = headers["x-request-id"]
        val suffix = if (requestId != null) " [request id $requestId]" else ""
        return TungoDecodeException("The response (status $status)$suffix could not be decoded: ${firstLine(cause.message)}", cause, status, headers, body)
    }

    private class Prepared(val request: Request, val method: String, val hasIdempotencyKey: Boolean)

    private sealed class Attempt {
        class Done(val response: RawResponse) : Attempt()
        class Failed(val response: RawResponse) : Attempt()
    }

    private fun prepare(spec: RequestSpec, requestOptions: RequestOptions, accept: String): Prepared {
        val url = buildString {
            append(options.baseUrl).append(spec.path)
            val query = spec.query?.build().orEmpty()
            if (query.isNotEmpty()) append('?').append(query)
        }.toHttpUrlOrNull() ?: throw TungoValidationException("The request URL is not valid")

        val headers = Headers.Builder()
        try {
            headers["Accept"] = accept
            headers["User-Agent"] = TungoSdk.USER_AGENT
            for ((name, value) in options.defaultHeaders) headers[name] = value
            for ((name, value) in spec.headers) headers[name] = value
            for ((name, value) in requestOptions.headers) headers[name] = value
            // The key goes only to operations that require it; hooks and public endpoints never get it.
            headers.removeAll("x-api-key")
            if (spec.auth == AuthMode.KEY) {
                val key = options.apiKey?.takeIf { it.isNotEmpty() }
                    ?: throw TungoConfigException("No API key: set apiKey on the client or the environment variable TUNGO_API_KEY")
                headers["x-api-key"] = key
            } else {
                headers.removeAll("Authorization")
            }
            val idempotencyKey = requestOptions.idempotencyKey ?: if (spec.idempotent) UUID.randomUUID().toString() else null
            if (idempotencyKey != null) headers[IDEMPOTENCY_HEADER] = idempotencyKey
            val payload = spec.payload
            val repeatable = Retry.isRepeatable(spec.method, idempotencyKey != null)
            // A null media type keeps OkHttp from appending a charset to the Content-Type set here.
            fun bodyOf(bytes: ByteArray): RequestBody = if (repeatable) bytes.toRequestBody(null) else OneShotBody(bytes)
            val body: RequestBody? = when {
                payload != null -> {
                    headers["Content-Type"] = payload.contentType
                    bodyOf(payload.bytes)
                }
                requiresBody(spec.method) -> bodyOf(EMPTY)
                else -> null
            }
            if (payload != null && payload.contentType.toMediaTypeOrNull() == null) {
                throw TungoValidationException("The content type of the request body is not valid")
            }
            val request = Request.Builder().url(url).headers(headers.build()).method(spec.method, body).build()
            return Prepared(request, spec.method, idempotencyKey != null)
        } catch (e: IllegalArgumentException) {
            // OkHttp rejects header values with control characters; never echo the value.
            throw TungoValidationException("A request header contains characters that cannot be sent", null)
        }
    }

    private suspend fun withRetries(prepared: Prepared, requestOptions: RequestOptions, attempt: suspend () -> Attempt): RawResponse {
        val maxRetries = requestOptions.maxRetries ?: options.maxRetries
        val repeatable = Retry.isRepeatable(prepared.method, prepared.hasIdempotencyKey)
        var retries = 0
        while (true) {
            val canRetry = repeatable && retries < maxRetries
            val result = try {
                attempt()
            } catch (e: CancellationException) {
                throw e
            } catch (e: TungoConnectionException) {
                if (!canRetry) throw e
                null
            } catch (e: TungoTimeoutException) {
                if (!canRetry) throw e
                null
            }
            var delaySeconds = Retry.backoffSeconds(retries, hooks.random())
            when (result) {
                is Attempt.Done -> return result.response
                is Attempt.Failed -> {
                    val response = result.response
                    val error = Errors.fromResponse(response.status, response.headers, response.text(), hooks.nowMillis())
                    if (!canRetry || !Retry.isRetryableStatus(response.status)) throw error
                    val asked = error.retryAfterSeconds
                    if (asked != null) {
                        // The server asks for more patience than a client call should have: report it.
                        if (asked > Retry.MAX_SERVER_DELAY_SECONDS) throw error
                        // A zero delay or a date that already passed says nothing useful: the
                        // computed backoff applies instead of an immediate retry.
                        if (asked > 0.0) delaySeconds = asked
                    }
                }
                null -> Unit
            }
            sleep(Math.ceil(delaySeconds * 1000.0).toLong())
            retries++
        }
    }

    private fun mapIo(e: IOException, limit: Duration): TungoException = mapIo(e, "The request timed out (limit $limit)")

    private fun mapIo(e: IOException, timeoutMessage: String): TungoException {
        val timedOut = e is SocketTimeoutException || (e is InterruptedIOException && e.message == "timeout")
        return if (timedOut) {
            TungoTimeoutException(timeoutMessage, e)
        } else {
            TungoConnectionException("Connection error: ${e.javaClass.simpleName}: ${firstLine(e.message)}", e)
        }
    }

    internal companion object {
        val DEFAULT_TIMEOUT: Duration = 60.seconds
        val LONG_RUNNING_TIMEOUT: Duration = 600.seconds
        const val IDEMPOTENCY_HEADER: String = "Idempotency-Key"
        const val JSON_MEDIA_TYPE: String = "application/json"
        private const val ACCEPT_JSON: String = "application/json"
        private const val ACCEPT_SSE: String = "text/event-stream"
        private const val ERROR_EVENT: String = "error"
        private const val DEFAULT_EVENT: String = "message"

        /** True for the media type text/event-stream, whatever its parameters and letter case. */
        private fun isEventStream(contentType: String?): Boolean =
            contentType?.substringBefore(';')?.trim().equals(ACCEPT_SSE, ignoreCase = true)
        private val EMPTY = ByteArray(0)

        private fun requiresBody(method: String): Boolean = method == "POST" || method == "PUT" || method == "PATCH"

        private fun firstLine(text: String?): String = text?.lineSequence()?.firstOrNull()?.take(300) ?: "no details"

        private const val RETRY_AFTER: String = "Retry-After"
        private const val HIDDEN_RETRY_AFTER: String = "X-Tungo-Sdk-Retry-After"
        private const val HIDDEN_STATUS: String = "X-Tungo-Sdk-Status"
        private const val DISGUISED_STATUS: Int = 599

        /** The status the server sent (see the network interceptor of the engine). */
        private fun statusOf(response: Response): Int =
            if (response.code == DISGUISED_STATUS) response.header(HIDDEN_STATUS)?.toIntOrNull() ?: response.code else response.code

        fun toHeaders(headers: Headers): ResponseHeaders {
            val entries = ArrayList<Pair<String, String>>(headers.size)
            for (i in 0 until headers.size) {
                val name = headers.name(i)
                when {
                    name.equals(HIDDEN_STATUS, ignoreCase = true) -> Unit
                    name.equals(HIDDEN_RETRY_AFTER, ignoreCase = true) -> entries.add(RETRY_AFTER to headers.value(i))
                    else -> entries.add(name to headers.value(i))
                }
            }
            return ResponseHeaders(entries)
        }

        /** The body of a request that must be sent at most once. */
        private class OneShotBody(private val bytes: ByteArray) : RequestBody() {
            override fun contentType(): MediaType? = null

            override fun contentLength(): Long = bytes.size.toLong()

            override fun isOneShot(): Boolean = true

            override fun writeTo(sink: BufferedSink) {
                sink.write(bytes)
            }
        }

        /** Runs the call and reads the whole body on OkHttp's thread; cancelling the coroutine cancels the call. */
        private suspend fun Call.awaitBuffered(): RawResponse = suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { cancel() }
            enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val raw = try {
                        response.use { RawResponse(statusOf(it), toHeaders(it.headers), it.body?.bytes() ?: EMPTY) }
                    } catch (e: IOException) {
                        continuation.resumeWithException(e)
                        return
                    }
                    continuation.resume(raw)
                }
            })
        }

        /** Runs the call until the response headers arrive; the body stays open for streaming. */
        private suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { cancel() }
            enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    continuation.resume(response) { _, unused, _ -> unused.close() }
                }
            })
        }
    }
}
