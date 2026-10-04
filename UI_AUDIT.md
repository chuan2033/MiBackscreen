# UI 与设置链路：保留问题

本文件维护工程审查后的逐项修复进度；每项完成代码与相应检查后标注“（已完成）”。工程入口见[工程说明](交接文档.md)，视觉/手势约束见 [Miuix 与玻璃维护说明](MIUIX_0.9.4_AND_LIQUID_GLASS_REVIEW.md)。安装结果与各项真机验收分别记录，不能以构建或安装成功代替行为验证。

## 静态问题修复进度

代码路径相对于 `app/src/main/java/hook/HyperBackscreen/`。“（已完成）”表示修复已实现并通过所列验证，不代表未执行的真机矩阵已验收：

| 项目 | 当前依据与影响 | 后续验证 |
| --- | --- | --- |
| A01 设置 UI 刷新不完整（已完成） | 新增 ModulePreferencesSnapshot；监听 App 缓存变化、服务状态和 ON_RESUME，刷新全部十项 Hook 设置（含 enablePickup）。主线程处理事件时读取当前缓存，不回写、不重放后台旧快照；退出组合注销监听 | 新增 7 项测试覆盖三面板键、取件开关、待同步值、排队刷新和注销；68 项单测、Debug、Lint 通过（0 errors / 31 warnings / 1 hint）。日志 build/fix-a01-verification.log；真实 LSPosed 重连/前后台切换未实测 |
| A05 空点击语义（已完成） | CardBlock 保留可空 onClick，所有只读 Card 的 onLongPress 为 null；HomeStatusCard 两项回调均为空值，真实子控件与有动作卡片回调保留 | 核对 Miuix 0.9.4 Card 仅在非空回调时添加 combinedClickable；Debug 编译通过，日志 build/fix-a05-verification.log；TalkBack 真机焦点未实测 |
| A07 系统作用域重启（已完成） | 沿用“重启作用域”原布局、“系统”名称和“确定”按钮，移除顶部说明段，新增“智能助理”（com.miui.personalassistant）；勾选系统并确认后执行 su -c reboot；与应用共同勾选时只重启一次。只选应用仍执行停止应用，普通开关触发的自动刷新无重启权限；拒绝非法包名 | 85 项单测、Debug、Lint 通过（0 errors / 30 warnings / 1 hint）；命令测试覆盖确认重启、混合选择去重、自动刷新拒绝重启及非法输入。日志 build/restart-scope-layout-verification.log；覆盖智能助理命令映射，未执行真实 root/reboot 命令，设备重启行为由用户自行确认验证 |
| A08 外链失败反馈（已完成） | 外链返回启动结果，失败显示可重试提示；更新下载失败保留弹窗并在弹窗内显示错误/重试。主题店两个注入入口启动失败或目标缺失时保留日志并显示本地化提示 | 新增 3 项启动结果测试，74 项单测及 Debug 通过；日志 build/fix-a08-verification.log；未在真实设备禁用浏览器或触发宿主错误 |
| A09 部分硬编码文案（已完成） | AI 标题按宿主语言读取模块资源；通知空选择优先宿主取件标签并支持双语回退；面板全部降级文案支持中英文；装饰 Logo 不再提供硬编码说明。动态 AI 标题添加精确资源保留规则 | 新增 3 项语言/空选择格式测试，77 项单测及 Debug 通过；日志 build/fix-a09-verification.log；真实英文宿主和 APK 替换后的资源降级待设备验证 |
| A10 应用加载失败处理（已完成） | 查询异常显示错误/重试，空可见集合与搜索无结果分别提示；保留现有列表和已选包，单个标签读取失败使用包名，取消页面任务不显示伪错误。发现已选但不可见的包时说明仍可取消选择，不推断厂商权限编号 | 新增 4 项查询/空集合/重试/取消测试，81 项单测、Debug、Release/R8 及 Lint 通过（0 errors / 30 warnings / 1 hint）；日志 build/fix-a10-final-verification.log；设备权限受限界面尚未实测 |

## 待运行确认的风险

- A11：短窗口、大字体、长英文及 IME 下，状态卡/对话框的文案和按钮是否完整可达。不能仅凭固定高度认定已复现裁切。
- A12：SwipePanelHost 底部 22% 区域在 DOWN 时接管触摸，放大字体或滚动后的开关可能落入该区域；没有本轮复现，不改现有关闭手势。
- A13：“已激活”只表示 Xposed 服务连接，不能推断作用域勾选或 Hook 全部成功。
- 顶栏持续渐进模糊已保留；历史默认字号截图仍有约一级亮度变化，色带根因及深色/Monet 覆盖未关闭。不得用关闭模糊或覆盖实色底板代替验证。
- 液态栏取消、快速交错操作、TalkBack/键盘唯一焦点、RTL、鼠标/触控板与功耗尚缺完整矩阵。

## 已有实现及证据边界

- A02 的选择提交失败/编码超限处理已进入 PickupSelectionRepository / ViewModel，包含待同步和刷新失败区分；有单元测试及合成设备验证。
- A03/A04 的深色前景、导航名称和 selected 语义已补齐；装饰副本禁用交互。完整无障碍/主题矩阵未验收。
- A06 的详情栈/主 Tab/捐赠项恢复已实现；取件分组/session/token 也保存恢复。见[导航验证](docs/miuix-nav-migration.md)和[取件验证](docs/feedback13-validation-2026-09-28.md)。
- A14 日志任务已有 ViewModel、阶段进度、防重入、失败/分享重试和锚定弹出卡片，不再使用日志 Snackbar。历史合成场景不能证明真实 root 采集链路。

原始设备证据根目录：`D:\Codex\MiBackscreenHuancun\evidence`，UI 初查为 `ui-audit-2026-09-27`。它们是本地历史资料，不是可移植仓库依赖。
