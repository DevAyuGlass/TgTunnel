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

## Current limitation

The Compose UI, settings persistence, JNI boundary and shared C++ build are in place. The first native protocol layer now includes SHA-256, AES-256-CTR, client obfuscation-handshake decryption, and relay-init generation, backed by Mbed TLS 3.6.5. These primitives have compatibility tests, but they are **not yet wired into a TCP listener or WebSocket relay**. The native start operation intentionally returns an error instead of pretending that a proxy is connected. Do not use this build as a working Telegram proxy yet.

The next implementation step is to port the transport/protocol modules from the preserved reference implementation into `core/`, with compatibility tests before enabling a successful connection state.
