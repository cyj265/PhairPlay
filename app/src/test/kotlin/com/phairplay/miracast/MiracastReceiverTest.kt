package com.phairplay.miracast

import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.os.Looper
import com.phairplay.airplay.RtspRequest
import com.phairplay.service.ProtocolState
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MiracastReceiverTest — verifies Wi-Fi Direct service advertisement behavior.
 */
class MiracastReceiverTest {

    @Test
    fun `start advertises WFD service and emits advertising state`() {
        val context = mockk<Context>()
        val manager = mockk<WifiP2pManager>(relaxed = true)
        val channel = mockk<WifiP2pManager.Channel>(relaxed = true)
        val actionListener = slot<WifiP2pManager.ActionListener>()
        val states = mutableListOf<ProtocolState>()

        every { context.getSystemService(Context.WIFI_P2P_SERVICE) } returns manager
        every { context.mainLooper } returns Looper.getMainLooper()
        every { context.checkSelfPermission("android.permission.NEARBY_WIFI_DEVICES") } returns
            PackageManager.PERMISSION_GRANTED
        every { context.checkSelfPermission("android.permission.ACCESS_FINE_LOCATION") } returns
            PackageManager.PERMISSION_DENIED
        every { manager.initialize(eq(context), any(), any()) } returns channel
        every {
            manager.addLocalService(
                eq(channel),
                any<WifiP2pDnsSdServiceInfo>(),
                capture(actionListener)
            )
        } answers {
            actionListener.captured.onSuccess()
            Unit
        }

        MiracastReceiver(context) { states.add(it) }.start()

        verify(exactly = 1) {
            manager.addLocalService(eq(channel), any<WifiP2pDnsSdServiceInfo>(), any())
        }
        assertEquals(ProtocolState.ADVERTISING, states.last())
    }

    @Test
    fun `start emits error when WifiP2pManager is unavailable`() {
        val context = mockk<Context>()
        val states = mutableListOf<ProtocolState>()

        every { context.getSystemService(Context.WIFI_P2P_SERVICE) } returns null

        MiracastReceiver(context) { states.add(it) }.start()

        assertTrue(states.contains(ProtocolState.ERROR))
    }

    @Test
    fun `WFD RTSP port uses Miracast default`() {
        assertEquals(7236, MiracastReceiver.WFD_RTSP_PORT)
    }

    @Test
    fun `WFD RTSP server advertises sink capabilities`() {
        val server = WfdRtspServer(
            onSessionStarted = {},
            onSessionStopped = {}
        )

        val response = server.routeRequest(
            RtspRequest(
                method = "GET_PARAMETER",
                uri = "rtsp://192.168.49.1/wfd1.0",
                headers = mapOf("CSeq" to "2"),
                body = ""
            )
        )

        assertEquals(200, response.statusCode)
        assertEquals("text/parameters", response.headers["Content-Type"])
        assertTrue(response.body.contains("wfd_audio_codecs"))
        assertTrue(response.body.contains("wfd_video_formats"))
        assertTrue(response.body.contains("wfd_client_rtp_ports"))
    }

    @Test
    fun `WFD RTSP PLAY emits connected state once`() {
        var started = 0
        val server = WfdRtspServer(
            onSessionStarted = { started++ },
            onSessionStopped = {}
        )
        val request = RtspRequest(
            method = "PLAY",
            uri = "rtsp://192.168.49.1/wfd1.0",
            headers = mapOf("CSeq" to "5"),
            body = ""
        )

        assertEquals(200, server.routeRequest(request).statusCode)
        assertEquals(200, server.routeRequest(request).statusCode)

        assertEquals(1, started)
    }
}
