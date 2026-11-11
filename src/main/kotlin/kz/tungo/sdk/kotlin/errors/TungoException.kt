package kz.tungo.sdk.kotlin.errors

/**
 * Base class of every failure reported by the SDK.
 *
 * Coroutine cancellation is not wrapped: a cancelled call throws the usual CancellationException.
 */
public open class TungoException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
