# Miuix 与本地玻璃代码维护约束

当前依赖以 [app/build.gradle](app/build.gradle) 为准：Miuix UI/preference/icons/blur/nav 使用 0.9.4。本文件保留仍有效的技术约束，替代旧版本变更全集、过期“未实现”计划和大段建议代码。

## 依赖边界

- 本项目使用 Miuix Backdrop/blur，不直接依赖 Kyant Backdrop。FloatingBottomBar、animation、liquid 是项目维护的移植代码；升级库不会自动更新这些文件。
- `Lens.kt`、`InnerShadow.kt`、`CombinedBackdrop.kt`、`Vibrancy.kt` 使用 Miuix 的类型和效果接口。不能将 Kyant 同名文件直接覆盖；保留源码中的 Miuix、AndroidLiquidGlass、KernelSU 来源。
- minSdk 36 下不为跨平台示例添加无关 fallback 或 Shapes 依赖，不跟随上游构建环境整体升级 AGP/Gradle/Kotlin。
- 比对上游时固定到依赖版本，不能混用 main 的 Pager API。历史核对材料位于本地 evidence/upstream-ui-2026-09-27；本轮没有重新声明任何上游“最新版本”结论。

## 模糊与布局

实现位于 `app/src/main/java/hook/HyperBackscreen/ui/`：

| 文件 | 保留约束 |
| --- | --- |
| `components/BlurredBar.kt` | 高层 progressiveTextureBlur，半径 15f、surface 混色 0.3f、Top curve 10f；持续启用，不按 offset 关闭或改整层透明度 |
| `HomeScreen.kt` | 每个 MainTabPage 独立 layerBackdrop，先绘 surface 再绘内容；顶栏/按钮置于自身采样子树之外，避免采样环；底栏独立采样 Pager |
| `components/ScrollGlassIconButton.kt` | 44dp/22dp，圆形背景模糊与渐变；只渐变背景，图标/点击区域稳定；标题位移与列表滚动共同判断 |
| `components/FloatingBottomBar.kt` | 普通层 onSurface、选中层 primary；三项宽 248dp，80dp/项加 8dp；模糊/着色副本不参与焦点、键盘或点击语义 |

当前顶栏参数来自用户确认的设计，不是 Miuix 默认值。不恢复已放弃的 18/0.42/6、滚动透明度联动或“实色遮横线”方案。保留正常 contentPadding 与透明 TopAppBar，不用额外实色底板掩盖模糊问题。

## 导航、手势与动画

- HomeRoute 使用序列化路由和 miuix-nav；详细恢复边界见[导航验证](docs/miuix-nav-migration.md)。不恢复旧 detailPage 枚举导航。
- 页面横滑选中项跟随 pagerState.currentPage；点击使用独立 TabPageRequest + springToPage。完成/取消只清对应请求，横滑接管清除点击请求；不要再用 selectedPage 同时驱动观察和切页命令。
- CrossAxisPager 在横向意图成立后消费，配套 nested scroll；不能只替换一个 modifier 而保留冲突的原生滚动入口。
- 液态栏整栏按压预览，松手提交；取消恢复原索引、不提交。保留键盘/辅助功能路径，避免重复提交。
- DampedDragAnimation 取消旧 press/release 作业；速度使用单调时钟、snapTo 和非零跨度保护。
- 高光倾斜 3° 量化，在绘制阶段读 State；remember 包含方向/动画实例，回调使用最新引用。模糊前至少预留 40dp 采样边界。
- 已有这些代码不等于快速手势、焦点、重组性能或功耗已完成测量。

## 验证与来源

剩余风险统一维护在 [UI_AUDIT.md](UI_AUDIT.md)，设备范围见[回归矩阵](docs/DEVICE_TEST_MATRIX.md)。保留默认字号/大字号、深色/Monet、模糊开关、快速取消、配置变化和不同输入方式验证，不凭单张截图或上游提交声明项目已修复。

来源：[Miuix](https://github.com/compose-miuix-ui/miuix)、[AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)、[KernelSU](https://github.com/tiann/KernelSU)。本地移植保留各文件版权/许可证说明；项目许可证及用户致谢见 [README](README.md#许可证与致谢)。
