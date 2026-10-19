package dev.sovietlab.stopbl

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.media.AudioManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Serviço de acessibilidade mínimo: não lê conteúdo de tela nem eventos.
 * Existe apenas para usar performGlobalAction(GLOBAL_ACTION_MEDIA_PLAY_PAUSE),
 * que o sistema roteia para o player ativo — como uma tecla de hardware.
 *
 * Em Android 16, getActiveSessions(null) exige MANAGE_MEDIA_ACCESS
 * (signature/preinstalled), então esse é o caminho para pausar um
 * player de terceiros sem root.
 */
class StopBLAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "StopBL"

        @Volatile
        var instance: StopBLAccessibilityService? = null
            private set

        /** true quando o serviço está habilitado e conectado pelo sistema. */
        fun isReady(): Boolean = instance != null

        /**
         * true se há áudio de mídia ativo no sistema (qualquer app),
         * consultado de forma síncrona via AudioManager.isMusicActive().
         */
        fun isMediaActive(ctx: Context): Boolean {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (am == null) {
                return false
            }
            return try {
                am.isMusicActive()
            } catch (e: Exception) {
                Log.w(TAG, "isMusicActive falhou", e)
                false
            }
        }

        /**
         * Envia a tecla play/pause de mídia para o player ativo.
         *
         * É um toggle: o chamador deve checar isMediaActive() antes para
         * não retomar um player que já estava pausado.
         *
         * @return true se a tecla foi entregue ao sistema.
         */
        fun sendMediaPlayPause(): Boolean {
            val svc = instance
            if (svc == null) {
                Log.d(TAG, "sendMediaPlayPause: acessibilidade não habilitada")
                return false
            }
            val ok = svc.performGlobalAction(
                AccessibilityService.GLOBAL_ACTION_MEDIA_PLAY_PAUSE
            )
            Log.d(TAG, "sendMediaPlayPause: tecla play/pause -> $ok")
            return ok
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Serviço de acessibilidade conectado")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Intencionalmente ignorado — só usamos performGlobalAction.
    }

    override fun onInterrupt() {
        // Nada a fazer.
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }
}
