package com.oshun.gpsbridge.crash

import android.content.Context
import java.io.File

/** Persists the most recent crash report so it can be retrieved on the next launch. */
object CrashStore {
    private const val FILE_NAME = "last_crash.txt"

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    fun save(context: Context, report: String) {
        try {
            file(context).writeText(report)
        } catch (_: Throwable) {
        }
    }

    fun read(context: Context): String? = try {
        val f = file(context)
        if (f.exists()) f.readText() else null
    } catch (_: Throwable) {
        null
    }

    fun clear(context: Context) {
        try {
            file(context).delete()
        } catch (_: Throwable) {
        }
    }
}
