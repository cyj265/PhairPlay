package com.phairplay.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * DidlTextTest — the "song title shown as &#33853;" bug, driven by the exact
 * bytes NetEase Cloud Music sends in DIDL-Lite metadata.
 */
class DidlTextTest {

    @Test
    fun `decimal character references become Chinese characters`() {
        // The title captured from a real DLNA cast on 2026-11-20.
        val senderTitle = "&#33853; (&#33457;&#24320;&#33457;&#33853;&#26085;&#21319;&#26085;&#27809;)"
        val decoded = DidlText.decodeCharRefs(senderTitle)

        assertEquals("落 (花开花落日升日没)", decoded)
    }

    @Test
    fun `hexadecimal character references are decoded too`() {
        assertEquals("落花", DidlText.decodeCharRefs("&#x843D;&#X82B1;"))
    }

    @Test
    fun `ordinary text and null pass through untouched`() {
        assertEquals("Annie", DidlText.decodeCharRefs("Annie"))
        assertEquals("A & B", DidlText.decodeCharRefs("A & B"))
        assertNull(DidlText.decodeCharRefs(null))
    }

    @Test
    fun `invalid or unpaired code points stay literal instead of crashing`() {
        assertEquals("&#99999999;", DidlText.decodeCharRefs("&#99999999;"))
        assertEquals("&#xD800;", DidlText.decodeCharRefs("&#xD800;"))
    }
}
