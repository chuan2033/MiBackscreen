#!/system/bin/sh
# ============================================================================
# 修好资源权限：把 data-root 里「应用读不到」的文件/目录权限摆正。
#
# 只动权限位与 SELinux 上下文，不动文件内容、不动 appInfo。
# 输出末尾一行 RESULT=ok|nothing|fail 供前端判读。
# ============================================================================

MODDIR=${0%/*}
[ -f "$MODDIR/module.prop" ] || MODDIR=/data/adb/modules/mibackscreen_18_personalization

. "$MODDIR/lib.sh"
ns_reexec "$@"

ROOT=$MIBACKSCREEN_DATA_ROOT
PARENT=$MIBACKSCREEN_DATA_PARENT

echo "修好资源权限：$ROOT"
echo

if [ ! -d "$ROOT" ]; then
    echo "data-root 不存在，无法修复。请先确认模块已同步资源。"
    echo "RESULT=fail"
    exit 1
fi

# 异常项 = 其他人不可读的文件 + 其他人不可进入的目录（含 ROOT 本身）。
# 直接让 find 按权限位筛选再计数，不用 for f in $(find ...)：路径里有空格会被拆开。
count_perm_issues() {
    _bad_f=$(find "$ROOT" -type f ! -perm -0004 2>/dev/null | wc -l | tr -d ' ')
    _bad_d=$(find "$ROOT" -type d ! -perm -0001 2>/dev/null | wc -l | tr -d ' ')
    echo $(( ${_bad_f:-0} + ${_bad_d:-0} ))
}

# 统计改前异常数
before=$(count_perm_issues)

if [ "$before" -eq 0 ]; then
    echo "权限本来就正常，无需修改。"
    echo "RESULT=nothing"
    exit 0
fi

# 与宿主保持一致：uid/gid 取 theme_magic/users/0
uid=$(stat -c %u "$PARENT" 2>/dev/null)
gid=$(stat -c %g "$PARENT" 2>/dev/null)
[ -n "$uid" ] && [ -n "$gid" ] && chown -R "$uid:$gid" "$ROOT" 2>/dev/null
find "$ROOT" -type d -exec chmod 0755 {} \; 2>/dev/null
find "$ROOT" -type f -exec chmod 0644 {} \; 2>/dev/null
restorecon -RF "$ROOT" 2>/dev/null

# 统计改后
after=$(count_perm_issues)

echo "已修正 $((before - after)) 项，剩余异常 $after 项。"
klog "fix-perms before=$before after=$after"

if [ "$after" -eq 0 ]; then
    pkill -f com.android.thememanager 2>/dev/null
    echo "已重启主题商店，重新打开背屏个性化即可。"
    echo "RESULT=ok"
else
    echo "仍有 $after 项异常，请导出日志反馈。"
    echo "RESULT=partial"
fi
