package com.github.djbigmuh.minutewatcher.core.common.annotation

/**
 * Marks explicit synchronous-to-asynchronous coroutine bridges where runBlocking
 * is intentionally allowed by architecture policy.
 */
@Target(
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.EXPRESSION,
    AnnotationTarget.CLASS,
    AnnotationTarget.FILE,
)
@Retention(AnnotationRetention.SOURCE)
annotation class RunBlockingBridge(
    val reason: String = "",
)
