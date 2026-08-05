package com.oshun.gpsbridge.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.oshun.gpsbridge.BridgeConfig
import com.oshun.gpsbridge.BridgeState
import com.oshun.gpsbridge.MainActivity
import com.oshun.gpsbridge.R
import com.oshun.gpsbridge.location.LocationSource
import com.oshun.gpsbridge.net.NetworkUtils
import com.oshun.gpsbridge.net.NmeaTcpServer
import com.oshun.gpsbridge.net.NmeaTransport
import com.oshun.gpsbridge.net.NmeaUdpBroadcaster
import com.oshun.gpsbridge.nmea.NmeaFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps reading GPS and broadcasting NMEA even with the
 * screen off. Started from the UI with a [BridgeConfig] in the intent extras.
 */
class GpsBridgeService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var collectJob: Job? = null
    private val transports = mutableListOf<NmeaTransport>()
    private var sent = 0L

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
        if (config.tcpEnabled) {
            transports += NmeaTcpServer(config.port).also { tcp ->
                tcp.onClientsChanged = { count ->
                    BridgeState.update { it.copy(tcpClients = count) }
                    updateNotification()
                }
            }
        }
        if (config.udpEnabled) {
            transports += NmeaUdpBroadcaster(config.port)
        }
        transports.forEach {
            try {
                it.start()
            } catch (e: Exception) {
                // e.g. port already in use — surface as not running for that transport
            }
        }

        BridgeState.update {
            it.copy(
                running = true,
                ipAddress = NetworkUtils.localIpAddress(),
                port = config.port,
                tcpEnabled = config.tcpEnabled && transports.any { t -> t.label == "TCP" && t.isRunning },
                udpEnabled = config.udpEnabled && transports.any { t -> t.label == "UDP" && t.isRunning },
                tcpClients = 0,
                sentencesSent = 0,
            )
        }
        updateNotification()

        val source = LocationSource(this)
        collectJob = scope.launch {
            source.fixes(config.intervalMillis)
                .onEach { fix ->
                    val lines = NmeaFormatter.sentences(fix)
                    transports.forEach { it.broadcast(lines) }
                    sent += lines.size
                    BridgeState.update { it.copy(lastFix = fix, sentencesSent = sent) }
                }
                .collect { }
        }
    }

    private fun stopSelfAndCleanup() {
        collectJob?.cancel()
        collectJob = null
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
        val protocols = buildList {
            if (status.tcpEnabled) add("TCP")
            if (status.udpEnabled) add("UDP")
        }.joinToString("/")
        val text = "${status.ipAddress ?: "?"}:${status.port} · $protocols" +
            if (status.tcpEnabled) " · ${status.tcpClients} cliente(s)" else ""

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Oshun GPS Bridge activo")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_bridge)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Detener", stopIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Oshun GPS Bridge",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Transmisión de GPS por NMEA a Navionics" }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "gps_bridge"
        private const val NOTIF_ID = 1

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
