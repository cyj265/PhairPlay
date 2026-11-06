package com.phairplay.sender

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Surface
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * MirrorSenderService — captures the phone screen, encodes it as H.264 and
 * streams it to the TV over PPM1.
 *
 * Capture must live in a foreground service (Android 10+ enforces this, and
 * Android 14 additionally requires the mediaProjection service type), so the
 * Activity only obtains the user's consent and hands the result over.
 */
class MirrorSenderService : Service() {

    private var worker: Thread? = null
    @Volatile private var running = false
    @Volatile private var socket: Socket? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = intent?.let { readProjectionData(it) }
        val host = intent?.getStringExtra(EXTRA_HOST)
        val port = intent?.getIntExtra(EXTRA_PORT, 0) ?: 0
        val name = intent?.getStringExtra(EXTRA_NAME).orEmpty()
        if (data == null || host.isNullOrBlank() || port <= 0) {
            Log.e(TAG, "missing start parameters — stopping")
            stopSelf()
            return START_NOT_STICKY
        }
        startSession(resultCode, data, host, port, name)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        worker?.join(1500)
        worker = null
        super.onDestroy()
    }

    private fun startInForeground() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "投屏", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("PhairPlay 投屏")
            .setContentText("正在把手机屏幕发送到电视")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startSession(resultCode: Int, data: Intent, host: String, port: Int, name: String) {
        if (running) {
            Log.i(TAG, "session already running — ignoring duplicate start")
            return
        }
        running = true
        worker = Thread({ runSession(resultCode, data, host, port, name) }, "mirror-sender").also {
            it.start()
        }
    }

    private fun runSession(resultCode: Int, data: Intent, host: String, port: Int, name: String) {
        var projection: MediaProjection? = null
        var virtualDisplay: VirtualDisplay? = null
        var codec: MediaCodec? = null
        var inputSurface: Surface? = null
        try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, data)
                ?: throw IllegalStateException("MediaProjection denied")
            // Android 14+ requires a callback to be registered before createVirtualDisplay.
            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.i(TAG, "projection stopped by the system")
                    running = false
                    try { socket?.close() } catch (_: Exception) {}
                }
            }, null)

            val metrics = resources.displayMetrics
            val width = (metrics.widthPixels / 2 * 2).coerceAtLeast(320)
            val height = (metrics.heightPixels / 2 * 2).coerceAtLeast(240)
            val dpi = metrics.densityDpi

            val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SECONDS)
            }
            codec = MediaCodec.createEncoderByType(MIME_TYPE)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = codec.createInputSurface()
            codec.start()

            virtualDisplay = projection.createVirtualDisplay(
                "phairplay-mirror",
                width,
                height,
                dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                inputSurface,
                null,
                null
            )

            val client = Socket()
            socket = client
            client.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            client.soTimeout = CONNECT_TIMEOUT_MS
            val output = client.getOutputStream()
            output.write(SenderProtocol.MAGIC.toByteArray(Charsets.US_ASCII))
            output.write(SenderProtocol.encodeName(name))
            output.flush()
            val hello = SenderProtocol.readLine(client.getInputStream())
            if (hello == null || !hello.startsWith("PPM1")) {
                throw IllegalStateException("handshake rejected: $hello")
            }
            client.soTimeout = 0
            Log.i(TAG, "streaming ${width}x$height to $host:$port (receiver: $hello)")

            stream(codec, output, width, height, client)
        } catch (e: Exception) {
            Log.e(TAG, "mirror session failed", e)
        } finally {
            running = false
            try { virtualDisplay?.release() } catch (_: Exception) {}
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { inputSurface?.release() } catch (_: Exception) {}
            try { projection?.stop() } catch (_: Exception) {}
            try { socket?.close() } catch (_: Exception) {}
            socket = null
            Log.i(TAG, "mirror session ended")
            stopSelf()
        }
    }

    private fun stream(
        codec: MediaCodec,
        output: OutputStream,
        width: Int,
        height: Int,
        client: Socket,
    ) {
        val info = MediaCodec.BufferInfo()
        var configSent = false
        while (running && !client.isClosed) {
            when (val index = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    configSent = sendConfig(codec, output, width, height) || configSent
                }
                MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                else -> if (index >= 0) {
                    if (!configSent) configSent = sendConfig(codec, output, width, height)
                    val buffer = codec.getOutputBuffer(index)
                    if (buffer != null && info.size > 0 &&
                        (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                    ) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val avcc = ByteArray(info.size)
                        buffer.get(avcc)
                        val annexB = SenderProtocol.toAnnexB(avcc)
                        if (annexB.isNotEmpty()) {
                            output.write(
                                SenderProtocol.encodeHeader(
                                    SenderProtocol.TYPE_VIDEO,
                                    annexB.size,
                                    info.presentationTimeUs
                                )
                            )
                            output.write(annexB)
                            output.flush()
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                }
            }
        }
    }

    /** Sends SPS/PPS from the encoder's output format and asks for an IDR to start on. */
    private fun sendConfig(
        codec: MediaCodec,
        output: OutputStream,
        width: Int,
        height: Int,
    ): Boolean {
        return try {
            val format = codec.outputFormat
            val sps = format.getByteBuffer("csd-0") ?: return false
            val pps = format.getByteBuffer("csd-1") ?: return false
            val spsBytes = SenderProtocol.stripStartCode(ByteArray(sps.remaining()).also { sps.get(it) })
            val ppsBytes = SenderProtocol.stripStartCode(ByteArray(pps.remaining()).also { pps.get(it) })
            val payload = SenderProtocol.encodeConfig(width, height, 0, spsBytes, ppsBytes)
            output.write(SenderProtocol.encodeHeader(SenderProtocol.TYPE_CONFIG, payload.size, 0))
            output.write(payload)
            output.flush()
            codec.setParameters(
                android.os.Bundle().apply {
                    putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                }
            )
            Log.i(TAG, "config sent (sps=${spsBytes.size}B pps=${ppsBytes.size}B)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "unable to send codec config", e)
            false
        }
    }

    private fun readProjectionData(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_PROJECTION_DATA)
        }

    companion object {
        private const val TAG = "MirrorSender"
        private const val CHANNEL_ID = "mirror_sender"
        private const val NOTIFICATION_ID = 42
        private const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val BIT_RATE = 8_000_000
        private const val FRAME_RATE = 30
        private const val I_FRAME_INTERVAL_SECONDS = 1
        private const val DEQUEUE_TIMEOUT_US = 10_000L
        private const val CONNECT_TIMEOUT_MS = 5_000

        const val ACTION_START = "com.phairplay.sender.START"
        const val ACTION_STOP = "com.phairplay.sender.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_PROJECTION_DATA = "projection_data"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_NAME = "name"
    }
}
