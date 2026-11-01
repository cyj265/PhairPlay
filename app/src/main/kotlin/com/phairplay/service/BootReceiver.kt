package com.phairplay.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.phairplay.settings.SettingsRepository
import com.phairplay.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * BootReceiver — starts [PhairPlayService] whenever the box comes back to life.
 *
 * WHY: a receiver app is only useful while its service is advertising. Android
 * stops the service in three situations a TV box actually hits:
 *
 *   1. Cold boot → `BOOT_COMPLETED` (~30–60 s after boot).
 *   2. Cover-install / APK upgrade → the process is killed and
 *      `BOOT_COMPLETED` is NOT re-sent; `MY_PACKAGE_REPLACED` is. Without this
 *      the box silently disappears from the cast menu after every
 *      `adb install -r`, which looks exactly like "the box is offline".
 *   3. Wake-up from standby / screen on → `USER_PRESENT` / `SCREEN_ON`. Some
 *      box ROMs implement the remote's power button as suspend, so no
 *      `BOOT_COMPLETED` is ever sent on "power on".
 *
 * All four actions funnel into [startIfEnabled], which reads the
 * [com.phairplay.settings.AppSettings.startOnBoot] flag and calls
 * [ServiceController.start] only when enabled. Duplicate starts are harmless:
 * PhairPlayService guards `startReceivers()` with an idempotency flag, so a
 * wake-up arriving while the service is already running is a no-op instead of
 * a second bind of the SSDP/mDNS sockets.
 *
 * Declared in AndroidManifest.xml with `exported="false"` — these are system
 * broadcasts; no third-party app can trigger this receiver.
 *
 * NOTE: on Android 8+ the implicit-broadcast restrictions block statically
 * registered `USER_PRESENT`/`SCREEN_ON`; `BOOT_COMPLETED` and
 * `MY_PACKAGE_REPLACED` remain exempt. The firetv flavour targets Android 7.1
 * (API 25), where all four are delivered; the googletv flavour still gets the
 * two important ones.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_USER_PRESENT,
            Intent.ACTION_SCREEN_ON -> startIfEnabled(context, action)
            else -> return
        }
    }

    private fun startIfEnabled(context: Context, action: String) {
        Timber.d("BootReceiver: %s received", action)
        // Release builds plant no Timber tree, so Timber never reaches the
        // on-device file. DebugLog is the only sink visible over :8099/log —
        // which is exactly how "did the box start itself after the update?"
        // gets answered in the field.
        DebugLog.log("BOOT", "收到 $action")

        // goAsync() extends the execution window beyond the 10-second
        // BroadcastReceiver limit while DataStore is read off the main thread.
        val pendingResult: PendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = SettingsRepository(context).settingsFlow.first()
                if (settings.startOnBoot) {
                    Timber.i("BootReceiver: %s, startOnBoot=true → starting PhairPlayService", action)
                    DebugLog.log("BOOT", "startOnBoot=true → 启动服务（$action）")
                    ServiceController.start(context)
                } else {
                    Timber.d("BootReceiver: %s, startOnBoot=false → not starting service", action)
                    DebugLog.log("BOOT", "startOnBoot=false → 不启动（$action）")
                }
            } catch (e: Exception) {
                Timber.e(e, "BootReceiver: failed to read settings or start service (%s)", action)
                DebugLog.log("BOOT", "读取设置/启动失败（$action）：${e.message}")
            } finally {
                pendingResult.finish()
            }
        }
    }
}
