package it.biagini.crylog.core

import kotlinx.coroutines.flow.StateFlow

/** Cosa il Parent Node vuole ricevere da questa sessione. */
data class StreamRequest(
    /** L'altro capo: il Nursery Node se si sta guardando, il Parent se si sta trasmettendo. */
    val peerId: String,
    /** Il video è sempre facoltativo, e la scelta è di ogni Parent Node per sé. */
    val video: Boolean = false,
    /** Microfono del Parent Node verso la cameretta. */
    val talkBack: Boolean = false,
)

sealed interface TransportState {
    data object Idle : TransportState

    /** Negoziazione in corso: l'altro capo è stato contattato ma non risponde ancora. */
    data object Connecting : TransportState

    data class Streaming(
        val video: Boolean,
        /** Il Nursery Node trasmette solo audio: il video non è disponibile a monte. */
        val videoUnavailable: Boolean = false,
    ) : TransportState

    data class Failed(val reason: String) : TransportState
}

/**
 * Trasporto del flusso audio/video fra due dispositivi.
 *
 * Esiste come interfaccia perché il trasporto è la parte del sistema con più
 * probabilità di cambiare: oggi WebRTC, domani forse RTSP se WebRTC dovesse
 * deludere sul campo. La UI e la logica di dominio non devono accorgersene.
 *
 * Nessun metodo espone tipi di WebRTC, di proposito.
 */
interface StreamTransport {

    val state: StateFlow<TransportState>

    /** Apre una sessione verso [request].peerId. */
    suspend fun start(request: StreamRequest): Result<Unit>

    /** Cambia idea sul video a stream avviato, senza rinegoziare da capo. */
    suspend fun setVideoEnabled(enabled: Boolean)

    suspend fun setTalkBackEnabled(enabled: Boolean)

    /**
     * Silenzia o riattiva l audio in arrivo senza chiudere la sessione.
     *
     * Serve all AudioFocus: una telefonata deve zittire l ascolto e poi
     * restituirlo, e rinegoziare la sessione a ogni chiamata costerebbe secondi
     * di silenzio vero.
     */
    suspend fun setPlaybackEnabled(enabled: Boolean)

    suspend fun stop()

    /**
     * Consegna un messaggio di signaling arrivato dall'Hub.
     *
     * Il trasporto non conosce l'Hub né la rete: riceve buste e ne produce
     * altre, così può essere esercitato senza connessione.
     */
    suspend fun onSignal(fromPeerId: String, payload: String)
}
