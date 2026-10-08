package com.faithdesk.mobile.ui

import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.faithdesk.mobile.databinding.ActivityControllerBinding
import com.faithdesk.mobile.network.SignalingEvent
import com.faithdesk.mobile.network.SignalingManager
import com.faithdesk.mobile.webrtc.WebRTCManager
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.webrtc.*

/**
 * Tela do CONTROLADOR — quem se conecta ao dispositivo remoto.
 *
 * Funções:
 *  - Digitar o ID do dispositivo alvo
 *  - Solicitar conexão (via servidor de sinalização)
 *  - Ver a tela remota (via WebRTC VideoTrack)
 *  - Enviar toques/gestos para controle remoto
 */
class ControllerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityControllerBinding
    private var webRTCManager: WebRTCManager? = null
    private val eglBase by lazy { EglBase.create() }
    private var sessionId: String? = null

    // Dimensões da tela do host (recebidas com a oferta)
    private var remoteWidth = 1280f
    private var remoteHeight = 720f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityControllerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        observeSignaling()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupUI() {
        // Botão de conectar
        binding.btnConnect.setOnClickListener {
            val targetId = binding.etTargetId.text.toString().trim()
            if (targetId.length < 9) {
                Toast.makeText(this, "Digite um ID válido (9 dígitos)", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            requestConnection(targetId)
        }

        // Botão de encerrar
        binding.btnEndSession.setOnClickListener {
            endSession()
        }

        // Tela remota — captura toques e converte para coordenadas do dispositivo remoto
        binding.surfaceRemote.setOnTouchListener { view, event ->
            if (sessionId == null) return@setOnTouchListener false
            translateAndSendTouch(view, event)
            true
        }

        // SurfaceView para WebRTC
        binding.surfaceRemote.init(eglBase.eglBaseContext, null)
        binding.surfaceRemote.setEnableHardwareScaler(true)
    }

    private fun requestConnection(targetId: String) {
        binding.tvStatus.text = "Aguardando aceite do dispositivo..."
        binding.btnConnect.isEnabled = false

        // Registra este dispositivo se ainda não estiver
        val deviceName = Build.MODEL
        SignalingManager.connect(deviceName, null)

        lifecycleScope.launch {
            // Espera o registro antes de solicitar
            SignalingManager.deviceId.collect { id ->
                if (id != null) {
                    SignalingManager.requestConnection(targetId)
                    return@collect
                }
            }
        }
    }

    private fun observeSignaling() {
        lifecycleScope.launch {
            SignalingManager.events.collect { event ->
                when (event) {
                    is SignalingEvent.RequestSent -> {
                        binding.tvStatus.text = "Solicitação enviada. Aguardando aceite..."
                    }
                    is SignalingEvent.RequestDeclined -> {
                        binding.tvStatus.text = "Conexão recusada pelo dispositivo."
                        binding.btnConnect.isEnabled = true
                    }
                    is SignalingEvent.SessionStarted -> {
                        if (event.role == "client") {
                            sessionId = event.sessionId
                            setupWebRTC(event.sessionId)
                        }
                    }
                    is SignalingEvent.WebRTCSignal -> handleWebRTCSignal(event)
                    is SignalingEvent.SessionEnded -> {
                        runOnUiThread {
                            binding.tvStatus.text = "Sessão encerrada."
                            showRemoteScreen(false)
                            sessionId = null
                        }
                    }
                    is SignalingEvent.Error -> {
                        runOnUiThread {
                            binding.tvStatus.text = "Erro: ${event.message}"
                            binding.btnConnect.isEnabled = true
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    private fun setupWebRTC(sid: String) {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(applicationContext)
                .createInitializationOptions()
        )

        webRTCManager = WebRTCManager(
            context = this,
            sessionId = sid,
            isHost = false,
            onRemoteVideoTrack = { track ->
                runOnUiThread {
                    track.addSink(binding.surfaceRemote)
                    binding.tvStatus.text = "Conectado — sessão remota ativa"
                    showRemoteScreen(true)
                }
            },
            onConnectionStateChange = { state ->
                runOnUiThread {
                    when (state) {
                        PeerConnection.IceConnectionState.CONNECTED,
                        PeerConnection.IceConnectionState.COMPLETED -> {
                            binding.tvStatus.text = "Conectado"
                        }
                        PeerConnection.IceConnectionState.DISCONNECTED,
                        PeerConnection.IceConnectionState.FAILED -> {
                            binding.tvStatus.text = "Conexão perdida"
                        }
                        else -> {}
                    }
                }
            }
        ).also { it.initialize() }
        // O HOST enviará o offer; aguardamos via sinalização
    }

    private fun handleWebRTCSignal(event: SignalingEvent.WebRTCSignal) {
        when (event.type) {
            "offer" -> webRTCManager?.handleOffer(event.data)
            "ice-candidate" -> webRTCManager?.addIceCandidate(event.data)
        }
    }

    // ── Traduzir toque local → coordenadas remotas ─────────────────────────────
    private fun translateAndSendTouch(view: View, event: MotionEvent) {
        val scaleX = remoteWidth / view.width
        val scaleY = remoteHeight / view.height

        val remoteX = event.x * scaleX
        val remoteY = event.y * scaleY

        val touchData = JSONObject().apply {
            put("action", event.actionMasked)
            put("x", remoteX)
            put("y", remoteY)
            put("pressure", event.pressure)
        }

        sessionId?.let { sid ->
            SignalingManager.sendWebRTCSignal(sid, "touch", touchData)
        }
    }

    private fun showRemoteScreen(show: Boolean) {
        binding.surfaceRemote.visibility = if (show) View.VISIBLE else View.GONE
        binding.layoutConnect.visibility = if (show) View.GONE else View.VISIBLE
        binding.btnEndSession.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun endSession() {
        sessionId?.let { SignalingManager.endSession(it) }
        webRTCManager?.dispose()
        eglBase.release()
        finish()
    }

    override fun onDestroy() {
        endSession()
        super.onDestroy()
    }
}
