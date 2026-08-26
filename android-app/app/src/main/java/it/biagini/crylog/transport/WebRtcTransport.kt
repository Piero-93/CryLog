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
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack

/**
 * Trasporto WebRTC fra un Nursery Node e i Parent Node che lo ascoltano.
 *
 * Chi ha il media fa l'offerta: il Nursery Node. Il Parent chiede e risponde.
 * L'alternativa — Parent che offre — costringerebbe a rinegoziare ogni volta
 * che cambia cosa il Nursery può dare.
 *
 * **Un Nursery serve più ascoltatori insieme.** WebRTC non sa mandare lo stesso
 * flusso a più destinatari da una connessione sola: serve una `PeerConnection`
 * per ascoltatore, ognuna con la sua negoziazione e i suoi candidati. Il
 * microfono e la fotocamera però restano uno solo, condivisi da tutte: la
 * fotocamera non si apre due volte, e aprire due volte il microfono sarebbe
 * comunque uno spreco visto che il suono è lo stesso.
 *
 * Il costo è la banda in salita del telefono in cameretta, che si moltiplica
 * per il numero di ascoltatori: oltre [MAX_LISTENERS] non se ne accettano
 * altri, perché un video a scatti per quattro è peggio di un video pulito per
 * tre e un rifiuto onesto al quarto.
 *
 * Nessun server ICE configurato: i telefoni si vedono sulla tailnet, quindi i
 * candidati locali bastano. Perché quelli della tailnet compaiano davvero serve
 * però la configurazione in [WebRtcFactory].
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
    /** Avvisa il Nursery Node che qualcuno sta parlando. */
    private val onTalkBack: (Boolean) -> Unit = {},
    /** Quanti Parent Node stanno ascoltando in questo momento. */
    private val onListeners: (Int) -> Unit = {},
) : StreamTransport {

    private val camera = CameraSource(context)

    private val _state = MutableStateFlow<TransportState>(TransportState.Idle)
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    /**
     * Una sessione verso un capo, con tutto quello che le appartiene.
     *
     * I candidati stanno qui e non in un campo unico: arrivano intrecciati da
     * ascoltatori diversi, e applicare quelli di uno alla connessione di un
     * altro darebbe un guasto difficilissimo da leggere.
     */
    private class Listener(val id: String) {
        var connection: PeerConnection? = null

        /**
         * I candidati arrivati prima della descrizione remota.
         *
         * WebRTC li rifiuta se applicati troppo presto, e l'ordine di arrivo
         * non è garantito: vanno tenuti da parte e versati dopo.
         */
        val pending = mutableListOf<IceCandidate>()

        var talking = false
        var wantsVideo = false
    }

    /** In ordine di arrivo: il primo che ha chiesto è il primo della lista. */
    private val listeners = LinkedHashMap<String, Listener>()

    // --- Media del Nursery Node: uno solo, per tutti gli ascoltatori ---
    private var roomAudio: AudioTrack? = null
    private var roomVideo: VideoTrack? = null

    // --- Media del Parent Node, che di sessioni ne ha sempre una ---
    private var talkBackAudio: AudioTrack? = null
    private var remoteAudio: AudioTrack? = null

    /** Ricordato dalla richiesta: serve quando arriva l offerta, non prima. */
    private var wantTalkBack = false

    override suspend fun start(request: StreamRequest): Result<Unit> = runCatching {
        stop()
        _state.value = TransportState.Connecting

        when (role) {
            // Il Parent chiede e aspetta: sarà il Nursery a proporre.
            Role.PARENT -> {
                wantTalkBack = request.talkBack
                listeners[request.peerId] = Listener(request.peerId)
                Log.i(TAG, "richiesta: video=${request.video} talkBack=${request.talkBack}")
                send(request.peerId, SignalPayload.Request(request.video, request.talkBack))
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
                // Chi rifà la richiesta sta riaprendo la sua sessione, non ne
                // apre una seconda: la vecchia va chiusa, o resterebbe a
                // consumare banda verso un capo che non ascolta più.
                if (listeners.containsKey(fromPeerId)) {
                    closeListener(fromPeerId, notify = false)
                } else if (listeners.size >= MAX_LISTENERS) {
                    Log.i(TAG, "richiesta rifiutata: gia $MAX_LISTENERS ascoltatori")
                    sendSignal(fromPeerId, SignalPayload.Busy.encode())
                    return
                }
                answerRequest(fromPeerId, parsed)
            }

            SignalPayload.Busy -> {
                _state.value = TransportState.Failed(BUSY_REASON)
                listeners.clear()
            }

            is SignalPayload.Offer -> if (role == Role.PARENT) {
                acceptOffer(fromPeerId, parsed)
            }

            is SignalPayload.Answer -> applyAnswer(fromPeerId, parsed)

            is SignalPayload.Ice -> addCandidate(fromPeerId, parsed)

            is SignalPayload.Talk -> if (role == Role.NURSERY) {
                Log.i(TAG, "$fromPeerId ${if (parsed.on) "sta parlando" else "ha chiuso"}")
                listeners[fromPeerId]?.talking = parsed.on
                refreshTalkBack()
            }

            // Chiude solo chi ha salutato: gli altri stanno ancora ascoltando.
            SignalPayload.Stop -> closeListener(fromPeerId, notify = false)
        }
    }

    /** Il Nursery Node prepara la sessione per un ascoltatore e propone quello che ha. */
    private fun answerRequest(peerId: String, request: SignalPayload.Request) {
        val factory = WebRtcFactory.get(context)
        val listener = Listener(peerId)
        val pc = createConnection(listener) ?: return
        listeners[peerId] = listener

        // Il microfono si apre una volta sola e la sua traccia va a tutti: una
        // seconda cattura sullo stesso microfono è fragile, e comunque inutile
        // visto che il suono sarebbe identico.
        val audio = roomAudio ?: factory
            .createAudioTrack("audio", factory.createAudioSource(MediaConstraints()))
            .also { roomAudio = it }
        pc.addTrack(audio, listOf(STREAM_ID))

        // Il video ha due interruttori indipendenti: quello del Parent Node che
        // chiede, e quello globale del Nursery. Se il secondo è chiuso, non si
        // offre video a nessuno, qualunque cosa arrivi dall'altro capo.
        listener.wantsVideo = request.video && !audioOnly()
        val video = if (listener.wantsVideo) openCamera(factory) else null
        video?.let { pc.addTrack(it, listOf(STREAM_ID)) }

        pc.createOffer(
            object : SdpAdapter("createOffer") {
                override fun onCreateSuccess(description: SessionDescription) {
                    Log.i(TAG, "offerta a $peerId, audio ${audioDirection(description.description)}")
                    pc.setLocalDescription(SdpAdapter("setLocal"), description)
                    send(
                        peerId,
                        SignalPayload.Offer(description.description, hasVideo = video != null),
                    )
                }
            },
            MediaConstraints(),
        )

        refreshListeners()
    }

    /**
     * Accende la fotocamera, o riusa quella già accesa.
     *
     * Una sola per tutti: il secondo ascoltatore che chiede il video riceve la
     * stessa traccia del primo, perché la fotocamera non si apre due volte.
     */
    private fun openCamera(factory: PeerConnectionFactory): VideoTrack? {
        roomVideo?.let { return it }

        // Prima si dichiara l'uso della fotocamera, poi la si apre: al
        // contrario Android troverebbe una fotocamera attiva su un servizio
        // che non l'ha dichiarata, e chiuderebbe tutto.
        onCameraInUse(true)
        val track = camera.start(factory, WebRtcFactory.eglBase.eglBaseContext)
        if (track == null) onCameraInUse(false)
        roomVideo = track
        return track
    }

    /** Il Parent Node accetta la proposta e risponde. */
    private fun acceptOffer(peerId: String, offer: SignalPayload.Offer) {
        val listener = listeners[peerId] ?: Listener(peerId).also { listeners[peerId] = it }
        val pc = createConnection(listener) ?: return

        // La traccia per il talk-back si aggiunge qui, prima della risposta:
        // aggiungerla dopo costringerebbe a rinegoziare la sessione. Parte
        // silenziata, e il pulsante "Parla" si limita ad accenderla.
        if (wantTalkBack) {
            Log.i(TAG, "aggiungo la traccia per il talk-back")
            val factory = WebRtcFactory.get(context)
            val source = factory.createAudioSource(MediaConstraints())
            talkBackAudio = factory.createAudioTrack("talkback", source).also {
                it.setEnabled(false)
                pc.addTrack(it, listOf(STREAM_ID))
            }
        }

        pc.setRemoteDescription(
            object : SdpAdapter("setRemote") {
                override fun onSetSuccess() {
                    drainCandidates(listener)
                    pc.createAnswer(
                        object : SdpAdapter("createAnswer") {
                            override fun onCreateSuccess(description: SessionDescription) {
                                Log.i(
                                    TAG,
                                    "risposta, audio ${audioDirection(description.description)}",
                                )
                                pc.setLocalDescription(SdpAdapter("setLocal"), description)
                                send(peerId, SignalPayload.Answer(description.description))
                            }
                        },
                        MediaConstraints(),
                    )
                }
            },
            SessionDescription(SessionDescription.Type.OFFER, offer.sdp),
        )
    }

    private fun applyAnswer(peerId: String, answer: SignalPayload.Answer) {
        val listener = listeners[peerId] ?: return
        Log.i(TAG, "risposta da $peerId, audio ${audioDirection(answer.sdp)}")
        listener.connection?.setRemoteDescription(
            object : SdpAdapter("setRemote") {
                override fun onSetSuccess() = drainCandidates(listener)
            },
            SessionDescription(SessionDescription.Type.ANSWER, answer.sdp),
        )
    }

    private fun addCandidate(peerId: String, ice: SignalPayload.Ice) {
        val listener = listeners[peerId] ?: return
        val candidate = IceCandidate(ice.sdpMid, ice.sdpMLineIndex, ice.candidate)
        val pc = listener.connection

        if (pc?.remoteDescription == null) {
            listener.pending += candidate
            return
        }
        pc.addIceCandidate(candidate)
    }

    private fun drainCandidates(listener: Listener) {
        val pc = listener.connection ?: return
        listener.pending.forEach(pc::addIceCandidate)
        listener.pending.clear()
    }

    private fun createConnection(listener: Listener): PeerConnection? {
        val configuration = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            // Su una tailnet i due capi si raggiungono direttamente: raccogliere
            // candidati oltre a quelli locali sarebbe tempo perso.
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        val pc = WebRtcFactory.get(context)
            .createPeerConnection(configuration, Observer(listener))
        if (pc == null) {
            _state.value = TransportState.Failed("connessione non creata")
            return null
        }
        listener.connection = pc
        return pc
    }

    override suspend fun setVideoEnabled(enabled: Boolean) {
        // Il video si decide alla richiesta: cambiarlo a sessione aperta
        // vorrebbe dire rinegoziare con ogni ascoltatore.
    }

    override suspend fun setPlaybackEnabled(enabled: Boolean) {
        remoteAudio?.setEnabled(enabled)
    }

    override suspend fun setTalkBackEnabled(enabled: Boolean) {
        Log.i(TAG, "talk-back $enabled, traccia=${talkBackAudio != null}")
        talkBackAudio?.setEnabled(enabled)
        // Il Nursery sospende il rilevamento mentre parli: altrimenti il suo
        // microfono risentirebbe la tua voce dallo speaker e la segnalerebbe
        // come rumore in cameretta.
        listeners.keys.forEach { send(it, SignalPayload.Talk(enabled)) }
    }

    override suspend fun stop() {
        if (listeners.isNotEmpty()) Log.i(TAG, "chiusura di ${listeners.size} sessioni")
        listeners.keys.toList().forEach { closeListener(it, notify = true) }

        wantTalkBack = false
        talkBackAudio = null
        remoteAudio = null
        roomAudio = null
        onRemoteVideo(null)
        onRemoteAudio(null)
        StreamLevel.reset()
        _state.value = TransportState.Idle
    }

    /**
     * Chiude una sessione sola, lasciando in piedi le altre.
     *
     * [notify] distingue chi se ne va da chi viene mandato via: a chi ha già
     * salutato non serve rispondere, a chi non lo sa ancora sì.
     */
    private fun closeListener(peerId: String, notify: Boolean) {
        val listener = listeners.remove(peerId) ?: return
        if (notify) send(peerId, SignalPayload.Stop)

        listener.connection?.dispose()
        listener.connection = null
        listener.pending.clear()

        refreshTalkBack()
        releaseCameraIfUnused()
        refreshListeners()
    }

    /** Il rilevamento tace se **almeno uno** sta parlando, non solo l'ultimo. */
    private fun refreshTalkBack() {
        onTalkBack(listeners.values.any { it.talking })
    }

    /** La fotocamera resta accesa finché almeno un ascoltatore vuole il video. */
    private fun releaseCameraIfUnused() {
        if (listeners.values.any { it.wantsVideo }) return
        if (!camera.isRunning) return

        camera.stop()
        roomVideo = null
        onCameraInUse(false)
    }

    private fun refreshListeners() {
        onListeners(listeners.size)
        if (role != Role.NURSERY) return

        // Sul Nursery lo stato è la somma degli ascoltatori: finché ne resta
        // uno la trasmissione è in corso, e quando esce l'ultimo si torna in
        // attesa. Con una sessione sola questa distinzione non serviva.
        _state.value = if (listeners.isEmpty()) {
            TransportState.Idle
        } else {
            TransportState.Streaming(video = roomVideo != null)
        }
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

    private fun send(peerId: String, payload: SignalPayload) {
        sendSignal(peerId, payload.encode())
    }

    private inner class Observer(private val listener: Listener) : PeerConnection.Observer {

        override fun onIceCandidate(candidate: IceCandidate) {
            // Quali interfacce WebRTC offre davvero: distingue una tailnet che
            // non viene enumerata da una che c e ma non instrada.
            Log.i(TAG, "candidato: ${candidate.sdp}")
            send(
                listener.id,
                SignalPayload.Ice(candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex),
            )
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            Log.i(TAG, "connessione con ${listener.id}: $newState")

            if (role == Role.NURSERY) {
                when (newState) {
                    // Un ascoltatore che cade libera il suo posto: tenerlo
                    // occupato impedirebbe a un altro di entrare, e il suo
                    // "sto parlando" non arriverebbe mai a spegnersi.
                    PeerConnection.PeerConnectionState.FAILED,
                    PeerConnection.PeerConnectionState.DISCONNECTED,
                    -> {
                        listener.talking = false
                        listener.wantsVideo = false
                        closeListener(listener.id, notify = false)
                    }

                    else -> refreshListeners()
                }
                return
            }

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

            remoteAudio = track
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
        const val BUSY_REASON = "Il Nursery Node sta gia trasmettendo al massimo dei dispositivi"

        /**
         * Quanti ascoltatori insieme.
         *
         * Senza un SFU ogni ascoltatore è un flusso in salita in più dal
         * telefono in cameretta. Oltre questo numero l'upload satura e la
         * qualità peggiora per tutti: meglio un rifiuto onesto al quarto che un
         * video a scatti per tre.
         */
        const val MAX_LISTENERS = 3
    }
}
