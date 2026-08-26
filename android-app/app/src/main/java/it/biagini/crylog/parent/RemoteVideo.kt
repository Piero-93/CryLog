package it.biagini.crylog.parent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.VideoSink
import org.webrtc.VideoTrack

/**
 * Il video in arrivo dalla cameretta.
 *
 * Sta qui e non nel ViewModel perché un `VideoTrack` è un tipo di WebRTC, e
 * l'interfaccia `StreamTransport` esiste apposta per non farne trapelare
 * nessuno verso la logica di dominio. La UI lo prende da qui e lo attacca a
 * una superficie; il ViewModel sa solo se c'è o non c'è.
 */
object RemoteVideo {

    private var track: VideoTrack? = null
    private val sinks = mutableSetOf<VideoSink>()

    private val _available = MutableStateFlow(false)
    val available: StateFlow<Boolean> = _available.asStateFlow()

    @Synchronized
    fun set(newTrack: VideoTrack?) {
        // I sink restano attaccati fra una sessione e l'altra: la superficie su
        // cui disegnare è la stessa, cambia solo da dove arrivano i fotogrammi.
        sinks.forEach { sink -> runCatching { track?.removeSink(sink) } }
        track = newTrack
        newTrack?.let { fresh -> sinks.forEach { fresh.addSink(it) } }
        _available.value = newTrack != null
    }

    @Synchronized
    fun attach(sink: VideoSink) {
        if (!sinks.add(sink)) return
        runCatching { track?.addSink(sink) }
    }

    @Synchronized
    fun detach(sink: VideoSink) {
        if (!sinks.remove(sink)) return
        runCatching { track?.removeSink(sink) }
    }
}
