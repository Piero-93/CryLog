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

const int = (name, fallback) => {
  const raw = process.env[name]
  if (raw === undefined) return fallback
  const value = Number(raw)
  if (!Number.isFinite(value) || value <= 0) {
    throw new Error(`${name} deve essere un numero positivo, ricevuto: ${raw}`)
  }
  return value
}

export const config = {
  port: int('CRYLOG_PORT', 8080),
  host: process.env.CRYLOG_HOST ?? '0.0.0.0',
  dataDir: process.env.CRYLOG_DATA_DIR ?? './data',

  // Se assente viene generato al primo avvio e stampato nei log.
  adminToken: process.env.CRYLOG_ADMIN_TOKEN ?? null,

  // Un Nursery Node considerato vivo deve farsi sentire entro offlineAfterMs.
  // Tre battiti persi prima di dichiararlo offline: un falso allarme notturno
  // costa piu' di qualche secondo di ritardo.
  heartbeatIntervalMs: int('CRYLOG_HEARTBEAT_MS', 30_000),
  offlineAfterMs: int('CRYLOG_OFFLINE_AFTER_MS', 90_000),
  watchdogTickMs: int('CRYLOG_WATCHDOG_TICK_MS', 15_000),

  pairingCodeTtlMs: int('CRYLOG_PAIRING_TTL_MS', 10 * 60_000),
}
