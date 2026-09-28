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

// Ogni riga porta l'ora in UTC. Senza, il log diceva cosa era successo ma non
// quando: non si poteva metterlo accanto a quello che avevano visto i
// telefoni, ne' dire se due connessioni erano arrivate a un secondo o a
// un'ora di distanza.
export function createLogger({ sink = console, now = () => new Date() } = {}) {
  const line = (message) => `${now().toISOString()} ${message}`
  return {
    info: (message) => sink.log(line(message)),
    warn: (message) => sink.warn(line(message)),
    error: (message) => sink.error(line(message)),
  }
}
