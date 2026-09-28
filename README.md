<h1>MiBackscreen</h1>

<p>
  用于小米 17 Pro 系列的背屏Xposed模块。<br>
 为背屏补全壁纸管理、背屏保护与快捷面板等功能。
</p>

<p>
  <a href="./README_EN.md">English</a> · <a href="https://github.com/chuan2033/MiBackscreen">项目主页</a>
</p>

<p>
  <a href="https://github.com/chuan2033/MiBackscreen/releases"><img alt="GitHub Release" src="https://img.shields.io/github/v/release/chuan2033/MiBackscreen?display_name=release"></a>
 <a href="https://github.com/chuan2033/MiBackscreen/stargazers"><img alt="GitHub Repo stars" src="https://img.shields.io/github/stars/chuan2033/MiBackscreen?style=flat"></a>
 <a href="https://github.com/chuan2033/MiBackscreen/issues"><img alt="GitHub Issues" src="https://img.shields.io/github/issues/chuan2033/MiBackscreen"></a>
  <a href="https://developer.android.com/"><img src="https://img.shields.io/badge/Android-16%2B-3DDC84?logo=android&logoColor=white" alt="Android"></a>
 <a href="https://github.com/LSPosed/LSPosed"><img src="https://img.shields.io/badge/Framework-LSPosed%20%2F%20Xposed-5C6BC0" alt="Framework"></a>
</p>


## 功能概览

- 解除背屏壁纸 15 张上限，并修复主题商店壁纸应用失败与状态同步。
- 拦截背屏保护提示，支持按应用禁用背屏双击唤醒。
- 在官方上滑应用卡列表中注入 MiBackscreen 入口，点击打开快捷面板。
- 识别小爱记忆岛中的多个快递取件码，支持按驿站分类并选择显示在灵动岛卡片上。

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
| `com.miui.voiceassist`       | 取件码识别、灵动岛卡片点击与刷新       |
| `com.miui.personalassistant` | 独立分支的背屏应用卡商店适配 |

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
- 面板打开期间的系统手势排除区占底部 22%，新增原厂手势时需要重新评估冲突。
- `textureBlur` 不能放入同一个 `layerBackdrop` 采样子树，否则可能形成采样环并触发 native crash。

## 安装

1. 从 [Releases](https://github.com/chuan2033/MiBackscreen/releases) 下载最新 APK 并安装。
2. 在 LSPosed 中启用 `MiBackscreen`。
3. 勾选作用域：`system`、`com.xiaomi.subscreencenter`、`com.android.thememanager`、`com.miui.voiceassist`。
4. 重启手机；只调试背屏中心或主题商店功能时，也可分别重启对应作用域进程。
5. 打开模块 App，主页状态卡显示已激活（绿卡）即可。

App 内强制停止作用域进程的功能需要 root。

## 使用说明

- 上滑打开官方应用卡列表，点击 MiBackscreen 卡片进入快捷面板；底部上滑关闭，关闭后恢复宿主手势。
- 按应用禁用双击唤醒时，会同时读取系统记录的前台包名和当前主屏任务中的候选 Activity 包名（不包含历史后台任务），覆盖游戏启动后短暂跳转到 SDK/登录页导致前台包名变化的场景。
- 反馈日志应在复现问题后立即生成，生成前不要重新应用壁纸或重启背屏中心、主题商店。

## 快捷面板

### 手势

- 沿用官方上滑手势打开应用卡列表。
- 点击 MiBackscreen 卡片打开快捷面板。
- 从面板底部上滑关闭，系统手势排除区恢复为打开前的值。

## 取件码

- 在小爱中记忆包含多个快递取件码的通知后，点击灵动岛取件卡片进入取件码页。
- 页面按驿站分组；每个驿站可以单独选择哪些取件码显示在灵动岛上。
- 不同一批记忆结果不会继续带入上一批历史码；确认取件后，当前取件码页会自动关闭。
- 模块 App「功能」页提供「取件码增强」总开关，默认开启；关闭后完全走原生逻辑，无需重启小爱作用域。

## 从源码构建

当前 `feat/18pro-max-rear-screen` 是独立开发分支，包含新版宿主应用卡片及快捷面板适配，不合入以 `48921ad` 为基线的 main。快捷面板入口为官方上滑应用卡列表中的 MiBackscreen 卡片。本轮设备信息、上游调查、修复及验证结果见 [本地验证记录](docs/validation-2026-09-27.md)。

本地测试只需 debug。若要让 debug 使用指定密钥签名，在不提交的 `local.properties` 中设置 `RELEASE_STORE_FILE`、`RELEASE_KEY_ALIAS`、`RELEASE_STORE_PASSWORD`、`RELEASE_KEY_PASSWORD`；也支持同名环境变量。debug 保持不混淆及调试日志，仅更换签名。指定密钥配置不完整时会报错，不会回退到默认 debug 签名。

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

1. LSPosed 模块日志（主页 → 日志）。
2. 设备型号、系统版本与模块版本。
3. 复现步骤。
4. 预期行为与实际行为。
5. 相关截图。

模块 App 主页「日志」卡可生成 ZIP 反馈包并调起系统分享面板（需 root 授权才采集完整日志，生成时机见上方使用说明）。

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
