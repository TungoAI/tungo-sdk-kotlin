package kz.tungo.sdk.kotlin.resources

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kz.tungo.sdk.kotlin.NullableField
import kz.tungo.sdk.kotlin.RequestOptions
import kz.tungo.sdk.kotlin.ResponseHeaders
import kz.tungo.sdk.kotlin.errors.ApiErrorData
import kz.tungo.sdk.kotlin.errors.TungoApiException
import kz.tungo.sdk.kotlin.errors.TungoDecodeException
import kz.tungo.sdk.kotlin.internal.Errors
import kz.tungo.sdk.kotlin.internal.TungoJson
import kz.tungo.sdk.kotlin.models.ChatEvent
import kz.tungo.sdk.kotlin.models.ChatEventDone
import kz.tungo.sdk.kotlin.models.ChatEventError
import kz.tungo.sdk.kotlin.models.ChatEventProgress
import kz.tungo.sdk.kotlin.models.ChatEventText
import kz.tungo.sdk.kotlin.models.ChatMessageRequest
import kz.tungo.sdk.kotlin.models.ChatTurnError
import kz.tungo.sdk.kotlin.models.ChatTurnResult
import kz.tungo.sdk.kotlin.models.ChatUsage
import kz.tungo.sdk.kotlin.streaming.StreamResponse

/** Id of the dialog of a chat stream (response header X-Dialog-Id). */
public val StreamResponse<ChatEvent>.dialogId: String? get() = headers["X-Dialog-Id"]

/** Number of the turn of a chat stream (response header X-Turn-No). */
public val StreamResponse<ChatEvent>.turnNo: Int? get() = headers["X-Turn-No"]?.trim()?.toIntOrNull()

/** The outcome of one streamed chat turn. */
public data class ChatTurn(
    /** Id of the dialog the turn belongs to. */
    public val dialogId: String?,
    /** Number of the turn inside the dialog. */
    public val turnNo: Int?,
    /** The final answer. */
    public val text: String,
    /** Why the turn stopped, when the done event said so. */
    public val stopReason: String?,
    /** Cost of the turn in tiyn, when the API reported it. */
    public val costTiyn: Long?,
    /** Token usage of the turn, when the API reported it. */
    public val usage: ChatUsage?,
    /** Id of the question the assistant is waiting an answer for. */
    public val choiceId: String?,
    /** Number of events received, keep-alives excluded. */
    public val eventCount: Int,
)

/**
 * Folds the events of one chat turn into the final answer, the way the API defines it: text
 * deltas are concatenated; a progress event withdraws the text of its step; the answer of the
 * done event, when present, replaces the accumulated text.
 *
 * A turn is complete only when its done event arrived ([isComplete]). A stream that ends earlier
 * was cut off: [text] then holds only a part of the answer and [toTurn] refuses to present it as
 * the outcome of the turn.
 *
 * An instance is not safe for concurrent use.
 */
public class ChatStreamAccumulator {
    private val segments = ArrayList<Pair<Double?, String>>()
    private var answer: String? = null

    /** Number of events added. */
    public var eventCount: Int = 0
        private set

    /** Why the turn stopped; null until the done event arrives. */
    public var stopReason: String? = null
        private set

    /** Cost of the turn in tiyn, from the done event. */
    public var costTiyn: Long? = null
        private set

    /** Token usage of the turn, from the done event. */
    public var usage: ChatUsage? = null
        private set

    /** Id of the question the assistant asked, from the done event. */
    public var choiceId: String? = null
        private set

    /** The first error event of the turn that has the shape the contract describes, if any. */
    public var error: ChatEventError? = null
        private set

    /**
     * The first error event of the turn, if any: a [ChatEventError], or a [ChatEvent.Unknown]
     * with the type "error" when the server sent an error whose data does not have that shape.
     */
    public var errorEvent: ChatEvent? = null
        private set

    /** True once the done event arrived; false while the turn runs and for a stream that was cut off. */
    public var isComplete: Boolean = false
        private set

    /** The answer accumulated so far; the final answer only when [isComplete] is true. */
    public val text: String get() = answer ?: segments.joinToString("") { it.second }

    /** Adds the next event of the stream. */
    public fun add(event: ChatEvent) {
        eventCount++
        when (event) {
            is ChatEventText -> segments.add(event.step to event.delta)
            is ChatEventProgress -> segments.removeAll { it.first != null && it.first == event.step }
            is ChatEventError -> {
                if (error == null) error = event
                if (errorEvent == null) errorEvent = event
            }
            is ChatEvent.Unknown -> if (event.type == "error" && errorEvent == null) errorEvent = event
            is ChatEventDone -> {
                isComplete = true
                answer = event.answer
                stopReason = event.stopReason
                costTiyn = event.costTiyn
                usage = event.usage
                choiceId = event.choiceId
            }
            else -> Unit
        }
    }

    /**
     * The outcome of the finished turn as an immutable value.
     *
     * @throws TungoDecodeException when no done event was added: the stream ended before
     *   completion and its partial [text] is not an answer
     */
    public fun toTurn(dialogId: String?, turnNo: Int?): ChatTurn {
        if (!isComplete) throw incompleteTurn(eventCount, 0, ResponseHeaders.Empty)
        return ChatTurn(dialogId, turnNo, text, stopReason, costTiyn, usage, choiceId, eventCount)
    }
}

private fun incompleteTurn(eventCount: Int, status: Int, headers: ResponseHeaders): TungoDecodeException = TungoDecodeException(
    "The chat stream ended before completion: no \"done\" event arrived after $eventCount event(s)",
    null, status, headers, "",
)

/**
 * Collects the stream to its end and returns the outcome of the turn.
 *
 * @param onEvent called for every event as it arrives
 * @throws TungoApiException when the turn reported an error event; the subclass follows its error.type
 * @throws TungoDecodeException when the stream ended before completion (no done event, for example
 *   because the connection was cut): a partial answer is never returned as a finished turn
 */
public suspend fun StreamResponse<ChatEvent>.accumulate(onEvent: suspend (ChatEvent) -> Unit = {}): ChatTurn {
    val accumulator = ChatStreamAccumulator()
    events.collect { event ->
        accumulator.add(event)
        onEvent(event)
    }
    accumulator.errorEvent?.let { throw chatTurnException(it, this) }
    if (!accumulator.isComplete) throw incompleteTurn(accumulator.eventCount, status, headers)
    return accumulator.toTurn(dialogId, turnNo)
}

/**
 * Asks the assistant a question and waits for the complete answer (no streaming). The default
 * timeout of this call is 600 s.
 *
 * @param message the question
 * @param dialogId continue this dialog; a new dialog is started when null
 */
public suspend fun ChatResource.ask(
    message: String,
    dialogId: String? = null,
    companyId: String? = null,
    requestOptions: RequestOptions = RequestOptions.Default,
): ChatTurnResult {
    val dialog: NullableField<String> = if (dialogId == null) NullableField.Absent else NullableField.Value(dialogId)
    return create(ChatMessageRequest(message = message, dialogId = dialog), companyId, requestOptions)
}

/**
 * Starts a chat turn as an event stream, reports every event to [onEvent] and returns the
 * outcome of the turn when the stream ends.
 *
 * @throws TungoApiException when the turn reported an error event
 * @throws TungoDecodeException when the stream ended before its done event
 */
public suspend fun ChatResource.streamTurn(
    body: ChatMessageRequest,
    companyId: String? = null,
    requestOptions: RequestOptions = RequestOptions.Default,
    onEvent: suspend (ChatEvent) -> Unit = {},
): ChatTurn = withRawResponse.createStream(body, companyId, requestOptions) { it.accumulate(onEvent) }

private val errorKinds: Set<String> = setOf("invalid_request_error", "authentication_error", "payment_required_error", "permission_error", "not_found_error", "conflict_error", "gone_error", "limit_error", "api_error", "unavailable_error")

internal fun chatTurnException(event: ChatEvent, response: StreamResponse<ChatEvent>): TungoApiException {
    if (event !is ChatEventError) {
        // An error the server sent in a shape this version does not know: keep what can be read.
        val json = (event as? ChatEvent.Unknown)?.json
        return Errors.fromStreamObject(response.status, response.headers, json, json?.toString().orEmpty())
    }
    val detail: ChatTurnError = event.error
    val encoded = TungoJson.encoder.encodeToJsonElement(ChatTurnError.serializer(), detail) as JsonObject
    val extra = LinkedHashMap<String, JsonElement>()
    for ((key, value) in encoded) {
        if (key != "type" && key != "code" && key != "message") extra[key] = value
    }
    return ApiErrorData(
        status = response.status,
        type = detail.type,
        kind = if (detail.type in errorKinds) detail.type else "api_error",
        code = detail.code.value,
        apiMessage = detail.message,
        requestId = event.requestId ?: response.requestId,
        fields = emptyList(),
        retryAfterSeconds = null,
        extra = extra,
        envelope = true,
        headers = response.headers,
        body = "",
    ).toException()
}
