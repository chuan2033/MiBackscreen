# MiBackscreen

小米背屏 LSPosed 模块，使用 Modern Xposed API 102。

## 作用域

| 包名                         | 用途                       |
| ---------------------------- | -------------------------- |
| `com.xiaomi.subscreencenter` | 长按拦截、快捷面板         |
| `com.android.thememanager`   | 壁纸数量限制、壁纸应用修复 |

最低 Android 版本为 API 35。

## 功能

| 功能                 | 默认值 | 作用进程 |
| -------------------- | ------ | -------- |
| 禁用背屏长按切换壁纸 | 开     | 背屏中心 |
| 去除 15 张壁纸上限   | 开     | 主题商店 |
| 修复背屏壁纸应用失败 | 关     | 主题商店 |
| 背屏上滑快捷面板     | 开     | 背屏中心 |

“修复背屏壁纸应用失败”同时负责壁纸状态同步：从主题商店应用壁纸后，设置页预览会更新；从背屏切换壁纸后，`theme_rear_widget` 与主题商店数据库会按当前背屏状态同步。

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

不要把内容起点改回 296px，也不要只给内容区绘制背景。

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

在 LSPosed 中启用模块，并勾选两个作用域包。安装或更新模块后重启背屏中心和主题商店。App 内强制停止作用域的功能需要 root。

## 代码结构

```text
app/src/main/java/hook/HyperBackscreen/
├─ app/
│  └─ ModuleApp.java
├─ bridge/
│  ├─ PrefsBridge.java
│  └─ PreferenceBridgeProvider.java
├─ common/
│  └─ Constants.java
├─ hook/
│  └─ ModuleMain.java
└─ ui/
   ├─ RearScreenApp.kt
   ├─ HomeScreen.kt
   ├─ SwipePanelHost.kt
   ├─ MiuixStyleSwitch.kt
   ├─ about/
   ├─ config/
   ├─ home/
   ├─ components/
   ├─ animation/
   ├─ liquid/
   ├─ theme/
   └─ util/
```

模块边界：

- `ModuleMain`：安装 Hook，识别背屏手势。
- `SwipePanelHost`：创建和销毁注入到背屏 Activity 的原生面板。
- `PrefsBridge`：模块 App、Hook 与远程偏好的统一读写入口。
- `PreferenceBridgeProvider`：处理背屏进程发起的受限写入。
- `RearScreenApp` / `HomeScreen`：模块 App 的 Compose UI。

## 调试

日志标签统一为 `MiBackscreen`：

```powershell
adb logcat -s MiBackscreen
```

关键日志：

| 日志                                             | 含义                             |
| ------------------------------------------------ | -------------------------------- |
| `Hooks installed for com.xiaomi.subscreencenter` | 背屏 Hook 已安装                 |
| `Theme store hooks installed`                    | 主题商店 Hook 已安装             |
| `Swipe panel gesture hook installed`             | 快捷面板手势 Hook 已安装         |
| `Long press hook target resolved: k2.s`          | 已匹配 HyperOS 4 长按目标        |
| `Swipe-up drag started`                          | 已识别上滑并开始拖动面板         |
| `Panel drag started: safeLeft=298px`             | 面板已挂载，内容起点正确         |
| `Panel opened after drag`                        | 面板展开完成                     |
| `Panel switch saved: ...`                        | 面板配置写入成功                 |
| `Promoted current rear wallpaper: ...`           | 重复应用的壁纸已提升到设置页首位 |
| `Synced rear selection to Settings: ...`         | 背屏选择已同步到 Secure Settings |
| `Synced Theme DB to rear selection: ...`         | 主题商店数据库已同步到当前背屏   |
| `Hook target missing: ...`                       | 宿主版本与当前 Hook 目标不匹配   |

在副屏启动 Launcher：

```powershell
adb shell am start --display 1 -n com.xiaomi.subscreencenter/.SubScreenLauncher
```

## 兼容性注意事项

- `k2.s`、`Z1.t`、`Z1.v`、`yp31`、`o5`、`ol` 等为宿主 R8 名称，系统应用升级后需要重新核对。
- 权限目录字段必须经过绝对路径校验；不要重新把非路径字符串 `qp5l=incallshow` 当作目录。
- 快捷面板依赖 `SubScreenLauncher`、`notification_panel` 和 `smart_assistant_panel`。
- `298px` 是当前目标机的固定兜底值，其他背屏优先使用 `DisplayCutout`。
- 系统手势排除区占底部 30%，新增原厂手势时需要重新评估冲突。
- `textureBlur` 不能放入同一个 `layerBackdrop` 采样子树，否则可能形成采样环并触发 native crash。
- 毛玻璃和液态玻璃会增加 GPU 开销。

## 依赖与许可证

项目许可证：[GPL-3.0](LICENSE)。

| 项目                                                         | 许可证     | 用途                           |
| ------------------------------------------------------------ | ---------- | ------------------------------ |
| [compose-miuix-ui/miuix](https://github.com/compose-miuix-ui/miuix) | Apache-2.0 | Compose UI、主题、开关视觉规格 |
| [AndroidX Activity Compose](https://developer.android.com/jetpack/androidx/releases/activity) | Apache-2.0 | Compose Activity               |
| [Modern Xposed API](https://github.com/libxposed/api)        | Apache-2.0 | LSPosed 模块 API               |
| [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) | Apache-2.0 | 液态玻璃效果上游               |
| [KernelSU](https://github.com/tiann/KernelSU)                | GPL-3.0    | 悬浮底栏实现参考               |

App 内“开源许可”页面只列外部项目，不重复列本项目自身。
