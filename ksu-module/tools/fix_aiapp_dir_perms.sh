#!/system/bin/sh
#
# MiBackscreen · 修复背屏「AI 生成应用卡」目录属主 / 权限
#
# 症状
#   主题商店里下载 AI 生成应用卡显示成功，但点「应用」失败；
#   背屏中心的应用卡列表里始终看不到任何 AI 卡。
#
# 原因
#   /data/system/theme_magic/users/<id>/rearScreenAiApp_Theme 的属主变成了 root:root，
#   主题商店（以 system_theme 身份运行）没有写权限，下载回来的卡落盘失败，
#   索引文件 runtimeAiApp.json 因此一直建不出来，模块侧整段同步被跳过。
#
# 用法（需要 root）
#   把本文件传到手机，然后：
#       sh fix_aiapp_dir_perms.sh
#   或在 KernelSU / Magisk 管理器里以 root 身份执行。
#
# 只动这一个目录：不重启、不删除任何数据；重复执行安全（幂等）。

set -u

TARGET_NAME="rearScreenAiApp_Theme"

# ---------------- root 自检 / 自提权 ----------------
if [ "$(id -u 2>/dev/null)" != "0" ]; then
    SELF="$0"
    case "$SELF" in
        /*) ;;
        *) SELF="$(pwd)/$SELF" ;;
    esac
    if command -v su >/dev/null 2>&1; then
        echo "[i] 需要 root，正在提权..."
        exec su -c "sh '$SELF'"
    fi
    echo "[x] 需要 root。请用 su 执行，或在 KernelSU / Magisk 管理器里以 root 运行本脚本。"
    exit 1
fi

echo "==================================================="
echo " MiBackscreen · AI 应用卡目录修复"
echo "==================================================="
echo

# ---------------- 定位目录 ----------------
USER_ID="$(am get-current-user 2>/dev/null)"
[ -z "$USER_ID" ] && USER_ID=0

BASE="/data/system/theme_magic/users/$USER_ID/$TARGET_NAME"
# 多用户设备上 get-current-user 可能拿不到值，回退到机主目录
if [ ! -d "$BASE" ] && [ -d "/data/system/theme_magic/users/0/$TARGET_NAME" ]; then
    BASE="/data/system/theme_magic/users/0/$TARGET_NAME"
fi

echo "用户 ID  : $USER_ID"
echo "目标目录 : $BASE"
echo

if [ ! -d "$BASE" ]; then
    echo "[x] 目录不存在。"
    echo
    echo "    这个目录是主题商店成功写入过 AI 卡之后才会出现的。现在连它都没有，说明"
    echo "    这台机器上「AI 生成应用卡」从来没落过盘。请先去主题商店里下载并使用一次"
    echo "    AI 生成应用卡，再回来跑本脚本。"
    exit 1
fi

ctx_of()  { ls -Zd "$1" 2>/dev/null | tr -s ' ' '\n' | grep ':' | head -n 1; }
owner_of(){ stat -c '%U:%G' "$1" 2>/dev/null; }
mode_of() { stat -c '%a'    "$1" 2>/dev/null; }
count_of(){ ls "$1" 2>/dev/null | wc -l; }

echo "---------------- 修复前 ----------------"
ls -ld "$BASE"
echo "  —— 目录内容 ——"
ls -l "$BASE" | head -n 20
echo
echo "属主    : $(owner_of "$BASE")   （正常应为 system_theme:system_theme）"
echo "权限    : $(mode_of  "$BASE")   （正常应为 777）"
echo "子项数  : $(count_of "$BASE")"
echo "SELinux : $(ctx_of   "$BASE")   （正常应为 u:object_r:theme_data_file:s0）"
echo

# ---------------- 修复 ----------------
echo "---------------- 执行修复 ----------------"
chown -R system_theme:system_theme "$BASE" && echo "[+] 属主 -> system_theme:system_theme"
# 只给目录开写权限；文件一律 0644，与正常机器的基线保持一致。
find "$BASE" -type d -exec chmod 0777 {} \; && echo "[+] 目录权限 -> 0777"
find "$BASE" -type f -exec chmod 0644 {} \; && echo "[+] 文件权限 -> 0644"

CTX="$(ctx_of "$BASE")"
case "$CTX" in
    *theme_data_file*)
        echo "[=] SELinux 上下文正常，无需处理"
        ;;
    *)
        echo "[!] SELinux 上下文异常（$CTX），尝试恢复"
        if chcon -R u:object_r:theme_data_file:s0 "$BASE" 2>/dev/null; then
            echo "[+] chcon u:object_r:theme_data_file:s0"
        else
            echo "[!] chcon 失败（可能已禁用 SELinux 强制模式）"
        fi
        ;;
esac
echo

# ---------------- 校验 ----------------
echo "---------------- 修复后 ----------------"
ls -ld "$BASE"
echo "  —— 目录内容 ——"
ls -l "$BASE" | head -n 20
echo
echo "属主    : $(owner_of "$BASE")"
echo "权限    : $(mode_of  "$BASE")"
echo "子项数  : $(count_of "$BASE")"
echo "SELinux : $(ctx_of   "$BASE")"
echo

if [ "$(owner_of "$BASE")" = "system_theme:system_theme" ] && [ "$(mode_of "$BASE")" = "777" ]; then
    echo "[√] 修复成功。"
    echo
    echo "接下来请这样操作："
    echo "  1) 重启主题商店（或直接重启手机），让它丢掉之前失败的状态"
    echo "  2) 进主题商店，重新点一次「AI 生成应用卡」的『应用』"
    echo "  3) 回到背屏桌面，卡应该就能出现了"
else
    echo "[!] 修复未完全生效，属主 / 权限仍不符合预期。"
    echo "    如果上面 chown / chmod 有报错，说明根管理器的挂载或 SELinux 策略挡住了，"
    echo "    请把这段完整输出发回来。"
fi
echo
echo "完成。"
