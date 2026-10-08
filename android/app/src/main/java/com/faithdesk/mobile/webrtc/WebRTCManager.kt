package com.faithdesk.mobile.webrtc

import android.content.Context
import android.util.Log
import com.faithdesk.mobile.network.SignalingManager
import org.json.JSONObject
import org.webrtc.*

/**
 * Gerencia a conexão WebRTC peer-to-peer.
 * 
 * Fluxo:
 *  HOST (quem compartilha a tela):
 *    1. Cria PeerConnection
 *    2. Adiciona VideoTrack da MediaProjection
 *    3. Cria Offer → envia via sinalização
 *    4. Recebe Answer → setRemoteDescription
 *    5. Troca ICE candidates
 *
 *  CLIENTE (quem vê/controla):
 *    1. Cria PeerConnection
 *    2. Recebe Offer → setRemoteDescription
 *    3. Cria Answer → envia via sinalização
 *    4. Troca ICE candidates
 *    5. Recebe VideoTrack → exibe na SurfaceView
 */
class WebRTCManager(
    private val context: Context,
    private val sessionId: String,
    private val isHost: Boolean,
    private val onRemoteVideoTrack: ((VideoTrack) -> Unit)? = null,
    private val onConnectionStateChange: ((PeerConnection.IceConnectionState) -> Unit)? = null
) {
    private val TAG = "WebRTCManager"

    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var localVideoTrack: VideoTrack? = null
    private var videoCapturer: VideoCapturer? = null

    private val pendingCandidates = mutableListOf<IceCandidate>()
    private var remoteDescriptionSet = false

    // ── Configuração ICE (STUN + TURN) ────────────────────────────────────────
    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer()
        // Adicione TURN aqui se necessário:
        // PeerConnection.IceServer.builder("turn:seu-servidor.com:3478")
        //     .setUsername("faithdesk").setPassword("senha").createIceServer()
    )

    // ── Inicializar factory ───────────────────────────────────────────────────
    fun initialize() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )

        val options = PeerConnectionFactory.Options()
        factory = PeerConnectionFactory.builder()
            .setOptions(options)
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(EglBase.create().eglBaseContext))
            .setVideoEncoderFactory(
                DefaultVideoEncoderFactory(EglBase.create().eglBaseContext, true, true)
            )
            .createPeerConnectionFactory()

        createPeerConnection()
    }

    // ── Criar PeerConnection ──────────────────────────────────────────────────
    private fun createPeerConnection() {
        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
        }

        peerConnection = factory?.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                Log.d(TAG, "ICE candidate gerado")
                SignalingManager.sendWebRTCSignal(
                    sessionId, "ice-candidate",
                    JSONObject().apply {
                        put("sdpMid", candidate.sdpMid)
                        put("sdpMLineIndex", candidate.sdpMLineIndex)
                        put("candidate", candidate.sdp)
                    }
                )
            }

            override fun onTrack(transceiver: RtpTransceiver) {
                val track = transceiver.receiver.track()
                if (track is VideoTrack) {
                    Log.d(TAG, "VideoTrack remoto recebido")
                    onRemoteVideoTrack?.invoke(track)
                }
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                Log.d(TAG, "ICE Connection State: $state")
                onConnectionStateChange?.invoke(state)
            }

            override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
                Log.d(TAG, "Peer Connection State: $state")
            }

            // Callbacks obrigatórios (não usados ativamente)
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(dc: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
        })
    }

    // ── HOST: Adiciona VideoTrack da tela capturada ───────────────────────────
    fun addVideoTrack(track: VideoTrack) {
        localVideoTrack = track
        peerConnection?.addTrack(track, listOf("faithdesk-stream"))
    }

    // ── HOST: Criar e enviar Offer ────────────────────────────────────────────
    fun createOffer() {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
        }

        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        SignalingManager.sendWebRTCSignal(
                            sessionId, "offer",
                            JSONObject().apply {
                                put("type", sdp.type.canonicalForm())
                                put("sdp", sdp.description)
                            }
                        )
                        Log.d(TAG, "Offer enviado")
                    }
                    override fun onCreateFailure(error: String?) {}
                    override fun onSetFailure(error: String?) { Log.e(TAG, "SetLocal error: $error") }
                    override fun onCreateSuccess(sdp: SessionDescription?) {}
                }, sdp)
            }
            override fun onCreateFailure(error: String?) { Log.e(TAG, "CreateOffer error: $error") }
            override fun onSetSuccess() {}
            override fun onSetFailure(error: String?) {}
        }, constraints)
    }

    // ── CLIENTE: Receber Offer e criar Answer ─────────────────────────────────
    fun handleOffer(offerData: JSONObject) {
        val sdp = SessionDescription(
            SessionDescription.Type.fromCanonicalForm(offerData.getString("type")),
            offerData.getString("sdp")
        )

        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                remoteDescriptionSet = true
                addPendingCandidates()
                createAnswer()
            }
            override fun onSetFailure(error: String?) { Log.e(TAG, "SetRemote error: $error") }
            override fun onCreateSuccess(sdp: SessionDescription?) {}
            override fun onCreateFailure(error: String?) {}
        }, sdp)
    }

    private fun createAnswer() {
        peerConnection?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        SignalingManager.sendWebRTCSignal(
                            sessionId, "answer",
                            JSONObject().apply {
                                put("type", sdp.type.canonicalForm())
                                put("sdp", sdp.description)
                            }
                        )
                        Log.d(TAG, "Answer enviado")
                    }
                    override fun onCreateFailure(error: String?) {}
                    override fun onSetFailure(error: String?) { Log.e(TAG, "SetLocal error: $error") }
                    override fun onCreateSuccess(sdp: SessionDescription?) {}
                }, sdp)
            }
            override fun onCreateFailure(error: String?) { Log.e(TAG, "CreateAnswer error: $error") }
            override fun onSetSuccess() {}
            override fun onSetFailure(error: String?) {}
        }, MediaConstraints())
    }

    // ── HOST: Receber Answer ──────────────────────────────────────────────────
    fun handleAnswer(answerData: JSONObject) {
        val sdp = SessionDescription(
            SessionDescription.Type.fromCanonicalForm(answerData.getString("type")),
            answerData.getString("sdp")
        )
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                remoteDescriptionSet = true
                addPendingCandidates()
            }
            override fun onSetFailure(error: String?) { Log.e(TAG, "SetRemote error: $error") }
            override fun onCreateSuccess(sdp: SessionDescription?) {}
            override fun onCreateFailure(error: String?) {}
        }, sdp)
    }

    // ── Adicionar ICE Candidate ───────────────────────────────────────────────
    fun addIceCandidate(candidateData: JSONObject) {
        val candidate = IceCandidate(
            candidateData.getString("sdpMid"),
            candidateData.getInt("sdpMLineIndex"),
            candidateData.getString("candidate")
        )

        if (remoteDescriptionSet) {
            peerConnection?.addIceCandidate(candidate)
        } else {
            pendingCandidates.add(candidate)
        }
    }

    private fun addPendingCandidates() {
        pendingCandidates.forEach { peerConnection?.addIceCandidate(it) }
        pendingCandidates.clear()
    }

    // ── Encerrar ──────────────────────────────────────────────────────────────
    fun dispose() {
        localVideoTrack?.dispose()
        peerConnection?.close()
        peerConnection = null
        factory?.dispose()
        factory = null
    }
}
