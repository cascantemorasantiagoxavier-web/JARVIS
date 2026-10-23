package com.example.jarvis.voice

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import com.example.jarvis.core.net.BackendClient
import com.example.jarvis.core.net.BackendException
import com.example.jarvis.core.net.RealtimeCallRequest
import com.example.jarvis.core.net.RealtimeSessionRequest
import com.example.jarvis.core.net.await
import com.example.jarvis.core.realtime.RealtimeConnectRequest
import com.example.jarvis.core.realtime.RealtimeConnector
import com.example.jarvis.core.realtime.RealtimeProtocol
import com.example.jarvis.core.realtime.RealtimeTransport
import com.example.jarvis.core.realtime.TransportState
import com.example.jarvis.data.settings.ConnectMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.sqrt

/** Niveles de audio reales (0..1) del micrófono y de la voz de JARVIS, para animar el núcleo. */
class AudioLevels {
    private val _mic = MutableStateFlow(0f)
    val mic: StateFlow<Float> = _mic.asStateFlow()
    private val _output = MutableStateFlow(0f)
    val output: StateFlow<Float> = _output.asStateFlow()

    fun mic(v: Float) { _mic.value = _mic.value * 0.6f + v * 0.4f }
    fun output(v: Float) { _output.value = _output.value * 0.6f + v * 0.4f }
    fun reset() { _mic.value = 0f; _output.value = 0f }

    companion object {
        /** RMS normalizado de PCM 16 bits little-endian. */
        fun rms(pcm: ByteArray): Float {
            if (pcm.size < 2) return 0f
            val sb = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            var sum = 0.0
            val n = sb.remaining()
            for (i in 0 until n) { val s = sb.get(i) / 32768.0; sum += s * s }
            return (sqrt(sum / n) * 3.2).toFloat().coerceIn(0f, 1f)
        }
    }
}

/** Enruta el audio de la conversación al altavoz o a los auriculares conectados. */
class AudioRouting(context: Context) {
    private val am = context.getSystemService(AudioManager::class.java)
    private var previousMode = AudioManager.MODE_NORMAL

    fun start() {
        val audio = am ?: return
        previousMode = audio.mode
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        if (Build.VERSION.SDK_INT >= 31) {
            val devices = audio.availableCommunicationDevices
            val preferred = devices.firstOrNull { it.type in HEADSET_TYPES } ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            if (preferred != null) audio.setCommunicationDevice(preferred)
        } else {
            val headset = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in HEADSET_TYPES }
            @Suppress("DEPRECATION")
            audio.isSpeakerphoneOn = !headset
        }
    }

    fun stop() {
        val audio = am ?: return
        if (Build.VERSION.SDK_INT >= 31) {
            audio.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            audio.isSpeakerphoneOn = false
        }
        audio.mode = previousMode
    }

    companion object {
        private val HEADSET_TYPES = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_BLE_HEADSET,
        )
    }
}

private object WebRtcEnv {
    @Volatile private var initialized = false
    fun ensure(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions(),
            )
            initialized = true
        }
    }
}

/**
 * Conecta con OpenAI Realtime por WebRTC.
 *  - UNIFIED: la oferta SDP va al backend, que la negocia con OpenAI (sin credenciales en el teléfono).
 *  - EPHEMERAL: el backend entrega un client secret temporal y el teléfono negocia con /v1/realtime/calls.
 */
class WebRtcRealtimeConnector(
    private val context: Context,
    private val backend: BackendClient,
    private val http: OkHttpClient,
    private val mode: () -> ConnectMode,
    private val levels: AudioLevels,
) : RealtimeConnector {

    override suspend fun connect(request: RealtimeConnectRequest): RealtimeTransport {
        WebRtcEnv.ensure(context)
        val transport = withContext(Dispatchers.Default) { WebRtcTransport(context, levels) }
        try {
            val offer = transport.createOfferSdp()
            val answer: String
            when (mode()) {
                ConnectMode.UNIFIED -> {
                    val r = backend.realtimeCall(
                        RealtimeCallRequest(offer, request.model, request.voice, request.speed, request.instructions, request.maxSessionMinutes),
                    )
                    answer = r.sdp
                    transport.callId = r.callId
                    transport.onClosed = { id -> CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { runCatching { backend.realtimeHangup(id) } } }
                }
                ConnectMode.EPHEMERAL -> {
                    val secret = backend.realtimeSession(RealtimeSessionRequest(request.model, request.voice, request.speed, request.instructions))
                    answer = postOffer(secret.callsUrl, secret.clientSecret, offer)
                }
            }
            transport.applyAnswer(answer)
            transport.awaitOpen(15_000)
            return transport
        } catch (e: Throwable) {
            transport.close()
            throw e
        }
    }

    /** Intercambio SDP directo con OpenAI usando la credencial efímera (nunca la clave permanente). */
    private suspend fun postOffer(url: String, ephemeralKey: String, sdp: String): String {
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $ephemeralKey")
            .post(sdp.toRequestBody("application/sdp".toMediaType()))
            .build()
        val resp = http.newCall(req).await()
        return withContext(Dispatchers.IO) {
            resp.use {
                val body = it.body?.string().orEmpty()
                if (!it.isSuccessful || !body.startsWith("v=")) {
                    throw BackendException("realtime_sdp_failed", "OpenAI rechazó la conexión de voz (HTTP ${it.code}).", it.code)
                }
                body
            }
        }
    }
}

/** Una conexión WebRTC con audio bidireccional y el canal de datos de eventos. */
class WebRtcTransport(context: Context, private val levels: AudioLevels) : RealtimeTransport {
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 512)
    override val messages: Flow<String> = _messages
    private val _state = MutableStateFlow(TransportState.CONNECTING)
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    @Volatile var callId: String? = null
    @Volatile var onClosed: ((String) -> Unit)? = null
    @Volatile private var closed = false
    private val opened = CompletableDeferred<Unit>()
    private val gathered = CompletableDeferred<Unit>()

    private val adm: JavaAudioDeviceModule = JavaAudioDeviceModule.builder(context)
        .setUseHardwareAcousticEchoCanceler(JavaAudioDeviceModule.isBuiltInAcousticEchoCancelerSupported())
        .setUseHardwareNoiseSuppressor(JavaAudioDeviceModule.isBuiltInNoiseSuppressorSupported())
        .setSamplesReadyCallback { samples -> levels.mic(AudioLevels.rms(samples.data)) }
        .setPlaybackSamplesReadyCallback { samples -> levels.output(AudioLevels.rms(samples.data)) }
        .setAudioRecordErrorCallback(object : JavaAudioDeviceModule.AudioRecordErrorCallback {
            override fun onWebRtcAudioRecordInitError(errorMessage: String) = fail()
            override fun onWebRtcAudioRecordStartError(errorCode: JavaAudioDeviceModule.AudioRecordStartErrorCode, errorMessage: String) = fail()
            override fun onWebRtcAudioRecordError(errorMessage: String) = fail()
        })
        .createAudioDeviceModule()

    private val factory: PeerConnectionFactory = PeerConnectionFactory.builder().setAudioDeviceModule(adm).createPeerConnectionFactory()

    private val observer = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState) {}
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
            if (state == PeerConnection.IceGatheringState.COMPLETE) gathered.complete(Unit)
        }
        override fun onIceCandidate(candidate: IceCandidate) {}
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
        override fun onAddStream(stream: MediaStream) {}
        override fun onRemoveStream(stream: MediaStream) {}
        override fun onDataChannel(channel: DataChannel) {}
        override fun onRenegotiationNeeded() {}
        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            when (newState) {
                PeerConnection.PeerConnectionState.FAILED -> if (!closed) _state.value = TransportState.FAILED
                PeerConnection.PeerConnectionState.CLOSED -> if (!closed) _state.value = TransportState.CLOSED
                else -> {}
            }
        }
    }

    private val pc: PeerConnection = factory.createPeerConnection(
        PeerConnection.RTCConfiguration(emptyList()).apply { sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN },
        observer,
    ) ?: error("No se pudo crear la conexión WebRTC.")

    private val audioSource: AudioSource = factory.createAudioSource(MediaConstraints())
    private val audioTrack: AudioTrack = factory.createAudioTrack("jarvis-mic", audioSource)
    private val channel: DataChannel

    init {
        pc.addTrack(audioTrack, listOf("jarvis"))
        channel = pc.createDataChannel(RealtimeProtocol.DATA_CHANNEL, DataChannel.Init())
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}
            override fun onStateChange() {
                when (channel.state()) {
                    DataChannel.State.OPEN -> { _state.value = TransportState.OPEN; opened.complete(Unit) }
                    DataChannel.State.CLOSED -> if (!closed) _state.value = TransportState.CLOSED
                    else -> {}
                }
            }
            override fun onMessage(buffer: DataChannel.Buffer) {
                if (buffer.binary) return
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                _messages.tryEmit(String(bytes, Charsets.UTF_8))
            }
        })
    }

    private fun fail() {
        if (!closed) _state.value = TransportState.FAILED
    }

    suspend fun createOfferSdp(): String {
        val offer = suspendCancellableCoroutine<SessionDescription> { cont ->
            pc.createOffer(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription) = cont.resume(sdp)
                override fun onCreateFailure(error: String) = cont.resumeWithException(IllegalStateException("createOffer: $error"))
                override fun onSetSuccess() {}
                override fun onSetFailure(error: String) {}
            }, MediaConstraints())
        }
        setDescription(local = true, offer)
        // Reunir candidatos ICE (máx. 2,5 s); OpenAI no admite trickle ICE en este flujo.
        withTimeoutOrNull(2_500) { gathered.await() }
        return pc.localDescription?.description ?: offer.description
    }

    suspend fun applyAnswer(sdp: String) = setDescription(local = false, SessionDescription(SessionDescription.Type.ANSWER, sdp))

    private suspend fun setDescription(local: Boolean, sdp: SessionDescription) = suspendCancellableCoroutine<Unit> { cont ->
        val obs = object : SdpObserver {
            override fun onSetSuccess() = cont.resume(Unit)
            override fun onSetFailure(error: String) = cont.resumeWithException(IllegalStateException("setDescription: $error"))
            override fun onCreateSuccess(sdp: SessionDescription) {}
            override fun onCreateFailure(error: String) {}
        }
        if (local) pc.setLocalDescription(obs, sdp) else pc.setRemoteDescription(obs, sdp)
    }

    suspend fun awaitOpen(timeoutMs: Long) {
        withTimeoutOrNull(timeoutMs) { opened.await() } ?: throw IllegalStateException("El canal de datos de voz no se abrió a tiempo.")
    }

    override fun send(json: String): Boolean {
        if (closed || channel.state() != DataChannel.State.OPEN) return false
        return channel.send(DataChannel.Buffer(ByteBuffer.wrap(json.toByteArray(Charsets.UTF_8)), false))
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        withContext(Dispatchers.Default) {
            runCatching { channel.unregisterObserver() }
            runCatching { channel.close() }
            runCatching { channel.dispose() }
            runCatching { pc.dispose() }
            runCatching { audioTrack.dispose() }
            runCatching { audioSource.dispose() }
            runCatching { factory.dispose() }
            runCatching { adm.release() }
        }
        levels.reset()
        _state.value = TransportState.CLOSED
        callId?.let { id -> onClosed?.invoke(id) }
    }
}
