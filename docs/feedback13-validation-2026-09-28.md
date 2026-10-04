# 日志与取件选择验证

2026-09-28 的合成设备验证记录。当前链路见[工程说明](../交接文档.md)，可复现工具见[feedback-validation](../tools/feedback-validation/README.md)。

当日 55 项 JVM 测试通过（含 7 项选择事务测试），Debug 构建成功，Lint 0 errors / 37 warnings / 1 hint。结果属于当时版本，不是当前提交的真机验收。

## 已验证范围

设备为小米 17 Pro Max / Android 17 API 37，使用独立 `hook.HyperBackscreen.feedbacktest`，不注册 Xposed。

Instrumentation 9 组检查通过：慢任务进度/防重入/ViewModel 保留、异常与重试、无效文件拒绝、commit=false 保留勾选、写入异常/待同步、刷新重试不重复提交、新 payload 与旧重试隔离、读取失败重试、通用 Snackbar 替换及单次动作。

| 合成场景 | 结果 | evidence/feedback13 下的证据 |
| --- | --- | --- |
| 慢导出、Activity 重建、切详情返回 | 保留任务和进度，无重复导出 | log-recreated、log-return |
| 失败/重试/完成 | 保留重试；完成后手动分享或重生 | log-failed、log-retrying、log-ready |
| 分享失败再试 | 同一 ZIP，exports 不增加 | share-failed、instrumentation-and-ui.log |
| 分享器打开 | 显示 synthetic.zip 后返回，未选择接收方 | share-chooser、share-return |
| 文件失效 | 要求重生并恢复进度 | share-missing、log-regenerating |
| 离线选择、重建、合并第二驿站 | 待同步反馈；分组及选择保留 | pickup-pending、pickup-recreated、pickup-merged-recreated |

早期截图中的日志 Snackbar 是历史 UI，随后已由锚定卡片替代，不能当当前外观依据。当前关闭卡片不取消导出，重开不重复任务；真实分享和真实 root 采集需要另行验证。

证据根目录：`D:\Codex\MiBackscreenHuancun\evidence`。日志为 feedback13-build.log / feedback13-fixture-build.log；隔离包已清理。仅使用合成 ZIP、合成取件码及注入存储模拟服务失败；没有采集真实诊断包、修改正式选择、发送分享内容或重启作用域，不证明真实小爱识别到 LSPosed 的全链路。
