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

package it.biagini.crylog.parent

import android.content.Context
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.CombinedVibration
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Rende percepibile un avviso su un telefono silenzioso o a faccia in giù.
 *
 * Vibrazione e flash sono deliberatamente separati dalla notifica di sistema:
 * la modalità silenziosa e Non disturbare possono azzerare quest'ultima, e un
 * avviso che il genitore non percepisce non è un avviso.
 */
class Alerter(private val context: Context, private val scope: CoroutineScope) {

    private var flashJob: Job? = null

    fun alert(vibrate: Boolean, flash: Boolean) {
        if (vibrate) vibrate()
        if (flash) flash()
    }

    private fun vibrate() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        } ?: return

        if (!vibrator.hasVibrator()) return

        // Tre impulsi separati: un pattern si distingue da una notifica qualsiasi.
        val effect = VibrationEffect.createWaveform(VIBRATION_PATTERN, -1)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)
                ?.vibrate(CombinedVibration.createParallel(effect))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(effect)
        }
    }

    private fun flash() {
        val manager = context.getSystemService(CameraManager::class.java) ?: return
        val cameraId = manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id)
                .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return

        flashJob?.cancel()
        flashJob = scope.launch {
            try {
                repeat(FLASH_BLINKS) {
                    manager.setTorchMode(cameraId, true)
                    delay(FLASH_ON_MS)
                    manager.setTorchMode(cameraId, false)
                    delay(FLASH_OFF_MS)
                }
            } catch (e: Exception) {
                // La torcia può essere occupata da un'altra app: non è un motivo
                // per far fallire l'avviso, la vibrazione è già partita.
                Log.w(TAG, "flash non disponibile: ${e.message}")
                runCatching { manager.setTorchMode(cameraId, false) }
            }
        }
    }

    fun stop() {
        flashJob?.cancel()
        flashJob = null
    }

    private companion object {
        const val TAG = "CryLogAlerter"
        val VIBRATION_PATTERN = longArrayOf(0, 400, 200, 400, 200, 400)
        const val FLASH_BLINKS = 6
        const val FLASH_ON_MS = 200L
        const val FLASH_OFF_MS = 200L
    }
}
