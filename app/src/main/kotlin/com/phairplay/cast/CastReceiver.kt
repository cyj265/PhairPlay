package com.phairplay.cast

import android.content.Context
import android.view.Surface
import com.phairplay.service.ProtocolState
import com.phairplay.util.Logger
import com.phairplay.util.NetworkUtils

/**
 * Google Cast V2 receiver (Default Media Receiver).
 *
 * WHAT WORKS: LAN senders that speak Cast V2 over TLS — they discover the
 * `_googlecast._tcp` service, authenticate the (self-signed) identity, launch
 * CC1AD845 and fling an http(s) media URL. VLC is the reference client.
 *
 * WHAT DOES NOT: stock Google senders (Chrome's cast menu, YouTube, Google
 * Home) verify the receiver against Google's Cast CA and will not accept the
 * self-signed certificate, so the device may not even appear there.
 *
 * The Google TV flavor overrides this class with the official Cast Connect
 * implementation (src/googletv/kotlin/.../CastReceiver.kt).
 */
class CastReceiver(
    context: Context,
    displayName: String,
    private val videoSurfaceProvider: () -> Surface? = { null },
    private val onStateChanged: (ProtocolState) -> Unit
) {
    private val name = displayName.ifBlank { "PhairPlay" }
    private val deviceId = NetworkUtils.getPersistentUuid(context)

    private lateinit var player: CastPlayer

    private val session = CastSession(
        load = { request -> player.load(request) },
        command = { command -> player.command(command) }
    )

    private val server = GoogleCastReceiver(context, name, deviceId, session)

    init {
        player = CastPlayer(
            context = context,
            surfaceProvider = videoSurfaceProvider,
            onState = { state -> session.update(state) },
            onStreamingChanged = { streaming ->
                onStateChanged(if (streaming) ProtocolState.CONNECTED else ProtocolState.ADVERTISING)
            }
        )
    }

    fun start() {
        onStateChanged(ProtocolState.ADVERTISING)
        server.start()
        Logger.i("Cast receiver started as '$name'")
    }

    fun stop() {
        try {
            player.release()
            server.stop()
        } finally {
            onStateChanged(ProtocolState.DISABLED)
            Logger.i("Cast receiver stopped")
        }
    }
}
