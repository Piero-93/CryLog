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

import { DatabaseSync } from 'node:sqlite'
import { mkdirSync } from 'node:fs'
import { join } from 'node:path'

const SCHEMA = `
  PRAGMA journal_mode = WAL;
  PRAGMA foreign_keys = ON;

  CREATE TABLE IF NOT EXISTS settings (
    key        TEXT PRIMARY KEY,
    value      TEXT NOT NULL
  );

  CREATE TABLE IF NOT EXISTS devices (
    id         TEXT PRIMARY KEY,
    role       TEXT NOT NULL CHECK (role IN ('nursery', 'parent')),
    name       TEXT NOT NULL,
    token_hash TEXT NOT NULL UNIQUE,
    fcm_token  TEXT,
    last_seen  INTEGER,
    created_at INTEGER NOT NULL
  );

  CREATE TABLE IF NOT EXISTS pairing_codes (
    code_hash  TEXT PRIMARY KEY,
    created_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL,
    used_at    INTEGER
  );

  CREATE TABLE IF NOT EXISTS events (
    id         TEXT PRIMARY KEY,
    nursery_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    started_at INTEGER NOT NULL,
    ended_at   INTEGER,
    peak_db    REAL,
    created_at INTEGER NOT NULL
  );

  CREATE INDEX IF NOT EXISTS idx_events_started_at ON events (started_at DESC);
`

const toDevice = (row) => row && {
  id: row.id,
  role: row.role,
  name: row.name,
  fcmToken: row.fcm_token,
  lastSeen: row.last_seen,
  createdAt: row.created_at,
}

const toEvent = (row) => row && {
  id: row.id,
  nurseryId: row.nursery_id,
  nurseryName: row.nursery_name ?? null,
  startedAt: row.started_at,
  endedAt: row.ended_at,
  peakDb: row.peak_db,
  createdAt: row.created_at,
}

export function openDatabase(dataDir) {
  const isMemory = dataDir === ':memory:'
  if (!isMemory) mkdirSync(dataDir, { recursive: true })

  const db = new DatabaseSync(isMemory ? ':memory:' : join(dataDir, 'crylog.db'))
  db.exec(SCHEMA)

  const stmt = {
    getSetting: db.prepare('SELECT value FROM settings WHERE key = ?'),
    setSetting: db.prepare('INSERT INTO settings (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value'),

    insertDevice: db.prepare('INSERT INTO devices (id, role, name, token_hash, created_at) VALUES (?, ?, ?, ?, ?)'),
    deviceByTokenHash: db.prepare('SELECT * FROM devices WHERE token_hash = ?'),
    deviceById: db.prepare('SELECT * FROM devices WHERE id = ?'),
    devicesByRole: db.prepare('SELECT * FROM devices WHERE role = ? ORDER BY created_at'),
    allDevices: db.prepare('SELECT * FROM devices ORDER BY created_at'),
    deleteDevice: db.prepare('DELETE FROM devices WHERE id = ?'),
    touchDevice: db.prepare('UPDATE devices SET last_seen = ? WHERE id = ?'),
    setFcmToken: db.prepare('UPDATE devices SET fcm_token = ? WHERE id = ?'),
    setRole: db.prepare('UPDATE devices SET role = ?, name = ? WHERE id = ?'),

    insertPairingCode: db.prepare('INSERT INTO pairing_codes (code_hash, created_at, expires_at) VALUES (?, ?, ?)'),
    pairingCodeByHash: db.prepare('SELECT * FROM pairing_codes WHERE code_hash = ?'),
    consumePairingCode: db.prepare('UPDATE pairing_codes SET used_at = ? WHERE code_hash = ? AND used_at IS NULL'),
    deleteExpiredCodes: db.prepare('DELETE FROM pairing_codes WHERE expires_at < ?'),

    insertEvent: db.prepare('INSERT INTO events (id, nursery_id, started_at, ended_at, peak_db, created_at) VALUES (?, ?, ?, ?, ?, ?)'),

    // Il nome arriva dalla join: un elenco di identificativi non dice a nessuno
    // quale stanza abbia fatto rumore.
    recentEvents: db.prepare(`
      SELECT e.*, d.name AS nursery_name
      FROM events e LEFT JOIN devices d ON d.id = e.nursery_id
      ORDER BY e.started_at DESC LIMIT ?
    `),
    eventsByNursery: db.prepare(`
      SELECT e.*, d.name AS nursery_name
      FROM events e LEFT JOIN devices d ON d.id = e.nursery_id
      WHERE e.nursery_id = ? ORDER BY e.started_at DESC LIMIT ?
    `),
  }

  return {
    close: () => db.close(),

    getSetting: (key) => stmt.getSetting.get(key)?.value ?? null,
    setSetting: (key, value) => { stmt.setSetting.run(key, value) },

    createDevice: ({ id, role, name, tokenHash, createdAt }) => {
      stmt.insertDevice.run(id, role, name, tokenHash, createdAt)
      return toDevice(stmt.deviceById.get(id))
    },
    findDeviceByTokenHash: (hash) => toDevice(stmt.deviceByTokenHash.get(hash)),
    findDeviceById: (id) => toDevice(stmt.deviceById.get(id)),
    listDevices: () => stmt.allDevices.all().map(toDevice),
    listDevicesByRole: (role) => stmt.devicesByRole.all(role).map(toDevice),
    deleteDevice: (id) => stmt.deleteDevice.run(id).changes > 0,
    touchDevice: (id, at) => { stmt.touchDevice.run(at, id) },
    setFcmToken: (id, token) => { stmt.setFcmToken.run(token, id) },

    // Ruolo e nome cambiano insieme: "Cameretta" e "Telefono" non si scambiano
    // da soli, e un Nursery che si chiama Telefono confonde chi legge il log.
    setRole: (id, role, name) => { stmt.setRole.run(role, name, id) },

    createPairingCode: ({ codeHash, createdAt, expiresAt }) => {
      stmt.insertPairingCode.run(codeHash, createdAt, expiresAt)
    },
    findPairingCode: (codeHash) => {
      const row = stmt.pairingCodeByHash.get(codeHash)
      return row && { codeHash: row.code_hash, createdAt: row.created_at, expiresAt: row.expires_at, usedAt: row.used_at }
    },
    // Ritorna false se il codice era gia' stato usato: l'atomicita' della UPDATE
    // impedisce che due device in corsa consumino lo stesso codice.
    consumePairingCode: (codeHash, at) => stmt.consumePairingCode.run(at, codeHash).changes > 0,
    deleteExpiredPairingCodes: (now) => stmt.deleteExpiredCodes.run(now).changes,

    createEvent: ({ id, nurseryId, startedAt, endedAt, peakDb, createdAt }) => {
      stmt.insertEvent.run(id, nurseryId, startedAt, endedAt ?? null, peakDb ?? null, createdAt)
      return { id, nurseryId, startedAt, endedAt: endedAt ?? null, peakDb: peakDb ?? null, createdAt }
    },
    listRecentEvents: (limit) => stmt.recentEvents.all(limit).map(toEvent),
    listEventsByNursery: (nurseryId, limit) => stmt.eventsByNursery.all(nurseryId, limit).map(toEvent),
  }
}
