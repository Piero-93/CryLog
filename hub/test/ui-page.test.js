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

import test from 'node:test'
import assert from 'node:assert/strict'
import { PAIRING_PAGE } from '../src/ui.js'

const scriptOf = (page) => {
  const open = page.indexOf('<script>')
  const close = page.lastIndexOf('</script>')
  assert.ok(open > 0, 'la pagina non ha un blocco script')
  assert.ok(close > open, 'il blocco script non e chiuso')
  return page.slice(open + '<script>'.length, close)
}

/**
 * La pagina e' un template literal, quindi il suo JavaScript non e' codice per
 * chi lo legge: e' una stringa. `node --check` sul modulo non ci entra, e
 * nemmeno gli altri test, che parlano con l'Hub e non con il browser.
 *
 * Il risultato e' che un errore di sintassi la' dentro passa la CI intera e
 * arriva in produzione, dove lo script semplicemente non parte: niente lista
 * dispositivi, niente codici, niente ascolto. E' gia' successo, con un
 * apostrofo perso fra i due livelli di escape del template literal.
 */
test('il codice della pagina e sintatticamente valido', () => {
  const script = scriptOf(PAIRING_PAGE)
  assert.ok(script.length > 0, 'il blocco script e vuoto')

  // `new Function` analizza il codice senza eseguirlo: nessun DOM necessario,
  // e un errore di sintassi diventa un'eccezione qui invece che nel browser.
  assert.doesNotThrow(() => new Function(script), SyntaxError)
})

/** Controprova: il test sopra deve davvero accorgersi di un apostrofo perso. */
test('un apostrofo non protetto viene riconosciuto come errore', () => {
  const rotto = "const messaggio = 'Serve l'admin token'"
  assert.throws(() => new Function(rotto), SyntaxError)
})
