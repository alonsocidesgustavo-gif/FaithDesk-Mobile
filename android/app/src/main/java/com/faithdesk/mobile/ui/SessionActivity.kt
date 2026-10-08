package com.faithdesk.mobile.ui

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.faithdesk.mobile.databinding.ActivitySessionBinding
import com.faithdesk.mobile.network.SignalingEvent
import com.faithdesk.mobile.network.SignalingManager
import com.faithdesk.mobile.service.RemoteControlService
import com.faithdesk.mobile.service.ScreenCaptureService
import com.faithdesk.mobile.webrtc.WebRTCManager
import kotlinx.coroutines.launch
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory
import org.webrtc.VideoTrack

/**
 * Tela ativa quando o dispositivo está sendo compartilhado (HOST).
 *
 * Mostra:
 *  - Banner vermelho visível indicando que a tela está sendo compartilhada
 *  - Identidade de quem está conectado
 *  - Botão de encerrar (sempre visível e funcional)
 */
class SessionActivity : AppCompatActivity() {

    private val TAG = "SessionActivity"
    private lateinit var binding: ActivitySessionBinding

    private var sessionId: String = ""
    private var captureService: ScreenCaptureService? = null
    private var webRTCManager: WebRTCManager? = null
    private val eglBase by lazy { EglBase.create() }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            captureService = (binder as ScreenCaptureService.LocalBinder).getService()
            requestScreenCapture()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            captureService = null
        }
    }

    private val capturePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startCapture(result.data!!)
        } else {
            showError("Permissão de captura recusada.")
            endSession()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySessionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Mantém tela acesa durante a sessão
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        sessionId = intent.getStringExtra("SESSION_ID") ?: run {
            finish(); return
        }

        setupUI()
        setupWebRTC()
        startCaptureService()
        observeSignaling()
        checkAccessibilityService()
    }

    private fun setupUI() {
        binding.btnEndSession.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Encerrar Sessão")
                .setMessage("Tem certeza que deseja encerrar a sessão remota?")
                .setPositiveButton("Encerrar") { _, _ -> endSession() }
                .setNegativeButton("Cancelar", null)
                .show()
        }
    }

    private fun setupWebRTC() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(applicationContext)
                .createInitializationOptions()
        )

        webRTCManager = WebRTCManager(
            context = this,
            sessionId = sessionId,
            isHost = true,
            onConnectionStateChange = { state ->
                runOnUiThread {
                    binding.tvConnectionState.text = "WebRTC: $state"
                }
            }
        ).also { it.initialize() }

        // HOST: cria offer depois de adicionar a trilha de vídeo
    }

    private fun startCaptureService() {
        val intent = Intent(this, ScreenCaptureService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        bindService(intent, serviceConnection, BIND_AUTO_CREATE)
    }

    private fun requestScreenCapture() {
        val projManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        capturePermissionLauncher.launch(projManager.createScreenCaptureIntent())
    }

    private fun startCapture(projectionData: Intent) {
        val factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(
                org.webrtc.DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
            )
            .setVideoDecoderFactory(
                org.webrtc.DefaultVideoDecoderFactory(eglBase.eglBaseContext)
            )
            .createPeerConnectionFactory()

        captureService?.startCapture(projectionData, factory, eglBase.eglBaseContext)

        captureService?.videoTrack?.let { track ->
            webRTCManager?.addVideoTrack(track)
            webRTCManager?.createOffer()
            RemoteControlService.sessionActive = true
            runOnUiThread {
                binding.tvStatus.text = "Sessão ativa"
                binding.bannerActive.visibility = android.view.View.VISIBLE
            }
        }
    }

    private fun observeSignaling() {
        lifecycleScope.launch {
            SignalingManager.events.collect { event ->
                when (event) {
                    is SignalingEvent.WebRTCSignal -> handleWebRTCSignal(event)
                    is SignalingEvent.SessionEnded -> {
                        runOnUiThread {
                            showError("Sessão encerrada pelo controlador.")
                            endSession()
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    private fun handleWebRTCSignal(event: SignalingEvent.WebRTCSignal) {
        when (event.type) {
            "answer" -> webRTCManager?.handleAnswer(event.data)
            "ice-candidate" -> webRTCManager?.addIceCandidate(event.data)
        }
    }

    private fun checkAccessibilityService() {
        val service = "${packageName}/${RemoteControlService::class.java.name}"
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )?.contains(service) == true

        if (!enabled) {
            binding.tvAccessibility.text = "⚠️ Serviço de acessibilidade inativo (controle remoto desabilitado)"
            binding.tvAccessibility.visibility = android.view.View.VISIBLE
        }
    }

    private fun showError(msg: String) {
        AlertDialog.Builder(this).setMessage(msg).setPositiveButton("OK", null).show()
    }

    private fun endSession() {
        SignalingManager.endSession(sessionId)
        RemoteControlService.sessionActive = false
        captureService?.stopCapture()
        webRTCManager?.dispose()
        eglBase.release()
        unbindService(serviceConnection)
        finish()
    }

    override fun onDestroy() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onDestroy()
    }
}
