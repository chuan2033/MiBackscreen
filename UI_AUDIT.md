# MiBackscreen UI 审查报告

审查日期：2026-09-27。依据：`D:\Codex\ui组件排查.md`。本次只审查，未修改业务源码、Hook、配置键、默认值、构建配置或依赖。

## 1. 核心结论与范围

**不能判定全部 UI 正常。** 最优先处理配置与界面状态不同步、取件码保存失败仍更新勾选状态；真机已确认深色液态底栏前景色错误、两种悬浮导航的语义缺失，以及无操作卡片暴露点击/长按动作。

本报告共记录 **14 项**：9 项已确认问题（其中 3 项含真机视觉/控件树证据，其余为静态可证明路径）、1 项疑似风险、2 项待运行验证、2 项优化建议。优先级为 P1 两项、P2 九项、P3 三项，无已发现的 P0。已确认不等于所有触发条件都已在设备上复现。

### 1.1 代码基线、工程与依赖

| 项目 | 本次实际情况 |
| --- | --- |
| 仓库 | `D:\Codex\MiBackscreen`，分支 `feat/18pro-max-rear-screen` |
| HEAD | `5ffe821c0dca3452c210796b3317ab38ba174455`，**审查对象还包含工作区已有未提交/未跟踪源码**，不是只审查该提交 |
| 工程 | 单模块 `:app`；`main`、`debug`、`test` 源集；无 productFlavors、Fragment 或项目自有 XML layout |
| 变体 | debug / release；release 有 R8 与资源收缩。本轮只构建和验证 debug；未安装新 APK、未运行 release |
| SDK | compileSdk 37 / Build Tools 37.0.0 / minSdk 36 / targetSdk 36 |
| 构建 | AGP 9.2.1；Compose compiler Gradle plugin 2.3.21；Gradle 9.6.0；JDK 21.0.2 |
| Miuix | `miuix-ui-android`、`miuix-preference-android`、`miuix-icons-android`、`miuix-blur-android` **0.9.4**，已核对实际依赖解析 |
| Compose | `debugRuntimeClasspath` 解析得到 AndroidX UI / Foundation / Runtime **1.12.0**；不是从 import 猜测版本 |
| Activity / LSP | activity-compose 1.13.0；libxposed API 102.0.0；本地 interface/service 102.0.0 JAR |
| 语言 | 默认英文资源、`values-zh-rCN`；locale-config 声明 en / zh-CN |
| 主题入口 | `RearScreenApp.kt:186–220`；`PickupCodesActivity.kt:85–100`；背屏原生面板采用宿主 uiMode |
| 导航入口 | `MainActivity.kt:7` → RearScreenApp → HomeScreen；`PickupCodesActivity.kt:74`；LauncherAlias 与 LSPosed 模块设置入口指向 MainActivity |
| 共享组件 | `ui/components`；`MiuixStyleSwitch.kt`；`ui/theme`；`ui/animation`、`ui/liquid` |

扫描范围：`app/src/main/java` 全部文件的入口/控件/调用搜索，完整检查 UI Kotlin 文件及 UI Java 工具，读取 manifest、全部 strings/themes/locale 资源、debug 测试入口、现有测试与构建文件；追踪 `ModuleApp`、`PrefsBridge`、Provider、Constants、PickupCodes，以及 Hook 中插入卡片/设置行/通知视图和消费配置的路径。没有把非 UI Hook 的全量算法审查或安全审计算进本报告。

排除：`.gradle`、build 生成源码、依赖内部页面、设备上的其他应用页面；第三方源码仅作为 API 核实证据，不并入组件定义数。项目内 `liquid` 等从 AndroidLiquidGlass/Miuix 示例移植并维护的代码单独标识为视觉基础设施；未把它们当作新增产品页面。

### 1.2 运行环境与证据

- 真机 Xiaomi 17 Pro Max / 2509FPN0BC，Android 17 / API 37；主屏 1200×2608，480 dpi（约 400×869 dp），字体比例 **0.9**，中文、竖屏、手势导航。系统报告主屏 ON、副屏 OFF。
- 安装 APK 与本次 debug APK SHA-256 相同：`ed7f613b3b650aa07f6531454aca6329bd2f761d7bc2cf4ca9974b01c3baf394`。没有用旧包冒充当前构建。
- 测试临时切换了**应用内**颜色模式和底栏外观；最终恢复：跟随系统、悬浮底栏开、液态玻璃开、底栏模糊开。语言与业务开关/应用名单未改动；未改系统字体、密度、旋转、权限或框架状态；未重启/force-stop 任何作用域、清数据或发送真实通知。
- 重启弹窗只测试打开、全选、返回关闭，未点确定。取件码通过无 token 的测试 Intent 展示两组合成数据，未勾选写入、未广播通知刷新，完成后退出 Activity。
- 截图、XML 控件树、命令记录、依赖源码、构建和测试日志位于 [本地证据目录](/D:/Codex/MiBackscreenHuancun/evidence/ui-audit-2026-09-27)。截图未进行视觉改造；ADB 输出夹带的文本警告只在提取 PNG 字节时去除。
- 旧的 `docs/validation-2026-09-27.md` 仅作环境与测试方式参考，里面的历史通过项没有计入本轮覆盖率。

### 1.3 Miuix API 核实依据

下载并读取当前发布版本的 [miuix-ui-android 0.9.4 sources](https://repo.maven.apache.org/maven2/top/yukonga/miuix/kmp/miuix-ui-android/0.9.4/miuix-ui-android-0.9.4-sources.jar) 与 [miuix-preference-android 0.9.4 sources](https://repo.maven.apache.org/maven2/top/yukonga/miuix/kmp/miuix-preference-android/0.9.4/miuix-preference-android-0.9.4-sources.jar)。SHA-256 分别为 `222fde…35c453`、`0589ca…d3a3c0`，原始包和解压文件保存在证据目录。

核实了 Card 的无事件/可交互重载、SwitchPreference/CheckboxPreference、NavigationBar/FloatingNavigationBarItem、InputField、WindowDialog 及 DialogContentLayout、MiuixTheme/LocalContentColor。没有用新版文档替代 0.9.4 实现。

规范判定：未发现项目主应用可见设置控件调用 Material、Material3 或 AppCompat 的替代实现；Miuix 同名组件导入来源与实际调用一致。基础 Row/Column/Box/Image/Canvas、Drawable 的 ImageView 适配、系统输入法/Toast/分享器均不算混用。manifest 的平台 Material window theme 不会自行产生业务 Material 表单。背屏原生面板在宿主进程没有项目的 Compose owner/Miuix 主题，保留原生实现有明确依据；宿主 RecyclerView/RemoteViews 也不能直接替换成Compose控件。已确认的不规范使用集中在 A03–A05，而不是包名覆盖率。

## 2. 页面、组件与状态清单

计数口径：**9 个项目页面/面板**＝7 个主应用目的地＋1 个取件码页面＋1 个原生背屏面板；不把 MainActivity 外壳、Tab 的每次实例、弹窗或宿主既有页面重复算页面。另列 3 个 WindowDialog、3 个设置下拉菜单、4 组宿主注入入口/展示。Compose 定义与源码调用点清单见附录，动态项数量单独说明。

以下 `ui/`、`hook/`、`bridge/` 均相对于 `app/src/main/java/hook/HyperBackscreen/`；附录提供完整可点击路径。

### 2.1 全部页面与主要交互

| ID / 页面 | 定义和入口 | 页面组件、动态内容、状态与事件 | 静态 / 运行状态 |
| --- | --- | --- | --- |
| P01 主页 | `ui/home/HomePage.kt:28`；`HomeScreen.kt:458` | HomeStatusCard；5 个 InfoRow（机型/系统/Android/主题商店/背屏中心）；日志、捐赠 ArrowPreference。激活状态来自 ModuleApp 服务；日志异步生成后调用系统分享器 | 已静态；本机正常状态与捐赠入口已展示。未激活/缺目标包、日志生成/授权/分享未实测 |
| P02 功能 | `ui/function/FunctionPage.kt:15`；`HomeScreen.kt:465` | 7 个 SwitchPreference：长按、壁纸上限、应用卡上限、应用修复、背屏保护、双击、取件码；双击开时动态展开应用名单入口；状态全部提升到 RearScreenApp | 已静态；浅/深色、业务现有开关、二级入口与返回已看；未改业务值 |
| P03 关于 | `ui/about/AboutPage.kt:31,64`；`HomeScreen.kt:485` | Logo、版本、开发者/仓库/Miuix 链接与许可入口；顶栏设置入口 | 已静态；浅/深色及许可跳转已测，未打开外站 |
| P04 设置 | `ui/HomeScreen.kt:272` → `ui/config/ConfigPage.kt:18` | 3 个 WindowDropdownPreference（入口2项、语言3项、颜色6项）；6 个 SwitchPreference（应用卡、隐藏图标、更新、悬浮、液态、模糊）。液态仅在悬浮启用时显示 | 已静态；颜色菜单、深浅切换、悬浮/液态变化已测并恢复；入口/语言菜单及其写入、隐藏图标和自动更新开关写入未测 |
| P05 应用选择 | `ui/config/AppPickerPage.kt:74`；`HomeScreen.kt:218` | InputField → query 即时过滤；加载/空结果 MessageCard；动态 CheckboxPreference＋AppIcon；已选但查不到的包以包名占位保留；修改即时保存，无提交按钮 | 已静态；加载、占位项、键盘、搜索无结果和返回已测。设备未返回正常应用列表；图标 Drawable 分支、改选/失败回滚未覆盖，见 A10 |
| P06 捐赠 | `ui/about/DonatePage.kt:54`；`HomeScreen.kt:217` | Miuix TabRow 两项；微信/支付宝两张本地图片；返回；无支付调用或图片保存操作 | 已静态；两张图切换及深色布局已看。小屏/大字/横屏未测 |
| P07 开源许可 | `ui/about/LicensePage.kt:43,72`；`HomeScreen.kt:216` | 5 条 LicenseItem 经 CardBlock/AboutArrowPreference 生成；全部 URL 与许可证说明 | 已静态；中文浅色列表、长 URL 换行及返回已看，未打开外站 |
| P08 取件码 | `ui/pickup/PickupCodesActivity.kt:74,229,352`；通知 PendingIntent、exported Activity | 空页；动态驿站分组、每码 CheckboxPreference、全选/取消全选；新 Intent 合并同会话组、异会话替换；持久化选择后广播刷新；确认取件通知关闭页面 | 已静态；空页、两码、同会话两驿站4码、返回已展示；未写选择、未发真实通知，保存失败/冷绑定/重建未实测 |
| P09 背屏快捷面板 | `ui/SwipePanelHost.kt:710,866`；`hook/ModuleMain.java:679` | 原生根层、SwipeDismissCard、ScrollView、3 个 switchRow/MiuixStyleSwitch、标题/摘要；版本替换时仅显示重启提示；后台保存→成功摘要/失败回滚；底部上滑及返回关闭 | 已静态；本轮副屏 OFF，未打开、未改变唤醒状态，所有面板运行场景未覆盖 |

所有主应用目的地与取件码均有 Miuix Scaffold/TopAppBar；详情页与取件码有 BackHandler 和 Miuix IconButton。各 LazyColumn 只取 Scaffold 顶部 padding，并在最后加 navigationBarsPadding；主 Tab 额外预留 120 dp 底栏空间。代码没有业务 Slider、RadioButton、日期选择器、独立底部面板或 XML 表单；这些标为不适用，不虚构缺项。

### 2.2 顶层结构、弹窗、导航与宿主入口

| 组件/入口 | 定义/调用位置 | 状态与事件 / 覆盖 |
| --- | --- | --- |
| MainActivity / LauncherAlias / LSPosed settings | `ui/MainActivity.kt:7`；manifest:28,48 | setContent → RearScreenApp → MiuixTheme；两个入口共用页面；已运行主 Activity，未单独启动 alias/框架入口 |
| HomeScreen / MainContent / MainTabPage | `HomeScreen.kt:135,501,368` | detailPage、selected；AnimatedContent＋HorizontalPager；三个列表状态分开；已运行切页与详情返回，重建未测 |
| 固定 NavigationBar | `HomeScreen.kt:634–665` | 3 个 NavigationBarItem；用 Miuix selected/label；已临时关闭悬浮查看浅色，后恢复 |
| Miuix 悬浮栏 | `HomeScreen.kt:705–745` | 3 个 FloatingNavigationBarItem；空 label，见 A04；深色运行与 XML 已核实 |
| 自定义液态栏 | `components/FloatingBottomBar.kt:149,178`；`HomeScreen.kt:668` | 点击/拖动→selected→Pager；双层绘制、倾斜传感器、高光；已看浅深色与点击，复杂拖动/取消/多指未测 |
| 重启 WindowDialog | `HomeScreen.kt:748` | 动态循环4作用域复选项＋全选/确定；确定逐项异步 force-stop；已测显示、全选和取消，实际执行未测 |
| 更新 WindowDialog | `updater/UpdateDialog.kt:33`；`RearScreenApp.kt:326` | UpdateInfo 非空显示；滚动 Markdown 子集、取消、下载；静态已查，未出现新版本弹窗 |
| 兼容性 WindowDialog | `RearScreenApp.kt:335` | 属性值为0显示；确认/外部关闭；静态已查，当前设备未触发，未修改系统属性 |
| 3 个设置下拉菜单 | `ConfigPage.kt:40,72,93` | 选中索引对应 boolean/语言/ThemeMode；颜色菜单已打开；另2个未运行 |
| 背屏模块卡入口、明暗预览 | `hook/PanelAppCard.java:145,223`；`ModuleMain.java:606,669` | 造宿主数据项，PNG 资源路径→宿主绘制；点击接管到 SwipePanelHost；保存时去除模块项；本轮静态，历史截图不计覆盖 |
| 妙享背屏中的模块设置行 | `ModuleMain.java:1393,1519` | 宿主 controller/RecyclerView 行，图标/标题/Intent；开关读取 KEY_THEME_SETTINGS_SHORTCUT；静态，当前设置禁用，未开关或重启主题商店 |
| 妙享背屏 AI 入口 | `ModuleMain.java:1384,1556` | 宿主 controller/行→可解析的 AI Activity；静态；硬编码中文见 A09 |
| 小爱通知/灵动岛/背屏取件入口 | `hook/PickupCodeHook.java:286,592,642,731` | focused、focused night、expanded、tiny、tiny night、rear、rear AOD 共7个 RemoteViews key，另 collapsed JSON；保留宿主布局，替换文字和点击 PendingIntent；静态，未发布通知 |
| DebugHostProbe | `app/src/debug/java/.../DebugHostProbe.java:39` | 仅 debug 的 DUMP 权限广播测试入口，包含 panel 与合成通知操作；不是新页面；本轮读取未调用 |
| 系统界面 | Toast、浏览器、分享 chooser、输入法 | 不计项目自绘 Miuix 组件；本轮只出现输入法，没有实际分享、下载或 root 授权 |

### 2.3 共享封装、基础设施和资源

完整定义/全部调用行号在附录 A、B。关键关联如下：

- **CardBlock** → Miuix Card，13 个源码调用点，包含所有设置分组、应用项、许可项、取件组、二维码容器。**AboutArrowPreference** → ArrowPreference → openUrl / 页面回调，5 个调用点（许可处循环5次）。**AnimatedPreferenceVisibility** 两处，分别控制液态设置和应用名单入口。
- **InfoRow** 主页5处；**SettingsInfoRow** 无调用，仅检查定义，不纳入运行覆盖。**BlurredBar** 6 处，分别包住主 Tab 顶栏、设置、应用选择、许可、捐赠、取件码顶栏。
- **HomeStatusCard/StatusGlyph**：激活/未激活和动态取色条件；固定绿配色有明确设计注释，不作为风格违规。**AboutHeader** 本地 logo/版本。**MessageCard/AppIcon** 分别覆盖加载/空结果、Drawable/首字符占位。
- **MiuixStyleSwitch** 是宿主进程原生控件；在 `SwipePanelHost.kt:918` 构造，switchRow 三处调用（786、798、810），设置保存时禁用行与开关，失败恢复旧值并显示失败摘要。没有证据证明一次正常触摸同时触发父行和子开关两次写入，故不报“重复回调”。
- **视觉基础设施**：FloatingBottomBar 的 `rememberGravityRotatedHighlight` 两处；DampedDragAnimation、InteractiveHighlight、inspectDragGestures；CombinedBackdrop、InnerShadow、lens、vibrancy。均读取实现/调用，未将视觉 shader 的数学正确性、功耗和所有手势路径认定为验证通过。
- **资源**：默认/中文字符串、夜间/日间 window theme、locale-config、launcher/round launcher、about logo、panel icon、微信/支付宝图、FileProvider paths。未发现缺少对应中文键导致的构建错误；中文资源内 `Search` 等来自库默认语义，项目硬编码项见 A09。`Theme.MiBackscreen.Translucent` 仅夜间定义但当前无入口引用，不报实际崩溃。

### 2.4 配置链路核对

同名 UI/Hook 读写统一进入 PrefsBridge，以下键名、类型、默认值静态一致，没有发现 UI 写 A 键而 Hook 读 B 键的情况。**这不代表刷新/提交成功路径正确**，见 A01/A02。

| 键 / 类型 / 默认值 | UI 调用 | Hook/持久化消费 |
| --- | --- | --- |
| disable_long_press_edit / bool / true | FunctionPage:34，RearScreenApp:238；Panel:786 | PrefsBridge:170,326；ModuleMain:2865 |
| remove_wallpaper_limit / bool / true | FunctionPage:40，RearScreenApp:242；Panel:798 | PrefsBridge:178,330；ModuleMain:1062,1208；主 UI 还 force-stop 主题商店 |
| remove_app_card_limit / bool / true | FunctionPage:46，RearScreenApp:247；Panel:810 | PrefsBridge:194,338；ModuleMain:587–588；主 UI 还 force-stop 背屏 |
| fix_rear_screen_apply / bool / false | FunctionPage:52，RearScreenApp:252 | PrefsBridge:202,342；ModuleMain:820,849,1207,1601,1619,2223；主 UI force-stop 背屏/主题商店 |
| enable_app_card / bool / true | ConfigPage:51，RearScreenApp:277 | PrefsBridge:186,334；ModuleMain:587,606,670；主 UI force-stop 背屏 |
| disable_rear_screen_cover / bool / false | FunctionPage:58，RearScreenApp:286 | PrefsBridge:218,354；ModuleMain:384 |
| disable_double_tap_wake / bool / false | FunctionPage:64，RearScreenApp:290 | PrefsBridge:226,358；ModuleMain:410,440 |
| double_tap_wake_disabled_packages / String / 空 | AppPickerPage:181，RearScreenApp:294 | PrefsBridge:235,242,362,371；PackageListCodec；无名称/包名混存 |
| enable_pickup / bool / true | FunctionPage:86，RearScreenApp:282 | PrefsBridge:210,346；PickupCodeHook:122,219,292,445 |
| theme_settings_shortcut / bool / true | ConfigPage:40，RearScreenApp:319 | PrefsBridge:246,350；ModuleMain:1255；主 UI force-stop 主题商店 |
| pickup_island_selection / String / 空 | PickupCodesActivity:244 | PrefsBridge:256,264,286；PickupCodeHook:707；含驿站/历史格式兼容、空选标记，见 A02 |
| floating_nav_bar / false；liquid_glass / false；bottom_bar_blur / true；check_updates / true | ConfigPage；RearScreenApp:257–275 | 本地 module_config；前三项不由 Hook 消费；更新启动时检查 |
| theme_mode / Int / SYSTEM(0) | ThemePrefs:38–51 | 独立 app_ui_prefs；6种模式；两个 Activity 读取 |
| 语言 / 默认系统；隐藏桌面图标 / alias默认启用 | AppLanguage；LauncherIconController | LocaleManager、PackageManager；不是模块远程偏好 |

## 3. 问题列表

优先级按影响而非修复难度。代码片段均来自当前工作区；下列静态结论不冒充真机复现。

### A01 · P1 · 已确认问题（静态）· 配置改变后主界面没有持续同步，并漏刷新取件码总开关

**位置**：`ui/RearScreenApp.kt:73–127,147–183,282–285`；`bridge/PrefsBridge.java:452–464`；`app/ModuleApp.java:76–89`。

```kotlin
var disableLongPress by remember { mutableStateOf(PrefsBridge.readDisableLongPressForUi(context)) }
var enablePickup by remember { mutableStateOf(PrefsBridge.readEnablePickupForUi(context)) }
// DisposableEffect 只订阅 ModuleApp.addServiceListener(listener)
// listener 刷新若干值，但没有 readEnablePickupForUi / enablePickup = ...
```

**静态推导/触发**：主 Activity 已组合且服务绑定稳定→用户在背屏面板改动长按/壁纸上限/卡上限→Provider 将远程和本地值改好，但不发送服务绑定事件→主 UI 的 remember 值不变。返回前台也没有 ON_RESUME 重读/SharedPreferences listener。另一路：冷启动本地 enable_pickup 与远程不同，服务稍后绑定并完成同步，其余开关重新赋值，enablePickup 永远停留在首帧缓存，直到重建或用户修改。

**表现/影响**：功能页显示与 Hook 读取的真值不一致，用户据此操作可能覆盖已经保存的值；前者至少影响面板共用的3个开关，后者影响取件码总开关。未执行真实业务开关往返测试。

**预期/最小修复**：补全 enablePickup 的绑定刷新；在前台恢复时以统一数据源重读，或提供可观察的配置状态并监听已提交更新，避免只靠服务生命周期刷新。

**修后验证**：Mock 本地/远程相反且延迟绑定；主界面保活期间通过测试 Provider 成功写3个面板键，回前台核对 UI/远程/Hook 三者一致；快速写与重连交错时不得用过期快照覆盖新状态。

### A02 · P1 · 已确认问题（静态）· 取件码提交失败或编码超限仍显示新勾选

**位置**：`ui/pickup/PickupCodesActivity.kt:244–257`；`bridge/PrefsBridge.java:264–282`；`common/PickupCodes.java:122–145`。

```kotlin
selectedByGroup = nextSelections
PrefsBridge.writePickupIslandSelectionFromUi(context, updatedStored)
requestIslandRefresh(context)
```
```java
boolean committed = remote.edit().putString(..., value).commit();
if (committed) { /* 回写本地 */ } // false 不返回给页面、不提示
// upsertIslandSelection 超过 MAX_SELECTION_LENGTH 时原样返回 stored
```

**触发/推导**：令远程 commit 返回 false，页面仍先更新勾选并广播刷新；持久值未改，卡片仍按旧值显示。累计选择记录超过8192字符时编码函数返回旧字符串，也会写旧值却显示新选择。没有远程时只写待补交本地值，页面同样无法区分“待同步”与“已生效”。

**表现/预期**：选择状态是该功能的核心数据，不能静默显示保存成功；应在提交成功后更新并刷新，或失败明确回滚/标记待同步。同步 commit 还在主线程调用，实际卡顿时长另待性能验证。

**最小修复**：保存 API 返回明确结果，处理编码拒绝和 remote commit false；后台串行提交，成功后再确认界面、失败保留可重试状态；不改变存储协议。

**修后验证**：Mock commit 成功/失败/抛异常/服务迟到，构造超限记录；失败时勾选和卡片不出现静默分叉，重新打开仍与存储一致。此轮仅展示合成数据，未注入故障或修改真实选择。

### A03 · P2 · 已确认问题（真机＋版本源码）· 深色液态底栏图标和文字为黑色

**位置**：`ui/RearScreenApp.kt:220`；`ui/HomeScreen.kt:690–698`；`ui/components/FloatingBottomBar.kt:302–354`。第三方0.9.4 `theme/MiuixTheme.kt:54–75`、`theme/ContentColor.kt:21`、`basic/Icon.kt:56`。

```kotlin
MiuixTheme(colors = appColors) { ... }
Icon(imageVector = item.icon, contentDescription = ..., modifier = Modifier.size(24.dp))
Text(text = stringResource(item.labelRes), fontSize = 10.sp)
```

**复现**：悬浮底栏开、液态玻璃开、底栏模糊开→颜色模式深色→返回关于。未选中“主页/功能”图标和文本为黑色，在接近黑色底栏上难以辨认。[截图](/D:/Codex/MiBackscreenHuancun/evidence/ui-audit-2026-09-27/about-dark.png)，对应 `about-dark.xml`、`function-dark.png`。

**根因**：0.9.4 的 `MiuixTheme(colors=...)` 重载没有提供 LocalContentColor；其默认值是黑色。Card/TopAppBar 等内部自行提供或指定颜色，但自定义栏没有，Text/Icon 回落黑色。controller 重载的行为不同，不能套用其默认值。

**影响/预期**：自定义液态导航三个 Tab 的普通绘制层；深色/Monet 深色应使用可见的前景色；选中色通过另一个绘制层处理不解决未选中内容。

**最小修复**：在自定义栏提供匹配 surface 的 LocalContentColor，或显式传 Icon.tint/Text.color；保留原玻璃设计。

**修后验证**：普通深色、Monet深色及从浅色即时切换；模糊开/关、三个选中位置；同时确认选中层与普通层颜色不互相覆盖。

### A04 · P2 · 已确认问题（真机控件树＋源码）· 两种悬浮底栏丢失导航语义

**位置**：`ui/HomeScreen.kt:736–740`；`ui/components/FloatingBottomBar.kt:149–174,318–322`；0.9.4 `basic/NavigationBar.kt:377–417`。

```kotlin
FloatingNavigationBarItem(selected = ..., onClick = ..., icon = item.icon, label = "")
// 自定义：clickable(role = Role.Tab, onClick = onClick)，没有 selected 参数或语义
```

**证据/复现**：关闭液态、保留悬浮后，`native-floating-dark.xml` 中导航节点没有名称；该版本 FloatingNavigationBarItem 只画图标，label 专门用于 contentDescription，传空不是隐藏可见文字的必要条件。液态开启时 `initial.xml`、`function-dark.xml` 的三个 Tab 均没有 selected=true，虽然视觉高亮随页面变化。

**影响/预期**：TalkBack/自动化无法可靠识别普通悬浮项名称或液态栏当前选中页；父 Row 还提供空点击。没有开启 TalkBack 音频测试，因此不声称已听到某条播报。

**最小修复**：Miuix 悬浮项传 `stringResource(item.labelRes)`，不会新增可见标签；自定义项使用 `selectable(selected, role=Tab)` 或等价语义，父容器 selectableGroup，移除空点击语义；装饰性图标避免与文本重复命名。

**修后验证**：三种底栏在每个选中页检查可访问名称/唯一选中项；TalkBack、键盘焦点与点击切页一致。

### A05 · P2 · 已确认问题（真机控件树＋源码）· 无操作卡片仍可点击和长按

**位置**：`ui/components/PreferenceCards.kt:35–53`，全部13个调用点见附录A；`ui/home/HomeStatusCard.kt:69–83`；0.9.4 `basic/Card.kt:116–129`。

```kotlin
onClick = onClick ?: {},
onLongPress = {}
```

**复现/证据**：打开关于、许可、主页或捐赠，XML 中设置行之外还出现整张卡片的 clickable=true；主页状态卡、设备信息卡和二维码容器也可点击但没有动作。`initial.xml`、`license.xml`、`donate-alipay-dark.xml`。库以回调是否 null 决定 combinedClickable，即使 pressFeedbackType=None/showIndication=false 也保留点击/长按。

**影响/预期**：多余焦点/空操作，降低辅助技术可用性；不能由此推断子开关一定重复触发。无动作内容不应暴露动作。

**最小修复**：无操作时使用无事件 Card 重载，或保留可空 onClick/onLongPress；HomeStatusCard 同理。有设计意图的按压视觉可保留，但与虚假语义分开。

**修后验证**：核对全部 CardBlock 调用，卡片只读时无点击/长按，真正的 Arrow/Checkbox/Switch 子项仍可用，TalkBack 焦点不多出空卡片。

### A06 · P2 · 已确认问题（静态）· Activity 重建丢失页面位置和已合并的取件组

**位置**：`ui/HomeScreen.kt:173–174`；`ui/about/DonatePage.kt:59`；`ui/pickup/PickupCodesActivity.kt:75–82,152–191`。

```kotlin
var selected by remember { mutableStateOf(HomeNavigationPolicy.Tab.HOME) }
var detailPage by remember { mutableStateOf<DetailPage?>(null) }
// 取件页 onNewIntent: setIntent(intent); mergePayload(intent)
// onCreate: readPayload(intent)，没有恢复合并后的 payload/session
```

**触发/推导**：系统因旋转/语言变更/进程回收重建主 Activity 后 detailPage 被重置为null，详情页退出；selected 初始化为HOME，还会与Pager自身保存的状态进行同步，主Tab最终位置需运行核实。应用选择页虽然 query 用 rememberSaveable，父目的地未恢复，用户仍被带离该页。取件页先开A组再以同会话新 Intent 合并B组，当前 intent 已换成B；重建只从最后 intent 解析B，A丢失。捐赠 Tab 的本地选中索引也重新初始化为微信。

**证据**：`pickup-synthetic.xml`→`pickup-merged.xml` 已证明运行中可合并两组；**没有实际强制重建设备 Activity**，丢组结论依据重建路径。

**最小修复**：保存可序列化的选中目的地和必要页面状态；取件组/session 通过 SavedStateHandle 或 Bundle 按原协议恢复，避免只依赖最后一个 Intent。无需更换整套导航。

**修后验证**：ActivityScenario.recreate 与进程重建；7个主目的地/3个Tab位置及搜索状态；取件同站/异站、同会话合并后重建仍完整，异会话按设计替换。

### A07 · P2 · 已确认问题（静态＋设备命令帮助）· “系统”被当作应用包 force-stop

**位置**：`common/Constants.java:5`；`ui/HomeScreen.kt:127–131,779–788`；`ui/RearScreenApp.kt:303–310,367–375`。

```java
public static final String SYSTEM_PACKAGE = "system";
```
```kotlin
Runtime.getRuntime().exec(arrayOf("su", "-c", "am force-stop $packageName"))
// 仅检查进程退出码后显示“重启指令已发送”
```

**触发/依据**：重启对话框选择系统后传入 `system`。本机 `am help` 明确 force-stop 接收 PACKAGE，`pm list packages system` 无精确 `package:system` 项。这不是重启 system_server 或系统的命令；[AOSP 的 ActivityManagerShellCommand 实现](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/am/ActivityManagerShellCommand.java) 也将 force-stop 作为包操作处理，作为补充依据，未用主分支替代设备行为。

**影响/预期**：用户无法通过该项使系统作用域重新加载；真实返回码/Toast未执行验证，不声称设备已经显示成功。常规应用项实际也是停止包而非主动重新启动，文案应说明后续需重新打开。

**最小修复**：系统项与普通包操作分开，先给出准确的手动重启说明；若后续支持真实重启，应独立明确确认，不能静默换成重启设备。

**修后验证**：Mock command executor，系统分支不得发送 `am force-stop system`；普通包失败/超时能逐项反馈；真机重启留给另行授权的测试。

### A08 · P2 · 已确认问题（静态）· 链接/下载启动失败没有反馈且下载弹窗被关闭

**位置**：`ui/util/UrlOpener.kt:8–12`；`ui/components/PreferenceCards.kt:97–102`；`ui/updater/UpdateChecker.kt:135–140`；`ui/RearScreenApp.kt:328–331`；宿主设置行同类行为 `hook/ModuleMain.java:1545–1551,1568–1577`。

```kotlin
try { context.startActivity(intent) } catch (_: RuntimeException) { }
pendingUpdate?.let { UpdateChecker.openDownload(context, it) }
pendingUpdate = null
```

**触发/推导**：浏览器/处理器不可用或策略拒绝启动→异常吞掉→关于/许可链接无反应；更新下载仍关闭弹窗，用户既没有下载也没有错误说明。宿主设置行只写日志而无可见反馈。

**影响/预期**：外链或更新路径失败后应给出可理解的消息，下载弹窗保留重试。静态路径已确认，未在用户设备禁用浏览器制造故障。

**最小修复/验证**：openUrl/openDownload 返回结果；失败提示并保留更新对象；Mock startActivity 抛 ActivityNotFoundException/SecurityException，确认界面不假结束，可重试或复制链接。

### A09 · P3 · 已确认问题（静态）· 英文环境仍存在项目硬编码中文/英文语义

**位置**：`hook/ModuleMain.java:1386,1565` 的 `"AI 背屏"`；`common/PickupCodes.java:186,195` 空选择返回 `"取件码"`；`ui/SwipePanelHost.kt:1059–1068` 资源不可用时仅中文 fallback；`ui/about/AboutPage.kt:82` `"App Icon"`。

**触发**：英文宿主展示注入的AI行或全不选的通知标题；英文设备上面板读取模块资源失败；中文关于页由辅助技术读取Logo说明。

**表现/预期**：这些字符串不随项目/宿主语言变化。应将项目可见文案通过当前合适的资源上下文解析；品牌名 MiBackscreen、许可证名和包名不属于错误。

**最小修复/验证**：AI标题本地化；通知空选文字在调用方使用宿主 delivery 资源或模块双语资源；原生 fallback 对语言分支；装饰性Logo可空描述。验证 zh-CN/en、资源加载异常和空选择；本轮没有改系统/应用语言，也未触发通知空选。

### A10 · P2 · 疑似风险（有运行现象，根因未确认）· 应用列表受限时缺少可诊断状态，加载异常无捕获

**位置**：`ui/config/AppPickerPage.kt:89–116,168–175,251–270`。

```kotlin
apps = withContext(Dispatchers.IO) { loadInstalledApps(context) }
loading = false
// getInstalledApplications / loadLabel 没有对应 catch、error 状态或重试入口
```

**运行现象**：空查询时只显示2个已选包的首字母占位，未展示正常已安装应用/图标，见 `picker-attempt2.xml/png`；搜索无结果只提示“没有匹配的应用”。再次进入捕获到加载态（`nav-repro-picker.xml`）。读取 appops 看见厂商操作有拒绝记录，但操作编号未建立可信映射；尝试 GET_INSTALLED_APPS 名称被设备报 Unknown operation，**不能据此断言是某项权限导致，也未更改权限**。

**风险/影响**：查询返回受限集合、真正空集与查询失败没有可区分反馈；若调用抛异常，协程路径无捕获，可能退出页面/应用。当前不能把“可能崩溃”写成已复现崩溃。

**最小建议**：区分 loading/data/empty/error，捕获查询错误并提供重试；在已知厂商能力可核实时解释授权路径，保留丢失包可取消选择的原设计；不要凭空申请未核实权限。

**验证**：在测试环境返回空/部分集合及抛异常；本机在用户已授权的权限条件下重新查询，核实正常应用图标、长名称、即时保存；失败时不崩溃、不无限加载。

### A11 · P2 · 待运行验证 · 短窗口/大字体下状态卡和对话框可能裁切

**位置**：`ui/home/HomeStatusCard.kt:34,88,104–122` 固定118 dp、22/15 sp；`ui/HomeScreen.kt:748–791` 重启4行＋48 dp按钮，无滚动；`ui/RearScreenApp.kt:335–362` 兼容性长文＋48 dp按钮；`ui/updater/UpdateDialog.kt:45–71` 正文上限320 dp、按钮48 dp；`ui/HomeScreen.kt:710–711` 悬浮栏偏移。

**静态依据**：0.9.4 DialogContentLayout 提供 IME/导航栏 inset，但其 Column **不自动滚动**。用户内容可能消耗小横屏/分屏的可用高度，固定高度状态卡也可能放不下未激活长文。捐赠 `size(400.dp)`受父约束和ContentScale.Fit限制，不能仅据此断言溢出。

**实际/预期**：当前竖屏0.9字体的已展示页面未见上述裁切；未验证横屏、大字、分屏、更新和兼容性弹窗。预期所有重要文案和按钮可访问。

**最小建议/验证**：只有复现后按局部需求用最小高度、可滚动正文和固定操作区调整，不改整体设计。测试320 dp宽、短横屏、字体1.0/1.3/2.0、长英文、手势/三键导航与IME，截图记录按钮是否仍可达。

### A12 · P2 · 待运行验证 · 背屏底部22%触摸先被 Activity Hook 消费，可能挡住滚入该区的控件

**位置**：`ui/SwipePanelHost.kt:58,635–701,830–841`；`hook/ModuleMain.java:709–714`。

```kotlin
launcherGestureFromBottom = event.y > card.height * BOTTOM_SWIPE_REGION_RATIO
return launcherGestureFromBottom // DOWN 即返回 true，不等待超过滑动阈值
```

**推导**：面板有可滚动内容，短屏/大字体下第三行或滚动后的开关可能位于底部保留区；该区整条序列由 Activity 层消费，即便只是点击也到不了行。内部 SwipeDismissCard 等待 touchSlop 的实现不能补救更早的 Hook 截断。

**实际/影响**：本轮副屏 OFF，没有观察到具体控件被挡；不能声称当前默认布局第三个开关失效。潜在影响为某些缩放/滚动位置不可点击。

**最小建议/验证**：在976×596及放大字体下检查3行、滚动到底、上下滑取消；如复现，将关闭手势限制于独立手柄/空白区或确认滑动后才拦截，并为内容预留手势空间。保留已实现的排除区清理与宿主手势恢复。

### A13 · P3 · 优化建议 · 激活状态无法说明作用域/兼容性/实际 Hook 状态

**位置**：`ui/RearScreenApp.kt:125–127,178`；`ui/home/HomeStatusCard.kt:106–122`；`ui/util/SystemInfo.kt:29–38`；`ui/home/HomePage.kt:46–52`。

**依据**：模块状态只检查 `ModuleApp.getService()!=null`，缺目标包的版本值统一回落 `—`；没有获取作用域列表或 Hook 成功信号。未激活状态统一给启用/重启指引，无法区分没框架、未启用与绑定暂未完成。

**评价/预期**：当前“已激活”可以解释为服务已连接，不能直接认定是假报“所有Hook成功”；建议明确此含义，并对确知的目标包缺失/未知状态给出说明。配置保存与实际 Hook 生效应各自表达，不推断不可观测状态。

**最小建议/验证**：先增加不夸大的状态说明与缺包显示；若将来引入状态源，在测试环境覆盖框架缺失/未启用/无作用域/不兼容，未知不能显示为已验证兼容。未在实机改框架或作用域。

### A14 · P3 · 优化建议 · 日志导出的长任务只有瞬时提示

**位置**：`ui/home/HomePage.kt:35,57–74`；`ui/about/FeedbackLogExporter.kt:48–98,428–464`。

**依据**：generatingLog 阻止重入，但 ArrowPreference 外观没有 disabled/loading，只有开始时 Toast；导出顺序包含多项各自20秒超时的root采集。切详情页会移除 MainContent/HomePage 的协程作用域，离开时的结果/取消体验也需要另测。

**影响/预期**：较慢环境用户可能误以为无响应。并非已复现卡死；采集本身在IO线程、有超时，不能报主线程直接执行这些shell命令。

**建议/验证**：让行摘要持续展示进度或禁用状态，明确失败并可重试；Mock 慢/无root/返回失败、切页及重建。没有实际生成用户诊断包或调用分享器。

## 4. 实际执行验证与限制

### 4.1 构建/测试

先用实际安装的 Gradle 执行 `:app:tasks --all --console=plain` 确认任务，再执行：

```powershell
$env:JAVA_HOME = 'D:\Codex\MiBackscreenHuancun\tools\jdk-21.0.2'
$env:GRADLE_USER_HOME = 'D:\Codex\MiBackscreenHuancun\gradle'
& 'D:\Codex\MiBackscreenHuancun\tools\gradle-9.6.0\bin\gradle.bat' `
  :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --console=plain
& 'D:\Codex\MiBackscreenHuancun\tools\gradle-9.6.0\bin\gradle.bat' `
  :app:testDebugUnitTest --rerun --console=plain
& 'D:\Codex\MiBackscreenHuancun\tools\gradle-9.6.0\bin\gradle.bat' `
  :app:dependencies --configuration debugRuntimeClasspath --console=plain
```

| 验证 | 本轮结果 / 证据 | 能证明什么 |
| --- | --- | --- |
| Debug 构建＋Lint＋单测任务 | BUILD SUCCESSFUL，39s，52任务中5执行/47 up-to-date；[build.log](/D:/Codex/MiBackscreenHuancun/evidence/ui-audit-2026-09-27/build.log) | 当前源码可构建；部分产物增量复用，不伪称全部重新编译 |
| 强制重新执行单测任务 | BUILD SUCCESSFUL，2s；[test-rerun.log](/D:/Codex/MiBackscreenHuancun/evidence/ui-audit-2026-09-27/test-rerun.log)；13套件46项，0失败/错误/跳过 | 编码/会话/导航策略/版本检测等纯逻辑；不验证Compose画面、实际Binder失败和TalkBack |
| Lint | 0 errors、37 warnings、1 hint；[报告](/D:/Codex/MiBackscreenHuancun/evidence/ui-audit-2026-09-27/lint-results-debug.txt) | 包括StaticFieldLeak 3、SetTextI18n 2、ViewConstructor 1；未将警告全部升级为已证实UI缺陷 |
| 依赖解析 | 成功；[dependencies.log](/D:/Codex/MiBackscreenHuancun/evidence/ui-audit-2026-09-27/dependencies.log) | 确认Miuix/Compose实际解析版本；Material窗口尺寸传递依赖不等于业务混用 |
| Android UI 自动测试 | 未运行；无 `src/androidTest` 用例 | 未创建假UI测试，未把JVM布局常量断言称为截图验收 |
| release/API36 | 未构建/运行 | 不能由debug/API37覆盖混淆与最低支持版本 |

Lint 静态泄漏警告已结合 `SwipePanelHost.release/removePanel/clearReferences`、宿主 pause/destroy Hook 和 onDetach 复核：存在清理路径，不能仅凭 static View 字段断言必然泄漏；需保留生命周期运行验证。MiuixStyleSwitch 仅以代码构造，不因无XML构造器直接判运行错误。

辅助检查失败：GitHub v0.9.4 页面抓取失败后改从 Maven 当前版本源码包核实；厂商 appops 的 GET_INSTALLED_APPS 名称不被设备识别，退出码1，未据此作权限定性。未发生构建或单测失败。命令探测中不存在的本地第三方源码路径随后用实际文件定位，不影响项目文件审查。

### 4.2 运行证据索引

以下每个名称均有同名 `.png` 与 `.xml`（初始 `initial` 亦有）。目录见1.2；[设备操作记录](/D:/Codex/MiBackscreenHuancun/evidence/ui-audit-2026-09-27/device-actions.log) 记录测试脚本操作。早期命名为 `settings` 的截取实际还在关于，`picker-dark` 实际为主页；按内容认定，不能按文件名虚增页面覆盖。

| 证据名称 | 有效内容 |
| --- | --- |
| initial、license | 初始中文浅色关于、许可5项 |
| settings-open、color-menu、settings-dark | 设置正常列表、颜色下拉、深色即时切换 |
| about-dark、function-dark、function-stable | 深色内容、液态底栏前景和语义问题 |
| picker-dark | 实际为主页深色截图：设备信息、激活状态、日志/捐赠入口 |
| picker-attempt2、picker-keyboard、picker-empty | 应用占位列表、搜索聚焦、输入法/无匹配 |
| restart-dialog、restart-selected | 重启弹窗未选/全选；只取消，未执行 |
| donate-dark、donate-alipay-dark | 微信/支付宝二维码及Tab切换 |
| native-floating-dark、standard-navbar-light | Miuix悬浮空标签、普通底栏展示 |
| pickup-empty、pickup-synthetic、pickup-merged | 空数据、单组、同会话多驿站合成展示；未保存选择 |
| nav-repro-function、nav-repro-picker、nav-repro-return | 受控验证“功能→应用选择→一次返回”正常回到功能；不将早期瞬时页面变化报成导航故障 |
| appearance-restored-final、final-about-restored | 原外观值恢复，最终回关于页；仍是原业务设置 |

### 4.3 覆盖统计和所有未完成的运行维度

| 口径 | 发现 | 已静态检查 | 本轮至少一次运行/展示 | 未运行 |
| --- | ---: | ---: | ---: | ---: |
| 页面/面板 | 9 | 9 | 8（均仅部分状态） | 1：原生背屏面板 |
| 项目UI Composable定义（不含2个remember helper） | 28 | 28 | 26（含外壳，且只按至少一次可见/执行） | 2：无调用的SettingsInfoRow、未显示的UpdateDialog |
| 项目原生UI定义/构建函数 | 4 | 4 | 0 | buildPanel、switchRow、SwipeDismissCard、MiuixStyleSwitch；副屏OFF |
| Compose helper | 2 | 2 | 随液态栏运行，不视为独立UI验收 | 传感器/动画边界未穷尽 |
| WindowDialog实例定义 | 3 | 3 | 1：重启对话框部分交互 | 2：更新、兼容性 |
| 设置下拉实例 | 3 | 3 | 1：颜色菜单 | 2：入口、语言 |
| 底栏样式 | 3 | 3 | 3（普通浅色、悬浮深色、液态浅深色） | 每种全部组合仍未覆盖 |
| 宿主注入入口/展示族 | 4 | 4 | 0 | 卡片、模块设置行、AI行、通知视图 |
| Miuix可见组件种类/源码调用点 | 20 / 86 | 20 / 86 | 未以种类被执行推算所有调用点通过 | 全调用位置见附录B，部分条件未触发 |

必须补测的内容（本轮全部未验证，不能隐含为通过）：

1. **屏幕与系统**：小屏/大屏、横屏、分屏/自由窗口、字体放大、显示缩放、三键导航、主屏横向cutout、安全区变化、API36、其他固件及release混淆包。
2. **主题/语言**：中文当前浅色与应用强制深色之外的完整页面矩阵、Monet三模式、系统主题变化、英文页面、语言变更引发重建；XML window背景与强制应用主题在冷启动时的闪色。
3. **主业务设置**：所有Hook键实际写入及失败、跨面板/前台同步、连续快速操作、服务断开重连、作用域重启生效。没有在用户实机更改这些值。
4. **应用选择**：正常全应用列表与Drawable图标、权限受限根因、改选/取消、真实保存失败、大量应用性能、键盘下列表末项可达性、清空/IME搜索动作完整体验。查询无匹配与键盘展示仅是其中一部分。
5. **取件码**：选择写入、全选/全不选持久化、提交失败、慢Binder/磁盘、重建/进程重启、确认取件回调、冷服务绑定、真实通知7个RemoteViews分支/JSON、超64合并码及超长存储、多页状态竞争。当前合成展示不算完整端到端验收。
6. **背屏面板**：主入口卡、3开关保存成功/拒绝、滚动/触摸拦截、底部上滑取消、onPause/onDestroy/onDetach清理、版本替换提示、浅深/语言/放大字体/洞区。副屏OFF，本轮未唤醒、未引用历史通过记录。`beginOpeningDrag/updateOpeningDrag/finishOpeningDrag` 等保留接口不等于当前存在可达的下拉入口，当前生产入口是卡片点击。
7. **条件弹窗与外部流程**：更新检查新版本/无网络/取消/下载失败、兼容性属性异常弹窗、日志导出各失败路径/分享chooser、缺浏览器、隐藏桌面图标、缺目标包、没框架/未启用/未配置作用域、真正重启系统。
8. **辅助技术/生命周期**：TalkBack实际播报、键盘/开关控制焦点、复杂拖动/多指、所有页面Activity重建/进程死亡、长时间泄漏/功耗。XML语义检查不能替代TalkBack使用测试。

## 5. 修复顺序

1. **A01、A02**：先让保存结果和屏幕显示一致，补齐跨入口/服务迟到刷新，再测失败与重建，避免后续视觉验收建立在错误状态上。
2. **A03、A04、A05**：局部修复深色前景色、Tab名称/选中语义和空卡片动作。均有当前版本API依据，无需更换Miuix或重写UI。
3. **A06、A07、A08**：补页面恢复、系统作用域操作语义和外部启动失败反馈。
4. **A10、A11、A12**：用可控权限/数据、短窗口/字体和副屏环境定性，再决定最小改动；不要将疑似项直接列为已修复。
5. **A09、A13、A14**：补语言兜底、状态说明与持续任务反馈。

本报告不包含代码修复；现有未提交工作保持原状。以下附录保留全部自有Composable定义及Miuix调用位置，作为后续逐项复测的索引。

<!-- GENERATED_INVENTORY_APPENDIX -->

## 附录 A. 全部项目 Composable 定义与调用位置
对去除字符串/注释后的源码建立定义与真实调用点索引，再与页面分派/封装实现逐项核对。项目不存在UI别名导入。定义共30个：28个UI函数（含1个无调用定义）＋2个remember视觉helper。下面“运行”只指至少一次执行/展示，不表示全部状态、所有动态实例都通过。
| 定义 | 定义位置 | 全部源码调用位置 | 关联与检查状态 |
| --- | --- | --- | --- |
| AboutPage | [ui/about/AboutPage.kt:31](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/about/AboutPage.kt:31) | [ui/HomeScreen.kt:485](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/HomeScreen.kt:485) | 关于 → AboutHeader / CardBlock / AboutArrowPreference / SmallTitle；已静态；部分运行 |
| AboutHeader | [ui/about/AboutPage.kt:64](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/about/AboutPage.kt:64) | [ui/about/AboutPage.kt:32](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/about/AboutPage.kt:32) | 关于 → Image / Text；logo / 版本；已静态；部分运行 |
| DonatePage | [ui/about/DonatePage.kt:54](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/about/DonatePage.kt:54) | [ui/HomeScreen.kt:217](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/HomeScreen.kt:217) | 捐赠 → Scaffold / TopAppBar / TabRow / CardBlock / Image；已静态；部分运行 |
| LicensePage | [ui/about/LicensePage.kt:72](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/about/LicensePage.kt:72) | [ui/HomeScreen.kt:216](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/HomeScreen.kt:216) | 许可 → Scaffold / TopAppBar / CardBlock / AboutArrowPreference，5项数据循环；已静态；部分运行 |
| BlurredBar | [ui/components/BlurredBar.kt:19](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/components/BlurredBar.kt:19) | [ui/about/DonatePage.kt:73](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/about/DonatePage.kt:73), [ui/about/LicensePage.kt:86](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/about/LicensePage.kt:86), [ui/config/AppPickerPage.kt:123](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/config/AppPickerPage.kt:123), [ui/HomeScreen.kt:306](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/HomeScreen.kt:306), [ui/HomeScreen.kt:401](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/HomeScreen.kt:401), [ui/pickup/PickupCodesActivity.kt:276](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/pickup/PickupCodesActivity.kt:276) | 6处顶栏容器 → Box + progressiveTextureBlur；不接收业务事件；已静态；部分运行 |
| rememberGravityRotatedHighlight | [ui/components/FloatingBottomBar.kt:113](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/components/FloatingBottomBar.kt:113) | [ui/components/FloatingBottomBar.kt:293](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/components/FloatingBottomBar.kt:293), [ui/components/FloatingBottomBar.kt:294](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/components/FloatingBottomBar.kt:294) | 液态栏 helper → rememberDeviceTilt，重算光源位置；已静态；随液态栏执行，非独立验收 |
| FloatingBottomBarItem | [ui/components/FloatingBottomBar.kt:149](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/components/FloatingBottomBar.kt:149) | [ui/HomeScreen.kt:684](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/HomeScreen.kt:684) | 液态栏3个Tab → Column.clickable(Role.Tab)；A04；已静态；部分运行 |
| FloatingBottomBar | [ui/components/FloatingBottomBar.kt:178](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/components/FloatingBottomBar.kt:178) | [ui/HomeScreen.kt:676](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/HomeScreen.kt:676) | 主Tab液态导航 → 多层Row/Backdrop/拖动/高光；A03/A04；已静态；部分运行 |
| InfoRow | [ui/components/InfoRows.kt:21](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/components/InfoRows.kt:21) | [ui/home/HomePage.kt:43](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/home/HomePage.kt:43), [ui/home/HomePage.kt:44](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/home/HomePage.kt:44), [ui/home/HomePage.kt:45](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/home/HomePage.kt:45), [ui/home/HomePage.kt:46](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/home/HomePage.kt:46), [ui/home/HomePage.kt:50](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/home/HomePage.kt:50) | 主页5行设备信息 → Column / Miuix Text；已静态；部分运行 |
| SettingsInfoRow | [ui/components/InfoRows.kt:51](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/components/InfoRows.kt:51) | 无 | 无调用 → Row / Miuix Text；不得算页面覆盖；已静态；未运行（无调用） |
| CardBlock | [ui/components/PreferenceCards.kt:28](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/components/PreferenceCards.kt:28) | [ui/about/AboutPage.kt:34](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/about/AboutPage.kt:34), [ui/about/AboutPage.kt:48](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/about/AboutPage.kt:48), [ui/about/DonatePage.kt:124](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/about/DonatePage.kt:124), [ui/about/LicensePage.kt:121](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/about/LicensePage.kt:121), [ui/config/AppPickerPage.kt:176](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/config/AppPickerPage.kt:176), [ui/config/AppPickerPage.kt:209](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/config/AppPickerPage.kt:209), [ui/config/ConfigPage.kt:39](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/config/ConfigPage.kt:39), [ui/config/ConfigPage.kt:92](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/config/ConfigPage.kt:92), [ui/function/FunctionPage.kt:33](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/function/FunctionPage.kt:33), [ui/home/HomePage.kt:42](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/home/HomePage.kt:42), [ui/home/HomePage.kt:56](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/home/HomePage.kt:56), [ui/home/HomePage.kt:79](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/home/HomePage.kt:79), [ui/pickup/PickupCodesActivity.kt:371](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/pickup/PickupCodesActivity.kt:371) | 7个主目的地及取件组 → Miuix Card；A05；13源码调用点≠13运行实例；已静态；部分运行 |
| AnimatedPreferenceVisibility | [ui/components/PreferenceCards.kt:61](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/components/PreferenceCards.kt:61) | [ui/config/ConfigPage.kt:114](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/config/ConfigPage.kt:114), [ui/function/FunctionPage.kt:70](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/function/FunctionPage.kt:70) | 设置液态开关、功能应用入口 → AnimatedVisibility；已静态；部分运行 |
| AboutArrowPreference | [ui/components/PreferenceCards.kt:85](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/components/PreferenceCards.kt:85) | [ui/about/AboutPage.kt:35](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/about/AboutPage.kt:35), [ui/about/AboutPage.kt:40](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/about/AboutPage.kt:40), [ui/about/AboutPage.kt:49](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/about/AboutPage.kt:49), [ui/about/AboutPage.kt:54](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/about/AboutPage.kt:54), [ui/about/LicensePage.kt:122](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/about/LicensePage.kt:122) | 关于4处、许可循环 → Miuix ArrowPreference → 回调/openUrl；A08；已静态；部分运行 |
| AppPickerPage | [ui/config/AppPickerPage.kt:74](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/config/AppPickerPage.kt:74) | [ui/HomeScreen.kt:218](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/HomeScreen.kt:218) | 功能二级页 → InputField / CheckboxPreference / AppIcon / MessageCard；A10；已静态；部分运行 |
| MessageCard | [ui/config/AppPickerPage.kt:208](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/config/AppPickerPage.kt:208) | [ui/config/AppPickerPage.kt:170](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/config/AppPickerPage.kt:170), [ui/config/AppPickerPage.kt:173](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/config/AppPickerPage.kt:173) | 应用选择加载/空结果 → CardBlock / Text；已静态；部分运行 |
| AppIcon | [ui/config/AppPickerPage.kt:219](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/config/AppPickerPage.kt:219) | [ui/config/AppPickerPage.kt:192](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/config/AppPickerPage.kt:192) | 应用选择动态行 → AndroidView(ImageView) 或首字母 Text；仅占位分支运行；已静态；部分运行 |
| ConfigPage | [ui/config/ConfigPage.kt:18](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/config/ConfigPage.kt:18) | [ui/HomeScreen.kt:338](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/HomeScreen.kt:338) | 设置 → 6 SwitchPreference + 3 WindowDropdownPreference + CardBlock；已静态；部分运行 |
| FunctionPage | [ui/function/FunctionPage.kt:15](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/function/FunctionPage.kt:15) | [ui/HomeScreen.kt:465](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/HomeScreen.kt:465) | 功能 → 7 SwitchPreference + 动态 ArrowPreference；PrefsBridge 状态提升；已静态；部分运行 |
| HomePage | [ui/home/HomePage.kt:28](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/home/HomePage.kt:28) | [ui/HomeScreen.kt:458](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/HomeScreen.kt:458) | 主页 → HomeStatusCard + 5 InfoRow + 2 ArrowPreference；日志/捐赠；已静态；部分运行 |
| HomeStatusCard | [ui/home/HomeStatusCard.kt:46](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/home/HomeStatusCard.kt:46) | [ui/home/HomePage.kt:37](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/home/HomePage.kt:37) | 主页状态 → Miuix Card / Text / StatusGlyph；A05/A11；已静态；部分运行 |
| StatusGlyph | [ui/home/HomeStatusCard.kt:130](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/home/HomeStatusCard.kt:130) | [ui/home/HomeStatusCard.kt:95](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/home/HomeStatusCard.kt:95) | 状态卡 → Canvas，正常/错误图案；仅正常图案运行；已静态；部分运行 |
| HomeScreen | [ui/HomeScreen.kt:135](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/HomeScreen.kt:135) | [ui/RearScreenApp.kt:221](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/RearScreenApp.kt:221) | 主Activity → AnimatedContent/目的地状态/三列表状态；A06；已静态；部分运行 |
| SettingsPage | [ui/HomeScreen.kt:272](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/HomeScreen.kt:272) | [ui/HomeScreen.kt:195](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/HomeScreen.kt:195) | 设置外壳 → Scaffold / TopAppBar / BlurredBar / LazyColumn / ConfigPage；已静态；部分运行 |
| MainTabPage | [ui/HomeScreen.kt:368](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/HomeScreen.kt:368) | [ui/HomeScreen.kt:601](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/HomeScreen.kt:601) | 3个主Tab共用外壳 → Scaffold / TopAppBar / LazyColumn / 顶部动作；已静态；部分运行 |
| MainContent | [ui/HomeScreen.kt:501](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/HomeScreen.kt:501) | [ui/HomeScreen.kt:223](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/HomeScreen.kt:223) | 主导航 → HorizontalPager / 3种底栏 / 重启WindowDialog；已静态；部分运行 |
| rememberCombinedBackdrop | [ui/liquid/CombinedBackdrop.kt:38](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/liquid/CombinedBackdrop.kt:38) | [ui/components/FloatingBottomBar.kt:296](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/components/FloatingBottomBar.kt:296) | 液态栏 helper → 合并两背景，不是独立可见控件；已静态；随液态栏执行，非独立验收 |
| PickupCodesPage | [ui/pickup/PickupCodesActivity.kt:229](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/pickup/PickupCodesActivity.kt:229) | [ui/pickup/PickupCodesActivity.kt:100](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/pickup/PickupCodesActivity.kt:100) | 取件Activity → Scaffold / TopAppBar / 全选 / 动态PickupGroupBlock / 空页；已静态；部分运行 |
| PickupGroupBlock | [ui/pickup/PickupCodesActivity.kt:352](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/pickup/PickupCodesActivity.kt:352) | [ui/pickup/PickupCodesActivity.kt:334](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/pickup/PickupCodesActivity.kt:334) | 取件驿站 → SmallTitle / Text / CardBlock / CheckboxPreference，动态码循环；已静态；部分运行 |
| RearScreenApp | [ui/RearScreenApp.kt:55](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/RearScreenApp.kt:55) | [ui/MainActivity.kt:11](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/MainActivity.kt:11) | MainActivity → 状态根/MiuixTheme/HomeScreen/更新与兼容性弹窗；已静态；部分运行 |
| UpdateDialog | [ui/updater/UpdateDialog.kt:33](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/updater/UpdateDialog.kt:33) | [ui/RearScreenApp.kt:326](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/RearScreenApp.kt:326) | 根层条件弹窗 → WindowDialog / Text / TextButton / Button；本轮没有UpdateInfo；已静态；未显示（新版本条件未触发） |

## 附录 B. Miuix 底层组件全部源码调用点
20种可见组件、86个源码调用点；另有MiuixScrollBehavior的6个构造点单列，不算可见控件。循环产生的3个Tab、5个许可证项、4个作用域、任意应用/取件码实例不按静态调用点展开。MiuixTheme有2个主题入口，另见1.1；Colors/Defaults/blur/squircle API是基础设施，不另凑控件数。每一行均已核对调用及封装。
| 底层组件（均为top.yukonga.miuix.kmp） | 源码调用点数 | 全部调用位置 | 运行限制 |
| --- | ---: | --- | --- |
| basic.Button | 3 | [ui/HomeScreen.kt:779](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/HomeScreen.kt:779), [ui/RearScreenApp.kt:353](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/RearScreenApp.kt:353), [ui/updater/UpdateDialog.kt:67](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/updater/UpdateDialog.kt:67) | 重启按钮只展示/全选；确认未执行；更新与兼容性分支未显示；取件全选未写入 |
| basic.Card | 2 | [ui/components/PreferenceCards.kt:35](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/components/PreferenceCards.kt:35), [ui/home/HomeStatusCard.kt:69](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/home/HomeStatusCard.kt:69) | 本轮部分展示/状态，详见页面清单与4.3 |
| basic.FloatingNavigationBar | 1 | [ui/HomeScreen.kt:713](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/HomeScreen.kt:713) | 本轮部分展示/状态，详见页面清单与4.3 |
| basic.FloatingNavigationBarItem | 1 | [ui/HomeScreen.kt:736](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/HomeScreen.kt:736) | 本轮部分展示/状态，详见页面清单与4.3 |
| basic.Icon | 7 | [ui/about/DonatePage.kt:81](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/about/DonatePage.kt:81), [ui/about/LicensePage.kt:94](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/about/LicensePage.kt:94), [ui/config/AppPickerPage.kt:131](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/config/AppPickerPage.kt:131), [ui/HomeScreen.kt:314](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/HomeScreen.kt:314), [ui/HomeScreen.kt:420](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/HomeScreen.kt:420), [ui/HomeScreen.kt:690](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/HomeScreen.kt:690), [ui/pickup/PickupCodesActivity.kt:284](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/pickup/PickupCodesActivity.kt:284) | 本轮部分展示/状态，详见页面清单与4.3 |
| basic.IconButton | 5 | [ui/about/DonatePage.kt:80](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/about/DonatePage.kt:80), [ui/about/LicensePage.kt:93](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/about/LicensePage.kt:93), [ui/config/AppPickerPage.kt:130](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/config/AppPickerPage.kt:130), [ui/HomeScreen.kt:313](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/HomeScreen.kt:313), [ui/pickup/PickupCodesActivity.kt:283](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/pickup/PickupCodesActivity.kt:283) | 本轮部分展示/状态，详见页面清单与4.3 |
| basic.InputField | 1 | [ui/config/AppPickerPage.kt:156](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/config/AppPickerPage.kt:156) | 本轮部分展示/状态，详见页面清单与4.3 |
| basic.MiuixScrollBehavior | 6 | [ui/about/DonatePage.kt:60](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/about/DonatePage.kt:60), [ui/about/LicensePage.kt:73](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/about/LicensePage.kt:73), [ui/config/AppPickerPage.kt:80](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/config/AppPickerPage.kt:80), [ui/HomeScreen.kt:293](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/HomeScreen.kt:293), [ui/HomeScreen.kt:395](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/HomeScreen.kt:395), [ui/pickup/PickupCodesActivity.kt:231](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/pickup/PickupCodesActivity.kt:231) | 非可见组件；6处随页面执行，滚动边界未全测 |
| basic.NavigationBar | 1 | [ui/HomeScreen.kt:635](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/HomeScreen.kt:635) | 本轮部分展示/状态，详见页面清单与4.3 |
| basic.NavigationBarItem | 1 | [ui/HomeScreen.kt:658](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/HomeScreen.kt:658) | 本轮部分展示/状态，详见页面清单与4.3 |
| basic.Scaffold | 6 | [ui/about/DonatePage.kt:69](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/about/DonatePage.kt:69), [ui/about/LicensePage.kt:82](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/about/LicensePage.kt:82), [ui/config/AppPickerPage.kt:119](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/config/AppPickerPage.kt:119), [ui/HomeScreen.kt:302](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/HomeScreen.kt:302), [ui/HomeScreen.kt:397](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/HomeScreen.kt:397), [ui/pickup/PickupCodesActivity.kt:272](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/pickup/PickupCodesActivity.kt:272) | 本轮部分展示/状态，详见页面清单与4.3 |
| basic.SmallTitle | 4 | [ui/about/AboutPage.kt:47](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/about/AboutPage.kt:47), [ui/config/ConfigPage.kt:38](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/config/ConfigPage.kt:38), [ui/config/ConfigPage.kt:91](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/config/ConfigPage.kt:91), [ui/pickup/PickupCodesActivity.kt:359](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/pickup/PickupCodesActivity.kt:359) | 本轮部分展示/状态，详见页面清单与4.3 |
| basic.TabRow | 1 | [ui/about/DonatePage.kt:110](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/about/DonatePage.kt:110) | 本轮部分展示/状态，详见页面清单与4.3 |
| basic.Text | 18 | [ui/about/AboutPage.kt:88](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/about/AboutPage.kt:88), [ui/about/AboutPage.kt:94](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/about/AboutPage.kt:94), [ui/components/InfoRows.kt:34](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/components/InfoRows.kt:34), [ui/components/InfoRows.kt:39](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/components/InfoRows.kt:39), [ui/components/InfoRows.kt:64](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/components/InfoRows.kt:64), [ui/components/InfoRows.kt:70](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/components/InfoRows.kt:70), [ui/config/AppPickerPage.kt:210](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/config/AppPickerPage.kt:210), [ui/config/AppPickerPage.kt:242](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/config/AppPickerPage.kt:242), [ui/home/HomeStatusCard.kt:106](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/home/HomeStatusCard.kt:106), [ui/home/HomeStatusCard.kt:113](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/home/HomeStatusCard.kt:113), [ui/HomeScreen.kt:695](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/HomeScreen.kt:695), [ui/HomeScreen.kt:791](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/HomeScreen.kt:791), [ui/pickup/PickupCodesActivity.kt:324](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/pickup/PickupCodesActivity.kt:324), [ui/pickup/PickupCodesActivity.kt:363](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/pickup/PickupCodesActivity.kt:363), [ui/RearScreenApp.kt:344](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/RearScreenApp.kt:344), [ui/RearScreenApp.kt:359](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/RearScreenApp.kt:359), [ui/updater/UpdateDialog.kt:45](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/updater/UpdateDialog.kt:45), [ui/updater/UpdateDialog.kt:71](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/updater/UpdateDialog.kt:71) | UpdateDialog及SettingsInfoRow内调用未展示；其余仅已列条件 |
| basic.TextButton | 3 | [ui/HomeScreen.kt:771](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/HomeScreen.kt:771), [ui/pickup/PickupCodesActivity.kt:293](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/pickup/PickupCodesActivity.kt:293), [ui/updater/UpdateDialog.kt:62](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/updater/UpdateDialog.kt:62) | 重启按钮只展示/全选；确认未执行；更新与兼容性分支未显示；取件全选未写入 |
| basic.TopAppBar | 6 | [ui/about/DonatePage.kt:74](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/about/DonatePage.kt:74), [ui/about/LicensePage.kt:87](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/about/LicensePage.kt:87), [ui/config/AppPickerPage.kt:124](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/config/AppPickerPage.kt:124), [ui/HomeScreen.kt:307](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/HomeScreen.kt:307), [ui/HomeScreen.kt:402](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/HomeScreen.kt:402), [ui/pickup/PickupCodesActivity.kt:277](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/pickup/PickupCodesActivity.kt:277) | 本轮部分展示/状态，详见页面清单与4.3 |
| preference.ArrowPreference | 4 | [ui/components/PreferenceCards.kt:93](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/components/PreferenceCards.kt:93), [ui/function/FunctionPage.kt:72](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/function/FunctionPage.kt:72), [ui/home/HomePage.kt:57](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/home/HomePage.kt:57), [ui/home/HomePage.kt:80](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/home/HomePage.kt:80) | 本轮部分展示/状态，详见页面清单与4.3 |
| preference.CheckboxPreference | 3 | [ui/config/AppPickerPage.kt:177](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/config/AppPickerPage.kt:177), [ui/HomeScreen.kt:754](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/HomeScreen.kt:754), [ui/pickup/PickupCodesActivity.kt:374](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/pickup/PickupCodesActivity.kt:374) | 本轮部分展示/状态，详见页面清单与4.3 |
| preference.SwitchPreference | 13 | [ui/config/ConfigPage.kt:51](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/config/ConfigPage.kt:51), [ui/config/ConfigPage.kt:57](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/config/ConfigPage.kt:57), [ui/config/ConfigPage.kt:62](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/config/ConfigPage.kt:62), [ui/config/ConfigPage.kt:108](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/config/ConfigPage.kt:108), [ui/config/ConfigPage.kt:115](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/config/ConfigPage.kt:115), [ui/config/ConfigPage.kt:122](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/config/ConfigPage.kt:122), [ui/function/FunctionPage.kt:34](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/function/FunctionPage.kt:34), [ui/function/FunctionPage.kt:40](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/function/FunctionPage.kt:40), [ui/function/FunctionPage.kt:46](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/function/FunctionPage.kt:46), [ui/function/FunctionPage.kt:52](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/function/FunctionPage.kt:52), [ui/function/FunctionPage.kt:58](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/function/FunctionPage.kt:58), [ui/function/FunctionPage.kt:64](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/function/FunctionPage.kt:64), [ui/function/FunctionPage.kt:86](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/function/FunctionPage.kt:86) | 本轮部分展示/状态，详见页面清单与4.3 |
| preference.WindowDropdownPreference | 3 | [ui/config/ConfigPage.kt:40](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/config/ConfigPage.kt:40), [ui/config/ConfigPage.kt:72](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/config/ConfigPage.kt:72), [ui/config/ConfigPage.kt:93](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/config/ConfigPage.kt:93) | 3行已展示；仅颜色菜单展开 |
| window.WindowDialog | 3 | [ui/HomeScreen.kt:748](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/HomeScreen.kt:748), [ui/RearScreenApp.kt:335](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/RearScreenApp.kt:335), [ui/updater/UpdateDialog.kt:38](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/updater/UpdateDialog.kt:38) | 仅HomeScreen重启弹窗显示；另2处未显示 |

## 附录 C. 其他UI控件、事件与资源覆盖索引
以下为没有包含在Miuix调用表中的布局/绘制/宿主组件；它们不因包名不属于Miuix就被判违规。
| 定义/类别 | 定义与全部相关调用位置 | 检查状态 / 未覆盖原因 |
| --- | --- | --- |
| 原生MiuixStyleSwitch | `ui/MiuixStyleSwitch.kt:24`；唯一构造 `ui/SwipePanelHost.kt:918`；switchRow调用786/798/810 | 静态含绘制、touch cancel、performClick、checkable/checked语义、onDetach取消动画；副屏OFF未运行。49×28dp本体但有整行点击，不能仅据本体尺寸报触摸不可用 |
| SwipeDismissCard/根层/滚动/标题摘要 | `ui/SwipePanelHost.kt:716,735,749,773,830,866,890,907,1075` | 静态；3开关/旧版本提示/保存失败摘要/关闭手势未运行；A12 |
| ImageView互操作 | `ui/config/AppPickerPage.kt:229,231` → AppIcon:219 | 静态；用于Drawable适配而非替代设置控件；本机无正常应用Drawable分支 |
| Compose Image | `ui/about/AboutPage.kt:80`；`ui/about/DonatePage.kt:131`（qr资源选择48–50） | 已展示；不因基础Image而判混用；图标描述见A09 |
| Canvas | `ui/home/HomeStatusCard.kt:135` | 正常勾图运行；错误叉图未运行，固定绿有设计依据 |
| AnimatedContent / HorizontalPager | `ui/HomeScreen.kt:179,580` | 分派7目的地/3Tab；受控详情返回通过；未测快速往返/拖动取消/重建 |
| LazyColumn | `ui/HomeScreen.kt:326,442`；`ui/about/DonatePage.kt:93`；`ui/about/LicensePage.kt:106`；`ui/config/AppPickerPage.kt:143`；`ui/pickup/PickupCodesActivity.kt:307` | 6个源码调用点已检查；列表动态keys见源码；大数据、短窗口末项及IME完整矩阵未测 |
| 平台提示/外部启动 | Toast：`ui/RearScreenApp.kt:65,306`、`ui/home/HomePage.kt:63,72`；URL：`ui/util/UrlOpener.kt:8`；分享：`ui/about/FeedbackLogExporter.kt:111`；下载：`ui/updater/UpdateChecker.kt:135` | 静态；未触发真实分享/下载/root/作用域停止，A07/A08/A14 |
| 绘制和手势基础设施 | `ui/animation/DampedDragAnimation.kt:19`、`DragGestureInspector.kt:15`、`InteractiveHighlight.kt:22`；`ui/liquid/CombinedBackdrop.kt:16`、`InnerShadow.kt:33`、`Lens.kt:13`、`Vibrancy.kt:9`；使用方为 `FloatingBottomBar.kt` | 移植后项目维护代码单列；静态＋随液态栏绘制；并非独立页面/库源码整体审计。复杂拖动、传感器边界、功耗未验证 |
| 宿主视图绑定 | `hook/ModuleMain.java:1519,1556` 设置行；`hook/PanelAppCard.java:145` 卡片数据/图；`hook/PickupCodeHook.java:286,642` RemoteViews | 项目注入逻辑静态已查；宿主内部控件定义不并入自有组件统计，所有宿主运行分支待测 |

### C.1 UI目录逐文件检查状态

| 文件 | 静态检查内容 | 运行说明 |
| --- | --- | --- |
| [ui/about/AboutPage.kt:1](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/about/AboutPage.kt:1) | 定义、全部调用、布局、主题、事件/状态 | 至少部分展示；未覆盖条件见4.3 |
| [ui/about/DonatePage.kt:1](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/about/DonatePage.kt:1) | 定义、全部调用、布局、主题、事件/状态 | 至少部分展示；未覆盖条件见4.3 |
| [ui/about/FeedbackLogExporter.kt:1](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/about/FeedbackLogExporter.kt:1) | UI入口、IO任务、结果/分享、超时；采集内容为辅助路径 | 未实际导出/分享 |
| [ui/about/LicensePage.kt:1](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/about/LicensePage.kt:1) | 定义、全部调用、布局、主题、事件/状态 | 至少部分展示；未覆盖条件见4.3 |
| [ui/animation/DampedDragAnimation.kt:1](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/animation/DampedDragAnimation.kt:1) | 绘制/动画/手势实现与调用 | 随液态栏部分执行，边界/性能未验收 |
| [ui/animation/DragGestureInspector.kt:1](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/animation/DragGestureInspector.kt:1) | 绘制/动画/手势实现与调用 | 随液态栏部分执行，边界/性能未验收 |
| [ui/animation/InteractiveHighlight.kt:1](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/animation/InteractiveHighlight.kt:1) | 绘制/动画/手势实现与调用 | 随液态栏部分执行，边界/性能未验收 |
| [ui/components/BlurredBar.kt:1](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/components/BlurredBar.kt:1) | 定义、全部调用、布局、主题、事件/状态 | 至少部分展示；未覆盖条件见4.3 |
| [ui/components/FloatingBottomBar.kt:1](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/components/FloatingBottomBar.kt:1) | 定义、全部调用、布局、主题、事件/状态 | 至少部分展示；未覆盖条件见4.3 |
| [ui/components/InfoRows.kt:1](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/components/InfoRows.kt:1) | 定义、全部调用、布局、主题、事件/状态 | 至少部分展示；未覆盖条件见4.3 |
| [ui/components/PreferenceCards.kt:1](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/components/PreferenceCards.kt:1) | 定义、全部调用、布局、主题、事件/状态 | 至少部分展示；未覆盖条件见4.3 |
| [ui/config/AppPickerPage.kt:1](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/config/AppPickerPage.kt:1) | 定义、全部调用、布局、主题、事件/状态 | 至少部分展示；未覆盖条件见4.3 |
| [ui/config/ConfigPage.kt:1](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/config/ConfigPage.kt:1) | 定义、全部调用、布局、主题、事件/状态 | 至少部分展示；未覆盖条件见4.3 |
| [ui/function/FunctionPage.kt:1](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/function/FunctionPage.kt:1) | 定义、全部调用、布局、主题、事件/状态 | 至少部分展示；未覆盖条件见4.3 |
| [ui/home/HomePage.kt:1](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/home/HomePage.kt:1) | 定义、全部调用、布局、主题、事件/状态 | 至少部分展示；未覆盖条件见4.3 |
| [ui/home/HomeStatusCard.kt:1](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/home/HomeStatusCard.kt:1) | 定义、全部调用、布局、主题、事件/状态 | 至少部分展示；未覆盖条件见4.3 |
| [ui/HomeNavigationPolicy.java:1](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/HomeNavigationPolicy.java:1) | 状态/配置/平台调用与调用方 | 见正文；两Java类相关10项JVM测试通过，不等于UI测试 |
| [ui/HomeScreen.kt:1](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/HomeScreen.kt:1) | 定义、全部调用、布局、主题、事件/状态 | 至少部分展示；未覆盖条件见4.3 |
| [ui/LauncherIconController.kt:1](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/LauncherIconController.kt:1) | 状态/配置/平台调用与调用方 | 见正文；两Java类相关10项JVM测试通过，不等于UI测试 |
| [ui/liquid/CombinedBackdrop.kt:1](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/liquid/CombinedBackdrop.kt:1) | 绘制/动画/手势实现与调用 | 随液态栏部分执行，边界/性能未验收 |
| [ui/liquid/InnerShadow.kt:1](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/liquid/InnerShadow.kt:1) | 绘制/动画/手势实现与调用 | 随液态栏部分执行，边界/性能未验收 |
| [ui/liquid/Lens.kt:1](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/liquid/Lens.kt:1) | 绘制/动画/手势实现与调用 | 随液态栏部分执行，边界/性能未验收 |
| [ui/liquid/Vibrancy.kt:1](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/liquid/Vibrancy.kt:1) | 绘制/动画/手势实现与调用 | 随液态栏部分执行，边界/性能未验收 |
| [ui/MainActivity.kt:1](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/MainActivity.kt:1) | 定义、全部调用、布局、主题、事件/状态 | 至少部分展示；未覆盖条件见4.3 |
| [ui/MiuixStyleSwitch.kt:1](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/MiuixStyleSwitch.kt:1) | 宿主原生UI、手势、保存/恢复、语义 | 未运行，副屏OFF |
| [ui/ModuleUpdateState.java:1](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/ModuleUpdateState.java:1) | 状态/配置/平台调用与调用方 | 见正文；两Java类相关10项JVM测试通过，不等于UI测试 |
| [ui/pickup/PickupCodesActivity.kt:1](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/pickup/PickupCodesActivity.kt:1) | 定义、全部调用、布局、主题、事件/状态 | 至少部分展示；未覆盖条件见4.3 |
| [ui/RearScreenApp.kt:1](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/RearScreenApp.kt:1) | 定义、全部调用、布局、主题、事件/状态 | 至少部分展示；未覆盖条件见4.3 |
| [ui/SwipePanelHost.kt:1](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/SwipePanelHost.kt:1) | 宿主原生UI、手势、保存/恢复、语义 | 未运行，副屏OFF |
| [ui/theme/HomeUiTokens.kt:1](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/theme/HomeUiTokens.kt:1) | 定义、全部调用、布局、主题、事件/状态 | 至少部分展示；未覆盖条件见4.3 |
| [ui/updater/UpdateChecker.kt:1](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/updater/UpdateChecker.kt:1) | 异步检查/缓存/解析/下载/弹窗及失败路径 | 更新可见分支未触发 |
| [ui/updater/UpdateDialog.kt:1](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/updater/UpdateDialog.kt:1) | 异步检查/缓存/解析/下载/弹窗及失败路径 | 更新可见分支未触发 |
| [ui/util/AppLanguage.kt:1](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/util/AppLanguage.kt:1) | 状态/配置/平台调用与调用方 | 见正文；两Java类相关10项JVM测试通过，不等于UI测试 |
| [ui/util/RearDisplayCompatibility.kt:1](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/util/RearDisplayCompatibility.kt:1) | 状态/配置/平台调用与调用方 | 见正文；两Java类相关10项JVM测试通过，不等于UI测试 |
| [ui/util/SystemInfo.kt:1](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/util/SystemInfo.kt:1) | 状态/配置/平台调用与调用方 | 见正文；两Java类相关10项JVM测试通过，不等于UI测试 |
| [ui/util/ThemePrefs.kt:1](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/util/ThemePrefs.kt:1) | 状态/配置/平台调用与调用方 | 见正文；两Java类相关10项JVM测试通过，不等于UI测试 |
| [ui/util/UrlOpener.kt:1](/D:/Codex/MiBackscreen/app/src/main/java/hook/HyperBackscreen/ui/util/UrlOpener.kt:1) | 状态/配置/平台调用与调用方 | 见正文；两Java类相关10项JVM测试通过，不等于UI测试 |

### C.2 资源逐文件索引

所有项目资源文件均进入清单；图片只核对用途与已显示分支，不把压缩格式/图稿风格认定为缺陷。

| 文件 | 用途/检查状态 |
| --- | --- |
| [app/src/main/res/drawable/ic_about_logo.png](/D:/Codex/MiBackscreen/app/src/main/res/drawable/ic_about_logo.png) | 项目位图；about/logo、二维码在真机展示；launcher/panel图用于入口，未单独全状态验证 |
| [app/src/main/res/drawable/ic_launcher_background.xml](/D:/Codex/MiBackscreen/app/src/main/res/drawable/ic_launcher_background.xml) | 启动图标/背景资源，非新增页面 |
| [app/src/main/res/drawable/ic_launcher_foreground.png](/D:/Codex/MiBackscreen/app/src/main/res/drawable/ic_launcher_foreground.png) | 项目位图；about/logo、二维码在真机展示；launcher/panel图用于入口，未单独全状态验证 |
| [app/src/main/res/drawable/qr_alipay.jpg](/D:/Codex/MiBackscreen/app/src/main/res/drawable/qr_alipay.jpg) | 项目位图；about/logo、二维码在真机展示；launcher/panel图用于入口，未单独全状态验证 |
| [app/src/main/res/drawable/qr_wechat.png](/D:/Codex/MiBackscreen/app/src/main/res/drawable/qr_wechat.png) | 项目位图；about/logo、二维码在真机展示；launcher/panel图用于入口，未单独全状态验证 |
| [app/src/main/res/drawable-nodpi/panel_icon.png](/D:/Codex/MiBackscreen/app/src/main/res/drawable-nodpi/panel_icon.png) | 项目位图；about/logo、二维码在真机展示；launcher/panel图用于入口，未单独全状态验证 |
| [app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml](/D:/Codex/MiBackscreen/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml) | 启动图标/背景资源，非新增页面 |
| [app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml](/D:/Codex/MiBackscreen/app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml) | 启动图标/背景资源，非新增页面 |
| [app/src/main/res/values/strings.xml](/D:/Codex/MiBackscreen/app/src/main/res/values/strings.xml) | 静态核对两语言键/格式/可见文案；英文运行未测 |
| [app/src/main/res/values/themes.xml](/D:/Codex/MiBackscreen/app/src/main/res/values/themes.xml) | 平台窗口主题/状态栏/导航栏；不作为Material业务控件混用证据 |
| [app/src/main/res/values-night/themes.xml](/D:/Codex/MiBackscreen/app/src/main/res/values-night/themes.xml) | 平台窗口主题/状态栏/导航栏；不作为Material业务控件混用证据 |
| [app/src/main/res/values-zh-rCN/strings.xml](/D:/Codex/MiBackscreen/app/src/main/res/values-zh-rCN/strings.xml) | 静态核对两语言键/格式/可见文案；英文运行未测 |
| [app/src/main/res/xml/data_extraction_rules.xml](/D:/Codex/MiBackscreen/app/src/main/res/xml/data_extraction_rules.xml) | 备份相关平台资源，非页面 |
| [app/src/main/res/xml/file_paths.xml](/D:/Codex/MiBackscreen/app/src/main/res/xml/file_paths.xml) | 反馈导出FileProvider路径；分享未运行 |
| [app/src/main/res/xml/locales_config.xml](/D:/Codex/MiBackscreen/app/src/main/res/xml/locales_config.xml) | zh-CN/en声明核对；不改系统语言 |
