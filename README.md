# TgTunnel

TgTunnel is an **Android-only** client project for a local Telegram MTProto proxy.

## Supported platform

- **Android only** — the user-facing application is built for Android.
- Linux is used only as a development environment for building and testing the shared C++ core. There is no supported Linux desktop client.

## Current architecture

- Android UI: Kotlin + Jetpack Compose
- Native layer: C++17
- Android bridge: JNI
- Native build: CMake + Android NDK
- Python source under `third_party/tg-ws-proxy-reference/` is preserved only as a protocol-porting reference. It is not bundled or executed by Android.

## Build Android APKs

The Android project is rooted in `android/`:

```sh
gradle --no-daemon -p android :app:assembleDebug :app:assembleRelease
```

The GitHub Actions workflow installs Android SDK 35, NDK 27.2 and CMake 3.22.1, then publishes separate debug and release APK artifacts.

Supported Android ABIs:
- `arm64-v8a`
- `armeabi-v7a`
- `x86_64`

Use the release APK for performance testing. Debug builds are not representative of release rendering and runtime performance.

## Linux development tests

Linux can build and test the platform-independent C++ core, but this is not a supported end-user platform:

```sh
cmake -S . -B build -DTGTUNNEL_BUILD_TESTS=ON -DCMAKE_BUILD_TYPE=Release
cmake --build build --parallel
ctest --test-dir build --output-on-failure
```

## Android proxy runtime

The Android application now includes a local TCP listener bound to `127.0.0.1`, MTProto obfuscated-handshake validation, AES-CTR stream transformation, and an upstream transport that tries Telegram's TLS WebSocket endpoint (`kws{dc}.web.telegram.org/apiws)) before falling back to direct Telegram DC TCP on port 443.

- Generates and persists a 16-byte MTProto secret locally.
- Supports Telegram DC 1–5 and DC 203 (mapped to DC2 for the WebSocket hostname).
- Supports abridged, intermediate, and padded-intermediate obfuscation tags.
- Uses Android's TLS trust store and hostname verification for WebSocket connections.
- The local listener is loopback-only; use the generated Telegram proxy link from the app.
- The app process must remain alive while the local proxy is in use; a foreground service is not included yet.

If configured, a Cloudflare Worker is tried after Telegram's direct WebSocket endpoints and before direct DC TCP. The Worker hostname is optional; without it, the runtime retains the original Telegram WebSocket → direct TCP sequence. The app currently uses the Worker's WebSocket-to-TCP bridge only; HTTP/2 media multiplexing, Fake-TLS domain mode, and test-DC routing are not implemented.

## Native C++ core

The C++17 core remains separately built and tested with Mbed TLS 3.6.5. It contains the protocol handshake and cryptographic primitives, but the current Android listener/transport path is implemented in Kotlin using Android's platform TLS and crypto providers. The C++ transport lifecycle is not yet used by the Android runtime.

## Linux development tests

Linux can build and test the platform-independent C++ core, but this is not a supported end-user platform:

```sh
cmake -S . -B build -DTGTUNNEL_BUILD_TESTS=ON -DCMAKE_BUILD_TYPE=Release
cmake --build build --parallel
ctest --test-dir build --output-on-failure
```


## Optional Cloudflare Worker fallback

1. Create a Cloudflare Worker and deploy the source in [cloudflare/worker.js](cloudflare/worker.js).
2. Copy its `workers.dev` hostname (or configured custom hostname).
3. Open TGTunnel → Settings → Cloudflare Worker and paste the hostname without `https://`.
4. Start the local proxy. If Telegram's direct WebSocket endpoints fail, TGTunnel will try the configured Worker before direct TCP.

The Worker only accepts Telegram WebSocket DC hostnames and relays the encrypted MTProto stream; it does not decrypt Telegram traffic. Cloudflare account limits, network filtering of Cloudflare, and Worker availability can still prevent a connection.
