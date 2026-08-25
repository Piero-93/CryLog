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

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createVerify, generateKeyPairSync } from 'node:crypto'
import { buildJwt, buildMessage, createFcmSender, noisePayload, offlinePayload } from '../src/fcm.js'
import { selectPushTargets } from '../src/fanout.js'

const { privateKey, publicKey } = generateKeyPairSync('rsa', { modulusLength: 2048 })

const credentials = {
  client_email: 'crylog@progetto.iam.gserviceaccount.com',
  private_key: privateKey.export({ type: 'pkcs8', format: 'pem' }),
  project_id: 'progetto-di-prova',
}

const decode = (part) => JSON.parse(Buffer.from(part, 'base64url').toString('utf8'))

test('il JWT dichiara la service account e lo scope giusto', () => {
  const [header, claims] = buildJwt(credentials, 1_700_000_000).split('.')

  assert.deepEqual(decode(header), { alg: 'RS256', typ: 'JWT' })
  const payload = decode(claims)
  assert.equal(payload.iss, credentials.client_email)
  assert.equal(payload.scope, 'https://www.googleapis.com/auth/firebase.messaging')
  assert.equal(payload.aud, 'https://oauth2.googleapis.com/token')
  assert.equal(payload.iat, 1_700_000_000)
  assert.equal(payload.exp, 1_700_000_000 + 3600)
})

test('la firma del JWT regge la verifica con la chiave pubblica', () => {
  const jwt = buildJwt(credentials, 1_700_000_000)
  const [header, claims, signature] = jwt.split('.')

  const verifier = createVerify('RSA-SHA256')
  verifier.update(`${header}.${claims}`)
  assert.ok(verifier.verify(publicKey, signature, 'base64url'), 'firma non valida')
})

test('la firma cambia se cambiano i dati', () => {
  const a = buildJwt(credentials, 1_700_000_000).split('.')[2]
  const b = buildJwt(credentials, 1_700_000_001).split('.')[2]
  assert.notEqual(a, b)
})

test('il messaggio push non porta contenuto, solo un identificativo', () => {
  const message = buildMessage('token-del-telefono', noisePayload('evento-123')).message

  assert.deepEqual(message.data, { type: 'noise', eventId: 'evento-123' })
  assert.equal(message.token, 'token-del-telefono')

  // Nessun nome, nessun livello, nessun testo: la push è un campanello.
  const serialized = JSON.stringify(message)
  assert.ok(!serialized.includes('notification'), 'niente notifica di sistema')
  assert.ok(!/peak|db|nursery|name/i.test(JSON.stringify(message.data)), 'niente dettagli')
})

test('la push ha priorità alta e scade, per non arrivare fuori tempo massimo', () => {
  const android = buildMessage('t', noisePayload('e')).message.android
  assert.equal(android.priority, 'high', 'senza priorità alta il Doze la trattiene')
  assert.equal(android.ttl, '300s')
})

test('senza credenziali il mittente resta inerte invece di rompersi', async () => {
  const sender = createFcmSender()
  assert.equal(sender.enabled, false)
  assert.equal(await sender.send('token', noisePayload('evento')), false)
})

test('il token di accesso viene riusato finché è valido', async () => {
  let tokenRequests = 0
  const fetchImpl = async (url) => {
    if (url.includes('oauth2')) {
      tokenRequests++
      return { ok: true, json: async () => ({ access_token: 'abc', expires_in: 3600 }) }
    }
    return { ok: true, status: 200 }
  }

  const sender = createFcmSender({ credentials, fetchImpl, now: () => 1_700_000_000_000 })
  await sender.send('token', noisePayload('e1'))
  await sender.send('token', noisePayload('e2'))
  await sender.send('token', noisePayload('e3'))

  assert.equal(tokenRequests, 1, 'un token per ora, non uno per messaggio')
})

test('il token di accesso viene rinnovato prima di scadere', async () => {
  let tokenRequests = 0
  let clock = 1_700_000_000_000
  const fetchImpl = async (url) => {
    if (url.includes('oauth2')) {
      tokenRequests++
      return { ok: true, json: async () => ({ access_token: 'abc', expires_in: 3600 }) }
    }
    return { ok: true, status: 200 }
  }

  const sender = createFcmSender({ credentials, fetchImpl, now: () => clock })
  await sender.send('token', noisePayload('e1'))

  clock += 3_600_000
  await sender.send('token', noisePayload('e2'))

  assert.equal(tokenRequests, 2)
})

test('un token del dispositivo non più valido viene segnalato per la rimozione', async () => {
  const fetchImpl = async (url) => {
    if (url.includes('oauth2')) {
      return { ok: true, json: async () => ({ access_token: 'abc', expires_in: 3600 }) }
    }
    return { ok: false, status: 404 }
  }

  const silent = { info() {}, warn() {}, error() {} }
  const sender = createFcmSender({ credentials, fetchImpl, log: silent })

  assert.equal(await sender.send('token-morto', noisePayload('e1')), false, 'il token va dimenticato')
})

test('un errore temporaneo non fa dimenticare il token', async () => {
  const fetchImpl = async (url) => {
    if (url.includes('oauth2')) {
      return { ok: true, json: async () => ({ access_token: 'abc', expires_in: 3600 }) }
    }
    return { ok: false, status: 500 }
  }

  const silent = { info() {}, warn() {}, error() {} }
  const sender = createFcmSender({ credentials, fetchImpl, log: silent })

  assert.equal(await sender.send('token', 'e1'), true, 'un 500 è di Google, non del telefono')
})

test('una rete che non risponde non fa perdere il token', async () => {
  const fetchImpl = async () => { throw new Error('rete assente') }
  const silent = { info() {}, warn() {}, error() {} }
  const sender = createFcmSender({ credentials, fetchImpl, log: silent })

  assert.equal(await sender.send('token', 'e1'), true)
})

test('la push va solo ai Parent Node senza WebSocket aperta', () => {
  const parents = [
    { id: 'connesso', fcmToken: 'token-a' },
    { id: 'assente', fcmToken: 'token-b' },
    { id: 'senza-token', fcmToken: null },
  ]
  const isOnline = (id) => id === 'connesso'

  const targets = selectPushTargets(parents, isOnline)

  assert.deepEqual(targets.map((p) => p.id), ['assente'])
})

test('senza Parent Node registrati non si manda nulla', () => {
  assert.deepEqual(selectPushTargets([], () => false), [])
})

test('la sparizione di un Nursery Node ha un payload proprio', () => {
  const data = buildMessage('token', offlinePayload('nursery-1', 'timeout')).message.data

  assert.deepEqual(data, { type: 'nursery-offline', nurseryId: 'nursery-1', reason: 'timeout' })
})

test('anche l avviso di sparizione resta senza contenuto', () => {
  // Vale la stessa regola dell'evento rumore: nessun nome, nessun dettaglio.
  const data = buildMessage('token', offlinePayload('nursery-1', 'disconnected')).message.data
  assert.ok(!/name|cameretta|peak/i.test(JSON.stringify(data)))
})

test('il motivo distingue una disconnessione da un silenzio', () => {
  assert.equal(offlinePayload('n', 'timeout').reason, 'timeout')
  assert.equal(offlinePayload('n', 'disconnected').reason, 'disconnected')
})
