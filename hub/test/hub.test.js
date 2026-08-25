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

// Client WebSocket che accoda i messaggi, cosi un test puo attendere un tipo
// preciso senza dipendere dall ordine di arrivo.
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

test('/health risponde senza autenticazione', async () => {
  const res = await api('/health')
  assert.equal(res.status, 200)
  assert.equal(res.body.status, 'ok')
})

test('creare un codice di pairing richiede il token di amministrazione', async () => {
  assert.equal((await api('/pairing-codes', { method: 'POST' })).status, 401)
  assert.equal((await api('/pairing-codes', { method: 'POST', token: 'sbagliato' })).status, 401)
  assert.equal((await api('/pairing-codes', { method: 'POST', token: ADMIN })).status, 201)
})

test('un codice di pairing vale una volta sola', async () => {
  const code = await newCode()
  const first = await api('/pair', { method: 'POST', body: { code, role: 'parent', name: 'Primo' } })
  assert.equal(first.status, 201)

  const second = await api('/pair', { method: 'POST', body: { code, role: 'parent', name: 'Secondo' } })
  assert.equal(second.status, 403)
  assert.equal(second.body.error, 'already_used')
})

test('il pairing rifiuta ruoli, nomi e codici non validi', async () => {
  const badRole = await api('/pair', { method: 'POST', body: { code: await newCode(), role: 'admin', name: 'x' } })
  assert.equal(badRole.body.error, 'invalid_role')

  const badName = await api('/pair', { method: 'POST', body: { code: await newCode(), role: 'parent', name: '  ' } })
  assert.equal(badName.body.error, 'invalid_name')

  const badCode = await api('/pair', { method: 'POST', body: { code: 'non-un-codice', role: 'parent', name: 'x' } })
  assert.equal(badCode.body.error, 'invalid_code_format')
})

test('la WebSocket rifiuta un token sconosciuto', async () => {
  await assert.rejects(() => connect('token-inventato'))
})

test('un evento rumore raggiunge i Parent Node connessi', async () => {
  const nursery = await pair('nursery', 'Cameretta')
  const parent = await pair('parent', 'Telefono')

  const parentWs = await connect(parent.token)
  assert.equal((await parentWs.next('welcome')).deviceId, parent.deviceId)

  const nurseryWs = await connect(nursery.token)
  await nurseryWs.next('welcome')
  assert.equal((await parentWs.next('nursery-online')).nurseryId, nursery.deviceId)

  const startedAt = Date.now()
  nurseryWs.send({ type: 'noise', startedAt, peakDb: 72.5 })

  const received = await parentWs.next('noise')
  assert.equal(received.startedAt, startedAt)
  assert.equal(received.peakDb, 72.5)
  assert.equal(received.nurseryName, 'Cameretta')

  nurseryWs.close()
  parentWs.close()
})

test('un Parent Node non puo generare eventi rumore', async () => {
  const parent = await pair('parent', 'Impostore')
  const ws = await connect(parent.token)
  await ws.next('welcome')

  ws.send({ type: 'noise', startedAt: Date.now(), peakDb: 50 })
  assert.equal((await ws.next('error')).code, 'role_not_allowed')
  ws.close()
})

test('la disconnessione di un Nursery Node avvisa i Parent Node', async () => {
  const nursery = await pair('nursery', 'Cameretta 2')
  const parent = await pair('parent', 'Telefono 2')

  const parentWs = await connect(parent.token)
  await parentWs.next('welcome')

  const nurseryWs = await connect(nursery.token)
  await nurseryWs.next('welcome')
  await parentWs.next('nursery-online')

  nurseryWs.close()

  const offline = await parentWs.next('nursery-offline')
  assert.equal(offline.nurseryId, nursery.deviceId)
  assert.equal(offline.reason, 'disconnected')
  parentWs.close()
})

test('gli eventi restano leggibili e richiedono autenticazione', async () => {
  const parent = await pair('parent', 'Lettore')

  assert.equal((await api('/events')).status, 401)

  const res = await api('/events', { token: parent.token })
  assert.equal(res.status, 200)
  assert.ok(res.body.events.length >= 1)
  assert.ok(res.body.events.some((e) => e.peakDb === 72.5))
})

test('il registro dispositivi mostra chi e online', async () => {
  const parent = await pair('parent', 'Osservatore')
  const ws = await connect(parent.token)
  await ws.next('welcome')

  const res = await api('/devices', { token: parent.token })
  const self = res.body.devices.find((d) => d.id === parent.deviceId)
  assert.equal(self.online, true)
  assert.equal(self.role, 'parent')

  ws.close()
})

test('un dispositivo gia accoppiato puo invitarne un altro', async () => {
  // Senza questo, aggiungere un telefono richiederebbe un terminale e l'admin
  // token: impossibile proprio nel momento in cui serve, lontano da casa.
  const parent = await pair('parent', 'Telefono di casa')

  const invite = await api('/pairing-codes', { method: 'POST', token: parent.token })
  assert.equal(invite.status, 201)

  const invited = await api('/pair', {
    method: 'POST',
    body: { code: invite.body.code, role: 'nursery', name: 'Cameretta' },
  })
  assert.equal(invited.status, 201, 'il codice prodotto deve funzionare davvero')
})

test('un token inventato non puo invitare nessuno', async () => {
  assert.equal((await api('/pairing-codes', { method: 'POST', token: 'inventato' })).status, 401)
})

test('un Parent Node che si collega sa subito chi sta sorvegliando', async () => {
  const nursery = await pair('nursery', 'Cameretta attiva')
  const nurseryWs = await connect(nursery.token)
  await nurseryWs.next('welcome')

  // Il Parent arriva dopo: deve comunque sapere che c'e' qualcuno in ascolto,
  // senza aspettare il primo rumore.
  const parent = await pair('parent', 'Telefono tardivo')
  const parentWs = await connect(parent.token)
  await parentWs.next('welcome')

  const online = await parentWs.next('nursery-online')
  assert.equal(online.nurseryId, nursery.deviceId)
  assert.ok(online.at > 0, 'serve il momento da cui e attivo, per il cronometro')

  nurseryWs.close()
  parentWs.close()
})

test('senza Nursery Node attivi non arriva nessuno stato iniziale', async () => {
  const parent = await pair('parent', 'Telefono solo')
  const parentWs = await connect(parent.token)
  await parentWs.next('welcome')

  await assert.rejects(() => parentWs.next('nursery-online', 300))
  parentWs.close()
})
