package com.oshun.gpsbridge

import android.app.Application
import com.oshun.gpsbridge.crash.CrashHandler

class OshunApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashHandler(this).install()
    }
}
