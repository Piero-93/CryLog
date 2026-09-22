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
import { createServer, request } from 'node:http'
import { WebSocket } from 'ws'
import { openDatabase } from '../src/db.js'
import { createHub, silentLog } from '../src/hub.js'
import { PAIRING_PAGE } from '../src/ui.js'

const ADMIN = 'admin-token-di-test'
const PREFIX = '/crylog'

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

/**
 * Il reverse proxy che la documentazione chiede di configurare: toglie il
 * prefisso e passa il resto all'Hub, WebSocket compresi.
 *
 * È qui in trenta righe perché senza non si può provare niente: il fix vive
 * proprio nello spazio fra il browser, il proxy e l'Hub, e nessuno dei test
 * esistenti guarda quello spazio.
 */
const startProxy = (upstreamPort) => new Promise((resolve) => {
  const forward = (req) => ({
    hostname: '127.0.0.1',
    port: upstreamPort,
    method: req.method,
    headers: req.headers,
    path: req.url.startsWith(PREFIX) ? req.url.slice(PREFIX.length) || '/' : req.url,
  })

  const server = createServer((req, res) => {
    const upstream = request(forward(req), (upstreamRes) => {
      res.writeHead(upstreamRes.statusCode, upstreamRes.headers)
      upstreamRes.pipe(res)
    })
    req.pipe(upstream)
  })

  server.on('upgrade', (req, socket) => {
    const upstream = request(forward(req))
    upstream.on('upgrade', (upstreamRes, upstreamSocket, head) => {
      const headers = Object.entries(upstreamRes.headers)
        .map(([name, value]) => `${name}: ${value}`)
        .join('\r\n')
      socket.write(`HTTP/1.1 ${upstreamRes.statusCode} ${upstreamRes.statusMessage}\r\n${headers}\r\n\r\n`)
      if (head?.length) socket.write(head)
      upstreamSocket.pipe(socket).pipe(upstreamSocket)
    })
    upstream.end()
  })

  server.listen(0, '127.0.0.1', () => resolve(server))
})

/**
 * Il BASE calcolato dalla riga vera della pagina, non da una sua copia.
 *
 * Una copia si sarebbe allineata al primo refuso e avrebbe continuato a
 * passare mentre il browser sbagliava indirizzo.
 */
const baseFor = (pathname) => {
  const line = PAIRING_PAGE.split('\n').find((row) => row.includes('const BASE ='))
  assert.ok(line, 'la pagina non calcola più un BASE')
  return new Function('location', `${line}\nreturn BASE`)({ pathname })
}

let db
let hub
let proxy
let origin

before(async () => {
  db = openDatabase(':memory:')
  hub = createHub({ config: testConfig, db, adminToken: ADMIN, log: silentLog })
  const hubPort = await hub.start()
  proxy = await startProxy(hubPort)
  origin = `http://127.0.0.1:${proxy.address().port}`
})

after(async () => {
  await new Promise((resolve) => proxy.close(resolve))
  await hub.stop()
  db.close()
})

const api = async (path, { method = 'GET', token, body } = {}) => {
  const res = await fetch(origin + path, {
    method,
    headers: {
      ...(token ? { authorization: `Bearer ${token}` } : {}),
      ...(body ? { 'content-type': 'application/json' } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  })
  return { status: res.status, body: await res.json().catch(() => null) }
}

test("il BASE è vuoto quando l'Hub sta alla radice", () => {
  assert.equal(baseFor('/'), '')
  assert.equal(baseFor('/index.html'), '')
})

test("il BASE è il prefisso quando l'Hub sta sotto un reverse proxy", () => {
  assert.equal(baseFor('/crylog/'), '/crylog')
  assert.equal(baseFor('/crylog/index.html'), '/crylog')
  assert.equal(baseFor('/casa/crylog/'), '/casa/crylog')
})

test('nessuna chiamata della pagina parte dalla radice del dominio', () => {
  const chiamate = [...PAIRING_PAGE.matchAll(/fetch\((.{0,12})/g)].map((match) => match[1])
  assert.ok(chiamate.length > 0, 'la pagina non chiama più nessun endpoint')

  for (const chiamata of chiamate) {
    assert.ok(
      chiamata.startsWith('BASE +'),
      `una fetch non passa dal BASE e finirebbe fuori dal prefisso: fetch(${chiamata}`,
    )
  }

  // Il WebSocket non passa da fetch: va guardato a parte, o resta l'unico
  // indirizzo sbagliato di tutta la pagina.
  assert.match(PAIRING_PAGE, /new WebSocket\(scheme \+ location\.host \+ BASE \+ '\/ws/)
})

test('la pagina si apre attraverso il prefisso', async () => {
  const res = await fetch(`${origin}${PREFIX}/`)

  assert.equal(res.status, 200)
  assert.match(await res.text(), /<title>CryLog/)
})

test('gli endpoint rispondono attraverso il prefisso', async () => {
  const code = await api(`${PREFIX}/pairing-codes`, { method: 'POST', token: ADMIN })
  assert.equal(code.status, 201)

  const paired = await api(`${PREFIX}/pair`, {
    method: 'POST',
    body: { code: code.body.code, role: 'parent', name: 'Browser' },
  })
  assert.equal(paired.status, 201)

  const devices = await api(`${PREFIX}/devices`, { token: paired.body.token })
  assert.equal(devices.status, 200)
  assert.ok(devices.body.devices.some((device) => device.id === paired.body.deviceId))
})

test('il WebSocket si apre attraverso il prefisso', async () => {
  const code = await api(`${PREFIX}/pairing-codes`, { method: 'POST', token: ADMIN })
  const device = (await api(`${PREFIX}/pair`, {
    method: 'POST',
    body: { code: code.body.code, role: 'parent', name: 'Browser WS' },
  })).body

  const socket = new WebSocket(
    `${origin.replace('http://', 'ws://')}${PREFIX}/ws?token=${encodeURIComponent(device.token)}`,
  )

  const welcome = await new Promise((resolve, reject) => {
    socket.on('message', (raw) => resolve(JSON.parse(raw.toString())))
    socket.on('error', reject)
  })
  socket.close()

  assert.equal(welcome.type, 'welcome')
  assert.equal(welcome.deviceId, device.deviceId)
})

test("l'indirizzo che costruisce il browser sotto il prefisso è quello che risponde", async () => {
  // Chiude il cerchio: il BASE lo calcola la pagina come farebbe nel browser,
  // e l'indirizzo che ne esce viene chiesto davvero, attraverso il proxy.
  const base = baseFor(`${PREFIX}/`)
  const res = await fetch(`${origin}${base}/health`)

  assert.equal(res.status, 200)
  assert.equal((await res.json()).status, 'ok')
})
