package com.faithdesk.mobile.service

import android.app.*
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.faithdesk.mobile.FaithDeskApp
import com.faithdesk.mobile.R
import com.faithdesk.mobile.ui.MainActivity
import org.webrtc.*

/**
 * Serviço em foreground que captura a tela usando MediaProjection API oficial.
 * 
 * REQUISITOS DO ANDROID:
 *  - Precisa de permissão explícita do usuário (Intent de MediaProjection)
 *  - Deve rodar como ForegroundService com notificação visível
 *  - Tipo: mediaProjection (Android 10+)
 * 
 * PRIVACIDADE:
 *  - A tela NÃO é armazenada localmente nem no servidor
 *  - Frames são enviados diretamente via WebRTC (P2P criptografado)
 *  - O serviço para imediatamente quando o usuário encerra a sessão
 */
class ScreenCaptureService : Service() {

    private val TAG = "ScreenCaptureService"

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    // WebRTC video source
    private var videoSource: VideoSource? = null
    var videoTrack: VideoTrack? = null
        private set

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService() = this@ScreenCaptureService
    }

    override fun onBind(intent: Intent?) = binder

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    // ── Iniciar captura ───────────────────────────────────────────────────────
    fun startCapture(
        projectionData: Intent,
        factory: PeerConnectionFactory,
        eglContext: EglBase.Context
    ) {
        val projManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projManager.getMediaProjection(Activity.RESULT_OK, projectionData).also {
            it.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.d(TAG, "MediaProjection parada pelo sistema")
                    stopCapture()
                }
            }, Handler(Looper.getMainLooper()))
        }

        val metrics = DisplayMetrics()
        (getSystemService(WINDOW_SERVICE) as WindowManager)
            .defaultDisplay.getRealMetrics(metrics)

        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val dpi = metrics.densityDpi

        // Cria VideoSource WebRTC
        surfaceTextureHelper = SurfaceTextureHelper.create("CaptureSurface", eglContext)
        videoSource = factory.createVideoSource(false).also { source ->
            videoTrack = factory.createVideoTrack("faithdesk-video", source)

            // ScreenCapturerAndroid usa MediaProjection internamente via VideoSource
            val capturer = ScreenCapturerAndroid(
                projectionData,
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        Log.d(TAG, "ScreenCapturer parado")
                    }
                }
            )

            capturer.initialize(surfaceTextureHelper, applicationContext, source.capturerObserver)
            // Resolução: 720p para equilíbrio de qualidade/latência
            capturer.startCapture(1280, 720, 30)

            Log.d(TAG, "Captura de tela iniciada: ${width}x${height}@${dpi}dpi")
        }
    }

    // ── Parar captura ─────────────────────────────────────────────────────────
    fun stopCapture() {
        virtualDisplay?.release()
        virtualDisplay = null
        mediaProjection?.stop()
        mediaProjection = null
        imageReader?.close()
        imageReader = null
        surfaceTextureHelper?.dispose()
        surfaceTextureHelper = null
        videoTrack?.dispose()
        videoTrack = null
        videoSource?.dispose()
        videoSource = null
        Log.d(TAG, "Captura encerrada")
    }

    // ── Notificação foreground (obrigatória) ──────────────────────────────────
    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val mainIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, FaithDeskApp.CHANNEL_SESSION)
            .setContentTitle("FaithDesk Mobile")
            .setContentText("🔴 Sessão remota ativa — sua tela está sendo compartilhada")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setContentIntent(mainIntent)
            .addAction(0, "Encerrar sessão", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopCapture()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    companion object {
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.faithdesk.mobile.STOP_CAPTURE"
    }
}
