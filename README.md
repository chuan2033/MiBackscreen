# MiBackscreen

[English](README_EN.md) · [项目主页](https://github.com/chuan2033/MiBackscreen) · [下载](https://github.com/chuan2033/MiBackscreen/releases)

基于 Modern Xposed 的小米背屏模块。当前分支 `feat/18pro-max-rear-screen` 包含新版背屏中心、主题商店和负一屏应用卡适配。分支名及请求中使用的机型标识不代表实际设备支持范围。

## 功能与默认值

| 功能 | 默认 | 使用说明 |
| --- | --- | --- |
| 禁用长按切换壁纸 | 开 | 背屏长按不进入编辑 |
| 移除壁纸数量限制 | 开 | 解除主题商店的 15 张限制 |
| 背屏应用卡 / 移除应用卡数量限制 | 开 / 开 | 官方上滑列表中显示 MiBackscreen 卡片；点击进入快捷面板 |
| 修复背屏壁纸应用 | 关 | 修复资源访问及应用状态同步；确认切换与取消编辑保持不同语义 |
| 取件码增强 | 开 | 小爱取件卡片进入分组选择页，选择显示在岛上的码；新批次不混入旧批次 |
| 禁用背屏保护提示 | 关 | 拦截要求先熄灭正屏的提示 |
| 按应用禁用双击唤醒 | 关，名单为空 | 功能页进入选择应用；关掉开关保留名单，仅匹配当前主屏前台相关应用 |
| 主题商店模块入口 | 开 | 妙享背屏设置页显示快捷入口 |

外观支持系统/浅色/深色、动态取色、普通或悬浮底栏及液态玻璃。悬浮底栏、液态玻璃默认关闭，底栏模糊及启动检查更新默认开启。

底部“功能”标签默认显示已开启功能数量（1–7），全部关闭时不显示角标。每个功能开关计 1，包括应用选择页中的双击唤醒总开关，不按应用名单数量累加。在“设置 → 外观 → 显示功能数量”可隐藏角标，三种底栏均支持。

快捷面板从官方应用卡入口打开，底部上滑关闭。面板支持长按、壁纸上限、应用卡上限三个开关；写入失败会回滚。模块 App 离线修改保留待同步值，连接 LSPosed 服务后补交；待同步不表示宿主已经生效。

## 环境与安装

- 最低 Android API 36；使用支持 Modern Xposed 的 LSPosed / 兼容框架。模块元数据声明最低 API 101、目标 API 102，代码依赖 API 102。
- 仓库历史实测设备为小米 17 Pro Max（popsicle / 2509FPN0BC），Android 17 / API 37、HyperOS 4，副屏 976 × 596。已测试场景及宿主版本见[设备验证记录](docs/validation-2026-09-27.md)，不代表跨设备或跨固件完整兼容。
- Hook 依赖宿主类和方法签名。系统、背屏中心、主题商店或小爱更新后，需要重新核验。
- 主题商店含 AI 背屏资源与人脸录入数量兼容逻辑；人脸读取失败按未知处理。萌宠实际应用全链路仍待真机验证。

安装 APK，在框架中启用模块并勾选以下五个作用域，然后重启手机使所有 Hook 加载：

| 作用域 | 用途 |
| --- | --- |
| `system` | 背屏保护提示、按应用拦截双击唤醒 |
| `com.xiaomi.subscreencenter` | 长按、应用卡及快捷面板、确认壁纸选择同步 |
| `com.android.thememanager` | 壁纸数量/应用、设置入口、AI 背屏与人脸数量兼容 |
| `com.miui.voiceassist` | 取件码识别结果展示、通知点击及刷新 |
| `com.miui.personalassistant` | 负一屏背屏应用卡商店请求适配 |

主页“已激活”表示模块服务已连接，不证明所有 Hook 成功。配置由 Hook 下次读取生效；壁纸/应用卡等部分开关还会请求停止相关宿主，需 root 并重新打开对应应用。APK 更新后需重新加载相关宿主进程，系统 Hook 更新需重启手机。“重启作用域”包含系统、小爱、背屏、主题商店和智能助理；勾选“系统”并点击“确定”后，模块通过 root 执行整机重启；同时选择应用时只发送一次重启命令。仅选择应用则停止对应应用，普通设置开关的自动刷新不会重启手机。

若提示 `vendor.display.builtin_presentation=0`，检查隐藏背屏/防屏幕共享类模块，它们可能影响背屏截图或自定义壁纸。

## 反馈

复现后立即打开“主页 → 日志”，在重新应用壁纸或重启宿主之前生成反馈 ZIP。关闭进度卡片不会取消任务；完成后手动点击分享，失败可重试，不会自动发送。

当前反馈格式为 schema 6：包括设备/宿主版本、配置、Hook 状态、取件选择、过滤日志、系统诊断、资源路径与文件元数据、editConfig 文本、主题商店 `rearScreen.db` 及 WAL/SHM 副本；AI 数据库只报告结构，不复制内容。不复制用户壁纸图片/视频字节。包内可能包含取件码、应用名单及其他私人信息，分享前请检查。

完整采集需要 root 和支持 `su -M` 的实现；无权限、超时或缺失文件会记入相应报告，ZIP 生成成功不表示每项采集成功。通过 [Issues](https://github.com/chuan2033/MiBackscreen/issues) 提供复现步骤、预期/实际行为、版本和必要截图，按需附经检查的反馈包。

## 构建

需要 JDK 21、Android SDK 37、Build Tools 37.0.0；Wrapper 固定 Gradle 9.6.0。版本以 [app/build.gradle](app/build.gradle) 为准。设置本机 `JAVA_HOME` 和忽略的 `local.properties` 中的 `sdk.dir`，执行：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --offline --console=plain
.\gradlew.bat :app:assembleRelease --offline --console=plain
```

没有依赖或 Wrapper 缓存时去掉 `--offline` 并联网准备依赖。Debug 输出为 `app/build/outputs/apk/debug/app-debug.apk`。

Release 开启 R8 和资源收缩；配置签名后输出 `app/build/outputs/apk/release/app-release.apk`，未配置则为 `app-release-unsigned.apk`。签名从 `local.properties` 或同名环境变量读取 `RELEASE_STORE_FILE`、`RELEASE_STORE_PASSWORD`、`RELEASE_KEY_PASSWORD`，可选 `RELEASE_KEY_ALIAS`（默认 `hyperbackscreen`）。相对密钥路径基于 `app/`；未指定文件时检查 `app/release-key.jks`。显式指定密钥文件但配置不完整时构建报错。签名可用时 Debug 也使用该密钥，但保持不混淆；否则使用默认 Debug 签名。覆盖安装须与已安装版本证书一致。

开发链路、配置键及维护约束见[工程说明](交接文档.md)，验证范围见[回归矩阵](docs/DEVICE_TEST_MATRIX.md)。编译通过不等于真机 Hook 正常。

## 许可证与致谢

[GPL-3.0](LICENSE)。模块修改系统及宿主行为，不同固件可能存在兼容问题，请按已验证范围使用。

下表列主要技术来源；App“开源许可”按运行依赖及本地移植来源归组列出，包括 Kotlin / kotlinx、JetBrains Compose、Material Color Utilities、Poko、注解库、Guava 和 libxposed 服务接口。清单维护于 [LicensePage.kt](app/src/main/java/hook/HyperBackscreen/ui/about/LicensePage.kt)。

| 项目 | 许可证 | 用途 |
| --- | --- | --- |
| [Miuix](https://github.com/compose-miuix-ui/miuix) | Apache-2.0 | Compose UI、导航及模糊 |
| [AndroidX](https://developer.android.com/jetpack/androidx) | Apache-2.0 | Activity、Compose、生命周期 |
| [Modern Xposed](https://central.sonatype.com/artifact/io.github.libxposed/api/102.0.0) | Apache-2.0 | Hook API |
| [AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) | Apache-2.0 | 本地玻璃效果来源 |
| [KernelSU](https://github.com/tiann/KernelSU) | GPL-3.0 | 悬浮底栏实现参考 |
