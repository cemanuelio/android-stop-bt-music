package dev.sovietlab.stopbl

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    companion object {
        private const val REQ_PERMS = 1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.main)
        wireButtons()
        requestNeededPermissions()
    }

    override fun onResume() {
        super.onResume()
        maybeAutoStart()
        refresh()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMS && allPermsGranted()) {
            maybeAutoStart()
        }
        refresh()
    }

    private fun wireButtons() {
        findViewById<Button>(R.id.btnStart).setOnClickListener {
            startMonitor()
        }
        findViewById<Button>(R.id.btnStop).setOnClickListener {
            stopMonitor()
        }
        findViewById<Button>(R.id.btnTest).setOnClickListener {
            if (!StopBLAccessibilityService.isReady()) {
                toast("Habilite a acessibilidade primeiro")
                return@setOnClickListener
            }
            if (!StopBLAccessibilityService.isMediaActive(this)) {
                toast("Nada tocando no momento")
                return@setOnClickListener
            }
            val ok = StopBLAccessibilityService.sendMediaPlayPause()
            toast(if (ok) "Tecla play/pause enviada ✓" else "A tecla de mídia falhou")
        }
        findViewById<Button>(R.id.btnAcc).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btnBattery).setOnClickListener {
            requestIgnoreBatteryOptimizations()
        }
    }

    private fun maybeAutoStart() {
        if (!MonitorService.running && allPermsGranted()) {
            startMonitor()
        }
    }

    private fun startMonitor() {
        getSharedPreferences(MonitorService.PREFS, MODE_PRIVATE)
            .edit()
            .putBoolean(MonitorService.KEY_ENABLED, true)
            .apply()
        startForegroundService(Intent(this, MonitorService::class.java))
        refresh()
    }

    private fun stopMonitor() {
        getSharedPreferences(MonitorService.PREFS, MODE_PRIVATE)
            .edit()
            .putBoolean(MonitorService.KEY_ENABLED, false)
            .apply()
        stopService(Intent(this, MonitorService::class.java))
        refresh()
    }

    private fun allPermsGranted(): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return true
    }

    private fun requestNeededPermissions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (needed.isNotEmpty()) {
            requestPermissions(needed.toTypedArray(), REQ_PERMS)
        }
    }

    private fun requestIgnoreBatteryOptimizations() {
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        } else {
            toast("Já isento de otimização de bateria")
        }
    }

    private fun refresh() {
        findViewById<TextView>(R.id.tvStatus).text =
            if (MonitorService.running) "Monitorando" else "Parado"
        findViewById<TextView>(R.id.tvLastEvent).text =
            "Último evento: ${MonitorService.lastEvent}"
        findViewById<TextView>(R.id.tvPauseCount).text =
            "Pausas enviadas: ${MonitorService.pauseCount}"
        findViewById<TextView>(R.id.tvAcc).text =
            "Acessibilidade: " +
                if (StopBLAccessibilityService.isReady()) "habilitada ✓" else "desabilitada"
        findViewById<Button>(R.id.btnStart).visibility =
            if (MonitorService.running) View.GONE else View.VISIBLE
        findViewById<Button>(R.id.btnStop).visibility =
            if (MonitorService.running) View.VISIBLE else View.GONE
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
