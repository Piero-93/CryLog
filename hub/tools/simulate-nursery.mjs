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

// Nursery Node finto, per provare i Parent Node quando non si hanno due
// telefoni. Si accoppia da solo, si connette e genera eventi rumore.
//
//   CRYLOG_HUB=https://crylog.<tailnet>.ts.net \
//   CRYLOG_ADMIN=<admin token> \
//   node tools/simulate-nursery.mjs [--name Cameretta] [--every 20] [--once] [--freeze]
//
//   --once    un solo evento, poi esce (chiusura pulita: i Parent Node
//             ricevono nursery-offline con reason "disconnected")
//   --freeze  smette di rispondere senza chiudere, per provare il watchdog
//             (i Parent Node ricevono reason "timeout" dopo la soglia)

const HUB = process.env.CRYLOG_HUB
const ADMIN = process.env.CRYLOG_ADMIN

if (!HUB || !ADMIN) {
  console.error('Servono CRYLOG_HUB e CRYLOG_ADMIN.')
  process.exit(1)
}

const arg = (flag, fallback) => {
  const index = process.argv.indexOf(flag)
  return index === -1 ? fallback : process.argv[index + 1]
}
const has = (flag) => process.argv.includes(flag)

const name = arg('--name', 'Cameretta simulata')
const everyMs = Number(arg('--every', 20)) * 1000

const api = async (path, options = {}) => {
  const res = await fetch(HUB + path, options)
  const body = await res.json().catch(() => ({}))
  if (!res.ok) throw new Error(body.error ?? `HTTP ${res.status}`)
  return body
}

const { code } = await api('/pairing-codes', {
  method: 'POST',
  headers: { authorization: `Bearer ${ADMIN}` },
})

const device = await api('/pair', {
  method: 'POST',
  headers: { 'content-type': 'application/json' },
  body: JSON.stringify({ code, role: 'nursery', name }),
})

console.log(`accoppiato come "${device.name}" (${device.deviceId})`)

const ws = new WebSocket(`${HUB.replace('http', 'ws')}/ws?token=${encodeURIComponent(device.token)}`)

let noiseTimer = null

const sendNoise = () => {
  // Un pianto vero sta fra i 60 e gli 80 dB: qui basta un valore plausibile.
  const peakDb = Math.round((60 + Math.random() * 20) * 10) / 10
  ws.send(JSON.stringify({ type: 'noise', startedAt: Date.now(), peakDb }))
  console.log(`evento rumore inviato: ${peakDb} dB`)
}

ws.addEventListener('open', () => {
  console.log('connesso all Hub')

  if (has('--freeze')) {
    sendNoise()
    console.log('congelato: nessuna risposta da qui in avanti, il watchdog deve accorgersene')
    return
  }

  sendNoise()

  if (has('--once')) {
    setTimeout(() => {
      ws.close()
      console.log('disconnesso')
      process.exit(0)
    }, 1500)
    return
  }

  noiseTimer = setInterval(sendNoise, everyMs)
})

ws.addEventListener('message', (event) => {
  const message = JSON.parse(event.data)
  if (message.type !== 'welcome') console.log('ricevuto:', message.type)
})

ws.addEventListener('error', () => console.error('connessione fallita'))

ws.addEventListener('close', () => {
  if (noiseTimer) clearInterval(noiseTimer)
  console.log('connessione chiusa')
})

process.on('SIGINT', () => {
  ws.close()
  process.exit(0)
})
