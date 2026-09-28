/*
 * CryLog Hub — self-hosted baby monitor
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
 */

export const ROLES = ['nursery', 'parent']

// Il codice con cui un Nursery Node chiude quando qualcuno ha premuto
// "Interrompi". Una chiusura normale non basta a dirlo: l'app la usa anche per
// riconnettersi. Se il frame non arriva, perche' la rete e' gia' caduta,
// resta il timeout e con lui l'allarme: sbagliare costa un avviso di troppo,
// mai uno di meno.
export const CLOSE_MONITORING_STOPPED = 4001

const isFiniteNumber = (v) => typeof v === 'number' && Number.isFinite(v)

export function parseClientMessage(raw) {
  let msg
  try {
    msg = JSON.parse(raw)
  } catch {
    return { ok: false, error: 'invalid_json' }
  }
  if (msg === null || typeof msg !== 'object' || Array.isArray(msg)) {
    return { ok: false, error: 'invalid_message' }
  }

  switch (msg.type) {
    case 'heartbeat':
      return { ok: true, message: { type: 'heartbeat' } }

    case 'noise': {
      if (!isFiniteNumber(msg.startedAt)) return { ok: false, error: 'invalid_started_at' }
      if (msg.endedAt !== undefined && msg.endedAt !== null && !isFiniteNumber(msg.endedAt)) {
        return { ok: false, error: 'invalid_ended_at' }
      }
      if (msg.peakDb !== undefined && msg.peakDb !== null && !isFiniteNumber(msg.peakDb)) {
        return { ok: false, error: 'invalid_peak_db' }
      }
      return {
        ok: true,
        message: {
          type: 'noise',
          startedAt: msg.startedAt,
          endedAt: msg.endedAt ?? null,
          peakDb: msg.peakDb ?? null,
        },
      }
    }

    case 'signal':
      return parseSignal(msg)

    // Il Nursery Node annuncia le sue impostazioni, e dopo un cambio da
    // remoto dice anche chi l'ha chiesto.
    case 'detection': {
      const changedBy = msg.changedBy ?? null
      if (changedBy !== null && (typeof changedBy !== 'string' || changedBy.length > 64)) {
        return { ok: false, error: 'invalid_detection' }
      }
      return parseDetection(msg, { changedBy })
    }

    // Stessi valori, piu' il destinatario: un Parent Node che regola un
    // Nursery Node deve dire quale.
    case 'configure': {
      if (typeof msg.to !== 'string' || msg.to.length === 0) {
        return { ok: false, error: 'invalid_recipient' }
      }
      return parseDetection(msg, { type: 'configure', to: msg.to })
    }

    case 'fcm-token': {
      if (typeof msg.token !== 'string' || msg.token.length === 0 || msg.token.length > 512) {
        return { ok: false, error: 'invalid_fcm_token' }
      }
      return { ok: true, message: { type: 'fcm-token', token: msg.token } }
    }

    default:
      return { ok: false, error: 'unknown_type' }
  }
}

/**
 * I limiti delle impostazioni di rilevamento.
 *
 * Sono quelli che l'app puo' produrre, con un po' di margine: l'Hub li
 * controlla perche' un valore assurdo arrivato da un altro telefono — una
 * soglia a zero, una pausa di un giorno — spegnerebbe gli avvisi in silenzio,
 * e chi e' in cameretta non ha modo di accorgersene.
 */
export const DETECTION_LIMITS = {
  thresholdDb: [-60, -5],
  minDurationMs: [100, 10_000],
  cooldownMs: [5_000, 3_600_000],
}

function parseDetection(msg, extra) {
  const values = {}
  for (const [key, [min, max]] of Object.entries(DETECTION_LIMITS)) {
    const value = msg[key]
    if (!isFiniteNumber(value) || value < min || value > max) {
      return { ok: false, error: 'invalid_detection' }
    }
    values[key] = value
  }
  return { ok: true, message: { type: 'detection', ...extra, ...values } }
}

export const welcome = (device, serverTime) => ({
  type: 'welcome',
  deviceId: device.id,
  role: device.role,
  name: device.name,
  serverTime,
})

export const noiseEvent = (event, nursery) => ({
  type: 'noise',
  id: event.id,
  nurseryId: event.nurseryId,
  nurseryName: nursery.name,
  startedAt: event.startedAt,
  endedAt: event.endedAt,
  peakDb: event.peakDb,
})

export const nurseryOffline = (nursery, lastSeen, reason) => ({
  type: 'nursery-offline',
  nurseryId: nursery.id,
  nurseryName: nursery.name,
  lastSeen,
  reason,
})

export const nurseryOnline = (nursery, at) => ({
  type: 'nursery-online',
  nurseryId: nursery.id,
  nurseryName: nursery.name,
  at,
})

export const error = (code) => ({ type: 'error', code })

/**
 * Le impostazioni correnti di un Nursery Node, per i Parent Node.
 *
 * [changedBy] e' il nome del Parent Node che le ha appena cambiate, o null se
 * le ha regolate il Nursery stesso o se e' solo il loro stato.
 */
export const detection = (nursery, settings, changedBy = null) => ({
  type: 'detection',
  nurseryId: nursery.id,
  nurseryName: nursery.name,
  thresholdDb: settings.thresholdDb,
  minDurationMs: settings.minDurationMs,
  cooldownMs: settings.cooldownMs,
  changedBy,
})

/** Una richiesta di cambio, per il Nursery Node: da chi arriva e cosa chiede. */
export const configure = (fromDeviceId, fromName, settings) => ({
  type: 'configure',
  from: fromDeviceId,
  fromName,
  thresholdDb: settings.thresholdDb,
  minDurationMs: settings.minDurationMs,
  cooldownMs: settings.cooldownMs,
})

/**
 * Instradamento del signaling WebRTC.
 *
 * L'Hub non guarda dentro il payload: offer, answer e candidati ICE gli sono
 * opachi. Fa il postino fra due dispositivi accoppiati, e questo basta perche'
 * il media viaggi poi da telefono a telefono senza passargli davanti.
 */
export function parseSignal(msg) {
  if (typeof msg.to !== 'string' || msg.to.length === 0) {
    return { ok: false, error: 'invalid_recipient' }
  }
  if (msg.payload === null || typeof msg.payload !== 'object' || Array.isArray(msg.payload)) {
    return { ok: false, error: 'invalid_payload' }
  }
  return { ok: true, message: { type: 'signal', to: msg.to, payload: msg.payload } }
}

export const signal = (fromDeviceId, fromName, payload) => ({
  type: 'signal',
  from: fromDeviceId,
  fromName,
  payload,
})

/** Il destinatario non e' raggiungibile: chi ha chiesto lo stream deve saperlo. */
export const signalUndelivered = (to, reason) => ({
  type: 'signal-undelivered',
  to,
  reason,
})
