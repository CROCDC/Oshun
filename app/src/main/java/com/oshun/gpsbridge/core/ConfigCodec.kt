package com.oshun.gpsbridge.core

/**
 * Flattens a [BridgeConfig] into a single string so the Android layer can keep it in
 * SharedPreferences and hand it back when the system restarts the service with a null
 * intent (START_STICKY). Without that, a restarted service silently fell back to the
 * hardcoded defaults instead of the settings the user actually chose.
 *
 * [decode] is deliberately tolerant: any missing, malformed or unknown field falls back
 * to the default, so a value written by an older version never blocks a start.
 */
object ConfigCodec {

    private const val VERSION = "v1"
    const val MIN_PORT = 1
    const val MAX_PORT = 65535

    fun encode(config: BridgeConfig): String = listOf(
        VERSION,
        "port=${config.port}",
        "tcp=${config.tcpEnabled.token()}",
        "udp=${config.udpEnabled.token()}",
        "interval=${config.intervalMillis}",
        "autooff=${config.autoOffEnabled.token()}",
        "rawlog=${config.rawLogEnabled.token()}",
        "sim=${config.simulated.token()}",
    ).joinToString(";")

    fun decode(encoded: String?): BridgeConfig {
        val defaults = BridgeConfig()
        if (encoded.isNullOrBlank()) return defaults
        val fields = encoded.split(';')
            .mapNotNull { part ->
                val sep = part.indexOf('=')
                if (sep <= 0) null else part.substring(0, sep).trim() to part.substring(sep + 1).trim()
            }
            .toMap()

        val decoded = BridgeConfig(
            port = fields["port"]?.toIntOrNull()?.takeIf { it in MIN_PORT..MAX_PORT } ?: defaults.port,
            tcpEnabled = fields["tcp"].toBooleanOr(defaults.tcpEnabled),
            udpEnabled = fields["udp"].toBooleanOr(defaults.udpEnabled),
            intervalMillis = fields["interval"]?.toLongOrNull()?.takeIf { it > 0 } ?: defaults.intervalMillis,
            autoOffEnabled = fields["autooff"].toBooleanOr(defaults.autoOffEnabled),
            rawLogEnabled = fields["rawlog"].toBooleanOr(defaults.rawLogEnabled),
            simulated = fields["sim"].toBooleanOr(defaults.simulated),
        )
        // A config with no transport can't transmit anything; fall back to TCP rather
        // than restarting into a bridge that is "running" but mute.
        return if (!decoded.tcpEnabled && !decoded.udpEnabled) decoded.copy(tcpEnabled = true) else decoded
    }

    private fun Boolean.token(): String = if (this) "1" else "0"

    private fun String?.toBooleanOr(fallback: Boolean): Boolean = when (this) {
        "1" -> true
        "0" -> false
        else -> fallback
    }
}
