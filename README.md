# CryLog

Self-hosted baby monitor for Android. A phone in the nursery detects crying and alerts the parents'
phones in seconds; audio and video stream peer-to-peer over WebRTC across your own Tailscale
network. No cloud, no subscription.

Commercial baby monitor apps route your child's audio and video through servers you do not control.
CryLog does not: the media never leaves your tailnet, and the only component you run is a small
Node.js hub on your own hardware.

> **Status: early development.** Phase 0 (scaffolding) is complete. The app does not monitor
> anything yet. See [Roadmap](#roadmap).

## Terminology

These three names are used consistently throughout the code and documentation. "Server" and "client"
are deliberately avoided: the Nursery Node is a server for media but a client to the Hub.

| Term | Meaning |
|---|---|
| **Nursery Node** | The Android app in camera mode, in the child's room |
| **Parent Node** | The Android app in viewer mode |
| **Hub** | The Node.js service on your homelab: signaling, device registry, notification fan-out, event log |

## Features

- **Noise alerts that work independently of streaming.** The Nursery Node monitors audio in a
  dedicated foreground service. Alerts fire whether or not anyone is watching a stream.
- **On-demand audio/video** over WebRTC, peer-to-peer, with adaptive bitrate.
- **Continuous listening**, opt-in: keep the audio open all night with the screen off.
- **Up to three Parent Nodes at once**, each choosing audio or audio and video for itself. The
  nursery's microphone and camera are opened once and shared between them.
- **Never an ambiguous silence.** If a stream dies, the Parent Node retries and then raises an
  audible alarm. If the Nursery Node goes offline, the Hub tells every Parent Node.
- **Video is always optional**, at two independent levels: globally on the Nursery Node
  ("audio only"), and per-connection on each Parent Node.
- **Talk-back** from a Parent Node to the nursery.
- **Event history**: timestamp, duration and intensity of every noise event. No audio is ever stored.

## Requirements

- **Android 10 (API 29) or newer** on both devices. API 29 is where Android began allowing two
  apps — or two parts of one app — to record at the same time, which is what lets noise detection
  keep running while a stream is open.
- **Tailscale** on both phones and on the host running the Hub.
- A host for the Hub with **Docker** and **Node.js 24+** (a TrueNAS SCALE box, a Raspberry Pi, any
  always-on machine on your tailnet).
- **Optional:** a Firebase project, if you want push notifications to reach a Parent Node whose app
  is closed or whose phone is in Doze. See [Privacy](#privacy).

## Installation

> Written as the phases land. Currently only the Hub health check is functional.

### Hub

```sh
cd hub
docker compose up -d --build
curl https://crylog.<your-tailnet>.ts.net/health
```

The provided `docker-compose.yml` follows the sidecar pattern: a `tailscale/tailscale` container
owns the network namespace and `serve.json` exposes the Hub over HTTPS on the tailnet only. Nothing
is published to the public internet.

### Pairing a device

Pairing codes are single-use and expire after ten minutes. Creating one requires the admin
token, which is either `CRYLOG_ADMIN_TOKEN` or, if unset, generated on first start and printed
to the logs (`docker logs crylog-hub`).

```sh
# 1. create a code
curl -X POST https://crylog.<your-tailnet>.ts.net/pairing-codes \
  -H "Authorization: Bearer $CRYLOG_ADMIN_TOKEN"
# -> {"code":"K7M2-P9XQ","expiresAt":...}

# 2. the device redeems it, once
curl -X POST https://crylog.<your-tailnet>.ts.net/pair \
  -H "content-type: application/json" \
  -d '{"code":"K7M2-P9XQ","role":"nursery","name":"Nursery"}'
# -> {"deviceId":"...","token":"..."}
```

The device token is what authenticates every later request, over REST (`Authorization: Bearer`)
and over the WebSocket at `/ws`. The Hub stores only its hash.

Codes use a Crockford base32 alphabet — no I, L, O or U — so they can be read aloud or typed
without ambiguity. Until the app ships its pairing screen, the two calls above are the procedure.

### Push notifications (optional)

Without this, alerts only reach a Parent Node whose app is open. With it, they arrive with
the app closed and the phone asleep — which is the point of a baby monitor at night.

In the [Firebase console](https://console.firebase.google.com):

1. Create a project, Google Analytics off (it adds tracking and buys nothing here).
2. Register an Android app with package name `it.biagini.crylog`. SHA-1 is not needed for FCM.
3. Download `google-services.json` into `android-app/app/`.
4. Settings → **Service accounts** → **Generate new private key**. The language shown above the
   button only changes the sample snippet; the JSON is the same either way.

Put the service account file somewhere your Hub can read, mount it read-only and point
`CRYLOG_FCM_CREDENTIALS` at it — see the commented lines in `hub/docker-compose.yml`.

**That file can send notifications as you.** Keep it out of the repository; the `.gitignore`
covers the usual names but not every one Firebase might produce.

Both files are optional. Without `google-services.json` the Gradle plugin is skipped and the
app still builds; without the service account the Hub starts and says so in its logs.

### Android app

Install `crylog-<version>.apk` from the
[latest release](https://github.com/Piero-93/CryLog/releases/latest) on both phones. It is built for
arm64 only, which every phone new enough to run Android 10 is.

To build it yourself: open `android-app/` in Android Studio and run. Requires JDK 21 and AGP 9.3.
Without `google-services.json` the build still works — it just has no push notifications.

### Before it will work

Both phones need Tailscale, signed into the same tailnet as the Hub.

On Xiaomi, Redmi and POCO phones (MIUI/HyperOS) the Nursery Node **also needs autostart granted**,
under Settings → Apps → CryLog. Without it the system refuses to launch the app for its own
broadcasts, and monitoring will not tell you it has stopped — the only warning left is the offline
alert the Hub sends to the Parent Node. Turning off battery restrictions for the app is worth doing
at the same time.

## Privacy

| Channel | Encryption |
|---|---|
| WebRTC media, Nursery ↔ Parent | DTLS-SRTP **and** WireGuard (Tailscale) |
| WebSocket / REST to the Hub | TLS via `tailscale serve` **and** WireGuard |
| FCM push (optional) | TLS to Google. Payload carries **only an event id** — no audio, no content |

Firebase Cloud Messaging is the single point where CryLog touches a third party, and it is optional.
Everything else runs on hardware you own.

## Roadmap

| Phase | Scope | Status |
|---|---|---|
| 0 | Repo scaffolding, Hub health check | ✅ |
| 1 | Hub: SQLite, pairing, authenticated WebSocket, fan-out, offline watchdog | ✅ |
| 2 | App skeleton: role selection, pairing, Hub connection | ✅ |
| 3 | Noise detection, foreground service, vibration + flash alert | ✅ first usable version |
| 4 | FCM push | ✅ |
| 5 | WebRTC streaming, per-viewer audio/video, talk-back | ✅ |
| 6 | Continuous listening with reconnect watchdog and alarm | ✅ |
| 7 | Streaming away from the local network, over the tailnet | ✅ |
| 8 | Role changes without re-pairing, CI, interface pass | ✅ |
| 9 | Several Parent Nodes listening at once | ✅ |

## Known limitations

- **Three Parent Nodes at once, and no more.** Without an SFU every listener is another upstream
  from the phone in the room; past that the uplink saturates and the quality drops for everybody. A
  fourth request is refused rather than degrading the three already listening.
- **Monitoring cannot restart itself.** Since Android 14 a foreground service that uses the
  microphone cannot be started from the background — the restriction is on `RECORD_AUDIO`, which is
  granted only while an app is in use. So after a reboot, an app update, or the system reclaiming
  memory, the Nursery Node says it has stopped and waits to be reopened. It cannot quietly turn the
  microphone back on, and that rule is the right one.
- **Do Not Disturb bypass has to be granted before first use.** A notification channel's ability to
  sound through Do Not Disturb is fixed when the channel is created and cannot be raised afterwards.
  Granting the permission later has no effect until the app's data is cleared.
- **Threshold-only detection.** CryLog cannot tell crying from a passing truck. The `NoiseDetector`
  interface exists so a classifier can replace the threshold without touching anything else.
- **Not publishable on F-Droid.** FCM's proprietary library is not permitted in F-Droid builds. A
  `foss` flavour using UnifiedPush/ntfy would fix this, and would need no licence exception. Possible
  future extension.
- **Continuous listening assumes the Parent Node is charging.** WebRTC audio with a wakelock will
  not survive a night on battery.
- **CryLog is not a medical device** and is not a substitute for supervision.

## License

GPLv3 or later — see [LICENSE](LICENSE).

With one addition. When push notifications are enabled, the Android app bundles `firebase-messaging`,
a proprietary library. So that the combined APK can be distributed without ambiguity, CryLog grants an
**additional permission under GPLv3 section 7**:

> If you modify this Program, or any covered work, by linking or combining it with Google Play
> Services and the Firebase SDKs (or modified versions of those libraries), the licensors of this
> Program grant you additional permission to convey the resulting work.

The permission covers the Android app only. The Hub links no Google library and is plain GPLv3. See
[LICENSE-EXCEPTION.txt](LICENSE-EXCEPTION.txt) for the full scope and what it means for
contributions — in short, contributions are accepted under GPLv3 **with** this permission.
