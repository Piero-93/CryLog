package it.biagini.crylog.transport

import org.json.JSONObject

/**
 * I messaggi che i due telefoni si scambiano per accordarsi sullo stream.
 *
 * Viaggiano dentro la busta che l'Hub inoltra senza aprirla, e sono l'unica
 * cosa che passa dal server: dopo l'accordo, audio e video vanno diretti.
 */
sealed interface SignalPayload {

    /** Il Parent Node chiede di ricevere. Il Nursery decide cosa può offrire. */
    data class Request(val video: Boolean, val talkBack: Boolean) : SignalPayload

    data class Offer(val sdp: String, val hasVideo: Boolean) : SignalPayload

    data class Answer(val sdp: String) : SignalPayload

    data class Ice(val candidate: String, val sdpMid: String, val sdpMLineIndex: Int) : SignalPayload

    /**
     * Il genitore ha aperto o chiuso il microfono.
     *
     * La traccia c e comunque dall inizio, muta: senza questo avviso il Nursery
     * non saprebbe distinguere una sessione con talk-back disponibile da una in
     * cui si sta parlando davvero, e sospenderebbe il rilevamento per tutto il
     * tempo dell ascolto.
     */
    data class Talk(val on: Boolean) : SignalPayload

    /** Chiusura ordinata: senza, l altro capo resterebbe con una sessione aperta. */
    data object Stop : SignalPayload

    /**
     * Il Nursery Node sta gia trasmettendo a qualcun altro.
     *
     * Un solo ascoltatore alla volta: accettare il secondo butterebbe fuori il
     * primo senza dirglielo, che e il modo peggiore di gestire un limite.
     */
    data object Busy : SignalPayload

    companion object {

        fun parse(json: String): SignalPayload? = runCatching {
            val obj = JSONObject(json)
            when (obj.optString("kind")) {
                "request" -> Request(
                    video = obj.optBoolean("video"),
                    talkBack = obj.optBoolean("talkBack"),
                )
                "offer" -> Offer(obj.getString("sdp"), obj.optBoolean("hasVideo"))
                "answer" -> Answer(obj.getString("sdp"))
                "ice" -> Ice(
                    candidate = obj.getString("candidate"),
                    sdpMid = obj.optString("sdpMid"),
                    sdpMLineIndex = obj.optInt("sdpMLineIndex"),
                )
                "talk" -> Talk(obj.optBoolean("on"))
                "stop" -> Stop
                "busy" -> Busy
                else -> null
            }
        }.getOrNull()
    }

    fun encode(): String = when (this) {
        is Request -> JSONObject()
            .put("kind", "request")
            .put("video", video)
            .put("talkBack", talkBack)

        is Offer -> JSONObject().put("kind", "offer").put("sdp", sdp).put("hasVideo", hasVideo)

        is Answer -> JSONObject().put("kind", "answer").put("sdp", sdp)

        is Ice -> JSONObject()
            .put("kind", "ice")
            .put("candidate", candidate)
            .put("sdpMid", sdpMid)
            .put("sdpMLineIndex", sdpMLineIndex)

        is Talk -> JSONObject().put("kind", "talk").put("on", on)

        is Stop -> JSONObject().put("kind", "stop")

        is Busy -> JSONObject().put("kind", "busy")
    }.toString()
}
