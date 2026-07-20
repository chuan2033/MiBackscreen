# AGENTS.md

AI Agent 项目指令。

## 构建

```powershell
$env:JAVA_HOME='D:\RuanJian\Android\Android Studio\jbr'
$env:ANDROID_SDK_ROOT='D:\RuanJian\Android\Sdk'
$env:ANDROID_HOME='D:\RuanJian\Android\Sdk'
& 'D:\Ruanjian\Android\gradle-9.6.0\bin\gradle.bat' --no-daemon --console=plain :app:assembleDebug
```

安装：

```powershell
& 'D:\RuanJian\Android\Sdk\platform-tools\adb.exe' install -r -t 'D:\Ai\MiBackscreen\app\build\outputs\apk\debug\app-debug.apk'
```

## 关键文件

| 文件 | 职责 |
|------|------|
| `app/build.gradle` | SDK、插件、Miuix、Xposed 依赖配置 |
| `app/src/main/AndroidManifest.xml` | Activity、Receiver、XposedProvider |
| `app/src/main/resources/META-INF/xposed/java_init.list` | Xposed 入口类 |
| `app/src/main/resources/META-INF/xposed/module.prop` | Xposed API 版本 |
| `app/src/main/resources/META-INF/xposed/scope.list` | 目标作用域包 |
| `app/src/main/java/hook/HyperBackscreen/hook/ModuleMain.java` | Hook 主入口 |
| `app/src/main/java/hook/HyperBackscreen/bridge/PrefsBridge.java` | SharedPreferences 桥接 |
| `app/src/main/java/hook/HyperBackscreen/common/Constants.java` | 包名、混淆类名、prefs key |
| `app/src/main/java/hook/HyperBackscreen/ui/HomeScreen.kt` | 主界面、Tab 导航、重启作用域 |
| `app/src/main/java/hook/HyperBackscreen/ui/home/HomePage.kt` | 主页：功能设置 + 系统信息 |
| `app/src/main/java/hook/HyperBackscreen/ui/config/ConfigPage.kt` | 配置页：导航栏 + 当前配置 |
| `app/src/main/res/values/strings.xml` | 英文字符串资源 |
| `app/src/main/res/values-zh-rCN/strings.xml` | 中文字符串资源 |

## 项目规则

- 删除文件必须进回收站，禁止使用 `-Force` 永久删除
- Hook 目标使用 R8 混淆名，目标应用更新后需重新反混淆
- 所有 UI 使用 Miuix 组件，不要引入 Material3
- `textureBlur` 组件不要放进同一个 `layerBackdrop` 采样子树
- 新增开关需同时更新 HomePage 和 PrefsBridge 默认值
- 修改 Xposed 目标包时，同步更新 `scope.list` 和文档
- 所有用户字符串走 `stringResource`，禁止硬编码
- 新增字符串同时加到 `values/strings.xml` 和 `values-zh-rCN/strings.xml`
