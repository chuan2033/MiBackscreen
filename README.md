# MiBackscreen

面向小米背屏的 LSPosed 模块，基于 libxposed API 102。Hook 目标：`com.xiaomi.subscreencenter`（背屏中心）与 `com.android.thememanager`（主题商店）。

## 功能

- **禁用背屏长按** — 拦截背屏长按进入壁纸编辑态，默认开启
- **去除壁纸限制** — 绕过主题商店背屏壁纸 15 张上限，默认开启
- **修复应用失败** — 修复背屏壁纸应用失败，需搭配主题破解使用
- **重启作用域** — 右上角刷新图标，一键强制停止背屏或主题商店（需 root）
- **底栏样式** — 普通底栏 / 悬浮底栏 / 液态玻璃底栏三级切换
- **毛玻璃效果** — 顶栏和底栏使用 Miuix `textureBlur`，悬浮底栏使用液态玻璃（AGSL 折射 shader）
- **国际化** — 中英文，通过 Android 字符串资源实现

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

**注意事项：**

- **不要用 `gradlew`** — wrapper 本地无发行版缓存，会先下载整个 Gradle。`gradle-wrapper.properties` 的发行版地址已指向腾讯镜像，但仍不如直接跑本地 `gradle.bat`。
- `buildToolsVersion` 已在 `app/build.gradle` 固定为 `37.0.0`。AGP 9.2.1 默认要 build-tools 36.0.0，本地没装就会联网自动下载并卡在 `Still waiting for package manifests to be fetched remotely`。升级 AGP 后若再卡这里，先跑 `--offline` 让它报出缺哪个包，再固定到本地已装版本。
- `settings.gradle` 已配阿里云 maven 镜像（google / central / gradle-plugin），原仓库作兜底。
- Release 构建 `assembleRelease` 会启用 R8 混淆与资源收缩，但**当前没有配置签名**，产出的是 `app-release-unsigned.apk`，不能直接安装分发。

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

## 已知限制

- Hook 使用 R8 混淆名（`Z1.t` / `Z1.v` / `o5` 等，集中在 `Constants`），目标应用更新后可能失效
- 壁纸限制解除需要主题商店加入 LSPosed 作用域
- 实时毛玻璃会增加 GPU 压力，不要再添加额外全屏模糊层
- **`textureBlur` 组件不要放进同一个 `layerBackdrop` 采样子树里**，否则采样成环会触发 native crash
- 重启作用域需要 root 权限
- `compileSdk 37` 为预览级，`minSdk 35` 门槛较高

## 许可证

[GPL-3.0](LICENSE)

改编自以下上游项目：

- 液态玻璃折射效果（`ui/liquid/`）— [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)（Apache-2.0），经 compose-miuix-ui 示例镜像
- 悬浮底栏（`ui/components/FloatingBottomBar.kt`）— [KernelSU](https://github.com/tiann/KernelSU)（GPL-3.0）
- UI 组件库 — [compose-miuix-ui/miuix](https://github.com/compose-miuix-ui/miuix)（Apache-2.0）
