# LisVPN Android

Современный consumer-VPN клиент для Android на базе **sing-box** (libbox.aar). Чистая архитектура,
Jetpack Compose, Material 3, Hilt, Ktor, Room. Distributed как APK через сайт + Telegram (без Google Play).

> Главный принцип UX: **«Нажал кнопку — всё работает.»**

---

## Что уже готово (Phase 0–5 foundation)

- Multi-module Gradle (KTS + Version Catalog + Convention plugins).
- `:app` с dev/prod flavors, deep-link парсером (`lisvpn://import?sub=...` и `https://lisvpn.ru/c/<token>`),
  edge-to-edge Compose.
- Material 3 design system (`:core:designsystem`): тема, типографика, **`StatusOrb`** (анимированный
  индикатор статуса), `LisPrimaryButton`, `LisInfoLine`.
- Чистый `:core:domain` с sealed `VpnState`, `Server`, `Profile`, `AppRules`, `HealthSnapshot` и use case'ами.
- VPN-слой:
  - `:vpn:libbox` — обёртка `libbox.aar` (sing-box). `LibboxBridge` + `LisPlatformInterface` (TUN, split-tunnel).
  - `:vpn:core` — `LisVpnService` (foreground, `specialUse`), `VpnConnectionController`,
    `VpnNotifier`, intent-протокол.
  - `:vpn:config` — `VlessUriParser` (включая VLESS Reality из 3x-ui), `SubscriptionDecoder`,
    `SingBoxConfigBuilder` с `urltest` outbound для smart selection.
- `:core:data` — `VpnRepositoryImpl`, `AppRulesRepositoryImpl` (DataStore), in-memory
  `ProfileRepositoryImpl`, `InstalledAppsRepositoryImpl` (PackageManager).
- `:core:network` — Ktor 3 + OkHttp + `CertificatePinner` + flavor-aware `NetworkConfig`.
- `:core:database` — Room scaffold (health-snapshot DAO).
- `:core:datastore` — Preferences DataStore.
- `:feature:home` — главный экран с одной кнопкой подключения.

## Что в стадии разработки (Phase 6+)

| Phase | Содержание |
|---|---|
| 6 | Реальный `ProfileRepository` поверх Room + `SubscriptionFetcher` (Ktor) + `VmessParser`/`TrojanParser`/`ShadowsocksParser`. |
| 7 | `:feature:profiles` — экран импорта (URL paste, QR через CameraX + ML Kit, deep-link автоприём). |
| 8 | Smart selection: реальные health-probe воркеры (`:vpn:health`) + scoring + Room-каэш. |
| 9 | `:feature:splittunnel` — UI выбора приложений. |
| 10 | `:feature:settings` — DNS, режимы, тема. |
| 11 | `:feature:onboarding` + battery-optimization prompt + permission flow. |
| 12 | `:feature:updates` — APK self-update через `release/android.json`. |
| 13 | Reconnect logic: `ConnectivityManager.NetworkCallback` → `BoxService.sleep/wake`. |
| 14 | Telegram OAuth + JWT (после расширения backend). |

---

## Как собрать

### Требования

- JDK 17 (`jenv`/`scoop`/`brew install temurin@17` — любой дистрибутив).
- Android SDK 35 + build-tools (Android Studio Ladybug+ или `sdkmanager` напрямую).
- NDK не требуется — `libbox.aar` уже содержит prebuilt `.so` для всех ABIs.
- Gradle 8.10+ либо первый запуск через Android Studio (он сам подтянет wrapper).

### Локальный запуск (CLI)

```bash
# 1. Один раз — создать gradlew если открываете без Android Studio:
gradle wrapper --gradle-version 8.10.2

# 2. Сборка dev-варианта (backend = govchat.ru, debuggable):
./gradlew :app:assembleDevDebug

# 3. Установка на подключённое устройство:
./gradlew :app:installDevDebug

# 4. Production-сборка (backend = lisvpn.ru, R8 + minify):
./gradlew :app:assembleProdRelease
```

APK артефакты: `app/build/outputs/apk/{dev,prod}/{debug,release}/`.

### Через Android Studio

1. Open → выбрать `D:\ProjectGIT\LisVPN`.
2. Дождаться Gradle sync.
3. Build Variants → выбрать `devDebug` (для разработки) или `prodRelease` (для релиза).
4. ▶ Run.

> **Минимальное API: 26 (Android 8.0)** — это требование `libbox.aar`.

### Подпись prod-сборки

Сейчас `prodRelease` подписывается debug-keystore'ом (заглушка). Для настоящего релиза:

1. Сгенерировать keystore: `keytool -genkeypair -v -keystore lisvpn-release.jks -keyalg RSA -keysize 4096 -validity 10000 -alias lisvpn`.
2. Добавить в `~/.gradle/gradle.properties` (НЕ коммитить):
   ```properties
   LISVPN_RELEASE_STORE_FILE=/абсолютный/путь/lisvpn-release.jks
   LISVPN_RELEASE_STORE_PASSWORD=...
   LISVPN_RELEASE_KEY_ALIAS=lisvpn
   LISVPN_RELEASE_KEY_PASSWORD=...
   ```
3. В `app/build.gradle.kts` подключить `signingConfigs { create("release") { ... } }` и заменить
   `signingConfig = signingConfigs.getByName("debug")` на `release`. (Phase 12.)

---

## Структура проекта

```
LisVPN/
├── build-logic/                          Convention plugins (lisvpn.android.application etc.)
├── app/                                  Application + Hilt graph + Navigation host
├── core/
│   ├── common/                           Pure Kotlin utils, dispatchers, Result types, Timber tree
│   ├── designsystem/                     Material 3 theme + StatusOrb + components
│   ├── ui/                               Compose extensions (collectAsLisState, insets)
│   ├── domain/                           Entities + Repository interfaces + Use cases
│   ├── data/                             Repository implementations + Hilt @Binds
│   ├── database/                         Room (health snapshots)
│   ├── datastore/                        Preferences DataStore
│   └── network/                          Ktor client + CertificatePinner
├── feature/
│   ├── home/                             ★ Главный экран (StatusOrb + connect button)
│   ├── profiles/                         ⏳ Импорт подписок (Phase 7)
│   ├── servers/                          ⏳ Смена сервера (Phase 7)
│   ├── splittunnel/                      ⏳ Per-app rules UI (Phase 9)
│   ├── settings/                         ⏳ DNS, режимы, тема (Phase 10)
│   ├── onboarding/                       ⏳ Welcome + permissions (Phase 11)
│   └── updates/                          ⏳ APK self-update (Phase 12)
└── vpn/
    ├── core/                             ★ LisVpnService + Controller + Notifier
    ├── libbox/                           ★ libbox.aar bridge + PlatformInterface
    ├── config/                           ★ Parsers + sing-box JSON builder
    ├── health/                           ⏳ Probes + scoring (Phase 8)
    └── tunnel/                           ⏳ Network reactor (Phase 13)
```

---

## Архитектура

```
┌──────────────────────────────────────────────────────────────┐
│ Compose UI (feature/*)                                       │
│   HomeRoute → HomeViewModel → ObserveVpnStateUseCase ────┐   │
│                              ConnectVpnUseCase ──────┐   │   │
│                              DisconnectVpnUseCase ─┐ │   │   │
└────────────────────────────────────────────────────│─│───│───┘
                                                     ▼ ▼   ▼
┌────────────────────────────────────────────────────────────┐
│ Domain (core/domain) — pure logic, no Android              │
│   Use cases ↔ Repository interfaces                        │
└────────────────────────────────────────────────────────────┘
                          │
                          ▼
┌────────────────────────────────────────────────────────────┐
│ Data (core/data) — interface ⇒ implementation              │
│   VpnRepositoryImpl → VpnConnectionController              │
│   ProfileRepositoryImpl → Room (Phase 6)                   │
│   AppRulesRepositoryImpl → DataStore                       │
└──────┬─────────────────────────────────────────────────────┘
       │
       ▼
┌────────────────────────────────────────────────────────────┐
│ VPN runtime (vpn/*)                                        │
│   VpnConnectionController (singleton, holds StateFlow)     │
│         │                                                  │
│         ▼ Intent (ACTION_START + EXTRA_CONFIG_JSON)        │
│   LisVpnService (foreground)                               │
│         │                                                  │
│         ▼                                                  │
│   LibboxBridge → libbox.Libbox.newService(json, platform)  │
│         ↑                                                  │
│   LisPlatformInterface ←── VpnService.Builder + protect()  │
└────────────────────────────────────────────────────────────┘
```

State flow один — `VpnConnectionController.state: StateFlow<VpnState>`. UI читает, Service пишет.

### Smart server selection

1. **App-side cold-start ranking** (`SelectBestServerUseCase` + `:vpn:health` Phase 8): берём топ-N
   серверов по rolling score (latency × success rate × stability).
2. **Sing-box `urltest` outbound в реальном времени**: каждые 3 минуты пробует `generate_204` и
   переключает живой канал на лучший. Реализовано в `SingBoxConfigBuilder.kt`.

### Split tunneling

Применяется **только** на Android-уровне через `VpnService.Builder.addAllowedApplication`
/`addDisallowedApplication` — sing-box `process_name`-rules в TUN-режиме на Android ненадёжны.
Логика — `LisPlatformInterface.applyAppRules`.

---

## Backend integration (vpnys-bot)

Андроид-клиент совместим со стандартным subscription endpoint `GET /sub/<token>`:

- Тело — sing-box / Hiddify-формат (plain или base64, по строке `vless://...`).
- Заголовки — `Profile-Title`, `Subscription-Userinfo: expire=<unix>`, `announce: base64:<msg>`,
  `Profile-Update-Interval: <hours>`.
- Device fingerprint (UA + Client Hints) считается на backend → клиент шлёт **стабильный
  User-Agent** (см. `KtorClientFactory.install(UserAgent)`), чтобы один телефон = один device-slot.

В `vpnys-bot` рекомендуется добавить `app-link` redirect:

```
GET https://lisvpn.ru/c/<token> → 302 lisvpn://import?sub=https://lisvpn.ru/sub/<token>
```

Тогда кнопка «Открыть в LisVPN» в боте триггерит автоматический импорт профиля.

---

## libbox.aar

`vpn/libbox/libs/libbox.aar` — официальный sing-box билд (`libbox.*` namespace, prebuilt `.so` для
arm64-v8a / armeabi-v7a / x86 / x86_64). Версии и обновления:

```bash
# Обновление libbox: скачать с https://github.com/SagerNet/sing-box/releases
# Положить под vpn/libbox/libs/libbox.aar (тем же именем). Никаких других действий не нужно —
# Gradle подхватит автоматически.
```

ProGuard-keep правила на `go.**` и `libbox.**` уже прописаны в `vpn/libbox/consumer-rules.pro`
и `app/proguard-rules.pro`.

---

## Соглашения по коду

- **Kotlin 2.0 + K2** компилятор. JDK 17 toolchain. JVM target 17.
- DI: **Hilt** (не Koin). Все Hilt-биндинги — в `*/di/*Module.kt`.
- Networking: **Ktor 3 + OkHttp engine** (не Retrofit).
- Persistence: **Room (KSP)** для структурных данных, **Proto/Preferences DataStore** для
  настроек, **Tink + EncryptedFile** для секретов (Phase 7).
- Compose: только Material 3, никаких XML layouts (кроме адаптивного launcher icon и темы для
  splash-screen).
- ViewModels — `StateFlow<UiState>` (immutable data class). Side effects — `Channel<UiEvent>`.
- Navigation — Compose Navigation 2.8 со строковыми роутами (type-safe маршруты — Phase 10).

---

## Что нужно знать перед первой сборкой

1. **Подписки нет в build тестах**: при первом запуске экран Home покажет «Импортируйте подписку».
   Кнопка ведёт на `:feature:profiles` (пустая заглушка до Phase 7).
2. **VpnService.prepare()** запросит разрешение при первом нажатии «Подключить» — это нормально.
3. **Уведомление "VPN включён"** не сворачивается (`setOngoing(true)`) — это требование Android,
   не баг.
4. **Foreground service permission special-use** — declared в манифесте; Android 14+ требует
   обязательно (см. `<service>` с `android:foregroundServiceType="specialUse"`).

---

## Лицензия

Internal — TBD.
