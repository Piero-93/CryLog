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

import { randomBytes } from 'node:crypto'
import { config } from './config.js'
import { openDatabase } from './db.js'
import { createHub } from './hub.js'

const db = openDatabase(config.dataDir)

function resolveAdminToken() {
  if (config.adminToken) return config.adminToken

  const stored = db.getSetting('admin_token')
  if (stored) return stored

  const generated = randomBytes(24).toString('base64url')
  db.setSetting('admin_token', generated)
  console.log('')
  console.log('  Admin token generato (serve per creare i codici di pairing):')
  console.log(`      ${generated}`)
  console.log('  Impostalo come CRYLOG_ADMIN_TOKEN per non dipendere dal database.')
  console.log('')
  return generated
}

const hub = createHub({ config, db, adminToken: resolveAdminToken() })

await hub.start()
console.log(`crylog-hub in ascolto su ${config.host}:${config.port}`)
console.log(`  offline dopo ${config.offlineAfterMs / 1000}s senza segnali dal Nursery Node`)

let shuttingDown = false
for (const signal of ['SIGTERM', 'SIGINT']) {
  process.on(signal, () => {
    if (shuttingDown) return
    shuttingDown = true
    console.log(`${signal} ricevuto, chiusura`)
    hub.stop().then(() => {
      db.close()
      process.exit(0)
    })
    // Se un socket resta appeso non trasciniamo giu' il container con noi.
    setTimeout(() => process.exit(0), 5000).unref()
  })
}
