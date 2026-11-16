# TGTunnel

**TGTunnel** — экспериментальный Android-клиент локального MTProto-прокси для Telegram. Приложение запускает TCP listener на самом устройстве и пересылает obfuscated MTProto-поток через доступные upstream-транспорты.

Проект находится в разработке. Успешная сборка APK или запуск локального listener сами по себе не гарантируют, что соединение с Telegram заработает в любой сети.

- **Платформа приложения:** Android.
- **Исходный код:** [GitHub](https://github.com/DevAyuGlass/TgTunnel).
- **Распространение:** APK из GitHub Actions / GitHub Releases.

## Как это работает

1. TGTunnel запускает Android foreground service и открывает локальный TCP listener.
2. Telegram на **том же устройстве** подключается к нему по созданной приложением ссылке `tg://proxy`.
3. Движок принимает 64-байтовый obfuscated MTProto handshake, проверяет secret и определяет Telegram DC и тип транспорта.
4. TGTunnel подготавливает relay initialization и контекст AES-CTR.
5. Приложение пытается установить исходящее соединение по одному из доступных маршрутов:
   - пул доменов Cloudflare Proxy;
   - TLS WebSocket endpoints Telegram (`kws{dc}.web.telegram.org/apiws`);
   - необязательный собственный Cloudflare Worker с WebSocket-to-TCP relay;
   - прямое TCP-подключение к выбранному Telegram DC на порту 443.
6. После установления upstream-соединения TGTunnel пересылает данные в обе стороны между Telegram и выбранным маршрутом.

Cloudflare Proxy pool и собственный Cloudflare Worker — два отдельных механизма. Также можно настроить перенаправление DC → IP.

## Возможности текущей версии

- Локальный TCP listener на `127.0.0.1`.
- Генерация и локальное сохранение 16-байтового MTProto secret.
- Формирование ссылки `tg://proxy` для Telegram на этом же устройстве.
- Проверка obfuscated MTProto handshake и преобразование потока AES-CTR.
- Поддержка abridged, intermediate и padded-intermediate transport tags.
- Telegram DC 1–5 и DC 203 (для WebSocket hostname используется DC2).
- TLS WebSocket с использованием системного хранилища сертификатов Android и проверкой hostname.
- Пул Cloudflare Proxy domains с выбором маршрута и учётом состояния соединений.
- Необязательный Cloudflare Worker fallback, настраиваемый по hostname.
- Настраиваемые DC → IP redirects и прямой TCP fallback.
- Фоновый foreground service с постоянным уведомлением.
- Журнал событий, копирование логов и диагностика handshake/upstream-соединений.

**Важно:** строка `Local listener started` означает только, что локальный порт запущен. Она не доказывает, что Telegram передал handshake или что исходящее соединение установлено. Для проверки нужно смотреть последующие сообщения handshake, upstream и relay.

## Что пока не реализовано

- Системный Android VPN: приложение не использует `VpnService` и не перенаправляет весь трафик устройства.
- Публичный прокси для других устройств: listener привязан к loopback-интерфейсу.
- HTTP/2 media multiplexing.
- Fake-TLS domain mode.
- Test-DC routing.
- Управление жизненным циклом Android-транспорта из C++-ядра.

Cloudflare Worker пересылает зашифрованный MTProto-поток и не расшифровывает сообщения Telegram. Доступность Cloudflare, лимиты аккаунта и фильтрация сети могут помешать соединению.

## Установка и использование

1. Откройте **Actions** репозитория или страницу **Releases**.
2. Скачайте debug или release APK.
3. Установите APK на Android и запустите TGTunnel.
4. Нажмите **Подключиться**.
5. Откройте созданную приложением ссылку `tg://proxy` в Telegram на **том же устройстве** и подтвердите добавление прокси.
6. Оставьте foreground service TGTunnel работающим, пока используете локальный прокси.

Ссылка использует адрес `127.0.0.1`. Она не создаёт публичный сервер и не предназначена для Telegram на другом телефоне или компьютере.

## Настройки

В зависимости от сборки доступны настройки локального порта, MTProto secret, hostname собственного Cloudflare Worker, переключатель Cloudflare Proxy fallback и список DC → IP redirects.

Если выбранный локальный порт уже занят другим прокси, укажите свободный порт.

### Необязательная настройка Cloudflare Worker

1. Откройте Cloudflare Dashboard → **Workers & Pages** и создайте Worker.
2. Разверните код из [`cloudflare/worker.js`](cloudflare/worker.js).
3. Скопируйте hostname Worker, например `name.username.workers.dev`.
4. В настройках TGTunnel укажите hostname без `https://`.
5. Перезапустите прокси.

Worker используется как дополнительный WebSocket-to-TCP маршрут для зашифрованного MTProto-потока. Он не расшифровывает сообщения. Этот маршрут необязателен.

## Диагностика

Основные сообщения журнала:

- `Local listener started` — локальный TCP listener запущен.
- `Local client accepted` — Telegram или другой локальный клиент подключился к listener.
- `Handshake started`, `Handshake received · 64/64 bytes` — приём MTProto handshake.
- `Handshake timeout` или `Handshake EOF` — клиент не передал полный 64-байтовый handshake.
- `WebSocket established` и сообщения upstream — установлен исходящий транспорт.
- `MTProto relayInit sent` и `Client connected` — локальный handshake прошёл, relay инициализирован.
- `DC upstream connection failed` — не удалось подключиться ни одним из предусмотренных upstream-маршрутов.

Сам по себе успешный WebSocket upgrade ещё не доказывает, что данные Telegram проходят сквозь туннель.

При создании issue приложите полный журнал событий, модель устройства, версию Android, тип сети (Wi-Fi или мобильная) и информацию о включённом VPN. **Перед отправкой удалите MTProto secret и другие личные данные.**

## Архитектура

- **Android UI:** Kotlin + Jetpack Compose.
- **Android listener и сетевой транспорт:** Kotlin, системные TLS- и криптографические провайдеры Android.
- **Фоновая работа:** Android foreground service.
- **Отдельное ядро:** C++17, CMake, Android NDK и Mbed TLS.
- **JNI:** мост между Kotlin и native-библиотекой; текущий Android listener и жизненный цикл сетевого транспорта реализованы на Kotlin и пока не используют C++ transport lifecycle.
- **Python reference:** исходники в `third_party/tg-ws-proxy-reference/` сохранены только как справочный материал для переноса протокола. Android-приложение их не запускает и не включает в runtime.

Поддерживаемые Android ABI:
- `arm64-v8a`
- `armeabi-v7a`
- `x86_64`

Минимальная версия Android: API 24 (Android 7.0).

## Сборка Android APK

Из корня репозитория:

```sh
gradle --no-daemon -p android :app:assembleDebug :app:assembleRelease
```

GitHub Actions собирает отдельные debug и release APK artifacts.

## Сборка и тесты C++-ядра на Linux

Linux используется как среда разработки и тестирования платформенно-независимого C++-ядра, а не как поддерживаемая платформа приложения.

```sh
cmake -S . -B build -DTGTUNNEL_BUILD_TESTS=ON -DCMAKE_BUILD_TYPE=Release
cmake --build build --parallel
ctest --test-dir build --output-on-failure
```

## Лицензии и сторонние компоненты

Исходный протокол Cloudflare Worker основан на реализации [Flowseal/tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy). Подробности атрибуции и лицензирования: [`cloudflare/NOTICE.md`](cloudflare/NOTICE.md).
