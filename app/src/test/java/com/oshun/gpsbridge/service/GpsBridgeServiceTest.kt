package com.oshun.gpsbridge.service

import android.content.Intent
import com.oshun.gpsbridge.core.BridgeState
import com.oshun.gpsbridge.location.FixProvider
import com.oshun.gpsbridge.location.LocationSource
import com.oshun.gpsbridge.model.Fix
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.BufferedReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GpsBridgeServiceTest {

    @After
    fun tearDown() {
        GpsBridgeService.fixProviderFactory = { LocationSource(it) }
        BridgeState.reset()
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private val sampleFix = Fix(
        latitude = 48.1173,
        longitude = 11.5167,
        speedMetersPerSecond = 5.0,
        bearingDegrees = 90.0,
        altitudeMeters = 100.0,
        satellites = 8,
        timeUtcMillis = 0L,
    )

    private fun fakeProvider(fix: Fix) = object : FixProvider {
        override fun fixes(intervalMillis: Long): Flow<Fix> = flow {
            while (true) {
                emit(fix)
                delay(100)
            }
        }
    }

    private fun connect(port: Int): Socket {
        var last: Exception? = null
        repeat(100) {
            try {
                return Socket(InetAddress.getByName("127.0.0.1"), port).apply { soTimeout = 5000 }
            } catch (e: Exception) {
                last = e
                Thread.sleep(20)
            }
        }
        throw last!!
    }

    @Test
    fun startBroadcastsNmeaOverTcpThenStops() {
        val port = freePort()
        GpsBridgeService.fixProviderFactory = { fakeProvider(sampleFix) }

        val context = RuntimeEnvironment.getApplication()
        val intent = Intent(context, GpsBridgeService::class.java).apply {
            putExtra(GpsBridgeService.EXTRA_PORT, port)
            putExtra(GpsBridgeService.EXTRA_TCP, true)
            putExtra(GpsBridgeService.EXTRA_UDP, true)
            putExtra(GpsBridgeService.EXTRA_INTERVAL, 100L)
        }

        val controller = Robolectric.buildService(GpsBridgeService::class.java, intent)
            .create()
            .startCommand(0, 1)
        val service = controller.get()

        assertTrue("service marked running", BridgeState.status.value.running)
        assertTrue("tcp enabled", BridgeState.status.value.tcpEnabled)

        val client = connect(port)
        val line = BufferedReader(client.getInputStream().reader(Charsets.US_ASCII)).readLine()
        assertTrue("got an NMEA sentence: $line", line.startsWith("\$GP"))
        client.close()

        val stopIntent = Intent(context, GpsBridgeService::class.java)
            .setAction(GpsBridgeService.ACTION_STOP)
        service.onStartCommand(stopIntent, 0, 2)

        assertFalse("service stopped", BridgeState.status.value.running)
    }

    @Test
    fun udpOnlyConfigStartsWithoutTcpClients() {
        val port = freePort()
        GpsBridgeService.fixProviderFactory = { fakeProvider(sampleFix) }

        val context = RuntimeEnvironment.getApplication()
        val intent = Intent(context, GpsBridgeService::class.java).apply {
            putExtra(GpsBridgeService.EXTRA_PORT, port)
            putExtra(GpsBridgeService.EXTRA_TCP, false)
            putExtra(GpsBridgeService.EXTRA_UDP, true)
        }

        val service = Robolectric.buildService(GpsBridgeService::class.java, intent)
            .create()
            .startCommand(0, 1)
            .get()

        assertTrue(BridgeState.status.value.running)
        assertFalse("tcp disabled", BridgeState.status.value.tcpEnabled)
        assertTrue("udp enabled", BridgeState.status.value.udpEnabled)

        service.onStartCommand(
            Intent(context, GpsBridgeService::class.java).setAction(GpsBridgeService.ACTION_STOP),
            0, 2,
        )
        assertFalse(BridgeState.status.value.running)
    }
}
