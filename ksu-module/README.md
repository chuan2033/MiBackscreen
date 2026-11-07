# MiBackscreen-dist

MiBackscreen 附加模块的打包工程，产出 zip 模块包（支持 **KernelSU 系**与 **Magisk 系**管理器，APatch 未支持；判据见主仓交接文档 6.2）。**独立目录，不在主仓 `D:\Ai\MiBackscreen` 内，无版本控制**，
所以这里的文件移动/删除没有 git 兜底，操作前先看清楚。

模块 id `mibackscreen_18_personalization`（**不改**，改名会断升级链），显示名「MiBackscreen背屏附加资源」。
当前版本 **`1.3` / `versionCode 18`**（2026-10-15 出包，输出名规则 `MiBackscreen_v<version>.zip`）。

## 目录

| 目录 | 内容 |
| --- | --- |
| `packaging/mibackscreen_18_personalization/` | **模块源码**：`module.prop`、`service.sh` / `post-fs-data.sh` / `action.sh`、`appinfo_*.sh`、`status.sh`（诊断面板）、`webroot/`（面板前端）。改功能的唯一入口 |
| `tools/` | 构建、校验、诊断脚本（见下） |
| `device_popsicle_template/` | 测试机 `popsicle` 的模板快照（`normal` / `default` 的 `rearScreen.json`），比对 ROM 原始模板用 |
| `analysis/` | 逆向分析产物：智能助理 `personalassistant` 的 `base.apk` + 手挑的关键类 `classes/`（`18rom` 与 `current` 各一份，比对混淆名用）。`fullsrc/` 全量反编译产物已清理，需要时从 `base.apk` 重新反编译 |
| `out/` | **交付产物**：当前包 `MiBackscreen_v1.3.zip`（84 条目 / 68,204,530 B / sha256 `2576C24F…722A`）；上一版 `MiBackscreen_KSU_v1.2.1.zip`（回滚底本，与线上 Release 附件逐字节一致）；`MiBackscreen_v1.2.3.apk`（线上 Release 附件，回滚底本）；`MiBackscreen_v1.2.4.apk`（遗留副本，勿再维护）；发行说明 `RELEASE_NOTES_v1.2.5.md`（现行）/ `v1.2.4` / `v1.2.3`；`screenshots/`（实机验证截图）。⚠️ **APK 发行包不再往这里复制**，APK 产物只认主仓 `app\build\outputs\apk\release\app-release.apk` |
| `archive/` | 历史包回滚底本，不属于交付链 |

## 常用命令

构建（PowerShell，**必须与 ExecutionPolicy 设置同一会话**，`-Scope Process` 换次调用即失效）：

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass -Force
& "D:\Ai\MiBackscreen-dist\tools\build_personalization_module.ps1" `
  -RomRoot "D:\Q\Downloads\DNA_WIN最终版\DNA_18promax"
```

校验（输出包名按 `module.prop` 的 version 拼，即 `MiBackscreen_v<version>.zip`）：

```powershell
& "D:\Ai\MiBackscreen-dist\tools\validate_personalization_module.ps1" `
  -ZipPath "D:\Ai\MiBackscreen-dist\out\MiBackscreen_v1.3.zip" `
  -RomRoot "D:\Q\Downloads\DNA_WIN最终版\DNA_18promax"
```

装到设备（**装完必须重启**，管理器不会自动补跑 late_start 服务）：

```bash
adb push "D:\Ai\MiBackscreen-dist\out\MiBackscreen_v1.3.zip" /data/local/tmp/
adb shell su -c 'ksud module install /data/local/tmp/MiBackscreen_v1.3.zip'
adb reboot
```

## tools/ 一览

**构建 / 校验**

| 文件 | 用途 |
| --- | --- |
| `build_personalization_module.ps1` | 从指定 ROM 构建最终 zip |
| `validate_personalization_module.ps1` | 校验包结构、脚本权限、JSON、资源引用、ROM 文件哈希，以及 `module.prop` 的 id / version / versionCode（期望值读打包源文件，升版本只改 `module.prop`） |

**设备诊断（开发者用）**

| 文件 | 用途 |
| --- | --- |
| `diagnose_rear_presets.sh` | 分层只读诊断：视图隔离 / 模块结构 / data-root 资源 / 权限 context / appInfo 六卡 / 宿主视角 / 冲突检测 / 卡片就绪度 |
| `check_mount.sh` | 环境与模块结构快照（也被 `run_check.bat` 调用） |

**发给用户的客服脚本**

| 文件 | 用途 |
| --- | --- |
| `run_check.bat` + `check_mount.sh` + `诊断说明.txt` | 一套自助诊断（双击 bat，结果落同目录）。**三个文件必须同目录**，bat 用 `%~dp0` 引用 |
| `fix_aiapp_dir_perms.sh` | 修 `rearScreenAiApp_Theme` 目录属主（「AI 生成应用卡点应用失败」故障的自助修复） |

## archive/

| 子目录 | 内容 |
| --- | --- |
| `old-builds/` | `MiBackscreen_KSU_v1.2.zip` —— 1.2.1 的直接前身（加入 `fix-aiapp.sh` 之前），留作回滚底本。**当前交付包是 `out\MiBackscreen_v1.3.zip`** |

2026-10-13 已清理：`_diag` / `_preview` / `_tmpchk` 三个临时目录、`analysis/.../fullsrc`（118M 反编译全量）、三个 Hybrid Mount 时代脚本（`flatten_product.py` / `rebuild_module.py` / `device_test_module.sh`）、三个被取代的旧包（v1.4.1 / v1.4.2 / 旧版附加模块）。
