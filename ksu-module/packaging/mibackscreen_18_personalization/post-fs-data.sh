#!/system/bin/sh
# ============================================================================
# 开机极早期（post-fs-data 阶段）
#
# 这个阶段会阻塞开机（Magisk / KernelSU 约有 10 秒上限），这里只做轻量收尾：
# 清掉上次开机遗留的锁与临时文件。**不搬资源、不碰 appInfo.json 内容。**
#
# 以前这里会在资源缺失时复制约 68MB，理由是「service.sh 被跳过时兜底」——
# 不成立：post-fs-data 同样只在开机时运行，service.sh 随后还会再复制一遍，
# 超时后两次复制还可能并行。资源同步统一交给 service.sh（late_start 阶段）。
#
# 为什么锁要在这里清：
#   service.sh 的锁目录放在持久分区（/data/adb/...），EXIT trap 在 SIGKILL /
#   关机时不会执行。开机后约 4.5 分钟的守护窗口内重启手机，锁就会一直留着，
#   之后每次开机都记一行 "another sync is active" 然后退出，模块从此静默。
#   post-fs-data 阶段本模块的脚本和所有应用进程都还没开始跑，此刻出现的锁
#   一定是陈旧的，可以无条件删除。（选这个方案而不是在锁里记 boot_id：
#   清理点只有一个，service.sh / action 的取锁逻辑不用跟着变。）
#
# 注意：本脚本里不能 source service.sh —— 那个文件的顶层语句会直接
# 执行同步流程（含 appInfo 写入），不是纯函数库。
# ============================================================================

STATE_ROOT=/data/adb/mibackscreen_18_personalization_state
CONFIG_DIR=/data/system/theme_magic/users/0/subscreencenter/config
APPINFO="$CONFIG_DIR/appInfo.json"
RUNLOG="$STATE_ROOT/post-fs-data.log"

logi() {
    [ -d "$STATE_ROOT" ] || return 0
    printf '%s %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*" >> "$RUNLOG" 2>/dev/null
    chmod 0600 "$RUNLOG" 2>/dev/null
}

cleared=""
# 1. 模块内部锁：service.sh 的服务锁与 appInfo 写入锁
for stale in "$STATE_ROOT/lock" "$STATE_ROOT/appinfo.lock"; do
    if [ -e "$stale" ]; then
        rm -rf "$stale" 2>/dev/null
        cleared="$cleared ${stale##*/}"
    fi
done

# 2. 与 LSPosed 模块共用的 appInfo 锁（协议见 appinfo_txn.sh）
if [ -e "$APPINFO.mibackscreen.lockd" ]; then
    rm -rf "$APPINFO.mibackscreen.lockd" 2>/dev/null
    cleared="$cleared appInfo.json.mibackscreen.lockd"
fi

# 3. 本模块写在 config 目录里、没来得及提交的临时文件
for stale in "$CONFIG_DIR"/appInfo.json.mibackscreen.sh.*.tmp; do
    [ -e "$stale" ] || continue
    rm -f "$stale" 2>/dev/null
    cleared="$cleared ${stale##*/}"
done

[ -n "$cleared" ] && logi "post-fs-data: cleared stale:$cleared"
exit 0
