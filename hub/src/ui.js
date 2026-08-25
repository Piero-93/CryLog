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
</style>
</head>
<body>
<main>
  <h1>Aggiungi un dispositivo</h1>
  <p class="sub">Genera un codice da inserire nell'app CryLog.</p>

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
