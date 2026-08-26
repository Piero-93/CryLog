# Working on CryLog

A self-hosted baby monitor: two Android phones and one container. Audio and video
never leave the private network, and no third party ever holds them.

## Terminology, and it is binding

| Term | What it is |
|---|---|
| **Nursery Node** | the Android app in camera mode, in the child's room |
| **Parent Node** | the Android app in viewer mode |
| **Hub** | a Node.js container: signalling, device registry, notification fan-out, event log |

Never "server" or "client" on their own. The Nursery Node is the server of the
media stream and a client of the Hub at the same time, so either word alone says
the wrong thing. This applies to code, commit messages and documentation.

## Layout

```
android-app/app/src/main/java/it/biagini/crylog/
  core/       domain logic, plain Kotlin, testable without Android
  nursery/    the Nursery Node: capture, detection, foreground service
  parent/     the Parent Node: alerts, notifications, received-stream state
  transport/  StreamTransport and its WebRTC implementation
  hub/        WebSocket and REST client, paired-device storage
  ui/         Compose screens, Material 3 theme
hub/src/      index.js, db.js, ws.js, pairing.js, fcm.js, protocol.js, ui.js
docs/         ARCHITECTURE.md
```

## Building

```sh
# Android — JAVA_HOME must point at a JDK 21+; the one inside Android Studio works
cd android-app && ./gradlew assembleDebug

# Hub
cd hub && node --run dev     # serves /health
cd hub && node --test        # no test framework, node:test only
```

## Conventions

- **Code, identifiers, commit messages and this file are in English.** Comments
  are in Italian, matching the existing code.
- Match the surrounding style rather than importing your own. The project has one
  voice on purpose.
- Comments explain **why**, never what the line already says. A comment that
  restates the code is worse than no comment.
- YAGNI, and native first: the standard library and the platform API before any
  dependency. A dependency has to earn its maintenance cost.
- **Two abstractions exist by design and no more**: `NoiseDetector`, so the RMS
  threshold can be swapped for a real cry classifier, and `StreamTransport`, so
  the media layer can be replaced without the UI noticing. Do not add a third
  without a concrete second implementation in hand.
- Errors are handled explicitly, in the idiom of the language. Never silence one.

## Git

- **Never commit on `main`.** Feature branch, then a pull request.
- PR titles: `Phase N: short description` for phase work, a plain short
  description otherwise.
- Commit messages: a short imperative subject, and a body that explains the
  reasoning when the change is not self-evident.

## Things that are easy to get wrong

- **WebRTC**: Google dropped its Android artifact after M80. The dependency is
  `io.github.webrtc-sdk:android`, which still exposes the `org.webrtc` package.
  Anything suggesting `org.webrtc:google-webrtc` is out of date.
- **AGP 9 ships Kotlin support built in.** Applying `kotlin-android` on top of it
  breaks the build.
- **WebRTC audio defaults to the telephone path**, so it comes out of the earpiece
  and sounds broken. The audio device module sets `USAGE_MEDIA` with
  `CONTENT_TYPE_SPEECH`. Echo cancellation and noise suppression are switched
  **off on purpose**: they remove the very sounds a baby monitor exists to hear.
- **Foreground service types are raised at runtime.** Declaring `camera`
  permanently makes the service refuse to start on a device without the camera
  permission, which is the normal case for audio-only monitoring. The type goes up
  before the camera opens and back down when it closes.
- **Adaptive icons are cropped by 33% on both layers**, background included. Art
  that fills the background layer loses its edges.
- **The Hub never opens the signalling envelope.** SDP and ICE candidates travel as
  an opaque payload it forwards without looking inside.
- **Detection is independent of streaming.** A baby monitor that only warns while
  somebody is watching is not a baby monitor. The Nursery Node's foreground
  service keeps listening with the app closed and the screen off.

## Secrets

Never commit `google-services.json` or any Firebase service account key. The
service account key is the real one: it grants the right to *send* notifications.
It belongs on the host as a mounted volume, with its path in an environment
variable. `.gitignore` covers the usual filenames, but check before staging.

## Status

Phases 0 to 5 are done and verified on real devices: scaffolding, Hub, app
skeleton, noise detection with alerts, push notifications, and WebRTC streaming
with video and talk-back. Phase 6 is continuous listening, phase 7 power and
polish, phase 8 the multi-viewer mesh.
