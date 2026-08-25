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

import { createHash, randomBytes, randomInt, timingSafeEqual } from 'node:crypto'

// Crockford base32: niente I, L, O, U — si dettano al telefono senza ambiguita'.
const ALPHABET = '0123456789ABCDEFGHJKMNPQRSTVWXYZ'
const CODE_LENGTH = 8

export function generatePairingCode() {
  let code = ''
  for (let i = 0; i < CODE_LENGTH; i++) code += ALPHABET[randomInt(ALPHABET.length)]
  return `${code.slice(0, 4)}-${code.slice(4)}`
}

export function normalizePairingCode(input) {
  if (typeof input !== 'string') return null
  const cleaned = input.trim().toUpperCase().replace(/[\s-]/g, '')
    .replace(/I/g, '1').replace(/L/g, '1').replace(/O/g, '0').replace(/U/g, 'V')
  if (cleaned.length !== CODE_LENGTH) return null
  if (![...cleaned].every((c) => ALPHABET.includes(c))) return null
  return `${cleaned.slice(0, 4)}-${cleaned.slice(4)}`
}

export const hashSecret = (secret) => createHash('sha256').update(secret).digest('hex')

// Token ad alta entropia, non una password: SHA-256 basta, non serve un KDF lento.
export const generateDeviceToken = () => randomBytes(32).toString('base64url')

export const generateDeviceId = () => randomBytes(8).toString('hex')

export function checkPairingCode(record, now) {
  if (!record) return { ok: false, reason: 'unknown_code' }
  if (record.usedAt !== null) return { ok: false, reason: 'already_used' }
  if (record.expiresAt <= now) return { ok: false, reason: 'expired' }
  return { ok: true }
}

export function constantTimeEquals(a, b) {
  if (typeof a !== 'string' || typeof b !== 'string') return false
  const bufA = Buffer.from(a)
  const bufB = Buffer.from(b)
  if (bufA.length !== bufB.length) return false
  return timingSafeEqual(bufA, bufB)
}
