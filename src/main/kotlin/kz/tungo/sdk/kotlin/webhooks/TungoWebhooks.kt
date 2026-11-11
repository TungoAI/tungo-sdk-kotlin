package kz.tungo.sdk.kotlin.webhooks

import java.security.MessageDigest
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.SerializationException
import kz.tungo.sdk.kotlin.errors.TungoValidationException
import kz.tungo.sdk.kotlin.errors.WebhookVerificationException
import kz.tungo.sdk.kotlin.errors.WebhookVerificationException.Reason
import kz.tungo.sdk.kotlin.internal.TungoJson
import kz.tungo.sdk.kotlin.models.WebhookEvent
import okio.ByteString
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString

/**
 * Verification of outgoing webhooks (Standard Webhooks / Svix) and signatures of incoming hooks.
 *
 * All comparisons of signatures run in constant time.
 */
public object TungoWebhooks {
    /** The default distance allowed between the webhook timestamp and the clock: 300 seconds. */
    public val DEFAULT_TOLERANCE: Duration = 300.seconds

    /** The system clock in seconds since the epoch. */
    @JvmField
    public val SYSTEM_CLOCK: () -> Long = { System.currentTimeMillis() / 1000 }

    private const val SECRET_PREFIX = "whsec_"
    private const val HOOK_PREFIX = "sha256="

    /**
     * Verifies a webhook and returns the typed event.
     *
     * @param body the raw request body, exactly as received
     * @param headers the request headers (webhook-id, webhook-timestamp, webhook-signature or the
     *   svix-* equivalents); names are matched case-insensitively
     * @param secret the signing secret of the endpoint (whsec_...)
     * @param tolerance the largest accepted distance between the timestamp and the clock, in both
     *   directions; it must be positive
     * @param clock the current time in seconds since the epoch; the system clock by default
     * @throws WebhookVerificationException when the webhook cannot be trusted
     */
    public fun verify(
        body: String,
        headers: Map<String, String>,
        secret: String,
        tolerance: Duration = DEFAULT_TOLERANCE,
        clock: () -> Long = SYSTEM_CLOCK,
    ): WebhookEvent = verify(body.toByteArray(Charsets.UTF_8), secret, tolerance, clock, lookup(headers))

    /** Variant of [verify] for a body kept as bytes. */
    public fun verify(
        body: ByteArray,
        headers: Map<String, String>,
        secret: String,
        tolerance: Duration = DEFAULT_TOLERANCE,
        clock: () -> Long = SYSTEM_CLOCK,
    ): WebhookEvent = verify(body, secret, tolerance, clock, lookup(headers))

    /**
     * Variant of [verify] that reads the headers through a lookup function, for example
     * request::getHeader of a servlet or call.request.headers::get of Ktor.
     */
    public fun verify(
        body: ByteArray,
        secret: String,
        tolerance: Duration = DEFAULT_TOLERANCE,
        clock: () -> Long = SYSTEM_CLOCK,
        header: (name: String) -> String?,
    ): WebhookEvent {
        checkSignature(body, secret, tolerance, clock, header)
        return try {
            TungoJson.decoder.decodeFromString(WebhookEvent.serializer(), String(body, Charsets.UTF_8))
        } catch (e: SerializationException) {
            throw WebhookVerificationException(Reason.INVALID_PAYLOAD, "The signature is valid but the payload is not a webhook event", e)
        } catch (e: IllegalArgumentException) {
            throw WebhookVerificationException(Reason.INVALID_PAYLOAD, "The signature is valid but the payload is not a webhook event", e)
        }
    }

    /**
     * Computes the signature of an incoming-hook payload: HMAC-SHA256 of the exact bytes with the
     * hook secret, as lower-case hex.
     *
     * @throws TungoValidationException when the secret is empty
     */
    public fun hookSignature(body: ByteArray, secret: String): String {
        if (secret.isEmpty()) throw TungoValidationException("The hook secret must not be empty")
        return body.toByteString().hmacSha256(secret.encodeUtf8()).hex()
    }

    /** The value of the X-Signature header for a payload: "sha256=" followed by [hookSignature]. */
    public fun hookSignatureHeader(body: ByteArray, secret: String): String = HOOK_PREFIX + hookSignature(body, secret)

    /**
     * Checks the signature of an incoming-hook payload. The signature may be upper- or lower-case
     * hex, with or without the "sha256=" prefix. An empty secret never verifies.
     */
    public fun verifyHookSignature(body: ByteArray, signature: String, secret: String): Boolean {
        if (secret.isEmpty()) return false
        val hex = if (signature.regionMatches(0, HOOK_PREFIX, 0, HOOK_PREFIX.length, ignoreCase = true)) {
            signature.substring(HOOK_PREFIX.length)
        } else {
            signature
        }
        val given = try {
            hex.decodeHex()
        } catch (ignored: IllegalArgumentException) {
            return false
        }
        val expected = body.toByteString().hmacSha256(secret.encodeUtf8())
        return MessageDigest.isEqual(expected.toByteArray(), given.toByteArray())
    }

    /** Variant of [verifyHookSignature] for a text payload, taken as UTF-8. */
    public fun verifyHookSignature(body: String, signature: String, secret: String): Boolean =
        verifyHookSignature(body.toByteArray(Charsets.UTF_8), signature, secret)

    private fun lookup(headers: Map<String, String>): (String) -> String? = { name ->
        headers[name] ?: headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    }

    private fun checkSignature(body: ByteArray, secret: String, tolerance: Duration, clock: () -> Long, header: (String) -> String?) {
        if (!tolerance.isPositive()) {
            throw WebhookVerificationException(Reason.INVALID_TOLERANCE, "The timestamp tolerance must be positive")
        }
        val encoded = secret.removePrefix(SECRET_PREFIX)
        val key: ByteString? = if (encoded.isEmpty()) null else encoded.decodeBase64()
        if (key == null || key.size == 0) {
            throw WebhookVerificationException(Reason.INVALID_SECRET, "The webhook secret is empty or not valid base64")
        }
        val id = header("svix-id") ?: header("webhook-id")
        val timestamp = header("svix-timestamp") ?: header("webhook-timestamp")
        val signatures = header("svix-signature") ?: header("webhook-signature")
        if (id == null || timestamp == null || signatures == null) {
            throw WebhookVerificationException(Reason.MISSING_HEADER, "A webhook id, timestamp or signature header is missing")
        }
        val sent = timestamp.takeIf { text -> text.isNotEmpty() && text.all { it in '0'..'9' } }?.toLongOrNull()
            ?: throw WebhookVerificationException(Reason.MALFORMED_TIMESTAMP, "The webhook timestamp is not a number of seconds")
        val now = clock()
        val allowed = tolerance.inWholeSeconds
        if (now - sent > allowed) {
            throw WebhookVerificationException(Reason.TIMESTAMP_TOO_OLD, "The webhook timestamp is older than the tolerance allows")
        }
        if (sent - now > allowed) {
            throw WebhookVerificationException(Reason.TIMESTAMP_TOO_NEW, "The webhook timestamp is further in the future than the tolerance allows")
        }
        val prefix = "$id.$timestamp.".toByteArray(Charsets.UTF_8)
        val expected = (prefix + body).toByteString().hmacSha256(key).toByteArray()
        var matched = false
        for (part in signatures.split(' ', '\t').filter { it.isNotEmpty() }) {
            val comma = part.indexOf(',')
            if (comma < 0 || part.substring(0, comma) != "v1") continue
            val candidate = part.substring(comma + 1).decodeBase64() ?: continue
            // Every candidate is compared, so the time does not reveal which one matched.
            if (MessageDigest.isEqual(expected, candidate.toByteArray())) matched = true
        }
        if (!matched) {
            throw WebhookVerificationException(Reason.SIGNATURE_MISMATCH, "No signature of the webhook matches its payload")
        }
    }
}
