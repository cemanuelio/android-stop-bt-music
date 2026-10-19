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
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.Handler
import android.os.IBinder
import android.content.pm.ServiceInfo
import android.os.Looper
import android.util.Log

class MonitorService : Service() {

    companion object {
        const val TAG = "StopBL"

        const val PREFS = "stopbl"
        const val KEY_ENABLED = "enabled"

        private const val CHANNEL_ID = "monitor"
        private const val NOTIF_ID = 1
        private const val GRACE_MS = 600L
        private const val POLL_MS = 1500L
        // A tecla de mídia pode ser perdida enquanto o sistema troca a rota de
        // áudio após a desconexão; reenviamos até confirmar a pausa.
        private const val VERIFICAR_MS = 900L
        private const val MAX_TECLA_TENTATIVAS = 2

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
    // getSystemService exige contexto anexado — só pode ser usado a partir de
    // onCreate (não no construtor da classe, senão NPE).
    private val nm: NotificationManager by lazy { getSystemService(NotificationManager::class.java) }
    private var a2dp: BluetoothProfile? = null
    private var a2dpListener: BluetoothProfile.ServiceListener? = null
    // Em A16, getActiveSessions(null) exige MANAGE_MEDIA_ACCESS; se a primeira
    // chamada lançar SecurityException, não tentamos de novo.
    private var sessionsBloqueadas = false
    private var pendingPause: Runnable? = null
    private var pendenteVerificacao: Runnable? = null
    private var lastSeen: Set<String> = emptySet()

    // A API 36 removeu o onConnectionStateChanged do proxy; o caminho estável
    // é polluar getConnectedDevices() e detectar a transição "algum → nenhum".
    private val pollRunnable = object : Runnable {
        override fun run() {
            pollA2dp()
            handler.postDelayed(this, POLL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        createChannel()
        val notif = buildNotification("Monitorando Bluetooth")
        startForeground(
            NOTIF_ID,
            notif,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
        setupA2dp()
        Log.d(TAG, "MonitorService criado")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(pollRunnable)
        pendingPause?.let { handler.removeCallbacks(it) }
        pendingPause = null
        closeA2dp()
        running = false
        Log.d(TAG, "MonitorService destruído")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Monitoramento Bluetooth", NotificationManager.IMPORTANCE_LOW
        )
        nm.createNotificationChannel(channel)
    }

    private fun setupA2dp() {
        val bt = getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bt?.adapter
        if (adapter == null) {
            setLastEvent("Bluetooth indisponível")
            return
        }
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(state: Int, profile: BluetoothProfile) {
                a2dp = profile
                Log.d(TAG, "A2DP proxy conectado")
                pollA2dp()
            }

            override fun onServiceDisconnected(state: Int) {
                a2dp = null
                lastSeen = emptySet()
            }
        }
        a2dpListener = listener
        try {
            if (!adapter.getProfileProxy(this, listener, BluetoothProfile.A2DP)) {
                Log.e(TAG, "getProfileProxy falhou")
                setLastEvent("Falha ao obter proxy A2DP")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "BLUETOOTH_CONNECT negado — sem monitoramento A2DP", e)
            setLastEvent("Sem permissão de Bluetooth")
            return
        }
        handler.postDelayed(pollRunnable, POLL_MS)
    }

    private fun closeA2dp() {
        val proxy = a2dp
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        a2dp = null
        a2dpListener = null
        if (proxy != null) {
            try {
                adapter?.closeProfileProxy(BluetoothProfile.A2DP, proxy)
            } catch (e: Exception) {
                Log.w(TAG, "closeProfileProxy falhou", e)
            }
        }
    }

    private fun pollA2dp() {
        val connected: Set<String> = try {
            val devices = a2dp?.getConnectedDevices() ?: emptyList()
            devices.map { safeName(it) }.toSet()
        } catch (e: SecurityException) {
            Log.w(TAG, "Sem permissão para listar A2DP", e)
            return
        }
        if (lastSeen.isNotEmpty() && connected.isEmpty()) {
            Log.d(TAG, "Transição: todos os A2DP desconectados")
            schedulePauseCheck()
        }
        lastSeen = connected
    }

    private fun schedulePauseCheck() {
        pendingPause?.let { handler.removeCallbacks(it) }
        val r = Runnable {
            pendingPause = null
            val count = safeConnectedCount()
            if (count == 0) {
                Log.i(TAG, "Nenhum dispositivo A2DP conectado — pausando players")
                pausarPlayers()
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
            a2dp?.getConnectedDevices()?.size ?: 0
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

    private fun pausarPlayers() {
        if (!sessionsBloqueadas) {
            try {
                val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
                val controllers = msm.getActiveSessions(null)
                var n = 0
                for (c in controllers) {
                    try {
                        c.transportControls.pause()
                        n++
                    } catch (e: Exception) {
                        Log.w(TAG, "Falha ao pausar sessão de ${safePackageName(c)}", e)
                    }
                }
                pauseCount += n
                Log.i(TAG, "Pausa via sessão em $n de ${controllers.size}")
                setLastEvent("Pausa via sessão ($n/${controllers.size})")
                return
            } catch (e: SecurityException) {
                sessionsBloqueadas = true
                Log.w(TAG, "getActiveSessions bloqueado — usando tecla de mídia", e)
            }
        }

        when {
            !StopBLAccessibilityService.isReady() ->
                setLastEvent("Habilite a acessibilidade para pausar")
            !StopBLAccessibilityService.isMediaActive(this) ->
                setLastEvent("Nada tocando — sem pausa")
            else -> enviarTeclaComVerificacao(0)
        }
    }

    // A tecla é um toggle: enviamos, esperamos a rota de áudio assentar e
    // confirmamos com isMusicActive(). Se ainda estiver tocando (tecla
    // perdida, ou o player já tinha pausado e o toggle retomou), reenviamos.
    private fun enviarTeclaComVerificacao(tentativa: Int) {
        pendenteVerificacao?.let { handler.removeCallbacks(it) }
        if (!StopBLAccessibilityService.sendMediaPlayPause()) {
            setLastEvent("Tecla de mídia falhou")
            return
        }
        Log.d(TAG, "Tecla play/pause enviada (tentativa $tentativa)")
        val r = Runnable {
            pendenteVerificacao = null
            if (safeConnectedCount() > 0) {
                setLastEvent("A2DP reconectou — sem pausa")
                return@Runnable
            }
            if (StopBLAccessibilityService.isMediaActive(this@MonitorService)) {
                if (tentativa < MAX_TECLA_TENTATIVAS) {
                    Log.d(TAG, "Ainda tocando — reenviando tecla")
                    enviarTeclaComVerificacao(tentativa + 1)
                } else {
                    setLastEvent("Tecla de mídia não pausou")
                }
            } else {
                pauseCount++
                setLastEvent("Pausa via tecla de mídia")
            }
        }
        pendenteVerificacao = r
        handler.postDelayed(r, VERIFICAR_MS)
    }

    private fun safePackageName(c: MediaController): String {
        return try {
            c.packageName ?: "?"
        } catch (e: Exception) {
            "?"
        }
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
