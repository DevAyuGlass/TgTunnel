# TgTunnel

TgTunnel is an Android + Linux client project for a local Telegram MTProto proxy.

## Current architecture

- Android UI: Kotlin + Jetpack Compose
- Native layer: C++17
- Android bridge: JNI
- Native build: CMake + Android NDK
- Linux entry point: CMake CLI
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

## Build the portable C++ core on Linux

```sh
cmake -S . -B build -DTGTUNNEL_BUILD_TESTS=ON -DCMAKE_BUILD_TYPE=Release
cmake --build build --parallel
ctest --test-dir build --output-on-failure
```

The CLI currently supports `--info` and a guarded `start <port>` entry point.

## Current limitation

The Compose UI, settings persistence, JNI boundary and shared C++ build are in place. The MTProto transport has **not** yet been ported to C++. The native start operation intentionally returns an error instead of pretending that a proxy is connected. Do not use this build as a working Telegram proxy yet.

The next implementation step is to port the transport/protocol modules from the preserved reference implementation into `core/`, with compatibility tests before enabling a successful connection state.
