# Pane Management (Android)

Android companion app for the `pane-management` project. Connects to a local
companion backend over HTTP + WebSocket to browse tmux sessions, view pane
output, send input, and resolve Claude Code approvals.

## First-time setup

The Gradle wrapper JAR (`gradle/wrapper/gradle-wrapper.jar`) is **not** checked
in. The easiest way to get it:

1. Open this directory in Android Studio (Hedgehog or newer).
2. Let the initial Gradle sync run — it will download Gradle 8.11.1 and
   generate the wrapper JAR automatically.

Alternative: if you have a system Gradle installed, run once:

```
gradle wrapper --gradle-version 8.11.1
```

## Requirements

- JDK 21 (expected at `C:\Program Files\Java\jdk-21\` on this box)
- Android SDK (expected at `C:\Users\Andrea\AppData\Local\Android\Sdk\`)
- compileSdk / targetSdk 35, minSdk 26
- Kotlin 2.1.x, Jetpack Compose (Material 3), Gradle 8.11.1

Android Studio should pick these up via `local.properties` (auto-generated on
first open) and the JDK bundled with the IDE.

## Build from Windows cmd.exe

```
cd /d C:\Users\Andrea\Desktop\Botting\pane-management-mobile
gradlew.bat assembleDebug
```

Or from WSL:

```
cmd.exe /c "cd /d C:\Users\Andrea\Desktop\Botting\pane-management-mobile && gradlew.bat assembleDebug"
```

The debug APK lands at:

```
app/build/outputs/apk/debug/app-debug.apk
```

## Install on a connected device

```
/mnt/c/Users/Andrea/AppData/Local/Android/Sdk/platform-tools/adb.exe devices
/mnt/c/Users/Andrea/AppData/Local/Android/Sdk/platform-tools/adb.exe install app/build/outputs/apk/debug/app-debug.apk
/mnt/c/Users/Andrea/AppData/Local/Android/Sdk/platform-tools/adb.exe shell am start -n com.andreacanes.panemgmt/.MainActivity
```

Or, in one shot, via Gradle:

```
gradlew.bat installDebug
```

## Point the phone at a locally running companion

The companion defaults to `http://localhost:8833`. To hit it from a tethered
phone, set up a reverse port forward from the device to the host:

```
/mnt/c/Users/Andrea/AppData/Local/Android/Sdk/platform-tools/adb.exe reverse tcp:8833 tcp:8833
```

Then open the app, paste `http://localhost:8833` into the URL field and your
bearer token, and hit "Test connection".

## Logs

```
/mnt/c/Users/Andrea/AppData/Local/Android/Sdk/platform-tools/adb.exe logcat | grep panemgmt
```

## Project layout

```
app/
  src/main/
    AndroidManifest.xml
    java/com/andreacanes/panemgmt/
      MainActivity.kt           # ComponentActivity entry point
      PaneMgmtApp.kt            # NavHost (setup / grid / detail)
      data/
        AuthStore.kt            # DataStore: baseUrl + bearer token
        CompanionClient.kt      # Ktor HTTP/WS client
        models/Dtos.kt          # @Serializable DTOs
      ui/
        setup/SetupScreen.kt    # Backend URL + token entry
        grid/PaneGridScreen.kt  # Placeholder for pane grid
    res/
      values/{strings,themes}.xml
      xml/{backup_rules,data_extraction_rules}.xml
gradle/
  libs.versions.toml            # Version catalog
  wrapper/gradle-wrapper.properties
build.gradle.kts                # Root
settings.gradle.kts             # Project + repos
gradle.properties
gradlew / gradlew.bat
```

## Notes

- No secrets or URLs are hard-coded. Everything configurable lives in
  `AuthStore` (DataStore Preferences).
- Backup and device-transfer rules exclude the auth DataStore so the bearer
  token is never exfiltrated via Google backups.
- `android:usesCleartextTraffic="true"` is enabled so `http://localhost:8833`
  works. Tighten this once the companion grows TLS support.
