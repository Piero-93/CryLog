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
import { WebSocket } from 'ws'
import { openDatabase } from '../src/db.js'
import { createHub } from '../src/hub.js'

// Quello che il log deve dire da solo, senza bisogno di interrogare il
// database: un Parent Node che non potra' ricevere push e un Nursery Node con
// due socket aperti sono due guasti silenziosi.
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
  fcmTokenGraceMs: 150,
}

const warnings = []
const log = { info() {}, warn: (message) => warnings.push(message), error() {} }

let db
let hub
let base

before(async () => {
  db = openDatabase(':memory:')
  hub = createHub({ config: testConfig, db, adminToken: ADMIN, log })
  const port = await hub.start()
  base = `http://127.0.0.1:${port}`
})

after(async () => {
  await hub.stop()
  db.close()
})

const pair = async (role, name) => {
  const codeRes = await fetch(`${base}/pairing-codes`, {
    method: 'POST',
    headers: { authorization: `Bearer ${ADMIN}` },
  })
  const { code } = await codeRes.json()
  const res = await fetch(`${base}/pair`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ code, role, name }),
  })
  return res.json()
}

const connect = (token) => new Promise((resolve, reject) => {
  const ws = new WebSocket(`${base.replace('http', 'ws')}/ws?token=${encodeURIComponent(token)}`)
  ws.on('error', reject)
  ws.on('message', (raw) => {
    if (JSON.parse(raw.toString()).type === 'welcome') resolve(ws)
  })
})

const wait = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

test('un Parent Node che non manda il token FCM finisce nel log', async () => {
  const parent = await pair('parent', 'Senza token')
  const ws = await connect(parent.token)

  await wait(testConfig.fcmTokenGraceMs * 3)

  assert.ok(warnings.some((w) => w.includes('"Senza token"') && w.includes('token FCM')))
  ws.close()
})

test('un Parent Node che manda il token FCM non genera avvisi', async () => {
  const parent = await pair('parent', 'Con token')
  const ws = await connect(parent.token)
  ws.send(JSON.stringify({ type: 'fcm-token', token: 'token-di-prova' }))

  await wait(testConfig.fcmTokenGraceMs * 3)

  assert.ok(!warnings.some((w) => w.includes('"Con token"')))
  assert.equal(db.findDeviceById(parent.deviceId).fcmToken, 'token-di-prova')
  ws.close()
})

test('un secondo socket dello stesso Nursery Node finisce nel log', async () => {
  const nursery = await pair('nursery', 'Doppia')
  const first = await connect(nursery.token)
  assert.ok(!warnings.some((w) => w.includes('"Doppia"')))

  const second = await connect(nursery.token)
  assert.ok(warnings.some((w) => w.includes('"Doppia"') && w.includes('1 connessioni')))

  first.close()
  second.close()
})
