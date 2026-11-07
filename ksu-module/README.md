# MiBackscreen 背屏附加资源模块（ksu-module）

MiBackscreen 附加模块的打包工程，产出 zip 模块包（支持 **KernelSU 系**与 **Magisk 系**管理器，APatch 未支持；判据见主仓交接文档 6.2）。

位于主仓的 `ksu-module/` 目录下，**源码与工具脚本受 git 管理**；二进制产物与逆向材料（`analysis/`、`archive/`、`out/*.zip`、`out/*.apk`、`out/screenshots/`）被 `.gitignore` 排除，只留在本地，交付走 GitHub Release 附件。下文路径都相对主仓根目录。

模块 id `mibackscreen_18_personalization`（**不改**，改名会断升级链），显示名「MiBackscreen背屏附加资源」。
版本以 `ksu-module/packaging/mibackscreen_18_personalization/module.prop` 的 `version` / `versionCode` 为准（写这份说明时是 `1.3` / `18`），输出名规则 `MiBackscreen_v<version>.zip`。

## 目录

| 目录 | 内容 |
| --- | --- |
| `packaging/mibackscreen_18_personalization/` | **模块源码**：`module.prop`、`customize.sh`、`post-fs-data.sh` / `service.sh` / `uninstall.sh` / `action.sh`、`appinfo_txn.sh` / `appinfo_utils.sh`（appInfo.json 事务读改写）、`lib.sh`、`status.sh`（诊断面板）、`fix-*.sh` / `clear-reareye.sh` / `logpack.sh`、`webroot/`（面板前端）。改功能的唯一入口。`mibackscreen_resources/`、`appcard_entries/`、`appcard_appInfo_patch.json` 与 `META-INF/` 不在源码里，由构建脚本从 ROM 生成 |
| `tools/` | 构建、校验、诊断脚本（见下） |
| `device_popsicle_template/` | 测试机 `popsicle` 的模板快照（`normal` / `default` 的 `rearScreen.json`），比对 ROM 原始模板用 |
| `out/` | 构建输出目录：`MiBackscreen_v<version>.zip`（不进库）。App 的发行说明在主仓 `docs/release-notes/`。APK 产物只认主仓 `app/build/outputs/apk/release/app-release.apk`，不往这里复制 |
| `analysis/`（仅本地） | 逆向分析产物：智能助理 `personalassistant` 的 `base.apk` + 手挑的关键类，属于小米的代码，不进库 |
| `archive/`（仅本地） | 历史包回滚底本，不属于交付链，不进库 |

## 常用命令

以下命令都在主仓根目录执行。

构建（PowerShell，**必须与 ExecutionPolicy 设置同一会话**，`-Scope Process` 换次调用即失效）：

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass -Force
& .\ksu-module\tools\build_personalization_module.ps1 -RomRoot "<ROM 解包目录>"
```

`<ROM 解包目录>` 是 18 Pro Max ROM 解包后的根目录（下面要有 `product\media\rearscreen\...` 与 `product\etc\precust_theme\...`）。输出默认写到 `ksu-module\out\`。

校验（包名按 `module.prop` 的 version 拼）：

```powershell
& .\ksu-module\tools\validate_personalization_module.ps1 `
  -ZipPath .\ksu-module\out\MiBackscreen_v<version>.zip `
  -RomRoot "<ROM 解包目录>"
```

装到设备（**装完必须重启**，管理器不会自动补跑 late_start 服务）：

```bash
adb push ksu-module/out/MiBackscreen_v<version>.zip /data/local/tmp/
adb shell su -c 'ksud module install /data/local/tmp/MiBackscreen_v<version>.zip'
adb reboot
```

## tools/ 一览

**构建 / 校验**

| 文件 | 用途 |
| --- | --- |
| `build_personalization_module.ps1` | 从指定 ROM 构建最终 zip：复制模块脚本与 webroot 并统一成「UTF-8 无 BOM + LF」，生成资源、`appcard_entries/`，以及 Magisk 安装器 `META-INF/com/google/android/update-binary`（官方 module_installer）/ `updater-script`（`#MAGISK`） |
| `validate_personalization_module.ps1` | 校验包结构（含 `appinfo_txn.sh`、`META-INF`）、脚本权限、原始字节里无 BOM / CR、JSON、资源引用、ROM 文件哈希，以及 `module.prop` 的 id / version / versionCode（期望值读打包源文件，升版本只改 `module.prop`） |

**设备诊断（开发者用）**

| 文件 | 用途 |
| --- | --- |
| `diagnose_rear_presets.sh` | 分层只读诊断：视图隔离 / 模块结构 / data-root 资源 / 权限 context / appInfo 六卡 / 宿主视角 / 冲突检测 / 卡片就绪度。默认不放剪贴板，加 `--clipboard` 才放 |
| `check_mount.sh` | 环境与模块结构快照（也被 `run_check.bat` 调用）；会列出已装模块目录名和过滤后的 logcat |

**发给用户的客服脚本**

| 文件 | 用途 |
| --- | --- |
| `run_check.bat` + `check_mount.sh` + `诊断说明.txt` | 一套自助诊断（双击 bat，结果落同目录）。**三个文件必须同目录**，bat 用 `%~dp0` 引用。bat 必须是 CRLF（`.gitattributes` 里按原样存取） |
| `fix_aiapp_dir_perms.sh` | 修 `rearScreenAiApp_Theme` 目录属主（「AI 生成应用卡点应用失败」故障的自助修复） |

## 运行时约定（改脚本前先看）

- **appInfo.json 与 LSPosed 模块共用一把锁**：`appInfo.json` 同目录的 `appInfo.json.mibackscreen.lockd`，`mkdir` 成功即持有、删目录即释放，mtime 超过 30 秒视为陈旧。shell 侧只在「读指纹 → 改 → mv 提交 → 回读校验」这一段持有（`appinfo_utils.sh` 的 `appinfo_locked_call`），最多等 30 秒。两边的协议必须一起改。
- **改写 appInfo.json 前后做不变量校验**：括号配平、`"resId"` 个数与顶层对象数符合预期（新增 +1、替换不变、删除减相应数量），不符就不提交。临时文件建在 config 目录里（`appInfo.json.mibackscreen.sh.<pid>.<op>.tmp`）。
- **post-fs-data 只做轻量清理**（陈旧锁、残留临时文件），资源同步只在 `service.sh` 里做；资源没变（签名 + 清单一致）就跳过复制。
- **卸载只走 remove_owned / restore_modified**；清理失败时保留状态目录与 data-root，写 `UNINSTALL_INCOMPLETE`，并在状态目录的 `uninstall_retry/` 留一份可手动重跑的卸载脚本。
