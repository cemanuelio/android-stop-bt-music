package dev.sovietlab.stopbl

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log

class MonitorService : Service() {

    companion object {
        const val TAG = "StopBL"
        const val ACTION_TEST_START = "dev.sovietlab.stopbl.ACTION_TEST_START"
        const val ACTION_TEST_STOP = "dev.sovietlab.stopbl.ACTION_TEST_STOP"

        const val PREFS = "stopbl"
        const val KEY_ENABLED = "enabled"

        private const val CHANNEL_ID = "monitor"
        private const val NOTIF_ID = 1
        private const val GRACE_MS = 600L

        @Volatile
        var running: Boolean = false
            private set

        @Volatile
        var lastEvent: String = "—"
            private set

        @Volatile
        var pauseCount: Int = 0
            private set
    }

    private val handler = Handler(Looper.getMainLooper())
    private val nm = getSystemService(NotificationManager::class.java)
    private var a2dp: BluetoothProfile? = null
    private var testSession: MediaSession? = null
    private var pendingPause: Runnable? = null

    private val a2dpListener = object : BluetoothProfile.ServiceCallback {
        override fun onServiceConnected(state: Int, profile: BluetoothProfile) {
            a2dp = profile
            Log.d(TAG, "A2DP proxy conectado")
            setLastEvent("Monitorando (A2DP ativos: ${safeConnectedCount()})")
        }

        override fun onServiceDisconnected(state: Int) {
            a2dp = null
        }

        override fun onConnectionStateChanged(state: Int, device: BluetoothDevice) {
            val name = safeName(device)
            Log.d(TAG, "A2DP estado=$state dispositivo=$name")
            if (state == BluetoothProfile.STATE_DISCONNECTED) {
                setLastEvent("A2DP desconectado: $name — verificando…")
                schedulePauseCheck()
            } else if (state == BluetoothProfile.STATE_CONNECTED) {
                setLastEvent("A2DP conectado: $name")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        createChannel()
        val notif = buildNotification("Monitorando Bluetooth")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIF_ID,
                notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                    or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIF_ID, notif)
        }
        setupA2dp()
        Log.d(TAG, "MonitorService criado")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TEST_START -> startTestSession()
            ACTION_TEST_STOP -> stopTestSession()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        pendingPause?.let { handler.removeCallbacks(it) }
        pendingPause = null
        stopTestSession()
        a2dp = null
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            Log.w(TAG, "stopForeground falhou", e)
        }
        running = false
        Log.d(TAG, "MonitorService destruído")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Monitoramento Bluetooth", NotificationManager.IMPORTANCE_LOW
        )
        nm.createChannel(channel)
    }

    private fun setupA2dp() {
        val bt = getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager ?: return
        val adapter = bt.adapter
        try {
            adapter?.getProfile(BluetoothProfile.A2DP, a2dpListener)
        } catch (e: SecurityException) {
            Log.e(TAG, "BLUETOOTH_CONNECT negado — sem monitoramento A2DP", e)
            setLastEvent("Sem permissão de Bluetooth")
        }
    }

    private fun schedulePauseCheck() {
        pendingPause?.let { handler.removeCallbacks(it) }
        val r = Runnable {
            pendingPause = null
            val count = safeConnectedCount()
            if (count == 0) {
                Log.i(TAG, "Nenhum dispositivo A2DP conectado — pausando sessões")
                pauseAllSessions()
            } else {
                Log.d(TAG, "A2DP reconectou rápido ($count) — sem pausa")
                setLastEvent("Dispositivo voltou rápido — sem pausa")
            }
        }
        pendingPause = r
        handler.postDelayed(r, GRACE_MS)
    }

    private fun safeConnectedCount(): Int {
        return try {
            a2dp?.connectedDevices?.size ?: 0
        } catch (e: SecurityException) {
            Log.w(TAG, "Sem permissão para listar A2DP", e)
            0
        }
    }

    private fun safeName(d: BluetoothDevice): String {
        return try {
            d.name ?: d.address
        } catch (e: SecurityException) {
            d.address
        }
    }

    private fun pauseAllSessions() {
        val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val tokens: List<MediaSession.Token>
        try {
            tokens = if (Build.VERSION.SDK_INT >= 34) {
                msm.getActiveSessions(null)
            } else {
                @Suppress("DEPRECATION")
                msm.activeSessions
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Sem permissão para acessar sessões de mídia", e)
            setLastEvent("Erro: sem permissão para sessões")
            return
        }

        var n = 0
        for (t in tokens) {
            var c: MediaController? = null
            try {
                c = MediaController(this, t)
                c.transportControls.pause()
                n++
            } catch (e: Exception) {
                Log.w(TAG, "Falha ao pausar sessão", e)
            } finally {
                try {
                    c?.release()
                } catch (e: Exception) {
                    // release nunca deve falhar de forma crítica
                }
            }
        }
        pauseCount += n
        Log.i(TAG, "Pausa enviada em $n de ${tokens.size} sessão(ões)")
        setLastEvent("Pausa enviada ($n sessão(ões))")
    }

    private fun startTestSession() {
        if (testSession != null) {
            Log.d(TAG, "Sessão de teste já ativa")
            return
        }
        val callback = object : MediaSession.Callback() {
            override fun onPause() {
                Log.i(TAG, "SELFTEST: comando de pause recebido — pipeline OK")
                setLastEvent("Teste: pause recebido ✓")
            }

            override fun onPlay() {
                Log.d(TAG, "SELFTEST: play (ignorado)")
            }
        }
        val session = MediaSession(this, "StopBL SelfTest", null, null)
        session.setCallback(callback)
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        session.setSessionActivity(pi)
        session.setActive(true)
        session.setPlaybackState(
            PlaybackState.Builder()
                .setState(PlaybackState.STATE_PLAYING)
                .setActions(MediaSession.ACTION_PLAY or MediaSession.ACTION_PAUSE)
                .setPlaybackRate(1f)
                .setBufferedPercentage(0)
                .build()
        )
        testSession = session
        Log.d(TAG, "Sessão de teste ativa (STATE_PLAYING)")
        setLastEvent("Teste: sessão STATE_PLAYING ativa")
    }

    private fun stopTestSession() {
        testSession?.let {
            it.setActive(false)
            it.release()
        }
        testSession = null
    }

    private fun setLastEvent(msg: String) {
        lastEvent = msg
        Log.d(TAG, msg)
        try {
            nm.notify(NOTIF_ID, buildNotification(msg))
        } catch (e: Exception) {
            Log.w(TAG, "Falha atualizando notificação", e)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_bt)
            .setContentTitle("Stop BT Music")
            .setContentText(text)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(pi)
            .build()
    }
}
