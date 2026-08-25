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

// Registro delle connessioni vive. Non conosce WebSocket: una connessione e'
// un oggetto con send(), quindi la logica di fan-out si testa senza rete.
export function createRegistry() {
  const connections = new Set()

  const add = (connection) => {
    connections.add(connection)
    return () => connections.delete(connection)
  }

  const list = () => [...connections]

  const listByRole = (role) => list().filter((c) => c.role === role)

  const listByDevice = (deviceId) => list().filter((c) => c.deviceId === deviceId)

  const broadcastToRole = (role, message) => {
    let delivered = 0
    for (const connection of listByRole(role)) {
      if (connection.send(message)) delivered++
    }
    return delivered
  }

  const isOnline = (deviceId) => list().some((c) => c.deviceId === deviceId)

  return { add, list, listByRole, listByDevice, broadcastToRole, isOnline, get size() { return connections.size } }
}

// Un Parent Node puo' avere piu' connessioni aperte (riconnessione in corso,
// app aperta su due schermi). Contiamo i device raggiunti, non i socket.
export function countReachedDevices(connections) {
  return new Set(connections.map((c) => c.deviceId)).size
}

/**
 * Chi va raggiunto via push: i Parent Node che il WebSocket non copre.
 *
 * Mandare la push anche a chi e' gia' connesso raddoppierebbe l'avviso senza
 * aggiungere nulla, e consumerebbe batteria su un dispositivo che ha gia'
 * ricevuto l'evento in tempo reale.
 */
export function selectPushTargets(parents, isOnline) {
  return parents.filter((parent) => parent.fcmToken && !isOnline(parent.id))
}
