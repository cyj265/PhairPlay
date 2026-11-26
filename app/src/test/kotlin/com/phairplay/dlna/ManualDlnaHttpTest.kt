package com.phairplay.dlna

import com.phairplay.dlna.transport.ManualDlnaHttp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the in-project DLNA layer that replaced jUPnP.
 *
 * The old stack's registry returned 404 for its own descriptor path, so the
 * hand-rolled descriptor / SCPD / SOAP / GENA routes are now the only control
 * surface. These tests pin the paths and documents control points depend on.
 */
class ManualDlnaHttpTest {

    private val base = ManualDlnaHttp.getBase()

    @Test
    fun `port and UDN are stable and parseable`() {
        assertEquals(8899, ManualDlnaHttp.CONTROL_PORT)
        assertTrue(ManualDlnaHttp.getUdn().matches(Regex("[0-9a-f-]{36}")))
        assertEquals("/upnp/dev/" + ManualDlnaHttp.getUdn(), base)
    }

    @Test
    fun `descriptor scpd action and service paths are recognised`() {
        assertTrue(ManualDlnaHttp.isDeviceDesc("$base/desc"))
        assertFalse(ManualDlnaHttp.isDeviceDesc("$base/other"))

        val avt = "$base/svc/upnp-org/AVTransport"
        assertTrue(ManualDlnaHttp.isScpd("$avt/desc"))
        assertTrue(ManualDlnaHttp.isAction("$avt/action"))
        assertTrue(ManualDlnaHttp.isService("$avt/event"))
        assertEquals("AVTransport", ManualDlnaHttp.serviceOf("$avt/action"))
        assertEquals("RenderingControl", ManualDlnaHttp.serviceOf("$base/svc/upnp-org/RenderingControl/desc"))
    }

    @Test
    fun `device descriptor advertises MediaRenderer with all three services`() {
        val xml = ManualDlnaHttp.deviceDescriptorXml("http://192.168.10.12:8899")

        assertTrue(xml.contains("<deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>"))
        assertTrue(xml.contains("<UDN>uuid:" + ManualDlnaHttp.getUdn() + "</UDN>"))
        assertTrue(xml.contains(ManualDlnaHttp.AVT))
        assertTrue(xml.contains(ManualDlnaHttp.RC))
        assertTrue(xml.contains(ManualDlnaHttp.CM))
        assertTrue(xml.contains("<presentationURL>http://192.168.10.12:8899</presentationURL>"))
    }

    @Test
    fun `scpd documents keep the actions control points call`() {
        val avt = ManualDlnaHttp.scpdXml("$base/svc/upnp-org/AVTransport/desc")!!
        assertTrue(avt.contains("<name>SetAVTransportURI</name>"))
        assertTrue(avt.contains("<name>GetPositionInfo</name>"))

        val rc = ManualDlnaHttp.scpdXml("$base/svc/upnp-org/RenderingControl/desc")!!
        assertTrue(rc.contains("<name>SetVolume</name>"))

        val cm = ManualDlnaHttp.scpdXml("$base/svc/upnp-org/ConnectionManager/desc")!!
        assertTrue(cm.contains("<name>GetProtocolInfo</name>"))

        assertNull(ManualDlnaHttp.scpdXml("$base/svc/upnp-org/Unknown/desc"))
    }

    @Test
    fun `SOAP action name survives envelope wrapping`() {
        val body = """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>""" +
            """<u:Play xmlns:u="urn:schemas-upnp-org:service:AVTransport:1"><InstanceID>0</InstanceID>""" +
            """</u:Play></s:Body></s:Envelope>"""

        assertEquals("Play", ManualDlnaHttp.actionNameOf(body))
        val wrapped = ManualDlnaHttp.wrapEnvelope("<u:PlayResponse/>")
        assertTrue(wrapped.contains("Envelope"))
        assertTrue(wrapped.contains("PlayResponse"))
    }
}
