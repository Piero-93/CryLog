/*
 * CryLog — self-hosted baby monitor
 * Copyright (C) 2026 Piero Biagini
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * Additional permission under GNU GPL version 3 section 7
 *
 * If you modify this Program, or any covered work, by linking or combining it
 * with Google Play Services and the Firebase SDKs (or modified versions of
 * those libraries), the licensors of this Program grant you additional
 * permission to convey the resulting work. See LICENSE-EXCEPTION.txt.
 */

package it.biagini.crylog.nursery

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlin.concurrent.thread

/**
 * Cattura dal microfono e consegna blocchi di campioni.
 *
 * Esiste una sola cattura in tutto il sistema: in fase 5 lo streaming WebRTC
 * dovrà leggere dagli stessi buffer invece di aprire un secondo AudioRecord,
 * perché due catture concorrenti su Android sono fragili.
 */
class AudioSource(private val onSamples: (ShortArray, Int) -> Unit) {

    private var record: AudioRecord? = null
    @Volatile private var running = false
    private var worker: Thread? = null

    val isRunning: Boolean get() = running

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true

        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuffer <= 0) {
            Log.e(TAG, "il dispositivo non accetta $SAMPLE_RATE Hz mono a 16 bit")
            return false
        }

        // Un buffer generoso assorbe le pause dello scheduler senza perdere audio.
        val bufferSize = maxOf(minBuffer * 2, BLOCK_SAMPLES * 4)

        val audioRecord = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL, ENCODING, bufferSize)
        } catch (e: SecurityException) {
            Log.e(TAG, "permesso microfono mancante", e)
            return false
        }

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord non inizializzato")
            audioRecord.release()
            return false
        }

        record = audioRecord
        running = true
        audioRecord.startRecording()

        worker = thread(name = "crylog-audio", isDaemon = true) {
            val buffer = ShortArray(BLOCK_SAMPLES)
            while (running) {
                val read = audioRecord.read(buffer, 0, buffer.size)
                if (read > 0) {
                    onSamples(buffer, read)
                } else if (read < 0) {
                    Log.e(TAG, "lettura fallita: $read")
                    break
                }
            }
        }

        return true
    }

    fun stop() {
        running = false
        worker?.join(1_000)
        worker = null
        record?.runCatching {
            if (recordingState == AudioRecord.RECORDSTATE_RECORDING) stop()
            release()
        }
        record = null
    }

    companion object {
        private const val TAG = "CryLogAudio"

        /** 16 kHz basta per riconoscere un pianto e costa meno di 44,1 kHz. */
        const val SAMPLE_RATE = 16_000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

        /** Circa 100 ms per blocco: abbastanza fine per misurare, abbastanza grosso da non sprecare CPU. */
        const val BLOCK_SAMPLES = SAMPLE_RATE / 10
    }
}
