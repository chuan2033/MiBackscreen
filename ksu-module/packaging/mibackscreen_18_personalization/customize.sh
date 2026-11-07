#!/system/bin/sh
# ============================================================================
# 支持的管理器
#
#   KernelSU 系：官方 KernelSU 与 KernelSU-Next / Wild KSU / SukiSU-Ultra /
#                ReSukiSU / MKSU / RKSU 等分支，它们都继承 ksud 的安装环境，
#                一律设 KSU=true。
#   Magisk 系：官方 Stable / Beta / Canary 与 Kitsune Mask、Magisk Alpha 等
#                分支，都不设 KSU / APATCH。
#   APatch 未验证，安装时中止。
#
# 为什么不能靠 MAGISK_VER 判断「是不是 Magisk」：KernelSU 把它固定为
# v25.2 / 25200，APatch 固定为 v27.0 / 27000（两边官方文档都写明是为兼容
# 按 Magisk 版本判断的老模块）。所以 MAGISK_VER 有值不等于 Magisk，正确写法
# 是 KSU → APatch → 其余按 Magisk 兜底。
# ============================================================================

MODID=mibackscreen_18_personalization

[ "$BOOTMODE" = "true" ] || abort "! 请通过管理器安装（不支持 recovery 刷入）"

if [ "$APATCH" = "true" ]; then
    abort "! 本模块支持 KernelSU 与 Magisk，暂不支持 APatch"
fi

if [ "$KSU" = "true" ]; then
    MOD_MANAGER=KernelSU
else
    MOD_MANAGER=Magisk
fi

DEVICE="$(getprop ro.product.device)"
case "$DEVICE" in
    popsicle|pandora)
        ;;
    *)
        abort "! 当前设备 $DEVICE 不在支持列表（popsicle / pandora）"
        ;;
esac

[ -d "$MODPATH/mibackscreen_resources" ] || abort "! 安装包缺少背屏资源"
[ -d "$MODPATH/appcard_entries" ] || abort "! 安装包缺少应用卡索引"

rm -rf "$MODPATH/product" "$MODPATH/system" 2>/dev/null
rm -rf "/data/adb/metamodule/mnt/$MODID" 2>/dev/null
rm -rf "/data/adb/modules/meta-overlayfs/mnt/$MODID" 2>/dev/null
set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/customize.sh" 0 0 0755
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755
set_perm "$MODPATH/status.sh" 0 0 0755
set_perm "$MODPATH/action.sh" 0 0 0755
set_perm "$MODPATH/logpack.sh" 0 0 0755
set_perm "$MODPATH/fix-perms.sh" 0 0 0755
set_perm "$MODPATH/fix-regperm.sh" 0 0 0755
set_perm "$MODPATH/clear-reareye.sh" 0 0 0755
set_perm "$MODPATH/lib.sh" 0 0 0755
set_perm "$MODPATH/appinfo_utils.sh" 0 0 0644
set_perm "$MODPATH/appinfo_txn.sh" 0 0 0644
[ -d "$MODPATH/webroot" ] && set_perm_recursive "$MODPATH/webroot" 0 0 0755 0644
ui_print "- 已识别管理器：$MOD_MANAGER"
ui_print "- 已识别设备：$DEVICE"
ui_print "- 背屏资源将在系统启动完成后同步"
ui_print "- 点模块卡片可打开状态面板；没装 WebUI 宿主时点「操作」也能诊断"
