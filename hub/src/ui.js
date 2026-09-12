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
<title>CryLog</title>
<link rel="icon" href="data:image/png;base64,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">
<style>
  /* Una scala sola per spaziature, raggi e pesi: e' cio' che distingue una
     pagina disegnata da una assemblata. Tutto quello che segue usa questi. */
  :root {
    color-scheme: light dark;
    --fg: #101418; --bg: #f4f6f8; --card: #fff; --muted: #5b6472;
    --accent: #2f6fed; --border: #e2e6ea; --danger: #c0362c;
    --good-bg: #e8f5ec; --good-fg: #0f6b34; --good-line: #bfe3cc;
    --s1: 8px; --s2: 12px; --s3: 16px; --s4: 24px; --s5: 32px;
    --r-card: 14px; --r-ctl: 10px;
    --shadow: 0 1px 2px rgba(16, 20, 24, .05), 0 1px 8px rgba(16, 20, 24, .04);
  }
  @media (prefers-color-scheme: dark) {
    :root {
      --fg: #e6e9ed; --bg: #101316; --card: #191d21; --muted: #98a2b0;
      --accent: #7aa2f7; --border: #272c33; --danger: #e5796d;
      --good-bg: #12291c; --good-fg: #6fd291; --good-line: #1f4630;
      --shadow: none;
    }
  }
  * { box-sizing: border-box; }
  body { margin: 0; padding: var(--s4) var(--s3) var(--s5); background: var(--bg); color: var(--fg);
         font: 15px/1.55 system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
         -webkit-font-smoothing: antialiased; }
  main { max-width: 64rem; margin: 0 auto; }

  /* Intestazione: marchio a sinistra, stato dell'Hub a destra. Nessun
     sottotitolo che spiega la pagina: la pagina si spiega da sola. */
  .masthead { display: flex; align-items: center; gap: var(--s2); margin-bottom: var(--s4); }
  .masthead img { width: 34px; height: 34px; border-radius: 9px; flex: none; }
  .masthead h1 { font-size: 1.25rem; letter-spacing: -.01em; margin: 0; flex: 1; }
  .pill { font-size: .78rem; font-weight: 600; padding: 5px 11px; border-radius: 999px;
          border: 1px solid var(--border); color: var(--muted); white-space: nowrap; }
  .pill.on { background: var(--good-bg); color: var(--good-fg); border-color: var(--good-line); }
  .pill.off { color: var(--danger); border-color: color-mix(in srgb, var(--danger) 35%, transparent); }

  .cols { display: grid; gap: 0 var(--s5); grid-template-columns: 1fr; align-items: start; }
  @media (min-width: 60rem) { .cols { grid-template-columns: 1.55fr 1fr; } }
  .cols > section { min-width: 0; }

  h2 { font-size: .8rem; font-weight: 700; letter-spacing: .07em; text-transform: uppercase;
       color: var(--muted); margin: var(--s5) 0 var(--s2); }
  section > h2:first-child, .rowhead:first-child h2 { margin-top: 0; }
  .rowhead { display: flex; align-items: center; gap: var(--s2); margin: var(--s5) 0 var(--s2); }
  .rowhead h2 { margin: 0; flex: 1; }

  .card { background: var(--card); border: 1px solid var(--border); border-radius: var(--r-card);
          padding: var(--s3); margin-bottom: var(--s2); box-shadow: var(--shadow); }

  /* L'eroe, come nell'app: la scheda cambia tono quando l'audio scorre
     davvero, cosi' lo stato si legge da lontano senza leggere parole. */
  .hero { transition: background-color .25s, border-color .25s; }
  .hero.live { background: var(--good-bg); border-color: var(--good-line); }

  label { display: block; font-size: .82rem; font-weight: 500; color: var(--muted); margin-bottom: 6px; }
  input, select { width: 100%; padding: 11px var(--s2); font-size: .95rem; border-radius: var(--r-ctl);
                  border: 1px solid var(--border); background: var(--bg); color: var(--fg); }
  input:focus-visible, select:focus-visible, button:focus-visible
    { outline: 2px solid var(--accent); outline-offset: 2px; }
  button { width: 100%; padding: 12px; font-size: .95rem; font-weight: 600; margin-top: var(--s2);
           border: 0; border-radius: var(--r-ctl); background: var(--accent); color: #fff;
           cursor: pointer; transition: filter .15s; }
  button:hover:not(:disabled) { filter: brightness(1.07); }
  button:disabled { opacity: .45; cursor: default; }
  .ghost { background: transparent; color: var(--fg); border: 1px solid var(--border); }

  .chart { width: 100%; height: 116px; display: block; border-radius: var(--r-ctl);
           background: color-mix(in srgb, var(--fg) 5%, transparent); }
  .axis { display: flex; justify-content: space-between; color: var(--muted);
          font-size: .75rem; margin: 6px 2px var(--s3); }
  .state { font-size: .82rem; color: var(--muted); font-weight: 500; }
  .state.live { color: var(--good-fg); }
  .state.bad { color: var(--danger); }
  .named { margin-bottom: var(--s2); }

  .code { font: 700 2rem/1.2 ui-monospace, "SF Mono", Menlo, Consolas, monospace;
          letter-spacing: .14em; text-align: center; margin: var(--s2) 0 4px; }
  .expiry { text-align: center; color: var(--muted); font-size: .85rem; }
  .expiry.soon { color: var(--danger); }
  .error { color: var(--danger); font-size: .85rem; margin-top: var(--s2); }
  .steps { color: var(--muted); font-size: .85rem; }
  .steps ol { padding-left: 1.1rem; margin: var(--s1) 0 0; }

  ul { list-style: none; margin: 0; padding: 0; }
  li { display: flex; align-items: center; gap: var(--s2); padding: 10px 0;
       border-bottom: 1px solid var(--border); }
  li:last-child { border-bottom: 0; }
  li.empty { color: var(--muted); justify-content: center; padding: var(--s3) 0; font-size: .9rem; }
  .dot { width: 8px; height: 8px; border-radius: 50%; background: var(--border); flex: none; }
  .dot.on { background: #2ea043; }
  .who { flex: 1; min-width: 0; }
  .who strong { display: block; font-weight: 600; overflow: hidden;
                text-overflow: ellipsis; white-space: nowrap; }
  .who span { color: var(--muted); font-size: .8rem; }
  .remove { width: auto; margin: 0; padding: 6px 10px; font-size: .8rem; font-weight: 500;
            background: transparent; color: var(--muted); border: 1px solid var(--border); }
  /* Lo stato delle notifiche si deve leggere senza leggere: campanella piena e
     pastiglia verde quando sono attive, campanella sbarrata e grigio quando no. */
  .toggle { display: inline-flex; align-items: center; gap: 7px; }
  .toggle svg { width: 15px; height: 15px; flex: none; }
  .toggle.on { background: var(--good-bg); color: var(--good-fg); border-color: var(--good-line); }
  .toggle:disabled { opacity: .5; }
  .events .what { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .events .peak, .events .when { color: var(--muted); font-size: .8rem;
                                 font-variant-numeric: tabular-nums; flex: none; }
  .events li.fresh .what { font-weight: 600; }
  audio { display: none; }
</style>
</head>
<body>
<main>
  <div class="masthead">
    <img src="data:image/png;base64,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" alt="">
    <h1>CryLog</h1>
    <span class="pill" id="hubPill">&nbsp;</span>
  </div>

  <div class="cols">
  <section>

    <div class="rowhead">
      <h2>Ascolto</h2>
      <span class="state" id="listenState"></span>
    </div>
    <div class="card hero" id="hero">
      <canvas class="chart" id="level"></canvas>
      <div class="axis"><span>30 secondi fa</span><span>adesso</span></div>

      <div class="named" id="pairbox" hidden>
        <label for="browsername">Nome di questo browser</label>
        <input id="browsername" maxlength="64" autocomplete="off" placeholder="PC studio">
      </div>

      <select id="nursery"></select>
      <button id="listen">Ascolta</button>
      <div class="error" id="listenError" hidden></div>
      <audio id="audio" autoplay playsinline></audio>
    </div>

    <div class="rowhead">
      <h2>Attivit&agrave;</h2>
      <button class="remove toggle" id="notify">
        <span id="notifyIcon"></span><span id="notifyText">Notifiche</span>
      </button>
    </div>
    <div class="card">
      <ul class="events" id="events"><li class="empty">Caricamento...</li></ul>
    </div>

  </section>
  <section>

    <h2>Dispositivi</h2>
    <div class="card">
      <ul class="devices" id="devices"><li class="empty">Caricamento...</li></ul>
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

  </section>
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
    if (!confirm('Rimuovere "' + device.name + '"? Dovrà rifare il pairing per tornare.')) return
    const token = localStorage.getItem(STORED)
    const res = await fetch('/devices/' + device.id, {
      method: 'DELETE',
      headers: { authorization: 'Bearer ' + token },
    }).catch(() => null)
    // Senza questo controllo la riga spariva e poi riappariva al giro dopo,
    // senza che nessuno dicesse che la rimozione non era riuscita.
    if (!res || !res.ok) {
      alert('Non è stato possibile rimuovere "' + device.name + '".')
    }
    loadDevices()
  }

  // La pillola nell'intestazione: un colpo d'occhio dice se l'Hub risponde e
  // quanti dispositivi ci sono sopra, senza leggere la lista.
  const setPill = (text, kind) => {
    const pill = $('hubPill')
    pill.textContent = text
    pill.className = 'pill' + (kind ? ' ' + kind : '')
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
      const online = devices.filter((d) => d.online).length
      setPill(online === 1 ? '1 collegato' : online + ' collegati', online > 0 ? 'on' : '')

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
      setPill('Hub non raggiungibile', 'off')
      list.innerHTML = '<li class="empty">Hub non raggiungibile</li>'
      // Senza la lista non si sa se il Nursery Node sia collegato: il pulsante
      // resterebbe com'era, promettendo qualcosa che non si puo' sapere.
      if (!listening) {
        $('listen').disabled = true
        setState('Hub non raggiungibile')
      }
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

  // Campanella piena quando suonano, sbarrata quando no: la differenza si vede
  // prima di leggere l'etichetta, che resta per chi usa uno screen reader.
  const BELL = '<svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">'
    + '<path d="M12 22a2 2 0 0 0 2-2h-4a2 2 0 0 0 2 2Zm6-6V11a6 6 0 0 0-5-5.9V4a1 1 0 1 0-2 0v1.1A6 6 0 0 0 6 11v5l-2 2v1h16v-1l-2-2Z"/></svg>'
  const BELL_OFF = '<svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">'
    + '<path d="M12 22a2 2 0 0 0 2-2h-4a2 2 0 0 0 2 2Zm6-6V11a6 6 0 0 0-5-5.9V4a1 1 0 1 0-2 0v1.1A6 6 0 0 0 6 11v5l-2 2v1h16v-1l-2-2Z" opacity=".35"/>'
    + '<path d="M3.3 2 2 3.3 20.7 22l1.3-1.3Z"/></svg>'

  const refreshNotifyButton = () => {
    const button = $('notify')
    const icon = $('notifyIcon')
    const text = $('notifyText')

    if (!canNotify) {
      // Fuori da un contesto sicuro l'API non esiste: dirlo, invece di offrire
      // un pulsante che non farebbe niente.
      icon.innerHTML = BELL_OFF
      text.textContent = 'Notifiche non disponibili'
      button.disabled = true
      button.classList.remove('on')
      return
    }
    if (Notification.permission === 'denied') {
      icon.innerHTML = BELL_OFF
      text.textContent = 'Notifiche bloccate'
      button.disabled = true
      button.classList.remove('on')
      return
    }
    icon.innerHTML = notifyOn ? BELL : BELL_OFF
    text.textContent = notifyOn ? 'Notifiche attive' : 'Notifiche spente'
    button.classList.toggle('on', notifyOn)
    button.setAttribute('aria-pressed', String(notifyOn))
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
  const NURSERY = 'crylog-nursery-id'
  let ws = null
  let pc = null
  let nurseryId = null
  let listening = false
  // Se l'Hub chiude prima ancora di salutare, il token di questo browser non
  // vale piu': succede se il dispositivo viene rimosso dalla lista qui accanto.
  let greeted = false

  const setState = (text, kind) => {
    const el = $('listenState')
    el.textContent = text
    el.className = 'state' + (kind ? ' ' + kind : '')
    $('hero').classList.toggle('live', kind === 'live')
  }

  const listenError = (text) => {
    $('listenError').textContent = text
    $('listenError').hidden = false
  }

  // Messaggi che descrivono l'attesa, non un esito: solo questi si possono
  // sovrascrivere quando la situazione si sblocca.
  const IDLE_STATES = [
    'nessun Nursery Node accoppiato',
    'Nursery Node non collegato',
    'Hub non raggiungibile',
  ]

  const fillNurseries = (devices) => {
    const select = $('nursery')
    const nurseries = devices.filter((d) => d.role === 'nursery')
    // La scelta sopravvive al ricaricamento: senza, riaprendo la pagina il
    // menu tornava in silenzio al primo della lista, che con due Nursery Node
    // vuol dire ascoltare la stanza sbagliata senza aver scelto niente.
    const chosen = select.value || localStorage.getItem(NURSERY) || ''
    select.replaceChildren(...nurseries.map((d) => {
      const option = document.createElement('option')
      option.value = d.id
      option.textContent = d.name + (d.online ? '' : ' (non collegato)')
      return option
    }))
    if (nurseries.some((d) => d.id === chosen)) select.value = chosen
    if (select.value) localStorage.setItem(NURSERY, select.value)
    // Con un Nursery Node solo, scegliere non ha senso: il menu sparisce.
    select.hidden = nurseries.length < 2

    if (listening) return

    // Un pulsante che si puo' premere e poi risponde "non e collegato" fa fare
    // un giro a vuoto per dire una cosa che si sapeva gia'. Meglio spento, con
    // scritto perche'. Conta il Nursery scelto, non che ce ne sia uno acceso
    // qualsiasi: si ascolta quello, non un altro.
    const target = nurseries.find((d) => d.id === select.value)
    const ready = Boolean(target && target.online)
    $('listen').disabled = !ready

    if (nurseries.length === 0) return setState('nessun Nursery Node accoppiato')
    if (!ready) return setState('Nursery Node non collegato')
    // Pronto: si cancella solo un messaggio messo da qui, mai l'esito
    // dell'ultima sessione, che altrimenti sparirebbe entro cinque secondi.
    if (IDLE_STATES.includes($('listenState').textContent)) setState('')
  }

  // Due profili browser sono due dispositivi nel registro dell'Hub: senza un
  // nome scelto da chi accoppia, la lista diventa illeggibile in fretta.
  // Il campo si vede solo finche' questo browser non e' accoppiato.
  const browserName = () => {
    const typed = $('browsername').value.trim()
    if (typed) return typed.slice(0, 64)
    return ('Browser ' + Math.random().toString(16).slice(2, 6)).slice(0, 64)
  }

  const showPairBox = () => { $('pairbox').hidden = Boolean(localStorage.getItem(DEVICE)) }

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
    showPairBox()
    return token
  }

  // Perche' il WebSocket si e' chiuso. "Caduto" non aiuta nessuno: il codice
  // di chiusura distingue un token rifiutato da una rete che se n'e' andata.
  const closeReason = (event) => {
    if (event.code === 1000) return 'sessione chiusa'
    if (event.code === 1001) return 'Hub in riavvio'
    if (event.code === 1006) return 'collegamento interrotto senza risposta'
    if (event.code === 1011) return "errore interno dell'Hub"
    const detail = event.reason ? ': ' + event.reason : ''
    return 'collegamento chiuso (codice ' + event.code + ')' + detail
  }

  const sendSignal = (payload) => {
    if (!ws || ws.readyState !== WebSocket.OPEN || !nurseryId) return
    ws.send(JSON.stringify({ type: 'signal', to: nurseryId, payload }))
  }

  // --- Il grafico dei livelli ----------------------------------------------
  //
  // Stessa domanda a cui risponde il grafico dell'app: una cameretta silenziosa
  // e uno stream che non porta niente suonano identici, un grafico piatto e uno
  // che si muove no. Stessa geometria: 150 fette su trenta secondi, quindi una
  // lettura ogni 200 ms, e le barre al pavimento non si disegnano affatto.
  const SILENCE_DB = -100
  const SLOTS = 150
  const history = new Float32Array(SLOTS).fill(SILENCE_DB)
  let audioCtx = null
  let meter = null

  const cssVar = (name) =>
    getComputedStyle(document.documentElement).getPropertyValue(name).trim()

  const drawChart = () => {
    const canvas = $('level')
    const ratio = window.devicePixelRatio || 1
    const width = canvas.clientWidth
    const height = canvas.clientHeight
    if (!width || !height) return
    if (canvas.width !== width * ratio || canvas.height !== height * ratio) {
      canvas.width = width * ratio
      canvas.height = height * ratio
    }

    const ctx = canvas.getContext('2d')
    ctx.setTransform(ratio, 0, 0, ratio, 0, 0)
    ctx.clearRect(0, 0, width, height)

    const slot = width / SLOTS
    const bar = Math.max(1, slot * 0.7)
    ctx.fillStyle = cssVar('--accent') || '#2f6fed'

    for (let i = 0; i < SLOTS; i++) {
      const level = history[i]
      const top = height * Math.min(1, Math.max(0, 1 - (level - SILENCE_DB) / -SILENCE_DB))
      if (top >= height) continue
      ctx.fillRect(i * slot, top, bar, height - top)
    }
  }

  const stopMeter = () => {
    if (meter) { clearInterval(meter); meter = null }
    if (audioCtx) { audioCtx.close(); audioCtx = null }
    history.fill(SILENCE_DB)
    drawChart()
  }

  const startMeter = (stream) => {
    stopMeter()
    const Ctx = window.AudioContext || window.webkitAudioContext
    if (!Ctx) return
    audioCtx = new Ctx()
    const analyser = audioCtx.createAnalyser()
    analyser.fftSize = 2048
    audioCtx.createMediaStreamSource(stream).connect(analyser)
    const samples = new Float32Array(analyser.fftSize)

    meter = setInterval(() => {
      analyser.getFloatTimeDomainData(samples)
      let sum = 0
      for (let i = 0; i < samples.length; i++) sum += samples[i] * samples[i]
      const rms = Math.sqrt(sum / samples.length)
      const level = rms > 0 ? Math.max(SILENCE_DB, 20 * Math.log10(rms)) : SILENCE_DB
      history.copyWithin(0, 1)
      history[SLOTS - 1] = level
      drawChart()
    }, 200)
  }

  addEventListener('resize', drawChart)

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
      // Il grafico legge lo stesso stream che sta suonando: in Chrome un
      // MediaStream non scorre finche' qualcuno non lo riproduce davvero.
      startMeter(event.streams[0])
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
      greeted = true
      setState('richiesta inviata')
      sendSignal({ kind: 'request', video: false, talkBack: false })
      return
    }

    if (message.type === 'signal-undelivered') {
      stopListening(message.reason === 'offline'
        ? 'il Nursery Node non è collegato'
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

    if (payload.kind === 'busy') stopListening('il Nursery Node ha già il massimo di ascoltatori')
    if (payload.kind === 'stop') stopListening('sessione chiusa dal Nursery Node')
  }

  function stopListening(why) {
    if (listening) sendSignal({ kind: 'stop' })
    listening = false
    stopMeter()
    if (pc) { pc.close(); pc = null }
    if (ws) { ws.onclose = null; ws.close(); ws = null }
    $('audio').srcObject = null
    $('nursery').disabled = false
    $('listen').textContent = 'Ascolta'
    $('listen').classList.remove('ghost')
    $('listen').disabled = false
    setState(why || '', why ? 'bad' : '')
  }

  const startListening = async () => {
    $('listenError').hidden = true
    nurseryId = $('nursery').value
    if (!nurseryId) return listenError('Nessun Nursery Node accoppiato.')

    $('listen').disabled = true
    setState('accoppiamento…')

    let token
    try {
      token = await ensureDevice()
    } catch (err) {
      $('listen').disabled = false
      setState('')
      return listenError(err.message)
    }

    listening = true
    greeted = false
    // La sessione e' legata al Nursery scelto quando e' partita: lasciare il
    // menu attivo lo farebbe sembrare cambiabile a caldo, e non lo e'.
    $('nursery').disabled = true
    $('listen').textContent = 'Interrompi'
    $('listen').classList.add('ghost')
    $('listen').disabled = false
    setState("connessione all'Hub…")

    const scheme = location.protocol === 'https:' ? 'wss://' : 'ws://'
    ws = new WebSocket(scheme + location.host + '/ws?token=' + encodeURIComponent(token))
    ws.onmessage = (event) => {
      try {
        onHubMessage(JSON.parse(event.data))
      } catch {
        // Un messaggio illeggibile non deve buttare giu' la sessione.
      }
    }
    ws.onclose = (event) => {
      if (!listening) return
      if (!greeted) {
        // Chiuso prima del benvenuto: il token non e' stato accettato. Si
        // butta via, cosi' il prossimo tentativo riaccoppia invece di
        // ripetere per sempre lo stesso errore.
        localStorage.removeItem(DEVICE)
        showPairBox()
        stopListening('questo browser non è più accoppiato: premi di nuovo Ascolta')
        return
      }
      stopListening(closeReason(event))
    }
    ws.onerror = () => listenError('Hub non raggiungibile.')
  }

  // Scegliendo un altro Nursery il pulsante deve rivalutarsi subito, senza
  // aspettare il giro di lettura dei dispositivi. E la scelta si ricorda.
  $('nursery').addEventListener('change', () => {
    localStorage.setItem(NURSERY, $('nursery').value)
    loadDevices()
  })

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
  showPairBox()
  drawChart()
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
