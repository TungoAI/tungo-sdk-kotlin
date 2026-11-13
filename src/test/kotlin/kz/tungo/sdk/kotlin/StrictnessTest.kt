package kz.tungo.sdk.kotlin

import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kz.tungo.sdk.kotlin.errors.InternalServerException
import kz.tungo.sdk.kotlin.errors.LimitException
import kz.tungo.sdk.kotlin.errors.NotFoundException
import kz.tungo.sdk.kotlin.errors.ServiceUnavailableException
import kz.tungo.sdk.kotlin.errors.TungoApiException
import kz.tungo.sdk.kotlin.errors.TungoDecodeException
import kz.tungo.sdk.kotlin.errors.TungoPaginationException
import kz.tungo.sdk.kotlin.errors.TungoTimeoutException
import kz.tungo.sdk.kotlin.errors.TungoWaitTimeoutException
import kz.tungo.sdk.kotlin.internal.TungoJson
import kz.tungo.sdk.kotlin.models.ChatAnswerRequest
import kz.tungo.sdk.kotlin.models.ChatEvent
import kz.tungo.sdk.kotlin.models.ChatEventDone
import kz.tungo.sdk.kotlin.models.ChatEventError
import kz.tungo.sdk.kotlin.models.ChatMessageRequest
import kz.tungo.sdk.kotlin.resources.ChatStreamAccumulator
import kz.tungo.sdk.kotlin.resources.accumulate
import kz.tungo.sdk.kotlin.resources.streamTurn
import kz.tungo.sdk.kotlin.resources.waitForRun
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy

/**
 * A reply that is cut off, mistyped or reports a failure must fail loudly instead of looking like
 * a valid, finished answer; and a harmless addition of the API must not fail at all.
 */
class StrictnessTest {
    private val question = ChatMessageRequest(message = "q")
    private val text = "event: text\ndata: {\"type\":\"text\",\"delta\":\"Часть ответа\"}\n\n"
    private val done = "event: done\ndata: {\"type\":\"done\",\"stop_reason\":\"end_turn\"}\n\n"
    private val feedItem =
        """{"actions":[],"at":"2026-01-02T03:04:05Z","cursor":"c1","id":"f1","section":"proposals","section_title":"Предложения","title":"Счёт"}"""

    private fun sse(body: String, contentType: String? = "text/event-stream"): MockResponse {
        val response = MockResponse().setResponseCode(200).setBody(body)
        if (contentType != null) response.setHeader("Content-Type", contentType)
        return response
    }

    // ---- a chat stream that ends without "done" ----

    @Test
    fun aChatStreamThatEndsWithoutDoneIsNotAFinishedTurn() = runBlocking<Unit> {
        MockApi().use { api ->
            val client = api.client()
            api.enqueueSse(text, "x-request-id" to "req_cut")
            val error = assertFailsWith<TungoDecodeException> { client.chat.streamTurn(question) }
            assertTrue("ended before completion" in error.message.orEmpty(), error.message)
            assertEquals("req_cut", error.requestId)
            assertEquals(200, error.status)

            // The raw events simply end; the accumulator knows the turn is not complete.
            api.enqueueSse(text)
            val accumulator = ChatStreamAccumulator()
            client.chat.createStream(question).collect { accumulator.add(it) }
            assertEquals(1, accumulator.eventCount)
            assertFalse(accumulator.isComplete)
            assertEquals("Часть ответа", accumulator.text)
            assertFailsWith<TungoDecodeException> { accumulator.toTurn(null, null) }

            api.enqueueSse(text + done)
            val turn = client.chat.streamTurn(question)
            assertEquals("Часть ответа", turn.text)
            assertEquals("end_turn", turn.stopReason)
        }
    }

    // ---- a success reply that is not an event stream ----

    @Test
    fun aSuccessReplyThatIsNotAnEventStreamIsADecodeError() = runBlocking<Unit> {
        MockApi().use { api ->
            val client = api.client()
            api.server.enqueue(sse("""{"answer":"not a stream"}""", "application/json").setHeader("x-request-id", "req_json"))
            val json = assertFailsWith<TungoDecodeException> { client.chat.createStream(question).toList() }
            assertEquals(200, json.status)
            assertEquals("""{"answer":"not a stream"}""", json.body)
            assertEquals("req_json", json.requestId)
            assertTrue("application/json" in json.message.orEmpty(), json.message)

            api.server.enqueue(sse("<html>proxy</html>", "text/html"))
            assertFailsWith<TungoDecodeException> { client.feed.listEvents().toList() }

            api.server.enqueue(sse(text + done, null))
            val untyped = assertFailsWith<TungoDecodeException> { client.chat.withRawResponse.createStream(question) { it.accumulate() } }
            assertTrue("no Content-Type" in untyped.message.orEmpty(), untyped.message)
            assertEquals(3, api.server.requestCount, "a decode error is not retried")

            // The media type is compared without its parameters and without regard to case.
            api.server.enqueue(sse(text + done, "Text/Event-Stream; charset=utf-8"))
            assertEquals(2, client.chat.createStream(question).toList().size)
        }
    }

    // ---- Retry-After that asks for no delay ----

    @Test
    fun aZeroOrPastRetryAfterMeansTheNormalBackoff() = runBlocking<Unit> {
        val headers = listOf(
            "Retry-After" to "0",
            "Retry-After" to "Mon, 09 Nov 2020 14:59:00 GMT",
            "retry-after-ms" to "0",
        )
        for ((name, value) in headers) {
            MockApi().use { api ->
                api.server.enqueue(MockResponse().setResponseCode(503).setHeader(name, value))
                api.enqueueJson(Fixtures.COMPANY)
                assertEquals("co_1", api.client().companies.get().id)
                assertEquals(2, api.server.requestCount, "$name: $value")
                assertEquals(listOf(500L), api.sleeps.toList(), "$name: $value is not an immediate retry")
            }
        }
        MockApi().use { api ->
            api.server.enqueue(MockResponse().setResponseCode(503).setHeader("retry-after-ms", "40"))
            api.enqueueJson(Fixtures.COMPANY)
            api.client().companies.get()
            assertEquals(listOf(40L), api.sleeps.toList(), "a positive delay of the server is used as it is")
        }
    }

    // ---- a cycle of cursors ----

    @Test
    fun aCursorCycleStopsThePagination() = runBlocking<Unit> {
        MockApi().use { api ->
            api.enqueueJson(Fixtures.dialogs("A", "d1"))
            api.enqueueJson(Fixtures.dialogs("B", "d2"))
            api.enqueueJson(Fixtures.dialogs("A", "d3"))
            api.enqueueJson(Fixtures.dialogs("B", "d4"))
            val seen = ArrayList<String>()
            assertFailsWith<TungoPaginationException> { api.client().chat.listAll().collect { seen.add(it.id) } }
            assertEquals(listOf("d1", "d2", "d3"), seen)
            assertEquals(3, api.server.requestCount, "the cycle A, B, A is cut when A comes back")
        }
    }

    // ---- error events of streams ----

    @Test
    fun anErrorEventOfTheFeedIsTheTypedApiError() = runBlocking<Unit> {
        MockApi().use { api ->
            val client = api.client()
            api.enqueueSse(
                "event: feed\ndata: $feedItem\n\n" +
                    "event: error\ndata: {\"error\":\"feed_failed\",\"message\":\"Лента недоступна\"}\n\n",
                "x-request-id" to "req_feed",
            )
            val seen = ArrayList<String>()
            val failed = assertFailsWith<TungoApiException> { client.feed.listEvents().collect { seen.add(it.id) } }
            assertEquals(listOf("f1"), seen)
            assertIs<InternalServerException>(failed)
            assertEquals("feed_failed", failed.code)
            assertEquals("Лента недоступна", failed.apiMessage)
            assertEquals("api_error", failed.type)
            assertEquals("req_feed", failed.requestId)
            assertEquals(200, failed.status)

            // The error envelope of the API is understood as well.
            api.enqueueSse(
                "event: error\ndata: {\"type\":\"error\",\"error\":{\"type\":\"limit_error\",\"code\":\"budget\",\"message\":\"Лимит\"}," +
                    "\"request_id\":\"req_env\"}\n\n",
            )
            val limited = assertFailsWith<LimitException> { client.feed.listEvents().toList() }
            assertEquals("budget", limited.code)
            assertEquals("req_env", limited.requestId)

            // Events of other names are not feed items: they are skipped, not decoded.
            api.enqueueSse("event: hologram\ndata: {\"x\":1}\n\nevent: feed\ndata: $feedItem\n\ndata: $feedItem\n\n")
            assertEquals(listOf("f1", "f1"), client.feed.listEvents().toList().map { it.id })
        }
    }

    @Test
    fun anyErrorEventOfAChatTurnMakesTheHelperThrow() = runBlocking<Unit> {
        MockApi().use { api ->
            val client = api.client()
            // The contract shape.
            val typed = "event: error\ndata: {\"type\":\"error\",\"error\":{\"type\":\"not_found_error\",\"code\":\"dialog_not_found\"," +
                "\"message\":\"Нет\"},\"request_id\":\"req_1\"}\n\n"
            api.enqueueSse(text + typed + done)
            assertEquals("dialog_not_found", assertFailsWith<NotFoundException> { client.chat.streamTurn(question) }.code)

            // The error tag with a payload this version does not understand.
            val odd = "event: error\ndata: {\"type\":\"error\",\"error\":\"turn_failed\",\"message\":\"Сбой\"}\n\n"
            api.enqueueSse(text + odd + done)
            val failed = assertFailsWith<TungoApiException> { client.chat.streamTurn(question) }
            assertEquals("turn_failed", failed.code)
            assertEquals("Сбой", failed.apiMessage)

            // An event the server names "error" whose data carries no tag at all.
            val untagged = "event: error\ndata: {\"error\":{\"type\":\"limit_error\",\"code\":\"budget\",\"message\":\"Лимит\"}}\n\n"
            api.enqueueSse(text + untagged + done)
            assertEquals("budget", assertFailsWith<LimitException> { client.chat.streamTurn(question) }.code)

            // And one whose data is not JSON.
            api.enqueueSse(text + "event: error\ndata: upstream timeout\n\n" + done)
            assertEquals("upstream timeout", assertFailsWith<TungoApiException> { client.chat.streamTurn(question) }.apiMessage)

            // The raw flow still delivers such events instead of failing.
            api.enqueueSse(text + typed + odd + untagged + done)
            val events = client.chat.createStream(question).toList()
            assertEquals(listOf("text", "error", "error", "error", "done"), events.map { it.type })
            assertIs<ChatEventError>(events[1])
            assertIs<ChatEvent.Unknown>(events[2])
            assertIs<ChatEvent.Unknown>(events[3])
        }
    }

    // ---- a flow collected twice ----

    @Test
    fun collectingAStreamTwiceRepeatsTheSameIdempotencyKey() = runBlocking<Unit> {
        MockApi().use { api ->
            val client = api.client()
            repeat(5) { api.enqueueSse(text + done) }
            val flow = client.chat.createStream(question)
            assertEquals(0, api.server.requestCount, "nothing is sent before the flow is collected")
            flow.toList()
            flow.toList()
            val first = assertNotNull(api.take().getHeader("Idempotency-Key"))
            val second = assertNotNull(api.take().getHeader("Idempotency-Key"))
            assertEquals(first, second, "the second collection is the same request, not a new turn")

            // Another flow is another request.
            client.chat.createStream(question).toList()
            assertNotEquals(first, api.take().getHeader("Idempotency-Key"))

            // A key of the caller is kept.
            val mine = client.chat.createStream(question, requestOptions = RequestOptions { idempotencyKey = "turn-42" })
            mine.toList()
            mine.toList()
            assertEquals("turn-42", api.take().getHeader("Idempotency-Key"))
            assertEquals("turn-42", api.take().getHeader("Idempotency-Key"))

            // An operation without an idempotency key gets none.
            api.enqueueSse(text + done)
            client.chat.answerStream("d_1", ChatAnswerRequest(answers = emptyList(), choiceId = "ch_1")).toList()
            assertNull(api.take().getHeader("Idempotency-Key"))
        }
    }

    // ---- additions of the API ----

    @Test
    fun additionsOfTheApiDoNotFailDecoding() = runBlocking<Unit> {
        // An unknown property (nested too), an explicit null for an optional property.
        val event = TungoJson.decoder.decodeFromString(
            ChatEvent.serializer(),
            """{"type":"done","answer":null,"stop_reason":"end_turn","usage":null,"new_field":{"a":[1,{"b":null}]}}""",
        )
        val decoded = assertIs<ChatEventDone>(event)
        assertNull(decoded.answer)
        assertEquals("end_turn", decoded.stopReason)
        // An unknown variant of a union.
        assertIs<ChatEvent.Unknown>(TungoJson.decoder.decodeFromString(ChatEvent.serializer(), """{"type":"hologram","x":1}"""))

        MockApi().use { api ->
            val client = api.client()
            // Unknown properties and an unknown enum value in a response.
            api.enqueueJson(Fixtures.COMPANY.replace("\"status\":\"active\"", "\"status\":\"frozen_by_a_newer_api\",\"extra\":null"))
            val company = client.companies.get()
            assertEquals("frozen_by_a_newer_api", company.status.value)
            assertFalse(company.status.isKnown)

            // What stays strict: a required property that is missing, and a value of the wrong type.
            api.enqueueJson(Fixtures.COMPANY.replace("\"name\":\"ТОО Ромашка\",", ""))
            val missing = assertFailsWith<TungoDecodeException> { client.companies.get() }
            assertTrue("name" in missing.message.orEmpty(), missing.message)
            api.enqueueJson(Fixtures.COMPANY.replace("\"name\":\"ТОО Ромашка\"", "\"name\":5"))
            assertFailsWith<TungoDecodeException> { client.companies.get() }
            api.enqueueJson(Fixtures.TOKEN_COUNT.replace("\"input_tokens\":1200", "\"input_tokens\":12.7"))
            assertFailsWith<TungoDecodeException> { client.chat.countTokens(kz.tungo.sdk.kotlin.models.ChatTokenCountRequest(message = "q")) }
        }
    }

    // ---- timeouts ----

    @Test
    fun aWaitDeadlineHasItsOwnExceptionType() = runBlocking<Unit> {
        MockApi().use { api ->
            repeat(20) { api.enqueueJson(Fixtures.run("running")) }
            val error = assertFailsWith<TungoWaitTimeoutException> { api.client().automations.waitForRun("run_1", timeout = 10.seconds) }
            assertIs<TungoTimeoutException>(error)
            assertTrue("run_1" in error.message.orEmpty(), error.message)
        }
    }

    @Test
    fun theConnectTimeoutDefaultsToThirtySeconds() {
        TungoClient { apiKey = MockApi.KEY }.use { client ->
            assertEquals(30.seconds, client.options.connectTimeout)
        }
    }

    @Test
    fun aStreamThatDoesNotOpenReportsTheLimitsThatApply() = runBlocking<Unit> {
        MockApi().use { api ->
            api.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val client = api.client {
                maxRetries = 0
                streamIdleTimeout = 300.milliseconds
            }
            val error = assertFailsWith<TungoTimeoutException> { client.chat.listEvents("d_1").toList() }
            val message = error.message.orEmpty()
            assertTrue("did not open in time" in message, message)
            assertTrue("idle timeout 300ms" in message, message)
            assertTrue("connect timeout 30s" in message, message)
            assertFalse(error is TungoWaitTimeoutException)
        }
    }

    // ---- cancelling, retries of hooks ----

    @Test
    fun cancellingTheCollectorMidStreamClosesTheConnectionAndIsNotWrapped() = runBlocking<Unit> {
        val first = "data: {\"type\":\"text\",\"delta\":\"раз\"}\n\n"
        SseSocketServer(first, "data: {\"type\":\"done\"}\n\n").use { server ->
            TungoClient { apiKey = MockApi.KEY; baseUrl = server.baseUrl; companyId = "co_1" }.use { client ->
                val started = CompletableDeferred<Unit>()
                val seen = ArrayList<String>()
                var failure: Throwable? = null
                val job = launch(Dispatchers.Default) {
                    try {
                        client.chat.listEvents("d_1").collect { event ->
                            seen.add(event.type)
                            started.complete(Unit)
                        }
                    } catch (e: Throwable) {
                        failure = e
                        throw e
                    }
                }
                withTimeout(10_000) { started.await() }
                job.cancel()
                withTimeout(10_000) { job.join() }
                assertTrue(job.isCancelled)
                assertIs<kotlinx.coroutines.CancellationException>(failure)
                assertEquals(listOf("text"), seen)
                assertTrue(server.clientClosed.await(10, TimeUnit.SECONDS), "cancelling closed the connection")
                assertFalse(server.restSent)
            }
        }
    }

    @Test
    fun sendHookIsRetriedWithTheSameKeyBodyAndSignature() = runBlocking<Unit> {
        MockApi().use { api ->
            val client = api.client()
            api.server.enqueue(MockResponse().setResponseCode(503))
            api.server.enqueue(MockResponse().setResponseCode(502))
            api.enqueueJson(Fixtures.HOOK_ACCEPTED, 202)
            val accepted = client.sendHook("tok", "{\"n\":1}", secret = "s3cret", eventId = "evt_1")
            assertTrue(accepted.accepted)
            assertEquals(3, api.server.requestCount)
            assertEquals(listOf(500L, 1000L), api.sleeps.toList())
            val attempts = List(3) { api.take() }
            assertEquals(1, attempts.map { assertNotNull(it.getHeader("Idempotency-Key")) }.toSet().size)
            assertEquals(1, attempts.map { assertNotNull(it.getHeader("X-Signature")) }.toSet().size)
            assertEquals(setOf("evt_1"), attempts.map { it.getHeader("X-Event-Id") }.toSet())
            assertEquals(setOf("{\"n\":1}"), attempts.map { it.body.readUtf8() }.toSet())
            assertTrue(attempts.all { it.getHeader("x-api-key") == null })

            // The retries stop at the limit, and a refusal is final.
            repeat(3) { api.server.enqueue(MockResponse().setResponseCode(503)) }
            assertFailsWith<ServiceUnavailableException> { client.sendHook("tok", "{}") }
            assertEquals(6, api.server.requestCount)
            api.enqueueJson(Fixtures.error("not_found_error", "Нет такого хука", "hook_not_found"), 404)
            assertFailsWith<NotFoundException> { client.sendHook("tok", "{}") }
            assertEquals(7, api.server.requestCount)
        }
    }
}
