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
