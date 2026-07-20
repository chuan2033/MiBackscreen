# Miuix UI 规范

本项目遵循 [Miuix 官方 UI 规范](UI规范.md)，以下为具体实现对照。

## 组件使用

| 规范要求 | 项目实现 | 状态 |
|----------|----------|------|
| 所有 UI 组件使用 Miuix | Card、TopAppBar、NavigationBar、SmallTitle、SwitchPreference、CheckboxPreference 等 | ✅ |
| 不引入 Material3 | 仅使用 `androidx.compose.foundation` 和 Miuix | ✅ |
| 返回按钮使用 `MiuixIcons.Back` | LicensePage 导航返回 | ✅ |
| 操作 IconButton `minHeight/minWidth = 35.dp` | 暂无操作 IconButton | N/A |

## 自定义形状

| 规范要求 | 项目实现 | 状态 |
|----------|----------|------|
| 非 Miuix 组件用 squircle | AboutPage logo 使用 `squircleClip` | ✅ |
| 不使用 `RoundedCornerShape` | 已全部替换 | ✅ |
| 3dp 小徽章可保持 `clip(RoundedCornerShape(3.dp))` | 项目无徽章 | N/A |

## 页面骨架

| 规范要求 | 项目实现 | 状态 |
|----------|----------|------|
| Scaffold + TopAppBar(scrollBehavior) + LazyColumn | HomeScreen、LicensePage | ✅ |
| LazyColumn 加 `.scrollEndHaptic().overScrollVertical().nestedScroll()` | HomeScreen、LicensePage | ✅ |
| `contentPadding` 仅设 top | HomeScreen、LicensePage | ✅ |
| 首个 item 是 Card 时加 `Spacer(12.dp)` | LicensePage（Card 开头）| ✅ |
| SmallTitle 开头不加 Spacer | HomePage（SmallTitle 开头）| ✅ |
| 末尾 item 加 `Spacer(24.dp).navigationBarsPadding()` | HomeScreen、LicensePage | ✅ |
| 二级页面无 `bottomPadding` 参数 | LicensePage | ✅ |

## 毛玻璃效果

| 规范要求 | 项目实现 | 状态 |
|----------|----------|------|
| 所有 Scaffold 用 BlurredBar 包裹 TopAppBar/NavigationBar | HomeScreen、LicensePage | ✅ |
| 顶层取 `rememberLayerBackdrop()` + `blurActive` + `barColor` | HomeScreen、LicensePage | ✅ |
| 内容区 LazyColumn 加 `layerBackdrop(backdrop)` | HomeScreen、LicensePage | ✅ |
| 不把 textureBlur 放进同一个 layerBackdrop 采样子树 | 已遵守 | ✅ |

## Card 间距

| 规范要求 | 项目实现 | 状态 |
|----------|----------|------|
| 水平 12.dp，bottom 12.dp | CardBlock 使用 `padding(bottom = 12.dp)`，LazyColumn 使用 `padding(horizontal = 12.dp)` | ✅ |
| 不使用 `Arrangement.spacedBy` | LazyColumn 无 spacedBy | ✅ |

## 多组件卡片

| 规范要求 | 项目实现 | 状态 |
|----------|----------|------|
| 拆为独立 lazy item | 项目卡片较小（2-5 行），暂未拆分 | ⚠️ 可优化 |
| GroupedCardItems | 未使用 | N/A（小卡片不需要） |

## 对话框

| 规范要求 | 项目实现 | 状态 |
|----------|----------|------|
| Edit Dialog 按钮顺序 `not_modified \| cancel \| confirm` | 重启作用域对话框非 Edit Dialog | N/A |
| 长内容 Dialog 限高 + 滚动 | 重启作用域对话框内容较少 | N/A |

## 国际化 (i18n)

| 规范要求 | 项目实现 | 状态 |
|----------|----------|------|
| 所有用户字符串走 `stringResource` | 已全部资源化 | ✅ |
| 同时加到 `values/strings.xml` + `values-zh-rCN/strings.xml` | 已创建双语资源文件 | ✅ |
| key 命名 `{页面}_{描述}` | 使用 `home_`、`config_`、`about_`、`license_`、`restart_`、`common_` 前缀 | ✅ |
| 通用按钮 `common_` 前缀 | `common_back`、`common_select_all`、`common_confirm`、`common_cancel` | ✅ |

## 语义色 token

| 规范要求 | 项目实现 | 状态 |
|----------|----------|------|
| 禁止散落 `Color(0xFF...)` | 无硬编码颜色值 | ✅ |
| 仅用 `MiuixTheme.colorScheme.*` | 已遵守 | ✅ |

## 可复用组件 API

| 规范要求 | 项目实现 | 状态 |
|----------|----------|------|
| 暴露 `modifier: Modifier = Modifier` 作为第一可选参 | InfoRow、SettingsInfoRow、CardBlock、AboutArrowPreference | ✅ |
| 透传到底层 Miuix 组件 | AboutArrowPreference 透传到 ArrowPreference | ✅ |

## 未适用的规范条目

| 规范条目 | 原因 |
|----------|------|
| Badge | 项目没有 Badge 组件 |
| 宽屏适配 / NavigationRail | 手机 App，不需要宽屏适配 |
| AdaptiveTopAppBar | 不需要宽屏自适应顶栏 |
| GroupedCardItems | 卡片较小，不需要拆分 |
| Flow 收集 | 项目没有使用 Flow |
| @Immutable UiState | 项目没有复杂 UiState |
| 搜索动画页面 | 项目没有搜索功能 |
