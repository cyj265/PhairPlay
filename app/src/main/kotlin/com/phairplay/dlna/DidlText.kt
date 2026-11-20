package com.phairplay.dlna

/**
 * Decodes XML numeric character references in DIDL-Lite display text.
 *
 * WHY: several DLNA control points — NetEase Cloud Music among them — write
 * Chinese metadata as `&#33853;` instead of UTF-8 characters. The SOAP layer
 * only unescapes the five named entities (`&amp;`, `&lt;`, …), so the "now
 * playing" card used to display the digits verbatim, which reads as garbled
 * text. Decoding here (after the SOAP unescape, before the UI) turns them back
 * into the characters the sender meant.
 *
 * SCOPE: deliberately applied to human-readable fields (title / artist) only.
 * A URL can legitimately contain a literal `&#...` sequence in a query string;
 * decoding that would corrupt the media address, so callers must not run this
 * over URIs or over the DIDL document that is echoed back to the sender.
 */
object DidlText {

    private val CHAR_REF = Regex("&#(?:x[0-9a-fA-F]+|X[0-9a-fA-F]+|[0-9]+);")

    /** Returns [text] with `&#NNN;` / `&#xHHH;` replaced by their characters. */
    @JvmStatic
    fun decodeCharRefs(text: String?): String? {
        if (text == null || "&#" !in text) return text
        return CHAR_REF.replace(text) { match ->
            val digits = match.value.substring(2, match.value.length - 1)
            val codePoint = try {
                if (digits.startsWith("x") || digits.startsWith("X")) {
                    digits.substring(1).toInt(16)
                } else {
                    digits.toInt(10)
                }
            } catch (_: NumberFormatException) {
                return@replace match.value
            }
            if (codePoint in 0xD800..0xDFFF) {
                // A lone high/low surrogate is not a character on its own.
                return@replace match.value
            }
            try {
                String(Character.toChars(codePoint))
            } catch (_: IllegalArgumentException) {
                // Out of range: keep the original text.
                match.value
            }
        }
    }
}
