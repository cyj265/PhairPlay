package com.phairplay.dlna

/**
 * What the sender told us about the item currently loaded on the renderer.
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

    @Volatile
    @JvmField
    var title: String? = null

    @Volatile
    @JvmField
    var artist: String? = null

    @Volatile
    @JvmField
    var albumArtUri: String? = null

    @JvmStatic
    fun setMeta(title: String?, artist: String?, albumArtUri: String?) {
        this.title = title?.takeIf { it.isNotBlank() }
        this.artist = artist?.takeIf { it.isNotBlank() }
        this.albumArtUri = albumArtUri?.takeIf { it.isNotBlank() }
    }

    /** Called when an item is loaded/unloaded; unloading also drops audioOnly. */
    @JvmStatic
    fun setActive(active: Boolean) {
        hasMedia = active
        if (!active) {
            audioOnly = false
        }
    }

    @JvmStatic
    fun clearMeta() {
        title = null
        artist = null
        albumArtUri = null
    }
}
