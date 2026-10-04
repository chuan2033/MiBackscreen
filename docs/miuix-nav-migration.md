# 导航恢复验证

以下是 2026-09-27 的历史验证，非当前工作区全量验收。导航实现与维护约束见[UI 技术说明](../MIUIX_0.9.4_AND_LIQUID_GLASS_REVIEW.md)。

## 实现边界

HomeRoute 定义 Main / Settings / License / AppPicker / Donate，可序列化并由 rememberNavBackStack + NavDisplay 管理。统一顶部/系统返回，根栈不弹空、重复打开不重复入栈；隐藏页清理焦点和对外语义。主 Tab、捐赠支付方式及应用搜索状态可恢复。独立取件 Activity 的恢复不属于此轮导航迁移。

路由使用编译生成 serializer 及依赖 consumer rules，没有全量 keep UI。测试位于 HomeRouteTest、HomeNavigationPolicyTest。

## 历史证据

当日 48 项 JVM 测试、Debug 构建与 Lint 通过；Lint 0 errors / 37 warnings / 1 hint，当日未做 Release/R8。

设备：小米 17 Pro Max，Android 17 / API 37，1200 × 2608、480dpi、字体比例 0.9、中文。使用独立包 `hook.HyperBackscreen.navaudit`，排除 Xposed 元数据，独立输出/缓存及 DUMP 保护重建入口。未修改生产配置、停止生产作用域或改变系统设置。

| 场景 | 已验证结果 | evidence/miuix-nav-migration 下的证据 |
| --- | --- | --- |
| 设置页重建 | 保持页面；下层导航不暴露语义 | final-settings、final-settings-recreated |
| 颜色弹窗返回 | 先关弹窗，再回关于 | popup-dismissed、final-back-about |
| 搜索与重建 | Activity 及测试进程重建后保留目的地与实际输入文本 | picker-search-recreated、picker-process-restored、runtime.log |
| 顶部返回 | 回原功能 Tab，键盘退出 | picker-return-function |
| 捐赠重建 | 保留支付宝二维码 | donate-restored |
| 许可/边缘返回 | 回原页面；根返回不产生空栈 | license-final-back、gesture-return-home |
| 连续打开 | 连点三次无重复栈/重复 key | rapid-settings、rapid-back |

12 项保存证据断言通过，捐赠选项另经截图确认。证据根目录 `D:\Codex\MiBackscreenHuancun\evidence`。仅成功采集的新控件树和截图计为证据；不能复用失败采集留下的旧 XML。

未覆盖 TalkBack、所有预测返回取消轨迹、跨屏幕/系统矩阵。历史隔离包已卸载。安装状态和 APK 哈希随构建变化，不在本文维护。
