package com.oshun.gpsbridge

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.oshun.gpsbridge.core.DeliveryOutcome
import com.oshun.gpsbridge.core.EventKind
import com.oshun.gpsbridge.core.LogEvent
import com.oshun.gpsbridge.core.StopReason
import com.oshun.gpsbridge.service.GpsBridgeService

/**
 * Turns the neutral tokens the core produces (outcomes, event kinds, stop reasons) into
 * the translated text both screens show. Keeps `core/` free of user-facing strings.
 */
@Composable
internal fun outcomeLabel(outcome: DeliveryOutcome?): String = when (outcome) {
    DeliveryOutcome.OK -> stringResource(R.string.outcome_ok)
    DeliveryOutcome.NO_CLIENT -> stringResource(R.string.outcome_no_client)
    DeliveryOutcome.STALLED -> stringResource(R.string.outcome_stalled)
    DeliveryOutcome.DROPPED -> stringResource(R.string.outcome_dropped)
    DeliveryOutcome.BLIND -> stringResource(R.string.outcome_blind)
    DeliveryOutcome.NOT_SENT -> stringResource(R.string.outcome_not_sent)
    null -> stringResource(R.string.outcome_unknown)
}

@Composable
internal fun eventLabel(event: LogEvent): String = when (event.kind) {
    EventKind.SESSION_START -> stringResource(R.string.log_session_start, event.detail)
    EventKind.SESSION_STOP -> stringResource(R.string.log_session_stop, stopReasonLabel(event.detail))
    EventKind.CLIENT_CONNECTED -> stringResource(R.string.log_client_connected, event.detail)
    EventKind.CLIENT_DISCONNECTED -> stringResource(R.string.log_client_disconnected, event.detail)
    EventKind.DELIVERY -> outcomeLabel(event.outcome)
    EventKind.SIMULATION -> stringResource(R.string.log_simulation)
    EventKind.AIS_FEED -> when {
        event.detail == "up" -> stringResource(R.string.log_ais_up)
        // The first message, kept verbatim: what the feed actually sends is the one thing that
        // cannot be worked out from here when nothing appears on the chart.
        event.detail.startsWith(GpsBridgeService.RAW_PREFIX) ->
            stringResource(R.string.log_ais_sample, event.detail.removePrefix(GpsBridgeService.RAW_PREFIX))
        else -> stringResource(R.string.log_ais_down)
    }
    EventKind.FIX -> if (event.fixValid == true) {
        stringResource(R.string.log_fix_ok)
    } else {
        stringResource(R.string.log_fix_stale)
    }
}

@Composable
private fun stopReasonLabel(token: String): String = when (StopReason.fromToken(token)) {
    StopReason.IDLE_TIMEOUT -> stringResource(R.string.log_stop_idle)
    else -> stringResource(R.string.log_stop_user)
}

/** Bytes as a short human size: the log screen only needs the order of magnitude. */
internal fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f kB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}
