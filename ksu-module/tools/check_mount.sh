#!/system/bin/sh
# ============================================================================
# MiBackscreen 附加模块 —— 环境与模块结构快照（只读）
#
# 用法：
#   adb push check_mount.sh /data/local/tmp/
#   adb shell su -c 'sh /data/local/tmp/check_mount.sh'
#
# 结果同时打印并落盘 /sdcard/mibackscreen_check.txt。
#
# 注意：模块已改为 data-root 方案，**不再使用挂载**（实际版本以下方打印的
#       module.prop 为准）。本脚本只做环境/结构快照；失效定位请用 diagnose_rear_presets.sh。
#
# 隐私：会列出 /data/adb/modules 下所有已装模块的目录名，以及过滤后的 logcat
#       （只保留含 rearscreen / appcard / ai-mate / mibackscreen_18 的行）。
# ============================================================================

OUT=/sdcard/mibackscreen_check.txt
: > "$OUT" 2>/dev/null || OUT=/data/local/tmp/mibackscreen_check.txt

exec 9>&1
exec > "$OUT" 2>&1

MOD=mibackscreen_18_personalization
MODDIR=/data/adb/modules/$MOD
DATA_ROOT=/data/system/theme_magic/users/0/mibackscreen_18
APPINFO=/data/system/theme_magic/users/0/subscreencenter/config/appInfo.json

echo "==================== 1. 环境 ===================="
getprop ro.product.device
getprop ro.product.model
getprop ro.build.version.incremental
echo "-- Root 方案 --"
echo "ksu_dir=$([ -d /data/adb/ksu ] && echo present || echo absent)"
echo "ksud=$(command -v ksud 2>/dev/null || echo absent)"
echo "metamodule=$([ -e /data/adb/metamodule ] && echo present || echo absent)  (本模块不依赖)"
echo "-- 所有模块 --"
ls -1 /data/adb/modules/ 2>&1
echo "-- 本模块启用状态 --"
ls -la "$MODDIR" 2>&1 | grep -E "disable|remove|skip_mount"

echo
echo "==================== 2. 模块结构 ===================="
echo "-- 顶层（期望：module.prop / service.sh / skip_mount / mibackscreen_resources / appcard_entries / appinfo_utils.sh / uninstall.sh）--"
ls -la "$MODDIR" 2>&1
echo "-- 不应存在（挂载方案遗留）--"
echo "system=$([ -d "$MODDIR/system" ] && echo PRESENT_BAD || echo ok)"
echo "product=$([ -d "$MODDIR/product" ] && echo PRESENT_BAD || echo ok)"
echo "mount_resources.sh=$(ls "$MODDIR/mount_resources.sh" 2>/dev/null >/dev/null && echo PRESENT_BAD || echo ok)"
echo "-- 资源文件数（期望 58）--"
find "$MODDIR/mibackscreen_resources" -type f 2>/dev/null | wc -l
echo "-- 应用卡条目（期望 6）--"
ls -1 "$MODDIR/appcard_entries"/*.json 2>/dev/null
echo "-- 模块版本 --"
cat "$MODDIR/module.prop" 2>&1

echo
echo "==================== 3. data-root 资源落点 ===================="
echo "-- 目录 --"
ls -la "$DATA_ROOT" 2>&1
echo "-- 关键文件 --"
for f in template/normal/rearScreen.json template/default/rearScreen.json; do
    p="$DATA_ROOT/$f"
    if [ -f "$p" ]; then
        printf "%-52s exists ai-mate=%s\n" "$f" "$(grep -o 'ai-mate' "$p" 2>/dev/null | wc -l | tr -d ' ')"
    else
        printf "%-52s MISSING\n" "$f"
    fi
done
echo "-- appcard 分类目录 --"
find "$DATA_ROOT/appcard" -maxdepth 3 -mindepth 3 -type d 2>/dev/null | sort
echo "-- precust_theme 文件数 --"
find "$DATA_ROOT/precust_theme/theme/.data" -type f 2>/dev/null | wc -l
echo "-- 文件总数 --"
find "$DATA_ROOT" -type f 2>/dev/null | wc -l
echo "-- 属主/权限（应等于 theme_magic/users/0）--"
stat -c '%u:%g %a %n' "$DATA_ROOT" 2>&1
stat -c '%u:%g %a %n' /data/system/theme_magic/users/0 2>&1
echo "-- SELinux context --"
ls -Zd "$DATA_ROOT" 2>&1

echo
echo "==================== 4. appInfo.json ===================="
if [ -f "$APPINFO" ]; then
    stat -c '%u:%g %a %n' "$APPINFO" 2>&1
    echo "所属目录 env uid=$(stat -c %u /data/system/theme_magic/users/0 2>/dev/null)"
    echo "-- 六张预置卡 --"
    for pair in "1c922ed0-d694-508e-8de9-2d8b2ebf59a5:精选台历" \
                "36b61506-b02b-5f68-aaa9-7977f6804281:临近日程" \
                "44630e40-115e-590f-b81e-a72cae9efab4:隐身模式" \
                "04d7ca77-4e02-57b0-b9c3-21d12d3f0620:米家摄像机" \
                "62d7c4e8-91a3-5b2f-8c0d-4e1f2a3b4c5d:小米汽车" \
                "f05e6e29-5eeb-573c-bf31-f1da128f2b5f:股票行情"; do
        id=${pair%%:*}; name=${pair#*:}
        printf "%-12s %s\n" "$name" "$(grep -c "$id" "$APPINFO" 2>/dev/null)"
    done
else
    echo "(appInfo.json 不存在)"
fi

echo
echo "==================== 5. 状态与日志 ===================="
echo "-- 状态目录 --"
ls -la /data/adb/mibackscreen_18_personalization_state 2>&1
echo "-- service.log --"
cat /data/adb/mibackscreen_18_personalization_state/service.log 2>&1
echo "-- 已记录的新增卡 ID --"
cat /data/adb/mibackscreen_18_personalization_state/appcard_owned_ids 2>&1

echo
echo "==================== 6. 日志 ===================="
echo "-- MiBackscreen 标签 --"
logcat -d -s MiBackscreen 2>/dev/null | tail -30
echo
echo "-- 背屏/主题相关 --"
logcat -d 2>/dev/null | grep -iE "rearscreen|appcard|ai-mate|mibackscreen_18" | tail -40

echo
echo "==================== 完成 ===================="
echo "结果文件: $OUT"

exec >&9
echo "--------------------------------------------"
echo "快照完成。失效定位请再用 diagnose_rear_presets.sh。"
echo "  $OUT"
echo "--------------------------------------------"
