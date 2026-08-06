package com.oshun.gpsbridge

import android.app.Application
import android.os.Build
import com.oshun.gpsbridge.crash.CrashHandler

class OshunApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // The crash handler kills the process on an uncaught exception, which would
        // tear down the JVM test worker; skip it under Robolectric unit tests.
        if (Build.FINGERPRINT != "robolectric") {
            CrashHandler(this).install()
        }
    }
}
