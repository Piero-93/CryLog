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

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { countReachedDevices, createRegistry } from '../src/fanout.js'

const conn = (role, deviceId, deliverable = true) => ({
  role,
  deviceId,
  lastSeenAt: 0,
  sent: [],
  send(message) {
    if (!deliverable) return false
    this.sent.push(message)
    return true
  },
})

test('un evento raggiunge tutti i Parent Node connessi', () => {
  const registry = createRegistry()
  const p1 = conn('parent', 'p1')
  const p2 = conn('parent', 'p2')
  registry.add(p1)
  registry.add(p2)

  assert.equal(registry.broadcastToRole('parent', { type: 'noise' }), 2)
  assert.equal(p1.sent.length, 1)
  assert.equal(p2.sent.length, 1)
})

test('un Nursery Node non riceve gli eventi destinati ai Parent Node', () => {
  const registry = createRegistry()
  const nursery = conn('nursery', 'n1')
  registry.add(nursery)
  registry.add(conn('parent', 'p1'))

  registry.broadcastToRole('parent', { type: 'noise' })
  assert.deepEqual(nursery.sent, [])
})

test('una connessione morta non viene contata come consegnata', () => {
  const registry = createRegistry()
  registry.add(conn('parent', 'vivo'))
  registry.add(conn('parent', 'morto', false))

  assert.equal(registry.broadcastToRole('parent', { type: 'noise' }), 1)
})

test('rimuovere una connessione la esclude dal fan-out', () => {
  const registry = createRegistry()
  const parent = conn('parent', 'p1')
  const remove = registry.add(parent)

  remove()
  assert.equal(registry.broadcastToRole('parent', { type: 'noise' }), 0)
  assert.equal(registry.size, 0)
})

test('un device e online finche gli resta almeno una connessione', () => {
  const registry = createRegistry()
  const first = registry.add(conn('parent', 'p1'))
  registry.add(conn('parent', 'p1'))

  first()
  assert.ok(registry.isOnline('p1'), 'la seconda connessione lo tiene online')
  assert.equal(registry.listByDevice('p1').length, 1)
})

test('un device mai connesso non risulta online', () => {
  assert.equal(createRegistry().isOnline('sconosciuto'), false)
})

test('due socket dello stesso device contano come un solo device raggiunto', () => {
  const connections = [conn('parent', 'p1'), conn('parent', 'p1'), conn('parent', 'p2')]
  assert.equal(countReachedDevices(connections), 2)
})
