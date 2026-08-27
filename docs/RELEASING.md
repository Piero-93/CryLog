# Releasing CryLog

A release is cut by pushing a tag. That builds two things and publishes both:

- **The hub image**, to `ghcr.io/<owner>/crylog-hub`, tagged with the version
  and with `latest`.
- **The Android APK**, signed, attached to the GitHub Release.

```sh
git tag v0.2.0
git push origin v0.2.0
```

The tag is the single source of the version number, so the APK cannot claim a
version different from the release that contains it.

## Secrets the workflow needs

Set these under **Settings → Secrets and variables → Actions**. Nothing else is
required: the token for the container registry is provided by the workflow
itself.

| Secret | What it is |
|---|---|
| `ANDROID_KEYSTORE` | the signing keystore, base64-encoded |
| `ANDROID_KEYSTORE_PASSWORD` | its password |
| `ANDROID_KEY_ALIAS` | the key alias inside it |
| `ANDROID_KEY_PASSWORD` | that key's password |
| `GOOGLE_SERVICES_JSON` | `google-services.json`, base64-encoded |

Without the keystore the release job fails on purpose: an unsigned APK cannot be
installed, so producing one would only waste the run. Without
`GOOGLE_SERVICES_JSON` the build succeeds and warns — the APK works, it just has
no push notifications.

## Creating the signing key

**Do this once and keep the result safe.** The signing key is the app's identity
for as long as it exists: if it changes, existing installations stop being able
to update and have to be uninstalled first. There is no recovery.

```sh
keytool -genkeypair -v \
  -keystore crylog.jks \
  -alias crylog \
  -keyalg RSA -keysize 4096 \
  -validity 10000 \
  -storetype PKCS12
```

Then, to get the value for the secret:

```sh
base64 -w0 crylog.jks          # Linux
base64 -i crylog.jks           # macOS
```

Keep `crylog.jks` somewhere it survives this machine — a password manager, or
wherever the Firebase service account key already lives. It must never be
committed.

## Pulling the hub image

While the repository is private the package is private too, so the machine
running the hub needs a token with `read:packages`:

```sh
echo "$GITHUB_TOKEN" | docker login ghcr.io -u <username> --password-stdin
docker pull ghcr.io/<owner>/crylog-hub:latest
```

The compose stack can then reference the image instead of carrying a copy of the
source, which is what it does today and what makes the two repositories drift.

## What is deliberately not done

**R8 is off.** Turning it on would strip the unused Material icons, which are
most of the APK's size — but WebRTC, OkHttp and Firebase all reach for classes
by name, and switching on shrinking without first verifying their rules is the
classic way to discover in production that something was removed. It is worth
doing, with a test install afterwards, not as part of a release.

**The image is built for amd64 only.** That is what the NAS runs. Adding arm64
means `docker buildx` and roughly double the build time, and nothing needs it
yet.
