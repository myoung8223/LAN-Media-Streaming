package com.lanmedia.receiver

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.lanmedia.receiver.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val prefs by lazy { getSharedPreferences("lanmedia", Context.MODE_PRIVATE) }

    private val notifPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { startService() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.tvIp.text = NetUtils.localIpv4()
        b.etPort.setText(prefs.getInt("port", Protocol.DEFAULT_PORT).toString())
        b.etPassword.setText(prefs.getString("password", ""))

        // Panel name: generate a distinct default once, then let the user rename.
        var savedName = prefs.getString("name", "") ?: ""
        if (savedName.isEmpty()) {
            savedName = "Rcvr-" + (100 + java.util.Random().nextInt(900))
            prefs.edit().putString("name", savedName).apply()
        }
        b.etName.setText(savedName)
        b.cbEncrypt.isChecked = prefs.getBoolean("tls", true)
        b.cbAutostart.isChecked = prefs.getBoolean("autostart", true)
        b.cbBootstart.isChecked = prefs.getBoolean("bootstart", true)
        updateFingerprintVisibility()
        loadFingerprintAsync()

        b.btnRefreshIp.setOnClickListener { b.tvIp.text = NetUtils.localIpv4() }
        b.btnStartStop.setOnClickListener {
            if (ReceiverService.isRunning) stopService() else requestNotifThenStart()
        }
        b.btnMinimize.setOnClickListener { moveTaskToBack(true) }
        b.btnBattery.setOnClickListener { openBatterySettings() }
        b.btnOverlay.setOnClickListener { openOverlaySettings() }
        b.btnAbout.setOnClickListener { showAbout() }
        updateOverlayButton()

        b.cbEncrypt.setOnCheckedChangeListener { _, v ->
            prefs.edit().putBoolean("tls", v).apply()
            updateFingerprintVisibility()
        }
        b.cbAutostart.setOnCheckedChangeListener { _, v ->
            prefs.edit().putBoolean("autostart", v).apply()
        }
        b.cbBootstart.setOnCheckedChangeListener { _, v ->
            prefs.edit().putBoolean("bootstart", v).apply()
        }

        b.tvStatus.text = ReceiverService.lastStatus
        refreshUi()

        // Auto-listen on launch (if enabled and not already running).
        if (b.cbAutostart.isChecked && !ReceiverService.isRunning) {
            requestNotifThenStart()
        }
    }

    override fun onResume() {
        super.onResume()
        ReceiverService.statusListener = { status ->
            runOnUiThread { b.tvStatus.text = status; refreshUi() }
        }
        b.tvStatus.text = ReceiverService.lastStatus
        updateOverlayButton()
        refreshUi()
    }

    override fun onPause() {
        super.onPause()
        ReceiverService.statusListener = null
    }

    /** Update button text/color and lock the config fields while listening. */
    private fun refreshUi() {
        val running = ReceiverService.isRunning
        b.btnStartStop.text = if (running) "Stop listening" else "Start listening"
        b.btnStartStop.backgroundTintList = ColorStateList.valueOf(
            Color.parseColor(if (running) "#FF5C6C" else "#2ECC9B")
        )
        setFieldsEnabled(!running)
    }

    private fun setFieldsEnabled(enabled: Boolean) {
        val a = if (enabled) 1f else 0.5f
        for (v in listOf(b.etName, b.etPort, b.etPassword)) {
            v.isEnabled = enabled
            v.alpha = a
        }
        b.cbEncrypt.isEnabled = enabled
    }

    private fun updateFingerprintVisibility() {
        val show = b.cbEncrypt.isChecked
        b.lblFingerprint.visibility = if (show) android.view.View.VISIBLE else android.view.View.GONE
        b.tvFingerprint.visibility = if (show) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun loadFingerprintAsync() {
        Thread {
            val fp = try { TlsUtil.fingerprintSha256(applicationContext) } catch (e: Exception) { "unavailable" }
            runOnUiThread { b.tvFingerprint.text = fp }
        }.start()
    }

    private fun requestNotifThenStart() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startService()
        }
    }

    private fun currentPort(): Int =
        b.etPort.text.toString().trim().toIntOrNull()?.coerceIn(1024, 65535) ?: Protocol.DEFAULT_PORT

    private fun startService() {
        val port = currentPort()
        val password = b.etPassword.text.toString()
        val tls = b.cbEncrypt.isChecked
        val name = b.etName.text.toString().trim()
        prefs.edit().putInt("port", port).putString("password", password)
            .putBoolean("tls", tls).putString("name", name).apply()

        val svc = Intent(this, ReceiverService::class.java).apply {
            putExtra(ReceiverService.EXTRA_PORT, port)
            putExtra(ReceiverService.EXTRA_PASSWORD, password)
            putExtra(ReceiverService.EXTRA_TLS, tls)
            putExtra(ReceiverService.EXTRA_NAME, name)
        }
        ContextCompat.startForegroundService(this, svc)
        b.tvStatus.text = "Starting…"
        b.btnStartStop.postDelayed({ refreshUi() }, 300)
    }

    private fun stopService() {
        startService(Intent(this, ReceiverService::class.java).setAction(ReceiverService.ACTION_STOP))
        b.btnStartStop.postDelayed({ refreshUi(); b.tvStatus.text = ReceiverService.lastStatus }, 300)
    }

    /** Themed About dialog: version, credits, license, and repo link. */
    private fun showAbout() {
        val view = layoutInflater.inflate(R.layout.dialog_about, null)
        view.findViewById<TextView>(R.id.tvAboutVersion).text =
            "Version " + BuildConfig.VERSION_NAME
        val dialog = AlertDialog.Builder(this).setView(view).create()
        // Let the rounded panel background show instead of the default dialog frame.
        dialog.window?.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
        )
        view.findViewById<Button>(R.id.btnAboutClose).setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    /** Reflect whether "display over other apps" is granted (needed for auto pop-up). */
    private fun updateOverlayButton() {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
        b.btnOverlay.text =
            if (granted) "Appear on top: enabled ✓"
            else "Open appear on top settings"
    }

    private fun openOverlaySettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                    .setData(Uri.parse("package:$packageName"))
            )
        } catch (_: Exception) {
            openBatterySettings()   // fall back to the app's settings page
        }
    }

    private fun openBatterySettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:$packageName"))
            )
        } catch (_: Exception) {
        }
    }
}
