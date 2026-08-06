package com.oshun.gpsbridge.crash

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import com.oshun.gpsbridge.core.CrashFormatter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

/**
 * Global uncaught-exception handler: saves a crash report, shows [CrashActivity]
 * with the stack trace, then terminates the process. Installed from [com.oshun.gpsbridge.OshunApp].
 */
class CrashHandler(private val context: Context) : Thread.UncaughtExceptionHandler {

    private val default = Thread.getDefaultUncaughtExceptionHandler()

    fun install() {
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        val report = try {
            CrashFormatter.format(metadata(), throwable.stackTraceToString())
        } catch (_: Throwable) {
            throwable.stackTraceToString()
        }

        CrashStore.save(context, report)

        try {
            context.startActivity(
                Intent(context, CrashActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    .putExtra(CrashActivity.EXTRA_REPORT, report),
            )
        } catch (_: Throwable) {
            // If we cannot show the screen, the report is still saved for next launch.
            default?.uncaughtException(thread, throwable)
        }

        Process.killProcess(Process.myPid())
        exitProcess(10)
    }

    private fun metadata(): List<Pair<String, String>> {
        val version = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        } catch (_: Throwable) {
            "?"
        }
        val time = try {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        } catch (_: Throwable) {
            "?"
        }
        return listOf(
            "Time" to time,
            "App version" to version,
            "Android" to "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
            "Device" to "${Build.MANUFACTURER} ${Build.MODEL}",
        )
    }
}
