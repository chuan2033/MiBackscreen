# Miuix 0.9.4 与 AndroidLiquidGlass 更新及项目适配审查

核查日期：2026-09-27。项目：`D:\Codex\MiBackscreen` 当前工作区。本次只读源码、比较上游和生成报告，未改业务代码、依赖或设备状态。

## 1. 版本边界与结论

本报告的“0.9.4 新增/修改”指 **v0.9.3 → v0.9.4 正式版**，包含其间 RC 的变化；不是把现有全部 Miuix 组件说成这版新增。共核对92个提交的标题/涉及路径、相关库和示例源码差异，按用户可感知的行为归并为下面38项；纯发布、CI、格式化、贡献者/文档名单不当作UI功能。

| 上游 | 核实版本 | 截止本次的情况 |
| --- | --- | --- |
| [Miuix 0.9.4 发布页](https://github.com/compose-miuix-ui/miuix/releases/tag/v0.9.4) | 2026-09-20；`39c40f99` | 当前正式版；main为`5c91d5e5ce1a2fc7e8bdc1258a881c555102bbca`，另有9个提交，**不能当作已安装0.9.4内容** |
| [AndroidLiquidGlass 2.0.1](https://github.com/Kyant0/AndroidLiquidGlass/releases/tag/2.0.1) | 2026-08-26；`65ab177e90e5c1d8c62e70cf7755841982da65f6` | main与2.0.1相同，本轮未发现该标签之后的提交 |
| 你的项目 | 4个Miuix Android模块均0.9.4 | 已用新库；没有`io.github.kyant0:backdrop`依赖，不能靠添加“2.0.1版本号”升级本地移植文件 |

**建议**：继续使用Miuix 0.9.4，先有选择地补齐Miuix对应液态导航示例的交互、前景色和传感器优化；不要把整个绘制底座改回Kyant包，也不必为少量详情页立即更换导航框架。跨轴Pager的新功能需要单独评估，因为正式版之后仍有相关修复。

证据目录：[upstream-ui-2026-09-27](/D:/Codex/MiBackscreenHuancun/evidence/upstream-ui-2026-09-27)。包含两个只读调查用Git仓库、版本源码快照、`miuix-ui-093-094.diff`、`miuix-post-094.diff`、全部提交索引和本地文件比较结果。未运行上游仓库脚本或构建。

## 2. 0.9.4 全部UI相关变化及项目意义

状态含义：**已获得**＝相关库代码已随0.9.4进入依赖，不等于所有场景验收通过；**可选接入**＝需项目主动调用；**需要调整**＝项目代码存在对应缺口；**暂不需要**＝当前无相应功能。源码/提交链接固定到正式版本或具体提交。

### 2.1 导航与页面状态

| 编号 | 更新及作用 | 对项目是否有用、需要做什么 | 上游依据 |
| --- | --- | --- | --- |
| M01 | 新增独立`miuix-nav`，移除`miuix-navigation3-ui`。以连续栈深度`animatedTop`驱动多级push/pop，内置滑入、Modal、无动画转场；支持可选手势关闭、预测性返回、entry生命周期/ViewModel/saveable状态。手势关闭默认不启用 | **可选接入**。你的`HomeScreen.kt:173–193`是自建详情枚举＋AnimatedContent，未依赖旧Navigation3，**没有强制迁移任务**。若未来要多级返回栈、统一预测返回，可评估；当前先补rememberSaveable即可解决部分恢复问题 | [导航指南](https://github.com/compose-miuix-ui/miuix/blob/v0.9.4/docs/guide/miuix-nav.md)、[运行时](https://github.com/compose-miuix-ui/miuix/tree/v0.9.4/miuix-nav/src/commonMain/kotlin/top/yukonga/miuix/kmp/nav) |
| M02 | 新增`LocalNavTransitionScope`，让entry内部读取相对深度、手势/收敛阶段、`isRunning`，可协调内部动画 | 当前未用miuix-nav，**暂不需要**；若迁移，只在NavDisplay entry内读取，逐帧值应延迟到绘制层读取 | [提交5de9d0f5](https://github.com/compose-miuix-ui/miuix/commit/5de9d0f5) |
| M03 | Nav entry继承SavedState的CreationExtras与默认ViewModel工厂，修复SavedStateHandle以及依赖这些信息的Hilt集成 | **迁移后有用**，不会自动修复现有`remember`页面变量；也不能仅换NavDisplay就让未序列化路由自动恢复 | [提交29d6deb3](https://github.com/compose-miuix-ui/miuix/commit/29d6deb3) |
| M04 | 新增跨独立Android Window的导航返回事件桥接，改善弹窗窗口内嵌导航 | WindowDialog基础窗口层已更新，但你的弹窗没有内嵌NavDisplay，**直接收益有限**；不需要自己再造返回桥 | [提交652804e1](https://github.com/compose-miuix-ui/miuix/commit/652804e1) |
| M05 | 预测性返回完成/取消仲裁修复；Android返回是否提交尊重平台完成结果，降低重复pop或错误提交 | **miuix-nav用户受益**。现有详情BackHandler不会因此自动成为完整的可交互预测返回 | [提交e1086564](https://github.com/compose-miuix-ui/miuix/commit/e1086564)、[be0aa838](https://github.com/compose-miuix-ui/miuix/commit/be0aa838) |
| M06 | 页面滑动关闭与子控件滑动的冲突修复：区分子内容消费和导航手势，一旦归属确定不随横纵摆动乱抢 | 未来详情页引入滑动关闭时**有用**；不能把它当作当前背屏原生View手势的修复 | [提交1f3f05d6](https://github.com/compose-miuix-ui/miuix/commit/1f3f05d6) |
| M07 | 新导航默认`blockInputDuringTransition=false`；不同entry的指针过滤器使用不同key，避免过滤节点状态混淆 | **迁移时了解**。需要禁止转场误触时主动配置；不是建议现在放开所有业务交互 | [8c989bfd](https://github.com/compose-miuix-ui/miuix/commit/8c989bfd)、[a43f0790](https://github.com/compose-miuix-ui/miuix/commit/a43f0790) |

M01的保存恢复需要可序列化的NavKey层级，以及显式`rememberNavBackStack<Route>(...)`。这属于可选架构方案，不是0.9.4的必改项。

### 2.2 控件、弹窗、列表和输入

| 编号 | 更新及作用 | 对项目是否有用、需要做什么 | 上游依据 |
| --- | --- | --- | --- |
| M08 | 新增`BreadcrumbBar`，以可横向滚动的胶囊段展示路径/层级；带`BreadcrumbItem`、高亮索引、配色和`joinToPath()` | **暂不需要**。你没有文件路径导航或深层面包屑；不能拿它替换主页三Tab | [组件源码](https://github.com/compose-miuix-ui/miuix/blob/v0.9.4/miuix-ui/src/commonMain/kotlin/top/yukonga/miuix/kmp/basic/BreadcrumbBar.kt) |
| M09 | `NavigationBarItem`和`FloatingNavigationBarItem`新增`colors`，自定义选中/未选中前景；按压透明度与传入alpha相乘 | **可选接入**：`HomeScreen.kt:658,736`可统一选中主色。你的调用使用命名参数，无位置参数迁移问题。自定义`FloatingBottomBarItem`不使用这套API，不能靠该参数修复液态栏黑色文字 | [提交e2cca731](https://github.com/compose-miuix-ui/miuix/commit/e2cca731) |
| M10 | `NavigationRail`拆为固定布局与可展开state两种重载；恢复紧凑模式选中图标背景 | **暂不需要**；目前没有侧边导航。做平板/横屏导航时可选，旧`state=null`须改为无state重载 | [提交63529058](https://github.com/compose-miuix-ui/miuix/commit/63529058) |
| M11 | `RadioButtonPreference`选中标题/摘要默认主色；文字色合并到`RadioButtonPreferenceColors`；整行统一点击与触觉反馈，内部radio不再独立处理点击 | **暂不需要**，项目没有RadioButtonPreference。以后设计单选设置页可用；不要把现有Switch/Checkbox也按此迁移 | [组件差异](https://github.com/compose-miuix-ui/miuix/blob/v0.9.4/miuix-preference/src/commonMain/kotlin/top/yukonga/miuix/kmp/preference/RadioButtonPreference.kt) |
| M12 | `InputField`新增背景`color`；可传Transparent，在外部绘制玻璃背景 | `AppPickerPage.kt:156`**可选**；当前普通搜索框不必透明化。新增参数插在textStyle和leadingIcon之间；现有命名参数安全 | [SearchBar源码](https://github.com/compose-miuix-ui/miuix/blob/v0.9.4/miuix-ui/src/commonMain/kotlin/top/yukonga/miuix/kmp/basic/SearchBar.kt) |
| M13 | `OverlayDialog`/`WindowDialog`新增`maxWidth`，限制大屏弹窗宽度 | 3个WindowDialog**有用但不必立即改**：更新、重启、兼容性。默认420dp已生效；若内容需求不同可单独设，不应用宽度上限掩盖高度裁切 | [提交208654bf](https://github.com/compose-miuix-ui/miuix/commit/208654bf) |
| M14 | 弹窗新增`largeScreen`位置策略覆盖与`cornerRadius`；居中默认32dp，不再跟屏幕圆角走；底部形式保留屏幕圆角并限制32–48dp | **已获得默认行为**；横屏/平板视觉可通过参数控制。不是自动滚动/自动保证按钮可达，原UI审查的短窗口风险仍需测 | [0afe3f6f](https://github.com/compose-miuix-ui/miuix/commit/0afe3f6f)、[a8601040](https://github.com/compose-miuix-ui/miuix/commit/a8601040)、[bbd6669e](https://github.com/compose-miuix-ui/miuix/commit/bbd6669e) |
| M15 | ListPopup、BottomSheet、Dialog的动画graphicsLayer放到调用方modifier之前，避免自定义modifier破坏预测返回/进出动画层次 | 现有WindowDialog/下拉**已获得相关库修复**；无须改你当前的普通调用，若后续自定义模糊modifier要验证变换顺序 | [提交8682e005](https://github.com/compose-miuix-ui/miuix/commit/8682e005) |
| M16 | 普通/级联弹出列表支持在入场动画过程中被预测返回打断 | 设置下拉菜单**已获得**；回归“快速打开后立刻返回”，不必自写延迟拦截 | [提交602cbafa](https://github.com/compose-miuix-ui/miuix/commit/602cbafa) |
| M17 | 级联菜单按位置/索引追踪层级状态，而非依赖条目对象引用 | **暂不需要**，你的3个WindowDropdownPreference不是项目自建多级级联菜单 | [提交a370b370](https://github.com/compose-miuix-ui/miuix/commit/a370b370) |
| M18 | SmallTopAppBar固定时清理高度偏移；共享滚动状态切回TopAppBar时恢复正确位置与标题可见性，并保存pinned状态 | 你只使用普通TopAppBar，**相关基础代码已更新，主要触发条件暂未使用**。将来做横屏大小顶栏切换时受益 | [提交ad956299](https://github.com/compose-miuix-ui/miuix/commit/ad956299) |
| M19 | `TabRow`/`TabRowWithContour`横向滚动及越界不再传给外层Pager，避免滑标签意外切页 | 捐赠TabRow**已获得**；当前捐赠是独立详情，不处于主HorizontalPager内，因此核心冲突条件不强 | [提交86cce57f](https://github.com/compose-miuix-ui/miuix/commit/86cce57f) |
| M20 | 列表越界后，当子列表重新可滚动时解除旧overscroll偏移，减少悬住的回弹状态 | 你的多处`overScrollVertical()` **直接有用且已获得**，尤其动态展开设置、异步加载应用列表 | [提交4cacf614](https://github.com/compose-miuix-ui/miuix/commit/4cacf614) |
| M21 | Overscroll与PullToRefresh区分真实触摸手势会话和滚轮输入，避免非触摸滚动误触发拉伸/刷新 | `overScrollVertical` **已获得**；鼠标/触控板场景增益更明显。项目没有PullToRefresh，不会因此出现新刷新操作 | [提交47393d7a](https://github.com/compose-miuix-ui/miuix/commit/47393d7a) |
| M22 | PullToRefresh将触发阈值与圆圈视觉缩放分开；新增可调`refreshThreshold`、`fullDragProgress`、`visualProgress`；完成时高度连续，程序触发刷新适应后续阈值变化 | **暂不需要**；如果未来添加列表重试/手动检查更新才考虑。自定义指示器应判断`RefreshState.ThresholdReached`，不能只用progress==1 | [组件源码](https://github.com/compose-miuix-ui/miuix/blob/v0.9.4/miuix-ui/src/commonMain/kotlin/top/yukonga/miuix/kmp/basic/PullToRefresh.kt) |
| M23 | Scaffold测量底部floatingToolbar位置，让Snackbar显示在其上方 | **当前不直接受益**：项目用Toast，底栏又在外层Box里手工叠加，未接入Scaffold的floatingToolbar槽。以后改Snackbar要同步布局，不能只换提示组件 | [提交825c683e](https://github.com/compose-miuix-ui/miuix/commit/825c683e) |

### 2.3 模糊和Pager

| 编号 | 更新及作用 | 对项目是否有用、需要做什么 | 上游依据 |
| --- | --- | --- | --- |
| M24 | 新增真正按方向递减的渐进模糊：`Modifier.progressiveTextureBlur()`、`BackdropEffectScope.progressiveBlur()`、`ProgressiveBlur`方向/起止/curve；drawBackdrop增加`progressiveGradient`配合清晰端合成 | **正在使用**：`components/BlurredBar.kt:25–29`使用Top、curve=6、radius=18。没有必要再复制Kyant的另一套渐进模糊。现有drawBackdrop命名参数不受新参数位置影响 | [提交b459d861](https://github.com/compose-miuix-ui/miuix/commit/b459d861)、[ProgressiveBlur定义](https://github.com/compose-miuix-ui/miuix/blob/v0.9.4/miuix-blur/src/commonMain/kotlin/top/yukonga/miuix/kmp/blur/ProgressiveBlur.kt) |
| M25 | 修正layerBlock缩放时背景采样：显示分辨率下反向补偿缩放，并恢复后续采样路径，避免玻璃和背后内容错位 | 你的液态按压伸缩、速度变形**直接受益，已在0.9.4库中**；不用把DrawBackdropModifier复制进项目 | [6f6ffb26](https://github.com/compose-miuix-ui/miuix/commit/6f6ffb26)、[0f960603](https://github.com/compose-miuix-ui/miuix/commit/0f960603) |
| M26 | blur之后的效果若增大padding，重新构建一次效果链，避免采样边界仍用旧尺寸导致右/下边缘拖影 | 你的`blur→lens`链**已获得库修复**；仍建议在blur前预设最终padding，减少一次重建，并覆盖按压伸缩需要的采样范围 | [提交3528c846](https://github.com/compose-miuix-ui/miuix/commit/3528c846) |
| M27 | 新增Pager手势冲突工具：Native、CrossAxisInterceptor、TapToHalt；`pagerGestureOverride`、对应nested scroll连接、fling tracker；`springAnimateToPage`提供统一弹簧切页 | **可选且与你相关**：`HomeScreen.kt:565–598`有HorizontalPager＋竖向LazyColumn，只用了新`PagerNavigationSpringSpec`，尚未接入手势覆盖。建议先测“列表惯性滚动时横滑”，有需求再接；参见第4节未发布修复 | [提交39c40f99](https://github.com/compose-miuix-ui/miuix/commit/39c40f99)、[0.9.4工具源码](https://github.com/compose-miuix-ui/miuix/blob/v0.9.4/miuix-ui/src/commonMain/kotlin/top/yukonga/miuix/kmp/utils/PagerGestureUtils.kt) |

### 2.4 示例更新——复制到项目的代码不会自动更新

| 编号 | 更新及作用 | 对项目是否有用、需要做什么 | 上游依据 |
| --- | --- | --- | --- |
| M28 | 液态Lens用中心坐标判断四角半径，修复非对称圆角使用错角的折射 | **你的代码已包含**：`Lens.kt:127,164`均为`radiusAt(centeredCoord, cornerRadii)`；不是待修缺陷 | [提交ae0ff6a9](https://github.com/compose-miuix-ui/miuix/commit/ae0ff6a9) |
| M29 | 液态按压反馈升级：smoothstep光斑、松手位置回弹；倾斜方向3°量化并延迟到绘制阶段；扩大采样padding；修复高光旧动画引用；浅色阴影减淡 | **部分已有、部分要补**：你的光斑/回弹/浅色阴影已实现，但缺少3°量化、绘制期读取、40dp预留和完整remember keys。逐项见第3节 | [提交ddd06774](https://github.com/compose-miuix-ui/miuix/commit/ddd06774) |
| M30 | 液态导航按下预览、松手提交，取消回到已选Tab；支持Enter/空格激活，所有选择来源都同步指示器；增加单独onDragCancelled回调 | **优先调整**：你的取消仍调用onDragStopped，拖过目标后取消也可提交选择；手势还只挂在选中胶囊，未选项由普通clickable处理 | [提交bc4b939c](https://github.com/compose-miuix-ui/miuix/commit/bc4b939c)、[当前示例](https://github.com/compose-miuix-ui/miuix/blob/v0.9.4/example/shared/src/commonMain/kotlin/component/liquid/LiquidGlassNavigationBar.kt) |
| M31 | 示例在自适应布局/配置变化时保留页面、导航和选择状态 | **设计方法有用，不能直接修复项目**：你的detailPage/selected仍是remember；原报告A06依然成立，需本地恢复状态 | [提交a9e9d84f](https://github.com/compose-miuix-ui/miuix/commit/a9e9d84f) |
| M32 | 示例About页移除短横屏下的固定父高约束，确保内容能滚到；修正顶栏inset使用 | **回归思路有用**。你的AboutPage不是同一布局，不能照抄补丁；应测短横屏/大字体，并按实际问题改 | [提交1c3c7f62](https://github.com/compose-miuix-ui/miuix/commit/1c3c7f62) |
| M33 | 示例背景效果在不可见时暂停逐帧循环 | **当前没有同一BgEffectModifier，不需复制**；可作为检查不可见页面传感器/动画生命周期的依据 | [提交054ec567](https://github.com/compose-miuix-ui/miuix/commit/054ec567) |
| M34 | 示例SuperSearchBar将顶部inset读取放到布局阶段，减少组合阶段更新和位置滞后 | **当前无SuperSearchBar**，AppPicker用普通InputField；不需照搬 | [提交43fd3222](https://github.com/compose-miuix-ui/miuix/commit/43fd3222) |
| M35 | 增加overscroll＋load-more复现页，以及新导航/新组件示例和相应验证用例 | **测试参考**，不会给项目新增页面；可借用异步数据加入时的回弹测试方法 | [提交a0808ddb](https://github.com/compose-miuix-ui/miuix/commit/a0808ddb)、[示例组件目录](https://github.com/compose-miuix-ui/miuix/tree/v0.9.4/example/shared/src/commonMain/kotlin/component) |

### 2.5 UI集成相关兼容变更

| 编号 | 更新及作用 | 对项目是否有用、需要做什么 | 依据 |
| --- | --- | --- | --- |
| M36 | 共享Android minSdk由23升24；miuix-blur仍要求33。上游Compose Multiplatform升1.12.0、Lifecycle2.11.0、Kotlin2.4.20；上游构建AGP9.4.1/Gradle9.7.1 | 你minSdk36已满足，不必再升；上一轮解析的AndroidX Compose为1.12.0。**库生产端的构建工具版本不等于消费端必须整体照抄**；你AGP9.2.1、Compose compiler2.3.21、Gradle9.6.0已通过当前构建，除非发现具体兼容问题，不做无关升级 | [版本目录](https://github.com/compose-miuix-ui/miuix/blob/v0.9.4/gradle/libs.versions.toml)、[minSdk提交](https://github.com/compose-miuix-ui/miuix/commit/3fa5207e) |
| M37 | 公开函数签名有源/二进制兼容变化：旧navigation3移除；RadioButtonPreference/NavigationRail签名变化；导航items、InputField、drawBackdrop插入新参数；Dialog/PullToRefresh新增参数 | 你的源码命名调用已兼容；也没有预编译的自家旧Miuix UI组件库。若以后引入按0.9.3/RC编译的组件JAR应重编译。**不用为了追“新API”重写已正常调用** | [导航源码](https://github.com/compose-miuix-ui/miuix/blob/v0.9.4/miuix-ui/src/commonMain/kotlin/top/yukonga/miuix/kmp/basic/NavigationBar.kt)、[Radio源码](https://github.com/compose-miuix-ui/miuix/blob/v0.9.4/miuix-preference/src/commonMain/kotlin/top/yukonga/miuix/kmp/preference/RadioButtonPreference.kt) |
| M38 | UI内部维护：FloatingNavigationBar抑制modifier-not-at-root检查，TopAppBar常量改名；示例iOS资源路径修正、构建/文档/测试基础设施更新 | **没有可见功能要接入**；不把内部常量名、CI提速、文档名单、iOS安装包打包当成Android UI新能力 | [76ab5674](https://github.com/compose-miuix-ui/miuix/commit/76ab5674)、[d5c03cca](https://github.com/compose-miuix-ui/miuix/commit/d5c03cca)、[b728c2d9](https://github.com/compose-miuix-ui/miuix/commit/b728c2d9) |

补充边界：0.9.3→0.9.4的`miuix-icons`、`miuix-core`以及UI theme源码未产生对应功能差异；“深色自动修复所有自定义Text/Icon”“所有基础开关重新设计”不是这版更新。你的MiuixTheme(colors=...)重载缺LocalContentColor的问题不能假设已被0.9.4修好。

## 3. AndroidLiquidGlass 审查与本地差距

### 3.1 实际使用关系

```text
AndroidLiquidGlass / Backdrop的效果和示例思路
    ↓ Miuix示例适配 + KernelSU来源注释 + 项目自定义
本地 FloatingBottomBar / animation / liquid
    ↓ 依赖类型与渲染接口
Miuix 0.9.4：Backdrop、drawBackdrop、blur、RuntimeShaderEffect、传感器和高光
```

[上游README](https://github.com/Kyant0/AndroidLiquidGlass/blob/2.0.1/README.md)明确把LiquidButton/Toggle/Slider/BottomTabs列为示例，库并不直接打包这些高层控件。你的源码顶部同时标注Miuix示例与KernelSU来源；没有锁定具体移植提交，所以不能声称“你的AndroidLiquidGlass版本就是1.x”。本次以当前源码逐文件比对，保留现有来源标记。

### 3.2 AndroidLiquidGlass 最近正式更新

| 版本/提交 | 更新与作用 | 对你项目的结论 |
| --- | --- | --- |
| [2.0.1](https://github.com/Kyant0/AndroidLiquidGlass/releases/tag/2.0.1)，2026-08-26 | Compose升1.12.0；依赖与KMP构建层次更新。相对2.0.0只有6个构建/版本/包装器文件变化，backdrop/src与示例运行源码无变化 | **没有新的玻璃算法可直接补入**；你实际Compose已解析1.12.0，添加Kyant依赖不会自动更新本地FloatingBottomBar |
| [2.0.0](https://github.com/Kyant0/AndroidLiquidGlass/releases/tag/2.0.0)，2026-05-28 | Backdrop正式跨平台；改用Compose效果接口，增加公共RuntimeShader及runtimeShaderEffect能力 | 当前Android-only/minSdk36项目**不需要因此迁移**。本地lens已经适配Miuix同类接口；原生Android RuntimeShader能运行不是缺陷 |
| [坐标引用修复7c37be4](https://github.com/Kyant0/AndroidLiquidGlass/commit/7c37be4)，纳入2.0.0 | LayerBackdrop更新到另一个对象时清除旧对象的LayoutCoordinates；drawBackdrop切换exportedBackdrop也清理旧引用，减少引用滞留 | **需核对实现而不是照搬补丁**。Miuix0.9.4的LayerBackdropModifier:30–35已清旧坐标，onDetach也清；DrawBackdropModifier:1102释放图层/坐标且没有Kyant同形exportedBackdrop接口。未证明你的Miuix底座缺此修复 |
| [1.0.6](https://github.com/Kyant0/AndroidLiquidGlass/releases/tag/1.0.6) / [1.0.5](https://github.com/Kyant0/AndroidLiquidGlass/releases/tag/1.0.5) | 引入Kyant Shapes，随后提高最低Shapes版本到1.2.0；2.0.1使用1.2.1 | 当前用CircleShape和Miuix squircle，**不必为了版本新增另一套shape依赖**；需要支持更多非对称/自定义形状时再单独评估 |
| [1.0.4](https://github.com/Kyant0/AndroidLiquidGlass/releases/tag/1.0.4) | 修复绘制可能不更新 | 是旧历史修复，不能根据本地源码未带版本就说你还存在该bug；实际绘制底座是Miuix |
| [1.0.3](https://github.com/Kyant0/AndroidLiquidGlass/releases/tag/1.0.3)及更早 | HighlightStyle.Default改用颜色/blendMode等参数；旧Compose依赖升级 | 你使用Miuix BloomStroke，**不可机械替换Kyant的Highlight类型**；这些也不是2.0.1新增 |

### 3.3 本地玻璃代码逐项结论

| ID / 结论 | 本地证据、作用和建议 | 优先级/验证 |
| --- | --- | --- |
| G01 深色前景色缺失，已确认（上一轮真机） | `HomeScreen.kt:690–698`省略Icon.tint/Text.color；`FloatingBottomBar.kt:302`普通层无LocalContentColor。Miuix0.9.4示例明确提供onSurface。**补普通层前景色**，保持选中着色层设计 | P2，优先；普通/Monet深色、即时切换、模糊开关 |
| G02 导航选中语义缺失，已确认 | `FloatingBottomBar.kt:158`只有clickable(Role.Tab)，没有selected；Miuix示例有selected/Role.Tab/onClick、selectableGroup、focusable。Kyant2.0.1的LiquidBottomTab也仍只用clickable，**上游示例并非自动满足完整无障碍** | P2，优先；TalkBack/键盘/三个Tab唯一选中状态 |
| G03 取消手势会走提交路径，静态确认 | `DampedDragAnimation.kt:75–76`取消调用onDragStopped；`FloatingBottomBar.kt:247–250`只要wasDragged即onSelected。**增加独立onDragCancelled，回弹到已选索引，不提交**；Miuix #397已有做法 | P2，优先；拖到另一项后注入cancel/父层抢手势，页面不改变；正常UP只提交一次 |
| G04 按压和拖动入口不一致，交互改进 | 本地手势挂在指示胶囊`FloatingBottomBar.kt:401–402,450`，普通Tab走clickable；Miuix示例将整个栏作为手势入口，按下指向预览，松手提交。可获得一致伸缩/拖动，但属交互选择，**不是说所有clickable都错** | 建议；可与G03成组调整，保留无障碍/键盘激活路径，避免普通点击和手势双提交 |
| G05 倾斜状态在组合阶段高频读取，源码可确认，耗时未测 | `FloatingBottomBar.kt:113–145`读`val tilt by rememberDeviceTilt()`，高光复制也在组合阶段；Miuix0.9.4示例按3°量化、返回State并在highlight绘制lambda里读value。**采纳这种方式**减少重组与分配 | 性能优化；用Compose重组计数/帧耗时测试，不承诺未经测量的省电百分比 |
| G06 采样padding预留不足风险 | `FloatingBottomBar.kt:328–335`没有显式40dp预留；本地Lens只将padding升到refractionAmount(24dp)。Miuix示例考虑24dp折射＋按压伸缩边界，**在blur前设padding=maxOf(padding,40.dp.toPx())**。库的padding重建修复不等于项目已预留按压采样范围 | 待视觉验证；按住/快速拖动时高对比背景边缘不得黑边、拖影；无模糊分支不加无用成本 |
| G07 高光remember缺依赖，潜在旧引用 | `FloatingBottomBar.kt:280`key只有scope、tabWidthPx，lambda捕获isLtr和dampedDragAnimation；后者按density/isLtr/tabsCount重建。**补isLtr、动画实例key，回调用rememberUpdatedState**，不要仅靠宽度变化碰巧重建 | 静态风险；相同像素宽度下密度/方向切换、回调更新后高光仍跟随当前指示器；现有zh/en均LTR，不报当前已复现RTL异常 |
| G08 动画作业互相竞争风险 | 本地`DampedDragAnimation.kt:92–112`不保存/取消pressJob和releaseJob；Miuix示例已有旧释放作业取消，防止下一次按住时上次release压回1倍。**适合补齐，但它早于0.9.4存在，不冒充本版新增** | 优化/风险；快速按放再按、外部切Tab与手拖交错 |
| G09 速度跟踪过度平滑与零跨度边界 | 本地`DampedDragAnimation.kt:141–142`每次速度计算再animateTo，且直接除range跨度；Miuix示例给span下限并snapTo当前速度。现在固定3Tab不会触发零跨度；**可补防御，但不要报现有1Tab崩溃** | 低优先级；速度变形自然、动态1Tab复用不产生NaN |
| G10 四个liquid基础文件已对齐，不用替换 | 去除注释/包名/internal可见性/空白后，`Lens.kt`、`InnerShadow.kt`、`CombinedBackdrop.kt`、`Vibrancy.kt`与Miuix v0.9.4示例逐字规范化比较均相等。Lens已有downscaleFactor uniform适配与中心坐标修复 | 已核实；保留现有实现和来源，不能用Kyant同名文件覆盖 |
| G11 光斑增强已有，跨平台fallback可选 | `InteractiveHighlight.kt`已经包含smoothstep、位置回弹及增强透明度；只使用Android RuntimeShader，没有Miuix示例的跨平台/不支持shader时radialGradient回退。minSdk36满足Android支持，**当前无需为此改** | 若未来多平台或降低最低系统再迁移；不是当前兼容性缺陷 |
| G12 新旧库不能机械混用 | 本地`lens`以Miuix BackdropEffectScope为接收者，chromaticAberration是Float；Kyant2.0.1对应参数是Boolean，padding策略与Miuix不同；类型名相似但非可互换 | 架构约束；继续Miuix底座，挑选示例逻辑做适配，避免双引擎/重复Backdrop |

G01/G02关联上一轮 [UI_AUDIT.md](/D:/Codex/MiBackscreen/UI_AUDIT.md) A03/A04。本轮新增G03等静态结论，没有重跑真机手势或性能基准，不将建议写成已验证修复。

### 3.4 可以先调整的具体代码

以下是**建议片段，未应用、未编译验证**；使用的是已核对的0.9.4 API。实际修复还需结合完整作用域和调用签名。

**普通液态层前景色**：在`FloatingBottomBar.kt`普通Row周围提供主题颜色；彩色采样层维持独立选中颜色。

```kotlin
import top.yukonga.miuix.kmp.theme.LocalContentColor

CompositionLocalProvider(
    LocalContentColor provides MiuixTheme.colorScheme.onSurface
) {
    // 当前普通内容 Row（包含 Icon / Text）
}
```

**Tab语义**：给`FloatingBottomBarItem`新增selected参数，并在`HomeScreen.kt:684`传`selected == item.tab`。若仍保留各项触摸clickable架构，可用`selectable`替换；若采用全栏手势架构，则参考上游semantics＋键盘/focusable写法，避免两种输入同时提交。

```kotlin
Modifier.selectable(
    selected = selected,
    role = Role.Tab,
    interactionSource = null,
    indication = null,
    onClick = onClick
)
```

普通Miuix浮动项`HomeScreen.kt:740`的`label = ""`应改为`stringResource(item.labelRes)`。0.9.4该组件的label用于图标描述，不会多画一行文字；这是既有误用修复，不是新的colors功能。

**取消不提交**：`DampedDragAnimation`增加单独回调，调用方恢复当前已选索引。

```kotlin
// 构造参数
val onDragCancelled: DampedDragAnimation.() -> Unit = onDragStopped

// inspectDragGestures
onDragCancel = {
    onDragCancelled()
    release()
}
// FloatingBottomBar的取消回调：回到当前已选Tab，复位offsetAnimation，勿调用onSelected
```

**先设置玻璃采样边界**：`FloatingBottomBar.kt:328`的effects块。

```kotlin
effects = {
    padding = maxOf(padding, 40.dp.toPx())
    vibrancy()
    blur(4.dp.toPx(), 4.dp.toPx())
    lens(refractionHeight = 24.dp.toPx(), refractionAmount = 24.dp.toPx())
}
```

**普通Miuix底栏颜色可选统一**（不会改变自定义液态项）：

```kotlin
colors = NavigationBarDefaults.navigationBarItemColors(
    unselectedContentColor = MiuixTheme.colorScheme.onSurface,
    selectedContentColor = MiuixTheme.colorScheme.primary
)
```

**Pager接入仅在确认需要后进行**：0.9.4精确API如下；当前已使用PagerNavigationSpringSpec，不等于已经有跨轴接管。

```kotlin
HorizontalPager(
    state = pagerState,
    modifier = Modifier.pagerGestureOverride(
        pagerState = pagerState,
        mode = PagerInterceptionMode.CrossAxisInterceptor
    ),
    userScrollEnabled = false,
    pageNestedScrollConnection = PagerGestureNestedScrollConnection
) { page -> /* 原页面 */ }
// Tab点击可单独评估 pagerState.springAnimateToPage(index)
```

不是只给现有modifier加一个函数：CrossAxis模式必须同时匹配userScrollEnabled与nested-scroll配置；Native/TapToHalt须用各自配置。**鉴于第4节未发布修复，不建议把此段直接当成当前必须上线的补丁。**

## 4. Miuix 0.9.4 之后的更新——尚未包含在正式依赖中

本次实际`v0.9.4..main`为9提交：5项运行/交互修复＋Compose1.12.1＋3项纯工具更新。以下固定到本次main提交，不能与0.9.4 API混用。

| 日期 / 提交 | 作用 | 项目意义 |
| --- | --- | --- |
| 09-23 [a575835c](https://github.com/compose-miuix-ui/miuix/commit/a575835c) | 跨轴Pager重构，保留子控件手势，整合滚轮/触控板及fling行为 | 若准备接入M27，应先对比；已有API改名/签名变化，不宜零散拷贝 |
| 09-23 [00e2f193](https://github.com/compose-miuix-ui/miuix/commit/00e2f193) | 修复Pager接管后子控件跨轴手势状态 | 与“列表仍能纵向滚动、子控件不丢触摸”有关；当前项目未启用这些override |
| 09-23 [2afdbb39](https://github.com/compose-miuix-ui/miuix/commit/2afdbb39) | 子控件收到手势时，不再无条件中断Pager收敛 | 减少切页停在中间；仍属于新override路径，不据此断言项目普通Pager有同一bug |
| 09-23 [70528417](https://github.com/compose-miuix-ui/miuix/commit/70528417) | miuix-nav栈顶改变时清焦点，避免保留在组合中的旧页继续持有输入焦点 | 若未来迁移应用选择页到miuix-nav，有参考价值；当前父详情手动控制，要按本地焦点问题处理 |
| 09-27 [5c91d5e5](https://github.com/compose-miuix-ui/miuix/commit/5c91d5e5) | 优化渐进模糊shader key分配/无用uniform更新，失效时清理缓存 | `BlurredBar`直接相关，但**性能收益未在你设备量化**；可等稳定版，不必引入snapshot |
| 09-23 [73ba6073](https://github.com/compose-miuix-ui/miuix/commit/73ba6073) | Compose Multiplatform升级1.12.1 | 未进入本项目当前依赖图，不当作0.9.4版本 |
| 09-25/26 工具提交 | ktlint0.6.7、Spotless8.10.3、Gradle9.8.0 | 不改变应用可见UI，不需要消费端照抄 |

特别注意：main把`PagerInterceptionMode.CrossAxisInterceptor`改成`CrossAxis`，`pagerGestureOverride`增加必需的flingBehavior参数；上面0.9.4建议片段故意使用**正式版名称和签名**。最新在线文档可能指向main，不可直接套到0.9.4。

## 5. 项目行动顺序与复测

1. **先补G01/G02/G03**：深色颜色、Tab语义、取消不提交；这些直接影响可见性和交互正确性。不要先换引擎。
2. **补G05/G06/G07/G08**：倾斜读取优化、采样padding、remember依赖、动画作业取消；与现有外观兼容，性能/黑边需真机复测。
3. **可选G04**：决定是否采用按下预览/松手提交的全栏交互；和所有点击/键盘路径一起测试，避免只移手势不改选中状态。
4. **保留G10/G11的已正确部分**：四个liquid文件核心逻辑、已有shader光斑、单调时钟、浅色阴影，不重复重写。
5. **独立评估M27/M01**：跨轴Pager和新导航会扩大影响范围，应先用可控用例验证，再决定升级/迁移；不是修复液态栏的前置条件。

验收场景：3个Tab逐一点击；按住预览/松手；拖动越界回拖；父层抢手势触发cancel；快速再按；页面滑动带动指示器；深浅/Monet、模糊开关；TalkBack和Enter/空格；高对比背景检查缩放边缘；屏幕尺寸/密度变化；传感器静止/缓慢转动下的重组与帧耗时。

本轮验证是上游Git/版本源码比较＋项目静态链路审查；上一轮深色与语义截图可复用为问题证据。本轮没有应用上述建议、没有重新编译假补丁，也没有宣称取消路径、动画竞争或性能优化已经在真机验证完成。
