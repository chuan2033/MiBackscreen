# MiBackscreen 开发文档

> 更新时间：2026-07-20

## 概述

- 包名：`hook.HyperBackscreen`
- 工程路径：`D:\Ai\MiBackscreen`
- Git：`https://github.com/chuan2033/MiBackscreen`
- 目标包：`com.xiaomi.subscreencenter`、`com.android.thememanager`
- 最低系统：Android 15（`minSdk 35`）
- 版本：1.1.0 (versionCode 2)

## Hook 目标

| 目标包 | Hook 点 | 说明 |
|--------|---------|------|
| `com.xiaomi.subscreencenter` | `Z1.t#e()` / `Z1.v#g()` | 长按入口判断 |
| `com.xiaomi.subscreencenter` | `Z1.t#run()` / `Z1.v#run()` | 长按动作执行 |
| `com.xiaomi.subscreencenter` | `Z1.v#f()` | 新版手势触摸处理 |
| `com.android.thememanager` | `RearScreenDetailViewModel.o5(List)` | 壁纸数量检查 |

混淆注释集中在 `common/Constants.java`，目标应用更新后需重新反混淆。

## 实现方式

- `ModuleMain.onPackageReady()` 按目标包分别安装背屏 Hook 和主题商店 Hook
- 背屏 Hook 覆盖旧手势类 `Z1.t` 和新手势类 `Z1.v`
- `MainPanel#dispatchTouchEvent()` 获取 Hook 进程 Context，安装设置同步 Receiver
- `PrefsBridge.shouldBlockLongPressEdit()` 决定是否拦截背屏长按
- `PrefsBridge.shouldRemoveWallpaperLimit()` 决定是否绕过壁纸限制
- UI 开关先写本地 prefs，再写 XposedService remote prefs，广播同步到 Hook 进程
- 重启作用域通过 `su -c am force-stop <package>` 执行

## 工程结构

```text
app/src/main/java/hook/HyperBackscreen/
├── app/       ModuleApp.java - Application + XposedService 生命周期
├── bridge/    PrefsBridge / SettingsSyncBridge / SettingChangedReceiver
├── common/    Constants.java - 目标包、混淆类名、方法名、prefs key
├── hook/      ModuleMain.java - Modern Xposed 主入口
└── ui/
    ├── components/   FloatingBottomBar、InfoRows、PreferenceCards
    ├── liquid/       Lens、Vibrancy、InnerShadow、CombinedBackdrop
    ├── animation/    DampedDragAnimation、InteractiveHighlight
    ├── home/         HomePage（功能设置 + 系统信息）
    ├── config/       ConfigPage（导航栏设置 + 当前配置）
    ├── about/        AboutPage、LicensePage
    ├── theme/        HomeUiTokens
    └── util/         SystemInfo
app/src/main/res/
├── values/            strings.xml（英文默认）
├── values-zh-rCN/     strings.xml（中文）
```

## Xposed 元数据

- `java_init.list`：`hook.HyperBackscreen.hook.ModuleMain`
- `module.prop`：`minApiVersion=101`，`targetApiVersion=102`，`staticScope=true`
- `scope.list`：`com.xiaomi.subscreencenter`，`com.android.thememanager`

## 页面结构

| Tab | 图标 | 页面 | 内容 |
|-----|------|------|------|
| 主页 | `MiuixIcons.Home` | HomePage | 功能设置开关 + 当前系统信息 |
| 配置 | `MiuixIcons.Settings` | ConfigPage | 导航栏设置、当前配置 |
| 关于 | `MiuixIcons.Info` | AboutPage | 开发者、项目地址、引用 |

二级页面：LicensePage（从关于页进入，AnimatedContent 滑动切换）

## UI 实现

- `RearScreenApp.kt`：固定浅色 MiuixTheme、edge-to-edge、透明系统栏、关闭 contrast enforcement
- `HomeScreen.kt`：Scaffold + overlay 布局，内容层 `layerBackdrop(backdrop)` 提供采样，顶栏/底栏作为 overlay 使用 `textureBlur`
- 普通底栏：Miuix `NavigationBar`，blur radius `20f`，40% 透明 surface 混合
- 悬浮底栏：自定义 `FloatingBottomBar`，液态效果链 `vibrancy()` → `blur(4dp)` → `lens(refractionHeight=24dp, refractionAmount=24dp)`，选中指示器带 `chromaticAberration=0.5f` + `InnerShadow`
- 毛玻璃警告：不要把 `textureBlur` 放进同一个 `layerBackdrop` 采样子树，否则触发 `libhwui` 原生崩溃
- i18n：所有用户字符串通过 `stringResource` 资源化，支持中英文（`values/strings.xml` + `values-zh-rCN/strings.xml`）
- UI 规范：遵循 Miuix 官方规范，使用 squircle 替代 RoundedCornerShape，contentPadding 仅设 top，首尾 Spacer 处理呼吸和导航栏留白

## 技术栈

| 组件 | 版本 |
|------|------|
| Gradle | 9.6.0（本地） |
| Android Gradle Plugin | 9.2.1 |
| Kotlin Compose Plugin | 2.3.21 |
| JDK | 21 |
| compileSdk | 37 |
| targetSdk / minSdk | 35 |
| Miuix UI/Preference/Icons/Blur | 0.9.3 |
| Modern Xposed API | 102.0.0 |

## 项目规范

- `hook/` 只放 Hook 入口和目标应用行为修改
- `bridge/` 放 UI 进程与 Hook 进程的同步代码
- `common/Constants.java` 统一维护包名、混淆类名、方法名、广播 action 和 prefs key
- `ui/` 按页面、组件、主题 token 拆分；液态效果放 `ui/liquid/`，动画放 `ui/animation/`，底栏组件放 `ui/components/`
- 主题商店 Hook 放在 `ModuleMain.java` 的 `installWallpaperLimitHook()`
- 背屏长按 Hook 放在 `ModuleMain.java` 的 `installLongPressHooks()`
- 修改 Xposed 目标包时，同步更新 `scope.list` 和文档

## 构建验证

改了 Hook 行为需验证：

- LSPosed 作用域包含 `com.xiaomi.subscreencenter` 和 `com.android.thememanager`
- UI 开关状态能同步到 Hook 进程
- 背屏长按行为与开关状态一致
- 主题商店背屏壁纸数量限制解除生效

改了系统栏或底栏行为需验证：

- 状态栏和导航栏区域没有出现系统强制白底
- 顶栏、普通底栏和悬浮底栏的模糊、半透明底色正常显示
- 悬浮底栏的圆角、模糊、阴影正常显示
- 液态玻璃底栏：点击切换指示器跟随、拖拽松手吸附、重力感应高光旋转正常
- 滚动时观察是否有明显卡顿、闪烁、黑块或 native crash
