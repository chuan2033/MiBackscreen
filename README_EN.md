# MiBackscreen

[中文](README.md) · [项目主页](https://github.com/chuan2033/MiBackscreen)

A Xiaomi rear-screen (backscreen) LSPosed module built on the Modern Xposed API 102. It completes wallpaper management, backscreens protection, and a quick settings panel for HyperOS rear screens.

Current local version: `1.1.4 (8)`.

---

## Overview

- Remove the 15-wallpaper limit on the rear screen, and fix theme-store wallpaper application failures with state synchronization.
- Block the rear-screen protection prompt, and support per-app disabling of double-tap-to-wake.
- Inject an upward-swipe quick panel into the rear-screen center.
- Recognize multiple delivery pickup codes in XiaoAi memory-island cards, group them by station, and choose which codes remain visible on the island card.
- The module app provides appearance settings (color mode / floating bottom bar / liquid glass) and module settings (entry point / hide icon).

## Features

### Rear-screen center (com.xiaomi.subscreencenter)

- Disable long-press wallpaper switching on the rear screen.
- Upward-swipe quick settings panel.

### System process (system)

- Disable the rear-screen protection prompt ("press the power button to turn off the front screen before using the rear screen").
- Per-app disabling of double-tap-to-wake, intercepted by the current front-screen foreground app package name.

### Theme store (com.android.thememanager)

- Remove the 15-wallpaper limit.
- Fix rear-screen wallpaper application failures and handle wallpaper state synchronization.
- Quick entry on the Mi Share rear-screen page.

### Module app

- Appearance: color mode (follow system / light / dark), floating bottom bar, and the liquid glass toggle shown only after the floating bottom bar is enabled.
- Module settings: module entry (disabled / Mi Share rear screen), hide desktop icon, rear-screen swipe-up panel.
- The About page includes a donation entry (AfDian link and QR code).

- The home "Feature Settings" aggregates all rear-screen features, with a restart entry at the top right.
- After enabling "disable double-tap-to-wake per app", enter the "New app" sub-page and check apps to disable by package name.
- Bottom navigation keeps only "Home" and "About"; the gear on the About page opens the "Settings" sub-page (Appearance / Module settings).
- "Fix rear-screen wallpaper application failure" also syncs state: after applying a wallpaper, the settings-page preview updates; after a confirmed switch in the rear-screen editor, `theme_rear_widget` and the theme-store database sync to the current rear-screen state (init / refresh / cancel-edit do not trigger; already-consistent state is not rewritten).

## Module scopes

| Package                       | Purpose                                   |
| ----------------------------- | ---------------------------------------- |
| `system`                      | Rear-screen protection prompt, double-tap-to-wake interception |
| `com.xiaomi.subscreencenter`  | Long-press interception, quick panel     |
| `com.android.thememanager`    | Wallpaper limit, wallpaper fix, settings entry |
| `com.miui.voiceassist`        | Pickup-code recognition, island-card click and refresh |

Minimum Android version is API 36.

## Requirements & compatibility

- Android API 36 or above.
- LSPosed / compatible Xposed environment, Modern Xposed API 102.
- Target rear screen `976 × 596px` (HyperOS 4).
- The feedback package's database collection depends on the Root implementation's `su -M`; both KernelSU and Magisk provide this flag.
- If `vendor.display.builtin_presentation=0` is detected, the module app warns that hidden rear-display / anti-screen-sharing modules may cause rear-screen screenshot failures or black custom wallpapers.
- `k2.s`, `Z1.t`, `Z1.v`, `yp31`, `o5`, `ol`, etc. are host R8 names and must be re-verified after system app updates.
- The system-process hooks depend on `DualScreenCoverManager`, `PowerManagerServiceImpl`, and the current foreground task field, and must be re-confirmed after system updates.
- The Mi Share rear-screen quick entry depends on the theme store's `com.rearScreen.RearScreenSettingActivity`, `EntryConfig`, and the `user_guide` / `serve_assistant` controller keys.
- Permission directory fields must pass absolute-path validation; do not treat the non-path string `qp5l=incallshow` as a directory again.
- The quick panel depends on `SubScreenLauncher`, `notification_panel`, and `smart_assistant_panel`.
- `298px` is the fixed fallback for the current target device; other rear screens prefer `DisplayCutout`.
- The system gesture exclusion zone occupies the bottom 30%; re-evaluate conflicts when adding new stock gestures.
- `textureBlur` must not be placed in the same `layerBackdrop` sampling subtree, otherwise a sampling loop may form and trigger a native crash.
- Frosted glass and liquid glass increase GPU overhead.

## Installation

1. Download the latest APK from [Releases](https://github.com/chuan2033/MiBackscreen/releases) and install it.
2. Enable `MiBackscreen` in LSPosed.
3. Check the scopes: `system`, `com.xiaomi.subscreencenter`, `com.android.thememanager`, `com.miui.voiceassist`.
4. Reboot the phone; when only debugging the rear-screen center or theme store, you can also restart the corresponding scope process separately.
5. Open the module app and confirm the module is activated.

Force-stopping a scope process from within the app requires root.

## Usage

- Rear-screen swipe-up gesture: start with one finger below 70% of the screen height, swipe up more than `32dp` to open the panel; swipe down or tap the close button to exit, and a back-key event reaching `SubScreenLauncher` also closes it.
- When disabling double-tap-to-wake per app, the system-recorded foreground package name and the candidate Activity package name from the running tasks are both read, covering the scenario where a game briefly jumps to an SDK/login page after launch and the foreground package name changes.
- Generate the feedback log immediately after reproducing the issue; do not re-apply wallpapers or restart the rear-screen center / theme store beforehand.

### Pickup codes

- After XiaoAi remembers a delivery notification containing multiple pickup codes, tap the pickup island card to open the pickup-code page.
- Codes are grouped by station; each station can independently choose which codes appear on the island card.
- A new recognition batch does not intentionally reuse the previous batch's page data, and confirming pickup closes the current page.

## Quick panel

### Gesture

- Start with one finger below 70% of the screen height.
- Swipe up more than `32dp` to open the panel.
- Swipe down or tap the close button at the top right to exit.
- A back-key event reaching `SubScreenLauncher` also closes the panel.

The bottom 30% is set as a system gesture exclusion zone. While the panel is shown, touch events are sent directly to the window content via `Window.superDispatchTouchEvent()`, bypassing Xiaomi Launcher's gesture handling. When the native `notification_panel` or `smart_assistant_panel` is visible, the quick panel is not triggered.

### Layout constraints

The target rear screen is `976 × 596px`, with the camera area on the left `x=0..296px` spanning the full height.

| Item                 | Constraint                                         |
| -------------------- | -------------------------------------------------- |
| Panel background     | Covers the entire rear screen                      |
| Text, switches, etc. | Start from `x=298px`                               |
| System cutout available | `safeInsetLeft + 2px`, no less than 298px on target |
| System cutout unavailable | Fall back to 298px only on sub-screens `900..1050 × 500..700px` |
| Title text size      | `19sp`                                             |
| Feature title size   | `16sp`                                             |
| Status text size     | `13sp`                                             |

### Config write

Hooks read LSPosed `RemotePreferences`, preference group `module_config`.

The quick panel runs in `com.xiaomi.subscreencenter`; the write path is:

```text
SwipePanelHost
  -> ContentResolver.call()
  -> PreferenceBridgeProvider
  -> local cache
  -> XposedService RemotePreferences
```

`PreferenceBridgeProvider` is protected by `android.permission.MANAGE_ACTIVITY_TASKS` and only allows writing the following keys:

- `disable_long_press_edit`
- `remove_wallpaper_limit`

Pending values are kept when the remote service is temporarily unavailable and retried after recovery. On a failed write request, the panel switch rolls back.

## Build from source

Requirements:

- JDK 21
- Android SDK 37
- Android Build Tools 37.0.0
- Gradle 9.6.0

Debug:

```powershell
.\gradlew.bat :app:assembleDebug --offline --console=plain
```

Drop `--offline` on the first build without a local cache. APK output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Release:

```powershell
.\gradlew.bat :app:assembleRelease --offline --console=plain
```

Artifact:

```text
app/build/outputs/apk/release/app-release.apk
```

## Debugging

Unified log tag `MiBackscreen`:

```powershell
adb logcat -s MiBackscreen
```

Key logs:

| Log                                              | Meaning                                  |
| ------------------------------------------------ | ---------------------------------------- |
| `System hooks installed`                         | System-process hook installed            |
| `Hooks installed for com.xiaomi.subscreencenter` | Rear-screen hook installed               |
| `Theme store hooks installed`                    | Theme-store hook installed               |
| `MiBackscreen theme settings entry inserted ...` | Mi Share rear-screen quick entry inserted |
| `Rear screen cover skipped`                      | Rear-screen protection prompt intercepted |
| `Rear double-tap wake skipped ...`               | Rear double-tap wake intercepted by app list |
| `Swipe panel gesture hook installed`             | Quick-panel gesture hook installed       |
| `Long press hook target resolved: k2.s`          | Matched HyperOS 4 long-press target      |
| `Swipe-up drag started`                           | Swipe-up detected, panel drag started    |
| `Panel drag started: safeLeft=298px`             | Panel mounted, content offset correct    |
| `Panel opened after drag`                        | Panel expanded                           |
| `Panel switch saved: ...`                         | Panel config written                     |
| `Promoted current rear wallpaper: ...`           | Repeated wallpaper promoted to top of settings |
| `Rear selection commit requested: ...`           | User confirmed wallpaper switch          |
| `Synced rear selection to Settings: ...`         | Rear selection synced to Secure Settings |
| `Rear selection already synchronized: ...`       | State already consistent, no rewrite     |
| `Synced Theme DB to rear selection: ...`         | Theme-store DB synced to current rear screen |
| `Hook target missing: ...`                        | Host version mismatch with current hook target |

Launch the Launcher on the secondary screen:

```powershell
adb shell am start --display 1 -n com.xiaomi.subscreencenter/.SubScreenLauncher
```

## Feedback

Submit feedback in [Issues](https://github.com/chuan2033/MiBackscreen/issues) and include:

1. LSPosed module log (Settings → Log → Verbose log).
2. Device model, system version, and module version.
3. Steps to reproduce.
4. Expected vs. actual behavior.
5. Relevant screenshots.

The module app's "About → Feedback → Log" generates a ZIP feedback package and invokes the system share sheet (timing per Usage above).

The feedback package uses `feedback_schema=2` and includes:

- Versions of the device, system, module, and scope apps.
- Module switch states and hook installation states.
- `theme_rear_widget`, `user_pref.json`, `widget.json`, and `runtime.json`.
- Rear-screen resource manifest, permission file manifest, and the current `app.log`.
- Filtered system logcat, module local logs, and LSPosed module logs.
- Theme store's `rearScreen.db`, `rearScreen.db-wal`, and `rearScreen.db-shm`.

Database collection enters the global mount namespace via `su -M`; otherwise Android app data isolation may keep the Root process from seeing the theme store's private directory. Only the most recent 20000 lines of logcat are read to avoid export timeouts from excessive logs. The feedback package contains wallpaper resource paths and system logs — be mindful of privacy before sharing publicly.

## Code structure

```text
app/src/main/java/hook/HyperBackscreen/
├─ app/
│  └─ ModuleApp.java
├─ bridge/
│  ├─ PrefsBridge.java
│  └─ PreferenceBridgeProvider.java
├─ common/
│  ├─ AppPickerFilter.java
│  ├─ Constants.java
│  ├─ PackageListCodec.java
│  └─ RearScreenWakeMatcher.java
├─ hook/
│  ├─ ModuleMain.java
│  └─ SettingsEntryPlacement.java
└─ ui/
   ├─ RearScreenApp.kt
   ├─ HomeScreen.kt
   ├─ HomeNavigationPolicy.java
   ├─ SwipePanelHost.kt
   ├─ MiuixStyleSwitch.kt
   ├─ about/
   │  └─ FeedbackLogExporter.kt
   ├─ config/
   │  ├─ AppPickerPage.kt
   │  └─ ConfigPage.kt
   ├─ home/
   ├─ components/
   ├─ animation/
   ├─ liquid/
   ├─ theme/
   └─ util/
```

Module boundaries:

- `ModuleMain`: installs hooks, identifies rear-screen gestures, system rear-screen protection, and double-tap-to-wake interception.
- `SwipePanelHost`: creates and destroys the native panel injected into the rear-screen Activity.
- `PrefsBridge`: unified read/write entry for the module app, hooks, and remote preferences.
- `PackageListCodec` / `RearScreenWakeMatcher`: handle list parsing and matching for per-app double-tap-to-wake disabling.
- `PreferenceBridgeProvider`: handles restricted writes initiated from the rear-screen process.
- `FeedbackLogExporter`: generates the feedback ZIP containing host state, logs, and the theme-store database.
- `RearScreenApp` / `HomeScreen` / `HomeNavigationPolicy` / `AppPickerPage`: the module app's Compose UI, home navigation policy, and app picker page.

UI conventions:

- Secondary-page transitions (Settings / License / App picker) all go through `HomeScreen.kt`'s `AnimatedContent.transitionSpec`: `tween(300, FastOutSlowInEasing)` + enter `fadeIn`. Deterministic and overshoot-free, matching the miuix push-page feel. Do not switch the slideIn/slideOut `animationSpec` back to the Compose default spring, or the transition will float / overshoot and look broken.
- The top-bar blur uses `BlurredBar` (miuix `textureBlur`, 25f / surface 0.8f), mounted outside the `layerBackdrop` subtree. SoundMan's progressive blur needs the Kyant `com.kyant.backdrop` library + a custom AGSL shader; it is not adopted yet, keeping pure miuix.

## Disclaimer

- This module modifies system rear-screen, system UI, and theme-store behavior; assess the risk yourself.
- Compatibility may differ across system versions, firmware versions, and Xposed environments.
- Some hook points may need re-adaptation after system framework, system UI, or theme-store updates.
- The author is not responsible for malfunctions or device risks caused by using this module.

## Tech stack & acknowledgements

Project license: [GPL-3.0](LICENSE).

| Project                                                       | License    | Purpose                          |
| ------------------------------------------------------------- | ---------- | -------------------------------- |
| [compose-miuix-ui/miuix](https://github.com/compose-miuix-ui/miuix) | Apache-2.0 | Compose UI, theme, switch visuals |
| [AndroidX Activity Compose](https://developer.android.com/jetpack/androidx/releases/activity) | Apache-2.0 | Compose Activity                 |
| [Modern Xposed API](https://github.com/libxposed/api)         | Apache-2.0 | LSPosed module API               |
| [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) | Apache-2.0 | Liquid glass upstream            |
| [KernelSU](https://github.com/tiann/KernelSU)                 | GPL-3.0    | Floating bottom bar reference    |

Acknowledgements: thanks to the open-source projects miuix, Modern Xposed API, AndroidLiquidGlass, and KernelSU.

## License

See [LICENSE](LICENSE).
