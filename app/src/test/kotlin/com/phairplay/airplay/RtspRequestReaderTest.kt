package com.phairplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * RtspRequestReaderTest — body budgets for the two ways a sender uploads artwork.
 *
 * A/C senders use `PUT /photo`, but some iOS builds send the same JPEG through
 * `SET_PARAMETER` with `Content-Type: image/jpeg`. Both must get the photo-sized budget;
 * otherwise a legitimate image is rejected at the generic 1 MB RTSP message cap.
 */
class RtspRequestReaderTest {

    private fun wire(method: String, uri: String, headers: Map<String, String>, bodySize: Int): ByteArray {
        val head = buildString {
            append("$method $uri RTSP/1.0\r\n")
            headers.forEach { (key, value) -> append("$key: $value\r\n") }
            append("\r\n")
        }.toByteArray(Charsets.US_ASCII)
        return head + ByteArray(bodySize) { 0x41 }
    }

    @Test
    fun `SET_PARAMETER with image content type uses the photo body budget`() {
        val reader = RtspRequestReader(maxMessageBytes = 1_024, maxPhotoBytes = 4_096)
        val bytes = wire(
            "SET_PARAMETER", "/",
            mapOf("Content-Length" to "2000", "Content-Type" to "image/jpeg"),
            2_000
        )

        val request = reader.read(ByteArrayInputStream(bytes))
        assertNotNull(request)
        assertEquals(2_000, request!!.bodyBytes.size)
    }

    @Test
    fun `SET_PARAMETER without image content type stays within the generic budget`() {
        val reader = RtspRequestReader(maxMessageBytes = 1_024, maxPhotoBytes = 4_096)
        val bytes = wire(
            "SET_PARAMETER", "/",
            mapOf("Content-Length" to "2000", "Content-Type" to "application/x-apple-binary-plist"),
            2_000
        )

        assertNull(reader.read(ByteArrayInputStream(bytes)))
    }

    @Test
    fun `PUT photo keeps its photo budget`() {
        val reader = RtspRequestReader(maxMessageBytes = 1_024, maxPhotoBytes = 4_096)
        val bytes = wire(
            "PUT", PhotoHandler.PHOTO_PATH + "?session=1",
            mapOf("Content-Length" to "2000", "Content-Type" to "image/png"),
            2_000
        )

        val request = reader.read(ByteArrayInputStream(bytes))
        assertNotNull(request)
        assertEquals(2_000, request!!.bodyBytes.size)
    }
}
