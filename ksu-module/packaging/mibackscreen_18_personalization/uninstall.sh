#!/system/bin/sh
# ============================================================================
# 卸载清理
#
# 管理器在「标记移除后的下一次开机」早期执行本脚本，执行完就删掉模块目录。
#
# appInfo.json 只做两件事，不再整份覆盖：
#   · remove_owned：删掉本模块自己新增、且仍指向本模块数据根的卡；
#   · restore_modified：把被本模块改过路径的宿主卡还原成原件。
# 以前「当前哈希 == 最后一次补丁哈希就把首次同步时的基线整份拷回」的捷径
# 已去掉：基线只在第一次同步时生成，之后用户新加的卡、LSPosed 写入的面板卡 /
# AI 卡都不在基线里，整份拷回会把它们一起抹掉。
#
# 清理失败时（硬错误，或连续冲突）不删状态目录，也不删 data-root：
#   · 状态目录里有「自己新增了哪些卡」「被改卡的原件」，删了就再也还原不了；
#   · data-root 删了，索引里的卡就指向不存在的资源，LSPosed 侧会把这些悬空条目
#     自动删除，原本属于宿主的卡就永久丢失。
# 卸载后模块目录已经不在，没有任何代码会在下次开机自动重试。所以失败时：
#   · 状态目录（/data/adb/mibackscreen_18_personalization_state，在模块目录之外，
#     管理器不会删）原样保留，并写 UNINSTALL_INCOMPLETE 说明与 uninstall.log；
#   · 把本脚本和两个库复制到状态目录的 uninstall_retry/，可以手动重跑：
#       su -c 'sh /data/adb/mibackscreen_18_personalization_state/uninstall_retry/uninstall.sh'
#   · 重新安装模块也行：记录会被继续沿用，下次卸载再清理。
# ============================================================================

MODDIR=${0%/*}
MODID=mibackscreen_18_personalization
TAG=MiBackscreenKsu
DATA_ROOT=/data/system/theme_magic/users/0/mibackscreen_18
DATA_PARENT=${DATA_ROOT%/*}
CONFIG_DIR=/data/system/theme_magic/users/0/subscreencenter/config
APPINFO="$CONFIG_DIR/appInfo.json"
STATE_ROOT=/data/adb/mibackscreen_18_personalization_state
OWNED_IDS="$STATE_ROOT/appcard_owned_ids"
MODIFIED_IDS="$STATE_ROOT/appcard_modified_ids"
ORIGINAL_ENTRIES="$STATE_ROOT/appcard_original_entries"
RETRY_DIR="$STATE_ROOT/uninstall_retry"
ULOG="$STATE_ROOT/uninstall.log"

lib_ok=1
. "$MODDIR/appinfo_txn.sh" 2>/dev/null || lib_ok=0
. "$MODDIR/appinfo_utils.sh" 2>/dev/null || lib_ok=0

ulog() {
    log -p i -t "$TAG" "uninstall: $*" 2>/dev/null
    [ -d "$STATE_ROOT" ] || return 0
    printf '%s %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*" >> "$ULOG" 2>/dev/null
    chmod 0600 "$ULOG" 2>/dev/null
}

# 只匹配命令行以包名开头的进程，见 service.sh 的 kill_host
kill_host() {
    pkill -f "^$(printf '%s' "$1" | sed 's/\./\\./g')" 2>/dev/null
}

if [ "$lib_ok" = "1" ]; then
    trap appinfo_txn_cleanup EXIT
    trap 'exit 129' HUP
    trap 'exit 130' INT
    trap 'exit 143' TERM
    # 开机早期执行时没有任何宿主进程，共用锁若还在必然是上次开机留下的；
    # 直接清掉，免得时钟回拨让它看起来「不陈旧」而白等 30 秒（会拖慢开机）。
    if ! pidof com.xiaomi.subscreencenter com.miui.personalassistant \
            com.android.thememanager >/dev/null 2>&1; then
        rm -rf "$APPINFO.mibackscreen.lockd" 2>/dev/null
    fi
    APPINFO_SHARED_LOCK_WAIT=10
fi

# 卸载时宿主可能正开着并写 appInfo.json；冲突（rc=2）就等一拍重来，最多 5 轮。
cleanup_ok=1
appinfo_changed=0
if { [ -s "$OWNED_IDS" ] || [ -s "$MODIFIED_IDS" ]; } && [ -s "$APPINFO" ]; then
    cleanup_ok=0
    if [ "$lib_ok" != "1" ]; then
        ulog "appInfo cleanup skipped: helper scripts missing"
    else
        hash_before="$(sha256sum "$APPINFO" 2>/dev/null | awk '{print $1}')"
        cleanup_round=1
        while [ "$cleanup_round" -le 5 ]; do
            cleanup_rc=0
            if [ -s "$OWNED_IDS" ]; then
                appinfo_remove_owned "$APPINFO" "$OWNED_IDS" "$DATA_ROOT/appcard/"
                cleanup_rc=$?
            fi
            if [ "$cleanup_rc" = "0" ] && [ -s "$MODIFIED_IDS" ]; then
                appinfo_restore_modified "$APPINFO" "$MODIFIED_IDS" \
                    "$ORIGINAL_ENTRIES" "$DATA_ROOT/appcard/"
                cleanup_rc=$?
            fi

            if [ "$cleanup_rc" = "0" ]; then
                cleanup_ok=1
                break
            fi
            ulog "appInfo cleanup round $cleanup_round rc=$cleanup_rc $APPINFO_ERR"
            [ "$cleanup_rc" = "2" ] || break
            cleanup_round=$((cleanup_round + 1))
            sleep 2
        done
        hash_after="$(sha256sum "$APPINFO" 2>/dev/null | awk '{print $1}')"
        [ "$hash_before" != "$hash_after" ] && appinfo_changed=1
    fi
fi

if [ "$appinfo_changed" = "1" ]; then
    kill_host com.xiaomi.subscreencenter
    kill_host com.miui.personalassistant
fi

# 无论成败都清的残留：资源暂存 / 旧目录、本模块在 config 目录的临时文件、
# 旧版本的运行时目录与挂载残留。
rm -rf "$DATA_PARENT"/.mibackscreen_18.* 2>/dev/null
rm -f "$CONFIG_DIR"/appInfo.json.mibackscreen.sh.*.tmp 2>/dev/null
rm -rf /data/mibackscreen_18_personalization_runtime
rm -rf /data/adb/mibackscreen_18_personalization_runtime
rm -rf "/data/adb/metamodule/mnt/$MODID"
rm -rf "/data/adb/modules/meta-overlayfs/mnt/$MODID"

if [ "$cleanup_ok" = "1" ]; then
    rm -rf "$DATA_ROOT"
    rm -rf "$STATE_ROOT"
    rm -f /data/local/tmp/mibackscreen_personalization.log 2>/dev/null
    exit 0
fi

# ---- 清理没完成：保留状态与资源，留下说明和手动重试入口 ----
rm -rf "$STATE_ROOT/lock" "$STATE_ROOT/appinfo.lock" 2>/dev/null
rm -f "$STATE_ROOT"/appInfo.transaction.* "$STATE_ROOT"/appInfo.extract.* 2>/dev/null
if [ "$MODDIR" != "$RETRY_DIR" ] && mkdir -p "$RETRY_DIR" 2>/dev/null; then
    cp -f "$MODDIR/uninstall.sh" "$MODDIR/appinfo_txn.sh" "$MODDIR/appinfo_utils.sh" \
        "$RETRY_DIR/" 2>/dev/null
    chmod 0700 "$RETRY_DIR" 2>/dev/null
fi
{
    echo "MiBackscreen 附加资源模块卸载时没能把 appInfo.json 清理干净。"
    echo "time=$(date '+%Y-%m-%d %H:%M:%S')"
    echo "reason=${APPINFO_ERR:-unknown}"
    echo
    echo "为了不丢卡片，以下内容被保留："
    echo "  $STATE_ROOT （新增卡记录、被改卡的原件）"
    echo "  $DATA_ROOT （卡片引用的资源）"
    echo
    echo "处理方式（二选一）："
    echo "  1) su -c 'sh $RETRY_DIR/uninstall.sh'"
    echo "  2) 重新安装模块，之后再正常卸载"
} > "$STATE_ROOT/UNINSTALL_INCOMPLETE" 2>/dev/null
chmod 0600 "$STATE_ROOT/UNINSTALL_INCOMPLETE" 2>/dev/null
ulog "cleanup incomplete; kept $STATE_ROOT and $DATA_ROOT, see UNINSTALL_INCOMPLETE"
exit 0
