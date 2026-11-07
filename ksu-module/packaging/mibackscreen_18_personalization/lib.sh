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
_nsenter() {
    if command -v nsenter >/dev/null 2>&1; then
        nsenter "$@"
    elif [ -x /system/bin/nsenter ]; then
        /system/bin/nsenter "$@"
    else
        for _b in /data/adb/ksu/bin/busybox /data/adb/magisk/busybox \
                  /data/adb/ap/bin/busybox /system/bin/busybox; do
            [ -x "$_b" ] && { "$_b" nsenter "$@"; return $?; }
        done
        return 127
    fi
}

ns_reexec() {
    [ -n "$MIBACKSCREEN_NS_FIXED" ] && return 0
    n=$(ls /data/data 2>/dev/null | wc -l)
    [ "${n:-0}" -ge 100 ] && return 0            # 视图正常，无事发生

    n2=$(_nsenter -t 1 -m -- ls /data/data 2>/dev/null | wc -l)
    [ "${n2:-0}" -ge 100 ] || return 0           # nsenter 也救不了，算了

    MIBACKSCREEN_NS_FIXED=1
    export MIBACKSCREEN_NS_FIXED
    exec _nsenter -t 1 -m -- "$0" "$@"
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
