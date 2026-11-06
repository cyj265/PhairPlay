package com.phairplay.miracast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class P2pDeviceNameTest {

    @Test
    fun `short names are kept as-is`() {
        assertEquals("客厅电视盒子", P2pDeviceName.fromDisplayName(" 客厅电视盒子 "))
    }

    @Test
    fun `names longer than 32 UTF-8 bytes are truncated on a code point boundary`() {
        val name = "PhairPlay-客厅电视盒子-0123456789"
        val result = P2pDeviceName.fromDisplayName(name)!!
        assert(result.toByteArray(Charsets.UTF_8).size <= 32)
        assertEquals(true, name.startsWith(result))
        // Must not end mid-character: re-encoding has to round-trip exactly.
        assertEquals(result, String(result.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
    }

    @Test
    fun `empty and control-character names are rejected`() {
        assertNull(P2pDeviceName.fromDisplayName("   "))
        assertNull(P2pDeviceName.fromDisplayName("bad\u0000name"))
    }

    @Test
    fun `shell command quotes the name safely`() {
        val command = P2pDeviceName.command("/data/app/lib/libwfdctl.so", "/data/misc/wifi/sockets/p2p0", "it's")
        assertEquals(
            "'/data/app/lib/libwfdctl.so' '/data/misc/wifi/sockets/p2p0' 'SET device_name it'\"'\"'s'",
            command
        )
    }
}
