package com.phairplay.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MediaUriPolicyTest — the shared rule for a media URI the receiver fetches itself.
 *
 * WHY: both stacks let a peer name the address the box then opens (AirPlay
 * `POST /play`, DLNA `SetAVTransportURI`). The rule is one place, so it is tested once:
 * the normal case must still work, and every hostile shape must be refused with a
 * reason that is safe to log.
 */
class MediaUriPolicyTest {

    @Test
    fun `a private http URL is accepted`() {
        assertNull(MediaUriPolicy.check("http://192.168.10.5:8080/movie.mp4"))
        assertEquals(
            "http://192.168.10.5:8080/movie.mp4",
            MediaUriPolicy.accept("  http://192.168.10.5:8080/movie.mp4  "),
        )
    }

    @Test
    fun `a public https URL is accepted`() {
        assertNull(MediaUriPolicy.check("https://vjs.zencdn.net/v/oceans.mp4"))
    }

    @Test
    fun `a file URI is refused`() {
        assertEquals(
            "unsupported URI scheme",
            MediaUriPolicy.check("file:///data/data/com.phairplay.firetv/shared_prefs/phairplay_prefs.xml"),
        )
        assertNull(MediaUriPolicy.accept("file:///etc/passwd"))
    }

    @Test
    fun `the non-http schemes are refused`() {
        for (uri in listOf(
            "smb://192.168.1.5/share/movie.mkv",
            "rtsp://192.168.1.5/live",
            "ftp://192.168.1.5/movie.mp4",
            "rtmp://192.168.1.5/live",
            "udp://239.0.0.1:1234",
        )) {
            assertEquals(uri, "unsupported URI scheme", MediaUriPolicy.check(uri))
        }
    }

    @Test
    fun `an empty URI is refused`() {
        assertEquals("empty URI", MediaUriPolicy.check(""))
        assertEquals("empty URI", MediaUriPolicy.check("   "))
        assertEquals("empty URI", MediaUriPolicy.check(null))
    }

    @Test
    fun `an over-long URI is refused`() {
        val uri = "http://192.168.1.5/" + "a".repeat(MediaUriPolicy.MAX_URI_LENGTH)
        assertEquals("URI too long", MediaUriPolicy.check(uri))
    }

    @Test
    fun `a URI with control characters is refused`() {
        assertEquals(
            "URI contains control characters",
            MediaUriPolicy.check("http://192.168.1.5/movie.mp4\r\nHost: evil"),
        )
    }

    @Test
    fun `a URI without a scheme is refused`() {
        assertEquals("URI has no scheme", MediaUriPolicy.check("192.168.1.5/movie.mp4"))
    }

    @Test
    fun `a loopback address is refused`() {
        assertEquals(
            "the address is a loopback address",
            MediaUriPolicy.check("http://127.0.0.1:8080/admin"),
        )
    }

    @Test
    fun `the cloud metadata address is refused`() {
        assertEquals(
            "the address is a link-local address",
            MediaUriPolicy.check("http://169.254.169.254/latest/meta-data/"),
        )
        assertEquals(
            "the address is a cloud metadata address",
            MediaUriPolicy.check("http://100.100.100.200/latest/meta-data/"),
        )
    }

    @Test
    fun `the obfuscated notations of an address are refused`() {
        for (uri in listOf(
            "http://2130706433/",
            "http://0x7f000001/",
            "http://0177.0.0.1/",
        )) {
            assertEquals(uri, "the address uses a non-standard notation", MediaUriPolicy.check(uri))
        }
    }

    @Test
    fun `a user info part that hides the host is refused`() {
        assertEquals(
            "the URI carries a user info part",
            MediaUriPolicy.check("http://192.168.1.5@evil.example/movie.mp4"),
        )
    }

    @Test
    fun `multicast and the reserved range are refused`() {
        assertFalse(MediaUriPolicy.check("http://239.255.255.250/") == null)
        assertFalse(MediaUriPolicy.check("http://250.1.2.3/") == null)
        assertFalse(MediaUriPolicy.check("http://0.0.0.0/") == null)
    }

    @Test
    fun `the reason never contains the URI`() {
        val uri = "http://169.254.169.254/latest/meta-data/iam/security-credentials/"
        val reason = MediaUriPolicy.check(uri)
        assertNotNull(reason)
        assertFalse("the reason must be safe to log", requireNotNull(reason).contains("169.254"))
    }

    @Test
    fun `a name is not resolved and stays allowed`() {
        assertTrue(MediaUriPolicy.check("http://192.168.10.2:8099/log") == null)
        assertNull(MediaUriPolicy.check("https://example.com/video.mp4"))
    }
}
