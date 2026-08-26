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

const verify = (code) => api('/pairing-codes/verify', { method: 'POST', body: { code } })

const pair = (code, role, name) =>
  api('/pair', { method: 'POST', body: { code, role, name } })

test('un codice buono viene riconosciuto', async () => {
  const code = await newCode()

  const res = await verify(code)

  assert.equal(res.status, 200)
  assert.equal(res.body.valid, true)
})

test('verificare non consuma: il codice serve ancora dopo', async () => {
  const code = await newCode()

  await verify(code)
  await verify(code)
  const paired = await pair(code, 'nursery', 'Cameretta')

  // È il motivo per cui questo endpoint esiste: se consumasse, controllare il
  // codice significherebbe bruciarlo.
  assert.equal(paired.status, 201)
})

test('un codice inventato viene rifiutato', async () => {
  const res = await verify('ABCD-EFGH')

  assert.equal(res.status, 403)
  assert.equal(res.body.error, 'unknown_code')
})

test('un codice gia usato viene rifiutato', async () => {
  const code = await newCode()
  await pair(code, 'parent', 'Telefono')

  const res = await verify(code)

  assert.equal(res.status, 403)
  assert.equal(res.body.error, 'already_used')
})

test('un codice di forma sbagliata viene rifiutato prima di guardare il registro', async () => {
  const res = await verify('troppo-corto')

  assert.equal(res.status, 400)
  assert.equal(res.body.error, 'invalid_code_format')
})

test('un codice mancante viene rifiutato come uno malformato', async () => {
  const res = await api('/pairing-codes/verify', { method: 'POST', body: {} })

  assert.equal(res.status, 400)
  assert.equal(res.body.error, 'invalid_code_format')
})

test('verificare non richiede autenticazione: e il momento prima di averne una', async () => {
  const code = await newCode()

  const res = await verify(code)

  assert.equal(res.status, 200)
})

test('verificare non crea dispositivi', async () => {
  const before = db.listDevices().length
  const code = await newCode()

  await verify(code)

  assert.equal(db.listDevices().length, before)
})
