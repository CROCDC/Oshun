package com.oshun.gpsbridge.core

/**
 * Builds the human-readable crash report shown on the crash screen and copied to
 * the clipboard. Pure Kotlin (no Android), so it is unit-tested on the JVM.
 */
object CrashFormatter {

    fun format(metadata: List<Pair<String, String>>, stackTrace: String): String {
        val header = metadata.joinToString("\n") { (key, value) -> "$key: $value" }
        return buildString {
            if (header.isNotEmpty()) {
                append(header)
                append("\n\n")
            }
            append(stackTrace.trim())
            append("\n")
        }
    }
}
