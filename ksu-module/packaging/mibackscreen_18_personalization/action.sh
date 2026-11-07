#!/system/bin/sh
# ============================================================================
# 管理器模块卡「操作」按钮入口（APatch / KernelSU / Magisk 都支持 action.sh）
#
# 没装 WebUI 宿主（WebUI X / KSU WebUI Standalone）的用户走这条路：
#   跑一遍诊断 → 打印 → 顺手生成日志包 → 告诉用户文件在哪
#
# 输出会被管理器捕捉并显示在「执行模块操作」界面里。
# ============================================================================

MODDIR=${0%/*}
[ -f "$MODDIR/module.prop" ] || MODDIR=/data/adb/modules/mibackscreen_18_personalization

. "$MODDIR/lib.sh"
ns_reexec "$@"

MODVER=$(sed -n 's/^version=//p' "$MODDIR/module.prop" 2>/dev/null)

echo "=============================================="
echo " MiBackscreen背屏附加资源 · 状态自检"
echo " v${MODVER}"
echo "=============================================="
echo

# ---------------------------------------------------------------------------
# 先自愈再自检。
#
# 为什么需要：「装完模块不重启」时 service.sh（late_start 阶段）不会补跑，
# 资源没同步、应用卡没注入，用户看到的就是「装了模块但卡片不显示」。
# KSU 装模块后不会自动补跑 late_start 服务，只能等下一次开机。
# 这里让「点一下模块卡片」= 立即跑一次同步，不用重启就能生效。
#
# service.sh 自身幂等：宿主在写就等下一轮，已经对了就直接过。
# 失败也不影响下面的自检输出，诊断信息照常打印。
# ---------------------------------------------------------------------------
echo " 正在同步资源与应用卡…"
echo
# MIBS_NO_GUARD=1：只要一次同步，不等开机守护窗口（那是给开机流程用的）。
if MIBS_NO_GUARD=1 sh "$MODDIR/service.sh" >/dev/null 2>&1; then
    echo " 同步完成。"
else
    echo " 同步未完全成功（详见下方诊断与日志）。"
fi
echo

sh "$MODDIR/status.sh" --text

echo
echo "----------------------------------------------"
echo " 正在生成日志包…"
echo

SAVED=$(sh "$MODDIR/logpack.sh" 2>/dev/null | tail -1)

if [ -n "$SAVED" ] && [ "$SAVED" != "FAILED" ]; then
    SIZE=$(stat -c '%s' "$SAVED" 2>/dev/null || echo 0)
    echo " 日志已生成（$((SIZE / 1024)) KB）"
    echo
    echo " 位置：文件管理 → 内部存储 → Download"
    echo " 文件名：$(basename "$SAVED")"
    echo " （完整路径：$SAVED）"
    echo " 把这个文件发出来就行。"
else
    echo " 日志生成失败。"
    echo " 请手动提供："
    echo "   $MIBACKSCREEN_LOG"
    echo "   $MIBACKSCREEN_STATE_ROOT/service.log"
fi
echo "----------------------------------------------"

exit 0
