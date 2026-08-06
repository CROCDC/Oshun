package com.oshun.gpsbridge.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.oshun.gpsbridge.MainActivity
import com.oshun.gpsbridge.R
import com.oshun.gpsbridge.core.BatteryMath
import com.oshun.gpsbridge.core.BridgeConfig
import com.oshun.gpsbridge.core.BridgeLogic
import com.oshun.gpsbridge.core.BridgeState
import com.oshun.gpsbridge.location.FixProvider
import com.oshun.gpsbridge.location.LocationSource
import com.oshun.gpsbridge.net.NetworkUtils
import com.oshun.gpsbridge.net.NmeaTcpServer
import com.oshun.gpsbridge.net.NmeaTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Foreground service that keeps reading GPS and broadcasting NMEA even with the
 * screen off. Started from the UI with a [BridgeConfig] in the intent extras.
 */
class GpsBridgeService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var collectJob: Job? = null
    private var batteryJob: Job? = null
    private var autoOffJob: Job? = null
    private val transports = mutableListOf<NmeaTransport>()
    private var sent = 0L

    @Volatile
    private var autoOffEnabled = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelfAndCleanup()
                return START_NOT_STICKY
            }
            else -> start(intent)
        }
        return START_STICKY
    }

    private fun start(intent: Intent?) {
        if (collectJob != null) return // already running

        val config = BridgeConfig(
            port = intent?.getIntExtra(EXTRA_PORT, 2000) ?: 2000,
            tcpEnabled = intent?.getBooleanExtra(EXTRA_TCP, true) ?: true,
            udpEnabled = intent?.getBooleanExtra(EXTRA_UDP, true) ?: true,
            intervalMillis = intent?.getLongExtra(EXTRA_INTERVAL, 1000L) ?: 1000L,
        )

        createNotificationChannel()
        startForegroundCompat()

        transports.clear()
        transports += BridgeLogic.buildTransports(config)
        transports.filterIsInstance<NmeaTcpServer>().forEach { tcp ->
            tcp.onClientsChanged = { count ->
                BridgeState.update { it.copy(tcpClients = count) }
                updateNotification()
                if (autoOffEnabled) {
                    if (count > 0) cancelIdleOff() else armIdleOff()
                }
            }
        }

        // Publish enabled state immediately so the UI flips to "running".
        sent = 0
        autoOffEnabled = config.tcpEnabled && !config.udpEnabled
        BridgeState.update {
            it.copy(
                running = true,
                port = config.port,
                tcpEnabled = config.tcpEnabled,
                udpEnabled = config.udpEnabled,
                tcpClients = 0,
                sentencesSent = 0,
            )
        }
        updateNotification()

        val source = fixProviderFactory(this)
        collectJob = scope.launch {
            // Bind sockets off the main thread — ServerSocket/DatagramSocket setup on
            // the main thread throws NetworkOnMainThreadException.
            transports.forEach {
                try {
                    it.start()
                } catch (e: Exception) {
                    // e.g. port already in use — the transport just stays inactive
                }
            }
            BridgeState.update { it.copy(ipAddress = NetworkUtils.localIpAddress()) }
            updateNotification()

            source.fixes(config.intervalMillis)
                .onEach { fix ->
                    val lines = BridgeLogic.sentencesFor(fix)
                    transports.forEach { it.broadcast(lines) }
                    sent += lines.size
                    BridgeState.update { it.copy(lastFix = fix, sentencesSent = sent) }
                }
                .collect { }
        }

        batteryJob = scope.launch { monitorBattery() }

        // Battery-saver watchdog: reception is only observable over TCP (UDP is
        // connectionless), so this is armed only when TCP is the sole transport. It
        // shuts the service down after AUTO_OFF_MILLIS with no connected client —
        // both when nobody ever connects and when the last client disconnects.
        if (autoOffEnabled) armIdleOff()
    }

    @Synchronized
    private fun armIdleOff() {
        autoOffJob?.cancel()
        autoOffJob = scope.launch {
            delay(AUTO_OFF_MILLIS)
            withContext(Dispatchers.Main) { stopSelfAndCleanup() }
        }
    }

    @Synchronized
    private fun cancelIdleOff() {
        autoOffJob?.cancel()
        autoOffJob = null
    }

    /** Samples battery level and instantaneous draw, publishing an estimated drain rate. */
    private suspend fun monitorBattery() {
        val startElapsed = SystemClock.elapsedRealtime()
        val startPercent = readBatteryPercent()
        while (currentCoroutineContext().isActive) {
            val percent = readBatteryPercent()
            val drainRate = if (startPercent != null && percent != null) {
                BatteryMath.drainPercentPerHour(startPercent, percent, SystemClock.elapsedRealtime() - startElapsed)
            } else {
                null
            }
            BridgeState.update {
                it.copy(
                    batteryPercent = percent,
                    currentDrawMilliAmp = readCurrentDrawMilliAmp(),
                    batteryDrainPerHour = drainRate,
                )
            }
            delay(BATTERY_SAMPLE_MILLIS)
        }
    }

    private fun readBatteryPercent(): Int? = try {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 }
    } catch (e: Exception) {
        null
    }

    /** Instantaneous draw in mA (magnitude), or null when the device doesn't report it. */
    private fun readCurrentDrawMilliAmp(): Int? = try {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val microAmps = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        if (microAmps == Int.MIN_VALUE || microAmps == 0) null else abs(microAmps) / 1000
    } catch (e: Exception) {
        null
    }

    private fun stopSelfAndCleanup() {
        autoOffEnabled = false
        collectJob?.cancel()
        collectJob = null
        batteryJob?.cancel()
        batteryJob = null
        autoOffJob?.cancel()
        autoOffJob = null
        transports.forEach {
            try {
                it.stop()
            } catch (_: Exception) {
            }
        }
        transports.clear()
        sent = 0
        BridgeState.reset()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun updateNotification() {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.notify(NOTIF_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val status = BridgeState.status.value
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, GpsBridgeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val protocols = BridgeLogic.enabledProtocols(status)
            .joinToString("/")
            .ifEmpty { getString(R.string.notif_protocols_none) }
        val ip = status.ipAddress ?: "?"
        val text = if (status.tcpEnabled) {
            getString(R.string.notif_content_clients, ip, status.port, protocols, status.tcpClients)
        } else {
            getString(R.string.notif_content, ip, status.port, protocols)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_bridge)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.action_stop), stopIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = getString(R.string.notif_channel_desc) }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "gps_bridge"
        private const val NOTIF_ID = 1
        private const val BATTERY_SAMPLE_MILLIS = 10_000L
        private const val AUTO_OFF_MILLIS = 15 * 60 * 1000L

        /** Overridable so tests can supply a fake GPS source instead of Play Services. */
        var fixProviderFactory: (Context) -> FixProvider = { LocationSource(it) }

        const val ACTION_STOP = "com.oshun.gpsbridge.STOP"
        const val EXTRA_PORT = "port"
        const val EXTRA_TCP = "tcp"
        const val EXTRA_UDP = "udp"
        const val EXTRA_INTERVAL = "interval"

        fun start(context: Context, config: BridgeConfig) {
            val intent = Intent(context, GpsBridgeService::class.java).apply {
                putExtra(EXTRA_PORT, config.port)
                putExtra(EXTRA_TCP, config.tcpEnabled)
                putExtra(EXTRA_UDP, config.udpEnabled)
                putExtra(EXTRA_INTERVAL, config.intervalMillis)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, GpsBridgeService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
