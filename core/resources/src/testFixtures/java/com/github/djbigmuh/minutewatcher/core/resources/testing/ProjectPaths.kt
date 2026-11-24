package com.github.djbigmuh.minutewatcher.core.resources.testing

import java.io.File

object ProjectPaths {
    val root: File
        get() {
            val prop =
                System.getProperty("minutewatcher.projectRoot")
                    ?: throw IllegalStateException(
                        "System property 'minutewatcher.projectRoot' is not set. Expected absolute path to project root.",
                    )
            val file = File(prop)
            if (!file.exists() || !file.isDirectory) {
                throw IllegalStateException(
                    "System property 'minutewatcher.projectRoot' points to a non-existent or invalid directory: $prop",
                )
            }
            return file
        }

    val appManifest: File
        get() {
            val prop =
                System.getProperty("minutewatcher.appManifestPath")
                    ?: throw IllegalStateException(
                        "System property 'minutewatcher.appManifestPath' is not set. Expected absolute path to app AndroidManifest.xml.",
                    )
            val file = File(prop)
            if (!file.exists() || !file.isFile) {
                throw IllegalStateException(
                    "System property 'minutewatcher.appManifestPath' points to a non-existent or invalid file: $prop",
                )
            }
            return file
        }

    val resDir: File
        get() = File(root, "core/resources/src/main/res")

    val excludedDirNames: Set<String> =
        setOf(
            ".git",
            ".gradle",
            ".gradle_home",
            ".robolectric",
            ".kotlin",
            ".artifacts",
            "build",
            "node_modules",
        )
}
