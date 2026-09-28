# 第 13 项持续反馈验收（2026-09-28）

本轮只收尾交接文档中的第 13 项。第 2–12 项没有继续开发或宣称验收通过。

## 实现

- 日志导出由导航条目 ViewModel 持有，使用 Application，避免保存 Activity。运行中持续显示阶段计数、防重入；异常和无效文件进入失败状态。
- 分享失败的重试只重新打开同一个文件，不重新导出；文件失效提供重新生成。成功导出不会自动打开分享器。
- 导出采用唯一文件名和临时文件，打包成功才改为 ZIP；失败清理临时文件/数据库暂存目录。保留最近一天的旧包，避免重新生成立即删掉正在分享的包。采集子进程在退出路径销毁。
- 取件选择改为 Activity ViewModel 管理，保存期间禁止新提交，成功提交后才更新勾选。新 payload 到达会等待在途写入完成再读取，旧结果/旧重试不会覆盖新页面。
- 区分远程提交成功、本地待同步、提交失败和刷新请求失败。提交后才发刷新请求；刷新失败单独重试，不重复写入。读取待同步记录时不被服务刚连接时的旧远程值覆盖。
- 兼容旧版选择记录；全选时清理相应旧记录，编码超限/未完整表示目标选择时不提交。多驿站页面的 payload、会话和令牌随 Activity 重建保存恢复。
- 保留已有重启结果/隐藏图标失败 Snackbar 接入，不修改 Hook、系统重启语义、配置键、默认值或跨进程协议。

## 构建和自动检查

- `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug --offline --console=plain`：通过。
- 55 项单测，0 失败/错误；新增 7 项取件选择事务测试。
- Lint：0 errors、37 warnings、1 hint。第 13 项引入的 PluralsCandidate、ViewModel Context StaticFieldLeak、废弃 log_generating UnusedResources 均已消除；其余警告是原有项目问题。
- 本轮修改文件 `git diff --check` 通过。
- Debug APK SHA-256：`56ea2cf8a0e30964b02366c4151af6d41a3a3bd5aa3b38b05117f225f90b9179`。
- 已卸载隔离测试包、清理测试控件树，并将上述 debug 覆盖安装到正式包；ADB 返回 Success，应用启动成功。未重启系统或作用域。

## 真机验证

设备：小米 17 Pro Max，Android 17 / API 37。独立包名 `hook.HyperBackscreen.feedbacktest`，不注册为 Xposed 模块。

可重跑脚本及说明：[`tools/feedback-validation`](../tools/feedback-validation/README.md)。测试依赖通过外部 Gradle init 脚本注入，不编入正常 debug/release。

Instrumentation 的 9 组检查均通过：慢任务进度/防重入/保留 ViewModel、导出异常和重试、无效输出拒绝、commit=false 保留勾选及成功后刷新、写入异常及本地待同步、刷新重试不重复提交、新 payload 及旧重试隔离、读取失败重试、Snackbar 替换及操作只执行一次。

实际 UI 操作也已核对：

| 场景 | 结果 | 本地证据文件（下述 evidence 目录） |
| --- | --- | --- |
| 慢导出、Activity.recreate | 进度保持 2/10，无重复导出 | log-running、log-recreated |
| 导出中进入捐赠页再返回 | 返回仍显示同一任务进度 | log-detail、log-return |
| 导出失败→重试→完成 | 错误/重试持续可见，完成可分享或重新生成 | log-failed、log-retrying、log-ready |
| 分享模拟失败→重试 | 原 ZIP 保留，exports 计数不增加 | share-failed、instrumentation-and-ui.log |
| 允许分享 | 系统分享器显示 synthetic.zip，随后直接返回 | share-chooser、share-return |
| ZIP 被移除→重新生成 | 提示文件失效，重新生成恢复进度 | share-missing、log-regenerating |
| 三种底栏 | 液态、普通悬浮、普通底栏均未遮挡 Snackbar | log-ready、floating-failed、normal-failed |
| 服务未就绪的取件选择 | 实际本地提交后勾选更新，显示待同步 | pickup-initial、pickup-pending |
| 取件页面重建 | 10-11 未选、20-22 已选保持 | pickup-recreated |
| 同会话新增第二驿站再重建 | 两组均保留，第一组选项不重置 | pickup-merged-recreated |

证据目录：`D:\Codex\MiBackscreenHuancun\evidence\feedback13`，每个 UI 场景有 PNG 和 XML。
构建日志：同级 `feedback13-build.log`、`feedback13-fixture-build.log`。

测试只使用合成 ZIP 和隔离包中的合成取件码；未采集真实诊断日志、未选择分享接收方、未修改正式包取件选择、未执行作用域重启。远程提交失败/服务状态竞态等异常用注入存储模拟；并不声称重新验证了真实小爱通知识别及 LSPosed 的整个链路。
