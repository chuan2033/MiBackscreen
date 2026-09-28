# 第 13 项反馈流程验证

此目录的源码不属于生产源集。用 `isolated.init.gradle` 才会加入 APK，包名为
`hook.HyperBackscreen.feedbacktest`，排除 Xposed 注册文件，只使用合成日志和内存中的取件码记录。
不启用此包为模块，不执行真实作用域重启，不发送分享内容给任何应用。

```powershell
$env:JAVA_HOME = 'D:\Codex\MiBackscreenHuancun\tools\jdk-21.0.2'
$env:GRADLE_USER_HOME = 'D:\Codex\MiBackscreenHuancun\gradle'
.\gradlew.bat -I tools/feedback-validation/isolated.init.gradle --project-cache-dir D:\Codex\MiBackscreenHuancun\evidence\feedback13\fixture-gradle :app:assembleDebug --offline --console=plain
$feedbackAdb = 'D:\Codex\MiBackscreenHuancun\sdk\platform-tools\adb.exe'
& $feedbackAdb install -r -t D:\Codex\MiBackscreenHuancun\evidence\feedback13\isolated-build\outputs\apk\debug\app-debug.apk
& $feedbackAdb shell am instrument -w hook.HyperBackscreen.feedbacktest/hook.HyperBackscreen.feedbacktest.FeedbackInstrumentation
```

Instrumentation 的 `Feedback13` 日志逐项报告模型验证结果。失败会输出堆栈并返回
`INSTRUMENTATION_CODE: 0`；通过返回 `INSTRUMENTATION_CODE: -1`。

## UI 验证

启动 `hook.HyperBackscreen.feedbacktest/.FeedbackTestActivity`（实际完整类名为
`hook.HyperBackscreen.feedbacktest.FeedbackTestActivity`），可传 `--es bar liquid|floating|normal`。

主页点击“日志”后会在锚定卡片中停在 1/10，最多等待 180 秒；关闭再打开卡片不得重复生成。
控制广播要求发送方拥有 DUMP 权限，ADB shell 可以发送：

```powershell
& $feedbackAdb shell am broadcast -a hook.HyperBackscreen.feedbacktest.CONTROL -p hook.HyperBackscreen.feedbacktest --es command recreate
```

`command` 支持：

- `recreate`：重建当前 Activity。进度和生成任务不能重置。
- `fail`：当前任务抛出合成异常；检查错误提示、重试按钮及重试后 Running 状态。
- `succeed`：当前任务生成一个只含 `synthetic.txt` 的 ZIP；检查分享/重新生成入口。
- `progress`：配合 `--ei stage 2` 或 `--ei stage 5` 推进合成任务阶段，验证计数与进度条一起更新。
- `share-ok`：分享最初模拟 ActivityNotFoundException；发送后允许打开系统分享选择器。
- `delete-file`：删除合成 ZIP，验证文件失效时显示“重新生成”。
- `status`：在 `Feedback13` logcat 标签打印 exports/shares 次数，确认重建不重启导出、分享重试不重新生成。

等待期间还应打开捐赠详情再返回、切换标签页，并检查三种底栏均不遮挡 Snackbar。
打开系统分享选择器后返回即可，不选择任何接收方。

独立 project cache 避免 Gradle 把生产/测试目录中的上次产物当作旧输出清除。
验证结束仅卸载这个隔离包，回到正式应用。生产构建必须不传 `-I`。
