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

export const config = {
  port: Number(process.env.CRYLOG_PORT ?? 8080),
  // Bind esplicito: dentro il network namespace del sidecar Tailscale l'unico
  // ingresso legittimo e' tailscale serve, che arriva da localhost.
  host: process.env.CRYLOG_HOST ?? '0.0.0.0',
  dataDir: process.env.CRYLOG_DATA_DIR ?? './data',
}
