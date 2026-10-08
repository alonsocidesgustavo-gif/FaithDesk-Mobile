package com.faithdesk.mobile.network

import android.util.Log
import com.google.gson.Gson
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.net.URI

/**
 * Gerencia a conexão Socket.IO com o servidor de sinalização.
 * Toda a comunicação passa por aqui — sem backdoors, sem conexões ocultas.
 */
object SignalingManager {

    private const val TAG = "SignalingManager"

    // ⚠️ Troque pela URL do seu servidor em produção
    const val SERVER_URL = "https://seu-servidor-faithdesk.com"
    // Para testes locais: "http://10.0.2.2:3000" (emulador) ou "http://SEU-IP:3000" (dispositivo)

    private var socket: Socket? = null
    private val gson = Gson()

    // ── Estado ────────────────────────────────────────────────────────────────
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _deviceId = MutableStateFlow<String?>(null)
    val deviceId: StateFlow<String?> = _deviceId

    // ── Eventos ───────────────────────────────────────────────────────────────
    private val _events = MutableSharedFlow<SignalingEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<SignalingEvent> = _events

    // ── Conectar ──────────────────────────────────────────────────────────────
    fun connect(deviceName: String, existingId: String? = null) {
        if (socket?.connected() == true) return

        val opts = IO.Options.builder()
            .setReconnection(true)
            .setReconnectionAttempts(10)
            .setReconnectionDelay(1000)
            .setReconnectionDelayMax(5000)
            .build()

        socket = IO.socket(URI.create(SERVER_URL), opts).apply {
            on(Socket.EVENT_CONNECT) {
                Log.d(TAG, "Conectado ao servidor")
                _connectionState.tryEmit(ConnectionState.CONNECTED)

                // Registra o dispositivo imediatamente
                val payload = JSONObject().apply {
                    put("deviceName", deviceName)
                    existingId?.let { put("existingId", it) }
                }
                emit("register", payload)
            }

            on(Socket.EVENT_DISCONNECT) {
                Log.d(TAG, "Desconectado do servidor")
                _connectionState.tryEmit(ConnectionState.DISCONNECTED)
            }

            on(Socket.EVENT_CONNECT_ERROR) { args ->
                Log.e(TAG, "Erro de conexão: ${args.firstOrNull()}")
                _connectionState.tryEmit(ConnectionState.ERROR)
            }

            // ── Eventos do servidor ─────────────────────────────────────────
            on("registered") { args ->
                val data = args[0] as JSONObject
                val id = data.getString("deviceId")
                _deviceId.tryEmit(id)
                _events.tryEmit(SignalingEvent.Registered(id))
                Log.d(TAG, "Registrado com ID: $id")
            }

            on("incoming-request") { args ->
                val data = args[0] as JSONObject
                _events.tryEmit(
                    SignalingEvent.IncomingRequest(
                        requestId = data.getString("requestId"),
                        fromId = data.getString("fromId"),
                        fromName = data.getString("fromName")
                    )
                )
            }

            on("request-sent") { args ->
                val data = args[0] as JSONObject
                _events.tryEmit(SignalingEvent.RequestSent(data.getString("requestId")))
            }

            on("request-declined") { _ ->
                _events.tryEmit(SignalingEvent.RequestDeclined)
            }

            on("session-started") { args ->
                val data = args[0] as JSONObject
                _events.tryEmit(
                    SignalingEvent.SessionStarted(
                        sessionId = data.getString("sessionId"),
                        role = data.getString("role")
                    )
                )
            }

            on("webrtc-signal") { args ->
                val data = args[0] as JSONObject
                _events.tryEmit(
                    SignalingEvent.WebRTCSignal(
                        sessionId = data.getString("sessionId"),
                        type = data.getString("type"),
                        data = data.getJSONObject("data")
                    )
                )
            }

            on("session-ended") { args ->
                val data = args[0] as JSONObject
                _events.tryEmit(
                    SignalingEvent.SessionEnded(
                        sessionId = data.getString("sessionId"),
                        reason = data.getString("reason")
                    )
                )
            }

            on("error") { args ->
                val data = args[0] as JSONObject
                _events.tryEmit(
                    SignalingEvent.Error(
                        code = data.optString("code"),
                        message = data.optString("message")
                    )
                )
            }

            connect()
        }

        _connectionState.tryEmit(ConnectionState.CONNECTING)
    }

    // ── Solicitar conexão ─────────────────────────────────────────────────────
    fun requestConnection(targetId: String) {
        socket?.emit("request-connection", JSONObject().put("targetId", targetId))
    }

    // ── Responder solicitação ─────────────────────────────────────────────────
    fun respondToRequest(requestId: String, accepted: Boolean) {
        socket?.emit("respond-to-request", JSONObject().apply {
            put("requestId", requestId)
            put("accepted", accepted)
        })
    }

    // ── Sinalização WebRTC ────────────────────────────────────────────────────
    fun sendWebRTCSignal(sessionId: String, type: String, data: JSONObject) {
        socket?.emit("webrtc-signal", JSONObject().apply {
            put("sessionId", sessionId)
            put("type", type)
            put("data", data)
        })
    }

    // ── Encerrar sessão ───────────────────────────────────────────────────────
    fun endSession(sessionId: String) {
        socket?.emit("end-session", JSONObject().put("sessionId", sessionId))
    }

    // ── Desconectar ───────────────────────────────────────────────────────────
    fun disconnect() {
        socket?.disconnect()
        socket = null
        _connectionState.tryEmit(ConnectionState.DISCONNECTED)
    }
}

// ─── Tipos ────────────────────────────────────────────────────────────────────
enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

sealed class SignalingEvent {
    data class Registered(val deviceId: String) : SignalingEvent()
    data class IncomingRequest(val requestId: String, val fromId: String, val fromName: String) : SignalingEvent()
    data class RequestSent(val requestId: String) : SignalingEvent()
    object RequestDeclined : SignalingEvent()
    data class SessionStarted(val sessionId: String, val role: String) : SignalingEvent()
    data class WebRTCSignal(val sessionId: String, val type: String, val data: org.json.JSONObject) : SignalingEvent()
    data class SessionEnded(val sessionId: String, val reason: String) : SignalingEvent()
    data class Error(val code: String, val message: String) : SignalingEvent()
}
