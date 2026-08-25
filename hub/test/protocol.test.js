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
import { parseClientMessage } from '../src/protocol.js'

test('un heartbeat viene accettato', () => {
  assert.deepEqual(parseClientMessage('{"type":"heartbeat"}'), { ok: true, message: { type: 'heartbeat' } })
})

test('un evento rumore completo viene accettato', () => {
  const result = parseClientMessage(JSON.stringify({ type: 'noise', startedAt: 1700, endedAt: 1800, peakDb: 72.5 }))
  assert.deepEqual(result.message, { type: 'noise', startedAt: 1700, endedAt: 1800, peakDb: 72.5 })
})

test('un evento rumore ancora in corso ha endedAt e peakDb nulli', () => {
  const result = parseClientMessage('{"type":"noise","startedAt":1700}')
  assert.deepEqual(result.message, { type: 'noise', startedAt: 1700, endedAt: null, peakDb: null })
})

test('un evento rumore senza startedAt viene rifiutato', () => {
  assert.equal(parseClientMessage('{"type":"noise"}').error, 'invalid_started_at')
})

test('valori numerici non finiti vengono rifiutati', () => {
  assert.equal(parseClientMessage('{"type":"noise","startedAt":"adesso"}').error, 'invalid_started_at')
  assert.equal(parseClientMessage('{"type":"noise","startedAt":1,"peakDb":"forte"}').error, 'invalid_peak_db')
  assert.equal(parseClientMessage('{"type":"noise","startedAt":1,"endedAt":true}').error, 'invalid_ended_at')
})

test('JSON non valido non fa esplodere il parser', () => {
  assert.equal(parseClientMessage('non-json').error, 'invalid_json')
  assert.equal(parseClientMessage('').error, 'invalid_json')
})

test('un messaggio che non e un oggetto viene rifiutato', () => {
  for (const raw of ['null', '[1,2,3]', '"stringa"', '42']) {
    assert.equal(parseClientMessage(raw).error, 'invalid_message', `input: ${raw}`)
  }
})

test('un tipo sconosciuto viene rifiutato', () => {
  assert.equal(parseClientMessage('{"type":"shutdown"}').error, 'unknown_type')
})

test('un token FCM viene accettato solo se plausibile', () => {
  assert.equal(parseClientMessage('{"type":"fcm-token","token":"abc"}').message.token, 'abc')
  assert.equal(parseClientMessage('{"type":"fcm-token","token":""}').error, 'invalid_fcm_token')
  assert.equal(parseClientMessage(JSON.stringify({ type: 'fcm-token', token: 'x'.repeat(513) })).error, 'invalid_fcm_token')
})
