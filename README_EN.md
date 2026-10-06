# MiBackscreen

[中文](README.md) · [Project](https://github.com/chuan2033/MiBackscreen) · [Downloads](https://github.com/chuan2033/MiBackscreen/releases)

A Xiaomi rear-screen module using Modern Xposed. The `feat/18pro-max-rear-screen` branch adapts newer Rear Screen Center, Theme Manager and Personal Assistant app-card hosts. Neither the branch name nor a device identity sent in requests establishes hardware compatibility.

## Features and defaults

| Feature | Default | Behavior |
| --- | --- | --- |
| Rear-screen battery ring | Off | Shows battery level around the Xiaomi 17 Pro Max camera contour in 1% steps; green while charging, red below 20% when not charging, otherwise white |
| 18 Pro features | On | Controls AI rear-screen entries, 18 Pro device and download request parameters, rear-screen app-card unlocking, Personal Assistant store adaptation and AI app-card index synchronization |
| Disable long-press wallpaper editing | On | Prevents entering the rear-screen editor |
| Remove wallpaper limit | On | Removes Theme Manager's 15-wallpaper limit |
| Rear-screen app cards / remove card limit | On / On | Adds MiBackscreen to the stock swipe-up list; tap it to open the quick panel |
| Fix wallpaper application | Off | Repairs resource access and application state; confirmed selection and cancelled edits remain distinct |
| Pickup-code enhancement | On | Groups XiaoAi pickup codes and lets users select codes shown on island cards; new batches stay separate |
| Disable rear-screen protection prompt | Off | Blocks the prompt asking users to turn off the front screen first |
| Disable double-tap wake per app | Off, empty list | Open the app picker from Features; disabling retains the list and matching uses current front-screen activities |
| Theme Manager module entry | On | Adds an entry to the rear-screen settings page |

Appearance settings include system/light/dark themes, dynamic colors, standard or floating navigation, and liquid glass. Floating navigation and liquid glass default off; bottom-bar blur and launch-time update checks default on.

Functions includes an "18 Pro features" switch. Changing it requests a force-stop of Rear Screen Center, Theme Manager and Personal Assistant; reopening them refreshes their capabilities. When off, hosts use their original entry and capability checks. Downloaded and applied content and add-on resources remain. Pickup codes, long press and count limits still follow their own switches. Disable the add-on in KSU/Magisk and reboot to remove its resource mounts.

The battery ring is calibrated for the 976 x 596 popsicle rear display. It starts at the bottom center and passes the right, top and left centers before closing at 100%. Colors come from SystemUI battery resources when available. The battery progress line is drawn over the existing wallpaper. Changing the switch refreshes Rear Screen Center. The drawing layer is released when leaving the rear launcher, and transitions stop while its window is hidden.

The Functions tab shows an enabled-function count (1–9) by default and hides the badge when all functions are off. Each function switch counts once, including the battery ring, 18 Pro features and the double-tap wake master switch in the app picker; selected apps do not add to the count. Hide it through Settings → Appearance → Show enabled function count. All three navigation styles support the badge.

Open the quick panel through the stock MiBackscreen card; swipe up from its bottom to close it. Its three switches control long press, wallpaper limits and app-card limits; failed writes roll back. Offline changes in the module app remain pending until the LSPosed service reconnects. Pending does not mean applied in the host.

## Requirements and installation

- Minimum Android API 36 and an LSPosed / compatible framework supporting Modern Xposed. Module metadata declares minimum API 101 and target API 102; the code depends on API 102.
- Historical device tests used Xiaomi 17 Pro Max (popsicle / 2509FPN0BC), Android 17 / API 37, HyperOS 4, and a 976 × 596 rear display. These results do not establish full compatibility across devices or firmware.
- Hooks depend on host classes and method signatures; recheck after system or host updates.
- Theme Manager includes AI rear-screen resource and face-enrollment-count compatibility paths. Failed face queries return unknown. The complete pet-wallpaper application flow still needs device validation.

Install the APK, enable the module in your framework, select all five scopes below, then reboot to load all hooks:

| Scope | Purpose |
| --- | --- |
| `system` | Protection prompt and per-app double-tap wake interception |
| `com.xiaomi.subscreencenter` | Long press, app cards, quick panel and confirmed wallpaper-selection sync |
| `com.android.thememanager` | Wallpaper limits/application, settings entry, AI rear screen and face-count compatibility |
| `com.miui.voiceassist` | Pickup-code presentation, notification clicks and refresh |
| `com.miui.personalassistant` | Rear-screen app-card store request adaptation |

“Activated” means the module service is connected; it does not confirm every hook works. Settings take effect when hooks next read them. Some wallpaper/app-card switches also request stopping affected hosts, requiring root and reopening the app. APK updates require reloading affected hosts; system-hook updates require a device reboot. “Restart scopes” lists System, XiaoAi, Rear Screen, Theme Store and Personal Assistant. Selecting “System” and pressing “Confirm” runs a root device reboot. Including app scopes still sends only one reboot command. Selecting only apps stops those apps; automatic refreshes from ordinary setting switches never reboot the phone.

A warning about `vendor.display.builtin_presentation=0` indicates that rear-display hiding / anti-screen-sharing modules may interfere with rear-screen screenshots or custom wallpapers.

## Feedback

Immediately after reproduction, open Home → Logs and generate a ZIP before reapplying wallpapers or restarting hosts. Closing the progress card does not cancel the task. Share manually when ready; failures can be retried and nothing is sent automatically.

Schema 7 includes device/host versions, settings, hook status, pickup selections, filtered logs, system diagnostics, resource paths and metadata, editConfig text, and Theme Manager's `rearScreen.db` with WAL/SHM copies. It also includes both AI app-card indexes with an ID comparison, local/remote/pending preference snapshots, appearance settings, and persistent events for panel lifecycle, preference synchronization, pickup saves and refresh requests. The AI database contributes schema information only. Wallpaper image/video bytes are not copied. Archives may contain pickup codes, app selections and other private information; inspect them before sharing.

Complete collection requires root and a `su -M` implementation. Reports record permission failures, timeouts and missing files; a generated ZIP does not guarantee complete collection. Submit reproduction steps, expected/actual behavior, versions and relevant screenshots through [Issues](https://github.com/chuan2033/MiBackscreen/issues), attaching a reviewed archive when needed.

## Build

Use JDK 21, Android SDK 37 and Build Tools 37.0.0. The wrapper pins Gradle 9.6.0; [app/build.gradle](app/build.gradle) is the version authority. Configure `JAVA_HOME` and `sdk.dir` in ignored `local.properties`, then run:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --offline --console=plain
.\gradlew.bat :app:assembleRelease --offline --console=plain
```

Without cached dependencies or the wrapper distribution, remove `--offline` and prepare dependencies online. Debug output: `app/build/outputs/apk/debug/app-debug.apk`.

Release enables R8 and resource shrinking. Signed output is `app/build/outputs/apk/release/app-release.apk`; without signing it is `app-release-unsigned.apk`. Signing reads `RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_PASSWORD` and optional `RELEASE_KEY_ALIAS` (default: `hyperbackscreen`) from `local.properties` or matching environment variables. Relative key paths resolve from `app/`; absent an explicit path, the build checks `app/release-key.jks`. An explicitly configured key with incomplete settings fails the build. Available signing credentials also sign Debug without enabling minification; otherwise Debug uses its default certificate. Updating an installed app requires the same signing certificate.

After a successful build, validate hook behavior on the target device and host versions.

## License and acknowledgements

[GPL-3.0](LICENSE). This module changes system and host behavior; compatibility can vary by firmware.

The table below lists major sources. The app's Open Source Licenses page groups runtime dependencies and adapted sources, including Kotlin / kotlinx, JetBrains Compose, Material Color Utilities, Poko, annotation libraries, Guava and libxposed service interfaces. The catalog is maintained in [LicensePage.kt](app/src/main/java/hook/HyperBackscreen/ui/about/LicensePage.kt).

| Project | License | Use |
| --- | --- | --- |
| [Miuix](https://github.com/compose-miuix-ui/miuix) | Apache-2.0 | Compose UI, navigation and blur |
| [AndroidX](https://developer.android.com/jetpack/androidx) | Apache-2.0 | Activity, Compose and lifecycle |
| [Modern Xposed](https://central.sonatype.com/artifact/io.github.libxposed/api/102.0.0) | Apache-2.0 | Hook API |
| [AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) | Apache-2.0 | Source for local glass effects |
| [KernelSU](https://github.com/tiann/KernelSU) | GPL-3.0 | Floating navigation reference |
