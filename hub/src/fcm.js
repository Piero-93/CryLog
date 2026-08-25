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

import { createSign } from 'node:crypto'
import { readFileSync } from 'node:fs'

const TOKEN_URL = 'https://oauth2.googleapis.com/token'
const SCOPE = 'https://www.googleapis.com/auth/firebase.messaging'

const base64url = (input) => Buffer.from(input).toString('base64url')

/**
 * JWT firmato con la chiave della service account, da scambiare con un access
 * token. Quaranta righe invece di google-auth-library: l'unica cosa che
 * servirebbe di quella libreria e' esattamente questa.
 */
export function buildJwt(credentials, nowSeconds) {
  const header = base64url(JSON.stringify({ alg: 'RS256', typ: 'JWT' }))
  const claims = base64url(JSON.stringify({
    iss: credentials.client_email,
    scope: SCOPE,
    aud: TOKEN_URL,
    iat: nowSeconds,
    exp: nowSeconds + 3600,
  }))

  const signer = createSign('RSA-SHA256')
  signer.update(`${header}.${claims}`)
  const signature = signer.sign(credentials.private_key, 'base64url')

  return `${header}.${claims}.${signature}`
}

/**
 * Il payload porta solo l'identificativo dell'evento.
 *
 * Nessun contenuto, nessuna misura, nessun nome: la notifica e' un campanello,
 * non un messaggio. Google trasporta il fatto che qualcosa e' successo, non
 * cosa. I dettagli il Parent Node se li chiede all'Hub, che sta in casa.
 */
export function buildMessage(deviceToken, data) {
  return {
    message: {
      token: deviceToken,
      // Data-only: la notifica la costruisce l'app, cosi' resta uguale a
      // quella che arriva dal WebSocket.
      data,
      android: {
        priority: 'high',
        ttl: '300s',
      },
    },
  }
}

export const noisePayload = (eventId) => ({ type: 'noise', eventId })

/**
 * Un Nursery Node che sparisce e' piu' grave di un rumore: significa che da
 * quel momento nessuno sta sorvegliando. Deve raggiungere il genitore anche
 * con l'app chiusa, altrimenti se ne accorgerebbe la mattina dopo.
 */
export const offlinePayload = (nurseryId, reason) => ({
  type: 'nursery-offline',
  nurseryId,
  reason,
})

export function loadCredentials(path) {
  const raw = JSON.parse(readFileSync(path, 'utf8'))
  if (!raw.client_email || !raw.private_key || !raw.project_id) {
    throw new Error('service account incompleta: servono client_email, private_key e project_id')
  }
  return raw
}

/**
 * Se le credenziali mancano il mittente resta inerte: FCM e' opzionale, e un
 * Hub senza Firebase deve funzionare comunque, solo senza notifiche in
 * background.
 */
export function createFcmSender({
  credentials = null,
  fetchImpl = fetch,
  now = Date.now,
  log = console,
} = {}) {
  let accessToken = null
  let expiresAt = 0

  const enabled = credentials !== null

  async function getAccessToken() {
    // Un minuto di margine: un token scaduto fra la richiesta e l'uso
    // costerebbe una notifica persa.
    if (accessToken && now() < expiresAt - 60_000) return accessToken

    const jwt = buildJwt(credentials, Math.floor(now() / 1000))
    const res = await fetchImpl(TOKEN_URL, {
      method: 'POST',
      headers: { 'content-type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({
        grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer',
        assertion: jwt,
      }),
    })

    if (!res.ok) throw new Error(`token rifiutato: HTTP ${res.status}`)

    const body = await res.json()
    accessToken = body.access_token
    expiresAt = now() + body.expires_in * 1000
    return accessToken
  }

  return {
    enabled,

    /** Restituisce true se consegnato, false se il token va dimenticato. */
    async send(deviceToken, data) {
      if (!enabled) return false

      try {
        const token = await getAccessToken()
        const url = `https://fcm.googleapis.com/v1/projects/${credentials.project_id}/messages:send`

        const res = await fetchImpl(url, {
          method: 'POST',
          headers: {
            authorization: `Bearer ${token}`,
            'content-type': 'application/json',
          },
          body: JSON.stringify(buildMessage(deviceToken, data)),
        })

        if (res.ok) return true

        // 404 e 403 significano che il token non vale piu': l'app e' stata
        // disinstallata o ha rigenerato il token. Insistere non serve.
        if (res.status === 404 || res.status === 403) {
          log.warn(`token FCM non piu' valido, verra' dimenticato`)
          return false
        }

        log.error(`invio FCM fallito: HTTP ${res.status}`)
        return true
      } catch (err) {
        log.error(`invio FCM fallito: ${err.message}`)
        return true
      }
    },
  }
}
