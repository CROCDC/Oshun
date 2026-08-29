package com.oshun.gpsbridge.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.oshun.gpsbridge.MainActivity
import com.oshun.gpsbridge.R
import com.oshun.gpsbridge.ais.AisStreamFeed
import com.oshun.gpsbridge.core.AisReport
import com.oshun.gpsbridge.core.AisSimulator
import com.oshun.gpsbridge.core.AisTraffic
import com.oshun.gpsbridge.core.BatteryMath
import com.oshun.gpsbridge.core.BridgeConfig
import com.oshun.gpsbridge.core.BridgeLogic
import com.oshun.gpsbridge.core.BridgeState
import com.oshun.gpsbridge.core.DeliveryTracker
import com.oshun.gpsbridge.core.EventKind
import com.oshun.gpsbridge.core.EventLog
import com.oshun.gpsbridge.core.LogEvent
import com.oshun.gpsbridge.core.Position
import com.oshun.gpsbridge.core.StopReason
import com.oshun.gpsbridge.core.TrackLogFormatter
import com.oshun.gpsbridge.location.FixProvider
import com.oshun.gpsbridge.location.LocationSource
import com.oshun.gpsbridge.location.SimulatedFixProvider
import com.oshun.gpsbridge.model.AisTarget
import com.oshun.gpsbridge.model.Fix
import com.oshun.gpsbridge.net.AisStreamMessages
import com.oshun.gpsbridge.net.NetworkUtils
import com.oshun.gpsbridge.net.NmeaTcpServer
import com.oshun.gpsbridge.net.NmeaTransport
import com.oshun.gpsbridge.store.AisKeyStore
import com.oshun.gpsbridge.store.ConfigStore
import com.oshun.gpsbridge.store.TrackLogWriter
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
 *
 * Being a foreground service only stops the process from being killed — it does not
 * keep the CPU or the Wi-Fi radio awake, so the service also holds a partial wake lock
 * and a Wi-Fi lock while transmitting, and re-sends the last fix on a heartbeat so the
 * link never goes silent while the bridge is up.
 */
class GpsBridgeService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var collectJob: Job? = null
    private var heartbeatJob: Job? = null
    private var batteryJob: Job? = null
    private var autoOffJob: Job? = null
    private val transports = mutableListOf<NmeaTransport>()
    private var sent = 0L
    private var heartbeats = 0L

    private val tracker = DeliveryTracker()

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    @Volatile
    private var config: BridgeConfig? = null

    @Volatile
    private var lastFix: Fix? = null

    @Volatile
    private var lastFixAtMillis = 0L

    @Volatile
    private var lastSentAtMillis = 0L

    /** When this session started, so the simulated traffic sails the same clock as the boat. */
    private var simulationStartedAtMillis = 0L

    /** When the AIS names last went out; the positions go with every fix, the names do not. */
    private var lastAisNamesAtMillis = 0L

    /** Who made it past the filters in the last batch, so the report can say who did not. */
    private var lastVisibleTargets: List<AisTarget> = emptyList()

    /** The live connection to the internet AIS feed, when the user turned it on. */
    private var aisFeed: AisStreamFeed? = null

    /** What the feed has told us so far, keyed by MMSI. Written from the feed's own thread. */
    @Volatile
    private var aisTargets: Map<Int, AisTarget> = emptyMap()

    /** Messages the feed delivered, readable or not, and when we last published the count. */
    private var aisMessages = 0L
    private var aisCountPublishedAtMillis = 0L

    @Volatile
    private var lastClientCount = 0

    @Volatile
    private var autoOffEnabled = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelfAndCleanup(StopReason.USER)
                return START_NOT_STICKY
            }
            else -> start(configFrom(intent))
        }
        return START_STICKY
    }

    /**
     * The config to run with. A restart by the system (START_STICKY) redelivers a null
     * intent, and an intent without extras carries nothing either — in both cases the
     * stored config is what the user actually chose, so it beats the hardcoded defaults.
     */
    private fun configFrom(intent: Intent?): BridgeConfig {
        if (intent == null || !intent.hasExtra(EXTRA_PORT)) return ConfigStore.load(this)
        return BridgeConfig(
            port = intent.getIntExtra(EXTRA_PORT, 2000),
            tcpEnabled = intent.getBooleanExtra(EXTRA_TCP, true),
            udpEnabled = intent.getBooleanExtra(EXTRA_UDP, false),
            intervalMillis = intent.getLongExtra(EXTRA_INTERVAL, 1000L),
            autoOffEnabled = intent.getBooleanExtra(EXTRA_AUTO_OFF, true),
            rawLogEnabled = intent.getBooleanExtra(EXTRA_RAW_LOG, true),
            simulated = intent.getBooleanExtra(EXTRA_SIMULATED, false),
            aisEnabled = intent.getBooleanExtra(EXTRA_AIS, false),
        )
    }

    private fun start(newConfig: BridgeConfig) {
        if (collectJob != null) {
            // Already running: same config is a no-op, a different one restarts the
            // session. Silently ignoring it used to leave the bridge on the old port /
            // protocol while the UI showed the new settings.
            if (config == newConfig) return
            stopSession()
        }

        config = newConfig
        ConfigStore.save(this, newConfig)
        ConfigStore.saveStopReason(this, null)

        createNotificationChannel()
        startForegroundCompat()
        acquireLocks()

        transports.clear()
        transports += BridgeLogic.buildTransports(newConfig)
        transports.filterIsInstance<NmeaTcpServer>().forEach { tcp ->
            tcp.onClientsChanged = { count -> onClientsChanged(count) }
            tcp.onClientEvent = { connected, remote ->
                EventLog.record(
                    LogEvent(
                        atMillis = System.currentTimeMillis(),
                        kind = if (connected) EventKind.CLIENT_CONNECTED else EventKind.CLIENT_DISCONNECTED,
                        detail = remote,
                    ),
                )
            }
        }

        // Publish enabled state immediately so the UI flips to "running".
        sent = 0
        heartbeats = 0
        lastFix = null
        lastFixAtMillis = 0L
        lastSentAtMillis = 0L
        lastClientCount = 0
        tracker.reset()
        val startedAt = System.currentTimeMillis()
        EventLog.record(
            LogEvent(
                atMillis = startedAt,
                kind = EventKind.SESSION_START,
                detail = "${transports.joinToString("+") { it.label }}:${newConfig.port}",
            ),
        )
        if (newConfig.simulated) {
            EventLog.record(LogEvent(atMillis = startedAt, kind = EventKind.SIMULATION))
        }
        simulationStartedAtMillis = startedAt
        lastAisNamesAtMillis = 0L
        lastVisibleTargets = emptyList()
        if (newConfig.rawLogEnabled) {
            TrackLogWriter.open(this, TrackLogFormatter.sessionHeader(startedAt, newConfig))
        }
        autoOffEnabled = BridgeLogic.shouldArmIdleOff(newConfig)
        BridgeState.update {
            it.copy(
                running = true,
                port = newConfig.port,
                tcpEnabled = newConfig.tcpEnabled,
                udpEnabled = newConfig.udpEnabled,
                autoOffEnabled = autoOffEnabled,
                tcpClients = 0,
                sentencesSent = 0,
                heartbeatsSent = 0,
                simulated = newConfig.simulated,
                aisTargets = 0,
                aisFeedConnected = false,
                aisMessages = 0,
                lastFix = null,
                lastFixAtMillis = null,
                lastSendOkAtMillis = null,
                fixValid = false,
                outcome = null,
            )
        }
        updateNotification()

        startAisFeed(newConfig)

        // Test mode bypasses the injectable factory on purpose: the simulator is the thing
        // under test, so nothing may stand in for it.
        val source = if (newConfig.simulated) SimulatedFixProvider() else fixProviderFactory(this)
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

            source.fixes(newConfig.intervalMillis)
                .onEach { fix ->
                    lastFix = fix
                    lastFixAtMillis = System.currentTimeMillis()
                    aisFeed?.onOwnPosition(Position(fix.latitude, fix.longitude))
                    emitCurrentFix(heartbeat = false)
                }
                .collect { }
        }

        // Heartbeat: a real NMEA source transmits continuously. When the GPS stops
        // delivering (indoors, Doze, throttling) the stream used to just go quiet and
        // Navionics kept drawing the last position forever; now the last fix keeps
        // going out, flagged invalid once it is stale, and a client that reconnects
        // gets a position without waiting for the next fix.
        heartbeatJob = scope.launch {
            val tick = maxOf(MIN_HEARTBEAT_TICK_MILLIS, newConfig.intervalMillis / 2)
            while (currentCoroutineContext().isActive) {
                delay(tick)
                if (BridgeLogic.shouldResend(System.currentTimeMillis(), lastSentAtMillis, newConfig.intervalMillis)) {
                    emitCurrentFix(heartbeat = true)
                }
            }
        }

        batteryJob = scope.launch { monitorBattery() }

        // Battery-saver watchdog: reception is only observable over TCP (UDP is
        // connectionless), so this is armed only when TCP is the sole transport and the
        // user left it enabled. It shuts the service down after AUTO_OFF_MILLIS with no
        // connected client — both when nobody ever connects and when the last one leaves.
        if (autoOffEnabled) armIdleOff()
    }

    /**
     * The traffic to append to this batch: invented in test mode, whatever the feed still
     * stands behind otherwise, and nothing when neither is on.
     *
     * Targets travel on the same stream as our own position — that is how a plotter takes
     * them — and in the same batch: one send carries where we are and what is around us, so
     * the chart never moves the boat between vessels that have not been redrawn yet. Only
     * the names run slower, because a name is not news twice.
     */
    private fun aisSentences(current: BridgeConfig, now: Long): List<String> {
        if (!current.simulated && aisFeed == null) {
            // Say zero rather than saying nothing. Returning early without touching the count
            // left it frozen at whatever the feed managed before it died, so the status card
            // went on claiming twelve vessels while every batch carried none — the one number
            // that could have told us the feed was gone was the one still saying it was fine.
            lastVisibleTargets = emptyList()
            BridgeState.update { it.copy(aisTargets = 0) }
            return emptyList()
        }
        val withNames =
            BridgeLogic.shouldEmitAgain(now, lastAisNamesAtMillis, BridgeLogic.AIS_STATIC_INTERVAL_MILLIS)
        if (withNames) lastAisNamesAtMillis = now

        // Test mode invents its own traffic; otherwise it is whatever the feed still stands
        // behind — near us, and young enough to be worth drawing.
        val targets = if (current.simulated) {
            AisSimulator.targetsAt(now - simulationStartedAtMillis, now)
        } else {
            AisTraffic.visible(aisTargets, lastFix?.let { Position(it.latitude, it.longitude) }, now)
        }
        lastVisibleTargets = targets
        BridgeState.update { it.copy(aisTargets = targets.size) }
        return BridgeLogic.aisSentencesFor(targets, now, withNames)
    }

    /**
     * Folds one feed update into what we know. Synchronized on the same monitor as the
     * emission, so a batch of sentences is built from one consistent picture of the traffic
     * rather than from a table changing under it.
     */
    @Synchronized
    private fun rememberTarget(update: AisTraffic.Update) {
        aisTargets = AisTraffic.merge(aisTargets, update, System.currentTimeMillis())
    }

    /**
     * Counts what arrives, and keeps the first message verbatim.
     *
     * Three numbers tell three different stories apart, and without them a silent chart is
     * undiagnosable: no connection is one problem, a connection with no traffic is another,
     * and traffic we cannot read is a third — and only the last one is ours to fix. So the
     * first message goes into the log as it came, because the shape of somebody else's JSON
     * is not something you can guess at from a distance.
     */
    @Synchronized
    private fun onAisMessage(raw: String) {
        aisMessages += 1
        val now = System.currentTimeMillis()
        // The feed refuses in a message, not only in the close: log that sentence as itself.
        AisStreamMessages.errorOf(raw)?.let { error ->
            EventLog.record(LogEvent(atMillis = now, kind = EventKind.AIS_FEED, detail = ERROR_PREFIX + error))
        }
        if (aisMessages == 1L) {
            EventLog.record(
                LogEvent(atMillis = now, kind = EventKind.AIS_FEED, detail = RAW_PREFIX + AisStreamMessages.sample(raw)),
            )
        }
        // The count is a diagnostic, not a readout: publishing it per message would recompose
        // the screen dozens of times a second in a busy channel.
        if (BridgeLogic.shouldEmitAgain(now, aisCountPublishedAtMillis, AIS_COUNT_INTERVAL_MILLIS)) {
            aisCountPublishedAtMillis = now
            BridgeState.update { it.copy(aisMessages = aisMessages) }
        }
    }

    /**
     * Opens the internet AIS feed, when the user asked for it and gave it a key. Never in
     * test mode: the simulated traffic is the thing under test there, and mixing real
     * vessels into it would make the chart impossible to read.
     */
    private fun startAisFeed(current: BridgeConfig) {
        aisTargets = emptyMap()
        aisMessages = 0L
        aisCountPublishedAtMillis = 0L
        if (!current.aisEnabled || current.simulated) return
        val apiKey = AisKeyStore.load(this)
        if (apiKey.isBlank()) return
        aisFeed = AisStreamFeed(
            apiKey = apiKey,
            scope = scope,
            onUpdate = { update -> rememberTarget(update) },
            onRaw = { raw -> onAisMessage(raw) },
            onConnected = { connected, detail ->
                // Worth a line of its own, and worth the reason: while the feed is down the
                // chart keeps your own position and quietly stops showing anybody else's — and
                // a rejected key looks exactly like empty water unless the server's own words
                // are written down.
                EventLog.record(
                    LogEvent(
                        atMillis = System.currentTimeMillis(),
                        kind = EventKind.AIS_FEED,
                        detail = if (connected) UP else DOWN_PREFIX + detail,
                    ),
                )
                if (!connected) aisTargets = emptyMap()
                BridgeState.update { it.copy(aisFeedConnected = connected) }
            },
        ).also { it.start() }
    }

    /** Sends the current fix (fresh or repeated) to every transport, updating the diagnostics. */
    @Synchronized
    private fun emitCurrentFix(heartbeat: Boolean) {
        val fix = lastFix ?: return
        val current = config ?: return
        val now = System.currentTimeMillis()
        val valid = !BridgeLogic.isStale(now - lastFixAtMillis, current.intervalMillis)
        val lines = BridgeLogic.sentencesFor(fix, valid) + aisSentences(current, now)

        val results = transports.map { it.broadcast(lines, now) }
        val outcome = BridgeLogic.outcomeFor(results)
        val delivered = BridgeLogic.leftThePhone(outcome)

        sent += lines.size
        if (heartbeat) heartbeats += lines.size
        lastSentAtMillis = now

        // Assembled here because this is the only place that holds both halves at once: what
        // the feed knows, and what of it actually reached the wire.
        val snapshot = AisReport.Snapshot(
            atMillis = now,
            own = Position(fix.latitude, fix.longitude),
            fixValid = valid,
            // Test mode has no feed to have received anything from, so what the simulator
            // invented is the entire picture.
            known = if (current.simulated) lastVisibleTargets
            else AisTraffic.fresh(aisTargets, now).values.toList(),
            transmitted = lastVisibleTargets.mapTo(mutableSetOf()) { target -> target.mmsi },
            feedConnected = BridgeState.status.value.aisFeedConnected,
            feedMessages = aisMessages,
            simulated = current.simulated,
            link = "${BridgeLogic.transportsToken(results)}:${current.port} · " +
                BridgeLogic.clientTotal(results).let { n -> if (n == 1) "1 cliente" else "$n clientes" },
            sentences = lines,
        )

        BridgeState.update {
            it.copy(
                lastFix = fix,
                aisSnapshot = snapshot,
                sentencesSent = sent,
                heartbeatsSent = heartbeats,
                lastFixAtMillis = lastFixAtMillis,
                lastSendOkAtMillis = if (delivered) now else it.lastSendOkAtMillis,
                fixValid = valid,
                outcome = outcome,
            )
        }

        // Only transitions reach the on-screen log; the per-fix record goes to the CSV.
        EventLog.recordAll(tracker.onEmission(now, outcome, valid))
        if (current.rawLogEnabled) {
            TrackLogWriter.append(
                this,
                TrackLogFormatter.csvLine(
                    nowMillis = now,
                    fix = fix,
                    valid = valid,
                    outcome = outcome,
                    transports = BridgeLogic.transportsToken(results),
                    clients = BridgeLogic.clientTotal(results),
                ),
            )
        }
    }

    private fun onClientsChanged(count: Int) {
        val previous = lastClientCount
        lastClientCount = count
        BridgeState.update { it.copy(tcpClients = count) }
        updateNotification()
        if (autoOffEnabled) {
            if (count > 0) cancelIdleOff() else armIdleOff()
        }
        // A client that just connected would otherwise sit with an empty chart until the
        // next fix arrives; give it the last known position — and the names with it, which
        // are on a slower cycle and would otherwise leave the triangles blank for a minute.
        if (count > previous) {
            lastAisNamesAtMillis = 0L
            emitCurrentFix(heartbeat = true)
        }
    }

    /**
     * Keeps the CPU and the Wi-Fi radio awake while transmitting. A foreground service
     * alone does neither: with the screen off the CPU suspends between wakeups and the
     * Wi-Fi chip enters power save, which stalls the TCP stream and leaves Navionics
     * showing a position that stopped updating.
     */
    @Suppress("DEPRECATION") // WIFI_MODE_FULL_HIGH_PERF, see below
    private fun acquireLocks() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } catch (e: Exception) {
            // Without the lock the bridge still works with the screen on; don't fail the start.
        }
        try {
            if (wifiLock == null) {
                val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                // HIGH_PERF is deprecated but is the mode that actually survives screen-off;
                // LOW_LATENCY only applies while the screen is on, which is not our case.
                wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, WAKE_LOCK_TAG).apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } catch (e: Exception) {
            // Same: best effort.
        }
    }

    private fun releaseLocks() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
        try {
            wifiLock?.takeIf { it.isHeld }?.release()
        } catch (_: Exception) {
        }
        wifiLock = null
    }

    @Synchronized
    private fun armIdleOff() {
        autoOffJob?.cancel()
        autoOffJob = scope.launch {
            delay(autoOffMillis)
            withContext(Dispatchers.Main) { stopSelfAndCleanup(StopReason.IDLE_TIMEOUT) }
        }
    }

    /**
     * Disarms the watchdog, and nothing else.
     *
     * This used to tear the AIS feed down here as well, which put the teardown on the one
     * path that means the opposite of shutting down: a client connected. So the moment
     * Navionics attached, the feed was closed, the traffic was wiped, and every batch from
     * then on carried our own position and not one vessel — for the rest of the session,
     * because nothing recreates the feed outside a session start. The plotter was right when
     * it said it was receiving no AIS.
     */
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

    /** Tears the transmitting session down, leaving the service itself alive. */
    private fun stopSession() {
        autoOffEnabled = false
        collectJob?.cancel()
        collectJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        batteryJob?.cancel()
        batteryJob = null
        autoOffJob?.cancel()
        autoOffJob = null
        transports.forEach {
            try {
                it.detachClientCallback()
                it.stop()
            } catch (_: Exception) {
            }
        }
        transports.clear()
        // The feed is the session's, and aisstream allows one connection per key: leaving it
        // open strands that connection, and the next session's feed is refused outright.
        aisFeed?.stop()
        aisFeed = null
        aisTargets = emptyMap()
        sent = 0
        heartbeats = 0
        lastFix = null
        lastFixAtMillis = 0L
        lastSentAtMillis = 0L
        lastClientCount = 0
        config = null
    }

    /** Detaches the client callback so a transport being torn down can't re-arm the watchdog. */
    private fun NmeaTransport.detachClientCallback() {
        if (this is NmeaTcpServer) {
            onClientsChanged = null
            onClientEvent = null
        }
    }

    private fun stopSelfAndCleanup(reason: StopReason) {
        // Persisted so the UI can explain the silence even after the process is gone —
        // an idle shutdown is otherwise indistinguishable from "the app died".
        ConfigStore.saveStopReason(this, reason.takeIf { it != StopReason.USER })
        val endedAt = System.currentTimeMillis()
        EventLog.record(LogEvent(atMillis = endedAt, kind = EventKind.SESSION_STOP, detail = reason.name))
        TrackLogWriter.close(TrackLogFormatter.sessionFooter(endedAt, reason))
        if (reason == StopReason.IDLE_TIMEOUT) notifyAutoOff()
        stopSession()
        releaseLocks()
        BridgeState.reset()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        releaseLocks()
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

    /** One-off, dismissible notice: the watchdog stopped the bridge and nobody would know. */
    private fun notifyAutoOff() {
        try {
            createNotificationChannel()
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.notif_autooff_title))
                .setContentText(getString(R.string.notif_autooff_text, autoOffMillis / 60_000L))
                .setSmallIcon(R.drawable.ic_bridge)
                .setAutoCancel(true)
                .setContentIntent(
                    PendingIntent.getActivity(
                        this, 2,
                        Intent(this, MainActivity::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
                .build()
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIF_AUTO_OFF_ID, notification)
        } catch (e: Exception) {
            // A missing notification must never keep the service from shutting down.
        }
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

        val title = if (status.simulated) {
            getString(R.string.notif_title_simulated)
        } else {
            getString(R.string.notif_title)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
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
        private const val NOTIF_AUTO_OFF_ID = 2
        private const val BATTERY_SAMPLE_MILLIS = 10_000L
        private const val MIN_HEARTBEAT_TICK_MILLIS = 200L
        private const val WAKE_LOCK_TAG = "oshun:gps-bridge"

        /** Overridable so tests can supply a fake GPS source instead of Play Services. */
        var fixProviderFactory: (Context) -> FixProvider = { LocationSource(it) }

        /** Idle shutdown window; overridable so tests don't have to wait 15 minutes. */
        var autoOffMillis = 15 * 60 * 1000L

        const val ACTION_STOP = "com.oshun.gpsbridge.STOP"
        const val EXTRA_PORT = "port"
        const val EXTRA_TCP = "tcp"
        const val EXTRA_UDP = "udp"
        const val EXTRA_INTERVAL = "interval"
        const val EXTRA_AUTO_OFF = "autooff"
        const val EXTRA_RAW_LOG = "rawlog"
        const val EXTRA_SIMULATED = "sim"
        const val EXTRA_AIS = "ais"

        /** Marks a log entry that carries a verbatim feed message rather than a state change. */
        const val RAW_PREFIX = "raw:"

        /** The feed's state in the log: up, or down with whatever the server said on the way out. */
        const val UP = "up"
        const val DOWN_PREFIX = "down:"

        /** Marks a log entry carrying the feed's own refusal, in its words. */
        const val ERROR_PREFIX = "error:"

        /** How often the message counter reaches the screen. */
        private const val AIS_COUNT_INTERVAL_MILLIS = 1_000L

        fun start(context: Context, config: BridgeConfig) {
            val intent = Intent(context, GpsBridgeService::class.java).apply {
                putExtra(EXTRA_PORT, config.port)
                putExtra(EXTRA_TCP, config.tcpEnabled)
                putExtra(EXTRA_UDP, config.udpEnabled)
                putExtra(EXTRA_INTERVAL, config.intervalMillis)
                putExtra(EXTRA_AUTO_OFF, config.autoOffEnabled)
                putExtra(EXTRA_RAW_LOG, config.rawLogEnabled)
                putExtra(EXTRA_SIMULATED, config.simulated)
                putExtra(EXTRA_AIS, config.aisEnabled)
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
