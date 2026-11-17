**English** · [Français](README.fr.md)

# Relay for Android

Native Android app (Kotlin, Jetpack Compose) for [Relay](https://github.com/overnite22/relay-server-prod), the
self-hosted remote connection manager (SSH, RDP, VNC, Proxmox consoles, credential vault, Proxmox/NetBox sync).
Same feature set as the iPhone app: see [docs/PARITE.md](docs/PARITE.md) (in French).

## Features

- **QR code pairing** (Relay web → Administration → Devices), `relay://pair` link or manual entry.
  The device token is encrypted with the Android Keystore; HTTPS only.
- **Connections**: tree, search, favourites, recents, availability indicators.
- **SSH, RDP, VNC sessions and Proxmox consoles** inside the app (Guacamole protocol, native rendering):
  touchpad or direct touch, pinch to zoom, keyboard with an Esc/Ctrl/Alt/arrows/F1–F12 bar,
  hardware keyboard, clipboard, several open sessions, kept alive in the background (notification).
- **Files**: SFTP over SSH, "Relay" drive over RDP (upload from the phone, download, share).
- **Availability**, **vault** (fingerprint/face/device PIN, TOTP), **SSH commands** on several servers,
  **recordings** (player), **workspaces**, **quick connect**, **activity log**.
- **Editing** of entries, folders (inherited credentials), vault and attachments;
  **administration**: sync sources, duplicates, RDM import, encrypted backup.

Device management stays in Relay web (a lost phone must not be able to pair other devices).

## Installation

Download `Relay-X.Y.Z-N.apk` from the [Releases](../../releases) page and install it (allow installation from
your browser or file manager). Android 8.0 or later. Updates install over the previous version.
Check the file against `SHA256SUMS` if you wish.

## Server requirements

- A [Relay](https://github.com/overnite22/relay-server-prod) version that offers **Administration → Devices** (device tokens).
- If Relay sits behind an SSO proxy (oauth2-proxy…), an exception for `/api/`: the app authenticates with its token.

## Development

```
./gradlew :relaykit:test              # shared logic (JVM)
./gradlew :app:testDebugUnitTest      # pixel-level rendering and UI flows (Robolectric, no emulator)
./gradlew :app:recordRoborazziDebug   # same, with screenshots in app/screenshots/
APP_VERSION=0.4.0 APP_BUILD=1 ./gradlew :app:assembleRelease
```

- `relaykit/`: models, API client (OkHttp, no redirects), pairing, Guacamole protocol, recordings, commands,
  administration. No Android dependency.
- `app/`: Compose UI, Guacamole rendering on `Bitmap`/`Canvas`, remote screen, sessions.
- Tests only use fictitious data (`example.com`, `192.0.2.x` documentation addresses).

Release builds are signed with a key kept outside the repository (`~/.relay-android-signing/keystore.properties`
on the build machine); without it, `assembleRelease` produces an unsigned APK.

Licensed under the Apache License 2.0.
