package it.biagini.crylog.parent

import it.biagini.crylog.core.RmsNoiseDetector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Livello dell'audio che il Parent Node sta ricevendo.
 *
 * Serve a rispondere alla domanda che ci si pone per prima quando si apre
 * l'ascolto e non si sente nulla: è la cameretta a essere silenziosa, o è lo
 * stream che non porta niente? Un grafico piatto e uno che si muove danno due
 * risposte diverse.
 */
object StreamLevel {

    const val HISTORY_SIZE = 150
    const val HISTORY_SECONDS = 30
    private const val SILENCE = -100f

    private val _levelDb = MutableStateFlow(SILENCE.toDouble())
    val levelDb: StateFlow<Double> = _levelDb.asStateFlow()

    private val _history = MutableStateFlow(FloatArray(HISTORY_SIZE) { SILENCE })
    val history: StateFlow<FloatArray> = _history.asStateFlow()

    private var blocks = 0

    /**
     * Quando e arrivato l ultimo blocco audio, o zero se non ne e arrivato
     * nessuno.
     *
     * Lo stato ICE dice che la connessione regge, non che stia trasportando
     * qualcosa: una sessione "connessa" e muta e' esattamente il silenzio
     * ambiguo che un baby monitor non puo permettersi. Questo e l unico punto
     * in cui i pacchetti audio arrivano davvero, quindi e qui che si misura.
     */
    @Volatile
    var lastFrameAtMs: Long = 0L
        private set

    fun push(samples: ShortArray, length: Int) {
        val level = RmsNoiseDetector.levelDb(samples, length)
        _levelDb.value = level
        lastFrameAtMs = System.currentTimeMillis()

        // WebRTC consegna blocchi da 10 ms: uno su venti basta per il grafico,
        // e risparmia diciannove ricomposizioni su venti.
        if (blocks++ % 20 != 0) return

        val previous = _history.value
        _history.value = FloatArray(HISTORY_SIZE) { i ->
            if (i < HISTORY_SIZE - 1) previous[i + 1] else level.toFloat()
        }
    }

    fun reset() {
        blocks = 0
        lastFrameAtMs = 0L
        _levelDb.value = SILENCE.toDouble()
        _history.value = FloatArray(HISTORY_SIZE) { SILENCE }
    }
}
