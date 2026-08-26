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

import { after, before, test } from 'node:test'
import assert from 'node:assert/strict'
import { openDatabase } from '../src/db.js'
import { createHub, silentLog } from '../src/hub.js'

const ADMIN = 'admin-token-di-test'

const testConfig = {
  port: 0,
  host: '127.0.0.1',
  dataDir: ':memory:',
  adminToken: ADMIN,
  heartbeatIntervalMs: 60_000,
  offlineAfterMs: 90_000,
  watchdogTickMs: 60_000,
  pairingCodeTtlMs: 10 * 60_000,
}

let db
let hub
let base

before(async () => {
  db = openDatabase(':memory:')
  hub = createHub({ config: testConfig, db, adminToken: ADMIN, log: silentLog })
  const port = await hub.start()
  base = `http://127.0.0.1:${port}`
})

after(async () => {
  await hub.stop()
  db.close()
})

const api = async (path, { method = 'GET', token, body } = {}) => {
  const res = await fetch(base + path, {
    method,
    headers: {
      ...(token ? { authorization: `Bearer ${token}` } : {}),
      ...(body ? { 'content-type': 'application/json' } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  })
  return { status: res.status, body: await res.json().catch(() => null) }
}

const newCode = async () => (await api('/pairing-codes', { method: 'POST', token: ADMIN })).body.code

const pair = async (role, name) =>
  (await api('/pair', { method: 'POST', body: { code: await newCode(), role, name } })).body

const changeRole = (token, body) =>
  api('/device/role', { method: 'POST', token, body })

test('cambiare ruolo non richiede un codice nuovo', async () => {
  const device = await pair('nursery', 'Cameretta')

  const res = await changeRole(device.token, { role: 'parent', name: 'Telefono' })

  assert.equal(res.status, 200)
  assert.equal(res.body.role, 'parent')
  assert.equal(res.body.name, 'Telefono')
})

test('il ruolo cambia davvero nel registro, non solo nella risposta', async () => {
  const device = await pair('nursery', 'Cameretta')

  await changeRole(device.token, { role: 'parent', name: 'Telefono' })

  const stored = db.findDeviceById(device.deviceId)
  assert.equal(stored.role, 'parent')
  assert.equal(stored.name, 'Telefono')
})

test('il token resta valido dopo il cambio', async () => {
  const device = await pair('parent', 'Telefono')

  await changeRole(device.token, { role: 'nursery', name: 'Cameretta' })
  const again = await changeRole(device.token, { role: 'parent', name: 'Telefono' })

  assert.equal(again.status, 200)
})

test('senza token non si cambia ruolo a nessuno', async () => {
  await pair('nursery', 'Cameretta')

  const res = await changeRole(undefined, { role: 'parent', name: 'Telefono' })

  assert.equal(res.status, 401)
  assert.equal(res.body.error, 'unauthorized')
})

test('un token inventato non vale', async () => {
  const res = await changeRole('non-esiste', { role: 'parent', name: 'Telefono' })

  assert.equal(res.status, 401)
})

test('il token di amministrazione non basta: serve sapere chi si e', async () => {
  const res = await changeRole(ADMIN, { role: 'parent', name: 'Telefono' })

  assert.equal(res.status, 401)
})

test('un ruolo che non esiste viene rifiutato', async () => {
  const device = await pair('nursery', 'Cameretta')

  const res = await changeRole(device.token, { role: 'guardiano', name: 'Telefono' })

  assert.equal(res.status, 400)
  assert.equal(res.body.error, 'invalid_role')
})

test('il ruolo mancante viene rifiutato come uno sbagliato', async () => {
  const device = await pair('nursery', 'Cameretta')

  const res = await changeRole(device.token, { name: 'Telefono' })

  assert.equal(res.status, 400)
  assert.equal(res.body.error, 'invalid_role')
})

test('un nome vuoto viene rifiutato', async () => {
  const device = await pair('nursery', 'Cameretta')

  const res = await changeRole(device.token, { role: 'parent', name: '   ' })

  assert.equal(res.status, 400)
  assert.equal(res.body.error, 'invalid_name')
})

test('un nome lunghissimo viene rifiutato', async () => {
  const device = await pair('nursery', 'Cameretta')

  const res = await changeRole(device.token, { role: 'parent', name: 'x'.repeat(200) })

  assert.equal(res.status, 400)
  assert.equal(res.body.error, 'invalid_name')
})

test('un ruolo rifiutato non tocca il registro', async () => {
  const device = await pair('nursery', 'Cameretta')

  await changeRole(device.token, { role: 'guardiano', name: 'Telefono' })

  const stored = db.findDeviceById(device.deviceId)
  assert.equal(stored.role, 'nursery')
  assert.equal(stored.name, 'Cameretta')
})

test('il ruolo cambia anche restando lo stesso: serve a rinominare', async () => {
  const device = await pair('parent', 'Telefono')

  const res = await changeRole(device.token, { role: 'parent', name: 'Telefono di Anna' })

  assert.equal(res.status, 200)
  assert.equal(db.findDeviceById(device.deviceId).name, 'Telefono di Anna')
})

test('la connessione aperta viene chiusa: portava il ruolo vecchio', async () => {
  const device = await pair('nursery', 'Cameretta')

  const ws = new WebSocket(`${base.replace('http', 'ws')}/ws?token=${encodeURIComponent(device.token)}`)
  await new Promise((resolve, reject) => {
    ws.addEventListener('open', resolve)
    ws.addEventListener('error', () => reject(new Error('connessione rifiutata')))
  })

  const closed = new Promise((resolve) => ws.addEventListener('close', resolve))
  await changeRole(device.token, { role: 'parent', name: 'Telefono' })

  // Senza questa chiusura il fan-out continuerebbe a smistare il dispositivo
  // come Nursery Node fino alla prossima riconnessione.
  await closed
  assert.equal(ws.readyState, WebSocket.CLOSED)
})
