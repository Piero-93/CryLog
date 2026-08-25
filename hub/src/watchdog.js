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

// Un Nursery Node silenzioso e' indistinguibile da una stanza tranquilla: e' il
// failure mode che questo modulo esiste per rendere impossibile.
export function findStaleConnections(connections, now, offlineAfterMs) {
  return connections.filter(
    (c) => c.role === 'nursery' && now - c.lastSeenAt > offlineAfterMs,
  )
}

export function createWatchdog({ registry, onStale, intervalMs, offlineAfterMs, now = Date.now }) {
  let timer = null

  const tick = () => {
    const stale = findStaleConnections(registry.list(), now(), offlineAfterMs)
    for (const connection of stale) onStale(connection)
  }

  return {
    start() {
      if (timer) return
      timer = setInterval(tick, intervalMs)
      timer.unref?.()
    },
    stop() {
      if (!timer) return
      clearInterval(timer)
      timer = null
    },
    tick,
  }
}
