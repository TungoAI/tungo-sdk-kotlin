package kz.tungo.sdk.kotlin

/** Marks the builders of the SDK so that their scopes do not leak into each other. */
@DslMarker
@Target(AnnotationTarget.CLASS)
public annotation class TungoDsl
