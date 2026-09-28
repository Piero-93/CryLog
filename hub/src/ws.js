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

import { WebSocketServer } from 'ws'
import { bearerToken } from './http.js'
import { selectPushTargets } from './fanout.js'
import { noisePayload, offlinePayload } from './fcm.js'
import { hashSecret, generateDeviceId } from './pairing.js'
import {
  CLOSE_MONITORING_STOPPED,
  error,
  noiseEvent,
  nurseryOffline,
  nurseryOnline,
  parseClientMessage,
  signal,
  signalUndelivered,
  welcome,
} from './protocol.js'

const rejectUpgrade = (socket, status, reason) => {
  socket.write(`HTTP/1.1 ${status} ${reason}\r\nConnection: close\r\n\r\n`)
  socket.destroy()
}

export function attachWebSocket({ server, db, config, registry, fcm, log = console, now = Date.now }) {
  // Durante lo shutdown i socket vengono chiusi in massa e i loro handler
  // girano dopo che il database e stato chiuso: senza questa guardia
  // finirebbero su statement gia finalizzati.
  let closing = false
  const wss = new WebSocketServer({
    noServer: true,
    // Un SDP con molti candidati arriva a qualche kilobyte: 16 KB starebbero
    // stretti proprio nel messaggio che apre lo stream.
    maxPayload: 64 * 1024,
  })

  server.on('upgrade', (req, socket, head) => {
    const url = new URL(req.url, 'http://localhost')
    if (url.pathname !== '/ws') return rejectUpgrade(socket, 404, 'Not Found')

    // Il token puo' arrivare nell'header o in query: alcuni client WebSocket
    // non permettono header custom durante l'handshake.
    const token = bearerToken(req) ?? url.searchParams.get('token')
    if (!token) return rejectUpgrade(socket, 401, 'Unauthorized')

    const device = db.findDeviceByTokenHash(hashSecret(token))
    if (!device) return rejectUpgrade(socket, 401, 'Unauthorized')

    wss.handleUpgrade(req, socket, head, (ws) => onConnection(ws, device))
  })

  function announceNurseryGone(connection, reason) {
    // Il watchdog puo segnalare la stessa connessione a ogni tick, e dopo
    // terminate() scatta anche l handler close: senza questa guardia il
    // Parent Node riceverebbe lo stesso allarme piu volte.
    if (connection.goneAnnounced) return
    connection.goneAnnounced = true
    const nursery = db.findDeviceById(connection.deviceId)
    if (!nursery) return
    const message = nurseryOffline(nursery, connection.lastSeenAt, reason)
    const delivered = registry.broadcastToRole('parent', message)

    // Un Nursery Node che sparisce va detto anche a chi ha l'app chiusa: da
    // quel momento nessuno sta sorvegliando, ed e' l'informazione piu'
    // importante che questo sistema possa dare.
    const targets = selectPushTargets(db.listDevicesByRole('parent'), registry.isOnline)
    for (const parent of targets) {
      fcm.send(parent.fcmToken, offlinePayload(nursery.id, reason)).then((stillValid) => {
        if (!stillValid) db.setFcmToken(parent.id, null)
      })
    }

    log.warn(
      `nursery "${nursery.name}" offline (${reason}): ${delivered} connessioni, ${targets.length} push`,
    )
  }

  function onConnection(ws, device) {
    const at = now()
    const connection = {
      id: generateDeviceId(),
      deviceId: device.id,
      role: device.role,
      name: device.name,
      lastSeenAt: at,
      // Da quando questa sessione e' aperta: il Parent Node lo mostra come
      // tempo di sorveglianza.
      connectedAt: at,
      send(message) {
        if (ws.readyState !== ws.OPEN) return false
        try {
          ws.send(JSON.stringify(message))
          return true
        } catch (err) {
          log.error(`invio fallito a ${device.name}: ${err.message}`)
          return false
        }
      },
      terminate() {
        try { ws.terminate() } catch { /* gia chiuso */ }
      },
      close(code, reason) {
        try { ws.close(code, reason) } catch { ws.terminate() }
      },
    }

    const previous = registry.listByDevice(device.id)
    const wasOffline = previous.length === 0
    const remove = registry.add(connection)
    db.touchDevice(device.id, at)
    connection.send(welcome(device, at))

    if (device.role === 'nursery' && wasOffline) {
      registry.broadcastToRole('parent', nurseryOnline(device, at))
    }

    // Un Parent Node che si collega deve sapere subito chi sta sorvegliando e
    // da quando: senza, resterebbe all'oscuro fino al primo evento.
    if (device.role === 'parent') {
      for (const other of registry.listByRole('nursery')) {
        const nursery = db.findDeviceById(other.deviceId)
        if (nursery) connection.send(nurseryOnline(nursery, other.connectedAt))
      }
    }
    log.info(`connesso: ${device.role} "${device.name}" (${device.id})`)

    // Due socket dello stesso Nursery Node si coprono a vicenda: finche' uno
    // resta vivo, la caduta dell'altro non viene annunciata a nessuno.
    if (device.role === 'nursery' && previous.length > 0) {
      log.warn(`nursery "${device.name}" ha gia' ${previous.length} connessioni aperte`)
    }

    // Senza token FCM un Parent Node non riceve niente ad app chiusa, e prima
    // lo si scopriva solo da un "0 push" nel log del primo allarme.
    let tokenCheck = null
    if (device.role === 'parent') {
      tokenCheck = setTimeout(() => {
        if (!db.findDeviceById(device.id)?.fcmToken) {
          log.warn(
            `parent "${device.name}" connesso senza token FCM: ad app chiusa non ricevera' push` +
            ' (normale per la pagina web dell\'Hub)',
          )
        }
      }, config.fcmTokenGraceMs ?? 10_000)
      tokenCheck.unref?.()
    }

    ws.on('message', (raw) => {
      const parsed = parseClientMessage(raw.toString())
      if (!parsed.ok) {
        connection.send(error(parsed.error))
        return
      }
      connection.lastSeenAt = now()
      handleMessage(connection, device, parsed.message)
    })

    ws.on('pong', () => { connection.lastSeenAt = now() })

    ws.on('close', (code) => {
      clearTimeout(tokenCheck)
      remove()
      if (closing) return
      db.touchDevice(device.id, now())
      log.info(`disconnesso: ${device.role} "${device.name}"`)
      if (device.role === 'nursery' && !registry.isOnline(device.id)) {
        // Fermato a mano: i Parent Node vanno avvisati lo stesso, perche' da
        // ora nessuno sorveglia, ma senza l'allarme di un guasto.
        announceNurseryGone(connection, code === CLOSE_MONITORING_STOPPED ? 'stopped' : 'disconnected')
      }
    })

    ws.on('error', (err) => log.error(`errore socket ${device.name}: ${err.message}`))
  }

  function handleMessage(connection, device, message) {
    switch (message.type) {
      case 'heartbeat':
        db.touchDevice(device.id, connection.lastSeenAt)
        break

      case 'noise': {
        if (device.role !== 'nursery') {
          connection.send(error('role_not_allowed'))
          return
        }
        const event = db.createEvent({
          id: generateDeviceId(),
          nurseryId: device.id,
          startedAt: message.startedAt,
          endedAt: message.endedAt,
          peakDb: message.peakDb,
          createdAt: connection.lastSeenAt,
        })
        const delivered = registry.broadcastToRole('parent', noiseEvent(event, device))

        // Chi non ha il WebSocket aperto viene raggiunto dalla push: e' il caso
        // normale di notte, con l'app chiusa e il telefono in Doze.
        const targets = selectPushTargets(db.listDevicesByRole('parent'), registry.isOnline)
        for (const parent of targets) {
          fcm.send(parent.fcmToken, noisePayload(event.id)).then((stillValid) => {
            if (!stillValid) db.setFcmToken(parent.id, null)
          })
        }

        log.info(
          `evento rumore da "${device.name}": ${delivered} connessioni, ${targets.length} push`,
        )
        break
      }

      case 'fcm-token':
        // L'app lo rimanda a ogni sessione: nel log va solo quando cambia.
        if (db.findDeviceById(device.id)?.fcmToken !== message.token) {
          log.info(`token FCM registrato per "${device.name}"`)
        }
        db.setFcmToken(device.id, message.token)
        break

      case 'signal': {
        // Il payload non viene letto: SDP e candidati ICE restano affari dei
        // due telefoni. Qui si controlla solo che il destinatario esista e sia
        // raggiungibile, perche' chi aspetta uno stream deve sapere se e' vano.
        const recipient = db.findDeviceById(message.to)
        if (!recipient) {
          connection.send(signalUndelivered(message.to, 'unknown_device'))
          return
        }

        const targets = registry.listByDevice(message.to)
        if (targets.length === 0) {
          connection.send(signalUndelivered(message.to, 'offline'))
          return
        }

        const envelope = signal(device.id, device.name, message.payload)
        const delivered = targets.filter((target) => target.send(envelope)).length
        if (delivered === 0) connection.send(signalUndelivered(message.to, 'offline'))
        break
      }
    }
  }

  // Il ping tiene vive le connessioni attraverso i NAT e aggiorna lastSeenAt
  // anche quando il device non ha nulla da dire.
  const heartbeat = setInterval(() => {
    for (const client of wss.clients) {
      if (client.readyState === client.OPEN) client.ping()
    }
  }, config.heartbeatIntervalMs)
  heartbeat.unref?.()

  return {
    wss,
    onStale(connection) {
      // Un Nursery Node che si e' riconnesso da un'altra rete lascia indietro
      // il socket vecchio, che scade: non e' il Nursery a essere sparito. Conta
      // solo un altro socket che si fa ancora sentire, non uno che sta
      // scadendo nello stesso giro del watchdog.
      const alive = registry.listByDevice(connection.deviceId).some((other) =>
        other !== connection && now() - other.lastSeenAt <= config.offlineAfterMs)
      if (alive) {
        log.info(`chiusa una connessione scaduta di "${connection.name}", un'altra e' viva`)
      } else {
        announceNurseryGone(connection, 'timeout')
      }
      // close() attenderebbe un close frame di risposta che un device
      // congelato non mandera mai, lasciando la connessione nel registro.
      connection.terminate()
    },
    close() {
      closing = true
      clearInterval(heartbeat)
      for (const client of wss.clients) client.terminate()
      wss.close()
    },
  }
}
