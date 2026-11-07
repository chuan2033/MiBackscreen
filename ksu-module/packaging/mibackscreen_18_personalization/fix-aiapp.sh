#!/system/bin/sh
# ============================================================================
# 修好 AI 卡目录：把 rearScreenAiApp_Theme 的属主/权限改回「主题商店可写」。
#
# 症状：主题商店里下载「AI 生成应用卡」提示成功，但点「应用」失败；
#       背屏中心的应用卡列表里始终看不到任何 AI 卡。
# 原因：该目录属主被外部 root 操作（文件管理器 / 别的背屏工具）改成了 root:root。
#       主题商店以 system_theme 身份运行，写不进去 → 下载回来的卡落盘失败 →
#       索引 runtimeAiApp.json 建不出来 → 模块侧同步整段跳过。
#
# 只改属主/权限与 SELinux 上下文，**不删除任何文件**；重复执行安全。
# 输出末尾一行 RESULT=ok|nothing|fail。
# ============================================================================

MODDIR=${0%/*}
[ -f "$MODDIR/module.prop" ] || MODDIR=/data/adb/modules/mibackscreen_18_personalization

. "$MODDIR/lib.sh"
ns_reexec "$@"

D=$MIBACKSCREEN_AIAPP_DIR
WANT=system_theme:system_theme

echo "修好 AI 卡目录：$D"
echo

if [ ! -d "$D" ]; then
    echo "目录不存在 —— 说明这台机器还没成功用过「AI 生成应用卡」。"
    echo "请先去主题商店下载并使用一次，再回来点这个按钮。"
    echo "RESULT=fail"
    exit 1
fi

cur_owner=$(stat -c '%U:%G' "$D" 2>/dev/null)
cur_mode=$(stat -c '%a' "$D" 2>/dev/null)
cur_n=$(ls "$D" 2>/dev/null | wc -l | tr -d ' ')
echo "修复前：属主 $cur_owner，权限 $cur_mode，$cur_n 个子项"

if [ "$cur_owner" = "$WANT" ]; then
    echo "属主本来就正确，无需修改。"
    echo "如果背屏里仍然没有 AI 卡，去主题商店重新点一次「应用」，或重启一次手机。"
    echo "RESULT=nothing"
    exit 0
fi

# 属主是关键项；0777 只是照抄正常机器的基线。
# 只改目录权限不改属主的话，目录里已有的 root 文件仍是 root 所有，迟早再出问题。
chown -R "$WANT" "$D" 2>/dev/null
find "$D" -type d -exec chmod 0777 {} \; 2>/dev/null
find "$D" -type f -exec chmod 0644 {} \; 2>/dev/null
restorecon -R "$D" 2>/dev/null

new_owner=$(stat -c '%U:%G' "$D" 2>/dev/null)
new_mode=$(stat -c '%a' "$D" 2>/dev/null)
new_n=$(ls "$D" 2>/dev/null | wc -l | tr -d ' ')
echo "修复后：属主 $new_owner，权限 $new_mode，$new_n 个子项"
echo

if [ "$new_owner" != "$WANT" ]; then
    echo "属主没改成功（当前 $new_owner），可能被 SELinux 或挂载策略挡住了。"
    echo "请把上面的完整输出发回。"
    echo "RESULT=fail"
    exit 1
fi

pkill -f com.android.thememanager 2>/dev/null
klog "fix-aiapp owner $cur_owner -> $new_owner ($cur_n -> $new_n items)"

echo "已改回 $WANT，并重启了主题商店。"
if [ -f "$D/runtimeAiApp.json" ]; then
    echo "去主题商店里重新点一次「AI 生成应用卡」的「应用」，背屏上就会出现。"
else
    echo "索引文件还没有：去主题商店下载并使用一次「AI 生成应用卡」，之后背屏上就会出现。"
fi
echo "RESULT=ok"
