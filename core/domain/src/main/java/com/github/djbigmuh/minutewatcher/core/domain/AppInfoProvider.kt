package com.github.djbigmuh.minutewatcher.core.domain

/**
 * Interface providing application version and build metadata (such as version name and version code).
 *
 * This abstraction abstracts away direct dependencies on Android `BuildConfig`, allowing domain
 * and feature layers to remain decoupled and testable through mock implementations.
 */
interface AppInfoProvider {
    /** Application version name string. */
    val versionName: String

    /** Application version code integer. */
    val versionCode: Int
}
