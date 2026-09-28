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

// Le impostazioni di rilevamento si cambiano da un Parent Node, ma restano del
// Nursery Node: l'Hub inoltra la richiesta, e solo quello che il Nursery
// annuncia dopo averla applicata arriva agli altri.
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

const SETTINGS = { thresholdDb: -30, minDurationMs: 500, cooldownMs: 60_000 }

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
  const inbox = []
  const waiters = []

  ws.addEventListener('message', (e) => {
    const message = JSON.parse(e.data)
    const waiter = waiters.find((w) => w.type === message.type)
    if (waiter) {
      waiters.splice(waiters.indexOf(waiter), 1)
      waiter.resolve(message)
    } else {
      inbox.push(message)
    }
  })

  ws.addEventListener('error', () => reject(new Error('connessione rifiutata')))
  ws.addEventListener('open', () => resolve({
    send: (message) => ws.send(JSON.stringify(message)),
    close: () => ws.close(),
    next: (type, timeoutMs = 3000) => new Promise((res, rej) => {
      const queued = inbox.find((m) => m.type === type)
      if (queued) {
        inbox.splice(inbox.indexOf(queued), 1)
        return res(queued)
      }
      const waiter = { type, resolve: res }
      waiters.push(waiter)
      const timer = setTimeout(() => {
        if (!waiters.includes(waiter)) return
        waiters.splice(waiters.indexOf(waiter), 1)
        rej(new Error(`nessun messaggio "${type}" entro ${timeoutMs}ms`))
      }, timeoutMs)
      timer.unref?.()
    }),
  }))
})

test('le impostazioni annunciate dal Nursery Node arrivano ai Parent Node', async () => {
  const nursery = await pair('nursery', 'Cameretta')
  const parent = await pair('parent', 'Telefono')

  const parentWs = await connect(parent.token)
  await parentWs.next('welcome')
  const nurseryWs = await connect(nursery.token)
  await nurseryWs.next('welcome')

  nurseryWs.send({ type: 'detection', ...SETTINGS })

  const announced = await parentWs.next('detection')
  assert.deepEqual(announced, {
    type: 'detection',
    nurseryId: nursery.deviceId,
    nurseryName: 'Cameretta',
    changedBy: null,
    ...SETTINGS,
  })

  parentWs.close()
  nurseryWs.close()
})

test('un Parent Node che si collega dopo trova le impostazioni correnti', async () => {
  const nursery = await pair('nursery', 'Cameretta 2')
  const parent = await pair('parent', 'Telefono 2')

  const nurseryWs = await connect(nursery.token)
  await nurseryWs.next('welcome')
  nurseryWs.send({ type: 'detection', ...SETTINGS })
  // Serve un giro perche' l'annuncio sia arrivato prima del Parent.
  await new Promise((resolve) => setTimeout(resolve, 50))

  const parentWs = await connect(parent.token)
  await parentWs.next('welcome')
  const current = await parentWs.next('detection')
  assert.equal(current.nurseryId, nursery.deviceId)
  assert.equal(current.thresholdDb, SETTINGS.thresholdDb)

  parentWs.close()
  nurseryWs.close()
})

test('una richiesta di cambio arriva al Nursery Node con il nome di chi la fa', async () => {
  const nursery = await pair('nursery', 'Cameretta 3')
  const parent = await pair('parent', 'Poco F5')

  const nurseryWs = await connect(nursery.token)
  await nurseryWs.next('welcome')
  const parentWs = await connect(parent.token)
  await parentWs.next('welcome')

  parentWs.send({ type: 'configure', to: nursery.deviceId, ...SETTINGS, thresholdDb: -40 })

  const request = await nurseryWs.next('configure')
  assert.deepEqual(request, {
    type: 'configure',
    from: parent.deviceId,
    fromName: 'Poco F5',
    ...SETTINGS,
    thresholdDb: -40,
  })

  parentWs.close()
  nurseryWs.close()
})

test('una richiesta verso un Nursery Node spento torna indietro come errore', async () => {
  const nursery = await pair('nursery', 'Cameretta spenta')
  const parent = await pair('parent', 'Telefono 4')

  const parentWs = await connect(parent.token)
  await parentWs.next('welcome')
  parentWs.send({ type: 'configure', to: nursery.deviceId, ...SETTINGS })

  assert.equal((await parentWs.next('error')).code, 'nursery_offline')
  parentWs.close()
})

test('i ruoli non si scambiano i messaggi del rilevamento', async () => {
  const nursery = await pair('nursery', 'Cameretta 5')
  const other = await pair('nursery', 'Cameretta 6')
  const parent = await pair('parent', 'Telefono 5')

  const nurseryWs = await connect(nursery.token)
  await nurseryWs.next('welcome')
  nurseryWs.send({ type: 'configure', to: other.deviceId, ...SETTINGS })
  assert.equal((await nurseryWs.next('error')).code, 'role_not_allowed')

  const parentWs = await connect(parent.token)
  await parentWs.next('welcome')
  parentWs.send({ type: 'detection', ...SETTINGS })
  assert.equal((await parentWs.next('error')).code, 'role_not_allowed')

  // Un Parent Node non si regola: il destinatario deve essere un Nursery.
  parentWs.send({ type: 'configure', to: parent.deviceId, ...SETTINGS })
  assert.equal((await parentWs.next('error')).code, 'invalid_recipient')

  parentWs.close()
  nurseryWs.close()
})
