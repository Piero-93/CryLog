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

import {
  checkPairingCode,
  constantTimeEquals,
  generateDeviceId,
  generateDeviceToken,
  generatePairingCode,
  hashSecret,
  normalizePairingCode,
} from './pairing.js'
import { ROLES } from './protocol.js'
import { PAIRING_PAGE } from './ui.js'

const MAX_BODY_BYTES = 8 * 1024

const send = (res, status, payload) => {
  const body = JSON.stringify(payload)
  res.writeHead(status, { 'content-type': 'application/json', 'content-length': Buffer.byteLength(body) })
  res.end(body)
}

const readJsonBody = (req) => new Promise((resolve) => {
  const chunks = []
  let size = 0
  req.on('data', (chunk) => {
    size += chunk.length
    if (size > MAX_BODY_BYTES) {
      resolve({ ok: false, error: 'body_too_large' })
      req.destroy()
      return
    }
    chunks.push(chunk)
  })
  req.on('end', () => {
    if (chunks.length === 0) return resolve({ ok: true, body: {} })
    try {
      resolve({ ok: true, body: JSON.parse(Buffer.concat(chunks).toString('utf8')) })
    } catch {
      resolve({ ok: false, error: 'invalid_json' })
    }
  })
  req.on('error', () => resolve({ ok: false, error: 'read_error' }))
})

export const bearerToken = (req) => {
  const header = req.headers.authorization
  if (typeof header !== 'string' || !header.startsWith('Bearer ')) return null
  const token = header.slice('Bearer '.length).trim()
  return token.length > 0 ? token : null
}

export function createHttpHandler({ db, config, adminToken, registry, log, startedAt, now = Date.now }) {
  const requireDevice = (req) => {
    const token = bearerToken(req)
    if (!token) return null
    return db.findDeviceByTokenHash(hashSecret(token))
  }

  const isAdmin = (req) => {
    const token = bearerToken(req)
    return token !== null && constantTimeEquals(token, adminToken)
  }

  return async function handle(req, res) {
    const url = new URL(req.url, 'http://localhost')
    const path = url.pathname
    const method = req.method

    if (method === 'GET' && (path === '/' || path === '/index.html')) {
      res.writeHead(200, { 'content-type': 'text/html; charset=utf-8' })
      res.end(PAIRING_PAGE)
      return
    }

    if (method === 'GET' && path === '/health') {
      return send(res, 200, {
        status: 'ok',
        version: process.env.CRYLOG_VERSION ?? 'dev',
        uptimeSeconds: Math.floor((now() - startedAt) / 1000),
        connections: registry.size,
      })
    }

    if (method === 'POST' && path === '/pairing-codes') {
      // Anche un dispositivo gia' accoppiato puo' invitarne un altro: altrimenti
      // aggiungere un telefono richiederebbe un terminale e l'admin token, cosa
      // impossibile proprio quando serve, cioe' lontano da casa.
      if (!isAdmin(req) && !requireDevice(req)) return send(res, 401, { error: 'unauthorized' })
      const code = generatePairingCode()
      const createdAt = now()
      const expiresAt = createdAt + config.pairingCodeTtlMs
      db.deleteExpiredPairingCodes(createdAt)
      db.createPairingCode({ codeHash: hashSecret(code), createdAt, expiresAt })
      return send(res, 201, { code, expiresAt })
    }

    if (method === 'POST' && path === '/pair') {
      const parsed = await readJsonBody(req)
      if (!parsed.ok) return send(res, 400, { error: parsed.error })

      const { code, role, name } = parsed.body
      const normalized = normalizePairingCode(code)
      if (!normalized) return send(res, 400, { error: 'invalid_code_format' })
      if (!ROLES.includes(role)) return send(res, 400, { error: 'invalid_role' })
      if (typeof name !== 'string' || name.trim().length === 0 || name.length > 64) {
        return send(res, 400, { error: 'invalid_name' })
      }

      const at = now()
      const codeHash = hashSecret(normalized)
      const check = checkPairingCode(db.findPairingCode(codeHash), at)
      if (!check.ok) return send(res, 403, { error: check.reason })
      if (!db.consumePairingCode(codeHash, at)) return send(res, 403, { error: 'already_used' })

      const token = generateDeviceToken()
      const device = db.createDevice({
        id: generateDeviceId(),
        role,
        name: name.trim(),
        tokenHash: hashSecret(token),
        createdAt: at,
      })
      return send(res, 201, { deviceId: device.id, role: device.role, name: device.name, token })
    }

    if (method === 'GET' && path === '/devices') {
      if (!requireDevice(req) && !isAdmin(req)) return send(res, 401, { error: 'unauthorized' })
      const devices = db.listDevices().map((d) => ({
        id: d.id,
        role: d.role,
        name: d.name,
        lastSeen: d.lastSeen,
        online: registry.isOnline(d.id),
      }))
      return send(res, 200, { devices })
    }

    if (method === 'DELETE' && path.startsWith('/devices/')) {
      if (!isAdmin(req)) return send(res, 401, { error: 'unauthorized' })
      const id = decodeURIComponent(path.slice('/devices/'.length))
      return db.deleteDevice(id)
        ? send(res, 200, { deleted: id })
        : send(res, 404, { error: 'not_found' })
    }

    // Cambiare ruolo senza rifare il pairing.
    //
    // Il dispositivo e' gia' noto e ha gia' il suo token: obbligarlo a
    // ricominciare da un codice nuovo non aggiungeva sicurezza, aggiungeva
    // solo passaggi. Conseguenza accettata: un token rubato ora permette
    // entrambi i ruoli invece di uno solo.
    if (method === 'POST' && path === '/device/role') {
      const device = requireDevice(req)
      if (!device) return send(res, 401, { error: 'unauthorized' })

      const parsed = await readJsonBody(req)
      if (!parsed.ok) return send(res, 400, { error: parsed.error })

      const { role, name: rawName } = parsed.body
      const name = typeof rawName === 'string' ? rawName.trim() : ''
      if (!ROLES.includes(role)) return send(res, 400, { error: 'invalid_role' })
      if (name.length === 0 || name.length > 64) return send(res, 400, { error: 'invalid_name' })

      db.setRole(device.id, role, name)
      log.info(`ruolo cambiato: "${device.name}" ${device.role} -> ${role} ("${name}")`)

      // Le connessioni aperte portano ancora il ruolo vecchio, e il fan-out
      // smista per ruolo. Chiuderle e' piu' semplice che mutarle a caldo, e il
      // client si riconnette da solo: se era un Nursery, il close avvisa anche
      // i Parent che non c'e' piu'.
      for (const connection of registry.listByDevice(device.id)) connection.terminate()

      return send(res, 200, { id: device.id, role, name })
    }

    if (method === 'GET' && path === '/events') {
      if (!requireDevice(req) && !isAdmin(req)) return send(res, 401, { error: 'unauthorized' })
      const raw = Number(url.searchParams.get('limit') ?? 50)
      const limit = Number.isFinite(raw) ? Math.min(Math.max(Math.trunc(raw), 1), 500) : 50
      const nurseryId = url.searchParams.get('nurseryId')
      const events = nurseryId
        ? db.listEventsByNursery(nurseryId, limit)
        : db.listRecentEvents(limit)
      return send(res, 200, { events })
    }

    return send(res, 404, { error: 'not_found' })
  }
}
