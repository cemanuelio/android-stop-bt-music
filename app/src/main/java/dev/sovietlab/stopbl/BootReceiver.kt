package dev.sovietlab.stopbl

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) {
            return
        }
        val prefs = context.getSharedPreferences(
            MonitorService.PREFS, Context.MODE_PRIVATE
        )
        if (!prefs.getBoolean(MonitorService.KEY_ENABLED, false)) {
            Log.d(MonitorService.TAG, "BOOT: monitor desativado, nada a fazer")
            return
        }
        try {
            context.startForegroundService(
                Intent(context, MonitorService::class.java)
            )
            Log.d(MonitorService.TAG, "BOOT: MonitorService reiniciado")
        } catch (e: Exception) {
            Log.e(MonitorService.TAG, "BOOT: falha ao reiniciar serviço", e)
        }
    }
}
