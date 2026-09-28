# miuix-nav 导航迁移验证

日期：2026-09-27；基于当前工作区，保留原有未提交改动。

## 改动

- 引入 `miuix-nav-android:0.9.4`、Kotlin Serialization 插件2.3.21及JSON运行库1.11.0；其余现有Miuix版本保持一致。
- `HomeRoute`定义可序列化的 Main / Settings / License / AppPicker / Donate 路由。
- 主应用以 `rememberNavBackStack<HomeRoute>` + `NavDisplay`管理返回栈，替换原来的detailPage枚举状态和手写AnimatedContent转场。
- 三个主Tab与现有底栏继续使用原有Pager和外观；详情页通过库默认Miuix转场和系统预测返回处理。没有新增全屏滑动关闭手势。
- 移除四个详情页的独立BackHandler，顶部返回按钮与系统返回使用同一受保护的pop路径；根页面不会被pop成空栈。
- 连续打开操作仅从Main入栈，避免同一目的地重复contentKey；转场期间阻止非稳定页面点击。
- 切换栈顶清理输入焦点；遮盖页面保留恢复所需状态，但清除其对外语义，避免底层控件出现在控件树中。
- 主Tab选择、捐赠支付方式使用rememberSaveable；应用选择页原有可保存的搜索状态通过导航entry恢复。
- 更新ProGuard注释，使用序列化依赖自带consumer rules，没有新增全量UI keep规则。

修改文件：`build.gradle`、`app/build.gradle`、`app/proguard-rules.pro`、`ui/HomeScreen.kt`、新增`ui/HomeRoute.kt`、`ui/about/DonatePage.kt`、`ui/about/LicensePage.kt`、`ui/config/AppPickerPage.kt`、新增`HomeRouteTest.kt`。

`ui/`相对于`app/src/main/java/hook/HyperBackscreen/`。独立取件码Activity没有内部多页面导航，本轮未迁移或修改其业务实现。之前UI审查发现的配置同步、保存失败等问题不属于本轮迁移修复。

## 构建和单元测试

实际执行：

```powershell
$env:JAVA_HOME = 'D:\Codex\MiBackscreenHuancun\tools\jdk-21.0.2'
$env:GRADLE_USER_HOME = 'D:\Codex\MiBackscreenHuancun\gradle'
& 'D:\Codex\MiBackscreenHuancun\tools\gradle-9.6.0\bin\gradle.bat' `
  :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --console=plain
```

- 最终Debug构建、Lint及单元测试任务通过。
- **48项单测，0失败/错误/跳过**；新增2项覆盖所有详情栈JSON往返和保存的路由名兼容性。
- Lint：**0 errors、37 warnings、1 hint**，与迁移前数量相同。
- 未升级AGP/Compose compiler/Gradle主版本；新序列化插件与当前工具链编译验证通过。
- 未构建/验收release混淆包，也没有把Debug结果写成release已验证。

日志：[build-final.log](/D:/Codex/MiBackscreenHuancun/evidence/miuix-nav-migration/build-final.log)。

## 真机验证方法

设备：Xiaomi 17 Pro Max / Android17 API37，1200×2608、480dpi、字体比例0.9、中文。

使用外部Gradle init脚本生成独立测试包 `hook.HyperBackscreen.navaudit`，输出目录也独立。排除 `META-INF/xposed/**` 并核实APK中没有LSP模块注册元数据；测试包只运行UI，没有启用Hook。正式项目构建不包含测试Application或重建广播入口。

测试Application仅在独立包中提供受DUMP权限保护的Activity.recreate入口，并种入本地测试外观/名单入口开关。测试中没有写生产模块偏好、停止生产作用域或改系统设置。进程恢复测试仅对独立测试包执行后台 `am kill`，PID从4917变为7669，并从系统保留任务恢复；不是清数据或force-stop测试。

| 场景 | 结果 | 证据名称（同名PNG/XML） |
| --- | --- | --- |
| 关于→设置 | 正常打开，原底栏不出现在详情页 | final-settings |
| 设置页Activity重建 | 保持设置页 | final-settings-recreated |
| 被遮盖的关于页语义 | 最终详情树不再暴露MiBackscreen标题/底栏等隐藏节点 | final-settings |
| 颜色菜单→返回 | 先关菜单，仍留设置页；再次返回才回关于 | color-popup、popup-dismissed、final-back-about |
| 功能→应用选择 | 正常进入，保留原有加载/空结果逻辑 | final-function、picker-open |
| 搜索内容→Activity重建 | EditText实际文本恢复一致 | picker-search、picker-search-recreated |
| 搜索页→后台终止测试进程→恢复任务 | 冷启动后仍在应用选择页，搜索文本一致 | picker-process-restored、runtime.log |
| 应用选择页顶部返回 | 返回原来的功能Tab，键盘退出 | picker-return-function |
| 捐赠页选支付宝→Activity重建 | 仍显示支付宝二维码，人工查看截图确认 | donate-alipay、donate-restored |
| 系统左边缘返回手势 | 捐赠返回主页 | gesture-return-home |
| 关于→许可→顶部返回 | 正常打开许可，再回关于 | license-final、license-final-back |
| 连续3次点击设置入口 | 没有重复栈/重复key崩溃，一次返回回关于 | rapid-settings、rapid-back |
| 根页系统返回 | 新启动测试根页后按返回，恢复之前的生产应用任务；没有空白导航栈 | 末轮ADB活动状态检查 |

保存证据自动断言12项全部通过，见 [assertions.json](/D:/Codex/MiBackscreenHuancun/evidence/miuix-nav-migration/assertions.json)。捐赠图片选择另经截图人工验证。没有在本轮开启TalkBack或全面覆盖所有预测返回取消轨迹/不同屏幕与系统版本。

早期UIAutomator有未取得新树时残留旧XML的情况；测试工具随后增加dump成功检查。早期`about/settings/resumed`等不匹配的捕获不作为通过证据，以上列出的final或专项捕获才计入验证。搜索输入会被当前输入法转换，验证比较的是三次实际EditText文本，而非假设ADB输入原字符串不变。

## 交付与清理

- APK：[app-debug.apk](/D:/Codex/MiBackscreen/app/build/outputs/apk/debug/app-debug.apk)。
- SHA-256：`e8f8f3c6218e05124f7e3f4164f62f3bbd8be6b9677a505157076a656c9b3ad0`。
- 正式APK保留原模块元数据，不包含测试入口；临时包测试结束后卸载，临时设备XML删除。
- 生产安装APK没有覆盖；其SHA-256仍为`ed7f613b3b650aa07f6531454aca6329bd2f761d7bc2cf4ca9974b01c3baf394`。
- 调查脚本、测试构建、截图和日志保存在本地缓存 [miuix-nav-migration](/D:/Codex/MiBackscreenHuancun/evidence/miuix-nav-migration)，不加入项目源码。

本轮验证限定于主应用导航迁移，未声称旧UI审查列出的所有问题已修复。
