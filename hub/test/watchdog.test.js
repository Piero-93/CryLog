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
import { createWatchdog, findStaleConnections } from '../src/watchdog.js'
import { createRegistry } from '../src/fanout.js'

const T0 = 1_000_000
const conn = (role, lastSeenAt, deviceId = role) => ({ role, lastSeenAt, deviceId })

test('un Nursery Node silenzioso oltre la soglia e stale', () => {
  const stale = findStaleConnections([conn('nursery', T0 - 91_000, 'muto')], T0, 90_000)
  assert.equal(stale.length, 1)
  assert.equal(stale[0].deviceId, 'muto')
})

test('il watchdog sorveglia i Nursery Node, non i Parent Node', () => {
  const connections = [conn('parent', T0 - 600_000), conn('nursery', T0)]
  assert.deepEqual(findStaleConnections(connections, T0, 90_000), [])
})

test('la soglia e esclusiva: esattamente al limite non e ancora stale', () => {
  assert.equal(findStaleConnections([conn('nursery', T0 - 90_000)], T0, 90_000).length, 0)
  assert.equal(findStaleConnections([conn('nursery', T0 - 90_001)], T0, 90_000).length, 1)
})

test('il watchdog non allarma finche il Nursery Node risponde', () => {
  const registry = createRegistry()
  const nursery = conn('nursery', T0, 'cameretta')
  registry.add(nursery)

  let clock = T0
  const alarms = []
  const watchdog = createWatchdog({
    registry,
    onStale: (c) => alarms.push(c.deviceId),
    intervalMs: 1000,
    offlineAfterMs: 90_000,
    now: () => clock,
  })

  watchdog.tick()
  assert.deepEqual(alarms, [])

  clock = T0 + 89_000
  watchdog.tick()
  assert.deepEqual(alarms, [], 'a 89 secondi il silenzio e ancora tollerato')

  clock = T0 + 91_000
  watchdog.tick()
  assert.deepEqual(alarms, ['cameretta'], 'a 91 secondi scatta l allarme')
})

test('un Nursery Node che si rifa vivo smette di essere segnalato', () => {
  const registry = createRegistry()
  const nursery = conn('nursery', T0, 'cameretta')
  registry.add(nursery)

  let clock = T0 + 91_000
  const alarms = []
  const watchdog = createWatchdog({
    registry,
    onStale: (c) => alarms.push(c.deviceId),
    intervalMs: 1000,
    offlineAfterMs: 90_000,
    now: () => clock,
  })

  watchdog.tick()
  assert.equal(alarms.length, 1)

  nursery.lastSeenAt = clock
  watchdog.tick()
  assert.equal(alarms.length, 1, 'nessun nuovo allarme dopo il ritorno del battito')
})

test('start e stop sono idempotenti', () => {
  const watchdog = createWatchdog({
    registry: createRegistry(),
    onStale: () => {},
    intervalMs: 10_000,
    offlineAfterMs: 90_000,
  })
  watchdog.start()
  watchdog.start()
  watchdog.stop()
  watchdog.stop()
})
