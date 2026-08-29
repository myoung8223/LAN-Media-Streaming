package com.lanmedia.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * Starts the receiver service after the device boots, if the user enabled
 * "Start automatically after reboot". Note: some Android versions and OEM
 * skins restrict starting a foreground service (or any app) from boot — the
 * device may also need this app allowed in its "auto-start" list.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != "android.intent.action.LOCKED_BOOT_COMPLETED"
        ) return

        val prefs = context.getSharedPreferences("lanmedia", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("bootstart", true)) return

        val port = prefs.getInt("port", Protocol.DEFAULT_PORT)
        val password = prefs.getString("password", "") ?: ""
        val tls = prefs.getBoolean("tls", true)
        val name = prefs.getString("name", "") ?: ""
        val svc = Intent(context, ReceiverService::class.java).apply {
            putExtra(ReceiverService.EXTRA_PORT, port)
            putExtra(ReceiverService.EXTRA_PASSWORD, password)
            putExtra(ReceiverService.EXTRA_TLS, tls)
            putExtra(ReceiverService.EXTRA_NAME, name)
        }
        try {
            ContextCompat.startForegroundService(context, svc)
        } catch (_: Exception) {
            // OS blocked a background/boot foreground-service start; user can open the app.
        }
    }
}
