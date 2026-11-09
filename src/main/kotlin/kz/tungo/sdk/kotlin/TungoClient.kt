package kz.tungo.sdk.kotlin

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs
import kotlin.math.roundToLong

data class SSEEvent(val event: String, val raw: String)

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
}

object TungoWebhooks {
    fun signHookPayload(rawBody: String, secret: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        val digest = mac.doFinal(rawBody.toByteArray(StandardCharsets.UTF_8))
        return "sha256=" + digest.joinToString("") { "%02x".format(it) }
    }

    fun verifyHookSignature(rawBody: String, signature: String, secret: String): Boolean {
        val expected = signHookPayload(rawBody, secret)
        return MessageDigest.isEqual(
            expected.toByteArray(StandardCharsets.UTF_8),
            signature.trim().toByteArray(StandardCharsets.UTF_8),
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
    baseUrl: String = System.getenv("TUNGO_BASE_URL") ?: "https://api.tungo.ai",
    private val workspaceId: String? = System.getenv("TUNGO_WORKSPACE_ID"),
    private val companyId: String? = System.getenv("TUNGO_COMPANY_ID"),
) {
    private val baseUrl: String = baseUrl.trimEnd('/')

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
    fun ask(prompt: String, companyId: String? = null): Map<String, Any?> {
        val params = if (!companyId.isNullOrEmpty()) mapOf("co" to companyId) else emptyMap()
        return chat.create(mapOf("message" to prompt, "stream" to false), params)
    }

    fun request(
        method: String,
        path: String,
        query: Map<String, String>,
        body: Map<String, Any?>?,
        auth: String,
        idempotent: Boolean,
        retrySafe: Boolean,
    ): Map<String, Any?> = emptyMap()

    fun requestStream(
        method: String,
        path: String,
        query: Map<String, String>,
        body: Map<String, Any?>?,
        auth: String,
    ): Sequence<SSEEvent> = emptySequence()
}
