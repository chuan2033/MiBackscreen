# MiBackscreen

小米背屏 LSPosed 模块，使用 Modern Xposed API 102。

当前本地版本：`1.1.3 (5)`。

## 作用域

| 包名                         | 用途                                   |
| ---------------------------- | -------------------------------------- |
| `system`                     | 背屏保护提示、背屏双击唤醒拦截         |
| `com.xiaomi.subscreencenter` | 长按拦截、快捷面板                     |
| `com.android.thememanager`   | 壁纸数量限制、壁纸应用修复、设置页入口 |

最低 Android 版本为 API 35。

## 功能

| 功能                       | 默认值 | 作用进程 |
| -------------------------- | ------ | -------- |
| 禁用背屏长按切换壁纸       | 开     | 背屏中心 |
| 去除 15 张壁纸上限         | 开     | 主题商店 |
| 修复背屏壁纸应用失败       | 关     | 主题商店 |
| 禁用背屏保护提示           | 关     | 系统进程 |
| 按应用禁用背屏双击唤醒     | 关     | 系统进程 |
| 背屏上滑快捷面板           | 开     | 背屏中心 |
| 妙享背屏页快捷入口         | 开     | 主题商店 |
| 隐藏桌面图标、悬浮底栏设置 | 关     | 模块 App |

“修复背屏壁纸应用失败”同时负责壁纸状态同步：从主题商店应用壁纸后，设置页预览会更新；用户在背屏编辑界面确认切换壁纸后，`theme_rear_widget` 与主题商店数据库会按当前背屏状态同步。背屏列表初始化、资源自动刷新和取消编辑不会触发同步；状态已经一致时也不会重复更新 `updateTime`。

主页“功能设置”中包含所有背屏相关功能，右上角保留重启应用入口。“按应用禁用背屏双击唤醒”开启后，可进入“新添加应用”二级页，从本机应用列表中搜索并勾选需要禁用背屏双击唤醒的应用；名单按包名保存。底部导航只保留“主页”和“关于”。关于页右上角的齿轮进入“设置”二级页，其中的“模块设置”用于模块自身行为，包括隐藏桌面图标、背屏上滑面板、悬浮底栏，以及悬浮底栏开启后才显示的液态玻璃开关。

## 背屏保护与双击唤醒

系统进程 Hook 负责两类背屏保护行为：

- `DualScreenCoverManager#showCoverView`：用于拦截“请按电源键熄灭正屏后使用背屏”提示。
- `PowerManagerServiceImpl#isScreenSkippedWakeup` / `DualScreenCoverManager#isScreenSkippedWakeup`：用于按当前正屏前台应用包名拦截背屏双击唤醒。

按应用禁用双击唤醒时，会同时读取系统记录的前台包名和运行任务中的候选 Activity 包名，以覆盖游戏启动后短暂跳转到 SDK/登录页导致前台包名变化的场景。

## 快捷面板

### 手势

- 单指从屏幕高度 70% 以下起手。
- 向上位移超过 `32dp` 时打开面板。
- 向下拖动或点击右上角关闭按钮退出。
- 返回键事件到达 `SubScreenLauncher` 时也会关闭面板。

底部 30% 设置为系统手势排除区。面板显示期间，触摸事件通过 `Window.superDispatchTouchEvent()` 直接发送给窗口内容，不再进入小米 Launcher 的手势处理。检测到原生 `notification_panel` 或 `smart_assistant_panel` 可见时，不触发快捷面板。

### 布局约束

目标背屏为 `976 × 596px`，摄像头区域为左侧 `x=0..296px`，且贯穿全高。

| 项目                 | 约束                                               |
| -------------------- | -------------------------------------------------- |
| 面板背景             | 覆盖整个背屏                                       |
| 文字、开关等内容     | 从 `x=298px` 开始                                  |
| 系统挖孔数据可用时   | `safeInsetLeft + 2px`，目标机不小于 298px          |
| 系统挖孔数据不可用时 | 仅在 `900..1050 × 500..700px` 的副屏上回退到 298px |
| 标题字号             | `19sp`                                             |
| 功能标题字号         | `16sp`                                             |
| 状态字号             | `13sp`                                             |


### 配置写入

Hook 读取 LSPosed `RemotePreferences`，偏好组为 `module_config`。

快捷面板运行在 `com.xiaomi.subscreencenter` 中，写入路径如下：

```text
SwipePanelHost
  -> ContentResolver.call()
  -> PreferenceBridgeProvider
  -> 本地暂存
  -> XposedService RemotePreferences
```

`PreferenceBridgeProvider` 受 `android.permission.MANAGE_ACTIVITY_TASKS` 保护，只允许写入以下键：

- `disable_long_press_edit`
- `remove_wallpaper_limit`

远程服务暂时不可用时保留待写值，服务恢复后重试。写入请求失败时，面板开关回滚。

## 反馈日志

在模块 App 的“关于 → 反馈 → 日志”中可以生成 ZIP 反馈包并调起系统分享面板。遇到问题时，应在复现后立即生成，生成前不要重新应用壁纸或重启背屏中心、主题商店。

反馈包使用 `feedback_schema=2`，包括：

- 设备、系统、模块和作用域 App 的版本。
- 模块开关状态与 Hook 安装状态。
- `theme_rear_widget`、`user_pref.json`、`widget.json` 和 `runtime.json`。
- 背屏资源文件清单、权限文件清单与当前 `app.log`。
- 过滤后的系统 logcat、模块本地日志和 LSPosed 模块日志。
- 主题商店的 `rearScreen.db`、`rearScreen.db-wal` 和 `rearScreen.db-shm`。

数据库采集通过 `su -M` 进入全局挂载命名空间，否则 Android 应用数据隔离可能让 Root 进程仍看不到主题商店私有目录。logcat 只读取最近 20000 行，避免日志量过大导致导出超时。反馈包包含壁纸资源路径和系统日志，公开分享前请注意隐私。

## 构建

要求：

- JDK 21
- Android SDK 37
- Android Build Tools 37.0.0
- Gradle 9.6.0

Debug：

```powershell
.\gradlew.bat :app:assembleDebug --offline --console=plain
```

首次构建没有本地缓存时去掉 `--offline`。APK 输出到：

```text
app/build/outputs/apk/debug/app-debug.apk
```

Release：

```powershell
.\gradlew.bat :app:assembleRelease --offline --console=plain
```

产物：

```text
app/build/outputs/apk/release/app-release.apk
```

## 安装

```powershell
adb install -r .\app\build\outputs\apk\release\app-release.apk
```

在 LSPosed 中启用模块，并勾选系统框架、背屏中心和主题商店三个作用域。安装或更新模块后，建议重启手机；只调试背屏中心或主题商店功能时，也可以分别重启对应作用域进程。App 内强制停止作用域的功能需要 root。

## 代码结构

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

模块边界：

- `ModuleMain`：安装 Hook，识别背屏手势、系统背屏保护和双击唤醒拦截。
- `SwipePanelHost`：创建和销毁注入到背屏 Activity 的原生面板。
- `PrefsBridge`：模块 App、Hook 与远程偏好的统一读写入口。
- `PackageListCodec` / `RearScreenWakeMatcher`：处理按应用禁用背屏双击唤醒的名单解析和匹配。
- `PreferenceBridgeProvider`：处理背屏进程发起的受限写入。
- `FeedbackLogExporter`：生成包含宿主状态、日志和主题商店数据库的反馈 ZIP。
- `RearScreenApp` / `HomeScreen` / `HomeNavigationPolicy` / `AppPickerPage`：模块 App 的 Compose UI、主页导航策略和应用选择页。

## 调试

日志标签统一为 `MiBackscreen`：

```powershell
adb logcat -s MiBackscreen
```

关键日志：

| 日志                                             | 含义                             |
| ------------------------------------------------ | -------------------------------- |
| `System hooks installed`                         | 系统进程 Hook 已安装             |
| `Hooks installed for com.xiaomi.subscreencenter` | 背屏 Hook 已安装                 |
| `Theme store hooks installed`                    | 主题商店 Hook 已安装             |
| `MiBackscreen theme settings entry inserted ...` | 妙享背屏页快捷入口已插入         |
| `Rear screen cover skipped`                      | 背屏保护提示已被拦截             |
| `Rear double-tap wake skipped ...`               | 背屏双击唤醒已按应用名单拦截     |
| `Swipe panel gesture hook installed`             | 快捷面板手势 Hook 已安装         |
| `Long press hook target resolved: k2.s`          | 已匹配 HyperOS 4 长按目标        |
| `Swipe-up drag started`                          | 已识别上滑并开始拖动面板         |
| `Panel drag started: safeLeft=298px`             | 面板已挂载，内容起点正确         |
| `Panel opened after drag`                        | 面板展开完成                     |
| `Panel switch saved: ...`                        | 面板配置写入成功                 |
| `Promoted current rear wallpaper: ...`           | 重复应用的壁纸已提升到设置页首位 |
| `Rear selection commit requested: ...`           | 已识别用户确认切换壁纸           |
| `Synced rear selection to Settings: ...`         | 背屏选择已同步到 Secure Settings |
| `Rear selection already synchronized: ...`       | 状态已经一致，未重复写入         |
| `Synced Theme DB to rear selection: ...`         | 主题商店数据库已同步到当前背屏   |
| `Hook target missing: ...`                       | 宿主版本与当前 Hook 目标不匹配   |

在副屏启动 Launcher：

```powershell
adb shell am start --display 1 -n com.xiaomi.subscreencenter/.SubScreenLauncher
```

## 兼容性注意事项

- `k2.s`、`Z1.t`、`Z1.v`、`yp31`、`o5`、`ol` 等为宿主 R8 名称，系统应用升级后需要重新核对。
- 系统进程 Hook 依赖 `DualScreenCoverManager`、`PowerManagerServiceImpl` 以及当前前台任务字段，系统升级后需要重新确认。
- 妙享背屏页快捷入口依赖主题商店 `com.rearScreen.RearScreenSettingActivity`、`EntryConfig` 和 `user_guide` / `serve_assistant` 这些 controller key。
- 权限目录字段必须经过绝对路径校验；不要重新把非路径字符串 `qp5l=incallshow` 当作目录。
- 快捷面板依赖 `SubScreenLauncher`、`notification_panel` 和 `smart_assistant_panel`。
- `298px` 是当前目标机的固定兜底值，其他背屏优先使用 `DisplayCutout`。
- 系统手势排除区占底部 30%，新增原厂手势时需要重新评估冲突。
- `textureBlur` 不能放入同一个 `layerBackdrop` 采样子树，否则可能形成采样环并触发 native crash。
- 毛玻璃和液态玻璃会增加 GPU 开销。
- 反馈包的数据库采集依赖 Root 实现支持 `su -M`；KernelSU 和 Magisk 均提供该参数。

## 依赖与许可证

项目许可证：[GPL-3.0](LICENSE)。

| 项目                                                         | 许可证     | 用途                           |
| ------------------------------------------------------------ | ---------- | ------------------------------ |
| [compose-miuix-ui/miuix](https://github.com/compose-miuix-ui/miuix) | Apache-2.0 | Compose UI、主题、开关视觉规格 |
| [AndroidX Activity Compose](https://developer.android.com/jetpack/androidx/releases/activity) | Apache-2.0 | Compose Activity               |
| [Modern Xposed API](https://github.com/libxposed/api)        | Apache-2.0 | LSPosed 模块 API               |
| [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) | Apache-2.0 | 液态玻璃效果上游               |
| [KernelSU](https://github.com/tiann/KernelSU)                | GPL-3.0    | 悬浮底栏实现参考               |

