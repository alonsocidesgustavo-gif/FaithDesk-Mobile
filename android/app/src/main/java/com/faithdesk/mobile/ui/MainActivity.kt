package com.faithdesk.mobile.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.faithdesk.mobile.databinding.ActivityMainBinding
import com.faithdesk.mobile.network.ConnectionState
import com.faithdesk.mobile.network.SignalingEvent
import com.faithdesk.mobile.network.SignalingManager
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var pendingRequestId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        observeSignaling()
        connectToServer()
    }

    private fun setupUI() {
        // Copiar ID para a área de transferência
        binding.btnCopyId.setOnClickListener {
            val id = binding.tvDeviceId.text.toString()
            if (id != "Conectando...") {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("FaithDesk ID", id))
                Toast.makeText(this, "ID copiado!", Toast.LENGTH_SHORT).show()
            }
        }

        // Ir para tela de controlador (conectar em outro dispositivo)
        binding.btnConnect.setOnClickListener {
            startActivity(Intent(this, ControllerActivity::class.java))
        }

        // Configurações (AccessibilityService)
        binding.btnSettings.setOnClickListener {
            showSettingsDialog()
        }
    }

    private fun connectToServer() {
        val deviceName = android.os.Build.MODEL
        val savedId = getPreferences(MODE_PRIVATE).getString("device_id", null)
        SignalingManager.connect(deviceName, savedId)
    }

    private fun observeSignaling() {
        lifecycleScope.launch {
            SignalingManager.connectionState.collect { state ->
                updateConnectionStatus(state)
            }
        }

        lifecycleScope.launch {
            SignalingManager.deviceId.collect { id ->
                id?.let {
                    // Formata: 123-456-789 → "123 456 789" (mais legível na tela)
                    binding.tvDeviceId.text = it.replace("-", " ")
                    getPreferences(MODE_PRIVATE).edit().putString("device_id", it).apply()
                }
            }
        }

        lifecycleScope.launch {
            SignalingManager.events.collect { event ->
                when (event) {
                    is SignalingEvent.IncomingRequest -> showIncomingRequest(event)
                    is SignalingEvent.SessionStarted -> {
                        if (event.role == "host") {
                            startHostSession(event.sessionId)
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    private fun updateConnectionStatus(state: ConnectionState) {
        when (state) {
            ConnectionState.CONNECTED -> {
                binding.tvStatus.text = "● Pronto para conexão"
                binding.tvStatus.setTextColor(getColor(android.R.color.holo_green_dark))
                binding.statusDot.setBackgroundResource(com.faithdesk.mobile.R.drawable.dot_green)
            }
            ConnectionState.CONNECTING -> {
                binding.tvStatus.text = "○ Conectando ao servidor..."
                binding.tvStatus.setTextColor(getColor(android.R.color.darker_gray))
            }
            ConnectionState.ERROR, ConnectionState.DISCONNECTED -> {
                binding.tvStatus.text = "● Desconectado"
                binding.tvStatus.setTextColor(getColor(android.R.color.holo_red_dark))
            }
        }
    }

    private fun showIncomingRequest(event: SignalingEvent.IncomingRequest) {
        pendingRequestId = event.requestId

        AlertDialog.Builder(this)
            .setTitle("Solicitação de Conexão")
            .setMessage(
                "O dispositivo '${event.fromName}' (${event.fromId}) " +
                "solicita acesso remoto à sua tela.\n\n" +
                "Você autoriza?"
            )
            .setPositiveButton("✅ Aceitar") { _, _ ->
                SignalingManager.respondToRequest(event.requestId, true)
            }
            .setNegativeButton("❌ Recusar") { _, _ ->
                SignalingManager.respondToRequest(event.requestId, false)
                pendingRequestId = null
            }
            .setCancelable(false)
            .show()
    }

    private fun startHostSession(sessionId: String) {
        val intent = Intent(this, SessionActivity::class.java).apply {
            putExtra("SESSION_ID", sessionId)
            putExtra("ROLE", "host")
        }
        startActivity(intent)
    }

    private fun showSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle("Configurações")
            .setMessage(
                "Para permitir controle remoto, o FaithDesk precisa do serviço de Acessibilidade.\n\n" +
                "Vá em: Configurações > Acessibilidade > FaithDesk — Controle Remoto\n\n" +
                "⚠️ Ative apenas quando quiser permitir acesso remoto."
            )
            .setPositiveButton("Abrir Configurações") { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton("Fechar", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        // NÃO desconectamos aqui — o serviço pode continuar em background
    }
}
