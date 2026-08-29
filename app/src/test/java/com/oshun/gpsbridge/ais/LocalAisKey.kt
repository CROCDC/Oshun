package com.oshun.gpsbridge.ais

import java.io.File
import java.util.Properties

/**
 * The aisstream key, read from the machine that runs the test and from nowhere else.
 *
 * `local.properties` is gitignored, so the key stays on this laptop: it is never committed
 * and never reaches CI, which is why the live test skips there instead of failing.
 */
object LocalAisKey {

    private const val PROPERTY = "aisstream.apiKey"
    private const val ENVIRONMENT = "AISSTREAM_API_KEY"

    val value: String? by lazy {
        (System.getProperty(PROPERTY) ?: System.getenv(ENVIRONMENT) ?: fromLocalProperties())
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    /** Walks up from the test's working directory, which is the module and not the repo root. */
    private fun fromLocalProperties(): String? {
        var directory: File? = File(System.getProperty("user.dir").orEmpty()).absoluteFile
        while (directory != null) {
            val file = File(directory, "local.properties")
            if (file.isFile) {
                val properties = Properties()
                file.inputStream().use(properties::load)
                properties.getProperty(PROPERTY)?.let { return it }
            }
            directory = directory.parentFile
        }
        return null
    }
}
