package com.oshun.gpsbridge.store

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.oshun.gpsbridge.core.TrackLogFormatter
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter

/**
 * Appends the per-fix track log to a rotating CSV in the app's private storage, so a
 * trip that went wrong can be read afterwards instead of reconstructed from memory.
 *
 * Every line is flushed: at one line per second the cost is nothing, and the whole point
 * is that the record survives the process being killed mid-trip.
 */
object TrackLogWriter {

    private const val DIR = "logs"
    private const val CURRENT = "track.csv"
    private const val PREVIOUS = "track.1.csv"
    private const val SHARE_NAME = "oshun-track.csv"

    /** Rotate at 5 MB — roughly 20 hours of 1 Hz logging per file. Overridable so tests
     * can exercise rotation without writing megabytes. */
    var maxBytes = 5L * 1024 * 1024

    private var writer: BufferedWriter? = null

    private fun dir(context: Context): File =
        File(context.applicationContext.filesDir, DIR).apply { mkdirs() }

    private fun current(context: Context): File = File(dir(context), CURRENT)

    private fun previous(context: Context): File = File(dir(context), PREVIOUS)

    /** Opens the file for a new session, writing the CSV header only on a fresh file. */
    @Synchronized
    fun open(context: Context, sessionHeader: String) {
        closeQuietly()
        try {
            val file = current(context)
            val fresh = !file.exists() || file.length() == 0L
            writer = BufferedWriter(FileWriter(file, true)).also {
                if (fresh) {
                    it.write(TrackLogFormatter.CSV_HEADER)
                    it.newLine()
                }
                it.write(sessionHeader)
                it.newLine()
                it.flush()
            }
        } catch (e: Exception) {
            writer = null // logging must never take the bridge down
        }
    }

    @Synchronized
    fun append(context: Context, line: String) {
        val out = writer ?: return
        try {
            out.write(line)
            out.newLine()
            out.flush()
            if (current(context).length() > maxBytes) rotate(context)
        } catch (e: Exception) {
            closeQuietly()
        }
    }

    /** Writes the closing line and releases the file. */
    @Synchronized
    fun close(footer: String?) {
        val out = writer
        if (out != null && footer != null) {
            try {
                out.write(footer)
                out.newLine()
                out.flush()
            } catch (_: Exception) {
            }
        }
        closeQuietly()
    }

    /** Keeps one previous file, so a rotation never costs you the whole trip. */
    private fun rotate(context: Context) {
        closeQuietly()
        try {
            val file = current(context)
            previous(context).delete()
            file.renameTo(previous(context))
            writer = BufferedWriter(FileWriter(file, true)).also {
                it.write(TrackLogFormatter.CSV_HEADER)
                it.newLine()
                it.flush()
            }
        } catch (e: Exception) {
            writer = null
        }
    }

    @Synchronized
    fun clear(context: Context) {
        closeQuietly()
        current(context).delete()
        previous(context).delete()
    }

    /** Bytes on disk across both files, for the log screen. */
    fun sizeBytes(context: Context): Long =
        (if (current(context).exists()) current(context).length() else 0L) +
            (if (previous(context).exists()) previous(context).length() else 0L)

    /**
     * Builds one shareable file with the whole history (previous file first) in the cache
     * directory and returns a content:// URI for it. Null when there is nothing to share.
     */
    @Synchronized
    fun shareUri(context: Context): Uri? {
        try {
            writer?.flush()
            val parts = listOf(previous(context), current(context)).filter { it.exists() && it.length() > 0 }
            if (parts.isEmpty()) return null
            val shareDir = File(context.applicationContext.cacheDir, DIR).apply { mkdirs() }
            val target = File(shareDir, SHARE_NAME)
            target.outputStream().use { out ->
                parts.forEach { part -> part.inputStream().use { it.copyTo(out) } }
            }
            return FileProvider.getUriForFile(
                context.applicationContext,
                "${context.applicationContext.packageName}.logs",
                target,
            )
        } catch (e: Exception) {
            return null
        }
    }

    private fun closeQuietly() {
        try {
            writer?.close()
        } catch (_: Exception) {
        }
        writer = null
    }
}
