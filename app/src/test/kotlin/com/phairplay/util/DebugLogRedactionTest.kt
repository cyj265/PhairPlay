package com.phairplay.util

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DebugLogRedactionTest — what the diagnostics port must not reveal.
 *
 * WHY: `http://<box>:8099/` has no authentication and answers anything on the LAN.
 * Besides the signed query strings of playback URLs, the payload can contain the
 * advertised device name — a free-text name of the household that no pattern can
 * recognise. The app registers that name, and the export masks it.
 */
class DebugLogRedactionTest {

    @After
    fun tearDown() {
        DebugLog.clearPersonalStrings()
    }

    @Test
    fun `a registered name is masked in the export`() {
        DebugLog.registerPersonalString("客厅电视盒子")

        val exported = DebugLog.redactForExport("DLNA renderer advertising as: 客厅电视盒子")

        assertFalse("the name must not appear", exported.contains("客厅电视盒子"))
        assertTrue(exported.contains(DebugLog.PERSONAL_PLACEHOLDER))
    }

    @Test
    fun `the name is masked case-insensitively`() {
        DebugLog.registerPersonalString("LivingRoomTV")

        val exported = DebugLog.redactForExport("advertising as livingroomtv at 192.168.10.2")

        assertFalse(exported.contains("livingroomtv"))
        assertTrue(exported.contains(DebugLog.PERSONAL_PLACEHOLDER))
    }

    @Test
    fun `a name shorter than four characters is not registered`() {
        DebugLog.registerPersonalString("TV")

        val exported = DebugLog.redactForExport("AirPlay TV session")

        assertTrue(exported.contains("TV"))
    }

    @Test
    fun `the URL query is still cut in the same pass`() {
        DebugLog.registerPersonalString("客厅电视盒子")

        val exported = DebugLog.redactForExport(
            "客厅电视盒子 播放 https://cdn.example.com/v.mp4?token=secret"
        )

        assertFalse(exported.contains("客厅电视盒子"))
        assertFalse(exported.contains("token=secret"))
        assertTrue(exported.contains("?<查询串已脱敏>"))
    }

    @Test
    fun `clearing the names restores the original text`() {
        DebugLog.registerPersonalString("客厅电视盒子")
        DebugLog.clearPersonalStrings()

        val exported = DebugLog.redactForExport("客厅电视盒子")

        assertTrue(exported.contains("客厅电视盒子"))
    }
}
