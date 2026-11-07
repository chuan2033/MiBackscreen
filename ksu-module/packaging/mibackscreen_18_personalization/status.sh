#!/system/bin/sh
# ============================================================================
# MiBackscreen 背屏资源 · 诊断引擎
#
# 只读。输出行协议供 WebUI 面板解析；--text 输出人类可读版（action.sh 用）。
#
# 用法：
#   sh status.sh            行协议（WebUI 解析）
#   sh status.sh --text     人类可读
#
# 行协议（记录以 @@ 开头，字段一行一个，值内换行已压平）：
#   @@
#   id=<检查项标识>
#   level=ok|warn|fail|info
#   title=<标题>
#   detail=<详情>
#   fix=<修复建议，可为空>
#   pkg=<应用包名，WebUI 用它取图标，可为空>
#   action=<修复动作 id，WebUI 据此画按钮，可为空>
#
# 分组前缀：env 运行环境 / conf 冲突检测 / res 资源 / inj 注入状态 /
#           pre 预设内容 / card 卡片就绪度 / app 背屏中心 / aiapp AI 应用卡 /
#           result 结论
#
# ---------------------------------------------------------------------------
# 设计教训（来自同类项目 zilewang7/xiaomi-rearscreen-appcard-preset）：
#   1. 管理器 WebUI 里的 exec 继承「应用的隔离 mount namespace」，那时
#      /data/data 只剩几个条目 —— 所有「去别处看一眼」的检查会静默变成
#      「不存在」。所以第一步必须自检本脚本的视图是否被隔离，否则全是假绿。
#   2. 「查不到」和「没有」是两回事。检查最该防的不是「检查失败」，
#      是「检查悄悄降级成通过」。
#   3. 判据要用「应用的身份」而不是 root —— 文件 root 读得到不代表应用读得到。
# ============================================================================

MODDIR=${0%/*}
[ -f "$MODDIR/module.prop" ] || MODDIR=/data/adb/modules/mibackscreen_18_personalization

. "$MODDIR/lib.sh"
ns_reexec "$@"

MODE="${1:-}"

DATA_ROOT=$MIBACKSCREEN_DATA_ROOT
APPINFO=$MIBACKSCREEN_APPINFO
STATE_ROOT=$MIBACKSCREEN_STATE_ROOT
RUNLOG=$STATE_ROOT/service.log
OWNED_IDS=$STATE_ROOT/appcard_owned_ids
BASELINE_APPINFO=$STATE_ROOT/appInfo.baseline
PATCHED_HASH=$STATE_ROOT/appInfo.patched.sha256

MIBACKSCREEN_APP_DIR=$MIBACKSCREEN_APP
PA_PKG=com.miui.personalassistant
SSC_PKG=com.xiaomi.subscreencenter
TM_PKG=com.android.thememanager

OUT="$MIBACKSCREEN_TMP/status.$$.txt"
: > "$OUT"

# ------------------------------------------------------------------ 记录
_flat() { printf '%s' "$1" | tr '\n\r\t' '   '; }

emit() {  # id level title detail fix [pkg] [action]
    printf '@@\nid=%s\nlevel=%s\ntitle=%s\ndetail=%s\nfix=%s\npkg=%s\naction=%s\n' \
        "$1" "$2" "$(_flat "$3")" "$(_flat "$4")" "$(_flat "$5")" "$6" "${7:-}" >> "$OUT"
}

# ------------------------------------------------------------------ 一次性取包名+版本
PKGS=$(pm list packages --show-versioncode 2>/dev/null)
pkg_version() { printf '%s\n' "$PKGS" | sed -n "s/^package:$1 versionCode:\([0-9]*\).*/\1/p" | head -1; }
is_num() { case "$1" in ''|*[!0-9]*) return 1 ;; *) return 0 ;; esac; }
size_of() { [ -f "$1" ] && stat -c %s "$1" 2>/dev/null; }
count_in(){ [ -f "$1" ] && grep -a -c "$2" "$1" 2>/dev/null; }
# 出现次数（-c 数的是「含匹配的行数」，压缩过的 JSON 会少算）
count_occ(){ [ -f "$1" ] && grep -a -o -- "$2" "$1" 2>/dev/null | wc -l | tr -d ' '; }
json_num(){ [ -f "$1" ] && grep -a -m1 -o "\"$2\":[ ]*[0-9]*" "$1" 2>/dev/null | tr -dc '0-9'; }

# 资源文件权限是否「应用也读得到」：文件 o+r，路径上每层目录 o+x
perm_issues() {
    _root="$1"; _bad=0; _sample=""
    [ -d "$_root" ] || { echo 0; return; }
    for f in $(find "$_root" -type f 2>/dev/null); do
        p=$(stat -c %a "$f" 2>/dev/null)
        case "$p" in
            *4|*5|*6|*7) ;;                       # 末位含读
            *) _bad=$((_bad + 1)); [ -z "$_sample" ] && _sample="${f#$_root/}";;
        esac
        d=$(dirname "$f")
        while [ "$d" != "$_root" ] && [ "$d" != "/" ]; do
            dp=$(stat -c %a "$d" 2>/dev/null)
            case "$dp" in
                *1|*3|*5|*7) ;;
                *) _bad=$((_bad + 1)); [ -z "$_sample" ] && _sample="${d#$_root/}/ (目录)";;
            esac
            d=$(dirname "$d")
        done
    done
    echo "$_bad ${_sample:-}"
}

# ------------------------------------------------------------------ 文本模式辅助
txt=0
[ "$MODE" = "--text" ] && txt=1
TXT="$MIBACKSCREEN_TMP/status.$$.text"
: > "$TXT"
tline() { printf '%s\n' "$1" >> "$TXT"; }
tgroup() { [ "$txt" = "1" ] && printf '\n【%s】\n' "$1" >> "$TXT"; }
tshow() { [ "$txt" = "1" ] && printf '  %s %s%s\n' "$2" "$3" \
    "$([ -n "$4" ] && printf '（%s）' "$4")" >> "$TXT"; }

# ============================================================ 1. 运行环境
tgroup "运行环境"
DEV="$(getprop ro.product.device)"
# 设备/系统/Root 读到真实值就算正常（ok）。
# 曾经标 info，但它们在「运行环境」这个检查分组里跟「诊断视图 ✓」并列，
# 灰点在视觉上像「未完成/未知」，用户会以为没查出来 —— 实际是查到了。
emit env.device ok "设备" "$(getprop ro.product.model) / $DEV" ""
tshow env "·" "设备 $DEV"

OSV="$(getprop ro.mi.os.version.name) ($(getprop ro.build.version.incremental))"
emit env.os ok "系统" "$OSV" ""

if [ -d /data/adb/ksu ]; then ROOT_SCHEME="KernelSU"
elif [ -d /data/adb/ap ]; then ROOT_SCHEME="APatch"
elif command -v magisk >/dev/null 2>&1; then ROOT_SCHEME="Magisk"
else ROOT_SCHEME="未识别"; fi
emit env.root ok "Root 方案" "$ROOT_SCHEME" ""

if [ "$NS_ISOLATED" = "1" ]; then
    emit env.view fail "诊断视图" \
        "本脚本运行在隔离的 mount namespace 里，/data/data 不完整，检查结果不可信。" \
        "改用 adb shell su -c 运行，或确认管理器支持 init 命名空间重入。"
    tshow env "✗" "诊断视图被隔离（结果不可信）"
else
    emit env.view ok "诊断视图" "正常（/data/data $(ls /data/data 2>/dev/null | wc -l | tr -d ' ') 项）" ""
    tshow env "✓" "诊断视图正常"
fi

MODVER="$(sed -n 's/^version=//p' "$MODDIR/module.prop" 2>/dev/null)"
if [ -d "$MODDIR" ]; then
    emit env.module ok "模块版本" "v$MODVER" ""
    tshow env "✓" "模块 v$MODVER"
    if [ -e "$MODDIR/disable" ]; then
        emit env.enabled fail "模块状态" "模块已被禁用（存在 disable 文件）" "在管理器里重新启用并重启"
        tshow env "✗" "模块已禁用"
    fi
else
    emit env.module fail "模块版本" "模块目录不存在" "重新刷入模块"
    tshow env "✗" "模块目录不存在"
fi

# ============================================================ 2. 冲突检测
tgroup "冲突检测"

# REAREye 会接管宿主对「系统预置路径」的文件访问
re_active=0; re_resid=0
for pkg in $PA_PKG $SSC_PKG $TM_PKG; do
    base=/data/data/$pkg/cache/reareye-preset-pack
    [ -d "$base" ] || continue
    if find "$base" -name active.rpp 2>/dev/null | grep -q .; then
        re_active=1
    else
        re_resid=1
    fi
done
if [ "$re_active" = "1" ]; then
    emit conf.reareye warn "REAREye 预设包" \
        "REAREye 已接管宿主对预置路径的文件访问（/data/data/*/cache/reareye-preset-pack/*/active.rpp）。" \
        "本模块资源在 data-root，路径不重叠，通常不受影响；若「注入状态」异常优先排查此项。" \
        "" "clear_reareye"
    tshow conf "!" "REAREye 已接管预置路径"
elif [ "$re_resid" = "1" ]; then
    emit conf.reareye warn "REAREye 残留" "检测到 REAREye 预设包目录但未完成提交，暂未接管" \
        "REAREye 一旦启用预设包就会接管；与本模块二选一。" "" "clear_reareye"
    tshow conf "!" "REAREye 有残留"
else
    emit conf.reareye ok "REAREye" "未安装或缺省不冲突" ""
    tshow conf "✓" "无 REAREye 冲突"
fi

if [ -d /data/adb/modules/rearscreen_appcard_preset ]; then
    emit conf.other warn "同类模块" \
        "检测到 rearscreen_appcard_preset（bind mount 同批应用卡）。" \
        "它与本模块路径不重叠但**会向 appInfo.json 写同一批卡**，同时启用会造成重复索引，建议二选一。"
    tshow conf "!" "同装 rearscreen_appcard_preset（索引重叠）"
else
    emit conf.other ok "同类模块" "未检测到冲突模块" ""
    tshow conf "✓" "无同类模块冲突"
fi

# ============================================================ 3. 资源
tgroup "资源"
if [ -d "$DATA_ROOT" ]; then
    nf=$(find "$DATA_ROOT" -type f 2>/dev/null | wc -l | tr -d ' ')
    emit res.progress ok "资源落点" "data-root 已同步，$nf 个文件" ""
    tshow res "✓" "data-root $nf 个文件"
else
    emit res.progress fail "资源落点" "data-root 不存在，service 阶段未成功同步" \
        "查看模块日志确认 sync 是否失败；必要时重启一次让 late_start 重跑。"
    tshow res "✗" "data-root 不存在"
fi

if [ -d "$DATA_ROOT" ]; then
    PI=$(perm_issues "$DATA_ROOT")
    pi_n=${PI%% *}; pi_sample=${PI#* }
    pi_n=${pi_n:-0}
    if [ "$pi_n" -gt 0 ]; then
        emit res.perm fail "资源权限" \
            "$pi_n 个文件/目录应用读不到（root 读得到不代表应用读得到）；如 $pi_sample" \
            "点「修好资源权限」就地修好，不用重启。" "" "fix_perms"
        tshow res "✗" "资源权限：$pi_n 项异常"
    else
        emit res.perm ok "资源权限" "全部文件与目录应用均可读" ""
        tshow res "✓" "资源权限正常"
    fi
fi

ctx=$(ls -Zd "$DATA_ROOT" 2>/dev/null | awk '{print $1}')
case "$ctx" in
    *theme_data_file*) emit res.context ok "SELinux 上下文" "$ctx" ""; tshow res "✓" "context $ctx";;
    "") ;;
    *) emit res.context warn "SELinux 上下文" "$ctx（期望含 theme_data_file）" \
            "上下文不对会导致应用读不到；重启一次让 service 重新 restorecon。" ;;
esac

# ============================================================ 4. 注入状态
#
# 判据（1.2 起改成这样，别再改回「宿主看得见文件」）：
#   旧实现用 `nsenter -t <宿主 pid> -m -- test -e <data-root 文件>` 判「应用视角可见」。
#   两处错：
#     1) 资源本来就不该被宿主当普通文件读到 —— 它在宿主进程内靠模块拦路径生效，
#        「文件在不在」既不必要也不充分，只会把「模块没注入」误判成通过。
#     2) 进别人的 mount namespace 在部分 Root 方案 / SELinux 策略下会静默失败，
#        于是「查不到」被渲染成「不可见」—— 教科书式的假红。
#   现在判「模块代码有没有真的进这个进程」：
#     · 主证据：LSPosed 日志里该 pid 的 onModuleLoaded 记录（pid 唯一，最硬）
#     · 辅助：进程内存映射里出现模块 APK（部分 LSPosed 版本只在内存加载 dex，命中率不保证）
#     · 再用「进程启动时间 vs 模块 APK 修改时间」区分「装完模块后这个应用还没重启」
#       和「根本没注入」——前者重启该应用即可，后者要去查作用域。
tgroup "注入状态"

LSP_LOG_DIR=/data/adb/lspd/log
LSP_LOAD_TMP="$MIBACKSCREEN_TMP/lsp_load.$$"
: > "$LSP_LOAD_TMP"
LSP_LOGS=$(ls -t "$LSP_LOG_DIR"/modules_*.log 2>/dev/null | head -3)
# 「日志不可读」和「日志里没有本模块」是两回事，必须分开：
#   前者是「查不到」，只能降级成 info；后者说明模块在所有被钩进程里都没加载过，是真故障。
LSP_AVAILABLE=0
[ -n "$LSP_LOGS" ] && LSP_AVAILABLE=1
for _f in $LSP_LOGS; do
    grep -a "$MIBACKSCREEN_APP.*onModuleLoaded" "$_f" 2>/dev/null >> "$LSP_LOAD_TMP"
done
# 该 pid 是否加载过本模块（日志行首两列是 pid/tid，包名在同一条记录里，避免 pid 复用误判）
has_pid_load() {
    [ -s "$LSP_LOAD_TMP" ] || return 1
    [ -n "$2" ] || return 1
    grep -a "onModuleLoaded: $2" "$LSP_LOAD_TMP" 2>/dev/null | grep -a -qE ": +$1: " 2>/dev/null
}
# 进程启动时刻（unix 秒）。/proc/<pid> 目录的 mtime 不是启动时间，必须用 stat 的 starttime。
proc_start_epoch() {
    _st=$(sed 's/.*) //' "/proc/$1/stat" 2>/dev/null | awk '{print $20}')
    is_num "$_st" || return 1
    _up=$(cut -d. -f1 /proc/uptime 2>/dev/null)
    is_num "$_up" || return 1
    _hz=$(getconf CLK_TCK 2>/dev/null); is_num "$_hz" || _hz=100
    echo $(( $(date +%s) - _up + _st / _hz ))
}

MODULE_APK=$(pm path "$MIBACKSCREEN_APP" 2>/dev/null | head -1 | sed 's/^package://')
MODULE_APK_MTIME=$(stat -c %Y "$MODULE_APK" 2>/dev/null)

for spec in "$PA_PKG|应用卡中心|pa" \
            "$SSC_PKG|背屏中心|ssc" \
            "$TM_PKG|主题商店|tm"; do
    hpkg=$(printf '%s' "$spec" | cut -d '|' -f 1)
    hrole=$(printf '%s' "$spec" | cut -d '|' -f 2)
    hid=$(printf '%s' "$spec" | cut -d '|' -f 3)

    hpids=$({ pidof "$hpkg" 2>/dev/null; pgrep -f "$hpkg" 2>/dev/null; } \
        | tr ' ' '\n' | grep -E '^[0-9]+$' | sort -u)
    if [ -z "$hpids" ]; then
        emit inj.$hid info "$hrole" "进程未运行，本次不判断注入" \
            "打开一次「$hrole」的页面后重新检测。"
        tshow inj "·" "$hrole 未运行"
        continue
    fi

    hit_pid=""; maps_pid=""; old_pid=""; old_start=""
    for hp in $hpids; do
        if [ -z "$hit_pid" ] && has_pid_load "$hp" "$hpkg"; then hit_pid=$hp; fi
        if [ -z "$maps_pid" ] \
            && grep -a -q "$MIBACKSCREEN_APP" "/proc/$hp/maps" 2>/dev/null; then maps_pid=$hp; fi
        hs=$(proc_start_epoch "$hp")
        if is_num "$hs" && { [ -z "$old_start" ] || [ "$hs" -lt "$old_start" ]; }; then
            old_start=$hs; old_pid=$hp
        fi
    done

    if [ -n "$hit_pid" ]; then
        emit inj.$hid ok "$hrole" "模块已注入（pid $hit_pid，LSPosed 已加载并装好 Hook）" ""
        tshow inj "✓" "$hrole 已注入（pid $hit_pid）"
    elif [ -n "$maps_pid" ]; then
        emit inj.$hid ok "$hrole" "模块代码已在进程内（pid $maps_pid，内存映射命中）" ""
        tshow inj "✓" "$hrole 模块在进程内"
    elif [ "$LSP_AVAILABLE" = "0" ]; then
        emit inj.$hid info "$hrole" "进程在跑，但读不到 LSPosed 日志，无法判断是否已注入" \
            "查不到不等于没有：确认 $LSP_LOG_DIR 可读后重新检测。"
        tshow inj "·" "$hrole 无日志可判"
    elif is_num "$old_start" && is_num "$MODULE_APK_MTIME" && [ "$old_start" -lt "$MODULE_APK_MTIME" ]; then
        emit inj.$hid warn "$hrole" \
            "进程还是模块更新前启动的（pid $old_pid），新模块没进去" \
            "强制停止「$hrole」再打开就装上了；重启手机同样有效。" "$hpkg"
        tshow inj "!" "$hrole 需重启该应用"
    else
        emit inj.$hid warn "$hrole" "进程在跑但模块没进去（pid $hpids）" \
            "在 LSPosed 里确认本模块作用域勾选了「$hrole」；勾选后重启该应用，仍无效就重启手机。" "$hpkg"
        tshow inj "!" "$hrole 未注入"
    fi
done
rm -f "$LSP_LOAD_TMP" 2>/dev/null

# ============================================================ 5. 预设内容
# 两种「预设」都在这里查，各有用途，别再只查其中一种：
#   · 模板 template/<x>/rearScreen.json —— 模板里 ai-mate / groupPhoto / phoneCase
#     三个 resType 槽位，模块靠它把 AI 陪伴 / AI 合照 / 保护壳挂进背屏壁纸。
#     default 模板本身就只有 ai-mate（精简版，ROM 原生如此），合照/保护壳为 0 属正常。
#   · 预设清单 appcard/default/rearScreen.json —— 模块往智能助理商店页注入预置卡时
#     读的那份（APK 侧 Constants.PERSONAL_ASSISTANT_PRESET_JSON_PATH）。
#
# 旧实现把两个模板路径写死，缺文件时只回一句「缺失」：既不说查的是哪个路径，也不区分
# 「资源没同步」和「这份 ROM 的模板目录名不一样」，用户只能看到一个红点。
# 现在模板目录按实际存在枚举，缺了就带上被查的完整路径。
tgroup "预设内容"

TEMPLATE_ROOT="$DATA_ROOT/template"
tmpl_seen=0
for tdir in "$TEMPLATE_ROOT"/*/; do
    [ -d "$tdir" ] || continue
    tmpl_seen=1
    t=$(basename "$tdir")
    tkey=$(printf '%s' "$t" | tr -c 'A-Za-z0-9_-' '_')
    tp="$tdir/rearScreen.json"
    if [ -s "$tp" ]; then
        am=$(count_occ "$tp" ai-mate)
        gp=$(count_occ "$tp" groupPhoto)
        pc=$(count_occ "$tp" phoneCase)
        tv=$(json_num "$tp" version)
        emit pre.tmpl_$tkey ok "模板 $t" \
            "v${tv:-?} · AI 陪伴 $am 项 · AI 合照 $gp 项 · 保护壳 DIY $pc 项" ""
        tshow pre "✓" "模板 $t：AI 陪伴=$am AI 合照=$gp 保护壳DIY=$pc"
    else
        emit pre.tmpl_$tkey fail "模板 $t" "$tp 不存在或为空" \
            "先在「资源」段确认 data-root；再查模块日志的 resource sync。"
        tshow pre "✗" "模板 $t 缺失（$tp）"
    fi
done
if [ "$tmpl_seen" = "0" ]; then
    if [ -d "$DATA_ROOT" ]; then
        emit pre.tmpl_root fail "模板目录" \
            "$TEMPLATE_ROOT 下没有任何模板子目录（模板按实际目录枚举，不再写死名字）" \
            "看模块日志的 resource sync 结果；必要时重启一次让 late_start 重跑。"
    else
        emit pre.tmpl_root fail "模板目录" \
            "data-root 不存在（$DATA_ROOT），资源还没同步到 data 分区" \
            "KSU 装完模块不会补跑 late_start：重启一次，或点模块卡「操作」立刻同步。"
    fi
    tshow pre "✗" "模板目录没找到"
fi

PRESET_JSON="$DATA_ROOT/appcard/default/rearScreen.json"
if [ -s "$PRESET_JSON" ]; then
    pv=$(json_num "$PRESET_JSON" version)
    pn=$(count_occ "$PRESET_JSON" '"resId"')
    emit pre.manifest ok "预设清单" \
        "v${pv:-?} · $pn 张卡（appcard/default/rearScreen.json）" ""
    tshow pre "✓" "预设清单 $pn 张卡"
else
    emit pre.manifest warn "预设清单" \
        "appcard/default/rearScreen.json 不存在或为空" \
        "模块往智能助理商店页注入预置卡时读这份文件；缺了预置卡不会出现在商店页。"
    tshow pre "!" "预设清单缺失"
fi

# ============================================================ 6. 卡片就绪度
tgroup "卡片就绪度"
card_check() {  # resId 名称 包名 最低版本
    _id=$1; _name=$2; _pkg=$3; _min=$4
    _v=$(pkg_version "$_pkg")
    if [ -z "$_v" ]; then
        emit card.$(printf '%s' "$_name" | sed 's/[^A-Za-z0-9]//g') warn "$_name" \
            "依赖 App 未安装：$_pkg" "装上它这张卡才会出现。" "$_pkg"
        tshow card "!" "$_name：未装 $_pkg"
    elif is_num "$_min" && [ "$_min" -gt 0 ] && [ "${_v:-0}" -lt "$_min" ]; then
        emit card.$(printf '%s' "$_name" | sed 's/[^A-Za-z0-9]//g') warn "$_name" \
            "$_pkg 版本 $_v < 要求 $_min" "升级 App 后这张卡才会出现。" "$_pkg"
        tshow card "!" "$_name：版本不足"
    else
        emit card.$(printf '%s' "$_name" | sed 's/[^A-Za-z0-9]//g') ok "$_name" \
            "$_pkg $_v" "" "$_pkg"
        tshow card "✓" "$_name：就绪"
    fi
}
card_check 1c922ed0-d694-508e-8de9-2d8b2ebf59a5 精选台历   com.android.calendar     180000040
card_check 36b61506-b02b-5f68-aaa9-7977f6804281 临近日程   com.android.calendar     180000040
card_check 44630e40-115e-590f-b81e-a72cae9efab4 隐身模式   com.miui.securitycenter  0
card_check 04d7ca77-4e02-57b0-b9c3-21d12d3f0620 米家摄像机 com.xiaomi.smarthome     11060500
card_check 62d7c4e8-91a3-5b2f-8c0d-4e1f2a3b4c5d 小米汽车   com.mi.car.mobile        25102214
card_check f05e6e29-5eeb-573c-bf31-f1da128f2b5f 股票行情   com.miui.personalassistant 0

# ============================================================ 7. 背屏中心
tgroup "背屏中心"
if [ -f "$APPINFO" ]; then
    au=$(stat -c %u "$APPINFO" 2>/dev/null)
    env_uid=$(stat -c %u "$MIBACKSCREEN_CONFIG_DIR" 2>/dev/null)
    n_all=$(grep -ao '"resId"' "$APPINFO" 2>/dev/null | wc -l | tr -d ' ')
    n_mod=0
    for id in 1c922ed0-d694-508e-8de9-2d8b2ebf59a5 36b61506-b02b-5f68-aaa9-7977f6804281 \
              44630e40-115e-590f-b81e-a72cae9efab4 04d7ca77-4e02-57b0-b9c3-21d12d3f0620 \
              62d7c4e8-91a3-5b2f-8c0d-4e1f2a3b4c5d f05e6e29-5eeb-573c-bf31-f1da128f2b5f; do
        grep -q "$id" "$APPINFO" 2>/dev/null && n_mod=$((n_mod + 1))
    done
    emit app.registry info "已添加卡片" "共 $n_all 张，其中模块预置 $n_mod/6 张在列" ""
    tshow app "·" "已添加 $n_all 张（模块 $n_mod/6）"

    if [ -n "$au" ] && [ -n "$env_uid" ] && [ "$au" != "$env_uid" ]; then
        emit app.writable fail "背屏卡片可写" \
            "appInfo.json 属主是 $au，应与宿主配置目录一致（$env_uid）。" \
            "点「修好卡片写入」只改属主/权限，不动已添加的卡片。" "" "fix_regperm"
        tshow app "✗" "appInfo 属主异常（$au ≠ $env_uid）"
    else
        emit app.writable ok "背屏卡片可写" "appInfo.json 属主正常（$au）" ""
        tshow app "✓" "appInfo 属主正常"
    fi
else
    emit app.registry fail "已添加卡片" "appInfo.json 不存在" "打开一次背屏应用卡中心，再重新检测。"
    tshow app "✗" "appInfo.json 不存在"
fi

# ============================================================ 8. AI 应用卡
# 主题商店以 system_theme 身份往这个目录里写 AI 卡。属主一旦被外部 root 操作
# 改成 root，下载回来的卡就一张都落不了盘，索引 runtimeAiApp.json 也建不出来，
# 背屏里自然一张 AI 卡都不显示 ——「点应用失败」的真实原因常在这一层。
# 属主是决定项；0777 只是照抄正常机器的基线（只改权限不改属主，治不好）。
tgroup "AI 应用卡"
AIAPP=$MIBACKSCREEN_AIAPP_DIR
if [ -d "$AIAPP" ]; then
    a_owner=$(stat -c '%U:%G' "$AIAPP" 2>/dev/null)
    a_uid=$(stat -c '%u' "$AIAPP" 2>/dev/null)
    a_mode=$(stat -c '%a' "$AIAPP" 2>/dev/null)
    a_n=$(ls "$AIAPP" 2>/dev/null | wc -l | tr -d ' ')
    if [ "$a_owner" = "system_theme:system_theme" ]; then
        emit aiapp.owner ok "资源目录属主" "$a_owner（权限 $a_mode，$a_n 个子项）" ""
        tshow aiapp "✓" "资源目录属主正常（$a_n 子项）"
    else
        emit aiapp.owner fail "资源目录属主" \
            "属主是 $a_owner（uid $a_uid，权限 $a_mode）。主题商店以 system_theme 身份写入，属主不对就落不了盘 —— 表现为「下载成功但点应用失败」，背屏里一张 AI 卡都没有。" \
            "点「修好 AI 卡目录」就地改回 system_theme，再重启主题商店重新应用一次。" "" "fix_aiapp"
        tshow aiapp "✗" "资源目录属主异常（$a_owner）"
    fi
    # 索引文件是 APK 侧同步的总开关：没有它，syncAiGeneratedAppCardsToSubscreenIndexNow() 开头就 return
    if [ -f "$AIAPP/runtimeAiApp.json" ]; then
        a_cnt=$(grep -ao '"productId"' "$AIAPP/runtimeAiApp.json" 2>/dev/null | wc -l | tr -d ' ')
        emit aiapp.index info "AI 卡索引" "runtimeAiApp.json 存在（$a_cnt 张卡）" ""
        tshow aiapp "·" "AI 卡索引 $a_cnt 张"
    else
        emit aiapp.index warn "AI 卡索引" \
            "runtimeAiApp.json 不存在。模块侧同步读到它才会注入，没有这个文件就整段跳过。" \
            "去主题商店下载并使用一次「AI 生成应用卡」；如果下载失败，先看上面那条属主检查。"
        tshow aiapp "!" "AI 卡索引缺失"
    fi
else
    # 目录不存在不等于故障：这台机器可能压根没用过 AI 生成应用卡
    emit aiapp.owner info "资源目录" \
        "尚未创建。这台机器还没成功用过「AI 生成应用卡」，或还没触发过下载。" \
        "去主题商店下载并使用一次后，这个目录才会出现，届时回来重新检测。"
    tshow aiapp "·" "AI 卡目录尚未创建"
fi

# ============================================================ 9. 结论
tgroup "结论"
n_fail=$(grep -c '^level=fail' "$OUT" 2>/dev/null)
n_warn=$(grep -c '^level=warn' "$OUT" 2>/dev/null)
n_fail=${n_fail:-0}; n_warn=${n_warn:-0}
if [ "$NS_ISOLATED" = "1" ]; then
    emit result verdict fail "视图被隔离" "诊断在隔离命名空间里运行，结果不可信，请用 adb shell su -c 重跑。" ""
    tline "  结论：视图被隔离，结果不可信。"
elif [ "$n_fail" -gt 0 ]; then
    emit result verdict fail "$n_fail 项异常" "见上方标 ✗ 的检查项，每条都写了怎么修。" ""
    tline "  结论：$n_fail 项异常，$n_warn 项注意。"
elif [ "$n_warn" -gt 0 ]; then
    emit result verdict warn "$n_warn 项注意" "没有硬故障，注意项可能不影响使用。" ""
    tline "  结论：$n_warn 项注意。"
else
    emit result verdict ok "全部通过" "文件链路与应用视角均正常。" ""
    tline "  结论：全部通过。"
fi

if [ "$txt" = "1" ]; then
    cat "$TXT"
else
    cat "$OUT"
fi
rm -f "$OUT" "$TXT" 2>/dev/null
