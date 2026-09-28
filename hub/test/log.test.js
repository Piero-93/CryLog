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
import { createLogger } from '../src/log.js'

test('ogni riga del log comincia con l\'ora in UTC', () => {
  const lines = []
  const sink = { log: (l) => lines.push(['info', l]), warn: (l) => lines.push(['warn', l]), error: (l) => lines.push(['error', l]) }
  const log = createLogger({ sink, now: () => new Date(Date.UTC(2026, 8, 25, 19, 30, 5)) })

  log.info('connesso')
  log.warn('offline')
  log.error('rotto')

  assert.deepEqual(lines, [
    ['info', '2026-09-25T19:30:05.000Z connesso'],
    ['warn', '2026-09-25T19:30:05.000Z offline'],
    ['error', '2026-09-25T19:30:05.000Z rotto'],
  ])
})
