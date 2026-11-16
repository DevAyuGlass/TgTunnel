# TGTunnel

TGTunnel is an experimental **Android-only** local MTProto proxy client for Telegram. It runs a loopback TCP listener on the same device as Telegram and forwards the obfuscated MTProto stream through supported upstream transports.

The project is experimental: a successful APK build or a running local listener does not guarantee that an upstream Telegram connection will work on every network.

## Platform and architecture

- **Application:** Android (Kotlin + Jetpack Compose).
- **Android proxy runtime:** Kotlin, using Android's TLS and cryptographic providers.
- **Background execution:** Android foreground service with a persistent notification.
- **Native core:** separate C++17 library, built with CMake, Android NDK and Mbed TLS.
- **JNI:** bridge between Kotlin and the native library; the current Android listener and transport lifecycle are implemented in Kotlin and do not use the C++ transport lifecycle.
- **Python reference:** `third_party/tg-ws-proxy-reference/` is retained for protocol-porting reference only; it is not bundled or executed by the Android app.

Linux is a development environment for building and testing the platform-independent C++ core. There is no supported Linux desktop client.

## How it works

1. TGTunnel starts an Android foreground service and opens a local TCP listener (default configuration is loopback-only).
2. Telegram on the **same device** connects to the local listener using the generated `tg://proxy` link.
3. TGTunnel reads and validates the 64-byte obfuscated MTProto handshake, checks the configured secret, and identifies the Telegram DC and transport tag.
4. The engine prepares the MTProto relay initialization and AES-CTR stream context.
5. It attempts to establish an upstream route. Depending on configuration and availability, the route selection includes:
   - the Cloudflare Proxy domain pool;
   - Telegram TLS WebSocket endpoints (`kws{dc}.web.telegram.org/apiws`);
   - an optional user-configured Cloudflare Worker WebSocket-to-TCP bridge;
   - direct TCP to the selected Telegram DC on port 443.
6. Once an upstream is established, TGTunnel relays data in both directions between Telegram and the upstream connection.

The Cloudflare Proxy pool and the optional user-owned Worker are separate mechanisms. DC-to-IP redirects can also be configured for supported routing scenarios.

## Current Android proxy features

- Loopback TCP listener bound to `127.0.0.1`.
- Local generation and persistence of a 16-byte MTProto secret.
- Generated `tg://proxy` link for Telegram on the same device.
- Obfuscated MTProto handshake validation and AES-CTR stream transformation.
- Abridged, intermediate and padded-intermediate transport tags.
- Telegram DC 1–5 and DC 203 (mapped to DC2 for the WebSocket hostname).
- TLS WebSocket connections using Android's TLS trust store and hostname verification.
- Cloudflare Proxy domain pool with connection selection and health/backoff tracking.
- Optional Cloudflare Worker fallback, configured by hostname.
- Configurable DC-to-IP redirects and direct TCP fallback.
- Foreground service, persistent notification, event log and log copying.
- Handshake and upstream diagnostics to help identify which connection stage failed.

**Important:** the local listener accepting a connection is only the first stage. It does not prove that Telegram completed its MTProto handshake or that an upstream route was established. Check the event log for handshake, upstream connection and relay messages.

## What is not implemented

- A system-wide Android VPN; TGTunnel is a local proxy endpoint, not a `VpnService`.
- A public proxy server for other devices; the listener is bound to loopback.
- HTTP/2 media multiplexing.
- Fake-TLS domain mode.
- Test-DC routing.
- The C++ core does not yet own the Android runtime's transport lifecycle.

The Cloudflare Worker forwards the encrypted MTProto stream; it does not decrypt Telegram messages. Cloudflare availability, account limits and network filtering can still prevent a connection.

## Install and use

1. Download a debug or release APK from the repository's **Actions** workflow artifacts or **Releases**.
2. Install it on Android and open TGTunnel.
3. Start the proxy.
4. Open the generated `tg://proxy` link in Telegram on the **same Android device** and confirm adding the proxy.
5. Keep the TGTunnel foreground service running while using the local proxy.

The generated link uses `127.0.0.1`. It is not intended for Telegram on another phone or computer.

## Settings

Available settings include the local listener port, MTProto secret, optional Cloudflare Worker hostname, Cloudflare Proxy fallback toggle and DC-to-IP redirect list. A port already used by another local proxy must be changed to a free port.

To use a Cloudflare Worker, deploy `cloudflare/worker.js`, then enter its hostname in TGTunnel settings without `https://`. This is optional; the app also has other upstream attempts.

## Diagnostics

Useful log stages include:

- `Local listener started` — the local TCP listener is active.
- `Local client accepted` — Telegram or another local client connected to the listener.
- `Handshake started`, `Handshake received · 64/64 bytes` — MTProto handshake reception.
- `Handshake timeout` or `Handshake EOF` — the client did not complete the 64-byte handshake.
- `WebSocket established` / upstream connection messages — an upstream transport was established.
- `MTProto relayInit sent` and `Client connected` — the local handshake passed and the relay was initialized.
- `DC upstream connection failed` — no configured upstream attempt succeeded.

A WebSocket upgrade alone is not proof that Telegram traffic is flowing end-to-end. When reporting an issue, include the complete event log and note the Android version, device, network type and whether a VPN is enabled. **Remove the MTProto secret and other private data before sharing logs.**

## Build Android APKs

The Android project is rooted in `android/`:

```sh
gradle --no-daemon -p android :app:assembleDebug :app:assembleRelease
```

GitHub Actions builds separate debug and release APK artifacts.

Supported Android ABIs:
- `arm64-v8a`
- `armeabi-v7a`
- `x86_64`

Minimum Android version: API 24 (Android 7.0).

## Build and test the C++ core on Linux

Linux can build and test the platform-independent C++ core; it is not a supported end-user client platform.

```sh
cmake -S . -B build -DTGTUNNEL_BUILD_TESTS=ON -DCMAKE_BUILD_TYPE=Release
cmake --build build --parallel
ctest --test-dir build --output-on-failure
```

## Optional Cloudflare Worker deployment

1. Open Cloudflare Dashboard → **Workers & Pages** and create a Worker.
2. Deploy the source from [`cloudflare/worker.js`](cloudflare/worker.js).
3. Copy the Worker hostname (for example, `name.username.workers.dev`).
4. In TGTunnel settings, enter the hostname without `https://`.
5. Restart the proxy.

The Worker is an optional WebSocket-to-TCP relay for the encrypted MTProto stream. It does not decrypt message contents. See [`cloudflare/NOTICE.md`](cloudflare/NOTICE.md) for attribution and licensing information.
