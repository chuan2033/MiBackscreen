#!/system/bin/sh
# ============================================================================
# 在宿主 appInfo.json 上做安全的事务性读改写
#
# 背景：宿主（背屏中心 / 智能助理）在开机早期会持续重写 appInfo.json。
# 朴素做法「读→改→mv 覆盖」会在宿主写入的间隙把它的改动静默吞掉，而且
# 脚本自己不知道失败。这里补三件事：
#
#   1. 用「mtime 秒 + 大小 + sha256」当版本指纹 —— 比单看 sha256 更早发现
#      同尺寸原地的改动，也避免 stat 精度问题。
#   2. 事务：改之前取指纹，改之后立刻回读指纹。两者不等 = 宿主在这中间
#      插了写 → 判定失败，回滚，交给上层重试。绝不把「被覆盖」当成功。
#   3. 用文件锁目录做互斥，保证同一时刻只有本模块一个写者。
#
# 锁用 mkdir 实现（原子），不用 flock —— 部分 busybox 不带 flock。
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

# ---- 文件锁 -----------------------------------------------------------------
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
        # 陈旧锁回收：持有者已死，且锁超过 120 秒
        if [ -f "$_lk_dir/pid" ]; then
            _lk_owner="$(cat "$_lk_dir/pid" 2>/dev/null)"
            if [ -n "$_lk_owner" ] && ! kill -0 "$_lk_owner" 2>/dev/null; then
                _lk_age=$(( $(date +%s) - $(stat -c %Y "$_lk_dir" 2>/dev/null || date +%s) ))
                [ "$_lk_age" -gt 120 ] && rm -rf "$_lk_dir" 2>/dev/null && continue
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
