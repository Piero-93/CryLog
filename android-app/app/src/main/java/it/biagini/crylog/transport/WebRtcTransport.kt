package it.biagini.crylog.transport

import android.content.Context
import android.util.Log
import it.biagini.crylog.core.Role
import it.biagini.crylog.core.StreamRequest
import it.biagini.crylog.core.StreamTransport
import it.biagini.crylog.core.TransportState
import it.biagini.crylog.parent.StreamLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack

/**
 * Trasporto WebRTC fra un Nursery Node e un Parent Node.
 *
 * Chi ha il media fa l'offerta: il Nursery Node. Il Parent chiede e risponde.
 * L'alternativa — Parent che offre — costringerebbe a rinegoziare ogni volta
 * che cambia cosa il Nursery può dare.
 *
 * Nessun server ICE configurato: i due telefoni si vedono direttamente sulla
 * tailnet, quindi i candidati locali bastano e non serve rivolgersi a nessuno.
 */
class WebRtcTransport(
    private val context: Context,
    private val role: Role,
    private val sendSignal: (peerId: String, payload: String) -> Unit,
    private val onRemoteAudio: (AudioTrack?) -> Unit = {},
    private val onRemoteVideo: (VideoTrack?) -> Unit = {},
    /** Interruttore globale del Nursery Node: se chiuso non si offre video. */
    private val audioOnly: () -> Boolean = { false },
    /** Avvisa quando la fotocamera si apre o si chiude, per il tipo del servizio. */
    private val onCameraInUse: (Boolean) -> Unit = {},
    /** Avvisa il Nursery Node che il genitore sta parlando. */
    private val onTalkBack: (Boolean) -> Unit = {},
) : StreamTransport {

    private val camera = CameraSource(context)

    private val _state = MutableStateFlow<TransportState>(TransportState.Idle)
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    private var connection: PeerConnection? = null
    private var peerId: String? = null
    private var localAudio: AudioTrack? = null

    /** Ricordato dalla richiesta: serve quando arriva l offerta, non prima. */
    private var wantTalkBack = false

    /**
     * I candidati che arrivano prima della descrizione remota non possono
     * essere applicati: WebRTC li rifiuta. Vanno tenuti da parte, perché
     * l'ordine di arrivo non è garantito.
     */
    private val pendingCandidates = mutableListOf<IceCandidate>()

    override suspend fun start(request: StreamRequest): Result<Unit> = runCatching {
        stop()
        peerId = request.peerId
        _state.value = TransportState.Connecting

        when (role) {
            // Il Parent chiede e aspetta: sarà il Nursery a proporre.
            Role.PARENT -> {
                wantTalkBack = request.talkBack
                Log.i(TAG, "richiesta: video=${request.video} talkBack=${request.talkBack}")
                send(SignalPayload.Request(request.video, request.talkBack))
            }

            // Un Nursery non apre sessioni di sua iniziativa: trasmette solo a
            // chi ha chiesto, così non consuma nulla quando nessuno guarda.
            Role.NURSERY -> error("il Nursery Node non avvia lo stream, risponde soltanto")
        }
    }

    override suspend fun onSignal(fromPeerId: String, payload: String) {
        val parsed = SignalPayload.parse(payload) ?: run {
            Log.w(TAG, "signaling non riconosciuto, ignorato")
            return
        }

        when (parsed) {
            is SignalPayload.Request -> if (role == Role.NURSERY) {
                // Una sessione alla volta. Accettare la seconda scollegherebbe
                // il primo ascoltatore senza avvisarlo: meglio dire di no.
                if (connection != null && peerId != null && peerId != fromPeerId) {
                    Log.i(TAG, "richiesta rifiutata: gia in ascolto con $peerId")
                    sendSignal(fromPeerId, SignalPayload.Busy.encode())
                    return
                }
                peerId = fromPeerId
                answerRequest(parsed)
            }

            SignalPayload.Busy -> {
                _state.value = TransportState.Failed(BUSY_REASON)
                peerId = null
            }

            is SignalPayload.Offer -> if (role == Role.PARENT) {
                peerId = fromPeerId
                acceptOffer(parsed)
            }

            is SignalPayload.Answer -> applyAnswer(parsed)

            is SignalPayload.Ice -> addCandidate(parsed)

            is SignalPayload.Talk -> if (role == Role.NURSERY) {
                Log.i(TAG, "il genitore ${if (parsed.on) "sta parlando" else "ha chiuso"}")
                onTalkBack(parsed.on)
            }

            SignalPayload.Stop -> stop()
        }
    }

    /** Il Nursery Node prepara la sessione e propone quello che ha. */
    private fun answerRequest(request: SignalPayload.Request) {
        _state.value = TransportState.Connecting
        val factory = WebRtcFactory.get(context)
        val pc = createConnection() ?: return

        val source = factory.createAudioSource(MediaConstraints())
        localAudio = factory.createAudioTrack("audio", source).also {
            pc.addTrack(it, listOf(STREAM_ID))
        }

        // Il video ha due interruttori indipendenti: quello del Parent Node che
        // chiede, e quello globale del Nursery. Se il secondo è chiuso, non si
        // offre video a nessuno, qualunque cosa arrivi dall'altro capo.
        val wantsVideo = request.video && !audioOnly()
        val videoTrack = if (wantsVideo) {
            // Prima si dichiara l'uso della fotocamera, poi la si apre: al
            // contrario Android troverebbe una fotocamera attiva su un servizio
            // che non l'ha dichiarata, e chiuderebbe tutto.
            onCameraInUse(true)
            camera.start(factory, WebRtcFactory.eglBase.eglBaseContext)
                ?: run {
                    onCameraInUse(false)
                    null
                }
        } else {
            null
        }
        videoTrack?.let { pc.addTrack(it, listOf(STREAM_ID)) }

        pc.createOffer(
            object : SdpAdapter("createOffer") {
                override fun onCreateSuccess(description: SessionDescription) {
                    Log.i(TAG, "offerta, audio ${audioDirection(description.description)}")
                    pc.setLocalDescription(SdpAdapter("setLocal"), description)
                    send(SignalPayload.Offer(description.description, hasVideo = videoTrack != null))
                }
            },
            MediaConstraints(),
        )
    }

    /** Il Parent Node accetta la proposta e risponde. */
    private fun acceptOffer(offer: SignalPayload.Offer) {
        val pc = createConnection() ?: return

        // La traccia per il talk-back si aggiunge qui, prima della risposta:
        // aggiungerla dopo costringerebbe a rinegoziare la sessione. Parte
        // silenziata, e il pulsante "Parla" si limita ad accenderla.
        if (wantTalkBack) {
            Log.i(TAG, "aggiungo la traccia per il talk-back")
            val factory = WebRtcFactory.get(context)
            val source = factory.createAudioSource(MediaConstraints())
            localAudio = factory.createAudioTrack("talkback", source).also {
                it.setEnabled(false)
                pc.addTrack(it, listOf(STREAM_ID))
            }
        }

        pc.setRemoteDescription(
            object : SdpAdapter("setRemote") {
                override fun onSetSuccess() {
                    drainCandidates()
                    pc.createAnswer(
                        object : SdpAdapter("createAnswer") {
                            override fun onCreateSuccess(description: SessionDescription) {
                                Log.i(TAG, "risposta, audio ${audioDirection(description.description)}")
                                pc.setLocalDescription(SdpAdapter("setLocal"), description)
                                send(SignalPayload.Answer(description.description))
                            }
                        },
                        MediaConstraints(),
                    )
                }
            },
            SessionDescription(SessionDescription.Type.OFFER, offer.sdp),
        )
    }

    private fun applyAnswer(answer: SignalPayload.Answer) {
        Log.i(TAG, "risposta ricevuta, audio ${audioDirection(answer.sdp)}")
        connection?.setRemoteDescription(
            object : SdpAdapter("setRemote") {
                override fun onSetSuccess() = drainCandidates()
            },
            SessionDescription(SessionDescription.Type.ANSWER, answer.sdp),
        )
    }

    private fun addCandidate(ice: SignalPayload.Ice) {
        val candidate = IceCandidate(ice.sdpMid, ice.sdpMLineIndex, ice.candidate)
        val pc = connection

        if (pc?.remoteDescription == null) {
            pendingCandidates += candidate
            return
        }
        pc.addIceCandidate(candidate)
    }

    private fun drainCandidates() {
        val pc = connection ?: return
        pendingCandidates.forEach(pc::addIceCandidate)
        pendingCandidates.clear()
    }

    private fun createConnection(): PeerConnection? {
        val configuration = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            // Su una tailnet i due capi si raggiungono direttamente: raccogliere
            // candidati oltre a quelli locali sarebbe tempo perso.
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        val pc = WebRtcFactory.get(context).createPeerConnection(configuration, Observer())
        if (pc == null) {
            _state.value = TransportState.Failed("connessione non creata")
            return null
        }
        connection = pc
        return pc
    }

    override suspend fun setVideoEnabled(enabled: Boolean) {
        // Il video arriva nel passo successivo: per ora la sessione è solo audio.
    }

    override suspend fun setTalkBackEnabled(enabled: Boolean) {
        Log.i(TAG, "talk-back $enabled, traccia=${localAudio != null}")
        localAudio?.setEnabled(enabled)
        // Il Nursery sospende il rilevamento mentre parli: altrimenti il suo
        // microfono risentirebbe la tua voce dallo speaker e la segnalerebbe
        // come rumore in cameretta.
        send(SignalPayload.Talk(enabled))
    }

    override suspend fun stop() {
        peerId?.let { send(SignalPayload.Stop) }

        onTalkBack(false)
        wantTalkBack = false
        if (camera.isRunning) {
            camera.stop()
            onCameraInUse(false)
        }
        connection?.dispose()
        connection = null
        localAudio = null
        onRemoteVideo(null)
        pendingCandidates.clear()
        peerId = null
        onRemoteAudio(null)
        StreamLevel.reset()
        _state.value = TransportState.Idle
    }

    /**
     * La direzione della sezione audio dell SDP.
     *
     * Distingue un talk-back che non e stato negoziato da uno negoziato che non
     * si sente: sono due guasti diversi e dai log sembrano identici.
     */
    private fun audioDirection(sdp: String): String {
        var inAudio = false
        for (line in sdp.lineSequence()) {
            if (line.startsWith("m=")) inAudio = line.startsWith("m=audio")
            if (inAudio && line.startsWith("a=") && line.drop(2).trimEnd() in DIRECTIONS) {
                return line.drop(2).trimEnd()
            }
        }
        return "?"
    }

    private fun send(payload: SignalPayload) {
        val target = peerId ?: return
        sendSignal(target, payload.encode())
    }

    private inner class Observer : PeerConnection.Observer {

        override fun onIceCandidate(candidate: IceCandidate) {
            send(SignalPayload.Ice(candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex))
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            Log.i(TAG, "connessione: $newState")
            _state.value = when (newState) {
                PeerConnection.PeerConnectionState.CONNECTED ->
                    TransportState.Streaming(video = false)

                PeerConnection.PeerConnectionState.CONNECTING -> TransportState.Connecting

                // Caduta la connessione il "sto parlando" non arriverebbe mai:
                // senza questo il Nursery resterebbe sordo a tempo indeterminato.
                PeerConnection.PeerConnectionState.FAILED -> {
                    onTalkBack(false)
                    TransportState.Failed("connessione fallita")
                }

                PeerConnection.PeerConnectionState.DISCONNECTED -> {
                    onTalkBack(false)
                    TransportState.Failed("connessione persa")
                }

                else -> _state.value
            }
        }

        override fun onTrack(transceiver: org.webrtc.RtpTransceiver) {
            val track = transceiver.receiver?.track()
            if (track is VideoTrack) {
                onRemoteVideo(track)
                _state.value = TransportState.Streaming(video = true)
                return
            }
            if (track !is AudioTrack) return
            Log.i(TAG, "traccia audio remota ricevuta")

            // I campioni servono solo a misurare quanto sta arrivando: la
            // riproduzione la fa WebRTC per conto suo.
            track.addSink { audioData, bitsPerSample, _, channels, frames, _ ->
                if (bitsPerSample == 16) {
                    val samples = ShortArray(frames * channels)
                    audioData.order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        .asShortBuffer()
                        .get(samples, 0, minOf(samples.size, audioData.remaining() / 2))
                    StreamLevel.push(samples, samples.size)
                }
            }

            onRemoteAudio(track)
        }

        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onDataChannel(channel: DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) = Unit
    }

    /** WebRTC pretende quattro callback anche quando ne interessa una. */
    private open inner class SdpAdapter(private val what: String) : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String) {
            Log.e(TAG, "$what fallito: $error")
            _state.value = TransportState.Failed(error)
        }
        override fun onSetFailure(error: String) {
            Log.e(TAG, "$what fallito: $error")
            _state.value = TransportState.Failed(error)
        }
    }

    private companion object {
        val DIRECTIONS = setOf("sendrecv", "sendonly", "recvonly", "inactive")
        const val TAG = "CryLogStream"
        const val STREAM_ID = "crylog"
        const val BUSY_REASON = "Il Nursery Node sta gia trasmettendo a un altro dispositivo"
    }
}
