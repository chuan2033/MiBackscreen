#!/system/bin/sh

MODDIR=${0%/*}
MODID=mibackscreen_18_personalization
DATA_ROOT=/data/system/theme_magic/users/0/mibackscreen_18
CONFIG_DIR=/data/system/theme_magic/users/0/subscreencenter/config
APPINFO="$CONFIG_DIR/appInfo.json"
STATE_ROOT=/data/adb/mibackscreen_18_personalization_state
OWNED_IDS="$STATE_ROOT/appcard_owned_ids"
MODIFIED_IDS="$STATE_ROOT/appcard_modified_ids"
ORIGINAL_ENTRIES="$STATE_ROOT/appcard_original_entries"
BASELINE_APPINFO="$STATE_ROOT/appInfo.baseline"
PATCHED_HASH="$STATE_ROOT/appInfo.patched.sha256"

. "$MODDIR/appinfo_txn.sh" 2>/dev/null
. "$MODDIR/appinfo_utils.sh" 2>/dev/null

# 卸载时宿主可能正开着并持有 appInfo.json；冲突（rc=2）就等一拍重来，最多 5 轮。
# appinfo_remove_owned / appinfo_restore_modified 内部走 appinfo_commit，
# 检测到宿主插写会返回 2 且不动文件。
appinfo_changed=0
if { [ -s "$OWNED_IDS" ] || [ -s "$MODIFIED_IDS" ]; } && [ -s "$APPINFO" ]; then
    current_hash="$(sha256sum "$APPINFO" 2>/dev/null | awk '{print $1}')"
    expected_hash="$(cat "$PATCHED_HASH" 2>/dev/null)"
    if [ -f "$BASELINE_APPINFO" ] && [ -n "$expected_hash" ] \
            && [ "$current_hash" = "$expected_hash" ]; then
        cp -af "$BASELINE_APPINFO" "$APPINFO"
        restorecon "$APPINFO" 2>/dev/null
        appinfo_changed=1
    else
        cleanup_round=1
        while [ "$cleanup_round" -le 5 ]; do
            cleanup_rc=0
            if [ -s "$OWNED_IDS" ]; then
                cleanup_tmp="$STATE_ROOT/appInfo.cleanup.$$"
                appinfo_remove_owned "$APPINFO" "$OWNED_IDS" \
                    "$DATA_ROOT/appcard/" "$cleanup_tmp"
                cleanup_rc=$?
                [ "$cleanup_rc" = "0" ] || rm -f "$cleanup_tmp"
            fi
            if [ "$cleanup_rc" = "0" ] && [ -s "$MODIFIED_IDS" ]; then
                restore_tmp="$STATE_ROOT/appInfo.restore.$$"
                appinfo_restore_modified "$APPINFO" "$MODIFIED_IDS" \
                    "$ORIGINAL_ENTRIES" "$DATA_ROOT/appcard/" "$restore_tmp"
                cleanup_rc=$?
                [ "$cleanup_rc" = "0" ] || rm -f "$restore_tmp.current" "$restore_tmp.replace"
            fi

            if [ "$cleanup_rc" = "0" ]; then
                appinfo_changed=1
                break
            fi
            if [ "$cleanup_rc" != "2" ]; then
                break
            fi
            cleanup_round=$((cleanup_round + 1))
            sleep 2
        done
    fi
fi

if [ "$appinfo_changed" = "1" ]; then
    pkill -f com.xiaomi.subscreencenter 2>/dev/null
    pkill -f com.miui.personalassistant 2>/dev/null
fi

rm -rf "$DATA_ROOT"
rm -rf "$STATE_ROOT"
rm -f /data/local/tmp/mibackscreen_personalization.log 2>/dev/null
rm -rf /data/mibackscreen_18_personalization_runtime
rm -rf /data/adb/mibackscreen_18_personalization_runtime
rm -rf "/data/adb/metamodule/mnt/$MODID"
rm -rf "/data/adb/modules/meta-overlayfs/mnt/$MODID"
