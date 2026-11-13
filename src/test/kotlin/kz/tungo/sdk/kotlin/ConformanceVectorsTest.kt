package kz.tungo.sdk.kotlin

import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kz.tungo.sdk.kotlin.errors.AuthenticationException
import kz.tungo.sdk.kotlin.errors.ConflictException
import kz.tungo.sdk.kotlin.errors.GoneException
import kz.tungo.sdk.kotlin.errors.InternalServerException
import kz.tungo.sdk.kotlin.errors.InvalidRequestException
import kz.tungo.sdk.kotlin.errors.LimitException
import kz.tungo.sdk.kotlin.errors.NotFoundException
import kz.tungo.sdk.kotlin.errors.PaymentRequiredException
import kz.tungo.sdk.kotlin.errors.PermissionDeniedException
import kz.tungo.sdk.kotlin.errors.ServiceUnavailableException
import kz.tungo.sdk.kotlin.errors.TungoApiException
import kz.tungo.sdk.kotlin.errors.TungoValidationException
import kz.tungo.sdk.kotlin.errors.WebhookVerificationException
import kz.tungo.sdk.kotlin.internal.Errors
import kz.tungo.sdk.kotlin.internal.QueryBuilder
import kz.tungo.sdk.kotlin.internal.Retry
import kz.tungo.sdk.kotlin.internal.SseParser
import kz.tungo.sdk.kotlin.internal.pathSegment
import kz.tungo.sdk.kotlin.models.ChatEvent
import kz.tungo.sdk.kotlin.models.ChatMessageRequest
import kz.tungo.sdk.kotlin.resources.ChatStreamAccumulator
import kz.tungo.sdk.kotlin.resources.dialogId
import kz.tungo.sdk.kotlin.resources.streamTurn
import kz.tungo.sdk.kotlin.resources.turnNo
import kz.tungo.sdk.kotlin.streaming.ServerSentEvent
import kz.tungo.sdk.kotlin.webhooks.TungoWebhooks
import okhttp3.mockwebserver.MockResponse
import okio.Buffer
import okio.ByteString.Companion.decodeBase64

/** Every section of conformance/vectors.json, checked against this SDK. */
class ConformanceVectorsTest {
    @Test
    fun vectorsHaveTheExpectedVersion() {
        assertEquals(2, Vectors.root.getValue("version").jsonPrimitive.int)
    }

    // ---- webhook_verification ----

    @Test
    fun webhookVerification() {
        val default = Vectors.section("webhook_verification", "default_tolerance_seconds").jsonPrimitive.long
        assertEquals(default, TungoWebhooks.DEFAULT_TOLERANCE.inWholeSeconds)
        val cases = Vectors.cases("webhook_verification", "cases")
        assertTrue(cases.size >= 20)
        for (case in cases) {
            val expect = case.obj("expect")
            val now = case.getValue("now").jsonPrimitive.long
            val tolerance = (case["tolerance_seconds"]?.jsonPrimitive?.long ?: default).seconds
            val verify = {
                TungoWebhooks.verify(case.str("body"), case.stringMap("headers"), case.str("secret"), tolerance) { now }
            }
            if (expect.flag("valid")) {
                val event = verify()
                assertEquals(expect.str("event_type"), event.type, case.caseName)
                // Only the deliberately incomplete vector may fall back to the Unknown variant.
                if (case["contract_valid"]?.jsonPrimitive?.booleanOrNull != false) {
                    assertFalse(event is kz.tungo.sdk.kotlin.models.WebhookEvent.Unknown, case.caseName)
                }
            } else {
                val error = assertFailsWith<WebhookVerificationException>(case.caseName) { verify() }
                assertEquals(expect.str("reason").uppercase(Locale.ROOT), error.reason.name, case.caseName)
                assertFalse(case.str("secret").isNotEmpty() && case.str("secret") in error.message.orEmpty(), case.caseName)
            }
        }
    }

    // ---- hook_signature ----

    @Test
    fun hookSignatureSign() {
        for (case in Vectors.cases("hook_signature", "sign")) {
            val body = case.str("body_base64").decodeBase64()!!.toByteArray()
            val expect = case.obj("expect")
            if (expect.flag("error")) {
                assertFailsWith<TungoValidationException>(case.caseName) { TungoWebhooks.hookSignature(body, case.str("secret")) }
                assertFailsWith<TungoValidationException>(case.caseName) { TungoWebhooks.hookSignatureHeader(body, case.str("secret")) }
            } else {
                assertEquals(expect.str("hex"), TungoWebhooks.hookSignature(body, case.str("secret")), case.caseName)
                assertEquals(expect.str("header"), TungoWebhooks.hookSignatureHeader(body, case.str("secret")), case.caseName)
            }
        }
    }

    @Test
    fun hookSignatureVerify() {
        val cases = Vectors.cases("hook_signature", "verify")
        assertTrue(cases.size >= 8)
        for (case in cases) {
            val body = case.str("body_base64").decodeBase64()!!.toByteArray()
            val valid = TungoWebhooks.verifyHookSignature(body, case.str("signature"), case.str("secret"))
            assertEquals(case.obj("expect").flag("valid"), valid, case.caseName)
        }
    }

    // ---- money ----

    @Test
    fun moneyToTiynFromString() {
        val cases = Vectors.cases("money", "to_tiyn")
        assertTrue(cases.size >= 40)
        for (case in cases) {
            val input = case.str("input")
            val expect = case.obj("expect")
            if (expect.flag("error")) {
                assertFailsWith<TungoValidationException>(case.caseName) { TungoMoney.toTiyn(input) }
            } else {
                assertEquals(expect.str("tiyn").toLong(), TungoMoney.toTiyn(input), case.caseName)
                assertEquals(expect.str("tiyn").toLong(), TungoMoney.toTiyn(BigDecimal(input)), case.caseName)
            }
        }
    }

    @Test
    fun moneyToTiynBoundsAHugeExponentBeforeRescaling() {
        // A BigDecimal parsed from user text may carry any exponent: setScale must never see it.
        val started = System.nanoTime()
        assertEquals(0, TungoMoney.toTiyn(BigDecimal("1E-2000000000")))
        assertEquals(0, TungoMoney.toTiyn(BigDecimal("-1E-10000000")))
        assertEquals(0, TungoMoney.toTiyn(BigDecimal("0E-2000000000")))
        assertEquals(0, TungoMoney.toTiyn(BigDecimal("9.99E-4")))
        assertEquals(1, TungoMoney.toTiyn(BigDecimal("5E-3")))
        assertEquals(-1, TungoMoney.toTiyn(BigDecimal("-5E-3")))
        assertEquals(10050, TungoMoney.toTiyn(BigDecimal("1.005E2")))
        assertFailsWith<TungoValidationException> { TungoMoney.toTiyn(BigDecimal("1E+999999999")) }
        assertFailsWith<TungoValidationException> { TungoMoney.toTiyn(BigDecimal("1E+1000000")) }
        assertFailsWith<TungoValidationException> { TungoMoney.toTiyn(BigDecimal("-1E19")) }
        assertTrue(System.nanoTime() - started < 2_000_000_000L, "the conversion must stay cheap")
    }

    @Test
    fun moneyToTiynFromDouble() {
        for (case in Vectors.cases("money", "to_tiyn_float")) {
            val input = when (case.strOrNull("special")) {
                "NaN" -> Double.NaN
                "Infinity" -> Double.POSITIVE_INFINITY
                "-Infinity" -> Double.NEGATIVE_INFINITY
                null -> case.str("input").toDouble()
                else -> fail("unknown special value in ${case.caseName}")
            }
            val expect = case.obj("expect")
            if (expect.flag("error")) {
                assertFailsWith<TungoValidationException>(case.caseName) { TungoMoney.toTiyn(input) }
            } else {
                assertEquals(expect.str("tiyn").toLong(), TungoMoney.toTiyn(input), case.caseName)
            }
        }
    }

    @Test
    fun moneyFormatTiyn() {
        val cases = Vectors.cases("money", "format_tiyn")
        assertTrue(cases.size >= 20)
        for (case in cases) {
            val tiyn = case.str("tiyn").toLong()
            val text = case.strOrNull("currency")?.let { TungoMoney.formatTiyn(tiyn, it) } ?: TungoMoney.formatTiyn(tiyn)
            assertEquals(case.obj("expect").str("text"), text, case.caseName)
        }
    }

    @Test
    fun binIin() {
        val cases = Vectors.cases("bin_iin")
        assertTrue(cases.size >= 15)
        for (case in cases) {
            assertEquals(case.obj("expect").flag("valid"), TungoMoney.isValidBinIin(case.str("input")), case.caseName)
        }
    }

    // ---- errors ----

    private fun classOf(kind: String) = when (kind) {
        "invalid_request_error" -> InvalidRequestException::class
        "authentication_error" -> AuthenticationException::class
        "payment_required_error" -> PaymentRequiredException::class
        "permission_error" -> PermissionDeniedException::class
        "not_found_error" -> NotFoundException::class
        "conflict_error" -> ConflictException::class
        "gone_error" -> GoneException::class
        "limit_error" -> LimitException::class
        "api_error" -> InternalServerException::class
        "unavailable_error" -> ServiceUnavailableException::class
        else -> fail("unknown error class $kind")
    }

    private fun checkError(case: JsonObject, error: TungoApiException) {
        val name = case.caseName
        val expect = case.obj("expect")
        assertEquals(case.getValue("status").jsonPrimitive.int, error.status, name)
        assertEquals(classOf(expect.str("class")), error::class, name)
        assertEquals(expect.flag("envelope"), error.isEnvelope, name)
        assertEquals(expect.str("type"), error.type, name)
        assertEquals(expect.strOrNull("code"), error.code, name)
        assertEquals(expect.strOrNull("request_id"), error.requestId, name)
        if (expect.flag("envelope")) assertEquals(expect.str("message"), error.apiMessage, name) else assertNull(error.apiMessage, name)
        val retryAfter = (expect["retry_after"] as? JsonPrimitive)?.doubleOrNull
        if (retryAfter == null) {
            assertNull(error.retryAfterSeconds, name)
        } else {
            assertEquals(retryAfter, error.retryAfterSeconds!!, 1e-9, name)
            assertEquals(retryAfter, error.retryAfter!!.inWholeMilliseconds / 1000.0, 1e-3, name)
        }
        val fields = expect.arr("fields").map { it.jsonObject }
        assertEquals(fields.size, error.fields.size, name)
        for ((i, field) in fields.withIndex()) {
            assertEquals(field.str("path"), error.fields[i].path, name)
            assertEquals(field.str("code"), error.fields[i].code.value, name)
            assertEquals(field.str("message"), error.fields[i].message, name)
        }
        assertEquals(expect.obj("extra"), JsonObject(error.extra), name)
        assertEquals(case.str("body"), error.body, name)
        val message = error.message.orEmpty()
        assertTrue(error.status.toString() in message && error.type in message, "$name: the message names the status and the type")
        error.code?.let { assertTrue(it in message, name) }
        error.requestId?.let { assertTrue(it in message, name) }
    }

    @Test
    fun errorsParsedFromVectors() {
        val cases = Vectors.cases("errors", "cases")
        assertTrue(cases.size >= 12)
        for (case in cases) {
            val error = Errors.fromResponse(
                case.getValue("status").jsonPrimitive.int,
                ResponseHeaders.of(case.stringMap("headers")),
                case.str("body"),
                0L,
            )
            checkError(case, error)
        }
    }

    @Test
    fun errorsRaisedByRealCalls() = runBlocking<Unit> {
        MockApi().use { api ->
            val client = api.client { maxRetries = 0 }
            for (case in Vectors.cases("errors", "cases")) {
                val response = MockResponse().setResponseCode(case.getValue("status").jsonPrimitive.int).setBody(case.str("body"))
                for ((name, value) in case.stringMap("headers")) response.setHeader(name, value)
                api.server.enqueue(response)
                val error = assertFailsWith<TungoApiException>(case.caseName) { client.companies.get() }
                // An HTTP date in Retry-After is relative to the clock; every other expectation holds as is.
                checkError(case, error)
                api.take()
            }
        }
    }

    @Test
    fun errorStatusFallback() {
        val cases = Vectors.cases("errors", "status_fallback")
        assertTrue(cases.size >= 15)
        for (case in cases) {
            val status = case.getValue("status").jsonPrimitive.int
            val type = case.obj("expect").str("type")
            assertEquals(type, Errors.statusFallback(status), "status $status")
            val error = Errors.fromResponse(status, ResponseHeaders.Empty, "<html>oops</html>", 0L)
            assertEquals(classOf(type), error::class, "status $status")
            assertEquals(type, error.type)
        }
    }

    // ---- retry_after, backoff, retry_policy ----

    @Test
    fun retryAfter() {
        val max = Vectors.section("retry_after", "max_sleep_seconds").jsonPrimitive.content.toDouble()
        assertEquals(max, Retry.MAX_SERVER_DELAY_SECONDS)
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val cases = Vectors.cases("retry_after", "cases")
        assertTrue(cases.size >= 15)
        for (case in cases) {
            val now = format.parse(case.str("now")).time
            val expect = case.obj("expect")
            val seconds = Retry.retryAfterSeconds(ResponseHeaders.of(case.stringMap("headers")), now)
            val wanted = (expect["seconds"] as? JsonPrimitive)?.doubleOrNull
            // "No useful delay" is written either as null or as 0 by the vectors; both lead to the computed backoff.
            if (wanted == null || wanted == 0.0) {
                assertTrue(seconds == null || seconds == 0.0, case.caseName)
            } else {
                assertEquals(wanted, assertNotNull(seconds, case.caseName), 1e-9, case.caseName)
            }
            val sleeps = seconds == null || seconds <= Retry.MAX_SERVER_DELAY_SECONDS
            assertEquals(expect.flag("sleep"), sleeps, case.caseName)
        }
    }

    @Test
    fun retryAfterDrivesRealRetries() = runBlocking<Unit> {
        for (case in Vectors.cases("retry_after", "cases")) {
            MockApi().use { api ->
                val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                api.now = format.parse(case.str("now")).time
                val client = api.client { maxRetries = 1 }
                val first = MockResponse().setResponseCode(503).setBody("busy")
                for ((name, value) in case.stringMap("headers")) first.setHeader(name, value)
                api.server.enqueue(first)
                api.enqueueJson("""{"data":[]}""")
                val expect = case.obj("expect")
                val seconds = (expect["seconds"] as? JsonPrimitive)?.doubleOrNull
                if (expect.flag("sleep")) {
                    client.companies.list()
                    assertEquals(2, api.server.requestCount, case.caseName)
                    val slept = api.sleeps.single()
                    if (seconds != null && seconds > 0.0) {
                        assertEquals(Math.ceil(seconds * 1000).toLong(), slept, case.caseName)
                    } else {
                        // No delay asked, a zero delay, a date in the past: the backoff of the first retry with jitter 1.0.
                        assertEquals(500L, slept, case.caseName)
                    }
                } else {
                    val error = assertFailsWith<ServiceUnavailableException>(case.caseName) { client.companies.list() }
                    assertEquals(1, api.server.requestCount, case.caseName)
                    assertTrue(api.sleeps.isEmpty(), case.caseName)
                    assertEquals(seconds!!, error.retryAfterSeconds!!, 1e-9, case.caseName)
                }
            }
        }
    }

    @Test
    fun backoff() {
        val section = Vectors.section("backoff").jsonObject
        assertEquals(section.str("base_seconds").toDouble(), Retry.BASE_SECONDS)
        assertEquals(section.str("max_seconds").toDouble(), Retry.MAX_SECONDS)
        assertEquals(section.str("jitter_min").toDouble(), Retry.JITTER_MIN)
        for (case in section.arr("cases").map { it.jsonObject }) {
            val attempt = case.getValue("attempt").jsonPrimitive.int
            val min = case.obj("expect").str("min_seconds").toDouble()
            val max = case.obj("expect").str("max_seconds").toDouble()
            assertEquals(min, Retry.backoffSeconds(attempt, 0.0), 1e-9, case.caseName)
            assertEquals(max, Retry.backoffSeconds(attempt, 1.0), 1e-9, case.caseName)
            repeat(200) {
                val delay = Retry.backoffSeconds(attempt, Math.random())
                assertTrue(delay >= min - 1e-9 && delay <= max + 1e-9, "${case.caseName}: $delay")
            }
        }
    }

    @Test
    fun retryPolicy() {
        val cases = Vectors.cases("retry_policy")
        assertTrue(cases.size >= 70)
        for (case in cases) {
            val repeatable = Retry.isRepeatable(case.str("method"), case.flag("idempotency_key"))
            val cause = case.getValue("cause").jsonPrimitive
            val retryable = if (cause.isString) true else Retry.isRetryableStatus(cause.int)
            assertEquals(case.obj("expect").flag("retry"), repeatable && retryable, case.caseName)
        }
    }

    // ---- path_segment, query ----

    @Test
    fun pathSegments() {
        val cases = Vectors.cases("path_segment")
        assertTrue(cases.size >= 15)
        for (case in cases) {
            val expect = case.obj("expect")
            if (expect.flag("error")) {
                assertFailsWith<TungoValidationException>(case.caseName) { pathSegment("id", case.str("input")) }
            } else {
                assertEquals(expect.str("encoded"), pathSegment("id", case.str("input")), case.caseName)
            }
        }
    }

    @Test
    fun pathSegmentsOnTheWire() = runBlocking<Unit> {
        MockApi().use { api ->
            val client = api.client()
            for (case in Vectors.cases("path_segment")) {
                val expect = case.obj("expect")
                if (expect.flag("error")) {
                    assertFailsWith<TungoValidationException>(case.caseName) { client.chat.delete(case.str("input")) }
                } else {
                    api.enqueueJson("""{"ok":true}""")
                    try {
                        client.chat.delete(case.str("input"))
                    } catch (ignored: kz.tungo.sdk.kotlin.errors.TungoDecodeException) {
                        // Only the request line matters here.
                    }
                    assertEquals("/v1/companies/co_1/chat/" + expect.str("encoded"), api.take().path, case.caseName)
                }
            }
            assertEquals(Vectors.cases("path_segment").count { !it.obj("expect").flag("error") }, api.server.requestCount)
        }
    }

    @Test
    fun queryStrings() {
        val cases = Vectors.cases("query")
        assertTrue(cases.size >= 12)
        for (case in cases) {
            val query = QueryBuilder()
            for (param in case.arr("params").map { it.jsonObject }) {
                val name = param.str("name")
                val value = param.getValue("value")
                when (param.str("type")) {
                    "string", "date-time" -> query.add(name, if (value is JsonNull) null else value.jsonPrimitive.content)
                    "boolean" -> query.add(name, (value as? JsonPrimitive)?.booleanOrNull)
                    "integer" -> query.add(name, if (value is JsonNull) null else value.jsonPrimitive.content.toLong())
                    "array" -> query.addAll(name, value.jsonArray.map { it.jsonPrimitive.content })
                    else -> fail("unknown parameter type in ${case.caseName}")
                }
            }
            val expect = case.obj("expect")
            val allowed = expect["any_of"]?.jsonArray?.map { it.jsonPrimitive.content } ?: listOf(expect.str("query"))
            assertTrue(query.build() in allowed, "${case.caseName}: ${query.build()}")
        }
    }

    // ---- sse ----

    private fun parse(chunks: List<ByteArray>): List<ServerSentEvent> {
        val parser = SseParser()
        val events = ArrayList<ServerSentEvent>()
        for (chunk in chunks) parser.feed(chunk, 0, chunk.size, events)
        parser.end(events)
        return events
    }

    private fun checkEvents(case: JsonObject, events: List<ServerSentEvent>, how: String) {
        val expected = case.obj("expect").arr("events").map { it.jsonObject }
        assertEquals(expected.size, events.size, "${case.caseName} ($how)")
        for ((i, want) in expected.withIndex()) {
            val label = "${case.caseName} ($how), event $i"
            assertEquals(want.str("event"), events[i].event, label)
            assertEquals(want.str("data"), events[i].data, label)
            assertEquals(want.strOrNull("id").orEmpty(), events[i].id.orEmpty(), label)
            assertEquals(want["retry"]?.jsonPrimitive?.long, events[i].retry, label)
        }
    }

    @Test
    fun sseParser() {
        val cases = Vectors.cases("sse")
        assertTrue(cases.size >= 20)
        for (case in cases) {
            val raw = case.str("raw_base64").decodeBase64()!!.toByteArray()
            checkEvents(case, parse(listOf(raw)), "one chunk")
            checkEvents(case, parse(raw.map { byteArrayOf(it) }), "byte by byte")
            case["chunks_base64"]?.jsonArray?.let { chunks ->
                checkEvents(case, parse(chunks.map { it.jsonPrimitive.content.decodeBase64()!!.toByteArray() }), "vector chunks")
            }
        }
    }

    @Test
    fun sseParserAcceptsVeryLongLines() {
        val size = 9 * 1024 * 1024
        val payload = ByteArray(size) { 'x'.code.toByte() }
        val events = parse(listOf("data: ".toByteArray(), payload, "\n\n".toByteArray()))
        assertEquals(1, events.size)
        assertEquals(size, events[0].data.length)
    }

    // ---- chat_stream ----

    @Test
    fun chatStreamReplayThatIsNotAReplayIsADecodeError() = runBlocking<Unit> {
        val cases = Vectors.cases("chat_stream_replay").filter { it.obj("expect")["decode_error"]?.jsonPrimitive?.booleanOrNull == true }
        assertTrue(cases.isNotEmpty())
        for (case in cases) {
            MockApi().use { api ->
                val response = MockResponse().setResponseCode(case.getValue("status").jsonPrimitive.int)
                    .setBody(Buffer().writeUtf8(case.getValue("body").toString()))
                for ((header, value) in case.stringMap("headers")) response.setHeader(header, value)
                api.server.enqueue(response)
                val error = assertFailsWith<kz.tungo.sdk.kotlin.errors.TungoDecodeException>(case.caseName) {
                    api.client().chat.streamTurn(ChatMessageRequest(message = "q"))
                }
                assertEquals(case.getValue("status").jsonPrimitive.int, error.status, case.caseName)
            }
        }
    }

    @Test
    fun chatStreamAccumulation() = runBlocking<Unit> {
        // A turn repeated with the same Idempotency-Key after it finished is answered with its
        // stored JSON result (chat_stream_replay): the stream must yield the same turn.
        val replays = Vectors.cases("chat_stream_replay").filter { it.obj("expect")["decode_error"] == null }
        assertTrue(replays.size >= 3)
        val cases = Vectors.cases("chat_stream")
        assertTrue(cases.size >= 6)
        for (case in cases + replays) {
            MockApi().use { api ->
                val client = api.client()
                val expect = case.obj("expect")
                val name = case.caseName
                fun enqueue() {
                    val response = MockResponse().setResponseCode(case.getValue("status").jsonPrimitive.int)
                        .setBody(Buffer().writeUtf8(case["raw"]?.jsonPrimitive?.content ?: case.getValue("body").toString()))
                    for ((header, value) in case.stringMap("headers")) response.setHeader(header, value)
                    api.server.enqueue(response)
                }

                // The raw stream: every event is delivered as a typed event, errors included.
                enqueue()
                val types = ArrayList<String>()
                val accumulator = ChatStreamAccumulator()
                client.chat.withRawResponse.createStream(ChatMessageRequest(message = "q")) { stream ->
                    assertEquals(expect.strOrNull("dialog_id"), stream.dialogId, name)
                    assertEquals(expect["turn_no"]?.jsonPrimitive?.int, stream.turnNo, name)
                    stream.events.collect { event: ChatEvent ->
                        types.add(event.type)
                        accumulator.add(event)
                    }
                }
                assertEquals(expect.getValue("event_count").jsonPrimitive.int, accumulator.eventCount, name)
                expect["event_types"]?.jsonArray?.let { wanted -> assertEquals(wanted.map { it.jsonPrimitive.content }, types, name) }
                assertEquals(expect.str("text"), accumulator.text, name)
                assertEquals(expect.strOrNull("stop_reason"), accumulator.stopReason, name)
                assertEquals(expect.strOrNull("choice_id"), accumulator.choiceId, name)
                assertEquals((expect["cost_tiyn"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.long, accumulator.costTiyn, name)
                val usage = expect["usage"] as? JsonObject
                if (usage == null) {
                    assertNull(accumulator.usage, name)
                } else {
                    val got = assertNotNull(accumulator.usage, name)
                    assertEquals(usage.getValue("input_tokens").jsonPrimitive.long, got.inputTokens.toLong(), name)
                    assertEquals(usage.getValue("output_tokens").jsonPrimitive.long, got.outputTokens.toLong(), name)
                    assertEquals(usage.getValue("cache_read_tokens").jsonPrimitive.long, got.cacheReadTokens.toLong(), name)
                    assertEquals(usage.getValue("cache_write_tokens").jsonPrimitive.long, got.cacheWriteTokens.toLong(), name)
                }

                // The helper: the same outcome, and an error event becomes a typed exception.
                enqueue()
                val wantedError = expect["error"] as? JsonObject
                if (wantedError == null && !accumulator.isComplete) {
                    // A stream that ended without its done event is not a finished turn, whatever text it carried.
                    val cut = assertFailsWith<kz.tungo.sdk.kotlin.errors.TungoDecodeException>(name) { client.chat.streamTurn(ChatMessageRequest(message = "q")) }
                    assertTrue("ended before completion" in cut.message.orEmpty(), name)
                } else if (wantedError == null) {
                    val turn = client.chat.streamTurn(ChatMessageRequest(message = "q"))
                    assertEquals(expect.str("text"), turn.text, name)
                    assertEquals(expect.strOrNull("dialog_id"), turn.dialogId, name)
                    assertEquals(expect["turn_no"]?.jsonPrimitive?.int, turn.turnNo, name)
                    assertEquals(expect.strOrNull("stop_reason"), turn.stopReason, name)
                    assertEquals(expect.getValue("event_count").jsonPrimitive.int, turn.eventCount, name)
                } else {
                    val error = assertFailsWith<TungoApiException>(name) { client.chat.streamTurn(ChatMessageRequest(message = "q")) }
                    assertEquals(classOf(wantedError.str("type")), error::class, name)
                    assertEquals(wantedError.str("type"), error.type, name)
                    assertEquals(wantedError.strOrNull("code"), error.code, name)
                    assertEquals(wantedError.str("message"), error.apiMessage, name)
                    assertEquals(wantedError.strOrNull("request_id"), error.requestId, name)
                    assertEquals(wantedError.obj("extra"), JsonObject(error.extra), name)
                }
                val request = api.take()
                assertEquals("text/event-stream", request.getHeader("Accept"), name)
                assertTrue("\"stream\":true" in request.body.readUtf8(), name)
            }
        }
    }
}
