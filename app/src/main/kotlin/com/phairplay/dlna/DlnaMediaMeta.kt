package com.phairplay.dlna

/**
 * What the UI knows about the item currently on the renderer.
 *
 * Two consumers need this: the music UI (audio-only casts must show a player
 * card, not a black video surface) and "return to playback" affordances (the
 * remote can leave full-screen playback, so something has to offer a way back
 * while the media keeps playing underneath).
 *
 * Kept as a plain singleton because the producer (ManualDlnaHttp, a SOAP
 * thread) and the consumers (Activity main thread) live on different threads
 * and the values are small, replaceable, and non-critical.
 */
object DlnaMediaMeta {

    /** True while an item is loaded (even paused, even with the UI hidden). */
    @Volatile
    @JvmField
    var hasMedia: Boolean = false

    /** True once the player reported no video track (music / audio-only cast). */
    @Volatile
    @JvmField
    var audioOnly: Boolean = false

    /** What the sender wrote in DIDL-Lite; null when it said nothing. */
    @Volatile
    private var senderTitle: String? = null
    @Volatile
    private var senderArtist: String? = null

    /** What Media3 read out of the container itself (ID3 for mp3, covr for m4a). */
    @Volatile
    private var tagTitle: String? = null
    @Volatile
    private var tagArtist: String? = null

    /** The media URL's file name — the last thing standing before a blank card. */
    @Volatile
    private var fileName: String? = null

    @Volatile
    @JvmField
    var albumArtUri: String? = null

    @Volatile
    private var embeddedArtBytes: ByteArray? = null

    /**
     * Three sources in trust order, resolved here rather than at the call sites
     * so the card, the OSD, the home page and the notification cannot disagree
     * about what is playing.
     *
     * The file name is deliberately not folded into it by the SOAP layer:
     * phone music apps name their cache files hashes
     * (`M500003XFddH3QFNkO.mp3`), and a guess must never outrank the tags the
     * media file carries itself — which is how the competing receivers still
     * show a real song title when the sender sends no DIDL metadata.
     */
    val title: String? get() = senderTitle ?: tagTitle ?: fileName

    val artist: String? get() = senderArtist ?: tagArtist

    /** Artwork bytes carried by the media, for whoever draws the cover. */
    val embeddedArt: ByteArray? get() = embeddedArtBytes

    /**
     * One item's worth of sender metadata, called from `SetAVTransportURI`.
     *
     * Whatever the player derived from the *previous* file is dropped here:
     * this is the one call guaranteed to happen for every new item, so a stale
     * ID3 title cannot answer for a file that never had one.
     */
    @JvmStatic
    fun setSenderMeta(
        title: String?,
        artist: String?,
        albumArtUri: String?,
        fileName: String?
    ) {
        senderTitle = title?.takeIf { it.isNotBlank() }
        senderArtist = artist?.takeIf { it.isNotBlank() }
        this.albumArtUri = albumArtUri?.takeIf { it.isNotBlank() }
        this.fileName = fileName?.takeIf { it.isNotBlank() }
        tagTitle = null
        tagArtist = null
        embeddedArtBytes = null
    }

    /** The container's own tags, reported by the UI once the player knows them. */
    @JvmStatic
    fun setTagMeta(title: String?, artist: String?, artwork: ByteArray?) {
        tagTitle = title?.takeIf { it.isNotBlank() }
        tagArtist = artist?.takeIf { it.isNotBlank() }
        embeddedArtBytes = artwork?.takeIf { it.isNotEmpty() }
    }

    /** Called when an item is loaded/unloaded; unloading also drops audioOnly. */
    @JvmStatic
    fun setActive(active: Boolean) {
        hasMedia = active
        if (!active) {
            audioOnly = false
        }
    }
}
