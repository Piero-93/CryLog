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

// Pagina per generare un codice di pairing dal browser del telefono.
//
// Il codice NON viene mostrato a chiunque apra la pagina: servirebbe a poco
// proteggere l'Hub con un pairing se poi il codice fosse pubblico sulla
// tailnet. Serve l'admin token, che il browser ricorda dopo la prima volta.
export const PAIRING_PAGE = `<!doctype html>
<html lang="it">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>CryLog — nuovo dispositivo</title>
<style>
  :root { color-scheme: light dark; --fg: #101418; --bg: #f6f7f9; --card: #fff; --muted: #5b6472; --accent: #2f6fed; --border: #dfe3e8; }
  @media (prefers-color-scheme: dark) {
    :root { --fg: #e8eaed; --bg: #14171a; --card: #1e2226; --muted: #9aa4b2; --accent: #7aa2f7; --border: #2c3239; }
  }
  * { box-sizing: border-box; }
  body { margin: 0; padding: 24px; background: var(--bg); color: var(--fg);
         font: 16px/1.5 system-ui, -apple-system, "Segoe UI", Roboto, sans-serif; }
  main { max-width: 26rem; margin: 0 auto; }
  h1 { font-size: 1.4rem; margin: 0 0 4px; }
  p.sub { color: var(--muted); margin: 0 0 24px; }
  .card { background: var(--card); border: 1px solid var(--border); border-radius: 14px; padding: 20px; margin-bottom: 16px; }
  label { display: block; font-size: .9rem; color: var(--muted); margin-bottom: 6px; }
  input { width: 100%; padding: 12px; font-size: 1rem; border-radius: 10px;
          border: 1px solid var(--border); background: var(--bg); color: var(--fg); }
  button { width: 100%; padding: 14px; font-size: 1rem; font-weight: 600; margin-top: 12px;
           border: 0; border-radius: 10px; background: var(--accent); color: #fff; cursor: pointer; }
  button:disabled { opacity: .5; cursor: default; }
  .code { font: 700 2.2rem/1.2 ui-monospace, "SF Mono", Menlo, Consolas, monospace;
          letter-spacing: .12em; text-align: center; margin: 8px 0; }
  .expiry { text-align: center; color: var(--muted); font-size: .9rem; }
  .expiry.soon { color: #d14343; }
  .error { color: #d14343; font-size: .9rem; margin-top: 12px; }
  .steps { color: var(--muted); font-size: .9rem; }
  .steps ol { padding-left: 1.2rem; margin: 8px 0 0; }
  h2 { font-size: 1.1rem; margin: 28px 0 12px; }
  .devices { list-style: none; margin: 0; padding: 0; }
  .devices li { display: flex; align-items: center; gap: 12px; padding: 12px 0; border-bottom: 1px solid var(--border); }
  .devices li:last-child { border-bottom: 0; }
  .devices li.empty { color: var(--muted); justify-content: center; }
  .dot { width: 10px; height: 10px; border-radius: 50%; background: var(--border); flex: none; }
  .dot.on { background: #2ea043; }
  .who { flex: 1; min-width: 0; }
  .who strong { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .who span { color: var(--muted); font-size: .85rem; }
  .remove { width: auto; margin: 0; padding: 8px 12px; font-size: .85rem; font-weight: 500;
            background: transparent; color: var(--muted); border: 1px solid var(--border); }
  .rowhead { display: flex; align-items: center; gap: 12px; margin: 28px 0 12px; }
  .rowhead h2 { margin: 0; flex: 1; }
  .events { list-style: none; margin: 0; padding: 0; }
  .events li { display: flex; align-items: baseline; gap: 12px; padding: 10px 0; border-bottom: 1px solid var(--border); }
  .events li:last-child { border-bottom: 0; }
  .events li.empty { color: var(--muted); justify-content: center; }
  .events .what { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .events .peak { color: var(--muted); font-size: .85rem; font-variant-numeric: tabular-nums; }
  .events .when { color: var(--muted); font-size: .85rem; font-variant-numeric: tabular-nums; flex: none; }
  .events li.fresh .what { font-weight: 600; }
  .state { color: var(--muted); font-size: .9rem; }
  .state.live { color: #2ea043; font-weight: 600; }
  .state.bad { color: #d14343; }
  select { width: 100%; padding: 12px; font-size: 1rem; border-radius: 10px; margin-bottom: 4px;
           border: 1px solid var(--border); background: var(--bg); color: var(--fg); }
  audio { display: none; }
</style>
</head>
<body>
<main>
  <h1>CryLog</h1>
  <p class="sub">Cosa succede nella cameretta, dal computer.</p>

  <div class="rowhead">
    <h2>Attivit&agrave;</h2>
    <button class="remove" id="notify">Attiva le notifiche</button>
  </div>
  <div class="card">
    <ul class="events" id="events"><li class="empty">Caricamento...</li></ul>
  </div>

  <div class="rowhead">
    <h2>Ascolto</h2>
    <span class="state" id="listenState"></span>
  </div>
  <div class="card">
    <select id="nursery"></select>
    <button id="listen">Ascolta</button>
    <div class="error" id="listenError" hidden></div>
    <audio id="audio" autoplay playsinline></audio>
  </div>

  <h2>Aggiungi un dispositivo</h2>

  <div class="card" id="auth">
    <label for="token">Admin token</label>
    <input id="token" type="password" autocomplete="off" placeholder="dai log dell'Hub">
    <button id="save">Ricorda su questo dispositivo</button>
  </div>

  <div class="card">
    <button id="generate">Genera un codice</button>
    <div id="result" hidden>
      <div class="code" id="code"></div>
      <div class="expiry" id="expiry"></div>
    </div>
    <div class="error" id="error" hidden></div>
  </div>

  <div class="card steps">
    <strong>Nell'app</strong>
    <ol>
      <li>Scegli il ruolo del dispositivo</li>
      <li>Indirizzo dell'Hub: questo stesso indirizzo</li>
      <li>Inserisci il codice qui sopra</li>
    </ol>
  </div>

  <h2>Dispositivi</h2>
  <div class="card">
    <ul class="devices" id="devices"><li class="empty">Caricamento...</li></ul>
  </div>
</main>

<script>
  const $ = (id) => document.getElementById(id)
  const STORED = 'crylog-admin-token'
  let countdown = null

  const stored = localStorage.getItem(STORED)
  if (stored) {
    $('token').value = stored
    $('auth').hidden = true
  }

  $('save').addEventListener('click', () => {
    const value = $('token').value.trim()
    if (!value) return
    localStorage.setItem(STORED, value)
    $('auth').hidden = true
    loadDevices()
  })

  const showError = (message) => {
    $('error').textContent = message
    $('error').hidden = false
    $('result').hidden = true
    $('auth').hidden = false
  }

  const tick = (expiresAt) => {
    const left = Math.max(0, Math.round((expiresAt - Date.now()) / 1000))
    const minutes = Math.floor(left / 60)
    const seconds = String(left % 60).padStart(2, '0')
    $('expiry').textContent = left > 0 ? 'Scade fra ' + minutes + ':' + seconds : 'Scaduto'
    $('expiry').classList.toggle('soon', left <= 60)
    if (left === 0) clearInterval(countdown)
  }

  const sinceText = (lastSeen) => {
    if (!lastSeen) return 'mai collegato'
    const seconds = Math.round((Date.now() - lastSeen) / 1000)
    if (seconds < 60) return 'visto pochi secondi fa'
    if (seconds < 3600) return 'visto ' + Math.round(seconds / 60) + ' min fa'
    if (seconds < 86400) return 'visto ' + Math.round(seconds / 3600) + ' h fa'
    return 'visto ' + Math.round(seconds / 86400) + ' giorni fa'
  }

  const removeDevice = async (device) => {
    if (!confirm('Rimuovere "' + device.name + '"? Dovra rifare il pairing per tornare.')) return
    const token = localStorage.getItem(STORED)
    await fetch('/devices/' + device.id, {
      method: 'DELETE',
      headers: { authorization: 'Bearer ' + token },
    })
    loadDevices()
  }

  const loadDevices = async () => {
    const token = localStorage.getItem(STORED) || $('token').value.trim()
    const list = $('devices')
    if (!token) {
      list.innerHTML = '<li class="empty">Serve l\\'admin token</li>'
      return
    }

    try {
      const res = await fetch('/devices', { headers: { authorization: 'Bearer ' + token } })
      if (!res.ok) {
        list.innerHTML = '<li class="empty">Non autorizzato</li>'
        return
      }

      const { devices } = await res.json()
      if (devices.length === 0) {
        list.innerHTML = '<li class="empty">Nessun dispositivo collegato</li>'
        return
      }

      fillNurseries(devices)

      list.replaceChildren(...devices.map((device) => {
        const li = document.createElement('li')

        const dot = document.createElement('span')
        dot.className = device.online ? 'dot on' : 'dot'
        dot.title = device.online ? 'collegato' : 'non collegato'

        const who = document.createElement('div')
        who.className = 'who'
        const name = document.createElement('strong')
        name.textContent = device.name
        const detail = document.createElement('span')
        const role = device.role === 'nursery' ? 'Nursery Node' : 'Parent Node'
        detail.textContent = role + ' — ' + (device.online ? 'collegato' : sinceText(device.lastSeen))
        who.append(name, detail)

        const remove = document.createElement('button')
        remove.className = 'remove'
        remove.textContent = 'Rimuovi'
        remove.addEventListener('click', () => removeDevice(device))

        li.append(dot, who, remove)
        return li
      }))
    } catch {
      list.innerHTML = '<li class="empty">Hub non raggiungibile</li>'
    }
  }

  loadDevices()
  setInterval(loadDevices, 5000)

  // --- Attivita' nella cameretta -------------------------------------------
  //
  // Legge /events con l'admin token, che la pagina ha gia': niente pairing del
  // browser, niente Web Push. Finche' la scheda e' aperta basta l'API
  // Notification, e il Web Push — con la cifratura del payload che si porta
  // dietro — non serve affatto.
  const NOTIFY = 'crylog-notify'
  const seen = new Set()
  let seeded = false

  const canNotify = typeof Notification !== 'undefined'
  let notifyOn = canNotify && Notification.permission === 'granted' && localStorage.getItem(NOTIFY) !== '0'

  const clock = (at) =>
    new Date(at).toLocaleTimeString('it-IT', { hour: '2-digit', minute: '2-digit' })

  const refreshNotifyButton = () => {
    const button = $('notify')
    if (!canNotify) {
      // Fuori da un contesto sicuro l'API non esiste: dirlo, invece di offrire
      // un pulsante che non farebbe niente.
      button.textContent = 'Notifiche non disponibili'
      button.disabled = true
      return
    }
    if (Notification.permission === 'denied') {
      button.textContent = 'Notifiche bloccate dal browser'
      button.disabled = true
      return
    }
    button.textContent = notifyOn ? 'Notifiche attive' : 'Attiva le notifiche'
  }

  const notify = (event) => {
    if (!notifyOn || Notification.permission !== 'granted') return
    const peak = event.peakDb == null ? '' : ' (' + Math.round(event.peakDb) + ' dB)'
    new Notification('Rumore da ' + (event.nurseryName || 'cameretta') + peak, {
      body: 'alle ' + clock(event.startedAt),
      // L'id dell'evento come tag: se la stessa notifica arrivasse due volte,
      // il browser la sostituisce invece di impilarla.
      tag: event.id,
    })
  }

  const loadEvents = async () => {
    const token = localStorage.getItem(STORED) || $('token').value.trim()
    const list = $('events')
    if (!token) {
      list.innerHTML = '<li class="empty">Serve il token di amministrazione</li>'
      return
    }

    try {
      const res = await fetch('/events?limit=30', { headers: { authorization: 'Bearer ' + token } })
      if (!res.ok) {
        list.innerHTML = '<li class="empty">Non autorizzato</li>'
        return
      }

      const { events } = await res.json()
      if (events.length === 0) {
        list.innerHTML = '<li class="empty">Ancora nessun rumore</li>'
        seeded = true
        return
      }

      // Dal piu' vecchio al piu' nuovo, cosi' le notifiche arrivano in ordine.
      const fresh = []
      for (let i = events.length - 1; i >= 0; i--) {
        const event = events[i]
        if (seen.has(event.id)) continue
        seen.add(event.id)
        // Al primo caricamento si prende nota e basta: altrimenti aprire la
        // pagina sparerebbe trenta notifiche di rumori gia' passati.
        if (seeded) fresh.push(event)
      }
      fresh.forEach(notify)

      const isFresh = new Set(fresh.map((e) => e.id))
      list.replaceChildren(...events.map((event) => {
        const li = document.createElement('li')
        if (isFresh.has(event.id)) li.className = 'fresh'

        const what = document.createElement('span')
        what.className = 'what'
        what.textContent = event.nurseryName || 'cameretta'

        const peak = document.createElement('span')
        peak.className = 'peak'
        peak.textContent = event.peakDb == null ? '' : Math.round(event.peakDb) + ' dB'

        const when = document.createElement('span')
        when.className = 'when'
        when.textContent = clock(event.startedAt)

        li.append(what, peak, when)
        return li
      }))
      seeded = true
    } catch {
      list.innerHTML = '<li class="empty">Hub non raggiungibile</li>'
    }
  }

  // --- Ascolto dal vivo ----------------------------------------------------
  //
  // Il browser diventa un Parent Node a tutti gli effetti: si accoppia una
  // volta, apre il WebSocket con il proprio token e parla lo stesso signaling
  // dell'app. L'Hub non guarda dentro il payload, quindi non ha dovuto
  // imparare niente di nuovo, e il Nursery Node non e' stato toccato: risponde
  // gia' a chiunque abbia ruolo parent.
  const DEVICE = 'crylog-device-token'
  let ws = null
  let pc = null
  let nurseryId = null
  let listening = false

  const setState = (text, kind) => {
    const el = $('listenState')
    el.textContent = text
    el.className = 'state' + (kind ? ' ' + kind : '')
  }

  const listenError = (text) => {
    $('listenError').textContent = text
    $('listenError').hidden = false
  }

  const fillNurseries = (devices) => {
    const select = $('nursery')
    const nurseries = devices.filter((d) => d.role === 'nursery')
    const chosen = select.value
    select.replaceChildren(...nurseries.map((d) => {
      const option = document.createElement('option')
      option.value = d.id
      option.textContent = d.name + (d.online ? '' : ' (non collegato)')
      return option
    }))
    if (nurseries.some((d) => d.id === chosen)) select.value = chosen
    // Con un Nursery Node solo, scegliere non ha senso: il menu sparisce.
    select.hidden = nurseries.length < 2
    if (!listening) $('listen').disabled = nurseries.length === 0
    if (nurseries.length === 0 && !listening) setState('nessun Nursery Node accoppiato')
  }

  // Un nome diverso per ogni profilo browser: due profili sono due dispositivi,
  // e con lo stesso nome il registro dell'Hub diventa illeggibile.
  const browserName = () => ('Browser ' + Math.random().toString(16).slice(2, 6)).slice(0, 64)

  // Il browser si accoppia da solo: genera un codice con l'admin token che la
  // pagina gia' ha, e lo riscatta per se'. Una volta sola, poi il token resta.
  const ensureDevice = async () => {
    const existing = localStorage.getItem(DEVICE)
    if (existing) return existing

    const admin = localStorage.getItem(STORED) || $('token').value.trim()
    if (!admin) throw new Error("Serve l'admin token.")

    const codeRes = await fetch('/pairing-codes', {
      method: 'POST',
      headers: { authorization: 'Bearer ' + admin },
    })
    if (!codeRes.ok) throw new Error('Codice di pairing non generato.')
    const { code } = await codeRes.json()

    const pairRes = await fetch('/pair', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ code, role: 'parent', name: browserName() }),
    })
    if (!pairRes.ok) throw new Error('Accoppiamento del browser fallito.')

    const { token } = await pairRes.json()
    localStorage.setItem(DEVICE, token)
    return token
  }

  const sendSignal = (payload) => {
    if (!ws || ws.readyState !== WebSocket.OPEN || !nurseryId) return
    ws.send(JSON.stringify({ type: 'signal', to: nurseryId, payload }))
  }

  const play = async () => {
    try {
      await $('audio').play()
      setState('in ascolto', 'live')
    } catch {
      // L'autoplay puo' rifiutare se il gesto dell'utente e' ormai scaduto:
      // dirlo, invece di restare muti senza spiegazioni.
      setState('audio bloccato dal browser: tocca di nuovo Ascolta', 'bad')
    }
  }

  const openPeer = () => {
    // Nessun server ICE: sulla tailnet i due capi si vedono direttamente, e un
    // TURN non c'e' comunque.
    const conn = new RTCPeerConnection({ iceServers: [] })

    conn.ontrack = (event) => {
      $('audio').srcObject = event.streams[0]
      play()
    }

    conn.onicecandidate = (event) => {
      if (!event.candidate) return
      sendSignal({
        kind: 'ice',
        candidate: event.candidate.candidate,
        sdpMid: event.candidate.sdpMid,
        sdpMLineIndex: event.candidate.sdpMLineIndex,
      })
    }

    conn.onconnectionstatechange = () => {
      if (conn.connectionState === 'failed') stopListening('connessione fallita')
      if (conn.connectionState === 'disconnected') setState('connessione persa', 'bad')
    }

    return conn
  }

  const onOffer = async (payload) => {
    pc = openPeer()
    await pc.setRemoteDescription({ type: 'offer', sdp: payload.sdp })
    const answer = await pc.createAnswer()
    await pc.setLocalDescription(answer)
    sendSignal({ kind: 'answer', sdp: answer.sdp })
  }

  const onHubMessage = async (message) => {
    if (message.type === 'welcome') {
      setState('richiesta inviata')
      sendSignal({ kind: 'request', video: false, talkBack: false })
      return
    }

    if (message.type === 'signal-undelivered') {
      stopListening(message.reason === 'offline'
        ? 'il Nursery Node non e collegato'
        : 'destinatario sconosciuto')
      return
    }

    if (message.type !== 'signal' || message.from !== nurseryId) return

    const payload = message.payload || {}

    if (payload.kind === 'offer') return onOffer(payload)

    if (payload.kind === 'ice') {
      if (!pc) return
      try {
        await pc.addIceCandidate({
          candidate: payload.candidate,
          sdpMid: payload.sdpMid,
          sdpMLineIndex: payload.sdpMLineIndex,
        })
      } catch {
        // Un candidato rifiutato non affonda la sessione: ne arrivano altri.
      }
      return
    }

    if (payload.kind === 'busy') stopListening('il Nursery Node ha gia tre ascoltatori')
    if (payload.kind === 'stop') stopListening('sessione chiusa dal Nursery Node')
  }

  function stopListening(why) {
    if (listening) sendSignal({ kind: 'stop' })
    listening = false
    if (pc) { pc.close(); pc = null }
    if (ws) { ws.onclose = null; ws.close(); ws = null }
    $('audio').srcObject = null
    $('listen').textContent = 'Ascolta'
    $('listen').disabled = false
    setState(why || '', why ? 'bad' : '')
  }

  const startListening = async () => {
    $('listenError').hidden = true
    nurseryId = $('nursery').value
    if (!nurseryId) return listenError('Nessun Nursery Node accoppiato.')

    $('listen').disabled = true
    setState('accoppiamento...')

    let token
    try {
      token = await ensureDevice()
    } catch (err) {
      $('listen').disabled = false
      setState('')
      return listenError(err.message)
    }

    listening = true
    $('listen').textContent = 'Interrompi'
    $('listen').disabled = false
    setState('connessione all Hub...')

    const scheme = location.protocol === 'https:' ? 'wss://' : 'ws://'
    ws = new WebSocket(scheme + location.host + '/ws?token=' + encodeURIComponent(token))
    ws.onmessage = (event) => {
      try {
        onHubMessage(JSON.parse(event.data))
      } catch {
        // Un messaggio illeggibile non deve buttare giu' la sessione.
      }
    }
    ws.onclose = () => { if (listening) stopListening('collegamento all Hub caduto') }
    ws.onerror = () => listenError('Hub non raggiungibile.')
  }

  $('listen').addEventListener('click', () => {
    if (listening) return stopListening('')
    startListening()
  })

  // Una scheda chiusa senza salutare terrebbe occupato uno dei tre posti sul
  // Nursery Node, e il telefono che conta si vedrebbe rifiutare la sessione.
  addEventListener('pagehide', () => { if (listening) stopListening('') })

  $('notify').addEventListener('click', async () => {
    if (!canNotify) return
    if (Notification.permission !== 'granted') {
      const outcome = await Notification.requestPermission()
      if (outcome !== 'granted') return refreshNotifyButton()
      notifyOn = true
    } else {
      notifyOn = !notifyOn
    }
    localStorage.setItem(NOTIFY, notifyOn ? '1' : '0')
    refreshNotifyButton()
  })

  refreshNotifyButton()
  loadEvents()
  setInterval(loadEvents, 5000)

  $('generate').addEventListener('click', async () => {
    const token = $('token').value.trim() || localStorage.getItem(STORED)
    if (!token) return showError('Serve l\\'admin token.')

    $('generate').disabled = true
    $('error').hidden = true

    try {
      const res = await fetch('/pairing-codes', {
        method: 'POST',
        headers: { authorization: 'Bearer ' + token },
      })
      const body = await res.json()

      if (!res.ok) {
        showError(res.status === 401 ? 'Admin token non valido.' : 'Errore: ' + (body.error || res.status))
        return
      }

      $('code').textContent = body.code
      $('result').hidden = false
      clearInterval(countdown)
      tick(body.expiresAt)
      countdown = setInterval(() => tick(body.expiresAt), 1000)
    } catch (err) {
      showError('Hub non raggiungibile.')
    } finally {
      $('generate').disabled = false
    }
  })
</script>
</body>
</html>
`
