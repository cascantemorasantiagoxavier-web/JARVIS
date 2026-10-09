package com.example.jarvis.ai.realtime

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.nio.ByteBuffer

class OpenAiWebRtcRealtimeClient(
    context: Context
) : RealtimeClient {

    private val appContext = context.applicationContext

    private val events = MutableSharedFlow<RealtimeEvent>(
        replay = 0,
        extraBufferCapacity = 64
    )

    override suspend fun commitAndCreateResponse() {}

    private val httpClient = OkHttpClient()

    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var dataChannel: DataChannel? = null

    private var audioSource: org.webrtc.AudioSource? = null
    private var localAudioTrack: AudioTrack? = null

    private var currentState = RealtimeConnectionState.DISCONNECTED

    override fun events(): Flow<RealtimeEvent> = events.asSharedFlow()

    override suspend fun connect(session: RealtimeSessionConfig) {
        try {
            changeState(RealtimeConnectionState.CONNECTING)

            initializeWebRtc()

            val peerFactory = factory
                ?: throw IllegalStateException("WebRTC factory was not initialized")

            val configuration = PeerConnection.RTCConfiguration(
                listOf(
                    PeerConnection.IceServer
                        .builder("stun:stun.l.google.com:19302")
                        .createIceServer()
                )
            ).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            }

            val iceGatheringComplete = CompletableDeferred<Unit>()

            val observer = createPeerConnectionObserver(
                iceGatheringComplete = iceGatheringComplete
            )

            val connection = peerFactory.createPeerConnection(
                configuration,
                observer
            ) ?: throw IllegalStateException(
                "Could not create WebRTC PeerConnection"
            )

            peerConnection = connection

            createLocalAudioTrack(peerFactory, connection)

            val channel = connection.createDataChannel(
                "oai-events",
                DataChannel.Init()
            )

            dataChannel = channel
            channel.registerObserver(createDataChannelObserver())

            val offer = createOffer(connection)

            connection.setLocalDescription(
                SimpleSdpObserver(
                    onSetSuccess = {}
                ),
                offer
            )

            withTimeoutOrNull(5000L) {
                iceGatheringComplete.await()
            }

            val answerSdp = postSdpToOpenAi(
                offerSdp = connection.localDescription?.description
                    ?: offer.description,
                clientSecret = session.token
            )

            val answer = SessionDescription(
                SessionDescription.Type.ANSWER,
                answerSdp
            )

            connection.setRemoteDescription(
                SimpleSdpObserver(
                    onSetSuccess = {
                        changeState(RealtimeConnectionState.LISTENING)
                    },
                    onSetFailure = { error ->
                        emitError(
                            code = -1,
                            message = "Failed to set remote SDP: $error"
                        )
                    }
                ),
                answer
            )

        } catch (t: Throwable) {
            emitError(
                code = -1,
                message = t.message ?: "WebRTC connection failed",
                throwable = t
            )

            changeState(RealtimeConnectionState.ERROR)

            throw t
        }
    }

    override suspend fun disconnect() {
        try {
            dataChannel?.unregisterObserver()

            dataChannel?.close()
            dataChannel = null

            peerConnection?.close()
            peerConnection?.dispose()
            peerConnection = null

            localAudioTrack?.dispose()
            localAudioTrack = null

            audioSource?.dispose()
            audioSource = null

            factory?.dispose()
            factory = null

            changeState(RealtimeConnectionState.DISCONNECTED)

        } catch (t: Throwable) {
            emitError(
                code = -1,
                message = t.message ?: "Failed to disconnect WebRTC",
                throwable = t
            )
        }
    }

    override suspend fun sendText(text: String) {
        val channel = dataChannel

        if (channel == null ||
            channel.state() != DataChannel.State.OPEN
        ) {
            emitError(
                code = -1,
                message = "Realtime data channel is not open"
            )
            return
        }

        try {
            val conversationItem = JSONObject()
                .put("type", "conversation.item.create")
                .put(
                    "item",
                    JSONObject()
                        .put("type", "message")
                        .put(
                            "role",
                            "user"
                        )
                        .put(
                            "content",
                            org.json.JSONArray()
                                .put(
                                    JSONObject()
                                        .put(
                                            "type",
                                            "input_text"
                                        )
                                        .put(
                                            "text",
                                            text
                                        )
                                )
                        )
                )

            sendEvent(conversationItem)

            val responseCreate = JSONObject()
                .put(
                    "type",
                    "response.create"
                )

            sendEvent(responseCreate)

        } catch (t: Throwable) {
            emitError(
                code = -1,
                message = t.message ?: "Failed to send text",
                throwable = t
            )
        }
    }

    override suspend fun sendAudio(audio: ByteArray) {
        emitError(
            code = -1,
            message = "WebRTC sends microphone audio through the media track"
        )
    }

    private fun initializeWebRtc() {
        if (factory != null) {
            return
        }

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions
                .builder(appContext)
                .createInitializationOptions()
        )

        factory = PeerConnectionFactory
            .builder()
            .createPeerConnectionFactory()
    }

    private fun createLocalAudioTrack(
        peerFactory: PeerConnectionFactory,
        connection: PeerConnection
    ) {
        val constraints = MediaConstraints()

        audioSource = peerFactory.createAudioSource(
            constraints
        )

        localAudioTrack = peerFactory.createAudioTrack(
            "JARVIS_LOCAL_AUDIO",
            audioSource
        )

        localAudioTrack?.setEnabled(true)

        connection.addTrack(
            localAudioTrack
                ?: throw IllegalStateException(
                    "Could not create local audio track"
                )
        )
    }

    private suspend fun createOffer(
        connection: PeerConnection
    ): SessionDescription {
        val deferred = CompletableDeferred<SessionDescription>()

        val constraints = MediaConstraints()

        connection.createOffer(
            object : SdpObserver {

                override fun onCreateSuccess(
                    description: SessionDescription?
                ) {
                    if (description != null) {
                        deferred.complete(description)
                    } else {
                        deferred.completeExceptionally(
                            IllegalStateException(
                                "WebRTC returned a null SDP offer"
                            )
                        )
                    }
                }

                override fun onSetSuccess() = Unit

                override fun onCreateFailure(
                    error: String?
                ) {
                    deferred.completeExceptionally(
                        IllegalStateException(
                            error ?: "Failed to create SDP offer"
                        )
                    )
                }

                override fun onSetFailure(
                    error: String?
                ) = Unit
            },
            constraints
        )

        return deferred.await()
    }

    private suspend fun postSdpToOpenAi(
        offerSdp: String,
        clientSecret: String
    ): String {
        val request = Request.Builder()
            .url(
                "https://api.openai.com/v1/realtime/calls"
            )
            .addHeader(
                "Authorization",
                "Bearer $clientSecret"
            )
            .addHeader(
                "Content-Type",
                "application/sdp"
            )
            .post(
                offerSdp.toRequestBody(
                    "application/sdp".toMediaType()
                )
            )
            .build()

        httpClient
            .newCall(request)
            .execute()
            .use { response ->

                val body = response.body?.string()
                    ?: ""

                if (!response.isSuccessful) {
                    throw IllegalStateException(
                        "OpenAI WebRTC error ${response.code}: $body"
                    )
                }

                return body
            }
    }

    private fun createPeerConnectionObserver(
        iceGatheringComplete: CompletableDeferred<Unit>
    ): PeerConnection.Observer {

        return object : PeerConnection.Observer {

            override fun onSignalingChange(
                newState: PeerConnection.SignalingState
            ) = Unit

            override fun onIceConnectionChange(
                newState: PeerConnection.IceConnectionState
            ) {
                when (newState) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> {
                        changeState(
                            RealtimeConnectionState.LISTENING
                        )
                    }

                    PeerConnection.IceConnectionState.FAILED -> {
                        emitError(
                            code = -1,
                            message = "WebRTC ICE connection failed"
                        )

                        changeState(
                            RealtimeConnectionState.ERROR
                        )
                    }

                    PeerConnection.IceConnectionState.DISCONNECTED -> {
                        changeState(
                            RealtimeConnectionState.DISCONNECTED
                        )
                    }

                    else -> Unit
                }
            }

            override fun onIceConnectionReceivingChange(
                receiving: Boolean
            ) = Unit

            override fun onIceGatheringChange(
                newState: PeerConnection.IceGatheringState
            ) {
                if (
                    newState ==
                    PeerConnection.IceGatheringState.COMPLETE
                ) {
                    iceGatheringComplete.complete(Unit)
                }
            }

            override fun onIceCandidate(
                candidate: IceCandidate
            ) = Unit

            override fun onIceCandidatesRemoved(
                candidates: Array<IceCandidate>
            ) = Unit

            override fun onAddStream(
                stream: MediaStream
            ) {
                stream.audioTracks.forEach {
                    it.setEnabled(true)
                }

                changeState(
                    RealtimeConnectionState.SPEAKING
                )
            }

            override fun onRemoveStream(
                stream: MediaStream
            ) = Unit

            override fun onDataChannel(
                channel: DataChannel
            ) {
                if (channel.label() == "oai-events") {
                    dataChannel = channel
                    channel.registerObserver(
                        createDataChannelObserver()
                    )
                }
            }

            override fun onRenegotiationNeeded() = Unit

            override fun onAddTrack(
                receiver: RtpReceiver,
                mediaStreams: Array<MediaStream>
            ) {
                val track = receiver.track()

                if (track is AudioTrack) {
                    track.setEnabled(true)

                    changeState(
                        RealtimeConnectionState.SPEAKING
                    )
                }
            }
        }
    }

    private fun createDataChannelObserver(): DataChannel.Observer {

        return object : DataChannel.Observer {

            override fun onBufferedAmountChange(
                previousAmount: Long
            ) = Unit

            override fun onStateChange() {
                when (dataChannel?.state()) {
                    DataChannel.State.OPEN -> {
                        changeState(
                            RealtimeConnectionState.LISTENING
                        )
                    }

                    DataChannel.State.CLOSING,
                    DataChannel.State.CLOSED -> {
                        changeState(
                            RealtimeConnectionState.DISCONNECTED
                        )
                    }

                    else -> Unit
                }
            }

            override fun onMessage(
                buffer: DataChannel.Buffer
            ) {
                try {
                    val bytes = ByteArray(
                        buffer.data.remaining()
                    )

                    buffer.data.get(bytes)

                    val json = JSONObject(
                        String(bytes, Charsets.UTF_8)
                    )

                    handleRealtimeEvent(json)

                } catch (t: Throwable) {
                    emitError(
                        code = -1,
                        message =
                            t.message
                                ?: "Failed to parse Realtime event",
                        throwable = t
                    )
                }
            }
        }
    }

    private fun handleRealtimeEvent(
        json: JSONObject
    ) {
        when (json.optString("type")) {

            "response.output_text.delta" -> {
                val text =
                    json.optString("delta")

                if (text.isNotEmpty()) {
                    events.tryEmit(
                        RealtimeEvent.TextReceived(text)
                    )
                }
            }

            "response.output_audio_transcript.delta" -> {
                val text =
                    json.optString("delta")

                if (text.isNotEmpty()) {
                    events.tryEmit(
                        RealtimeEvent.TextReceived(text)
                    )
                }
            }

            "input_audio_buffer.speech_started" -> {
                changeState(
                    RealtimeConnectionState.THINKING
                )
            }

            "input_audio_buffer.speech_stopped" -> {
                changeState(
                    RealtimeConnectionState.THINKING
                )
            }

            "response.created" -> {
                changeState(
                    RealtimeConnectionState.SPEAKING
                )
            }

            "response.done" -> {
                changeState(
                    RealtimeConnectionState.LISTENING
                )
            }

            "error" -> {
                val error =
                    json.optJSONObject("error")

                emitError(
                    code = -1,
                    message =
                        error?.optString("message")
                            ?: "Realtime API error"
                )

                changeState(
                    RealtimeConnectionState.ERROR
                )
            }
        }
    }

    private fun sendEvent(
        json: JSONObject
    ) {
        val channel = dataChannel
            ?: throw IllegalStateException(
                "Realtime data channel is not available"
            )

        if (channel.state() != DataChannel.State.OPEN) {
            throw IllegalStateException(
                "Realtime data channel is not open"
            )
        }

        val bytes = json
            .toString()
            .toByteArray(Charsets.UTF_8)

        channel.send(
            DataChannel.Buffer(
                ByteBuffer.wrap(bytes),
                false
            )
        )
    }

    private fun changeState(
        state: RealtimeConnectionState
    ) {
        currentState = state

        events.tryEmit(
            RealtimeEvent.StateChanged(state)
        )
    }

    private fun emitError(
        code: Int,
        message: String,
        throwable: Throwable? = null
    ) {
        events.tryEmit(
            RealtimeEvent.ErrorOccurred(
                RealtimeError(
                    code = code,
                    message = message,
                    throwable = throwable
                )
            )
        )
    }

    private class SimpleSdpObserver(
        private val onSetSuccess: () -> Unit = {},
        private val onSetFailure: (String?) -> Unit = {}
    ) : SdpObserver {

        override fun onCreateSuccess(
            description: SessionDescription?
        ) = Unit

        override fun onSetSuccess() {
            onSetSuccess()
        }

        override fun onCreateFailure(
            error: String?
        ) = Unit

        override fun onSetFailure(
            error: String?
        ) {
            onSetFailure(error)
        }
    }
}
