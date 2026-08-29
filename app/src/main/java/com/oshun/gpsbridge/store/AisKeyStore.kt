package com.oshun.gpsbridge.store

import android.content.Context

/**
 * Where the AIS feed's API key lives: on this phone, and nowhere else.
 *
 * Kept apart from [ConfigStore] on purpose. The bridge config travels in intents, is encoded
 * into a single string, names itself in the CSV header and is quoted in the log — every one
 * of those is a place a credential must never turn up. This store has one value and nothing
 * reads it but the feed.
 */
object AisKeyStore {

    private const val PREFS = "ais"
    private const val KEY = "api_key"

    fun load(context: Context): String =
        prefs(context).getString(KEY, "").orEmpty()

    fun save(context: Context, apiKey: String) {
        prefs(context).edit().putString(KEY, apiKey.trim()).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
