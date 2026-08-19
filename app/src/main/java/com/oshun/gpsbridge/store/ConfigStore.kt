package com.oshun.gpsbridge.store

import android.content.Context
import com.oshun.gpsbridge.core.BridgeConfig
import com.oshun.gpsbridge.core.ConfigCodec
import com.oshun.gpsbridge.core.StopReason

/**
 * Persists the last bridge configuration and the reason the bridge last stopped.
 *
 * The config survives a system-initiated service restart (START_STICKY delivers a null
 * intent, so the extras are gone) and pre-fills the UI. The stop reason outlives the
 * service itself, which is the point: an idle shutdown has to still be explainable when
 * the user finally opens the app and wonders why Navionics froze.
 */
object ConfigStore {

    private const val PREFS = "oshun_bridge"
    private const val KEY_CONFIG = "config"
    private const val KEY_STOP_REASON = "stop_reason"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(context: Context, config: BridgeConfig) {
        prefs(context).edit().putString(KEY_CONFIG, ConfigCodec.encode(config)).apply()
    }

    /** The stored config, or the defaults when nothing (or something unreadable) is stored. */
    fun load(context: Context): BridgeConfig =
        ConfigCodec.decode(prefs(context).getString(KEY_CONFIG, null))

    /** Written on the way down, so it uses commit(): the process may not survive to flush. */
    fun saveStopReason(context: Context, reason: StopReason?) {
        val editor = prefs(context).edit()
        if (reason == null) editor.remove(KEY_STOP_REASON) else editor.putString(KEY_STOP_REASON, reason.name)
        editor.commit()
    }

    fun readStopReason(context: Context): StopReason? =
        StopReason.fromToken(prefs(context).getString(KEY_STOP_REASON, null))
}
