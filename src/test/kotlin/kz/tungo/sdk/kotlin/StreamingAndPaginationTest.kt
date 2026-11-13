package kz.tungo.sdk.kotlin

import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kz.tungo.sdk.kotlin.errors.LimitException
import kz.tungo.sdk.kotlin.errors.PermissionDeniedException
import kz.tungo.sdk.kotlin.errors.ServiceUnavailableException
import kz.tungo.sdk.kotlin.errors.TungoApiException
import kz.tungo.sdk.kotlin.errors.TungoDecodeException
import kz.tungo.sdk.kotlin.errors.TungoPaginationException
import kz.tungo.sdk.kotlin.errors.TungoTimeoutException
import kz.tungo.sdk.kotlin.models.ChatAnswerRequest
import kz.tungo.sdk.kotlin.models.ChatEvent
import kz.tungo.sdk.kotlin.models.ChatEventDone
import kz.tungo.sdk.kotlin.models.ChatEventText
import kz.tungo.sdk.kotlin.models.ChatMessageRequest
import kz.tungo.sdk.kotlin.resources.accumulate
import kz.tungo.sdk.kotlin.resources.dialogId
import kz.tungo.sdk.kotlin.resources.streamTurn
import kz.tungo.sdk.kotlin.resources.turnNo
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy

/** Event streams and cursor pagination against a local mock of the API. */
class StreamingAndPaginationTest {
    // ---- pagination ----

    @Test
    fun pagesAreTypedAndNextKeepsTheFilters() = runBlocking<Unit> {
        MockApi().use { api ->
            api.enqueueJson(Fixtures.dialogs("c 2/+", "d1", "d2"), 200, "x-request-id" to "req_p1")
            api.enqueueJson(Fixtures.dialogs(null, "d3"))
            val first = api.client().chat.list(limit = 2)
            assertEquals(listOf("d1", "d2"), first.data.map { it.id })
            assertEquals("c 2/+", first.nextCursor)
            assertTrue(first.hasNext)
            assertEquals("req_p1", first.response.requestId)
            assertEquals(2, first.body.data.size)
            val second = assertNotNull(first.next())
            assertEquals(listOf("d3"), second.data.map { it.id })
            assertFalse(second.hasNext)
            assertNull(second.next())
            assertEquals("/v1/companies/co_1/chat?limit=2", api.take().path)
            assertEquals("/v1/companies/co_1/chat?cursor=c%202%2F%2B&limit=2", api.take().path)
            assertEquals(2, api.server.requestCount)
        }
    }

    @Test
    fun theFlowWalksEveryPageLazily() = runBlocking<Unit> {
        MockApi().use { api ->
            api.enqueueJson(Fixtures.dialogs("c2", "d1", "d2"))
            api.enqueueJson(Fixtures.dialogs("c3", "d3"))
            api.enqueueJson(Fixtures.dialogs("", "d4"))
            val flow = api.client().chat.listAll(limit = 2)
            assertEquals(0, api.server.requestCount, "a cold flow sends nothing until it is collected")
            assertEquals(listOf("d1", "d2", "d3", "d4"), flow.toList().map { it.id })
            assertEquals(3, api.server.requestCount)
            api.take()
            assertEquals("/v1/companies/co_1/chat?cursor=c2&limit=2", api.take().path)
            assertEquals("/v1/companies/co_1/chat?cursor=c3&limit=2", api.take().path)
        }
    }

    @Test
    fun stoppingEarlyRequestsNoFurtherPages() = runBlocking<Unit> {
        MockApi().use { api ->
            api.enqueueJson(Fixtures.dialogs("c2", "d1", "d2"))
            api.enqueueJson(Fixtures.dialogs("c3", "d3"))
            assertEquals(listOf("d1", "d2"), api.client().chat.listAll().take(2).toList().map { it.id })
            assertEquals(1, api.server.requestCount)
        }
    }

    @Test
    fun aRepeatedCursorStopsTheWalkWithATypedError() = runBlocking<Unit> {
        MockApi().use { api ->
            api.enqueueJson(Fixtures.dialogs("same", "d1"))
            api.enqueueJson(Fixtures.dialogs("same", "d2"))
            val seen = ArrayList<String>()
            assertFailsWith<TungoPaginationException> { api.client().chat.listAll().collect { seen.add(it.id) } }
            assertEquals(listOf("d1", "d2"), seen)
            assertEquals(2, api.server.requestCount)
        }
    }

    @Test
    fun anErrorOnALaterPageIsTyped() = runBlocking<Unit> {
        MockApi().use { api ->
            api.enqueueJson(Fixtures.dialogs("c2", "d1"))
            api.server.enqueue(MockResponse().setResponseCode(403).setBody(Fixtures.error("permission_error", "нельзя", "missing_permission")))
            val seen = ArrayList<String>()
            val error = assertFailsWith<PermissionDeniedException> { api.client().chat.listAll().collect { seen.add(it.id) } }
            assertEquals("missing_permission", error.code)
            assertEquals(listOf("d1"), seen)
        }
    }

    // ---- streaming ----

    @Test
    fun aStreamYieldsTypedEventsAndSkipsKeepAlives() = runBlocking<Unit> {
        MockApi().use { api ->
            api.enqueueSse(Fixtures.SSE_TWO_TEXTS, "X-Dialog-Id" to "d_1", "X-Turn-No" to "4", "x-request-id" to "req_s")
            val events = api.client().chat.createStream(ChatMessageRequest(message = "q")).toList()
            assertEquals(listOf("text", "text", "brand_new", "done"), events.map { it.type })
            assertEquals("Привет, ", assertIs<ChatEventText>(events[0]).delta)
            assertIs<ChatEvent.Unknown>(events[2])
            assertEquals(185L, assertIs<ChatEventDone>(events[3]).costTiyn)
            val request = api.take()
            assertEquals("POST", request.method)
            assertEquals("text/event-stream", request.getHeader("Accept"))
            assertEquals(MockApi.KEY, request.getHeader("x-api-key"))
            assertNotNull(request.getHeader("Idempotency-Key"))
            assertEquals("""{"message":"q","stream":true}""", request.body.readUtf8())
        }
    }

    @Test
    fun theRawStreamExposesHeadersAndAccumulates() = runBlocking<Unit> {
        MockApi().use { api ->
            api.enqueueSse(Fixtures.SSE_TWO_TEXTS, "X-Dialog-Id" to "d_1", "X-Turn-No" to "4", "x-request-id" to "req_s")
            val seen = ArrayList<String>()
            val turn = api.client().chat.withRawResponse.createStream(ChatMessageRequest(message = "q")) { stream ->
                assertEquals(200, stream.status)
                assertEquals("req_s", stream.requestId)
                assertEquals("d_1", stream.dialogId)
                assertEquals(4, stream.turnNo)
                stream.accumulate { seen.add(it.type) }
            }
            assertEquals("Привет, мир", turn.text)
            assertEquals("end_turn", turn.stopReason)
            assertEquals(185L, turn.costTiyn)
            assertEquals("d_1", turn.dialogId)
            assertEquals(4, turn.turnNo)
            assertEquals(4, turn.eventCount)
            assertEquals(4, seen.size)
        }
    }

    @Test
    fun everyStreamingOperationSpeaksSse() = runBlocking<Unit> {
        MockApi().use { api ->
            val client = api.client()
            repeat(2) { api.enqueueSse(Fixtures.SSE_TWO_TEXTS) }
            assertEquals(4, client.chat.answerStream("d 1", ChatAnswerRequest(answers = emptyList(), choiceId = "ch_1")).toList().size)
            assertEquals(4, client.chat.listEvents("d_1", turn = 3).toList().size)
            val answer = api.take()
            assertEquals("POST", answer.method)
            assertEquals("/v1/companies/co_1/chat/d%201/answer", answer.path)
            assertEquals("text/event-stream", answer.getHeader("Accept"))
            assertNull(answer.getHeader("Idempotency-Key"))
            assertTrue("\"stream\":true" in answer.body.readUtf8())
            val replay = api.take()
            assertEquals("GET", replay.method)
            assertEquals("/v1/companies/co_1/chat/d_1/events?turn=3", replay.path)
            assertEquals("text/event-stream", replay.getHeader("Accept"))

            // Streams of other event types decode into their own models; one broken event is a typed error.
            val item = """{"actions":[],"at":"2026-01-02T03:04:05Z","cursor":"c1","id":"f1","section":"proposals","section_title":"Предложения","title":"Счёт"}"""
            api.server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: $item\n\ndata: $item\n\n"))
            val items = client.feed.listEvents(once = true).toList()
            assertEquals(listOf("f1", "f1"), items.map { it.id })
            val feed = api.take()
            assertEquals("text/event-stream", feed.getHeader("Accept"))
            assertTrue(feed.path.orEmpty().startsWith("/v1/companies/co_1/feed/"), feed.path)
            assertTrue("once=1" in feed.path.orEmpty(), feed.path)

            api.server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: {not json\n\n"))
            assertFailsWith<TungoDecodeException> { client.batches.listEvents("b_1").toList() }
            assertEquals("/v1/companies/co_1/batch/b_1/events", api.take().path)
        }
    }

    @Test
    fun aStreamThatFailsToOpenRaisesTheTypedError() = runBlocking<Unit> {
        MockApi().use { api ->
            val body = Fixtures.error("limit_error", "Лимит исчерпан", "plan_limit")
            api.enqueueJson(body, 429, "Retry-After" to "3600", "x-request-id" to "req_limit")
            val error = assertFailsWith<LimitException> { api.client().chat.createStream(ChatMessageRequest(message = "q")).toList() }
            assertEquals(429, error.status)
            assertEquals("plan_limit", error.code)
            assertEquals("Лимит исчерпан", error.apiMessage)
            assertEquals("req_limit", error.requestId)
            assertEquals(body, error.body)
            assertEquals(1, api.server.requestCount)
        }
    }

    @Test
    fun openingAStreamIsRetriedLikeAnyRequest() = runBlocking<Unit> {
        MockApi().use { api ->
            api.server.enqueue(MockResponse().setResponseCode(503))
            api.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            api.enqueueSse(Fixtures.SSE_TWO_TEXTS)
            assertEquals(4, api.client().chat.createStream(ChatMessageRequest(message = "q")).toList().size)
            assertEquals(3, api.server.requestCount)
            val keys = List(3) { api.take().getHeader("Idempotency-Key") }
            assertEquals(1, keys.toSet().size)
            // answer has no idempotency key: a failed attempt is final.
            api.server.enqueue(MockResponse().setResponseCode(503))
            assertFailsWith<ServiceUnavailableException> { api.client().chat.answerStream("d_1", ChatAnswerRequest(answers = emptyList(), choiceId = "ch_1")).toList() }
            assertEquals(4, api.server.requestCount)
        }
    }

    @Test
    fun anErrorEventBecomesATypedExceptionInTheHelper() = runBlocking<Unit> {
        MockApi().use { api ->
            val raw = "data: {\"type\":\"text\",\"delta\":\"Начало\"}\n\n" +
                "data: {\"type\":\"error\",\"error\":{\"type\":\"limit_error\",\"code\":\"budget_exceeded\",\"message\":\"Бюджет\",\"resets_at\":\"2026-12-01\"}," +
                "\"request_id\":\"req_ev\"}\n\n"
            api.enqueueSse(raw)
            val error = assertFailsWith<LimitException> { api.client().chat.streamTurn(ChatMessageRequest(message = "q")) }
            assertEquals("budget_exceeded", error.code)
            assertEquals("Бюджет", error.apiMessage)
            assertEquals("req_ev", error.requestId)
            assertTrue("resets_at" in error.extra)
            assertIs<TungoApiException>(error)
        }
    }

    @Test
    fun eventsArriveOneByOneWhileTheStreamIsOpen() = runBlocking<Unit> {
        val first = "data: {\"type\":\"text\",\"delta\":\"раз\"}\n\n"
        val rest = "data: {\"type\":\"text\",\"delta\":\"два\"}\n\ndata: {\"type\":\"done\"}\n\n"
        SseSocketServer(first, rest).use { server ->
            TungoClient { apiKey = MockApi.KEY; baseUrl = server.baseUrl; companyId = "co_1" }.use { client ->
                val seen = ArrayList<String>()
                client.chat.listEvents("d_1").collect { event ->
                    if (seen.isEmpty()) {
                        assertFalse(server.restSent, "the first event is delivered before the server sends the rest")
                        server.release()
                    }
                    seen.add(event.type)
                }
                assertEquals(listOf("text", "text", "done"), seen)
            }
        }
    }

    @Test
    fun leavingAStreamEarlyClosesTheConnection() = runBlocking<Unit> {
        val first = "data: {\"type\":\"text\",\"delta\":\"раз\"}\n\n"
        SseSocketServer(first, "data: {\"type\":\"done\"}\n\n").use { server ->
            TungoClient { apiKey = MockApi.KEY; baseUrl = server.baseUrl; companyId = "co_1" }.use { client ->
                val event = client.chat.listEvents("d_1").first()
                assertEquals("text", event.type)
                assertTrue(server.clientClosed.await(10, TimeUnit.SECONDS), "the client closed the connection")
                assertFalse(server.restSent)
            }
        }
    }

    @Test
    fun aSilentStreamFailsWithTheIdleTimeout() = runBlocking<Unit> {
        val first = "data: {\"type\":\"text\",\"delta\":\"раз\"}\n\n"
        SseSocketServer(first, "").use { server ->
            TungoClient { apiKey = MockApi.KEY; baseUrl = server.baseUrl; companyId = "co_1"; streamIdleTimeout = 300.milliseconds }.use { client ->
                val seen = ArrayList<String>()
                assertFailsWith<TungoTimeoutException> { client.chat.listEvents("d_1").collect { seen.add(it.type) } }
                assertEquals(listOf("text"), seen)
            }
        }
    }
}
