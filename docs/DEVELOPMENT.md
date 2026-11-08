# Development and operation

[Русская версия](DEVELOPMENT.RU.md) · [Project overview](../README.md)

## Build environment

The project uses Gradle 8.11.1, Android Gradle Plugin 8.7.3, Kotlin 2.0.21, and KSP 2.0.21-1.0.28. Java and Kotlin target JVM 17. JDK 17 is the recommended build environment; Android Studio can use it as the Gradle JDK.

The Android SDK needs Platform 35 and accepted SDK licenses. Set `ANDROID_HOME` to the SDK directory or set `sdk.dir` in a local `local.properties` file. The app targets API 35 and runs on Android 8.0 (API 26) or later.

Commands below run from the repository root. On Windows, `./gradlew` becomes `.\gradlew.bat`. The wrapper downloads its Gradle distribution on the first run; `--offline` works only after the required distribution and dependencies are cached.

```bash
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

| Variant | APK | Configuration |
|---|---|---|
| Debug | `app/build/outputs/apk/debug/app-debug.apk` | Debuggable, suitable for development |
| Release | `app/build/outputs/apk/release/app-release.apk` | Non-debuggable, R8 optimization and resource shrinking |

## APK signing and installation

Both variants currently use the Android debug signing configuration. Builds made with the same debug keystore can update each other. A newly generated keystore has a different signing key, including on a fresh CI runner; its APK cannot update a copy signed with another key.

For a compatible build, an attached phone with USB debugging enabled accepts:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

For manual installation, the APK is copied to the phone and opened from its file manager. Android requests permission to install from that source. Manual installation does not require USB debugging.

GitHub Actions stores debug and release APKs as workflow artifacts, along with test and lint reports. An artifact can be downloaded from a completed workflow run. These builds retain debug signing. A stable private signing key and increasing version codes are needed for a separately maintained distribution.

## First launch and permissions

1. Pair the printer in the phone's Bluetooth settings. NotiPrint selects from already paired devices.
2. Allow Bluetooth/Nearby devices access, then select the printer with **Выбрать принтер** (Choose printer).
3. Run **Тестовая печать** (Test print). It sends a sample Cyrillic receipt and a diagnostic coverage pattern.
4. Enable notification access with **Разрешить доступ** (Allow access), and grant the requested SMS, contacts, call-log, and notification permissions.
5. Allow background operation and autostart in the phone settings. **Настроить автозапуск** (Configure autostart) opens the relevant settings when the device provides them.

| Access | Used for |
|---|---|
| Bluetooth / Nearby devices | Connecting to the selected paired printer |
| Notification access | Missed-call and calendar notifications; SMS notification fallback |
| Receive SMS | Receiving carrier SMS directly, independent of the SMS app's notification layout |
| Contacts | Sender names, the unknown-number SMS filter, and selecting contacts for the blacklist |
| Call log | Resolving the name and number of the latest missed call |
| App notifications | The ongoing foreground-service notification |

The interface and printed event headings are currently Russian. Event kinds are enabled by default. The default night interval is 22:00–08:00; it can be changed or disabled in the app. Equal start and end times also disable the interval.

The local blacklist applies to SMS and missed calls. It accepts a phone number or alphabetic Sender ID, and can add all numbers from a selected contact. The unknown-number SMS option applies to numeric senders; alphabetic Sender IDs remain eligible unless blacklisted.

## Automated checks

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
```

The 21 JVM tests cover notification classification, printer drain timing, night intervals, retry backoff, and queue processing with controlled printer sessions. Queue scenarios include a failed send followed by recovery, interrupted work, concurrent workers, and continuation after a batch of 50.

Local verification used OpenJDK 21.0.12.1 and Gradle 8.11.1: all 21 JVM tests passed, lint reported zero errors and 19 warnings, and debug, optimized release, and instrumentation APKs built. CI uses JDK 17. The lint warnings mainly concern dependency updates and existing SDK compatibility checks.

Room instrumented tests require an Android emulator or phone:

```bash
adb devices
./gradlew :app:connectedDebugAndroidTest
```

The five tests use real Room and WorkManager with a controlled printer session. Three storage scenarios reopen a file-backed database to verify an accepted event, an interrupted row, and a failed transfer. Two scheduling scenarios exercise a 120-event queue across three workers and an offline printer followed by delayed retry and backoff reset. They require no Bluetooth printer. GitHub Actions runs them on API 35 separately from the unit/build job.

Reports are written under `app/build/reports/tests/`, `app/build/reports/androidTests/`, and `app/build/reports/lint-results-debug.html`. Unit XML results are under `app/build/test-results/`; instrumented results are under `app/build/outputs/androidTest-results/`.

## Device checks

For a daytime check, the night interval is disabled or set outside the current time. The history and waiting count in the app help track the queue; worker logs are available with:

```bash
adb logcat -s NotiPrintWorker NotiPrintListener
```

| Scenario | Expected behavior |
|---|---|
| Incoming SMS, missed call, calendar event | An accepted event appears in history and produces a readable receipt |
| Printer off, then on | The event stays waiting; a retry or Bluetooth reconnection drains the queue |
| App process interrupted during printing | A later run recovers an unfinished row; an already transmitted receipt may be repeated |
| Test print requested during queued work | Printer operations run one at a time; raster streams stay separate |
| Night interval crosses midnight | Events stay queued inside the interval and resume at its end |
| Blacklisted sender | SMS or a resolved missed call from that sender is not added to the print queue |
| Unknown-number SMS filter enabled | Unknown numeric senders are skipped; non-blacklisted alphabetic Sender IDs remain eligible |
| Device reboot | With permissions and background access retained, the service requests listener rebind and queue processing |

The diagnostic test goes directly to the printer under the shared lock. It is not saved to the event queue and can print during the night interval.

## Printer diagnostics

The transport expects a Bluetooth Classic SPP printer, 58 mm paper, a printable width of 384 dots, and ESC/POS `GS v 0` raster support. The MUNBYN IMP006 is the model referenced by the implementation's continuous-stream and paper-margin choices.

If a job stays waiting, the first checks are printer selection, Bluetooth access, power, range, and the night interval. A failed job stores its last error in the app's event history. Background access and notification-listener access are separate settings on the phone.

For faint or uneven coverage, the diagnostic page helps compare text and dense black areas. Printer battery charge and paper sensitivity affect the result. The IMP006 paper specification calls for 58 mm thermal paper with a thickness of 0.06–0.08 mm.

Regular notifications use 128-row raster bands; the manual diagnostic uses 256-row bands. Both are sent without deliberate pauses between bands. Transport completion and the printer's estimated drain time are described in the [architecture](ARCHITECTURE.md#bitmap-and-escpos-transport).
