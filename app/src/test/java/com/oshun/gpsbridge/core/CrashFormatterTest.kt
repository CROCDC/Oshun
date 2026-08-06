package com.oshun.gpsbridge.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashFormatterTest {

    @Test
    fun includesMetadataAndTrace() {
        val out = CrashFormatter.format(
            listOf("App version" to "0.1.0", "SDK" to "34"),
            "java.lang.NullPointerException\n\tat com.oshun.Foo.bar(Foo.kt:10)",
        )
        assertTrue(out.contains("App version: 0.1.0"))
        assertTrue(out.contains("SDK: 34"))
        assertTrue(out.contains("NullPointerException"))
        assertTrue(out.contains("Foo.kt:10"))
    }

    @Test
    fun emptyMetadataOmitsHeader() {
        val out = CrashFormatter.format(emptyList(), "boom")
        assertFalse(out.contains(":"))
        assertTrue(out.trim() == "boom")
    }
}
