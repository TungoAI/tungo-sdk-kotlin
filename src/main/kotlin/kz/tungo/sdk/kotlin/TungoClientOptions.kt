package kz.tungo.sdk.kotlin

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kz.tungo.sdk.kotlin.errors.TungoConfigException
import okhttp3.OkHttpClient

/**
 * Immutable configuration of a [TungoClient].
 *
 * Build it with the [TungoClient] factory function:
 *
 *     val client = TungoClient {
 *         apiKey = "tg_..."
 *         maxRetries = 3
 *     }
 */
public class TungoClientOptions private constructor(builder: Builder) {
    /** API key. Sent only to the operations that require it. */
    public val apiKey: String? = builder.apiKey ?: env("TUNGO_API_KEY")

    /** Base URL of the API, without a trailing slash. */
    public val baseUrl: String = (builder.baseUrl ?: env("TUNGO_BASE_URL") ?: TungoSdk.DEFAULT_BASE_URL).trimEnd('/')

    /** Secret used by [TungoClient.verifyWebhook] when none is passed to the call. */
    public val webhookSecret: String? = builder.webhookSecret ?: env("TUNGO_WEBHOOK_SECRET")

    /** Company used by company-scoped operations when the call does not name one. */
    public val companyId: String? = builder.companyId ?: env("TUNGO_COMPANY_ID")

    /** Workspace used by workspace-scoped operations when the call does not name one. */
    public val workspaceId: String? = builder.workspaceId ?: env("TUNGO_WORKSPACE_ID")

    /**
     * Total time allowed for one attempt of a request, including reading the response body.
     * When it is null the default applies: 60 s, or 600 s for non-streaming chat turns.
     */
    public val timeout: Duration? = builder.timeout

    /** Time allowed to establish a connection. */
    public val connectTimeout: Duration = builder.connectTimeout

    /** Longest silence tolerated on an event stream before it fails with a timeout. */
    public val streamIdleTimeout: Duration = builder.streamIdleTimeout

    /** How many times a failed request is repeated when repeating it is safe. */
    public val maxRetries: Int = builder.maxRetries

    /** Headers added to every request. */
    public val defaultHeaders: Map<String, String> = builder.headers.toMap()

    /** The OkHttp client supplied by the caller, or null when the SDK creates its own. */
    public val httpClient: OkHttpClient? = builder.httpClient

    internal val hooks: InternalHooks = builder.hooks

    init {
        if (timeout != null && !timeout.isPositive()) throw TungoConfigException("timeout must be positive")
        if (!connectTimeout.isPositive()) throw TungoConfigException("connectTimeout must be positive")
        if (!streamIdleTimeout.isPositive()) throw TungoConfigException("streamIdleTimeout must be positive")
        if (maxRetries < 0) throw TungoConfigException("maxRetries must not be negative")
        if (apiKey != null && !isHeaderSafe(apiKey)) {
            throw TungoConfigException("The API key contains characters that cannot be sent in an HTTP header")
        }
        for ((name, value) in defaultHeaders) checkHeader(name, value)
    }

    /** Returns a builder pre-filled with these options. */
    public fun newBuilder(): Builder {
        val b = Builder()
        b.apiKey = apiKey
        b.baseUrl = baseUrl
        b.webhookSecret = webhookSecret
        b.companyId = companyId
        b.workspaceId = workspaceId
        b.timeout = timeout
        b.connectTimeout = connectTimeout
        b.streamIdleTimeout = streamIdleTimeout
        b.maxRetries = maxRetries
        b.headers.putAll(defaultHeaders)
        b.httpClient = httpClient
        b.hooks = hooks
        return b
    }

    /** The API key and the webhook secret are never printed. */
    override fun toString(): String =
        "TungoClientOptions(baseUrl=$baseUrl, apiKey=${redacted(apiKey)}, webhookSecret=${redacted(webhookSecret)}, " +
            "companyId=$companyId, workspaceId=$workspaceId, timeout=$timeout, maxRetries=$maxRetries)"

    /** Mutable builder of [TungoClientOptions]. */
    @TungoDsl
    public class Builder internal constructor() {
        /** API key. Default: the environment variable TUNGO_API_KEY. */
        public var apiKey: String? = null

        /** Base URL. Default: the environment variable TUNGO_BASE_URL, then [TungoSdk.DEFAULT_BASE_URL]. */
        public var baseUrl: String? = null

        /** Webhook signing secret (whsec_...). Default: the environment variable TUNGO_WEBHOOK_SECRET. */
        public var webhookSecret: String? = null

        /** Default company id. Default: the environment variable TUNGO_COMPANY_ID. */
        public var companyId: String? = null

        /** Default workspace id. Default: the environment variable TUNGO_WORKSPACE_ID. */
        public var workspaceId: String? = null

        /** Total timeout of one attempt. Default: 60 s, or 600 s for non-streaming chat turns. */
        public var timeout: Duration? = null

        /** Connect timeout. Default: 30 s. */
        public var connectTimeout: Duration = 30.seconds

        /** Idle timeout of event streams. Default: 120 s. */
        public var streamIdleTimeout: Duration = 120.seconds

        /** Maximum number of retries. Default: 2 (three attempts). */
        public var maxRetries: Int = 2

        /**
         * OkHttp client to send requests with. Its connection pool, proxy, interceptors and TLS
         * settings are used; its timeouts are replaced by the timeouts of the SDK, and redirects are
         * never followed whatever its own setting is. A client supplied
         * here is not shut down by [TungoClient.close].
         */
        public var httpClient: OkHttpClient? = null

        internal val headers: MutableMap<String, String> = LinkedHashMap()
        internal var hooks: InternalHooks = InternalHooks()

        /** Adds a header that is sent with every request. */
        public fun header(name: String, value: String) {
            headers[name] = value
        }

        internal fun build(): TungoClientOptions = TungoClientOptions(this)
    }

    internal companion object {
        fun env(name: String): String? = try {
            System.getenv(name)?.takeIf { it.isNotEmpty() }
        } catch (ignored: SecurityException) {
            null
        }

        fun redacted(secret: String?): String = if (secret.isNullOrEmpty()) "null" else "***"

        fun isHeaderSafe(value: String): Boolean = value.all { it == '\t' || it in ' '..'~' }

        fun checkHeader(name: String, value: String) {
            val nameOk = name.isNotEmpty() && name.all { it in '!'..'~' && it != ':' }
            if (!nameOk) throw TungoConfigException("Invalid header name")
            if (!isHeaderSafe(value)) throw TungoConfigException("The value of header '$name' contains characters that cannot be sent")
        }
    }
}

/** Test seams: the clock, the random source and the sleep function of the retry and polling loops. */
internal class InternalHooks(
    val sleep: (suspend (Long) -> Unit)? = null,
    val random: () -> Double = { Math.random() },
    val nowMillis: () -> Long = { System.currentTimeMillis() },
)
