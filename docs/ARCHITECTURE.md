# Architecture

This document records *why* CryLog is built the way it is. For what it does and how to install it,
see the [README](../README.md).

## Components

```
                    ┌──────────────────────────────┐
                    │   Hub (Docker, homelab)      │
                    │   crylog.<tailnet>.ts.net    │
                    │                              │
   noise event      │  ┌────────────────────────┐  │   WS (app in foreground)
   ───────────────► │  │ notification fan-out   │ ─┼──────────────────────────►
                    │  │ device registry        │  │   FCM (fallback)
                    │  │ WebRTC signaling       │ ─┼──────────────────────────►
                    │  │ offline watchdog       │  │
                    │  │ event log (SQLite)     │  │
                    │  └────────────────────────┘  │
                    └──────────────────────────────┘
        ▲                                                        ▲
        │ persistent WS (heartbeat)                              │ WS
        │                                                        │
┌───────┴────────┐                                      ┌────────┴───────┐
│  Nursery Node  │ ◄══════ WebRTC media, P2P ══════════►│  Parent Node   │
│                │         DTLS-SRTP inside             │                │
│  mic + camera  │         WireGuard/Tailscale          │  audio/video   │
└────────────────┘                                      └────────────────┘
```

The Hub never sees or relays media. It brokers the connection and then gets out of the way.

## Decisions

### Noise detection runs in its own foreground service, separate from streaming

This is the load-bearing decision of the whole design. A baby monitor that only alerts you while
someone happens to be watching a stream is not a baby monitor. Detection therefore has no dependency
on streaming: its foreground service starts when the Nursery Node is armed and stops when it is
disarmed, regardless of whether any Parent Node is connected.

The corollary is that a dropped stream degrades the experience but never the safety property.

### One audio capture, two consumers

Noise detection is always running and WebRTC also wants the microphone. Two concurrent
`AudioRecord` instances are fragile on Android, so there is exactly one capture: the WebRTC SDK's
`JavaAudioDeviceModule` owns it, and the `NoiseDetector` reads the same buffers through
`SamplesReadyCallback`.

This is why `minSdk` is 29: API 29 is where concurrent audio capture became officially supported.

### WebRTC only, behind a transport interface

The original design called for RTSP *and* WebRTC. RTSP was dropped:

- A multi-client RTSP server on Android means writing RTP packetisation, session handling and RTCP.
  The maintained Android libraries in this space (RootEncoder) are *publishers*, not servers.
- Its only real advantage — not needing the Hub — is moot, because the Hub is needed for
  notifications anyway.
- WebRTC gives sub-second latency, congestion control, and talk-back essentially for free.

`StreamTransport` still exists as an interface, so a second transport can be added without touching
the UI or the domain logic. It has exactly one implementation today.

### Two notification channels, deduplicated by event id

A WebSocket to the Hub is instant but dies when Android puts the app to sleep. FCM survives Doze but
is not instant and depends on Google. So: the WebSocket carries events whenever it is open, FCM
covers everything else, and the Parent Node discards duplicates by event id.

The FCM payload carries only an event id. No audio, no measurements, no content — the notification
is a doorbell, not a message.

### The Parent Node has no foreground service by default

Continuous listening is opt-in. Without it, the Parent Node holds no wakelock and no service: the
WebSocket lives only while the app is in the foreground, and FCM covers the rest. Turning on
continuous listening starts a `mediaPlayback` foreground service that keeps both the audio and the
WebSocket alive with the screen off.

### Silence must never be ambiguous

The dangerous failure mode of a baby monitor is not a crash — it is a connection that is technically
alive but carrying nothing, which is indistinguishable from a quiet room.

Therefore the watchdog does not trust ICE state; it checks that audio packets are actually arriving.
On failure the Parent Node attempts an ICE restart with backoff, and if it cannot recover within
roughly thirty seconds it raises an audible alarm on a notification channel exempt from Do Not
Disturb. Separately, the Hub watches Nursery Node heartbeats and notifies every Parent Node when one
stops reporting.

### The Hub has no runtime dependencies

Node 24's standard library covers everything the Hub needs: `node:http`, `node:crypto` for pairing
tokens and the FCM JWT, `node:sqlite` for storage. `node:sqlite` is a release candidate rather than
fully stable, so all database access is confined to `hub/src/db.js`; if the API shifts, one file
changes.

### Pairing

The Hub issues a single-use token, displayed as a QR code. A device exchanges it for a permanent
token, of which the Hub stores only a hash. Every WebSocket connection presents its token.

Tailscale already restricts who can reach the Hub, but network position alone is not authentication:
anything on the tailnet could otherwise register itself as a Parent Node and receive alerts.

## Testability

Domain logic is kept free of Android and network types so it can be tested without an emulator or a
live connection:

- `core/` in the Android app is plain Kotlin — threshold logic, pairing state, Hub protocol types.
- Hardware sits behind `NoiseDetector` and `StreamTransport`.
- On the Hub, fan-out and pairing are pure functions over the registry; transport is injected.

The regression that matters most here is a silent one in detection: nobody notices until the night
it is needed.
