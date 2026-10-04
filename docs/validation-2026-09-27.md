# 本地背屏验证记录（2026-09-27）

本文是 2026-09-27 的历史证据，不能替代当前提交验收。当前工程规则与签名前提见[工程说明](../交接文档.md)。

## 分支与环境

- 在 `feat/18pro-max-rear-screen` / `5ffe821` 上继续开发；主线基线 `48921ad`，不合入 main。
- 仓库工作目录：`D:\Codex\MiBackscreen`。
- 工具、宿主反编译资料、APK 备份和日志：`D:\Codex\MiBackscreenHuancun`。该目录不提交。
- ADB 设备：popsicle / 2509FPN0BC，小米 17 Pro Max。
- 实测系统：Android **17 / API 37**，`OS4.0.0.44.XPBCNXM`。与最初描述的 Android 16 / API 36 不同。
- 副屏：976 × 596，左侧挖孔 296px，displayId 1。
- 背屏中心：`RELEASE-1.0.2609181659` / 426091816。
- 小爱：`8.2.62.3916` / 508002062。
- 只构建 debug，保留日志；debug 使用用户指定的 release keystore 签名，不构建发行版。

## 上游实际能力

以下结论来自与设备版本一致的本地 APK 和从设备提取的 `miui-services.jar`，没有把设备名称或混淆名当作功能证据。

| 目标 | 实际行为 | 修复约束 |
| --- | --- | --- |
| `e2.k.c()` | 返回内部列表的 `ArrayList` 副本，Binder 插入/删除等操作也消费它 | 不注入 UI 专属模块卡片，否则计入官方容量上限 |
| `e2.k.d(Consumer)` | 内部列表空时异步加载；非空时立即分发副本 | 只在分发结果中注入一次，不能提前把内部列表变成非空 |
| `e2.k.e(ArrayList,String,e2.a)` | 异步保存列表 | 去掉模块条目，恢复容量绕过列表的全部内容 |
| `T1.c.onTransact` 交易 11 | `insertAppWidget` 检查 `size >= 15`，随后查重、追加、保存 | 数量绕过仅限该交易，保存不能丢失第 15 张以后的条目 |
| `U1.C.g/f/run` | g 判断可否长按，f 调度/取消，run 进入编辑 | 保留宿主正常手势，仅在开关开启时拦截 |
| `DualScreenCoverManager` | `android.policy:KEY` 是双击唤醒；POWER、通知等是其他来源 | 仅过滤副屏双击；只看当前主屏任务及其关联 Activity |
| 小爱 `memory.island.d.b/h` | focused/tiny RemoteViews 构建方法，当前签名仍适用 | 按完整签名、包名、布局校验 |
| `NotificationManager.notify` | 两参数版本委托到三参数版本 | 只 Hook 三参数入口，避免重复加工/捕获 |
| libxposed API 102 `getRemotePreferences` | Hook 进程返回只读偏好 | 删除不再使用的令牌持久化写入，设置写回由模块 Provider 负责 |

API 102 的只读契约也已从 Maven 发布的 `api-102.0.0-sources.jar` 核对。宿主反编译文件是调查材料，不提交其源码。

## 本轮修复

- 模块卡片只注入展示分发；PNG 在进程内复用，缓存被清理后重新生成；编码完成释放位图；字段不匹配时整张卡片放弃注入。
- 通知刷新从有界缓存中的宿主快照重建，不累计上次刷新追加的 RemoteViews 操作；不修改调用者通知；取消通知释放快照；替换通知时检查当前内容。
- 保持通知 tag 为 null 与空字符串的身份区别；刷新总开关关闭时不再重发。
- 刷新保持页面会话，不把用户勾选当作新的识别批次；移除已失效的令牌偏好写入。
- 面板写设置转入有界后台队列；Provider 仅在远程提交完成后回报成功。冷启动通过服务就绪事件在 Binder 工作线程最多等待一秒，不轮询、不阻塞背屏 UI；失败不残留待写值。服务重连补交取件码/应用卡开关。
- 按实际 Method 去重 Hook，允许失败目标后续重试；主题页不再 join 等待数据库线程，合并待同步选择；AI 索引同步不并发；资源修复采用有界去重重试，无睡眠线程。
- 面板关闭、宿主暂停/销毁或窗口分离时恢复原有手势排除区；不可见面板不拦截触摸。
- 修正 `U1.C0118n`（JADX 的 Windows 文件名别名）为实际 DEX 类名 `U1.n`，恢复与官方动画状态的同步。
- 系统唤醒优先 Hook PowerManagerServiceImpl；仅主目标不可用时 Hook cover manager，避免同一次官方委托链重复查询前台任务。

## 宿主探针

构建与签名步骤统一见 [README](../README.md#构建)。以下探针可能临时写配置或发布合成通知，须有当前任务授权才可执行。

debug 的 `hook.HyperBackscreen.DEBUG_PROBE` 动态广播入口在小爱/背屏/主题商店进程注册，要求发送方具备 `android.permission.DUMP`。它不在 release 源集中。

```powershell
$adb = 'D:\Codex\MiBackscreenHuancun\tools\platform-tools\adb.exe'
& $adb shell am broadcast -a hook.HyperBackscreen.DEBUG_PROBE -p com.xiaomi.subscreencenter --es command cards
& $adb shell am broadcast -a hook.HyperBackscreen.DEBUG_PROBE -p com.xiaomi.subscreencenter --es command prefs
& $adb shell am broadcast -a hook.HyperBackscreen.DEBUG_PROBE -p com.miui.voiceassist --es command pickup-post
& $adb shell am broadcast -a hook.HyperBackscreen.DEBUG_PROBE -p com.miui.voiceassist --es command pickup-refresh
& $adb shell am broadcast -a hook.HyperBackscreen.DEBUG_PROBE -p com.miui.voiceassist --es command pickup-status
& $adb shell am broadcast -a hook.HyperBackscreen.DEBUG_PROBE -p com.miui.voiceassist --es command pickup-clean
```

`cards` 校验 getter 没有注入、分发恰好一张、保存过滤正确；`prefs` 反转并恢复长按开关，核实跨进程回读；`pickup-post` 使用保留 ID 960927 发布两张合成测试通知，检查调用者对象未被修改。反复刷新后核对通知大小不增长，结束后清理测试通知。不会发送短信、调用识别服务或写入真实取件记录。

## 结果

| 项目 | 结果与证据 |
| --- | --- |
| 单元测试 | 46 项，0 失败/错误/跳过；包含新增会话保留、刷新不延长识别批次、超限保存尾部完整性回归 |
| Debug 构建与 lint | 最终构建通过（59s）；lint 0 errors、37 warnings、1 hint；build-debug-final.log |
| 签名/安装 | `CN=Q`，SHA-256 `f903d3c18c4cade17a8754dbf9306f3453e67a79d3bf366bf7ae95fc2f321e80`；ADB `install -r -t` 返回 Success，没有卸载 |
| 宿主卡片 | 真机 getter=21、dispatch=22、module=1、saveStrip=true；冷启动及多次热分发通过 |
| 图片复用 | 重启后首次生成，随后 4 次热分发 PNG 时间戳/大小不变；images-warm-before/after.txt |
| 面板开关桥 | prefs roundTrip=true，restoreAccepted=true；界面截图确认三个开关显示；最终包两次强制停止模块进程后直接写入，首次操作均通过 |
| 长按禁用关 | 点击面板关闭禁用 → 退出模块面板和官方列表 → 长按 1 秒，进入原厂编辑；round3-editor2.png |
| 长按禁用开 | 重新开启并关闭面板，长按 1 秒仍停留壁纸；round3-longpress-on.png；原值已恢复 |
| 通知两个重载 | 合成通知各捕获一次；调用者大小仍为 22,284 字节，没有原地修改 |
| 连续刷新 | 首次增强后 23,180 字节；多次刷新保持 23,180 字节，两张通知都在，未累加 RemoteViews 动作 |
| 页面勾选 | 真机页面取消一个码后 focused、expand、tiny、rear 都重绘；恢复全选后再次重绘；合成通知清理完毕 |
| 系统重启 | 最终系统 Hook 在 PID 4371 加载，唤醒仅安装 PowerManagerServiceImpl 主入口；provider/App 末轮修复随后覆盖安装并重启应用作用域，系统 Hook 代码未再变动 |
| 未选前台应用 | KEY / display 1，foreground=hook.HyperBackscreen，skipped=false；游戏留在后台也不误拦截 |
| 游戏/SDK 前台 | 用户实际双击：foreground=com.xiaomi.gamecenter.sdk.service,com.metek.ultraman.mi，skipped=true；覆盖游戏跳 SDK 页场景 |
| 其他唤醒来源 | 最终系统接口注入验证：游戏前台 KEY 后副屏 mWakefulness=0，POWER 后为 1；POWER 未被模块双击规则拦截 |
| 取消通知 | 最终包 cancel 后缓存清空，再刷新日志显示 no captured notifications，active count=0，不复活合成通知 |
| 主题页 | 正常打开妙享背屏，预览与当前金鱼壁纸一致；一次 WARM 启动 TotalTime=201ms（单次观测，不是性能对照实验） |
| 空闲线程 | prefs 测试时存在 MiBackscreen-IO，空闲超过 30 秒后 /proc/6796/task 中不再存在 |
| 清理 | 临时 SCREEN_BRIGHT_WAKE_LOCK 已释放；未修改用户游戏名单、壁纸选择；取件码测试选择恢复全选并清理合成通知 |

完整矩阵见 [真机回归矩阵](DEVICE_TEST_MATRIX.md)。未逐项证明的部分：真实快递通知从小爱识别到确认取件的全流程、新增/删除实际应用卡并写入宿主文件、下载新主题后资产迟到的重试路径、跨固件兼容性及长时间功耗。源码/单元测试/合成通知不能替代这些场景的实际验收。

设备日志与截图保存在缓存 evidence 目录，仅本地保留。所有“通过”均限定于表中场景。

当日仅构建并安装 Debug，未构建 Release；安装及工作区状态不在历史文档中持续维护。
