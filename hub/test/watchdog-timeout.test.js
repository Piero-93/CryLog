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
import { createHub, silentLog } from '../src/hub.js'

// Il caso peggiore per un baby monitor non e un socket chiuso, che si vede
// subito: e un Nursery Node che smette di rispondere lasciando il socket
// aperto — telefono senza rete, in crash o congelato. Lato server la
// connessione sembra viva e il silenzio sembra una stanza tranquilla.
//
// Le soglie qui sono in centinaia di millisecondi solo per non allungare la
// suite: il percorso esercitato e lo stesso dei 90 secondi di produzione.
const ADMIN = 'admin-token-di-test'

const fastConfig = {
  port: 0,
  host: '127.0.0.1',
  dataDir: ':memory:',
  adminToken: ADMIN,
  heartbeatIntervalMs: 150,
  offlineAfterMs: 400,
  watchdogTickMs: 100,
  pairingCodeTtlMs: 10 * 60_000,
}

let db
let hub
let base

before(async () => {
  db = openDatabase(':memory:')
  hub = createHub({ config: fastConfig, db, adminToken: ADMIN, log: silentLog })
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

// Client basato su ws invece che sul WebSocket globale, perche serve accesso
// al socket sottostante per simulare un device congelato.
const connect = (token) => new Promise((resolve, reject) => {
  const ws = new WebSocket(`${base.replace('http', 'ws')}/ws?token=${encodeURIComponent(token)}`)
  const inbox = []
  const waiters = []

  ws.on('message', (raw) => {
    const message = JSON.parse(raw.toString())
    const waiter = waiters.find((w) => w.type === message.type)
    if (waiter) {
      waiters.splice(waiters.indexOf(waiter), 1)
      waiter.resolve(message)
    } else {
      inbox.push(message)
    }
  })

  ws.on('error', reject)
  ws.on('open', () => resolve({
    raw: ws,
    // Smette di leggere dal socket: i ping del server non vengono piu
    // processati, quindi nessun pong torna indietro. Il socket TCP resta
    // aperto, che e esattamente lo scenario da coprire.
    freeze: () => ws._socket.pause(),
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

test('un Nursery Node congelato viene dichiarato offline per timeout', async () => {
  const nursery = await pair('nursery', 'Cameretta')
  const parent = await pair('parent', 'Telefono')

  const parentWs = await connect(parent.token)
  await parentWs.next('welcome')

  const nurseryWs = await connect(nursery.token)
  await nurseryWs.next('welcome')
  await parentWs.next('nursery-online')

  // Il device smette di rispondere senza chiudere nulla.
  nurseryWs.freeze()

  const offline = await parentWs.next('nursery-offline')
  assert.equal(offline.nurseryId, nursery.deviceId)
  assert.equal(offline.reason, 'timeout', 'timeout, non disconnected: il socket non e stato chiuso')
  assert.equal(offline.nurseryName, 'Cameretta')

  parentWs.close()
  nurseryWs.raw.terminate()
})

test('un Nursery Node che risponde ai ping non viene mai dichiarato offline', async () => {
  const nursery = await pair('nursery', 'Cameretta viva')
  const parent = await pair('parent', 'Telefono 2')

  const parentWs = await connect(parent.token)
  await parentWs.next('welcome')

  const nurseryWs = await connect(nursery.token)
  await nurseryWs.next('welcome')
  await parentWs.next('nursery-online')

  // Diverse volte la soglia di offline: il pong automatico deve bastare a
  // tenerlo vivo, senza che il device mandi un solo messaggio applicativo.
  await new Promise((resolve) => setTimeout(resolve, fastConfig.offlineAfterMs * 4))

  await assert.rejects(
    () => parentWs.next('nursery-offline', 200),
    'nessun falso allarme mentre il device risponde',
  )

  parentWs.close()
  nurseryWs.close()
})

test('dopo il timeout la connessione congelata viene chiusa dal server', async () => {
  const nursery = await pair('nursery', 'Cameretta 3')
  const nurseryWs = await connect(nursery.token)
  await nurseryWs.next('welcome')

  assert.equal(hub.registry.listByDevice(nursery.deviceId).length, 1)

  nurseryWs.freeze()
  await new Promise((resolve) => setTimeout(resolve, fastConfig.offlineAfterMs * 3))

  assert.equal(
    hub.registry.listByDevice(nursery.deviceId).length,
    0,
    'il server non deve trattenere connessioni morte nel registro',
  )

  nurseryWs.raw.terminate()
})
