package kz.tungo.sdk.kotlin.streaming

/** One event of a server-sent event stream, before its data is decoded. */
public data class ServerSentEvent(
    /** Event name; "message" when the stream did not name it. */
    public val event: String,
    /** The data lines joined with a line feed. */
    public val data: String,
    /** The last event id seen so far on the stream; null when none was sent. */
    public val id: String?,
    /** Reconnection delay in milliseconds, when this event set one. */
    public val retry: Long?,
)
