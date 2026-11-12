package kz.tungo.sdk.kotlin.internal

/** Who may see the API key. */
internal enum class AuthMode {
    /** The operation requires the API key. */
    KEY,

    /** A public operation: no credentials are sent. */
    PUBLIC,

    /** An incoming hook: the token in the path authenticates, the API key is never sent. */
    HOOK,
}

/** A request body: the exact bytes and their media type. */
internal class RequestPayload(val bytes: ByteArray, val contentType: String)

/** Everything the engine needs to know about one operation call. */
internal class RequestSpec(
    val method: String,
    /** Path with already encoded segments, starting with a slash. */
    val path: String,
    val auth: AuthMode,
    val query: QueryBuilder? = null,
    val headers: List<Pair<String, String>> = emptyList(),
    val payload: RequestPayload? = null,
    /** The contract declares the Idempotency-Key header: the SDK generates a key. */
    val idempotent: Boolean = false,
    /**
     * The key used instead of a random one when the caller gives none: the event id of a hook
     * event, because the API reads Idempotency-Key before X-Event-Id.
     */
    val defaultIdempotencyKey: String? = null,
    /** A non-streaming chat turn: the default timeout is 600 s. */
    val longRunning: Boolean = false,
)
