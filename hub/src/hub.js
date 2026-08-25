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

import { createServer } from 'node:http'
import { createRegistry } from './fanout.js'
import { createHttpHandler } from './http.js'
import { attachWebSocket } from './ws.js'
import { createFcmSender } from './fcm.js'
import { createWatchdog } from './watchdog.js'

const SILENT = { info() {}, warn() {}, error() {} }

// Tutto il cablaggio vive qui e non in index.js, cosi' un test puo' istanziare
// un Hub completo su una porta effimera senza avviare un processo.
export function createHub({ config, db, adminToken, fcm = createFcmSender(), log = console, now = Date.now }) {
  const startedAt = now()
  const registry = createRegistry()
  const handle = createHttpHandler({ db, config, adminToken, registry, startedAt, now })

  const server = createServer((req, res) => {
    handle(req, res).catch((err) => {
      log.error(`errore non gestito su ${req.method} ${req.url}: ${err.stack}`)
      if (!res.headersSent) {
        res.writeHead(500, { 'content-type': 'application/json' })
        res.end(JSON.stringify({ error: 'internal_error' }))
      }
    })
  })

  const sockets = attachWebSocket({ server, db, config, registry, fcm, log, now })

  const watchdog = createWatchdog({
    registry,
    onStale: sockets.onStale,
    intervalMs: config.watchdogTickMs,
    offlineAfterMs: config.offlineAfterMs,
    now,
  })

  return {
    server,
    registry,
    watchdog,
    start: () => new Promise((resolve) => {
      watchdog.start()
      server.listen(config.port, config.host, () => resolve(server.address().port))
    }),
    stop: () => new Promise((resolve) => {
      watchdog.stop()
      sockets.close()
      server.close(() => resolve())
    }),
  }
}

export { SILENT as silentLog }
