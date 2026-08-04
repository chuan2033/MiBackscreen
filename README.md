# MiBackscreen

面向小米背屏的 LSPosed 模块，基于 libxposed API 102。Hook 目标：`com.xiaomi.subscreencenter`与 `com.android.thememanager`。

## 功能

- **禁用背屏长按** — 拦截背屏长按进入壁纸编辑态，默认开启
- **去除壁纸限制** — 绕过主题商店背屏壁纸 15 张上限，默认开启
- **修复应用失败** — 修复背屏壁纸应用失败，需搭配主题破解使用

三个功能开关走 LSPosed 远程偏好（`module_config`），UI 改动后 Hook 端下次读取即生效，无需重启模块或宿主应用。底栏样式与液态玻璃是纯 UI 外观项，只存本地。

## 安装

1. 在 LSPosed 中启用模块，作用域勾选 `com.xiaomi.subscreencenter` 和 `com.android.thememanager`
2. 安装 APK 后重启背屏 / 主题商店（可用 App 内「重启作用域」）

```powershell
& 'D:\RuanJian\Android\Sdk\platform-tools\adb.exe' install -r 'app\build\outputs\apk\debug\app-debug.apk'
```

## 构建

用本地 Gradle 发行版直接构建，依赖已全部缓存，加 `--offline` 最快：

```powershell
& 'D:\Ruanjian\Android\gradle-9.6.0\bin\gradle.bat' assembleDebug --offline --console=plain
```

输出：`app/build/outputs/apk/debug/app-debug.apk`

## 调试

logcat tag 为 `MiBackscreen`（旧版本叫 `RearScreenLongPressToggle`，已废弃）：

```powershell
& 'D:\RuanJian\Android\Sdk\platform-tools\adb.exe' logcat -s MiBackscreen
```

Hook 安装成功会打印 `Hooks installed for ...`；目标类或方法找不到会打印 `Hook target missing: ...`，这通常意味着目标应用更新后混淆名变了。

## 项目结构

```
hook/HyperBackscreen/
├── app/        ModuleApp — Application 入口，绑定 LSPosed 服务并广播就绪回调
├── bridge/     PrefsBridge — 唯一的偏好读写入口，本地与远程双写
├── common/     Constants — 包名、偏好 key、Hook 目标类名与混淆名集中处
├── hook/       ModuleMain — LSPosed 模块入口，安装全部 Hook
└── ui/         Compose 界面
    ├── about/ config/ home/    三个页面
    ├── components/             共用组件（卡片、信息行、悬浮底栏、毛玻璃容器）
    ├── animation/ liquid/      阻尼拖拽与液态玻璃效果（移植自上游，见许可证）
    ├── theme/ util/            尺寸 token、系统信息、URL 跳转
```

依赖方向为 `hook → bridge → app/common` 单向。Hook 侧只读 LSPosed 远程偏好——那是唯一可跨进程共享的数据源，读宿主应用私有目录会拿到空值。

## 许可证

[GPL-3.0](LICENSE)

改编自以下上游项目：

- 液态玻璃折射效果（`ui/liquid/`）— [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)（Apache-2.0），经 compose-miuix-ui 示例镜像
- 悬浮底栏（`ui/components/FloatingBottomBar.kt`）— [KernelSU](https://github.com/tiann/KernelSU)（GPL-3.0）
- UI 组件库 — [compose-miuix-ui/miuix](https://github.com/compose-miuix-ui/miuix)（Apache-2.0）
