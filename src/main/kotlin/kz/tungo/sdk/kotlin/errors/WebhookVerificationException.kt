package kz.tungo.sdk.kotlin.errors

/** A webhook could not be verified. The message never contains the secret or the signature. */
public class WebhookVerificationException(
    /** Why the verification failed. */
    public val reason: Reason,
    message: String,
    cause: Throwable? = null,
) : TungoException(message, cause) {
    /** Reasons of a failed webhook verification. */
    public enum class Reason {
        /** The tolerance is zero or negative. */
        INVALID_TOLERANCE,

        /** The secret is missing, empty or not base64. */
        INVALID_SECRET,

        /** The id, timestamp or signature header is missing. */
        MISSING_HEADER,

        /** The timestamp header is not an integer number of seconds. */
        MALFORMED_TIMESTAMP,

        /** The message is older than the tolerance allows. */
        TIMESTAMP_TOO_OLD,

        /** The message is dated further in the future than the tolerance allows. */
        TIMESTAMP_TOO_NEW,

        /** No signature in the header matches the payload. */
        SIGNATURE_MISMATCH,

        /** The signature is valid but the payload is not a webhook event. */
        INVALID_PAYLOAD,
    }
}
