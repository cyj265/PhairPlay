package com.phairplay.dlna

import android.net.Uri
import androidx.media3.datasource.DataSpec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.BufferedReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import kotlin.concurrent.thread

/**
 * RangeOnlySourceTest — DLNA from servers that refuse a header-less GET.
 *
 * Captured on the p230 on 2026-11-26: a source that answers 416 unless `Range` is present got six
 * requests in a row without one and the position stayed at 0 s. media3's rule (send Range only
 * when the position is non-zero or the length is known) leaves the very first request for a whole
 * file without a Range header, so the retry has to be ours.
 */
@RunWith(RobolectricTestRunner::class)
class RangeOnlySourceTest {

    private val server = ServerSocket(0)
    private val seenRanges: MutableList<String?> = Collections.synchronizedList(mutableListOf())
    private var worker: Thread? = null

    @After
    fun tearDown() {
        runCatching { server.close() }
        worker?.join(2_000)
    }

    /** Serves 416 when no Range is present, 206 with the requested slice otherwise. */
    private fun startRangeOnlyServer(body: ByteArray) {
        worker = thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: Exception) { return@thread }
                thread(isDaemon = true) { handle(socket, body) }
            }
        }
    }

    private fun handle(socket: Socket, body: ByteArray) {
        socket.use { s ->
            val reader = s.getInputStream().bufferedReader()
            val range = readHeaders(reader)
            synchronized(seenRanges) { seenRanges.add(range) }
            val out: OutputStream = s.getOutputStream()
            if (range == null) {
                out.write(
                    ("HTTP/1.1 416 Range Not Satisfiable\r\n" +
                        "Content-Range: bytes */${body.size}\r\n" +
                        "Content-Length: 0\r\n\r\n").toByteArray()
                )
            } else {
                val start = range.removePrefix("bytes=").substringBefore('-').toIntOrNull() ?: 0
                val slice = body.copyOfRange(start.coerceAtMost(body.size), body.size)
                out.write(
                    ("HTTP/1.1 206 Partial Content\r\n" +
                        "Content-Type: video/mp4\r\n" +
                        "Accept-Ranges: bytes\r\n" +
                        "Content-Range: bytes $start-${body.size - 1}/${body.size}\r\n" +
                        "Content-Length: ${slice.size}\r\n\r\n").toByteArray()
                )
                out.write(slice)
            }
            out.flush()
        }
    }

    private fun readHeaders(reader: BufferedReader): String? {
        reader.readLine() ?: return null
        var range: String? = null
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            if (line.startsWith("Range:", ignoreCase = true)) range = line.substringAfter(':').trim()
        }
        return range
    }

    @Test
    fun `416 on a header-less first request is retried with bytes=0-`() {
        val body = ByteArray(1024) { (it % 251).toByte() }
        startRangeOnlyServer(body)
        val port = server.localPort
        val source = Ipv4HttpDataSource()
        try {
            val spec = DataSpec(Uri.parse("http://127.0.0.1:$port/movie.mp4"))
            val remaining = source.open(spec)
            assertEquals(body.size.toLong(), remaining)
            val buffer = ByteArray(body.size)
            var read = 0
            while (read < buffer.size) {
                val n = source.read(buffer, read, buffer.size - read)
                if (n == -1) break
                read += n
            }
            assertEquals(body.size, read)
            assertTrue("body should match", buffer.contentEquals(body))
        } finally {
            source.close()
        }
        assertEquals(2, seenRanges.size)
        assertEquals(null, seenRanges[0])
        assertEquals("bytes=0-", seenRanges[1])
    }
}
