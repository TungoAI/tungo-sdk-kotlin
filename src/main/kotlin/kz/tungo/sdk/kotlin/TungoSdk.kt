package kz.tungo.sdk.kotlin

/** Build constants of the Tungo SDK for Kotlin. */
public object TungoSdk {
    /** Version of this library. It is sent in the User-Agent header of every request. */
    public const val VERSION: String = "0.3.0" // x-release-version

    /** Production host of the Tungo Platform API. */
    public const val DEFAULT_BASE_URL: String = "https://api.tungo.kz"

    /** Version of the API contract this library was generated from. */
    public const val CONTRACT_VERSION: String = "0.1.0"

    internal const val USER_AGENT: String = "tungo-sdk-kotlin/$VERSION"
}
