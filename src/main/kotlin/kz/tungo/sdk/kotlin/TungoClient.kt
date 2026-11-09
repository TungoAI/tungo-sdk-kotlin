package kz.tungo.sdk.kotlin

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64
import java.util.Locale
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToLong

data class SSEEvent(val event: String, val raw: String)
data class RateLimitInfo(val limit: Long, val remaining: Long, val reset: Long)

open class TungoError(
    val status: Int,
    val code: String,
    override val message: String,
    val hint: String? = null,
    val requestId: String? = null,
    val retryAfter: Double? = null,
) : RuntimeException(message)

object TungoMoney {
    fun toTiyn(amount: Double): Long = (amount * 100.0).roundToLong()
    fun toTenge(tiyn: Long): Double = tiyn / 100.0

    fun formatTiyn(tiyn: Long, currency: String = "KZT"): String {
        val symbol = when (currency.trim().uppercase(Locale.ROOT)) {
            "", "KZT" -> "₸"
            "USD" -> "$"
            "EUR" -> "€"
            "RUB" -> "₽"
            else -> currency.trim().uppercase(Locale.ROOT)
        }
        val neg = tiyn < 0
        val a = abs(tiyn)
        val whole = (a / 100).toString()
        val frac = a % 100
        val grouped = buildString {
            for (i in whole.indices) {
                if (i > 0 && (whole.length - i) % 3 == 0) append(' ')
                append(whole[i])
            }
        }
        val sign = if (neg) "-" else ""
        return String.format(Locale.ROOT, "%s%s,%02d %s", sign, grouped, frac, symbol)
    }

    /** Проверяет контрольную сумму 12-значного БИН или ИИН Республики Казахстан (модуль 11, два прохода). */
    fun isValidBinIin(value: String): Boolean {
        if (value.length != 12 || !value.all { it.isDigit() }) return false
        val digits = IntArray(12) { value[it] - '0' }
        val w1 = intArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11)
        var control = (0 until 11).sumOf { digits[it] * w1[it] } % 11
        if (control == 10) {
            val w2 = intArrayOf(3, 4, 5, 6, 7, 8, 9, 10, 11, 1, 2)
            control = (0 until 11).sumOf { digits[it] * w2[it] } % 11
        }
        return control < 10 && control == digits[11]
    }
}

object TungoWebhooks {
    fun signHookPayload(rawBody: String, secret: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        val digest = mac.doFinal(rawBody.toByteArray(StandardCharsets.UTF_8))
        return "sha256=" + digest.joinToString("") { "%02x".format(it) }
    }

    fun verifyHookSignature(rawBody: String, signature: String, secret: String): Boolean {
        val trimmed = signature.trim()
        if (trimmed.isEmpty() || secret.isEmpty()) return false
        val hex = trimmed.removePrefix("sha256=").lowercase(Locale.ROOT)
        val expected = signHookPayload(rawBody, secret).removePrefix("sha256=").lowercase(Locale.ROOT)
        return MessageDigest.isEqual(
            expected.toByteArray(StandardCharsets.UTF_8),
            hex.toByteArray(StandardCharsets.UTF_8),
        )
    }

    fun verifyWebhookSignature(
        rawBody: String,
        headers: Map<String, String>,
        secret: String,
        nowUnix: Long? = null,
        toleranceSec: Long = 300L,
    ): String {
        val norm = headers.entries.associate { it.key.lowercase(Locale.ROOT) to it.value }
        val msgId = norm["svix-id"] ?: norm["webhook-id"] ?: throw TungoError(0, "webhook_error", "Missing svix-id")
        val msgTs = norm["svix-timestamp"] ?: norm["webhook-timestamp"] ?: throw TungoError(0, "webhook_error", "Missing svix-timestamp")
        val msgSig = norm["svix-signature"] ?: norm["webhook-signature"] ?: throw TungoError(0, "webhook_error", "Missing svix-signature")
        val ts = msgTs.toLongOrNull() ?: throw TungoError(0, "webhook_error", "Invalid svix-timestamp")
        if (nowUnix != null && toleranceSec > 0 && abs(nowUnix - ts) > toleranceSec) {
            throw TungoError(0, "webhook_error", "Webhook timestamp outside tolerance")
        }
        val keyBytes = Base64.getDecoder().decode(secret.removePrefix("whsec_"))
        val signed = "$msgId.$msgTs.$rawBody"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(keyBytes, "HmacSHA256"))
        val expectedB64 = Base64.getEncoder().encodeToString(mac.doFinal(signed.toByteArray(StandardCharsets.UTF_8)))
        val matched = msgSig.trim().split(Regex("\\s+")).any { part ->
            part.startsWith("v1,") && MessageDigest.isEqual(
                expectedB64.toByteArray(StandardCharsets.UTF_8),
                part.removePrefix("v1,").toByteArray(StandardCharsets.UTF_8),
            )
        }
        if (!matched) throw TungoError(0, "webhook_error", "Invalid svix-signature")
        return rawBody
    }
}

class TungoClient(
    private val apiKey: String? = System.getenv("TUNGO_API_KEY"),
    baseUrl: String = System.getenv("TUNGO_BASE_URL") ?: "https://api.tungo.kz",
    private val workspaceId: String? = System.getenv("TUNGO_WORKSPACE_ID"),
    private val companyId: String? = System.getenv("TUNGO_COMPANY_ID"),
    private val maxRetries: Int = 2,
) {
    private val baseUrl: String = baseUrl.trimEnd('/')
    private val httpClient: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()
    @Volatile
    var lastRateLimit: RateLimitInfo? = null
        private set

    val workspaces: WorkspacesResource = WorkspacesResource(this)
    val companies: CompaniesResource = CompaniesResource(this)
    val platform: PlatformResource = PlatformResource(this)
    val apiKeys: ApiKeysResource = ApiKeysResource(this)
    val webhooks: WebhooksResource = WebhooksResource(this)
    val chat: ChatResource = ChatResource(this)
    val proposals: ProposalsResource = ProposalsResource(this)
    val feed: FeedResource = FeedResource(this)
    val batches: BatchesResource = BatchesResource(this)
    val esf: EsfResource = EsfResource(this)
    val forms: FormsResource = FormsResource(this)
    val onec: OnecResource = OnecResource(this)
    val automations: AutomationsResource = AutomationsResource(this)

    fun resolveCompanyId(explicit: String? = null): String =
        explicit?.takeIf { it.isNotEmpty() } ?: companyId?.takeIf { it.isNotEmpty() }
            ?: throw TungoError(0, "missing_company_id", "Не указан companyId")

    fun resolveWorkspaceId(explicit: String? = null): String =
        explicit?.takeIf { it.isNotEmpty() } ?: workspaceId?.takeIf { it.isNotEmpty() }
            ?: throw TungoError(0, "missing_workspace_id", "Не указан workspaceId")

    /** Быстрый синхронный запрос к ИИ-бухгалтеру (stream = false). */
    fun ask(prompt: String, companyId: String? = null, dialogId: String? = null): Map<String, Any?> {
        val params = if (!companyId.isNullOrEmpty()) mapOf("co" to companyId) else emptyMap()
        val body = mutableMapOf<String, Any?>("message" to prompt, "stream" to false)
        if (!dialogId.isNullOrEmpty()) body["dialog_id"] = dialogId
        return chat.create(body, params)
    }

    /** Отправить событие во входящий хук /hooks/{token} с HMAC-SHA256 подписью. */
    fun sendHook(token: String, payload: Map<String, Any?>, secret: String? = null): Map<String, Any?> {
        val params = mutableMapOf<String, String>()
        if (!secret.isNullOrEmpty()) {
            params["X-Signature"] = TungoWebhooks.signHookPayload(toJson(payload), secret)
        }
        return automations.postHook(token, payload, params)
    }

    /** Дождаться завершения запуска сценария (state in done/error/cancelled/skipped). */
    fun waitForRun(runId: String, companyId: String? = null, timeoutMs: Long = 120_000L): Map<String, Any?> {
        val params = if (!companyId.isNullOrEmpty()) mapOf("co" to companyId) else emptyMap()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val run = automations.getRun(runId, params)
            val st = run["state"] as? String
            if (st == "done" || st == "error" || st == "cancelled" || st == "skipped") return run
            if (System.currentTimeMillis() >= deadline) {
                throw TungoError(0, "timeout", "Превышено время ожидания запуска $runId")
            }
            Thread.sleep(1000L)
        }
    }

    /** Дождаться завершения задания пачки (running == false). */
    fun waitForBatch(batchId: String, companyId: String? = null, timeoutMs: Long = 120_000L): Map<String, Any?> {
        val params = if (!companyId.isNullOrEmpty()) mapOf("co" to companyId) else emptyMap()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val job = batches.get(batchId, params)
            if (job["running"] != true) return job
            if (System.currentTimeMillis() >= deadline) {
                throw TungoError(0, "timeout", "Превышено время ожидания пачки $batchId")
            }
            Thread.sleep(1000L)
        }
    }

    fun parseSse(rawStream: String): List<SSEEvent> {
        val events = mutableListOf<SSEEvent>()
        var eventType = ""
        val dataLines = mutableListOf<String>()
        for (line in rawStream.split(Regex("\\r?\\n"))) {
            if (line.isEmpty()) {
                if (eventType.isNotEmpty() || dataLines.isNotEmpty()) {
                    events.add(SSEEvent(eventType.ifEmpty { "message" }, dataLines.joinToString("\n")))
                    eventType = ""
                    dataLines.clear()
                }
                continue
            }
            if (line.startsWith(":")) continue
            when {
                line.startsWith("event:") -> eventType = line.removePrefix("event:").trim()
                line.startsWith("data:") -> dataLines.add(line.removePrefix("data:").removePrefix(" "))
            }
        }
        if (eventType.isNotEmpty() || dataLines.isNotEmpty()) {
            events.add(SSEEvent(eventType.ifEmpty { "message" }, dataLines.joinToString("\n")))
        }
        return events
    }

    fun request(
        method: String,
        path: String,
        query: Map<String, String>,
        body: Map<String, Any?>?,
        auth: String,
        idempotent: Boolean,
        retrySafe: Boolean,
    ): Map<String, Any?> {
        val raw = executeHttp(method, path, query, body, auth, idempotent, retrySafe, "application/json")
        if (raw.isBlank()) return emptyMap()
        return mapOf("raw" to raw)
    }

    fun requestStream(
        method: String,
        path: String,
        query: Map<String, String>,
        body: Map<String, Any?>?,
        auth: String,
    ): Sequence<SSEEvent> {
        val raw = executeHttp(method, path, query, body, auth, false, false, "text/event-stream")
        return parseSse(raw).asSequence()
    }

    private fun executeHttp(
        method: String,
        path: String,
        query: Map<String, String>,
        body: Map<String, Any?>?,
        auth: String,
        idempotent: Boolean,
        retrySafe: Boolean,
        accept: String,
    ): String {
        val q = query.toMutableMap()
        val idemKey = q.remove("idempotency_key") ?: if (idempotent) UUID.randomUUID().toString() else null
        val sigHeader = q.remove("X-Signature")
        val qs = q.entries.joinToString("&") {
            "${URLEncoder.encode(it.key, StandardCharsets.UTF_8)}=${URLEncoder.encode(it.value, StandardCharsets.UTF_8)}"
        }
        val fullUrl = if (qs.isEmpty()) "$baseUrl$path" else "$baseUrl$path?$qs"
        val jsonBody = body?.let { toJson(it) }
        val maxAttempts = if (retrySafe || idempotent || method == "GET") (1 + maxRetries) else 1

        for (attempt in 0 until maxAttempts) {
            val rb = HttpRequest.newBuilder(URI.create(fullUrl))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", accept)
                .header("User-Agent", "tungo-sdk-kotlin/0.1.0")
            if (auth == "key") {
                val key = apiKey?.takeIf { it.isNotEmpty() }
                    ?: throw TungoError(401, "missing_api_key", "API ключ не задан")
                rb.header("x-api-key", key).header("Authorization", "Bearer $key")
            }
            if (!idemKey.isNullOrEmpty()) rb.header("Idempotency-Key", idemKey)
            if (!sigHeader.isNullOrEmpty()) rb.header("X-Signature", sigHeader)
            if (jsonBody != null) {
                rb.header("Content-Type", "application/json")
                rb.method(method, HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
            } else {
                rb.method(method, HttpRequest.BodyPublishers.noBody())
            }
            val resp = httpClient.send(rb.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            val lim = resp.headers().firstValue("x-ratelimit-limit").orElse(null)?.toLongOrNull()
            val rem = resp.headers().firstValue("x-ratelimit-remaining").orElse(null)?.toLongOrNull()
            val rst = resp.headers().firstValue("x-ratelimit-reset").orElse(null)?.toLongOrNull()
            if (lim != null && rem != null && rst != null) {
                lastRateLimit = RateLimitInfo(lim, rem, rst)
            }
            val status = resp.statusCode()
            if (status in 200..299) return resp.body()
            val retryAfter = resp.headers().firstValue("retry-after").orElse(null)?.toDoubleOrNull()
            if (attempt + 1 < maxAttempts && status in listOf(429, 502, 503, 504)) {
                val waitMs = retryAfter?.let { (it * 1000).toLong() } ?: min(250L * (1L shl attempt), 8000L)
                Thread.sleep(waitMs)
                continue
            }
            val reqId = resp.headers().firstValue("x-request-id").orElse(null)
            throw TungoError(status, "api_error", resp.body(), null, reqId, retryAfter)
        }
        throw TungoError(0, "max_retries_exceeded", "Превышено число попыток")
    }

    private fun toJson(value: Any?): String = when (value) {
        null -> "null"
        is String -> "\"${value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")}\""
        is Number, is Boolean -> value.toString()
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") { "${toJson(it.key.toString())}:${toJson(it.value)}" }
        is Iterable<*> -> value.joinToString(",", "[", "]") { toJson(it) }
        else -> toJson(value.toString())
    }
}
