# pane-management-mobile

Android companion for the pane-management Tauri desktop app. Kotlin 2.1 + Jetpack Compose (BOM 2025.01.01) + Ktor 3.0.3 client. Single-activity Compose app. Talks to the desktop companion on port 8833 over Tailscale (or `adb reverse tcp:8833 tcp:8833` for dev).

This repo is nested at `/home/andrea/pane-management/pane-management-mobile/` inside the outer `pane-management` repo but has its own git history (`andreacanes/pane-management-mobile`). The outer `.gitignore` hides it. All Android commits go to this repo.

## Toolchain

| Tool | Version |
|---|---|
| AGP | 8.7.3 |
| Kotlin | 2.1.10 |
| Compose BOM | 2025.01.01 |
| Ktor | 3.0.3 |
| Navigation Compose | 2.8.5 |
| kotlinx.serialization | 1.8.0 |
| kotlinx.coroutines | 1.10.1 |
| DataStore Preferences | 1.1.2 |
| compileSdk / targetSdk | 36 |
| minSdk | 26 |
| JDK | 21 |

Only `android-32` and `android-36` are installed on the build box — `compileSdk 35` will fail with "Platform SDK with path: platforms;android-35" and that's why we target 36 specifically. AGP 8.7.3 warns about compileSdk 36; add `android.suppressUnsupportedCompileSdk=36` to `gradle.properties` if it gets noisy.

## Module tree

```
app/src/main/java/com/andreacanes/panemgmt/
├── MainActivity.kt                 ComponentActivity + enableEdgeToEdge + MaterialTheme + PaneMgmtApp()
├── PaneMgmtApp.kt                  NavHost; routes SETUP / GRID / DETAIL; startDestination from AuthStore.configFlow
├── data/
│   ├── CompanionClient.kt          Ktor HttpClient(CIO) + WebSockets + ContentNegotiation + Auth
│   ├── AuthStore.kt                DataStore Preferences: { url, bearer }
│   └── models/Dtos.kt              @Serializable mirrors of Rust companion DTOs
├── ui/
│   ├── setup/SetupScreen.kt        URL + bearer form, test + save
│   ├── grid/PaneGridScreen.kt      LazyColumn of pane cards, live via WebSocket
│   └── detail/PaneDetailScreen.kt  capture + input + mic + approval dialog
└── voice/VoiceInputController.kt   SpeechRecognizer wrapper, offline preferred
```

No `test/`, no `androidTest/`, no lint plugin wired. No release signing configured — `assembleRelease` falls back to debug keystore.

## DTO wire contract (CRITICAL)

The JSON wire protocol is shared with the Rust companion at `workspace-resume/src-tauri/src/companion/models.rs`. Any change on either side needs a matching change here.

| Concept | Rust (`companion/models.rs`) | Kotlin (`data/models/Dtos.kt`) |
|---|---|---|
| `PaneState` enum | `#[serde(rename_all = "lowercase")]` — idle/running/waiting/done | `@SerialName("lowercase")` — same four variants |
| `PaneDto` | `snake_case` fields | camelCase with `@SerialName("snake_case")` per field |
| `SessionDto` | `u32 windows` | `Int windows` |
| `ApprovalDto` | `Uuid` + `serde_json::Value` | `String` + `JsonElement` |
| `ApprovalResponse` | `Decision` enum | send raw `String` — `"allow"` or `"deny"` |
| `EventDto` tagged union | `#[serde(tag = "type", rename_all = "snake_case")]` | `sealed class` + `@SerialName("snake_case")` per variant; `Json { classDiscriminator = "type" }` in `CompanionClient` |

**Kotlin also has a `Hello` variant** (`Dtos.kt:106`) that does NOT exist in the Rust enum — it corresponds to the synthetic hello frame emitted at `companion/ws.rs:37-43` using `serde_json::json!` directly. Wire-compatible because the Kotlin JSON config has `ignoreUnknownKeys = true`.

**Adding a field is safe** (make Rust side `Option` with `#[serde(skip_serializing_if = "Option::is_none")]`, give Kotlin a default). **Renaming a field silently breaks decoding** — no error surfaces with `ignoreUnknownKeys = true`.

## Ktor client patterns

`CompanionClient(baseUrl, token)` is constructed per-use (not a singleton). `DefaultRequest` (`CompanionClient.kt:68-73`) sets `Authorization: Bearer <token>` once and rewrites every call's `protocol/host/port` from the parsed base URL, so individual handlers pass paths only: `client.get("/api/v1/panes")`.

WebSocket auth: `parameter("token", token)` query param (`CompanionClient.kt:133`) — matches Rust `auth::bearer_mw`'s query-param fallback. Browsers can't set `Authorization` on WS handshakes.

JSON config: `ignoreUnknownKeys = true`, `encodeDefaults = true`, `classDiscriminator = "type"`. The last one is load-bearing for decoding the Rust tagged-union `EventDto`.

Timeouts: request 15s, connect 5s, socket 30s. `expectSuccess = true`; callers wrap in `try/catch (t: Throwable)` and `finally { client.close() }`.

## Compose conventions

- One `*Screen.kt` per `ui/<feature>/` directory
- Single-activity; nav lives in `PaneMgmtApp.kt`, gated on `AuthStore.configFlow` (null → SETUP, non-null → GRID)
- Material 3 only; fully-qualified single-symbol imports (no wildcards); `@OptIn(ExperimentalMaterial3Api::class)` per composable as needed
- Color literals for pane state colors are inline (`Color(0xFF4CAF50)`) in `PaneGridScreen::stateColor`
- Errors: `runCatching { ... }.onFailure { ... }` for fire-and-forget; no sealed error classes

## Android / ADB gotchas

Every one of these has cost hours at least once — don't re-learn.

1. **`run-as` drops network capabilities.** `adb shell run-as com.andreacanes.panemgmt curl ...` fails even though the real app works. Don't use `run-as` to debug connectivity — test via the actual app or `adb shell curl`
2. **`input text` hits the wrong field** when the soft keyboard opens and compresses the layout. Always dismiss the keyboard (tap a non-input area), re-dump the UI with `uiautomator dump` to get current bounds, then tap
3. **OnePlus "Writing Tools" popup** hijacks taps on AI-augmentable-looking input fields. Dismiss with BACK before interacting
4. **Tauri-style button bounds include neither icon nor label** — look for `<View clickable="true">` elements in the UI dump, not the `<Text>` inside. Filter by `clickable=true` to find button centers
5. **BACK to dismiss keyboard can navigate back too far** — on SetupScreen, pressing BACK with no keyboard visible exits the activity. Prefer tapping a non-input area
6. **ADB drops mid-session** when the phone sleeps or Tailscale reconnects. Recover with `$ADB connect 100.83.163.105:5555`
7. **SurfaceFlinger "out of order buffers"** logcat noise is harmless. Filter by PID: `adb logcat --pid=$(adb shell pidof com.andreacanes.panemgmt)`
8. **`usesCleartextTraffic=true`** in `AndroidManifest.xml` is intentional — the companion is HTTP-only over Tailscale

## Build / install

Use the `build` skill in the outer project — it handles sync.sh + gradlew + ADB install + launch in one sequence. Gist:

```bash
ADB=/mnt/c/Users/Andrea/AppData/Local/Android/Sdk/platform-tools/adb.exe
/home/andrea/pane-management/sync.sh && \
  cmd.exe /c "cd /d C:\Users\Andrea\Desktop\Botting\pane-management-v0.4.0\pane-management-mobile && gradlew.bat assembleDebug" && \
$ADB connect 100.83.163.105:5555 2>/dev/null && \
$ADB install -r 'C:\Users\Andrea\Desktop\Botting\pane-management-v0.4.0\pane-management-mobile\app\build\outputs\apk\debug\app-debug.apk' && \
$ADB shell am start -n com.andreacanes.panemgmt/.MainActivity
```

ADB connects to the phone **wirelessly over Tailscale** (not USB). Always `adb connect` before install.

## Connection URL

- Tailscale (normal): `http://100.110.47.29:8833`
- ADB reverse tunnel (dev, no Tailscale needed): `$ADB reverse tcp:8833 tcp:8833`, then `http://localhost:8833`
