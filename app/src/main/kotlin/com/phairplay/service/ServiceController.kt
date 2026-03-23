package com.phairplay.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.phairplay.util.Logger

/**
 * ServiceController — Provides a clean API to start, stop, and restart the PhairPlayService.
 *
 * WHY: Sending Intent actions to a service is verbose and error-prone if done directly
 * from the UI. This class centralizes all service control commands so the UI just calls
 * `ServiceController.start(context)` — no Intent construction in Fragment code.
 *
 * HOW: All methods are on the companion object (static-like). No instance needed.
 * Call from any Fragment or ViewModel that has a Context.
 *
 * Example:
 *   // From a Fragment or Activity:
 *   ServiceController.start(requireContext())
 *   ServiceController.stop(requireContext())
 *   ServiceController.restart(requireContext())
 */
object ServiceController {

    /**
     * Starts the PhairPlayService if it is not already running.
     *
     * Uses [ContextCompat.startForegroundService] which works correctly on all
     * Android versions (API 26+ requires the foreground service to be started
     * this way to avoid background start restrictions).
     *
     * @param context Any valid Android context.
     */
    fun start(context: Context) {
        Logger.i("ServiceController: start()")
        val intent = buildIntent(context, PhairPlayService.ACTION_START)
        ContextCompat.startForegroundService(context, intent)
    }

    /**
     * Stops the PhairPlayService.
     *
     * All receivers are stopped, all network ports are released, and the
     * persistent notification is removed.
     *
     * @param context Any valid Android context.
     */
    fun stop(context: Context) {
        Logger.i("ServiceController: stop()")
        val intent = buildIntent(context, PhairPlayService.ACTION_STOP)
        context.startService(intent)
    }

    /**
     * Restarts the PhairPlayService.
     *
     * Sends a restart command that stops all receivers and starts them again
     * with the latest settings. Useful after changing Settings or recovering
     * from an error state.
     *
     * The service itself keeps running during restart (no stopSelf() is called).
     * The visible interruption is brief (<500ms for port release + re-advertise).
     *
     * @param context Any valid Android context.
     */
    fun restart(context: Context) {
        Logger.i("ServiceController: restart()")
        val intent = buildIntent(context, PhairPlayService.ACTION_RESTART)
        ContextCompat.startForegroundService(context, intent)
    }

    /**
     * Constructs a service control [Intent] with the given action.
     *
     * @param context The calling context.
     * @param action  One of [PhairPlayService.ACTION_START], ACTION_STOP, ACTION_RESTART.
     */
    private fun buildIntent(context: Context, action: String): Intent =
        Intent(context, PhairPlayService::class.java).apply { this.action = action }
}
