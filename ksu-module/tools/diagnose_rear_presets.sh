#!/system/bin/sh
# ============================================================================
# MiBackscreen 背屏资源诊断 v3（对应附加资源模块的 data-root 方案；
# 实际版本以第 1 段打印的 module.prop 为准）
#
# 只读采集：不写系统文件、不改挂载、不改配置、不动 appInfo。
#
# 用途：判断「AI 陪伴 / AI 壁纸 / AI 合照 / 保护壳壁纸 DIY / 应用卡」缺失
#       发生在哪一层，并对每一层直接给出结论，而不是只堆文件清单。
#
# 运行（需 root）：
#   su -c 'sh /data/local/tmp/diagnose_rear_presets.sh'
#   su -c 'sh /data/local/tmp/diagnose_rear_presets.sh --clipboard'   # 额外放进剪贴板
#
# 交付：写 /sdcard/Download/MiBackscreen-diag-<时间戳>.txt，
#       并通过模块 FileProvider 弹分享面板。末尾「精简摘要」一段即可回传。
#       默认**不**放进剪贴板：剪贴板里的整份报告其他应用和输入法都能读到；
#       确实需要时加 --clipboard。
#
# ---------------------------------------------------------------------------
# 设计教训（来自同类项目 zilewang7/xiaomi-rearscreen-appcard-preset）：
#   1. 管理器 WebUI 里的 exec 继承「应用的隔离 mount namespace」，那时
#      /data/data 只剩几个条目 —— 所有「去别处看一眼」的检查都会静默变成
#      「不存在」。所以第一步必须自检本脚本的视图是否被隔离，否则后面全是假绿。
#   2. 「查不到」和「没有」是两回事。检查脚本最该防的不是「检查失败」，
#      是「检查悄悄降级成通过」。
# ============================================================================

MODDIR=/data/adb/modules/mibackscreen_18_personalization
MODID=mibackscreen_18_personalization
STATE_ROOT=/data/adb/mibackscreen_18_personalization_state
RUNLOG=$STATE_ROOT/service.log
OWNED_IDS=$STATE_ROOT/appcard_owned_ids
BASELINE_APPINFO=$STATE_ROOT/appInfo.baseline
PATCHED_HASH=$STATE_ROOT/appInfo.patched.sha256

DATA_ROOT=/data/system/theme_magic/users/0/mibackscreen_18
CONFIG_DIR=/data/system/theme_magic/users/0/subscreencenter/config
APPINFO=$CONFIG_DIR/appInfo.json

MODPKG=hook.HyperBackscreen
TMP=/data/local/tmp/.mibackscreen_diag_$$.txt

WANT_CLIPBOARD=0
for _arg in "$@"; do
    [ "$_arg" = "--clipboard" ] && WANT_CLIPBOARD=1
done

# 宿主：包名|角色|本模块在该宿主里最该出现的资源路径（用于「应用视角」判定）
HOSTS="
com.miui.personalassistant|应用卡中心(背屏)|$APPINFO
com.xiaomi.subscreencenter|背屏中心|$DATA_ROOT/template/normal/rearScreen.json
com.android.thememanager|主题商店|$DATA_ROOT/template/normal/rearScreen.json
"

line() { echo; echo "===== $* ====="; }

if [ "$(id -u 2>/dev/null)" != "0" ]; then
    echo "ERROR=need_root"
    echo "请用 su 运行：su -c 'sh \$0'"
    exit 1
fi

# ---------------------------------------------------------------------------
# A. 视图自检 —— 必须在任何检查之前
# ---------------------------------------------------------------------------
NS_ISOLATED=0
ns_view_ok() {
    _n=$(ls /data/data 2>/dev/null | wc -l | tr -d ' ')
    [ "${_n:-0}" -ge 100 ]
}

# 尝试带 init 的 mount namespace 重跑自己（先验证，再 exec）。
# exec 只能执行真实的程序，不能执行 shell 函数：先把 nsenter 解析成
# 「程序路径（+ busybox 时的 applet 名）」再 exec；重跑用 sh "$0"，不依赖可执行位。
NS_BIN=""
NS_APPLET=""
_ns_resolve() {
    _p="$(command -v nsenter 2>/dev/null)"
    if [ -n "$_p" ]; then
        NS_BIN=$_p
        return 0
    fi
    if [ -x /system/bin/nsenter ]; then
        NS_BIN=/system/bin/nsenter
        return 0
    fi
    for _b in /data/adb/ksu/bin/busybox /data/adb/magisk/busybox \
              /data/adb/ap/bin/busybox /system/bin/busybox; do
        if [ -x "$_b" ]; then
            NS_BIN=$_b
            NS_APPLET=nsenter
            return 0
        fi
    done
    return 1
}
_nsenter() {
    if [ -n "$NS_APPLET" ]; then
        "$NS_BIN" "$NS_APPLET" "$@"
    else
        "$NS_BIN" "$@"
    fi
}

MIBACKSCREEN_NS_FIXED="${MIBACKSCREEN_NS_FIXED:-}"
if ! ns_view_ok; then
    if [ -z "$MIBACKSCREEN_NS_FIXED" ] && _ns_resolve; then
        _n2=$(_nsenter -t 1 -m -- ls /data/data 2>/dev/null | wc -l | tr -d ' ')
        if [ "${_n2:-0}" -ge 100 ]; then
            # 进入新的 mount namespace 后 cwd 会被重置到根目录，相对路径的 $0 会失效
            _self="$0"
            case "$_self" in
                /*) ;;
                */*) _self="$(cd "${_self%/*}" 2>/dev/null && pwd)/${_self##*/}" ;;
                *) _self="$(pwd)/$_self" ;;
            esac
            MIBACKSCREEN_NS_FIXED=1
            export MIBACKSCREEN_NS_FIXED
            if [ -n "$NS_APPLET" ]; then
                exec "$NS_BIN" "$NS_APPLET" -t 1 -m -- sh "$_self" "$@"
            else
                exec "$NS_BIN" -t 1 -m -- sh "$_self" "$@"
            fi
        fi
    fi
    NS_ISOLATED=1
fi
# 重跑一次确认
ns_view_ok && NS_ISOLATED=0

# ---------------------------------------------------------------------------
# B. 工具
# ---------------------------------------------------------------------------
sha_of()  { [ -f "$1" ] && sha256sum "$1" 2>/dev/null | cut -d' ' -f1; }
size_of() { [ -f "$1" ] && stat -c %s "$1" 2>/dev/null; }
count_in(){ [ -f "$1" ] && grep -a -c "$2" "$1" 2>/dev/null; }
json_num(){ [ -f "$1" ] && grep -a -m1 -o "\"$2\":[ ]*[0-9]*" "$1" 2>/dev/null | tr -dc '0-9'; }

# 资源探针：同时给 root 视角与需要的数据
probe() {
    _f="$1"; _tag="$2"
    if [ -f "$_f" ]; then
        echo "$_tag.exists=true"
        echo "$_tag.version=$(json_num "$_f" version)"
        echo "$_tag.count=$(json_num "$_f" count)"
        echo "$_tag.ai_mate=$(count_in "$_f" ai-mate)"
        echo "$_tag.group_photo=$(count_in "$_f" groupPhoto)"
        echo "$_tag.phone_case=$(count_in "$_f" phoneCase)"
        echo "$_tag.size=$(size_of "$_f")"
        echo "$_tag.sha256=$(sha_of "$_f")"
    else
        echo "$_tag.exists=false"
    fi
}

# 某个进程命名空间里能不能看到某路径
app_view() {
    _pid="$1"; _path="$2"; _tag="$3"
    if [ -z "$_pid" ]; then
        echo "$_tag=unavailable(进程未运行)"
        return
    fi
    if [ -e "/proc/$_pid/root$_path" ]; then
        echo "$_tag.visible=true"
        echo "$_tag.sha256=$(sha_of "/proc/$_pid/root$_path")"
    else
        echo "$_tag.visible=false"
    fi
}

find_pid() {
    _p=$(pidof "$1" 2>/dev/null | tr ' ' '\n' | head -1)
    [ -n "$_p" ] || _p=$(pgrep -f "$1" 2>/dev/null | head -1)
    printf '%s' "$_p"
}

# ---------------------------------------------------------------------------
# C. 采集
# ---------------------------------------------------------------------------
collect() {
    line "0. 设备与视图自检"
    echo "fingerprint=$(getprop ro.build.fingerprint)"
    echo "device=$(getprop ro.product.device)"
    echo "model=$(getprop ro.product.model)"
    echo "incremental=$(getprop ro.build.version.incremental)"
    echo "generated_at=$(date '+%Y-%m-%d %H:%M:%S %z')"
    echo "uptime=$(cat /proc/uptime 2>/dev/null | cut -d' ' -f1)"
    echo "boot_id=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null)"
    echo "data_data_entries=$(ls /data/data 2>/dev/null | wc -l | tr -d ' ')"
    if [ "$NS_ISOLATED" = "1" ]; then
        echo "view.isolated=true"
        echo "view.note=本脚本运行在【隔离的 mount namespace】里，/data/data 不完整。"
        echo "view.fix=请改用 adb shell su -c 运行，或确认管理器已实现 init 命名空间重入。"
    else
        echo "view.isolated=false"
    fi

    line "1. Root 方案与模块目录"
    echo "ksu_dir=$([ -d /data/adb/ksu ] && echo present || echo absent)"
    echo "magisk_dir=$([ -d /data/adb/magisk ] && echo present || echo absent)"
    echo "ap_dir=$([ -d /data/adb/ap ] && echo present || echo absent)"
    echo "metamodule=$([ -e /data/adb/metamodule ] && echo present || echo absent)"
    echo "ksud=$(command -v ksud 2>/dev/null || echo absent)"
    if [ -d "$MODDIR" ]; then
        echo "module_dir=present"
        echo "disable=$([ -e "$MODDIR/disable" ] && echo present || echo absent)"
        echo "remove=$([ -e "$MODDIR/remove" ] && echo present || echo absent)"
        echo "skip_mount=$([ -e "$MODDIR/skip_mount" ] && echo present || echo absent)"
        echo "--- module.prop ---"
        cat "$MODDIR/module.prop" 2>&1
        echo "--- 顶层结构 ---"
        ls -1 "$MODDIR" 2>&1
        echo "--- 资源文件数（期望 58）---"
        echo "mibackscreen_resources_files=$(find "$MODDIR/mibackscreen_resources" -type f 2>/dev/null | wc -l | tr -d ' ')"
        echo "appcard_entries=$(ls "$MODDIR/appcard_entries"/*.json 2>/dev/null | wc -l | tr -d ' ')"
        echo "--- 不应存在的残留（挂载方案遗留）---"
        echo "has_system_dir=$([ -d "$MODDIR/system" ] && echo yes || echo no)"
        echo "has_product_dir=$([ -d "$MODDIR/product" ] && echo yes || echo no)"
        echo "has_mount_resources=$(ls "$MODDIR/mount_resources.sh" 2>/dev/null >/dev/null && echo yes || echo no)"
    else
        echo "module_dir=absent"
    fi
    echo "metamodule_mnt_this=$([ -e "/data/adb/metamodule/mnt/$MODID" ] && echo present || echo absent)"
    echo "metaoverlayfs_mnt_this=$([ -e "/data/adb/modules/meta-overlayfs/mnt/$MODID" ] && echo present || echo absent)"

    line "2. data-root 资源落点"
    if [ -d "$DATA_ROOT" ]; then
        echo "data_root=present"
        echo "data_root_uid=$(stat -c '%u:%g' "$DATA_ROOT" 2>/dev/null)"
        echo "data_root_perm=$(stat -c '%a' "$DATA_ROOT" 2>/dev/null)"
        echo "data_root_ctx=$(ls -Zd "$DATA_ROOT" 2>/dev/null | awk '{print $1}')"
        echo "data_root_files=$(find "$DATA_ROOT" -type f 2>/dev/null | wc -l | tr -d ' ')"
        echo "--- 权限异常文件（非 0644）---"
        # 按行读文件清单（here-doc，计数留在当前 shell）；不用 for f in $(find ...)，
        # 路径里有空格会被拆开。
        bad_perm=0
        _files=$(find "$DATA_ROOT" -type f 2>/dev/null)
        while IFS= read -r f; do
            [ -n "$f" ] || continue
            p=$(stat -c %a "$f" 2>/dev/null)
            [ "$p" = "644" ] || { echo "perm=$p $f"; bad_perm=$((bad_perm + 1)); }
        done <<EOF
$_files
EOF
        echo "bad_perm_files=$bad_perm"
    else
        echo "data_root=absent"
    fi
    probe "$DATA_ROOT/template/normal/rearScreen.json" data_template_normal
    probe "$DATA_ROOT/template/default/rearScreen.json" data_template_default
    echo "appcard_dirs=$(find "$DATA_ROOT/appcard" -maxdepth 3 -mindepth 3 -type d 2>/dev/null | wc -l | tr -d ' ')"
    echo "precust_data_files=$(find "$DATA_ROOT/precust_theme/theme/.data" -type f 2>/dev/null | wc -l | tr -d ' ')"

    line "3. 附加模块日志"
    if [ -f "$RUNLOG" ]; then
        echo "log_size=$(size_of "$RUNLOG")"
        echo "--- 完整 service.log ---"
        cat "$RUNLOG" 2>&1
    else
        echo "service_log=absent"
        echo "提示=模块从未在本机执行过 service 阶段，或 STATE_ROOT 被清理。"
    fi

    line "4. 状态目录（回滚基线）"
    echo "state_root=$([ -d "$STATE_ROOT" ] && echo present || echo absent)"
    if [ -f "$OWNED_IDS" ]; then
        echo "owned_ids_count=$(wc -l < "$OWNED_IDS" 2>/dev/null | tr -d ' ')"
        echo "--- owned ids ---"
        cat "$OWNED_IDS" 2>&1
    else
        echo "owned_ids=absent"
    fi
    echo "baseline_appinfo=$([ -f "$BASELINE_APPINFO" ] && echo present || echo absent)"
    echo "patched_hash=$(cat "$PATCHED_HASH" 2>/dev/null)"
    echo "live_appinfo_hash=$(sha_of "$APPINFO")"

    line "5. appInfo.json（应用卡索引）"
    if [ -f "$APPINFO" ]; then
        _au=$(stat -c %u "$APPINFO" 2>/dev/null)
        _ag=$(stat -c %g "$APPINFO" 2>/dev/null)
        echo "appinfo.size=$(size_of "$APPINFO")"
        echo "appinfo.owner=$_au:$_ag"
        echo "appinfo.env_uid=$(stat -c %u /data/system/theme_magic/users/0 2>/dev/null)"
        echo "appinfo.perm=$(stat -c %a "$APPINFO" 2>/dev/null)"
        echo "appinfo.ctx=$(ls -Z "$APPINFO" 2>/dev/null | awk '{print $1}')"
        echo "appinfo.sha256=$(sha_of "$APPINFO")"
        echo "--- 模块六张预置卡是否在索引中（1=已写入）---"
        card_idx() { echo "card.$1.$(printf '%s' "$2" | tr -d '"')=$(grep -c "$1" "$APPINFO" 2>/dev/null)"; }
        card_idx 1c922ed0-d694-508e-8de9-2d8b2ebf59a5 精选台历
        card_idx 36b61506-b02b-5f68-aaa9-7977f6804281 临近日程
        card_idx 44630e40-115e-590f-b81e-a72cae9efab4 隐身模式
        card_idx 04d7ca77-4e02-57b0-b9c3-21d12d3f0620 米家摄像机
        card_idx 62d7c4e8-91a3-5b2f-8c0d-4e1f2a3b4c5d 小米汽车
        card_idx f05e6e29-5eeb-573c-bf31-f1da128f2b5f 股票行情
    else
        echo "appinfo=absent"
    fi

    line "5b. 卡片就绪度（依赖 App 是否在场）"
    echo "应用卡中心会自行过滤掉「依赖 App 未装或版本不够」的卡片，"
    echo "预设挂对了也不显示 —— 这是「装了模块但卡片不出现」的常见原因。"
    PKGS=$(pm list packages --show-versioncode 2>/dev/null)
    ready_check() {  # $1=包名 $2=卡片名 $3=最低版本
        _v=$(printf '%s\n' "$PKGS" | sed -n "s/^package:$1 versionCode:\([0-9]*\).*/\1/p" | head -1)
        if [ -z "$_v" ]; then
            echo "ready.$2=NO(未安装 $1)"
        elif [ -n "$3" ] && [ "$3" -gt 0 ] && [ "${_v:-0}" -lt "$3" ]; then
            echo "ready.$2=NO($1 版本 $_v < 要求 $3)"
        else
            echo "ready.$2=ok($1 $_v)"
        fi
    }
    ready_check com.android.calendar      精选台历   180000040
    ready_check com.android.calendar      临近日程   180000040
    ready_check com.miui.securitycenter   隐身模式   0
    ready_check com.xiaomi.smarthome      米家摄像机 11060500
    ready_check com.mi.car.mobile         小米汽车   25102214
    ready_check com.miui.personalassistant 股票行情  0

    line "6. 宿主进程与「应用视角」对照（关键）"
    echo "应用进程是 zygote fork 出来的，各自有一份 mount namespace。"
    echo "「root 看得见但应用看不见」= 文件链路之外还有一层问题。"
    echo
    echo "$HOSTS" | while IFS='|' read -r hpkg hrole hpath; do
        [ -n "$hpkg" ] || continue
        hpid=$(find_pid "$hpkg")
        echo "--- $hpkg ($hrole) ---"
        if [ -n "$hpid" ]; then
            echo "pid=$hpid"
            echo "ns=$(readlink /proc/$hpid/ns/mnt 2>/dev/null)"
            app_view "$hpid" "$hpath" "view"
        else
            echo "pid=not_running"
            echo "提示=请先打开一次对应页面，再重新运行本脚本。"
        fi
    done

    line "7. root 自身视角（对照）"
    probe "/system/media/rearscreen/template/normal/rearScreen.json" root_system_template

    line "8. 冲突检测"
    re_found=0
    re_resid=0
    for pkg in com.miui.personalassistant com.xiaomi.subscreencenter com.android.thememanager; do
        base=/data/data/$pkg/cache/reareye-preset-pack
        if [ -d "$base" ]; then
            if find "$base" -name active.rpp 2>/dev/null | grep -q .; then
                echo "reareye.$pkg=active(已接管预置路径)"
                re_found=1
            else
                echo "reareye.$pkg=residue(有目录无 active.rpp)"
                re_resid=1
            fi
        else
            echo "reareye.$pkg=none"
        fi
    done
    if [ "$re_found" = "1" ]; then
        echo "reareye_conflict=YES"
        echo "reareye_note=REAREye 已提交预设包，它会 hook 宿主对预置路径的全部文件访问。"
        echo "reareye_note2=本模块资源在 data-root，理论上不受其 /system 重定向影响；若第 6 段应用视角为 false 优先怀疑此项。"
    elif [ "$re_resid" = "1" ]; then
        echo "reareye_conflict=RESIDUE"
    else
        echo "reareye_conflict=NO"
    fi

    if [ -d /data/adb/modules/rearscreen_appcard_preset ]; then
        echo "other_module.rearscreen_appcard_preset=present"
        echo "other_module.note=同装该模块会往 appInfo.json 塞同一批卡，可能重复索引；建议二选一。"
    else
        echo "other_module.rearscreen_appcard_preset=absent"
    fi

    line "9. 总结判读"
    DATA_TEMPLATE_OK=0
    APP_TEMPLATE_OK=0
    [ -f "$DATA_ROOT/template/normal/rearScreen.json" ] && DATA_TEMPLATE_OK=1

    PA_PID=$(find_pid com.miui.personalassistant)
    if [ -n "$PA_PID" ] && [ -f "/proc/$PA_PID/root$APPINFO" ]; then
        APPINFO_IN_APP=1
    else
        APPINFO_IN_APP=0
    fi

    TM_PID=$(find_pid com.android.thememanager)
    if [ -n "$TM_PID" ] && [ -f "/proc/$TM_PID/root$DATA_ROOT/template/normal/rearScreen.json" ]; then
        APP_TEMPLATE_OK=1
    fi

    echo "module_present=$([ -d "$MODDIR" ] && echo 1 || echo 0)"
    echo "data_template_ok=$DATA_TEMPLATE_OK"
    echo "appinfo_visible_to_app=$APPINFO_IN_APP"
    echo "template_visible_to_thememanager=$APP_TEMPLATE_OK"
    echo "view_isolated=$NS_ISOLATED"

    echo
    if [ "$NS_ISOLATED" = "1" ]; then
        echo "判读=脚本视图被隔离，上述结果为假绿，不可信。请用 adb shell su -c 重跑。"
    elif [ ! -d "$MODDIR" ]; then
        echo "判读=附加模块未安装。"
    elif [ "$DATA_TEMPLATE_OK" != "1" ]; then
        echo "判读=data-root 资源缺失。看第 3 段 service.log：是 sync 失败还是 service 从未跑。"
    elif [ "$APP_TEMPLATE_OK" != "1" ]; then
        echo "判读=资源在 root 视图存在，但主题商店进程看不到。重点查权限/context（第 2 段 bad_perm_files）与 SELinux label。"
    elif [ "$APPINFO_IN_APP" != "1" ]; then
        echo "判读=appInfo.json 在应用卡中心进程里不可见。检查第 5 段属主（应等于 theme_magic/users/0 的 uid）。"
    else
        echo "判读=文件链路完整。若页面仍缺卡片，问题在「卡片就绪度」（依赖 App 未装/版本低）或宿主缓存，检查第 5 段各 card.* 与日志。"
    fi
}

# ---------------------------------------------------------------------------
# D. 交付
# ---------------------------------------------------------------------------
TS=$(date +%Y%m%d-%H%M%S)
OUTNAME=MiBackscreen-diag-$TS.txt
DL=/sdcard/Download

# 只在显式传了 --clipboard 时调用
clip_probe() {
    command -v termux-clipboard-set >/dev/null 2>&1 && \
        termux-clipboard-set "$(cat "$TMP")" 2>/dev/null && return 0
    if command -v cmd >/dev/null 2>&1; then
        m=$(cmd clipboard set-text "$(head -c 60000 "$TMP")" 2>&1)
        case "$m" in
            *"No shell command implementation"*|*"Unknown command"*|*"Exception"*|*"Error"*) ;;
            *) return 0 ;;
        esac
    fi
    return 1
}

share_report() {
    _appdir=/data/user/0/$MODPKG
    [ -d "$_appdir" ] || return 1
    _dest="$_appdir/cache/feedback"
    mkdir -p "$_dest" 2>/dev/null || return 1
    cp "$TMP" "$_dest/$OUTNAME" 2>/dev/null || return 1
    _owner=$(stat -c %u "$_appdir" 2>/dev/null)
    [ -n "$_owner" ] && chown "$_owner:$_owner" "$_dest/$OUTNAME" 2>/dev/null
    chmod 0600 "$_dest/$OUTNAME" 2>/dev/null
    chcon u:object_r:app_data_file:s0 "$_dest/$OUTNAME" 2>/dev/null
    am start -a android.intent.action.SEND -t text/plain \
        --grant-read-uri-permission \
        --eu android.intent.extra.STREAM \
        "content://$MODPKG.files/feedback/$OUTNAME" >/dev/null 2>&1
}

collect > "$TMP" 2>&1
cat "$TMP"

line "B1. 交付"
REPORT_PATH=""
if [ -d "$DL" ] && cp "$TMP" "$DL/$OUTNAME" 2>/dev/null; then
    chmod 0644 "$DL/$OUTNAME" 2>/dev/null
    REPORT_PATH="$DL/$OUTNAME"
elif cp "$TMP" "/sdcard/$OUTNAME" 2>/dev/null; then
    chmod 0644 "/sdcard/$OUTNAME" 2>/dev/null
    REPORT_PATH="/sdcard/$OUTNAME"
fi
echo "report_file=${REPORT_PATH:-write_failed}"
if [ "$WANT_CLIPBOARD" = "1" ]; then
    clip_probe && echo "clipboard=ok" || echo "clipboard=unsupported"
else
    echo "clipboard=skipped(默认不放剪贴板，需要时加 --clipboard)"
fi
share_report && echo "share=launched" || echo "share=unavailable"

line "B2. 精简摘要（回传这一段即可）"
echo "fingerprint=$(getprop ro.build.fingerprint)"
echo "device=$(getprop ro.product.device)"
echo "uptime=$(cat /proc/uptime 2>/dev/null | cut -d' ' -f1)"
echo "view_isolated=$NS_ISOLATED"
echo "module_dir=$([ -d "$MODDIR" ] && echo present || echo absent)"
echo "data_root=$([ -d "$DATA_ROOT" ] && echo present || echo absent)"
echo "data_files=$(find "$DATA_ROOT" -type f 2>/dev/null | wc -l | tr -d ' ')"
echo "bad_perm_files=$(find "$DATA_ROOT" -type f ! -perm 644 2>/dev/null | wc -l | tr -d ' ')"
echo "data_template_version=$(json_num "$DATA_ROOT/template/normal/rearScreen.json" version)"
echo "service_log_tail=$(tail -n 1 "$RUNLOG" 2>/dev/null)"
echo "appinfo_hash=$(sha_of "$APPINFO")"
PA=$(find_pid com.miui.personalassistant)
echo "pa_pid=${PA:-not_running}"
if [ -n "$PA" ]; then
    echo "pa_appinfo_visible=$([ -f "/proc/$PA/root$APPINFO" ] && echo true || echo false)"
fi
TM=$(find_pid com.android.thememanager)
echo "tm_pid=${TM:-not_running}"
if [ -n "$TM" ]; then
    echo "tm_template_visible=$([ -f "/proc/$TM/root$DATA_ROOT/template/normal/rearScreen.json" ] && echo true || echo false)"
fi
echo "report_file=${REPORT_PATH:-unknown}"

rm -f "$TMP" 2>/dev/null
echo
echo "采集完成。回传第 B2 段，或发送 report_file 指向的文件。"
