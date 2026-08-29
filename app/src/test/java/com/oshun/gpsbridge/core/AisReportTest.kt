package com.oshun.gpsbridge.core

import com.oshun.gpsbridge.model.AisTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AisReportTest {

    @Test
    fun aVesselSaysWhetherItWentOutOrWasFilteredAway() {
        // The whole point of the report: the count on the status card says how many left, and
        // this has to say which ones did not, so a chart with no targets has an explanation.
        val report = AisReport.format(snapshot())
        val near = report.lines().single { it.contains("MYRA") }
        val far = report.lines().single { it.contains("LEJANO") }
        assertTrue("the vessel in range went out: $near", near.endsWith("sí"))
        assertTrue("the vessel out of range did not: $far", far.endsWith("no"))
        assertTrue("recibidos counts them both", report.contains("recibidos      2"))
        assertTrue("transmitidos counts only one", report.contains("transmitidos   1"))
    }

    @Test
    fun theVesselsAreListedNearestFirst() {
        val report = AisReport.format(snapshot())
        assertTrue(report.indexOf("MYRA") < report.indexOf("LEJANO"))
    }

    @Test
    fun theSentencesGoInVerbatimAndWithoutTheirLineEndings() {
        // They are read as bytes, so a stray blank line between them is a lie about the wire.
        val report = AisReport.format(snapshot())
        assertTrue(report.lines().contains("!AIVDM,1,1,,A,1815C?hP1cKlS`QdC:LUp4hD0000,0*14"))
        assertFalse("no carriage returns survived", report.contains("\r"))
        assertTrue(report.contains("última tanda enviada (2 sentencias)"))
    }

    @Test
    fun withoutAFixItSaysSoRatherThanPrintingADistance() {
        // No own position means no near or far, and inventing one would be the worst possible
        // answer to "why is the chart empty".
        val report = AisReport.format(snapshot().copy(own = null))
        assertTrue(report.contains("sin fix"))
        assertTrue("the distance column is blank, not zero", report.lines().single { it.contains("MYRA") }.contains("?"))
    }

    @Test
    fun testModeSaysItIsInventingTheVessels() {
        val report = AisReport.format(snapshot().copy(simulated = true))
        assertTrue(report.contains("PRUEBA"))
        assertTrue(report.contains("apagado en modo prueba"))
    }

    @Test
    fun aQuietFeedStillProducesAReport() {
        val report = AisReport.format(snapshot().copy(known = emptyList(), transmitted = emptySet()))
        assertTrue(report.contains("Ningún barco recibido."))
        assertEquals(1, report.lines().count { it.startsWith("recibidos") })
    }

    private fun snapshot() = AisReport.Snapshot(
        atMillis = NOW,
        own = Position(-34.4123, -58.4890),
        fixValid = true,
        known = listOf(far, near),
        transmitted = setOf(near.mmsi),
        feedConnected = true,
        feedMessages = 1843,
        simulated = false,
        link = "TCP:2000 · 1 cliente",
        sentences = listOf(
            "\$GPRMC,143210,A,3424.7380,S,05829.3400,W,0.0,0.0,290826,,*1B\r\n",
            "!AIVDM,1,1,,A,1815C?hP1cKlS`QdC:LUp4hD0000,0*14\r\n",
        ),
    )

    private companion object {
        const val NOW = 1787229296000L

        val near = AisTarget(
            mmsi = 538006335,
            name = "MYRA",
            latitude = -34.42925,
            longitude = -58.42386,
            speedKnots = 10.7,
            courseDegrees = 150.4,
            headingDegrees = 152.0,
            reportedAtMillis = NOW - 12_000L,
        )

        val far = AisTarget(
            mmsi = 244123456,
            name = "LEJANO",
            latitude = -34.10,
            longitude = -58.10,
            speedKnots = 5.0,
            courseDegrees = 90.0,
            reportedAtMillis = NOW - 30_000L,
        )
    }
}
