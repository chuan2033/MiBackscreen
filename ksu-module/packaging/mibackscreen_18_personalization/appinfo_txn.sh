#!/system/bin/sh
# ============================================================================
# 在宿主 appInfo.json 上做安全的事务性读改写
#
# 背景：宿主（背屏中心 / 智能助理）在开机早期会持续重写 appInfo.json。
# 朴素做法「读→改→mv 覆盖」会在宿主写入的间隙把它的改动静默吞掉，而且
# 脚本自己不知道失败。这里补四件事：
#
#   1. 用「mtime 秒 + 大小 + sha256」当版本指纹 —— 比单看 sha256 更早发现
#      同尺寸原地的改动，也避免 stat 精度问题。
#   2. 事务：改之前取指纹，提交前再比一次，提交后回读内容哈希。宿主在这中间
#      插了写 → 判定冲突，交给上层重试。绝不把「被覆盖」当成功。
#   3. 和 LSPosed 模块共用一把跨进程锁（appinfo_shared_lock），见下方协议。
#   4. 模块脚本之间另有一把模块内部锁（appinfo_lock，放在 /data/adb 下）。
#
# 锁都用 mkdir 实现（原子），不用 flock —— 部分 busybox 不带 flock；而且
# LSPosed 侧 Java 的 FileChannel 锁是 fcntl 锁，和 flock(2) 互不排斥。
# ============================================================================

# ---- 版本指纹 ---------------------------------------------------------------
# 输出：<mtime_sec>.<size>.<sha256前16位>
appinfo_fingerprint() {
    _fp_file="$1"
    [ -s "$_fp_file" ] || return 1
    _fp_stat="$(stat -c '%Y.%s' "$_fp_file" 2>/dev/null)" || return 1
    _fp_hash="$(sha256sum "$_fp_file" 2>/dev/null | awk '{print substr($1,1,16)}')" || return 1
    [ -n "$_fp_hash" ] || return 1
    printf '%s.%s\n' "$_fp_stat" "$_fp_hash"
}

# ---- 临时文件 ---------------------------------------------------------------
# 临时文件建在目标文件同目录：mv 是同目录 rename，新文件一出生就带 config 目录
# 的 SELinux 上下文，不会有「mv 之后、restorecon 之前」上下文不对的窗口。
# 名字带 .sh. 段，和 LSPosed 侧的 appInfo.json.mibackscreen.<pid>.<tid>.tmp 区分开，
# 卸载 / 开机清理时只按 appInfo.json.mibackscreen.sh.*.tmp 删本模块自己的残留。
APPINFO_ACTIVE_TMP=""
appinfo_tmp_path() {
    printf '%s.mibackscreen.sh.%s.%s.tmp\n' "$1" "$$" "$2"
}

# ---- 模块内部锁 -------------------------------------------------------------
# 模块脚本之间互斥（service.sh 的开机实例与「操作」按钮触发的实例）。
# appinfo_lock <锁目录> <超时秒>；成功 echo 1，失败 echo 0
appinfo_lock() {
    _lk_dir="$1"
    _lk_timeout="${2:-30}"
    _lk_wait=0
    _lk_step=1

    while [ "$_lk_wait" -lt "$_lk_timeout" ]; do
        if mkdir "$_lk_dir" 2>/dev/null; then
            printf '%s\n' "$$" > "$_lk_dir/pid" 2>/dev/null
            echo 1
            return 0
        fi
        # 陈旧锁回收：持有者已死，且锁超过 120 秒。
        # 跨开机的陈旧锁由 post-fs-data.sh 统一清掉（开机早期 pid 容易被复用，
        # 光靠 kill -0 判断不可靠）。
        if [ -f "$_lk_dir/pid" ]; then
            _lk_owner="$(cat "$_lk_dir/pid" 2>/dev/null)"
            if [ -n "$_lk_owner" ] && ! kill -0 "$_lk_owner" 2>/dev/null; then
                _lk_age=$(( $(date +%s) - $(stat -c %Y "$_lk_dir" 2>/dev/null || date +%s) ))
                if [ "$_lk_age" -gt 120 ]; then
                    rm -rf "$_lk_dir" 2>/dev/null
                    [ -e "$_lk_dir" ] || continue
                fi
            fi
        fi
        sleep "$_lk_step"
        _lk_wait=$((_lk_wait + _lk_step))
    done
    echo 0
    return 1
}

appinfo_unlock() {
    rm -rf "$1" 2>/dev/null
}

# ---- 与 LSPosed 模块共用的 appInfo 锁 ---------------------------------------
# 协议（两边必须一致，LSPosed 侧见 SubscreenAppIndex.APP_INFO_LOCK_DIR）：
#   · 锁目录 = appInfo.json 同目录下的 appInfo.json.mibackscreen.lockd
#   · mkdir（不带 -p）成功即持有；删掉目录即释放
#   · 目录已存在且 mtime 超过 30 秒视为陈旧（持有者已死），删掉后重抢
#   · Java 侧最多等 2 秒；shell 侧最多等 APPINFO_SHARED_LOCK_WAIT 秒（默认 30）
#
# shell 侧只在「读指纹 → 改 → mv 提交 → 回读校验」这一段持有，持有时间是
# 一次 awk + 几次 stat/sha256，远小于 30 秒的陈旧判定。
# 锁目录保持为空目录：Java 侧删目录前会先删子项，但空目录对两边都最省事。
APPINFO_SHARED_LOCK_WAIT=${APPINFO_SHARED_LOCK_WAIT:-30}
APPINFO_SHARED_LOCK_STALE=30
APPINFO_SHARED_LOCK_HELD=""

# appinfo_shared_lock <appInfo.json 路径>；成功返回 0，超时 / 目录不存在返回 1
appinfo_shared_lock() {
    _sl_dir="$1.mibackscreen.lockd"
    _sl_parent=${_sl_dir%/*}
    _sl_start=$(date +%s)
    while :; do
        if mkdir "$_sl_dir" 2>/dev/null; then
            APPINFO_SHARED_LOCK_HELD=$_sl_dir
            # 属主 / 上下文跟随所在目录：万一本脚本被 SIGKILL，宿主进程里的
            # Java 侧要能把它当陈旧锁删掉。
            _sl_uid="$(stat -c %u "$_sl_parent" 2>/dev/null)"
            _sl_gid="$(stat -c %g "$_sl_parent" 2>/dev/null)"
            [ -n "$_sl_uid" ] && [ -n "$_sl_gid" ] \
                && chown "$_sl_uid:$_sl_gid" "$_sl_dir" 2>/dev/null
            restorecon "$_sl_dir" 2>/dev/null
            return 0
        fi
        [ -d "$_sl_parent" ] || return 1
        _sl_now=$(date +%s)
        _sl_mtime="$(stat -c %Y "$_sl_dir" 2>/dev/null)"
        if [ -n "$_sl_mtime" ] \
                && [ $((_sl_now - _sl_mtime)) -gt "$APPINFO_SHARED_LOCK_STALE" ]; then
            rm -rf "$_sl_dir" 2>/dev/null
            # 删不掉（权限 / SELinux）就别空转，落到下面的等待与超时
            [ -e "$_sl_dir" ] || continue
        fi
        [ $((_sl_now - _sl_start)) -ge "$APPINFO_SHARED_LOCK_WAIT" ] && return 1
        sleep 0.2 2>/dev/null || sleep 1
    done
}

# 只释放自己持有的锁；没持有时调用是空操作（可以放心写进 trap）
appinfo_shared_unlock() {
    [ -n "$APPINFO_SHARED_LOCK_HELD" ] || return 0
    rm -rf "$APPINFO_SHARED_LOCK_HELD" 2>/dev/null
    APPINFO_SHARED_LOCK_HELD=""
}

# 给 EXIT / 信号 trap 用：释放共用锁，删掉还没提交的临时文件
appinfo_txn_cleanup() {
    [ -n "$APPINFO_ACTIVE_TMP" ] && rm -f "$APPINFO_ACTIVE_TMP" "$APPINFO_ACTIVE_TMP.n" 2>/dev/null
    APPINFO_ACTIVE_TMP=""
    appinfo_shared_unlock
}

# ---- 稳定等待 ---------------------------------------------------------------
# 等到 appInfo.json 连续 <stable_need> 次采样指纹不变（每次间隔 <interval> 秒）。
# echo 1 成功 / 0 超时。注意：这是「尽力而为」，不是正确性依赖 ——
# 正确性由 check-and-rollback 保证，这里只是减少重试。
appinfo_wait_stable() {
    _ws_file="$1"
    _ws_need="${2:-3}"
    _ws_interval="${3:-2}"
    _ws_max="${4:-60}"

    _ws_prev=""
    _ws_hits=0
    _ws_elapsed=0

    while [ "$_ws_elapsed" -lt "$_ws_max" ]; do
        _ws_cur="$(appinfo_fingerprint "$_ws_file" 2>/dev/null)"
        if [ -n "$_ws_cur" ] && [ "$_ws_cur" = "$_ws_prev" ]; then
            _ws_hits=$((_ws_hits + 1))
            [ "$_ws_hits" -ge "$_ws_need" ] && { echo 1; return 0; }
        else
            _ws_hits=0
        fi
        _ws_prev="$_ws_cur"
        sleep "$_ws_interval"
        _ws_elapsed=$((_ws_elapsed + _ws_interval))
    done
    echo 0
    return 1
}
