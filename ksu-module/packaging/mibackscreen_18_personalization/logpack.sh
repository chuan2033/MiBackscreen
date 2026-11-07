#!/system/bin/sh
# ============================================================================
# 打包日志：把诊断文本 + 关键状态文件收进一个 txt/tar.gz，输出保存路径。
#
# 最后一行输出文件路径（前端取它显示）。设备上可能没有 zip，用 tar 或纯 txt。
# ============================================================================

MODDIR=${0%/*}
[ -f "$MODDIR/module.prop" ] || MODDIR=/data/adb/modules/mibackscreen_18_personalization

. "$MODDIR/lib.sh"
ns_reexec "$@"

TS=$(date +%Y%m%d-%H%M%S)
DL=/sdcard/Download
[ -d "$DL" ] || DL=/sdcard
OUT="$DL/mibackscreen-log-$TS"

WORK="$MIBACKSCREEN_TMP/logpack.$$"
rm -rf "$WORK"; mkdir -p "$WORK" 2>/dev/null || WORK="$MODDIR"

MODVER=$(sed -n 's/^version=//p' "$MODDIR/module.prop" 2>/dev/null)

# ---- 诊断文本 ----
sh "$MODDIR/status.sh" --text > "$WORK/status.txt" 2>&1

# ---- 关键文件 ----
cp "$MODDIR/module.prop" "$WORK/module.prop" 2>/dev/null
cp "$MIBACKSCREEN_STATE_ROOT/service.log" "$WORK/service.log" 2>/dev/null
cp "$MIBACKSCREEN_STATE_ROOT/appcard_owned_ids" "$WORK/appcard_owned_ids" 2>/dev/null
cp "$MIBACKSCREEN_APPINFO" "$WORK/appInfo.json" 2>/dev/null
cp "$MIBACKSCREEN_DATA_ROOT/template/normal/rearScreen.json" "$WORK/rearScreen-normal.json" 2>/dev/null
cp "$MIBACKSCREEN_DATA_ROOT/template/default/rearScreen.json" "$WORK/rearScreen-default.json" 2>/dev/null

# ---- 环境信息 ----
{
    echo "fingerprint=$(getprop ro.build.fingerprint)"
    echo "device=$(getprop ro.product.device)"
    echo "model=$(getprop ro.product.model)"
    echo "incremental=$(getprop ro.build.version.incremental)"
    echo "module_version=$MODVER"
    echo "generated_at=$(date '+%Y-%m-%d %H:%M:%S %z')"
    echo "uptime=$(cat /proc/uptime 2>/dev/null | cut -d' ' -f1)"
} > "$WORK/env.txt" 2>&1

# ---- 打包 ----
SAVED=""
if command -v tar >/dev/null 2>&1; then
    if tar -czf "$OUT.tar.gz" -C "$WORK" . 2>/dev/null; then
        SAVED="$OUT.tar.gz"
    fi
fi

if [ -z "$SAVED" ]; then
    # tar 不可用就合并成一个 txt
    SAVED="$OUT.txt"
    {
        echo "===== env ====="; cat "$WORK/env.txt" 2>/dev/null
        echo; echo "===== status ====="; cat "$WORK/status.txt" 2>/dev/null
        echo; echo "===== service.log ====="; cat "$WORK/service.log" 2>/dev/null
        echo; echo "===== owned_ids ====="; cat "$WORK/appcard_owned_ids" 2>/dev/null
    } > "$SAVED" 2>/dev/null
fi

rm -rf "$WORK" 2>/dev/null

if [ -f "$SAVED" ]; then
    chmod 0644 "$SAVED" 2>/dev/null
    klog "logpack -> $SAVED"
    echo "$SAVED"
else
    echo "FAILED"
fi
