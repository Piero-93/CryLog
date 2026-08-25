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
import {
  checkPairingCode,
  constantTimeEquals,
  generateDeviceToken,
  generatePairingCode,
  hashSecret,
  normalizePairingCode,
} from '../src/pairing.js'

test('i codici generati sono nel formato atteso', () => {
  for (let i = 0; i < 50; i++) {
    assert.match(generatePairingCode(), /^[0-9ABCDEFGHJKMNPQRSTVWXYZ]{4}-[0-9ABCDEFGHJKMNPQRSTVWXYZ]{4}$/)
  }
})

test('i codici generati non contengono caratteri ambigui', () => {
  const codes = Array.from({ length: 200 }, generatePairingCode).join('')
  for (const ambiguous of ['I', 'L', 'O', 'U']) {
    assert.ok(!codes.includes(ambiguous), `il codice non deve contenere "${ambiguous}"`)
  }
})

test('la normalizzazione perdona le confusioni tipiche di chi legge un codice', () => {
  const expected = '1234-0V78'
  for (const input of ['1234-0V78', '1234 0v78', ' 12340V78 ', 'I234-OU78', 'l234-ou78']) {
    assert.equal(normalizePairingCode(input), expected, `input: "${input}"`)
  }
})

test('la normalizzazione rifiuta input malformati', () => {
  for (const input of ['', '123', '1234-0V789', 'ZZZZ-!!!!', null, 42, undefined]) {
    assert.equal(normalizePairingCode(input), null, `input: ${JSON.stringify(input)}`)
  }
})

test('un codice valido e non scaduto passa', () => {
  const now = 1_000_000
  assert.deepEqual(checkPairingCode({ usedAt: null, expiresAt: now + 1 }, now), { ok: true })
})

test('un codice gia usato viene rifiutato', () => {
  const now = 1_000_000
  const result = checkPairingCode({ usedAt: now - 5, expiresAt: now + 10_000 }, now)
  assert.deepEqual(result, { ok: false, reason: 'already_used' })
})

test('un codice scaduto viene rifiutato, anche esattamente alla scadenza', () => {
  const now = 1_000_000
  assert.equal(checkPairingCode({ usedAt: null, expiresAt: now - 1 }, now).reason, 'expired')
  assert.equal(checkPairingCode({ usedAt: null, expiresAt: now }, now).reason, 'expired')
})

test('un codice sconosciuto viene rifiutato', () => {
  assert.deepEqual(checkPairingCode(undefined, 1), { ok: false, reason: 'unknown_code' })
})

test('i token generati sono unici e di entropia adeguata', () => {
  const tokens = new Set(Array.from({ length: 500 }, generateDeviceToken))
  assert.equal(tokens.size, 500)
  assert.ok([...tokens][0].length >= 42, 'un token da 32 byte in base64url')
})

test('lo stesso segreto produce sempre lo stesso hash, segreti diversi no', () => {
  assert.equal(hashSecret('abc'), hashSecret('abc'))
  assert.notEqual(hashSecret('abc'), hashSecret('abd'))
  assert.equal(hashSecret('abc').length, 64)
})

test('il confronto a tempo costante distingue i valori senza esplodere sui tipi', () => {
  assert.ok(constantTimeEquals('token', 'token'))
  assert.ok(!constantTimeEquals('token', 'tokes'))
  assert.ok(!constantTimeEquals('token', 'token-piu-lungo'))
  assert.ok(!constantTimeEquals(null, 'token'))
  assert.ok(!constantTimeEquals('token', undefined))
})
