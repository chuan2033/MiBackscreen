#!/system/bin/sh
# ============================================================================
# 清除 REAREye 预设包：删掉它提交给宿主的 RPP 快照缓存，让宿主重新读真实文件系统。
#
# 只删缓存（/data/data/<宿主>/cache/reareye-preset-pack），
# 不动 REAREye 的应用本体与其它设置。REAREye 想用随时可重新下载预设包。
# 输出末尾一行 RESULT=ok|nothing|fail。
# ============================================================================

MODDIR=${0%/*}
[ -f "$MODDIR/module.prop" ] || MODDIR=/data/adb/modules/mibackscreen_18_personalization

. "$MODDIR/lib.sh"
ns_reexec "$@"

echo "清除 REAREye 预设包"
echo

if [ "$NS_ISOLATED" = "1" ]; then
    echo "当前视图被隔离，看不到其它应用数据目录，无法确认是否删除成功。"
    echo "请改用 adb shell su -c 运行。"
    echo "RESULT=fail"
    exit 1
fi

PKGS="com.miui.personalassistant com.xiaomi.subscreencenter com.android.thememanager"
removed=0
found=0

for pkg in $PKGS; do
    base=/data/data/$pkg/cache/reareye-preset-pack
    if [ ! -d "$base" ]; then
        echo "  $pkg：无预设缓存"
        continue
    fi
    found=1
    n=$(find "$base" -name active.rpp 2>/dev/null | wc -l | tr -d ' ')
    echo "  $pkg：发现预设缓存（active.rpp $n 个），删除中…"
    rm -rf "$base" 2>/dev/null && removed=$((removed + 1))
done

echo
if [ "$found" = "0" ]; then
    echo "未发现任何 REAREye 预设缓存，无需清除。"
    echo "RESULT=nothing"
    exit 0
fi

if [ "$removed" -gt 0 ]; then
    pkill -f com.miui.personalassistant 2>/dev/null
    pkill -f com.xiaomi.subscreencenter 2>/dev/null
    pkill -f com.android.thememanager 2>/dev/null
    klog "clear-reareye removed=$removed"
    echo "已清除 $removed 处预设缓存并重启相关应用。重启手机后本模块即生效。"
    echo "RESULT=ok"
else
    echo "删除失败（权限不足或目录被占用），请导出日志反馈。"
    echo "RESULT=fail"
fi
