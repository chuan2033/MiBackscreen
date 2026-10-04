# 萌宠人脸录入检测回归

`FaceEnrollmentDeviceTest.java` 使用 Android 自带 org.json，测试生产代码
`FaceEnrollmentReader.parseCount`。不采集人脸模板，不增删录入，不改变系统设置。

覆盖已有录入、空录入、不同用户、缺失用户、无权限/空输出、截断、非法类型、负数、重复用户、
非人脸服务、多传感器、溢出、输出上限，以及已有录入后变为空/读取失败。
共 17 项；`live` 参数额外只读查询用户 0 的实际数量。下列是设备测试命令，需当前任务授权；路径为本机示例，其他环境按实际 JDK/SDK 调整。

2026-09-28：17 项在手机上通过；以 `run-as hook.HyperBackscreen` 身份执行生产 reader，
实时返回 2，与 `dumpsys face` 的系统录入数量一致。测试无录入/移除场景使用合成 JSON，
没有移除用户真实人脸。55 项项目单测通过，lint 0 errors、37 warnings、1 hint。

复现（已有 JDK/SDK，无需额外依赖）：

```powershell
$env:JAVA_HOME = 'D:\Codex\MiBackscreenHuancun\tools\jdk-21.0.2'
$faceTestDir = 'D:\Codex\MiBackscreenHuancun\evidence\face-enrollment\device-test'
New-Item -ItemType Directory -Path $faceTestDir -Force | Out-Null
& "$env:JAVA_HOME\bin\javac.exe" --release 17 `
  -cp 'D:\Codex\MiBackscreenHuancun\sdk\platforms\android-37.0\android.jar' `
  -d $faceTestDir `
  'app\src\main\java\hook\HyperBackscreen\bridge\FaceEnrollmentReader.java' `
  'tools\face-enrollment-validation\FaceEnrollmentDeviceTest.java'
& "$env:JAVA_HOME\bin\jar.exe" cf "$faceTestDir\test-input.jar" `
  -C $faceTestDir FaceEnrollmentDeviceTest.class -C $faceTestDir hook
& 'D:\Codex\MiBackscreenHuancun\sdk\build-tools\37.0.0\d8.bat' `
  --min-api 36 --output "$faceTestDir\face-test.jar" "$faceTestDir\test-input.jar"
& 'D:\Codex\MiBackscreenHuancun\sdk\platform-tools\adb.exe' push `
  "$faceTestDir\face-test.jar" /data/local/tmp/mibackscreen-face-test.jar
& 'D:\Codex\MiBackscreenHuancun\sdk\platform-tools\adb.exe' shell chmod 444 /data/local/tmp/mibackscreen-face-test.jar
& 'D:\Codex\MiBackscreenHuancun\sdk\platform-tools\adb.exe' shell run-as hook.HyperBackscreen `
  env CLASSPATH=/data/local/tmp/mibackscreen-face-test.jar app_process /system/bin FaceEnrollmentDeviceTest live
& 'D:\Codex\MiBackscreenHuancun\sdk\platform-tools\adb.exe' shell rm /data/local/tmp/mibackscreen-face-test.jar
```

Debug 的现有 DUMP 权限保护探针增加 `face-status`，仅在主题商店进程可用；调用真实的
`RearScreenBiometricHelper.k(Context)`，不点击应用按钮、不触发人脸录入或身份验证。
需要用户自行重开主题商店，或另行授权重启后，新 Hook 才会加载：

```powershell
& 'D:\Codex\MiBackscreenHuancun\sdk\platform-tools\adb.exe' shell am broadcast `
  -a hook.HyperBackscreen.DEBUG_PROBE -p com.android.thememanager --es command face-status
& 'D:\Codex\MiBackscreenHuancun\sdk\platform-tools\adb.exe' shell logcat -d -s MiBackscreen:I
```

历史验证止于安装和 reader 查询，未执行主题商店宿主探针，也未验收萌宠应用流程。历史授权不作为后续任务的设备操作授权。

依赖边界：兼容查询依赖模块已有 root 授权及系统 FaceProvider 摘要格式。root 拒绝、超时、
格式不匹配或缺失当前用户均返回未知，不伪造已录入，不复用旧的正数。
