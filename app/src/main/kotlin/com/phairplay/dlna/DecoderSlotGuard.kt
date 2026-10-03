package com.phairplay.dlna

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import com.phairplay.util.DebugLog
import java.util.concurrent.TimeUnit

/**
 * Everything this app can know or do about the box's hardware decoder slot.
 *
 * WHY THIS EXISTS: on the N1 (Amlogic S905D) there is exactly one HEVC
 * hardware component, `OMX.amlogic.hevc.decoder.awesome`, and its instance
 * count is shared by every app on the box. When another player holds it,
 * `MediaCodec.configure()` fails with nothing useful in the message, the
 * picture stays black, and the user has no way to tell "my box is broken"
 * from "当贝投屏 is still holding the slot".
 *
 * WHAT IS AND IS NOT POSSIBLE (settled after checking, do not re-litigate):
 *
 * - **Nobody can ask the system who owns a codec.** There is no public API,
 *   and `dumpsys media.codec` needs `android.permission.DUMP` (signature
 *   level, or an adb shell uid) — a third-party app gets a permission denial.
 *   So the owner is *inferred*, never known.
 * - **`am force-stop` needs a system signature** (`FORCE_STOP_PACKAGES`).
 *   Reflecting into `ActivityManager.forceStopPackage` throws
 *   SecurityException. Not available to us.
 * - **`killBackgroundProcesses()` is a normal permission** but only reaches
 *   background processes; a foreground player is restarted immediately.
 *   Useful, not decisive.
 * - **The one thing a plain app can always do** is hand the user the system's
 *   own "Force stop" button: `ACTION_APPLICATION_DETAILS_SETTINGS`. That is
 *   the no-root main path.
 * - **With root** (most N1 firmwares have it) `am force-stop` works, and the
 *   real sledgehammer is `setprop ctl.restart media`, which on Android 7.1
 *   restarts `mediaserver` and releases every slot at once. That is also the
 *   only answer for the case no app can be blamed: a leaked slot left behind
 *   by a crash has no owner to stop, and only a service restart clears it.
 *
 * Every method here blocks on process I/O — call them off the main thread.
 */
object DecoderSlotGuard {

    /** Result of trying to take the HEVC slot for a moment and give it back. */
    sealed class ProbeResult {
        /** The slot answered: a fresh instance was created, started and released. */
        object Free : ProbeResult()

        /** An instance could not be created or started — for this box that means held. */
        class Occupied(val detail: String) : ProbeResult()

        /** Probing is not viable here (no component, or no-surface unsupported). */
        class Unsupported(val detail: String) : ProbeResult()
    }

    /** A running app that plausibly holds the slot. */
    data class Suspect(
        val packageName: String,
        val label: String,
        /** True while the process is foreground/visible — the likelier holder. */
        val foreground: Boolean
    )

    /**
     * Set once probing turns out to be impossible on this box, so a box where
     * `configure(null)` is rejected does not get probed before every retry.
     */
    @Volatile
    private var probingUnsupported = false

    @Volatile
    private var rootAvailable: Boolean? = null

    /**
     * Takes the HEVC slot for a few milliseconds and hands it straight back.
     *
     * WHY PROBE AT ALL: a failed probe *before* starting playback turns
     * "the picture is black and nobody knows why" into a sentence the user
     * can act on. It also separates the two failure modes: no instance at all
     * (component missing) versus configure/start refusing (slot held).
     *
     * A decoder runs fine without a surface — `configure(format, null, null, 0)`
     * is legal for a decoder and simply produces no rendered output — so this
     * needs no PlayerView and can run long before the UI is up.
     *
     * Cheap enough to do before a retry, expensive enough not to do before
     * every playback: callers only probe after a real failure.
     */
    fun probeHevcSlot(codecName: String, width: Int = 1920, height: Int = 1080): ProbeResult {
        if (probingUnsupported) {
            return ProbeResult.Unsupported("探活已在本次启动中判定为不可用")
        }
        if (codecName.isBlank()) {
            return ProbeResult.Unsupported("未选定 HEVC 组件，跳过探活")
        }
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, width, height)
        val codec = try {
            MediaCodec.createByCodecName(codecName)
        } catch (e: Exception) {
            // Not "occupied": the component the box advertises is not there.
            return ProbeResult.Unsupported("组件不存在: ${e.javaClass.simpleName}: ${e.message}")
        }
        return try {
            codec.configure(format, null, null, 0)
            codec.start()
            // start() is what really takes the instance. Getting here means the
            // box had a slot to give.
            ProbeResult.Free
        } catch (e: Exception) {
            val detail = "${e.javaClass.simpleName}: ${e.message}"
            if (e.message?.contains("surface", ignoreCase = true) == true ||
                e is UnsupportedOperationException
            ) {
                // The box refuses a surface-less decoder. Probing would report
                // "occupied" forever and mislead every retry after it.
                probingUnsupported = true
                ProbeResult.Unsupported("本盒不支持无 surface 解码器: $detail")
            } else {
                ProbeResult.Occupied(detail)
            }
        } finally {
            // Whatever happened, the instance must go back. A leaked probe
            // codec is indistinguishable from the leak we are hunting.
            try {
                codec.stop()
            } catch (_: Exception) {
            }
            try {
                codec.release()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Running apps that could plausibly be holding the decoder.
     *
     * Two sources, widest first:
     * 1. every app that declares a TV launcher entry (`CATEGORY_LEANBACK_LAUNCHER`)
     *    — that is the actual set of "apps you run on this box";
     * 2. package names carrying a player-ish substring, which catches IPTV
     *    clients that never declare a leanback launcher.
     *
     * The intersection with the running-process list is what narrows it down.
     * `getRunningAppProcesses()` still returns the full list on API 25 (what
     * Lollipop restricted was `getRunningTasks()`), and it carries
     * `importance`, which is how a foreground player is told from a cached
     * one. If a future box ever restricts it, the list simply comes back with
     * our own process only and the caller degrades to a plain hint.
     */
    fun findSuspects(context: Context): List<Suspect> {
        val pm = context.packageManager
        val self = context.packageName
        val mediaPackages = leanbackPackages(pm) + namedHits(pm)
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val running = runCatching { am?.runningAppProcesses }.getOrNull().orEmpty()
        if (running.isEmpty()) {
            DebugLog.log("DECODER", "进程枚举为空（本盒可能已限制 getRunningAppProcesses）")
            return emptyList()
        }
        val out = ArrayList<Suspect>(4)
        for (proc in running) {
            // IMPORTANCE_SERVICE (300) still covers a player decoding in the
            // background without a visible Activity — exactly the leak case.
            // Anything colder (cached/empty) has no codec to hold.
            if (proc.importance > ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE) continue
            val foreground = proc.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
            for (pkg in proc.pkgList) {
                if (pkg == self || pkg !in mediaPackages) continue
                val label = runCatching {
                    pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString()
                }.getOrDefault(pkg)
                if (out.any { it.packageName == pkg }) continue
                out.add(Suspect(pkg, label, foreground))
            }
        }
        // Foreground processes first: they are the likelier holder and the
        // only ones the user can recognise on screen.
        return out.sortedWith(compareBy({ !it.foreground }, { it.label }))
    }

    private fun leanbackPackages(pm: PackageManager): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        return runCatching {
            pm.queryIntentActivities(intent, 0).mapNotNull { it.activityInfo?.packageName }.toSet()
        }.getOrDefault(emptySet())
    }

    /**
     * Belt-and-braces name matching. Boxes are full of IPTV clients that
     * launch from a shortcut and declare no leanback category at all.
     */
    private fun namedHits(pm: PackageManager): Set<String> {
        val needles = listOf(
            "iptv", "tvbox", "fongmi", "dangbei", "player", "video",
            "media", "cast", "kodi", "vlc", "mxplayer", "bili", "qqlive", "youku"
        )
        val installed = runCatching {
            pm.getInstalledPackages(PackageManager.GET_ACTIVITIES)
        }.getOrNull().orEmpty()
        return installed.mapNotNull { it?.packageName }
            .filter { pkg -> needles.any { pkg.contains(it, ignoreCase = true) } }
            .toSet()
    }

    /**
     * Asks the system to drop a background process.
     *
     * Normal permission, no prompt. Effectively a nudge: it cannot touch a
     * foreground app, and anything with a service comes straight back — but a
     * player that leaked its codec while backgrounded is exactly the case this
     * does clear.
     */
    fun killBackground(context: Context, packageName: String): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        return runCatching {
            am.killBackgroundProcesses(packageName)
            true
        }.onFailure {
            DebugLog.log("DECODER", "清理后台进程失败 $packageName: ${it.message}")
        }.getOrDefault(false)
    }

    /** Cached so a box without su is not probed on every failure. */
    fun hasRoot(): Boolean {
        rootAvailable?.let { return it }
        val ok = runCatching {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val done = p.waitFor(3, TimeUnit.SECONDS)
            done && p.exitValue() == 0
        }.getOrDefault(false)
        rootAvailable = ok
        DebugLog.log("DECODER", "root 可用性检测: ${if (ok) "可用" else "不可用"}")
        return ok
    }

    /**
     * Stops an app outright — root only.
     *
     * Returns false when there is no root, so the caller can fall back to
     * handing the user the system's own force-stop screen.
     */
    fun forceStopWithRoot(packageName: String): Boolean {
        if (!hasRoot()) return false
        val ok = runSu("am force-stop $packageName")
        DebugLog.log("DECODER", "root 强停 $packageName: ${if (ok) "成功" else "失败"}")
        return ok
    }

    /**
     * Restarts the media service, releasing every hardware codec slot at once.
     *
     * THE CASE ONLY THIS COVERS: a slot can be leaked with no app left to
     * blame — a crashed player, or a HAL that never unwound after a failed
     * configure(). Nothing is running, nothing can be stopped, and the box
     * needs a reboot. `ctl.restart media` is that reboot, minus the reboot.
     *
     * Costs: it interrupts whatever else is decoding (including us), so the
     * caller must make sure PhairPlay itself is not playing.
     *
     * `media` is the Android 7.1 service name (`mediaserver`); Oreo split it
     * into `media.codec` etc., so both are attempted.
     */
    fun restartMediaService(): Boolean {
        if (!hasRoot()) return false
        var ok = runSu("setprop ctl.restart media")
        if (!ok) ok = runSu("setprop ctl.restart media.codec")
        DebugLog.log("DECODER", "root 重启媒体服务: ${if (ok) "已触发" else "失败"}")
        return ok
    }

    private fun runSu(command: String): Boolean = runCatching {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
        val done = p.waitFor(5, TimeUnit.SECONDS)
        if (!done) {
            p.destroy()
            false
        } else {
            p.exitValue() == 0
        }
    }.onFailure {
        DebugLog.log("DECODER", "su 执行失败 [$command]: ${it.message}")
    }.getOrDefault(false)

    /** Kept for the debug page: what this box actually reports. */
    fun describe(): String = "sdk=${Build.VERSION.SDK_INT} root=${rootAvailable ?: "未检测"}"
}
