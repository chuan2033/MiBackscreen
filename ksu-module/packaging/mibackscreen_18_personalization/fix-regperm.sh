#!/system/bin/sh
# ============================================================================
# 修好卡片写入：把 appInfo.json 的属主/权限改成「背屏应用可写」。
#
# 只改属主/权限与上下文，**不动卡片清单内容**（用户已有的卡片要保住）。
# 症状：点「添加」提示成功、背屏却没变化，重启后张数回到原样。
# 输出末尾一行 RESULT=ok|nothing|fail。
# ============================================================================

MODDIR=${0%/*}
[ -f "$MODDIR/module.prop" ] || MODDIR=/data/adb/modules/mibackscreen_18_personalization

. "$MODDIR/lib.sh"
ns_reexec "$@"

APPINFO=$MIBACKSCREEN_APPINFO
PARENT=$MIBACKSCREEN_CONFIG_DIR

echo "修好卡片写入：$APPINFO"
echo

if [ ! -f "$APPINFO" ]; then
    echo "appInfo.json 不存在，请先打开一次背屏应用卡中心让它生成。"
    echo "RESULT=fail"
    exit 1
fi

env_uid=$(stat -c %u "$PARENT" 2>/dev/null)
env_gid=$(stat -c %g "$PARENT" 2>/dev/null)
cur_uid=$(stat -c %u "$APPINFO" 2>/dev/null)

if [ "$cur_uid" = "$env_uid" ]; then
    echo "属主本来就正确（$cur_uid），无需修改。"
    echo "RESULT=nothing"
    exit 0
fi

# 同时修 appInfo 所在目录的其他状态文件（widget.json 等），保持一致
chown "$env_uid:$env_gid" "$APPINFO" 2>/dev/null
chmod 0644 "$APPINFO" 2>/dev/null
restorecon "$APPINFO" 2>/dev/null

echo "已把属主从 $cur_uid 改为 $env_uid:$env_gid。"

if [ -f "$MIBACKSCREEN_CONFIG_DIR/widget.json" ]; then
    chown "$env_uid:$env_gid" "$MIBACKSCREEN_CONFIG_DIR/widget.json" 2>/dev/null
    chmod 0644 "$MIBACKSCREEN_CONFIG_DIR/widget.json" 2>/dev/null
    restorecon "$MIBACKSCREEN_CONFIG_DIR/widget.json" 2>/dev/null
    echo "widget.json 属主/权限已一并校正。"
fi

pkill -f com.miui.personalassistant 2>/dev/null
pkill -f com.xiaomi.subscreencenter 2>/dev/null
klog "fix-regperm uid $cur_uid -> $env_uid"

echo "已重启应用卡中心。回到应用卡中心再点一次「添加」，这次背屏上就会出现了。"
echo "RESULT=ok"
