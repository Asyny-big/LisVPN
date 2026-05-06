# LisVPN Android — Architecture

Reference document. Mirrors the design committed by the architecture review on 2026-05-06.
For project layout and build commands see `README.md`.

---

## §1 Goals

- **Consumer UX** comparable to Happ / Amnezia / Cloudflare WARP. Single visible primary action.
- **Production-oriented foundation** — modular, scalable, with no legacy compromises.
- **Off-store distribution** via APK + Telegram; no Google Play Services dependency.
- **Backend-optional MVP** — pasted subscription URL, deep link or QR import are all sufficient.
- **KMP-ready** core layer (domain + parsers) for future iOS/Desktop reuse.

## §2 Technology stack

| Layer | Choice | Rationale |
|---|---|---|
| Language | Kotlin 2.0 + K2 | Single language across all layers. |
| UI | Compose BOM 2024.10 + Material 3 | First-class declarative UI; built-in animations. |
| Navigation | Compose Navigation 2.8 | Type-safe routes (post-MVP). |
| DI | Hilt | Best-in-class for Service/WorkManager integration. |
| Networking | Ktor 3 + OkHttp engine | KMP-ready, streaming-friendly. |
| Persistence | Room 2.6 (KSP), Preferences DataStore | Standard. |
| Background | WorkManager 2.10 + foreground service | Periodic refresh + tunnel runtime. |
| Secure storage | Tink + EncryptedFile | androidx.security deprecated 2024. |
| VPN core | sing-box `libbox.aar` | Required by product brief. |
| Build | Gradle 8.10 KTS + Version Catalog + Convention plugins | DRY across 20+ modules. |

## §3 Module graph

```
:app
 ├── :feature:home, :feature:profiles, :feature:servers, :feature:splittunnel,
 │   :feature:settings, :feature:onboarding, :feature:updates
 ├── :core:data
 │   ├── :core:domain
 │   ├── :core:database
 │   ├── :core:datastore
 │   ├── :core:network
 │   └── :vpn:core, :vpn:config, :vpn:health
 ├── :core:designsystem ─→ :core:common
 ├── :core:ui ─────────→ :core:designsystem
 ├── :vpn:core
 │   ├── :vpn:libbox  (libbox.aar)
 │   └── :vpn:config
 └── :vpn:tunnel
```

`:core:domain` and `:vpn:config` are pure-Kotlin candidates — they do not import any Android API
and can become KMP modules when iOS/Desktop work begins.

## §4 VPN runtime

### §4.1 State machine

```
Disconnected ──tap──▶ Preparing ──granted──▶ Connecting ──ok──▶ Connected
     ▲              │                            │                │
     │              └─denied──▶ Error             └─fail──▶ Error  │
     │                                                              │
     └────────── Reconnecting ◀──network change──────────────────────┘
```

Implementation: `VpnConnectionController.state: StateFlow<VpnState>` (singleton, app-scoped).
The controller is a writer-by-delegation — actual mutations happen inside `LisVpnService`.

### §4.2 Service lifecycle

`com.lisvpn.android.vpn.core.LisVpnService` is a `VpnService` declared with
`foregroundServiceType="specialUse"`:

- `onStartCommand(ACTION_START)` → `startForeground(specialUse)` → `LibboxBridge.start()`.
- `onStartCommand(ACTION_STOP)` → `bridge.stop()` → `stopForeground(REMOVE)` → `stopSelf()`.
- `onRevoke()` → publishes `Error(PermissionRevoked)` → graceful stop.
- `START_STICKY` ensures recovery after Android process kills.

### §4.3 libbox bridge

```
LisVpnService
   │ creates per-connection
   ▼
LibboxBridge ──▶ libbox.Libbox.newService(configJson, LisPlatformInterface)
                       │
                       ▼
                 BoxService.start() / sleep() / wake() / close()
                       │
                       ▼
            calls back into LisPlatformInterface.openTun
                       │
                       ▼
            VpnService.Builder.establish() → file descriptor
```

`LibboxBridge` enforces the *single BoxService per process* invariant via a `Mutex`.

### §4.4 Sing-box config builder

`SingBoxConfigBuilder` produces a complete config JSON that includes:

- `inbounds[0]` — a TUN inbound with `auto_route=true`, `strict_route=true`, `stack=system`.
- `outbounds` — `direct` / `block` / `dns-out` plus one outbound per server.
- When `smartSelection && servers.size > 1` an additional `urltest` outbound is added that
  becomes the route's `final` target — sing-box will probe `generate_204` every 3 minutes and
  switch to the lowest-latency outbound automatically.

## §5 Smart server selection (two-level)

| Level | Where | Frequency | Output |
|---|---|---|---|
| 1 — App-side ranking | `:vpn:health` (Phase 8) → Room | Every 30 min when on cellular/Wi-Fi | Top-N by score |
| 2 — Real-time | sing-box `urltest` outbound | Every 3 min during connection | Live winner |

Score formula:

```
score = 0.45 * (1 - normLatency)
      + 0.20 * (1 - normHandshake)
      + 0.20 * successRate24h
      + 0.10 * networkTypeStability
      + 0.05 * recencyOfLastSuccess
```

## §6 Per-app routing (split tunneling)

Enforced **only** at the Android `VpnService.Builder` layer:

```kotlin
when (appRules.mode) {
    Off          -> Unit
    AllowList    -> packages.forEach { builder.addAllowedApplication(it) }
    DisallowList -> packages.forEach { builder.addDisallowedApplication(it) }
}
```

Self-package is always excluded from the tunnel to prevent loops.

Sing-box `process_name` rules are intentionally **not** used — they rely on `/proc/net` access
which is unavailable on stock Android 10+ inside TUN mode.

## §7 Profile import pipeline (Phase 6+)

```
Raw input (URL / paste / QR)
        │
        ▼
SubscriptionDecoder      (base64 detect, line split)
        │
        ▼
UriParserRegistry        (vless / vmess / trojan / ss)
        │
        ▼
ProfileRepository.upsert (idempotent on rawUri)
        │
        ▼
ServerHealthRepository   (kicks off background probe)
```

Subscription headers (`Profile-Title`, `Subscription-Userinfo`, `announce`,
`Profile-Update-Interval`) are surfaced into `Profile.expiresAt`, `Profile.announceMessage`,
`Profile.updateIntervalHours`.

## §8 Background work

Scheduled via WorkManager (Hilt-aware factory installed in `LisVpnApp`):

- `SubscriptionRefreshWorker` — periodic, interval = `Profile.updateIntervalHours`. Re-fetches
  subscription URLs, diffs servers, prunes stale ones.
- `HealthProbeWorker` — periodic 30 min when not on metered cellular. Launches probes via
  `ServerHealthRepository.probe`.
- `AppUpdateCheckWorker` — daily, fetches `release/android.json`.

All workers use `IoDispatcher` from `:core:common`.

## §9 Security

- **Token at rest** — Tink `AeadConfig.register()` + `EncryptedFile` for subscription tokens
  (Phase 7).
- **Certificate pinning** — `CertificatePinner` (OkHttp) for prod flavor only. Pins are
  configured in `:app:di:AppNetworkModule`.
- **Network security config** — `cleartext = false` globally (`app/src/main/res/xml/network_security_config.xml`).
- **Backup exclusion** — DataStore + database + encrypted/ excluded from cloud backups
  (`app/src/main/res/xml/backup_rules.xml`).
- **Anti-tamper** — soft signing-cert check at startup (Phase 11). Failure shows a non-blocking
  warning rather than killing the app.
- **Logging** — `LisLogTree` masks UUIDs / tokens / subscription URLs in production builds.

## §10 Reconnect logic (Phase 13)

```
ConnectivityManager.NetworkCallback.onLost  ─→ controller.publishReconnecting → bridge.sleep()
ConnectivityManager.NetworkCallback.onAvailable ─→ bridge.wake() → controller.publishConnected
```

Wi-Fi ↔ LTE transitions are absorbed by sing-box's existing wake/sleep semantics — we never tear
down the TUN interface unless the OS revokes permission.

## §11 Updates (off-store)

Feed JSON contract:

```json
{
  "versionCode": 14,
  "versionName": "1.0.4",
  "apkUrl": "https://lisvpn.ru/dl/lisvpn-1.0.4.apk",
  "sha256": "...",
  "minSupportedVersionCode": 1,
  "rolloutPercent": 30,
  "changelog": "...",
  "mandatory": false
}
```

Rollout is gated by a stable `installId` bucket (FNV-1a of a per-install UUID) so the same user
either always receives or always defers a given staged version.

## §12 Testing strategy (Phase 6+)

- **Unit** — JUnit 4 + Turbine for Flow assertions, MockK for behavioural stubs.
- **Repository** — Robolectric for PackageManager, Tink, DataStore.
- **Compose** — `androidx.compose.ui.test.junit4` for screen-level snapshots.
- **End-to-end** — Maestro flows (`./maestro/home_connect.yaml`) against an emulator with a stub
  3x-ui server.
- **Manifest** — every release blocked on a clean `./gradlew :app:lintProdRelease`.
