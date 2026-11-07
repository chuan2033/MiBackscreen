#!/system/bin/sh
# ============================================================================
# MiBackscreen 附加模块 · 共享库
#
# 被 status.sh / action.sh / fix-*.sh 共同 source：
#   · 路径常量
#   · mount namespace 修正（ns_reexec）—— 管理器 WebUI 的 exec 在隔离
#     namespace 里，不修正的话所有「去别处看一眼」的检查都会静默变成「没有」
#   · 日志
# ============================================================================

MODDIR=${MODDIR:-${0%/*}}
[ -f "$MODDIR/module.prop" ] || MODDIR=/data/adb/modules/mibackscreen_18_personalization

# ---- 路径常量 ---------------------------------------------------------------
MIBACKSCREEN_MODID=mibackscreen_18_personalization
MIBACKSCREEN_DATA_ROOT=/data/system/theme_magic/users/0/mibackscreen_18
MIBACKSCREEN_DATA_PARENT=/data/system/theme_magic/users/0
MIBACKSCREEN_CONFIG_DIR=/data/system/theme_magic/users/0/subscreencenter/config
MIBACKSCREEN_APPINFO=$MIBACKSCREEN_CONFIG_DIR/appInfo.json
# AI 生成应用卡的资源根目录：主题商店以 system_theme 身份往这里写，
# 属主一旦被外部 root 操作改成 root，下载回来的卡就一张都落不了盘（见 status.sh 第 8 段）
MIBACKSCREEN_AIAPP_DIR=$MIBACKSCREEN_DATA_PARENT/rearScreenAiApp_Theme
MIBACKSCREEN_STATE_ROOT=/data/adb/mibackscreen_18_personalization_state
MIBACKSCREEN_APP=hook.HyperBackscreen
MIBACKSCREEN_LOG=/data/local/tmp/mibackscreen_personalization.log

# 工作目录：优先 /data/local/tmp，退回模块目录
MIBACKSCREEN_TMP=/data/local/tmp
[ -d "$MIBACKSCREEN_TMP" ] || MIBACKSCREEN_TMP="$MODDIR"
export MIBACKSCREEN_MODID MIBACKSCREEN_DATA_ROOT MIBACKSCREEN_DATA_PARENT \
       MIBACKSCREEN_CONFIG_DIR MIBACKSCREEN_APPINFO MIBACKSCREEN_STATE_ROOT \
       MIBACKSCREEN_APP MIBACKSCREEN_LOG MIBACKSCREEN_TMP MIBACKSCREEN_AIAPP_DIR

# ---- 日志 -------------------------------------------------------------------
klog() {
    printf '%s %s\n' "$(date '+%H:%M:%S')" "$*" >> "$MIBACKSCREEN_LOG" 2>/dev/null
}

# ---- mount namespace 修正 ---------------------------------------------------
# 管理器 WebUI 里的 exec 是「管理器的子进程」，继承了 Android 给应用准备的
# 隔离 mount namespace。在那个视图里 /data/data 只剩几个条目，别的应用数据
# 目录「看起来根本不存在」—— 冲突检测之类的检查会静默返回「没有」。
#
# 修正：发现视图被隔离就带 init 的 mount namespace 重跑自己。
# 先验证 nsenter 真的有效再 exec，免得把脚本跑死。
#
# exec 只能执行真实的程序，不能执行 shell 函数，所以这里先把 nsenter 解析成
# 「程序路径（+ busybox 时的 applet 名）」放进变量，再 exec 它。
# 重跑用 sh "$0"，不依赖脚本文件有可执行位（fix-aiapp.sh 等在旧版安装包里是 0644）。
NS_BIN=""
NS_APPLET=""
_ns_resolve() {
    NS_BIN=""
    NS_APPLET=""
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
    [ -n "$NS_BIN" ] || _ns_resolve || return 127
    if [ -n "$NS_APPLET" ]; then
        "$NS_BIN" "$NS_APPLET" "$@"
    else
        "$NS_BIN" "$@"
    fi
}

ns_reexec() {
    [ -n "$MIBACKSCREEN_NS_FIXED" ] && return 0
    n=$(ls /data/data 2>/dev/null | wc -l)
    [ "${n:-0}" -ge 100 ] && return 0            # 视图正常，无事发生

    _ns_resolve || return 0                      # 没有 nsenter，算了
    n2=$(_nsenter -t 1 -m -- ls /data/data 2>/dev/null | wc -l)
    [ "${n2:-0}" -ge 100 ] || return 0           # nsenter 也救不了，算了

    # 进入新的 mount namespace 后 cwd 会被重置到根目录，相对路径的 $0 会失效，
    # 先在当前视图里换成绝对路径（/data 下的路径在两个视图里一致）。
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
}

# 视图是否曾被隔离（status.sh 据此提示用户）
ns_was_isolated() { [ -n "$MIBACKSCREEN_NS_FIXED" ] && echo 1 || echo 0; }

# 视图是否仍然隔离（重跑后仍 <100 项）
ns_still_isolated() {
    n=$(ls /data/data 2>/dev/null | wc -l)
    [ "${n:-0}" -lt 100 ] && echo 1 || echo 0
}

NS_ISOLATED=$(ns_still_isolated)
export NS_ISOLATED
