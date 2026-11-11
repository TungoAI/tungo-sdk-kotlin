package kz.tungo.sdk.kotlin

import java.io.Closeable
import kotlin.time.Duration
import kz.tungo.sdk.kotlin.errors.TungoValidationException
import kz.tungo.sdk.kotlin.errors.WebhookVerificationException
import kz.tungo.sdk.kotlin.internal.HttpEngine
import kz.tungo.sdk.kotlin.models.AutomationHookAccepted
import kz.tungo.sdk.kotlin.models.WebhookEvent
import kz.tungo.sdk.kotlin.resources.ApiKeysResource
import kz.tungo.sdk.kotlin.resources.AutomationsResource
import kz.tungo.sdk.kotlin.resources.BatchesResource
import kz.tungo.sdk.kotlin.resources.ChatResource
import kz.tungo.sdk.kotlin.resources.CompaniesResource
import kz.tungo.sdk.kotlin.resources.EsfResource
import kz.tungo.sdk.kotlin.resources.FeedResource
import kz.tungo.sdk.kotlin.resources.FormsResource
import kz.tungo.sdk.kotlin.resources.OnecResource
import kz.tungo.sdk.kotlin.resources.PlatformResource
import kz.tungo.sdk.kotlin.resources.ProposalsResource
import kz.tungo.sdk.kotlin.resources.WebhooksResource
import kz.tungo.sdk.kotlin.resources.WorkspacesResource
import kz.tungo.sdk.kotlin.webhooks.TungoWebhooks
import okhttp3.Dispatcher
import okhttp3.OkHttpClient

/**
 * Client of the Tungo Platform API.
 *
 * A client is immutable and safe to share between coroutines and threads; create one and reuse
 * it. Every operation is a suspending function, so a call is cancelled by cancelling its
 * coroutine.
 *
 *     val client = TungoClient { apiKey = "tg_..." }
 *     val companies = client.companies.list()
 */
public class TungoClient internal constructor(
    /** The configuration of this client. */
    public val options: TungoClientOptions,
    private val httpClient: OkHttpClient,
    private val ownsHttpClient: Boolean,
) : Closeable {
    internal val engine: HttpEngine = HttpEngine(options, httpClient)

    /** Организации (workspace), доступные ключу API */
    public val workspaces: WorkspacesResource = WorkspacesResource(engine)

    /** Компании организации и их карточки */
    public val companies: CompaniesResource = CompaniesResource(engine)

    /** Баланс платформы, книга движений, отчёты о расходе, лимиты, аудит и тарифы моделей */
    public val platform: PlatformResource = PlatformResource(engine)

    /** Сведения о текущем ключе API и статистика его расхода */
    public val apiKeys: ApiKeysResource = ApiKeysResource(engine)

    /** Исходящие вебхуки организации (Svix): адреса, каталог событий, доставки и проверка подписи */
    public val webhooks: WebhooksResource = WebhooksResource(engine)

    /** Диалоги с ИИ-бухгалтером: вопросы, потоковый чат (SSE), подсчёт токенов и история */
    public val chat: ChatResource = ChatResource(engine)

    /** Предложения документов ИИ-бухгалтера: просмотр, одобрение записи в 1С и отклонение */
    public val proposals: ProposalsResource = ProposalsResource(engine)

    /** Лента событий и предложений компании (страницы и живой поток SSE) */
    public val feed: FeedResource = FeedResource(engine)

    /** Пачки обработки банковских выписок, входящих ЭСФ и DirectBank */
    public val batches: BatchesResource = BatchesResource(engine)

    /** Входящие и исходящие электронные счета-фактуры (ИС ЭСФ) */
    public val esf: EsfResource = EsfResource(engine)

    /** Налоговые формы (ФНО 100.00 и др.): сборка, проверка ИСНА и разрывы данных */
    public val forms: FormsResource = FormsResource(engine)

    /** Состояние подключения компании к базе 1С */
    public val onec: OnecResource = OnecResource(engine)

    /** Автоматизации компании: входящие хуки, сценарии, цепочки шагов, запуски и уровень автономности ИИ */
    public val automations: AutomationsResource = AutomationsResource(engine)

    /**
     * Returns a client with some options changed. It shares the connection pool of this client,
     * and closing it does nothing.
     */
    public fun withOptions(block: TungoClientOptions.Builder.() -> Unit): TungoClient {
        val changed = options.newBuilder().apply(block).build()
        return TungoClient(changed, changed.httpClient ?: httpClient, false)
    }

    /**
     * Verifies the signature and the timestamp of an outgoing webhook (Standard Webhooks / Svix)
     * and returns the typed event.
     *
     * @param body the raw request body, exactly as received
     * @param headers the request headers; names are matched case-insensitively
     * @param secret the signing secret (whsec_...); by default the webhook secret of the client
     * @param tolerance the largest accepted distance between the timestamp and the system clock
     * @throws WebhookVerificationException when the webhook cannot be trusted
     */
    public fun verifyWebhook(
        body: String,
        headers: Map<String, String>,
        secret: String? = null,
        tolerance: Duration = TungoWebhooks.DEFAULT_TOLERANCE,
    ): WebhookEvent = TungoWebhooks.verify(body, headers, webhookSecret(secret), tolerance)

    /** Variant of [verifyWebhook] that reads the headers through a lookup function such as request::getHeader. */
    public fun verifyWebhook(
        body: String,
        secret: String? = null,
        tolerance: Duration = TungoWebhooks.DEFAULT_TOLERANCE,
        header: (name: String) -> String?,
    ): WebhookEvent = TungoWebhooks.verify(body.toByteArray(Charsets.UTF_8), webhookSecret(secret), tolerance, TungoWebhooks.SYSTEM_CLOCK, header)

    /**
     * Sends an event to an incoming hook (POST /hooks/{token}). The API key is never sent. When a
     * secret is given, the exact bytes of the payload are signed in the X-Signature header.
     *
     * @param token the hook token
     * @param payload the body to send, byte for byte
     * @param secret the signing secret of the hook, when it has one
     * @param contentType media type of the payload
     * @param eventId value of the X-Event-Id header, used by the API to drop duplicates
     */
    public suspend fun sendHook(
        token: String,
        payload: ByteArray,
        secret: String? = null,
        contentType: String = "application/json",
        eventId: String? = null,
        requestOptions: RequestOptions = RequestOptions.Default,
    ): AutomationHookAccepted {
        if (secret != null && secret.isEmpty()) throw TungoValidationException("The hook secret must not be empty")
        val signature = secret?.let { TungoWebhooks.hookSignatureHeader(payload, it) }
        return automations.postHook(token = token, body = payload, contentType = contentType, eventId = eventId, signature = signature, requestOptions = requestOptions)
    }

    /** Variant of [sendHook] for a text payload, sent as UTF-8. */
    public suspend fun sendHook(
        token: String,
        payload: String,
        secret: String? = null,
        contentType: String = "application/json",
        eventId: String? = null,
        requestOptions: RequestOptions = RequestOptions.Default,
    ): AutomationHookAccepted = sendHook(token, payload.toByteArray(Charsets.UTF_8), secret, contentType, eventId, requestOptions)

    /**
     * Releases the threads and connections of this client. A client built around a caller-supplied
     * OkHttpClient, or derived with [withOptions], leaves them alone.
     */
    override fun close() {
        if (!ownsHttpClient) return
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
    }

    /** The API key is never printed. */
    override fun toString(): String = "TungoClient($options)"

    private fun webhookSecret(explicit: String?): String = explicit ?: options.webhookSecret
        ?: throw WebhookVerificationException(
            WebhookVerificationException.Reason.INVALID_SECRET,
            "No webhook secret: pass it to the call, set webhookSecret on the client or TUNGO_WEBHOOK_SECRET",
        )

    internal companion object {
        fun create(options: TungoClientOptions): TungoClient {
            val supplied = options.httpClient
            if (supplied != null) return TungoClient(options, supplied, false)
            val dispatcher = Dispatcher()
            dispatcher.maxRequests = 256
            dispatcher.maxRequestsPerHost = 256
            return TungoClient(options, OkHttpClient.Builder().dispatcher(dispatcher).build(), true)
        }
    }
}

/**
 * Creates a [TungoClient].
 *
 *     val client = TungoClient {
 *         apiKey = "tg_..."        // default: environment variable TUNGO_API_KEY
 *         timeout = 30.seconds
 *         maxRetries = 3
 *     }
 */
public fun TungoClient(block: TungoClientOptions.Builder.() -> Unit = {}): TungoClient =
    TungoClient.create(TungoClientOptions.Builder().apply(block).build())
