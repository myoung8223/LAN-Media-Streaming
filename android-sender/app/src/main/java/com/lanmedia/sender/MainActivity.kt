package com.lanmedia.sender

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.lanmedia.sender.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val prefs by lazy { getSharedPreferences("lanmediasender", Context.MODE_PRIVATE) }

    private val projLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val data = res.data
            if (res.resultCode == RESULT_OK && data != null) startSender(res.resultCode, data)
            else b.tvStatus.text = "Screen capture canceled"
        }

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { launchProjection() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        // Load saved settings.
        b.etName.setText(prefs.getString("name", ""))
        b.etHost.setText(prefs.getString("host", ""))
        b.etPort.setText(prefs.getInt("port", Protocol.DEFAULT_PORT).toString())
        b.etPassword.setText(prefs.getString("password", ""))
        b.cbEncrypt.isChecked = prefs.getBoolean("tls", true)
        b.etWidth.setText(prefs.getInt("width", 1920).toString())
        b.etHeight.setText(prefs.getInt("height", 1080).toString())
        b.etBitrate.setText(prefs.getInt("bitrate", 10).toString())
        b.etFps.setText(prefs.getInt("fps", 30).toString())
        b.cbAudio.isChecked = prefs.getBoolean("audio", true)

        b.btnStartStop.setOnClickListener {
            if (SenderService.isRunning) stopSender() else beginStart()
        }
        b.btnAbout.setOnClickListener { showAbout() }
        b.btnClearPin.setOnClickListener {
            prefs.edit().remove("pinnedFp").apply()
            refreshFingerprint()
        }

        refreshFingerprint()
    }

    override fun onResume() {
        super.onResume()
        SenderService.statusListener = { s -> runOnUiThread { b.tvStatus.text = s; refreshUi() } }
        SenderService.onPinnedChanged = { runOnUiThread { refreshFingerprint() } }
        b.tvStatus.text = SenderService.lastStatus
        refreshUi()
        refreshFingerprint()
    }

    override fun onPause() {
        super.onPause()
        SenderService.statusListener = null
        SenderService.onPinnedChanged = null
        saveSettings()
    }

    private fun refreshUi() {
        val running = SenderService.isRunning
        b.btnStartStop.text = if (running) "Stop streaming" else "Start streaming"
        b.btnStartStop.backgroundTintList = ColorStateList.valueOf(
            Color.parseColor(if (running) "#FF5C6C" else "#2ECC9B"))
        val enabled = !running
        for (v in listOf(b.etName, b.etHost, b.etPort, b.etPassword,
                b.etWidth, b.etHeight, b.etBitrate, b.etFps)) {
            v.isEnabled = enabled; v.alpha = if (enabled) 1f else 0.5f
        }
        b.cbEncrypt.isEnabled = enabled
        b.cbAudio.isEnabled = enabled
    }

    private fun refreshFingerprint() {
        val fp = prefs.getString("pinnedFp", "") ?: ""
        b.tvFingerprint.text = if (fp.isEmpty())
            "None pinned yet — trusted on first connect." else fp
    }

    // ---- validated field readers (clamp + reflect back) ----
    private fun clampField(view: TextView, min: Int, max: Int, fallback: Int, even: Boolean = false): Int {
        var v = view.text.toString().trim().toIntOrNull() ?: fallback
        v = v.coerceIn(min, max)
        if (even) v = v and 1.inv()
        view.text = v.toString()
        return v
    }

    private fun beginStart() {
        // Validate/clamp the video fields and persist before requesting permissions.
        clampField(b.etWidth, 320, 3840, 1920, even = true)
        clampField(b.etHeight, 240, 2160, 1080, even = true)
        clampField(b.etBitrate, 1, 50, 10)
        clampField(b.etFps, 10, 60, 30)
        clampField(b.etPort, 1024, 65535, Protocol.DEFAULT_PORT)
        saveSettings()

        if (b.etName.text.toString().isBlank() && b.etHost.text.toString().isBlank()) {
            b.tvStatus.text = "Enter a receiver name, IP, or hostname"; return
        }

        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33 &&
            !granted(Manifest.permission.POST_NOTIFICATIONS)) perms += Manifest.permission.POST_NOTIFICATIONS
        // System-audio capture only exists on API 29+, so only ask for the mic there.
        if (b.cbAudio.isChecked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            !granted(Manifest.permission.RECORD_AUDIO))
            perms += Manifest.permission.RECORD_AUDIO

        if (perms.isNotEmpty()) permLauncher.launch(perms.toTypedArray()) else launchProjection()
    }

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun launchProjection() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        b.tvStatus.text = "Requesting screen capture…"
        try { projLauncher.launch(mpm.createScreenCaptureIntent()) }
        catch (e: Exception) { b.tvStatus.text = "Cannot start screen capture: ${e.message}" }
    }

    private fun startSender(resultCode: Int, data: Intent) {
        val svc = Intent(this, SenderService::class.java).apply {
            putExtra(SenderService.EXTRA_RESULT_CODE, resultCode)
            putExtra(SenderService.EXTRA_DATA, data)
            putExtra(SenderService.EXTRA_NAME, b.etName.text.toString().trim())
            putExtra(SenderService.EXTRA_HOST, b.etHost.text.toString().trim())
            putExtra(SenderService.EXTRA_PORT, b.etPort.text.toString().trim().toIntOrNull() ?: Protocol.DEFAULT_PORT)
            putExtra(SenderService.EXTRA_PASSWORD, b.etPassword.text.toString())
            putExtra(SenderService.EXTRA_TLS, b.cbEncrypt.isChecked)
            putExtra(SenderService.EXTRA_WIDTH, b.etWidth.text.toString().trim().toIntOrNull() ?: 1920)
            putExtra(SenderService.EXTRA_HEIGHT, b.etHeight.text.toString().trim().toIntOrNull() ?: 1080)
            putExtra(SenderService.EXTRA_FPS, b.etFps.text.toString().trim().toIntOrNull() ?: 30)
            putExtra(SenderService.EXTRA_BITRATE, b.etBitrate.text.toString().trim().toIntOrNull() ?: 10)
            putExtra(SenderService.EXTRA_AUDIO, b.cbAudio.isChecked)
        }
        ContextCompat.startForegroundService(this, svc)
        b.tvStatus.text = "Starting…"
        b.btnStartStop.postDelayed({ refreshUi() }, 400)
    }

    private fun stopSender() {
        startService(Intent(this, SenderService::class.java).setAction(SenderService.ACTION_STOP))
        b.btnStartStop.postDelayed({ refreshUi(); b.tvStatus.text = SenderService.lastStatus }, 300)
    }

    private fun saveSettings() {
        prefs.edit()
            .putString("name", b.etName.text.toString().trim())
            .putString("host", b.etHost.text.toString().trim())
            .putInt("port", b.etPort.text.toString().trim().toIntOrNull() ?: Protocol.DEFAULT_PORT)
            .putString("password", b.etPassword.text.toString())
            .putBoolean("tls", b.cbEncrypt.isChecked)
            .putInt("width", b.etWidth.text.toString().trim().toIntOrNull() ?: 1920)
            .putInt("height", b.etHeight.text.toString().trim().toIntOrNull() ?: 1080)
            .putInt("bitrate", b.etBitrate.text.toString().trim().toIntOrNull() ?: 10)
            .putInt("fps", b.etFps.text.toString().trim().toIntOrNull() ?: 30)
            .putBoolean("audio", b.cbAudio.isChecked)
            .apply()
    }

    private fun showAbout() {
        val view = layoutInflater.inflate(R.layout.dialog_about, null)
        view.findViewById<TextView>(R.id.tvAboutVersion).text = "Version " + BuildConfig.VERSION_NAME
        val dialog = AlertDialog.Builder(this).setView(view).create()
        dialog.window?.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        view.findViewById<Button>(R.id.btnAboutClose).setOnClickListener { dialog.dismiss() }
        dialog.show()
    }
}
