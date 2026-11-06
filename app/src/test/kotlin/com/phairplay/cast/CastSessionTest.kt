package com.phairplay.cast

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CastSessionTest {

    private fun text(message: CastMessage) = JsonParser.parseString(message.text).asJsonObject

    private fun receiver(message: CastMessage) = text(message).string("type")

    /** Runs LAUNCH and returns the transport id the receiver reports. */
    private fun launch(session: CastSession): String {
        val reply = session.handle(
            CLIENT,
            CastMessage("sender-0", "receiver-0", CastWire.RECEIVER,
                """{"type":"LAUNCH","appId":"CC1AD845","requestId":1}""")
        ).single()
        assertEquals("RECEIVER_STATUS", receiver(reply))
        return text(reply).getAsJsonObject("status")
            .getAsJsonArray("applications")[0].asJsonObject.string("transportId")
    }

    private fun connect(session: CastSession, transport: String) {
        session.handle(
            CLIENT,
            CastMessage("sender-0", transport, CastWire.CONNECTION, """{"type":"CONNECT"}""")
        )
    }

    @Test
    fun `heartbeat ping is answered with pong`() {
        val session = CastSession({}, {})
        val reply = session.handle(
            CLIENT,
            CastMessage("sender-0", "receiver-0", CastWire.HEARTBEAT,
                """{"type":"PING","requestId":7}""")
        ).single()
        assertEquals("PONG", receiver(reply))
        assertEquals(7, text(reply).get("requestId").asInt)
    }

    @Test
    fun `default media receiver is available but other apps are not`() {
        val session = CastSession({}, {})
        val reply = session.handle(
            CLIENT,
            CastMessage("sender-0", "receiver-0", CastWire.RECEIVER,
                """{"type":"GET_APP_AVAILABILITY","appId":["CC1AD845","YouTube"]}""")
        ).single()
        val availability = text(reply).getAsJsonObject("availability")
        assertEquals("APP_AVAILABLE", availability.get("CC1AD845").asString)
        assertEquals("APP_UNAVAILABLE", availability.get("YouTube").asString)
    }

    @Test
    fun `load hands an http url to the player`() {
        var loaded: CastLoad? = null
        val session = CastSession({ loaded = it }, {})
        val transport = launch(session)
        connect(session, transport)

        session.handle(
            CLIENT,
            CastMessage("sender-0", transport, CastWire.MEDIA,
                """{"type":"LOAD","requestId":2,"autoplay":true,"media":{""" +
                    """"contentId":"http://192.168.10.5/movie.mp4",""" +
                    """"contentType":"video/mp4","metadata":{"title":"Test Movie"}}}"""
            )
        )

        val request = requireNotNull(loaded)
        assertEquals("http://192.168.10.5/movie.mp4", request.url)
        assertEquals("Test Movie", request.title)
        assertTrue(request.autoplay)
    }

    @Test
    fun `load refuses a non-http url`() {
        var loaded: CastLoad? = null
        val session = CastSession({ loaded = it }, {})
        val transport = launch(session)
        connect(session, transport)

        val reply = session.handle(
            CLIENT,
            CastMessage("sender-0", transport, CastWire.MEDIA,
                """{"type":"LOAD","media":{"contentId":"file:///etc/passwd","contentType":"video/mp4"}}""")
        ).single()

        assertEquals("LOAD_FAILED", receiver(reply))
        assertNull(loaded)
    }

    @Test
    fun `load refuses an image`() {
        var loaded: CastLoad? = null
        val session = CastSession({ loaded = it }, {})
        val transport = launch(session)
        connect(session, transport)

        val reply = session.handle(
            CLIENT,
            CastMessage("sender-0", transport, CastWire.MEDIA,
                """{"type":"LOAD","media":{"contentId":"http://192.168.10.5/a.png","contentType":"image/png"}}""")
        ).single()

        assertEquals("LOAD_FAILED", receiver(reply))
        assertNull(loaded)
    }

    @Test
    fun `transport commands are forwarded to the player`() {
        val commands = mutableListOf<CastCommand>()
        val session = CastSession({}, { commands += it })
        val transport = launch(session)
        connect(session, transport)
        session.handle(
            CLIENT,
            CastMessage("sender-0", transport, CastWire.MEDIA,
                """{"type":"LOAD","media":{"contentId":"http://192.168.10.5/movie.mp4","contentType":"video/mp4"}}""")
        )
        commands.clear()

        session.handle(
            CLIENT,
            CastMessage("sender-0", transport, CastWire.MEDIA,
                """{"type":"SEEK","mediaSessionId":1,"currentTime":30,"resumeState":"PLAYBACK_START"}""")
        )

        assertEquals(listOf("SEEK", "PLAY"), commands.map { it.type })
        assertEquals(30_000.0, commands[0].value, 0.001)
    }

    private companion object {
        const val CLIENT = "client-1"
    }
}
