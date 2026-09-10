<h1 align="center">MiBackscreen</h1>

<p align="center">
  用于小米 17 Pro 系列的背屏Xposed模块。<br>
 为背屏补全壁纸管理、背屏保护与快捷面板等功能。
</p>

<p align="center">
  <a href="./README_EN.md">English</a> · <a href="https://github.com/chuan2033/MiBackscreen">项目主页</a>
</p>

<p align="center">
  <a href="https://github.com/chuan2033/MiBackscreen/releases"><img alt="GitHub Release" src="https://img.shields.io/github/v/release/chuan2033/MiBackscreen?display_name=release"></a>
 <a href="https://github.com/chuan2033/MiBackscreen/stargazers"><img alt="GitHub Repo stars" src="https://img.shields.io/github/stars/chuan2033/MiBackscreen?style=flat"></a>
 <a href="https://github.com/chuan2033/MiBackscreen/issues"><img alt="GitHub Issues" src="https://img.shields.io/github/issues/chuan2033/MiBackscreen"></a>
  <a href="https://developer.android.com/"><img src="https://img.shields.io/badge/Android-16%2B-3DDC84?logo=android&logoColor=white" alt="Android"></a>
 <a href="https://github.com/LSPosed/LSPosed"><img src="https://img.shields.io/badge/Framework-LSPosed%20%2F%20Xposed-5C6BC0" alt="Framework"></a>
</p>

## 功能概览

- 解除背屏壁纸 15 张上限，并修复主题商店壁纸应用失败与状态同步。
- 拦截背屏保护提示，支持按应用禁用背屏双击唤醒。
- 在背屏中心注入上滑快捷面板。
- 模块 App 提供外观（颜色模式 / 悬浮底栏 / 液态玻璃）与模块设置（入口 / 隐藏图标）。

## 主要功能

### 背屏中心 

- 禁用背屏长按切换壁纸。
- 背屏上滑快捷面板。

### 系统进程 

- 禁用背屏保护提示（"请按电源键熄灭正屏后使用背屏"）。
- 按应用禁用背屏双击唤醒，按当前正屏前台应用包名拦截。

### 主题商店 

- 去除 15 张壁纸上限。
- 修复背屏壁纸应用失败，并负责壁纸状态同步。
- 妙享背屏页快捷入口。

## 模块作用域

| 包名                         | 用途                                   |
| ---------------------------- | -------------------------------------- |
| `system`                     | 背屏保护提示、背屏双击唤醒拦截         |
| `com.xiaomi.subscreencenter` | 长按拦截、快捷面板                     |
| `com.android.thememanager`   | 壁纸数量限制、壁纸应用修复、设置页入口 |

最低 Android 版本为 API 36。

## 前置要求与兼容性

- Android API 36 及以上。
- LSPosed / 兼容 Xposed 环境，Modern Xposed API 102。
- 目标背屏 `976 × 596px`（HyperOS 4）。
- 反馈包的数据库采集依赖 Root 实现的 `su -M`；KernelSU 与 Magisk 均提供该参数。
- 若检测到 `vendor.display.builtin_presentation=0`，模块 App 会提示隐藏背屏/防屏幕共享类模块可能导致背屏截图失败或自定义壁纸黑屏。
- `k2.s`、`Z1.t`、`Z1.v`、`yp31`、`o5`、`ol` 等为宿主 R8 名称，系统应用升级后需要重新核对。
- 系统进程 Hook 依赖 `DualScreenCoverManager`、`PowerManagerServiceImpl` 以及当前前台任务字段，系统升级后需要重新确认。
- 妙享背屏页快捷入口依赖主题商店 `com.rearScreen.RearScreenSettingActivity`、`EntryConfig` 和 `user_guide` / `serve_assistant` 这些 controller key。
- 权限目录字段必须经过绝对路径校验；不要重新把非路径字符串 `qp5l=incallshow` 当作目录。
- 快捷面板依赖 `SubScreenLauncher`、`notification_panel` 和 `smart_assistant_panel`。
- 系统手势排除区占底部 30%，新增原厂手势时需要重新评估冲突。
- `textureBlur` 不能放入同一个 `layerBackdrop` 采样子树，否则可能形成采样环并触发 native crash。

## 安装

1. 从 [Releases](https://github.com/chuan2033/MiBackscreen/releases) 下载最新 APK 并安装。
2. 在 LSPosed 中启用 `MiBackscreen`。
3. 勾选作用域：`system`、`com.xiaomi.subscreencenter`、`com.android.thememanager`。
4. 重启手机；只调试背屏中心或主题商店功能时，也可分别重启对应作用域进程。
5. 打开模块 App，确认模块已激活。

App 内强制停止作用域进程的功能需要 root。

## 使用说明

- 背屏上滑手势：单指从屏幕高度 70% 以下起手，向上位移超过 `32dp` 打开面板；向下拖动或点关闭按钮退出，返回键到达 `SubScreenLauncher` 也会关闭。
- 按应用禁用双击唤醒时，会同时读取系统记录的前台包名和运行任务中的候选 Activity 包名，覆盖游戏启动后短暂跳转到 SDK/登录页导致前台包名变化的场景。
- 反馈日志应在复现问题后立即生成，生成前不要重新应用壁纸或重启背屏中心、主题商店。

## 快捷面板

### 手势

- 单指从屏幕高度 70% 以下起手。
- 向上位移超过 `32dp` 时打开面板。
- 向下拖动或点击右上角关闭按钮退出。

## 从源码构建

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

```powershell
adb shell am start --display 1 -n com.xiaomi.subscreencenter/.SubScreenLauncher
```

## 问题反馈

在 [Issues](https://github.com/chuan2033/MiBackscreen/issues) 提交反馈，请附带：

1. LSPosed 模块日志（设置 → 日志 → 详细日志）。
2. 设备型号、系统版本与模块版本。
3. 复现步骤。
4. 预期行为与实际行为。
5. 相关截图。

模块 App 内"关于 → 反馈 → 日志"可生成 ZIP 反馈包并调起系统分享面板（生成时机见上方使用说明）。

## 免责声明

- 本模块会修改系统背屏、系统界面与主题商店行为，请自行评估风险。
- 不同系统版本、固件版本、Xposed 环境之间可能存在兼容性差异。
- 系统框架、系统界面或主题商店更新后，部分 Hook 点可能需要重新适配。
- 使用本模块造成的功能异常或设备风险，请自行承担。

## 技术栈与致谢

项目许可证：[GPL-3.0](LICENSE)。

| 项目                                                         | 许可证     | 用途                           |
| ------------------------------------------------------------ | ---------- | ------------------------------ |
| [compose-miuix-ui/miuix](https://github.com/compose-miuix-ui/miuix) | Apache-2.0 | Compose UI、主题、开关视觉规格 |
| [AndroidX Activity Compose](https://developer.android.com/jetpack/androidx/releases/activity) | Apache-2.0 | Compose Activity               |
| [Modern Xposed API](https://github.com/libxposed/api)        | Apache-2.0 | LSPosed 模块 API               |
| [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) | Apache-2.0 | 液态玻璃效果上游               |
| [KernelSU](https://github.com/tiann/KernelSU)                | GPL-3.0    | 悬浮底栏实现参考               |

致谢：感谢 miuix、Modern Xposed API、AndroidLiquidGlass 与 KernelSU 等开源项目。

## License

See [LICENSE](LICENSE).
