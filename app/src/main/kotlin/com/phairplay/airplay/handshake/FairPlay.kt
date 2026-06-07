package com.phairplay.airplay.handshake

/**
 * FairPlay — per-connection AirPlay FairPlay handshake (`POST /fp-setup`) and stream-key
 * decryption.
 *
 * The two `fp-setup` exchanges are trivial fixed responses (no crypto), so they live here in
 * Kotlin. The final key decryption (`decrypt`) uses Apple's reverse-engineered FairPlay
 * cipher + 483 KB of lookup tables — far too large/obfuscated to hand-port safely — so it
 * delegates to the proven RPiPlay C compiled into `libplayfair.so` via JNI.
 *
 * Reference: RPiPlay lib/fairplay_playfair.c + lib/playfair/.
 */
class FairPlay {

    /** The 164-byte phase-2 key message; required before [decrypt] can run. */
    private var keyMessage: ByteArray? = null

    /** fp-setup phase 1 (16-byte request) → one of four fixed 142-byte replies (by mode byte). */
    fun setup(req: ByteArray): ByteArray {
        require(req.size == 16) { "fp-setup phase 1 expects 16 bytes, got ${req.size}" }
        require(req[4].toInt() == 0x03) { "unsupported FairPlay version ${req[4].toInt()}" }
        val mode = req[14].toInt() and 0xFF
        require(mode in 0..3) { "invalid fp-setup mode $mode" }
        return REPLIES[mode].copyOf()
    }

    /** fp-setup phase 2 (164-byte request) → fixed 12-byte header + bytes [144,164) = 32 bytes. */
    fun handshake(req: ByteArray): ByteArray {
        require(req.size == 164) { "fp-setup phase 2 expects 164 bytes, got ${req.size}" }
        require(req[4].toInt() == 0x03) { "unsupported FairPlay version ${req[4].toInt()}" }
        keyMessage = req.copyOf()
        return FP_HEADER + req.copyOfRange(144, 164)
    }

    /** Decrypts the 72-byte FairPlay-wrapped key (the SETUP `ekey`) into the 16-byte AES key. */
    fun decrypt(ekey: ByteArray): ByteArray {
        val km = keyMessage ?: error("fp-setup handshake must complete before decrypt")
        require(ekey.size == 72) { "FairPlay ekey must be 72 bytes, got ${ekey.size}" }
        ensureNativeLoaded()
        return nativePlayfairDecrypt(km, ekey)
    }

    private external fun nativePlayfairDecrypt(keyMessage: ByteArray, cipher: ByteArray): ByteArray

    companion object {
        @Volatile private var nativeLoaded = false

        private fun ensureNativeLoaded() {
            if (!nativeLoaded) synchronized(this) {
                if (!nativeLoaded) { System.loadLibrary("playfair"); nativeLoaded = true }
            }
        }

        private fun hex(s: String): ByteArray =
            ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

        private val FP_HEADER = hex("46504c590301040000000014")

        private val REPLIES = arrayOf(
            hex("46504c59030102000000008202000f9f3f9e0a2521dbdf312ab2bfb29e8d232b6376a8c818701d22ae93d82737feaf9db4fdf41c2dba9d1f49caaabf6591ac1f7bc6f7e0663d21afe01565953eab81f418ceed095adb7c3d0e254909a79831d49c3982973434facb42c63a1cd911a6fe941a8a6d4a743b46c3a7649e44c78955e49d8155009549c4e2f7a3f6d5ba"),
            hex("46504c5903010200000000820201cf32a25714b2524f8aa0ad7af164e37bcf4424e200047efc0ad67afcd95ded1c2730bb591b962ed63a9c4ded88ba8fc78de64d91ccfd5c7b56da88e31f5cceafc7431995a01665a54e1939d25b94db64b9e45d8d063e1e6af07e9656162b0efa404275ea5a44d9591c7256b9fbe6513898b80227721988571650942ad946688a"),
            hex("46504c5903010200000000820202c169a352eeed35b18cdd9c58d64f16c1519a89eb5317bd0d4336cd68f638ff9d016a5b52b7fa9216b2b65482c78444118121a2c7fed83db7119e9182aad7d18c7063e2a457555910af9e0efc76347d164043807f581ee4fbe42ca9dedc1b5eb2a3aa3d2ecd59e7eee70b3629f22afd161d877353ddb99adc8e07006e56f850ce"),
            hex("46504c59030102000000008202039001e1727e0f57f9f5880db104a6257a23f5cfff1abbe1e93045251afb97eb9fc0011ebe0f3a81df5b691d76acb2f7a5c708e3d328f56bb39dbde5f29c8a17f481487e3ae863c678325422e6f78e166d18aa7fd636258bce28726f661f738893ce44311e4be6c0535193e5ef72e8686233729c227d820c999445d89246c8c359")
        )
    }
}
