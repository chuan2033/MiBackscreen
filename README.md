# MiBackscreen

面向小米背屏的 LSPosed 模块。

## 功能

- **禁用背屏长按** — 拦截背屏长按进入壁纸编辑态，默认开启
- **去除壁纸限制** — 绕过主题商店背屏壁纸 15 张上限，默认开启
- **修复应用失败** — 修复背屏壁纸应用失败，需搭配主题破解使用
- **重启作用域** — 右上角刷新图标，一键强制停止背屏或主题商店（需 root）
- **底栏样式** — 普通底栏 / 悬浮底栏 / 液态玻璃底栏三级切换
- **毛玻璃效果** — 顶栏和底栏使用 Miuix `textureBlur`，悬浮底栏使用液态玻璃（GLSL 折射）

## 安装

1. 在 LSPosed 中启用模块，作用域选择 `com.xiaomi.subscreencenter` 和 `com.android.thememanager`
2. 安装 APK 并重启背屏/主题商店

```powershell
& 'D:\RuanJian\Android\Sdk\platform-tools\adb.exe' install -r -t 'app\build\outputs\apk\debug\app-debug.apk'
```

## 构建

```powershell
$env:JAVA_HOME='D:\RuanJian\Android\Android Studio\jbr'
$env:ANDROID_SDK_ROOT='D:\RuanJian\Android\Sdk'
$env:ANDROID_HOME='D:\RuanJian\Android\Sdk'
& 'D:\Ruanjian\Android\gradle-9.6.0\bin\gradle.bat' --no-daemon --console=plain :app:assembleDebug
```

输出：`app/build/outputs/apk/debug/app-debug.apk`

## 已知限制

- Hook 使用 R8 混淆名，目标应用更新后可能失效
- 壁纸限制解除需要主题商店加入 LSPosed 作用域
- 实时毛玻璃会增加 GPU 压力，不要再添加额外全屏模糊层
- `textureBlur` 组件不要放进同一个 `layerBackdrop` 采样子树里，否则会触发 native crash
- 重启作用域需要 root 权限

## 许可证

[GPL-3.0](LICENSE)

液态玻璃底栏效果改编自 [KernelSU / AndroidLiquidGlass](https://github.com/tiann/KernelSU)（GPL-3.0）
