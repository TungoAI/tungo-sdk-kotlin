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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kz.tungo.sdk.kotlin.errors.AuthenticationException
import kz.tungo.sdk.kotlin.errors.InvalidRequestException
import kz.tungo.sdk.kotlin.errors.LimitException
import kz.tungo.sdk.kotlin.errors.NotFoundException
import kz.tungo.sdk.kotlin.errors.ServiceUnavailableException
import kz.tungo.sdk.kotlin.errors.TungoApiException
import kz.tungo.sdk.kotlin.errors.TungoConfigException
import kz.tungo.sdk.kotlin.errors.TungoConnectionException
import kz.tungo.sdk.kotlin.errors.TungoDecodeException
import kz.tungo.sdk.kotlin.errors.TungoException
import kz.tungo.sdk.kotlin.errors.TungoTimeoutException
import kz.tungo.sdk.kotlin.errors.TungoValidationException
import kz.tungo.sdk.kotlin.errors.WebhookVerificationException
import kz.tungo.sdk.kotlin.internal.TungoJson
import kz.tungo.sdk.kotlin.models.ChatMessageRequest
import kz.tungo.sdk.kotlin.models.ChatTokenCountRequest
import kz.tungo.sdk.kotlin.models.Company
import kz.tungo.sdk.kotlin.models.CompanyStatus
import kz.tungo.sdk.kotlin.models.WebhookEndpointCreate
import kz.tungo.sdk.kotlin.models.WebhookEndpointPatch
import kz.tungo.sdk.kotlin.resources.ask
import kz.tungo.sdk.kotlin.resources.runScenarioAndWait
import kz.tungo.sdk.kotlin.resources.waitForBatch
import kz.tungo.sdk.kotlin.resources.waitForRun
import kz.tungo.sdk.kotlin.webhooks.TungoWebhooks
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy

/** The behaviour of the client on the wire, against a local mock of the API. */
class ClientBehaviourTest {
    // ---- configuration, authentication, headers ----

    @Test
    fun defaultBaseUrlIsTheProductionApi() {
        assertEquals("https://api.tungo.kz", TungoSdk.DEFAULT_BASE_URL)
        TungoClient { apiKey = MockApi.KEY }.use { client ->
            assertEquals("https://api.tungo.kz", client.options.baseUrl)
            assertEquals(2, client.options.maxRetries)
        }
    }

    @Test
    fun keyAndUserAgentAreSentToAuthenticatedOperations() = runBlocking {
        MockApi().use { api ->
            api.enqueueJson(Fixtures.COMPANY)
            api.client().companies.get()
            val request = api.take()
            assertEquals("GET", request.method)
            assertEquals("/v1/companies/co_1", request.path)
            assertEquals(MockApi.KEY, request.getHeader("x-api-key"))
            assertNull(request.getHeader("Authorization"))
            assertEquals("application/json", request.getHeader("Accept"))
            val agent = request.getHeader("User-Agent").orEmpty()
            assertTrue(agent.startsWith("tungo-sdk-kotlin/" + TungoSdk.VERSION), agent)
            assertNull(request.getHeader("Idempotency-Key"))
        }
    }

    @Test
    fun aMissingKeyFailsBeforeAnyRequest() = runBlocking {
        MockApi().use { api ->
            val client = api.client { apiKey = null }
            val error = assertFailsWith<TungoConfigException> { client.companies.list() }
            assertTrue("TUNGO_API_KEY" in error.message.orEmpty())
            assertEquals(0, api.server.requestCount)
        }
    }

    @Test
    fun publicOperationsNeedNoKeyAndNeverSendOne() = runBlocking {
        MockApi().use { api ->
            api.server.enqueue(MockResponse().setResponseCode(404).setBody(Fixtures.error("not_found_error", "нет")))
            api.server.enqueue(MockResponse().setResponseCode(404).setBody(Fixtures.error("not_found_error", "нет")))
            assertFailsWith<NotFoundException> { api.client { apiKey = null }.platform.listUsageRates() }
            assertNull(api.take().getHeader("x-api-key"))
            val options = RequestOptions { header("x-api-key", "smuggled"); header("Authorization", "Bearer smuggled") }
            assertFailsWith<NotFoundException> { api.client().platform.listUsageRates(requestOptions = options) }
            val request = api.take()
            assertNull(request.getHeader("x-api-key"))
            assertNull(request.getHeader("Authorization"))
        }
    }

    @Test
    fun secretsAreNeverPrinted() {
        val client = TungoClient { apiKey = MockApi.KEY; webhookSecret = "whsec_c2VjcmV0c2VjcmV0" }
        client.use {
            for (text in listOf(it.toString(), it.options.toString())) {
                assertFalse(MockApi.KEY in text, text)
                assertFalse("whsec_c2VjcmV0c2VjcmV0" in text, text)
                assertTrue("***" in text, text)
            }
        }
    }

    @Test
    fun invalidConfigurationIsRejected() {
        assertFailsWith<TungoConfigException> { TungoClient { apiKey = "k"; baseUrl = "not a url" } }
        assertFailsWith<TungoConfigException> { TungoClient { apiKey = "k"; maxRetries = -1 } }
        assertFailsWith<TungoConfigException> { TungoClient { apiKey = "k"; timeout = 0.seconds } }
        val error = assertFailsWith<TungoConfigException> { TungoClient { apiKey = "bad\nkey-0123456789" } }
        assertFalse("key-0123456789" in error.message.orEmpty())
        assertFailsWith<TungoConfigException> { RequestOptions { idempotencyKey = "a\r\nb" } }
        assertFailsWith<TungoConfigException> { RequestOptions { maxRetries = -1 } }
    }

    @Test
    fun scopeIdsComeFromTheCallThenFromTheClient() = runBlocking {
        MockApi().use { api ->
            api.enqueueJson(Fixtures.COMPANY)
            api.client().companies.get(companyId = "co/other")
            assertEquals("/v1/companies/co%2Fother", api.take().path)
            val bare = api.client { companyId = null; workspaceId = null }
            assertFailsWith<TungoConfigException> { bare.companies.get() }
            assertFailsWith<TungoConfigException> { bare.webhooks.listEndpoints() }
            assertEquals(1, api.server.requestCount)
        }
    }

    @Test
    fun defaultAndPerRequestHeadersAreSent() = runBlocking {
        MockApi().use { api ->
            api.enqueueJson(Fixtures.COMPANY)
            val client = api.client { header("X-Trace", "client"); header("X-Tenant", "t1") }
            client.companies.get(requestOptions = RequestOptions { header("X-Trace", "request") })
            val request = api.take()
            assertEquals("request", request.getHeader("X-Trace"))
            assertEquals("t1", request.getHeader("X-Tenant"))
        }
    }

    @Test
    fun withOptionsDerivesAClientOnTheSamePool() = runBlocking {
        MockApi().use { api ->
            api.enqueueJson(Fixtures.COMPANY)
            val client = api.client()
            val other = client.withOptions { companyId = "co_2"; maxRetries = 0 }
            assertEquals("co_1", client.options.companyId)
            assertEquals(0, other.options.maxRetries)
            other.companies.get()
            assertEquals("/v1/companies/co_2", api.take().path)
            other.close() // must not shut the shared pool down
            api.enqueueJson(Fixtures.COMPANY)
            client.companies.get()
            assertEquals(2, api.server.requestCount)
        }
    }

    @Test
    fun aCallerSuppliedHttpClientIsUsedAndLeftOpen() = runBlocking {
        MockApi().use { api ->
            var seen = 0
            val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain -> seen++; chain.proceed(chain.request()) }).build()
            api.enqueueJson(Fixtures.COMPANY)
            val client = api.client { httpClient = http }
            client.companies.get()
            client.close()
            assertEquals(1, seen)
            assertFalse(http.dispatcher.executorService.isShutdown)
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }

    // ---- decoding ----

    @Test
    fun responsesAreDecodedIntoTypedModelsAndTolerateTheFuture() = runBlocking {
        MockApi().use { api ->
            api.enqueueJson(Fixtures.COMPANY)
            val company: Company = api.client().companies.get()
            assertEquals("co_1", company.id)
            assertEquals("ТОО Ромашка", company.name)
            assertEquals("990140000385", company.bin)
            assertEquals(CompanyStatus("active"), company.status)
            assertTrue(company.status.isKnown)
            // An enum value from a newer API is kept, not rejected.
            assertEquals("a.permission.from.the.future", company.permissions[1].value)
            assertFalse(company.permissions[1].isKnown)
        }
    }

    @Test
    fun int64IsDecodedWithoutLoss() = runBlocking {
        MockApi().use { api ->
            api.enqueueJson(Fixtures.TOKEN_COUNT)
            val count = api.client().chat.countTokens(ChatTokenCountRequest(message = "q"))
            assertEquals(9007199254740993L, count.inputCostTiyn)
            assertEquals(Long.MAX_VALUE, count.maxCostTiyn)
            api.take()
            api.enqueueJson(Fixtures.run("done"))
            val run = api.client().automations.getRun("run_1")
            assertEquals(9007199254740993L, run.costTiyn)
            val output = assertNotNull(run.output)
            assertEquals(12.5, output.getValue("total").asDouble())
            assertEquals(true, output.getValue("ok").asBoolean())
            assertEquals("текст", output.getValue("note").asString())
            assertNull(output.getValue("note").asDouble())
        }
    }

    @Test
    fun aBodyThatCannotBeDecodedIsATypedErrorAndIsNotRetried() = runBlocking {
        MockApi().use { api ->
            for (body in listOf("<html>proxy</html>", "", """{"id":1}""", "[]")) {
                api.server.enqueue(MockResponse().setResponseCode(200).setHeader("x-request-id", "req_bad").setBody(body))
                val error = assertFailsWith<TungoDecodeException> { api.client().companies.get() }
                assertEquals(200, error.status)
                assertEquals(body, error.body)
                assertEquals("req_bad", error.requestId)
                assertTrue("req_bad" in error.message.orEmpty())
                assertIs<TungoException>(error)
            }
            assertEquals(4, api.server.requestCount)
        }
    }

    @Test
    fun rawResponsesExposeStatusHeadersAndRateLimit() = runBlocking {
        MockApi().use { api ->
            api.enqueueJson(
                Fixtures.COMPANY, 200,
                "X-Request-Id" to "req_1", "x-ratelimit-limit" to "60", "x-ratelimit-remaining" to "59", "x-ratelimit-reset" to "7",
            )
            val response = api.client().companies.withRawResponse.get()
            assertEquals(200, response.status)
            assertEquals("req_1", response.requestId)
            assertEquals("req_1", response.headers["x-request-id"])
            assertEquals(RateLimit(60, 59, 7), response.rateLimit)
            assertEquals("co_1", response.body.id)
            api.enqueueJson(Fixtures.COMPANY)
            assertNull(api.client().companies.withRawResponse.get().rateLimit)
        }
    }

    // ---- request bodies ----

    @Test
    fun absentNullAndEmptyAreDifferentOnTheWire() = runBlocking {
        MockApi().use { api ->
            val client = api.client()
            val bodies = ArrayList<String>()
            for (patch in listOf(
                WebhookEndpointPatch(),
                WebhookEndpointPatch(channels = NullableField.Null),
                WebhookEndpointPatch(channels = NullableField.of(emptyList())),
                WebhookEndpointPatch(channels = NullableField.of(listOf("a")), enabled = false, description = ""),
            )) {
                api.enqueueJson(Fixtures.endpoint())
                client.webhooks.updateEndpoint("ep_1", patch)
                val request = api.take()
                assertEquals("PATCH", request.method)
                assertEquals("/v1/workspaces/ws_1/webhooks/endpoints/ep_1", request.path)
                assertEquals("application/json", request.getHeader("Content-Type"))
                bodies.add(request.body.readUtf8())
            }
            assertEquals("{}", bodies[0])
            assertEquals("""{"channels":null}""", bodies[1])
            assertEquals("""{"channels":[]}""", bodies[2])
            assertEquals("""{"channels":["a"],"description":"","enabled":false}""", bodies[3])
        }
    }

    @Test
    fun nullableFieldsRoundTrip() {
        val json = TungoJson.decoder
        val serializer = WebhookEndpointPatch.serializer()
        assertEquals(NullableField.Absent, json.decodeFromString(serializer, "{}").channels)
        assertEquals(NullableField.Null, json.decodeFromString(serializer, """{"channels":null}""").channels)
        assertEquals(NullableField.Value(listOf("x")), json.decodeFromString(serializer, """{"channels":["x"]}""").channels)
        assertEquals(listOf("x"), NullableField.of(listOf("x")).valueOrNull())
        assertNull(NullableField.Null.valueOrNull())
    }

    // ---- errors ----

    @Test
    fun errorsAreTypedAndCarryTheDetails() = runBlocking {
        MockApi().use { api ->
            val body = """{"error":{"type":"invalid_request_error","code":"validation_failed","message":"Проверьте поля",""" +
                """"fields":[{"path":"url","code":"required","message":"нужен адрес"}],"resets_at":"2026-12-01T00:00:00Z"},"request_id":"req_body"}"""
            api.enqueueJson(body, 400, "x-request-id" to "req_header")
            val error = assertFailsWith<InvalidRequestException> {
                api.client().webhooks.createEndpoint(WebhookEndpointCreate(url = ""))
            }
            assertEquals(400, error.status)
            assertEquals("invalid_request_error", error.type)
            assertEquals("validation_failed", error.code)
            assertEquals("Проверьте поля", error.apiMessage)
            assertEquals("req_body", error.requestId)
            assertEquals("url", error.fields.single().path)
            assertEquals(JsonPrimitive("2026-12-01T00:00:00Z"), error.extra["resets_at"])
            assertEquals(body, error.body)
            assertTrue(error.isEnvelope)
            for (part in listOf("400", "invalid_request_error", "validation_failed", "req_body", "Проверьте поля")) {
                assertTrue(part in error.message.orEmpty(), error.message)
            }
            assertIs<TungoApiException>(error)
        }
    }

    @Test
    fun nonJsonErrorBodiesKeepTheStatusAndTheBody() = runBlocking {
        MockApi().use { api ->
            val client = api.client { maxRetries = 0 }
            api.server.enqueue(MockResponse().setResponseCode(502).setHeader("x-request-id", "req_edge").setBody("<html>Bad Gateway</html>"))
            val html = assertFailsWith<ServiceUnavailableException> { client.companies.get() }
            assertEquals(502, html.status)
            assertEquals("<html>Bad Gateway</html>", html.body)
            assertEquals("req_edge", html.requestId)
            assertFalse(html.isEnvelope)
            assertNull(html.apiMessage)
            api.server.enqueue(MockResponse().setResponseCode(401))
            val empty = assertFailsWith<AuthenticationException> { client.companies.get() }
            assertEquals("", empty.body)
            assertEquals(401, empty.status)
        }
    }

    @Test
    fun connectionFailuresAreWrapped() = runBlocking {
        MockApi().use { api ->
            api.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
            val error = assertFailsWith<TungoConnectionException> { api.client { maxRetries = 0 }.companies.get() }
            assertNotNull(error.cause)
            assertIs<TungoException>(error)
            assertFalse(MockApi.KEY in error.toString())
        }
    }

    @Test
    fun aRefusedConnectionIsAConnectionError() = runBlocking {
        val port = java.net.ServerSocket(0).use { it.localPort }
        TungoClient { apiKey = MockApi.KEY; baseUrl = "http://127.0.0.1:$port"; maxRetries = 0 }.use { client ->
            assertFailsWith<TungoConnectionException> { client.companies.list() }
        }
    }

    @Test
    fun aRedirectIsNeverFollowedAndTheKeyStaysHome() = runBlocking {
        MockApi().use { other ->
            MockApi().use { api ->
                val location = other.server.url("/v1/elsewhere").toString()
                repeat(3) { api.server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", location)) }
                val own = api.client { maxRetries = 0 }
                // A client supplied by the caller follows redirects by default; the SDK turns that off.
                val supplied = api.client { maxRetries = 0; httpClient = OkHttpClient() }
                try {
                    assertEquals(302, assertFailsWith<TungoApiException> { own.companies.get() }.status)
                    assertEquals(302, assertFailsWith<TungoApiException> { supplied.companies.get() }.status)
                    val streamed = assertFailsWith<TungoApiException> {
                        own.chat.createStream(ChatMessageRequest(message = "q")).toList()
                    }
                    assertEquals(302, streamed.status)
                    assertEquals(3, api.server.requestCount)
                    assertEquals(0, other.server.requestCount, "the API key must not reach another host")
                } finally {
                    supplied.options.httpClient?.connectionPool?.evictAll()
                }
            }
        }
    }

    @Test
    fun theHookTokenNeverAppearsInAnError() = runBlocking {
        val port = java.net.ServerSocket(0).use { it.localPort }
        val token = "SECRET_HOOK_TOKEN_123"
        TungoClient { apiKey = MockApi.KEY; baseUrl = "http://127.0.0.1:$port"; maxRetries = 0 }.use { client ->
            val error = assertFailsWith<TungoConnectionException> { client.sendHook(token, "{}") }
            val text = StringBuilder()
            var cause: Throwable? = error
            while (cause != null) {
                text.append(cause.toString()).append(' ').append(cause.stackTraceToString()).append(' ')
                cause = cause.cause
            }
            assertFalse(token in text, text.toString())
        }
    }

    // ---- timeouts and cancellation ----

    @Test
    fun theTimeoutCoversTheWholeAttemptIncludingTheBody() = runBlocking {
        MockApi().use { api ->
            // Headers arrive at once, the body trickles: only a total timeout catches this.
            api.server.enqueue(MockResponse().setBody(Fixtures.COMPANY).throttleBody(16, 200, TimeUnit.MILLISECONDS))
            val started = System.nanoTime()
            val error = assertFailsWith<TungoTimeoutException> {
                api.client { maxRetries = 0 }.companies.get(requestOptions = RequestOptions { timeout = 400.milliseconds })
            }
            val elapsed = (System.nanoTime() - started) / 1_000_000
            assertTrue(elapsed < 4000, "took $elapsed ms")
            assertIs<TungoException>(error)
        }
    }

    @Test
    fun aTimeoutIsRetriedForSafeMethods() = runBlocking {
        MockApi().use { api ->
            api.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            api.enqueueJson(Fixtures.COMPANY)
            val company = api.client { timeout = 300.milliseconds }.companies.get()
            assertEquals("co_1", company.id)
            assertEquals(2, api.server.requestCount)
            assertEquals(listOf(500L), api.sleeps.toList())
        }
    }

    @Test
    fun cancellingTheCoroutineCancelsTheCallAndIsNotWrapped() = runBlocking {
        MockApi().use { api ->
            api.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val client = api.client()
            val call = async(kotlinx.coroutines.Dispatchers.Default, CoroutineStart.DEFAULT) { client.companies.get() }
            api.take()
            delay(100)
            call.cancel()
            val error = assertFailsWith<CancellationException> { withTimeout(5000) { call.await() } }
            assertFalse(error is kotlinx.coroutines.TimeoutCancellationException)
            assertEquals(1, api.server.requestCount)
            assertTrue(api.sleeps.isEmpty())
        }
    }

    @Test
    fun chatTurnsHaveALongDefaultTimeout() {
        assertEquals(600.seconds, kz.tungo.sdk.kotlin.internal.HttpEngine.LONG_RUNNING_TIMEOUT)
        assertEquals(60.seconds, kz.tungo.sdk.kotlin.internal.HttpEngine.DEFAULT_TIMEOUT)
    }

    // ---- retries and idempotency ----

    @Test
    fun getIsRetriedWithBackoffAndThenSucceeds() = runBlocking {
        MockApi().use { api ->
            api.server.enqueue(MockResponse().setResponseCode(500).setBody(Fixtures.error("api_error", "сбой")))
            api.server.enqueue(MockResponse().setResponseCode(502).setBody("bad gateway"))
            api.enqueueJson(Fixtures.COMPANY)
            assertEquals("co_1", api.client().companies.get().id)
            assertEquals(3, api.server.requestCount)
            assertEquals(listOf(500L, 1000L), api.sleeps.toList())
        }
    }

    @Test
    fun retriesStopAtTheLimitAndSurfaceTheLastError() = runBlocking {
        MockApi().use { api ->
            repeat(4) { api.server.enqueue(MockResponse().setResponseCode(503).setBody(Fixtures.error("unavailable_error", "позже"))) }
            assertFailsWith<ServiceUnavailableException> { api.client { maxRetries = 3 }.companies.get() }
            assertEquals(4, api.server.requestCount)
            assertEquals(listOf(500L, 1000L, 2000L), api.sleeps.toList())
            api.server.enqueue(MockResponse().setResponseCode(503))
            assertFailsWith<ServiceUnavailableException> {
                api.client { maxRetries = 3 }.companies.get(requestOptions = RequestOptions { maxRetries = 0 })
            }
            assertEquals(5, api.server.requestCount)
        }
    }

    @Test
    fun aRequestTimeoutStatusFollowsTheRetryLimitOfTheSdk() = runBlocking {
        MockApi().use { api ->
            api.server.enqueue(MockResponse().setResponseCode(408))
            val error = assertFailsWith<TungoApiException> { api.client { maxRetries = 0 }.companies.get() }
            assertEquals(408, error.status)
            assertNull(error.headers["X-Tungo-Sdk-Status"])
            assertEquals(1, api.server.requestCount)
            api.server.enqueue(MockResponse().setResponseCode(408))
            api.enqueueJson(Fixtures.COMPANY)
            api.client().companies.get()
            assertEquals(3, api.server.requestCount)
            assertEquals(listOf(500L), api.sleeps.toList())
        }
    }

    @Test
    fun clientErrorsAreNotRetried() = runBlocking {
        MockApi().use { api ->
            for (status in listOf(400, 401, 403, 404, 409, 410, 422)) {
                api.server.enqueue(MockResponse().setResponseCode(status))
                assertFailsWith<TungoApiException> { api.client().companies.get() }
            }
            assertEquals(7, api.server.requestCount)
            assertTrue(api.sleeps.isEmpty())
        }
    }

    @Test
    fun aRateLimitIsRetriedAfterTheDelayTheServerAsksFor() = runBlocking {
        MockApi().use { api ->
            api.server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "7").setBody(Fixtures.error("limit_error", "часто")))
            api.server.enqueue(MockResponse().setResponseCode(429).setHeader("retry-after-ms", "1500").setHeader("Retry-After", "30"))
            api.enqueueJson(Fixtures.COMPANY)
            api.client().companies.get()
            assertEquals(listOf(7000L, 1500L), api.sleeps.toList())
            api.server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "3600").setBody(Fixtures.error("limit_error", "лимит")))
            val error = assertFailsWith<LimitException> { api.client().companies.get() }
            assertEquals(3600.0, error.retryAfterSeconds)
            assertEquals(3600.seconds, error.retryAfter)
            assertEquals(4, api.server.requestCount)
        }
    }

    @Test
    fun idempotentPostsSendOneKeyAndRepeatItOnEveryAttempt() = runBlocking {
        MockApi().use { api ->
            api.server.enqueue(MockResponse().setResponseCode(503))
            api.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            api.enqueueJson(Fixtures.TURN)
            val turn = api.client().chat.ask("Сколько НДС?")
            assertEquals("Готово", turn.answer)
            val keys = List(3) { api.take().getHeader("Idempotency-Key") }
            assertNotNull(keys[0])
            assertTrue(keys[0]!!.length >= 16)
            assertEquals(1, keys.toSet().size, "the same key on every attempt")
            api.enqueueJson(Fixtures.TURN)
            api.client().chat.ask("Ещё раз")
            assertNotEquals(keys[0], api.take().getHeader("Idempotency-Key"), "a new key for a new call")
            api.enqueueJson(Fixtures.TURN)
            api.client().chat.ask("Свой ключ", requestOptions = RequestOptions { idempotencyKey = "my-key-1" })
            assertEquals("my-key-1", api.take().getHeader("Idempotency-Key"))
        }
    }

    @Test
    fun aPostWithoutAnIdempotencyKeyIsNeverRetried() = runBlocking {
        MockApi().use { api ->
            api.server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "1"))
            assertFailsWith<ServiceUnavailableException> { api.client().chat.stop("d_1") }
            api.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
            assertFailsWith<TungoConnectionException> { api.client().chat.stop("d_1") }
            assertEquals(2, api.server.requestCount)
            assertTrue(api.sleeps.isEmpty())
            assertNull(api.take().getHeader("Idempotency-Key"))
            // The caller's key makes it repeatable.
            api.take()
            api.server.enqueue(MockResponse().setResponseCode(503))
            api.enqueueJson("""{"id":"d_1","stopped":true}""")
            val stopped = api.client().chat.stop("d_1", requestOptions = RequestOptions { idempotencyKey = "stop-1" })
            assertTrue(stopped.stopped)
            assertEquals(4, api.server.requestCount)
        }
    }

    @Test
    fun aBrokenPooledConnectionIsNotSilentlyResentForAPostWithoutAKey() = runBlocking {
        MockApi().use { api ->
            val client = api.client()
            api.enqueueJson("""{"id":"d_1","stopped":true}""")
            client.chat.stop("d_1") // opens the connection that the next call reuses
            api.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            api.server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
            assertFailsWith<TungoConnectionException> { client.chat.stop("d_1") }
            assertEquals(2, api.server.requestCount)
            val error = assertFailsWith<ServiceUnavailableException> { client.chat.stop("d_1") }
            assertEquals(0.0, error.retryAfterSeconds)
            assertEquals("0", error.headers["Retry-After"])
            assertEquals(3, api.server.requestCount)
            assertTrue(api.sleeps.isEmpty())
        }
    }

    @Test
    fun theNonStreamingChatCallAsksForJson() = runBlocking {
        MockApi().use { api ->
            api.enqueueJson(Fixtures.TURN)
            val turn = api.client().chat.create(ChatMessageRequest(message = "q", stream = true))
            assertEquals(2.0, turn.turnNo.toDouble())
            val request = api.take()
            assertEquals("application/json", request.getHeader("Accept"))
            assertEquals("""{"message":"q","stream":false}""", request.body.readUtf8())
        }
    }

    // ---- hooks and webhooks ----

    @Test
    fun sendHookSignsTheExactBytesAndSendsNoKey() = runBlocking {
        MockApi().use { api ->
            val payload = "{\"сумма\": 100.50,  \"n\":1}\n"
            api.enqueueJson(Fixtures.HOOK_ACCEPTED, 202)
            val accepted = api.client().sendHook("tok/1", payload, secret = "s3cret", eventId = "evt_1")
            assertTrue(accepted.accepted)
            assertEquals(listOf("run_1"), accepted.runIds)
            val request = api.take()
            assertEquals("POST", request.method)
            assertEquals("/hooks/tok%2F1", request.path)
            assertNull(request.getHeader("x-api-key"))
            assertEquals("evt_1", request.getHeader("X-Event-Id"))
            // The API reads Idempotency-Key before X-Event-Id: the event id must be the key.
            assertEquals("evt_1", request.getHeader("Idempotency-Key"))
            assertEquals("application/json", request.getHeader("Content-Type"))
            val sent = request.body.readByteArray()
            assertTrue(payload.toByteArray().contentEquals(sent))
            val signature = assertNotNull(request.getHeader("X-Signature"))
            assertEquals(TungoWebhooks.hookSignatureHeader(sent, "s3cret"), signature)
            assertTrue(TungoWebhooks.verifyHookSignature(sent, signature, "s3cret"))

            api.enqueueJson(Fixtures.HOOK_ACCEPTED, 202)
            api.client { apiKey = null }.sendHook("tok", byteArrayOf(1, 2, 3), contentType = "application/octet-stream")
            val unsigned = api.take()
            assertNull(unsigned.getHeader("X-Signature"))
            assertNull(unsigned.getHeader("x-api-key"))
            assertEquals("application/octet-stream", unsigned.getHeader("Content-Type"))
            assertNotNull(unsigned.getHeader("Idempotency-Key"))

            api.enqueueJson(Fixtures.HOOK_ACCEPTED, 202)
            api.client().sendHook("tok", "{}", eventId = "evt_1", requestOptions = RequestOptions { idempotencyKey = "own" })
            val own = api.take()
            assertEquals("own", own.getHeader("Idempotency-Key"), "a key the caller gives wins")
            assertEquals("evt_1", own.getHeader("X-Event-Id"))
            assertFailsWith<TungoValidationException> { api.client().sendHook("tok", "{}", secret = "") }
        }
    }

    @Test
    fun verifyWebhookUsesTheSecretOfTheClient() {
        val case = Vectors.cases("webhook_verification", "cases").first { it.obj("expect").flag("valid") }
        val now = case.getValue("now").toString().toLong()
        val event = TungoWebhooks.verify(case.str("body"), case.stringMap("headers"), case.str("secret")) { now }
        assertEquals(case.obj("expect").str("event_type"), event.type)
        // The client method uses the system clock, so the recorded message is too old by now.
        TungoClient { apiKey = "k"; webhookSecret = case.str("secret") }.use { client ->
            val stale = assertFailsWith<WebhookVerificationException> { client.verifyWebhook(case.str("body"), case.stringMap("headers")) }
            assertEquals(WebhookVerificationException.Reason.TIMESTAMP_TOO_OLD, stale.reason)
            val headers = case.stringMap("headers")
            val viaLookup = assertFailsWith<WebhookVerificationException> {
                client.verifyWebhook(case.str("body")) { name -> headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value }
            }
            assertEquals(WebhookVerificationException.Reason.TIMESTAMP_TOO_OLD, viaLookup.reason)
        }
        TungoClient { apiKey = "k" }.use { client ->
            val missing = assertFailsWith<WebhookVerificationException> { client.verifyWebhook("{}", emptyMap()) }
            assertEquals(WebhookVerificationException.Reason.INVALID_SECRET, missing.reason)
        }
    }

    @Test
    fun aFreshlySignedWebhookIsAcceptedWithTheSystemClock() {
        val secret = "whsec_MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
        val body = """{"id":"evt_1","type":"an.event.from.the.future","created_at":"2026-11-09T15:00:00.000Z","data":{"k":1}}"""
        val timestamp = (System.currentTimeMillis() / 1000).toString()
        val key = okio.ByteString.of(*"0123456789abcdef0123456789abcdef".toByteArray())
        val signature = okio.ByteString.of(*"msg_1.$timestamp.$body".toByteArray()).hmacSha256(key).base64()
        val headers = mapOf("Webhook-Id" to "msg_1", "WEBHOOK-TIMESTAMP" to timestamp, "webhook-signature" to "v1,AAAA v1,$signature")
        TungoClient { apiKey = "k"; webhookSecret = secret }.use { client ->
            val event = client.verifyWebhook(body, headers)
            assertEquals("an.event.from.the.future", event.type)
            val unknown = assertIs<kz.tungo.sdk.kotlin.models.WebhookEvent.Unknown>(event)
            assertEquals(JsonPrimitive("evt_1"), unknown.json["id"])
            val tampered = assertFailsWith<WebhookVerificationException> { client.verifyWebhook("$body ", headers) }
            assertEquals(WebhookVerificationException.Reason.SIGNATURE_MISMATCH, tampered.reason)
            assertFalse(signature in tampered.message.orEmpty())
        }
    }

    @Test
    fun toStringOfModelsHidesCredentials() {
        val secret = TungoJson.decoder.decodeFromString(
            kz.tungo.sdk.kotlin.models.WebhookSecret.serializer(),
            """{"endpoint_id":"ep_1","secret":"whsec_TOPSECRET"}""",
        )
        val text = secret.toString()
        assertFalse("TOPSECRET" in text, text)
        assertEquals("WebhookSecret(endpointId=ep_1, secret=[REDACTED])", text)
        // The value itself stays readable, and copies keep hiding it.
        assertEquals("whsec_TOPSECRET", secret.secret)
        assertFalse("TOPSECRET" in secret.copy(endpointId = "ep_2").toString())
        assertFalse("TOPSECRET" in listOf(secret).toString())
    }

    // ---- waiting helpers ----

    @Test
    fun waitForRunPollsUntilAFinalState() = runBlocking {
        MockApi().use { api ->
            api.enqueueJson(Fixtures.run("queued"))
            api.enqueueJson(Fixtures.run("running"))
            api.enqueueJson(Fixtures.run("a_state_from_the_future"))
            api.enqueueJson(Fixtures.run("done"))
            val run = api.client().automations.waitForRun("run_1")
            assertEquals("done", run.state.value)
            assertEquals(4, api.server.requestCount)
            assertEquals(listOf(1000L, 1500L, 2250L), api.sleeps.toList())
        }
    }

    @Test
    fun waitForRunGivesUpAtTheDeadlineWithALocalTimeout() = runBlocking {
        MockApi().use { api ->
            repeat(20) { api.enqueueJson(Fixtures.run("running")) }
            val error = assertFailsWith<TungoTimeoutException> { api.client().automations.waitForRun("run_1", timeout = 10.seconds) }
            assertEquals(10_000L, api.sleeps.sum())
        }
    }

    @Test
    fun runScenarioAndWaitStartsTheRunAndWaits() = runBlocking {
        MockApi().use { api ->
            api.enqueueJson(Fixtures.run("queued"), 202)
            api.enqueueJson(Fixtures.run("error"))
            val run = api.client().automations.runScenarioAndWait("sc_1")
            assertEquals("error", run.state.value)
            val start = api.take()
            assertEquals("POST", start.method)
            assertEquals("/v1/companies/co_1/automations/scenarios/sc_1/run", start.path)
            assertEquals("/v1/companies/co_1/automations/runs/run_1", api.take().path)
        }
    }

    @Test
    fun waitForBatchPollsWhileTheJobRuns() = runBlocking {
        MockApi().use { api ->
            api.enqueueJson(Fixtures.batch(true))
            api.enqueueJson(Fixtures.batch(false))
            val job = api.client().batches.waitForBatch("b_1")
            assertFalse(job.running)
            assertEquals(listOf(1000L), api.sleeps.toList())
        }
    }

    @Test
    fun unknownJsonObjectsSurviveInTaggedUnions() {
        val event = TungoJson.decoder.decodeFromString(
            kz.tungo.sdk.kotlin.models.ChatEvent.serializer(),
            """{"type":"brand_new","payload":{"n":1}}""",
        )
        val unknown = assertIs<kz.tungo.sdk.kotlin.models.ChatEvent.Unknown>(event)
        assertEquals("brand_new", unknown.type)
        assertEquals(JsonObject(mapOf("n" to JsonPrimitive(1))), unknown.json["payload"])
    }
}
