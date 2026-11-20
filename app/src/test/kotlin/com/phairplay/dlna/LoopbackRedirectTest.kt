package com.phairplay.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LoopbackRedirectTest — the sender-local HLS proxy case captured on 2026-11-20:
 * the playlist came from `http://192.168.10.5:9979/proxy?...` but named its
 * segments `http://127.0.0.1:9979/proxy?...`, which the box read as its own
 * localhost and could never connect to.
 */
class LoopbackRedirectTest {

    private val origin = "192.168.10.5"

    @Test
    fun `loopback segment host is replaced while port and raw query survive`() {
        val url = "http://127.0.0.1:9979/proxy?do=m3u8&url=https%3A%2F%2Fcdn.example%2Fa.m3u8&v=3"
        assertEquals(
            "http://192.168.10.5:9979/proxy?do=m3u8&url=https%3A%2F%2Fcdn.example%2Fa.m3u8&v=3",
            redirectLoopbackUrl(url, origin)
        )
    }

    @Test
    fun `localhost and 127-range hosts are all treated as loopback`() {
        assertTrue(isLoopbackHttpHost("127.0.0.1"))
        assertTrue(isLoopbackHttpHost("127.12.0.4"))
        assertTrue(isLoopbackHttpHost("LOCALHOST"))
        assertTrue(isLoopbackHttpHost("::1"))
        assertFalse(isLoopbackHttpHost(origin))
        assertFalse(isLoopbackHttpHost(null))
    }

    @Test
    fun `non-loopback URLs are left alone`() {
        val url = "https://cdn.example.com/path/index.m3u8?token=abc"
        assertEquals(url, redirectLoopbackUrl(url, origin))
    }

    @Test
    fun `missing origin or malformed URL is a no-op`() {
        val url = "http://127.0.0.1:9979/proxy?x=1"
        assertEquals(url, redirectLoopbackUrl(url, null))
        assertEquals(url, redirectLoopbackUrl(url, "  "))
        assertEquals("not a url", redirectLoopbackUrl("not a url", origin))
    }

    @Test
    fun `scheme is preserved on an https loopback URL`() {
        assertEquals(
            "https://192.168.10.5:8443/segment.ts",
            redirectLoopbackUrl("https://localhost:8443/segment.ts", origin)
        )
    }
}
