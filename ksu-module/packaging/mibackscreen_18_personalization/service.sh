#!/system/bin/sh

MODDIR=${0%/*}
MODID=mibackscreen_18_personalization
TAG=MiBackscreenKsu
DATA_ROOT=/data/system/theme_magic/users/0/mibackscreen_18
DATA_PARENT=${DATA_ROOT%/*}
CONFIG_DIR=/data/system/theme_magic/users/0/subscreencenter/config
APPINFO="$CONFIG_DIR/appInfo.json"
ENTRY_DIR="$MODDIR/appcard_entries"
STATE_ROOT=/data/adb/mibackscreen_18_personalization_state
OWNED_IDS="$STATE_ROOT/appcard_owned_ids"
MODIFIED_IDS="$STATE_ROOT/appcard_modified_ids"
ORIGINAL_ENTRIES="$STATE_ROOT/appcard_original_entries"
BASELINE_APPINFO="$STATE_ROOT/appInfo.baseline"
PATCHED_HASH="$STATE_ROOT/appInfo.patched.sha256"
RESOURCE_STAMP="$STATE_ROOT/resources.stamp"
RUNLOG="$STATE_ROOT/service.log"
LOCK_DIR="$STATE_ROOT/lock"
APPINFO_LOCK_DIR="$STATE_ROOT/appinfo.lock"
SYNC_CHANGED=0
HOLD_SERVICE_LOCK=0
HOLD_APPINFO_LOCK=0
APPINFO_TXN_SNAPSHOT=""

# 退出码：0 成功 / 1 失败 / 3 另一个同步实例正在运行（action.sh 据此提示用户）
EXIT_BUSY=3

. "$MODDIR/appinfo_txn.sh" || exit 1
. "$MODDIR/appinfo_utils.sh" || exit 1

logi() {
    log -p i -t "$TAG" "$*"
    mkdir -p "$STATE_ROOT" 2>/dev/null
    chmod 0700 "$STATE_ROOT" 2>/dev/null
    printf '%s %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*" >> "$RUNLOG" 2>/dev/null
    chmod 0600 "$RUNLOG" 2>/dev/null
}

# 统一的退出清理：共用锁、未提交的临时文件、事务快照、模块内部锁。
# 信号也转成 exit，保证被管理器 SIGTERM 时 EXIT trap 照样执行
# （SIGKILL / 关机不走 trap，跨开机的残留锁由 post-fs-data.sh 清掉）。
service_cleanup() {
    appinfo_txn_cleanup
    [ -n "$APPINFO_TXN_SNAPSHOT" ] && rm -f "$APPINFO_TXN_SNAPSHOT"
    rm -f "$STATE_ROOT/appInfo.extract.$$" 2>/dev/null
    [ "$HOLD_APPINFO_LOCK" = "1" ] && appinfo_unlock "$APPINFO_LOCK_DIR"
    [ "$HOLD_SERVICE_LOCK" = "1" ] && rm -rf "$LOCK_DIR"
}
trap service_cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

# 只匹配命令行以包名开头的进程（主进程与 :xxx 子进程），
# 不会误杀命令行里恰好带着包名的 grep / logcat 之类。
kill_host() {
    pkill -f "^$(printf '%s' "$1" | sed 's/\./\\./g')" 2>/dev/null
}

wait_for_boot() {
    wait_count=0
    while [ "$wait_count" -lt 90 ]; do
        [ "$(getprop sys.boot_completed)" = "1" ] && return 0
        wait_count=$((wait_count + 1))
        sleep 2
    done
    return 1
}

# 服务锁：同一时刻只跑一个 service.sh（开机实例 / 「操作」按钮实例）。
# 锁里记 pid；持有者已经死了（被管理器杀掉）就回收。pid 文件缺失时按「仍被持有」
# 处理，跨开机的残留由 post-fs-data.sh 清掉。
acquire_service_lock() {
    if ! mkdir "$LOCK_DIR" 2>/dev/null; then
        lock_owner="$(cat "$LOCK_DIR/pid" 2>/dev/null)"
        case "$lock_owner" in ''|*[!0-9]*) return 1 ;; esac
        kill -0 "$lock_owner" 2>/dev/null && return 1
        logi "reclaiming service lock of dead pid $lock_owner"
        rm -rf "$LOCK_DIR"
        mkdir "$LOCK_DIR" 2>/dev/null || return 1
    fi
    HOLD_SERVICE_LOCK=1
    printf '%s\n' "$$" > "$LOCK_DIR/pid" 2>/dev/null
    return 0
}

appcard_index_ready() {
    [ -d "$ENTRY_DIR" ] || return 1
    [ -s "$APPINFO" ] || return 1
    appinfo_is_array "$APPINFO" || return 1

    for ready_entry in "$ENTRY_DIR"/*.json; do
        [ -f "$ready_entry" ] || continue
        ready_name=${ready_entry##*/}
        ready_id=${ready_name%.json}
        ready_path="$(sed -n 's/.*"mtzPath"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' \
            "$ready_entry" | head -1)"
        appinfo_has_id "$APPINFO" "$ready_id" || return 1
        appinfo_contains_path "$APPINFO" "$ready_path" || return 1
    done
    return 0
}

# 宿主是否已经不再写 appInfo.json。
# 用 appinfo_txn.sh 的指纹（mtime 秒 + 大小 + sha256 前缀）做稳定性判定：
# 指纹连续 stable_need 次不变即视为稳定。
# 注意这是「尽力而为」的收敛加速，不是正确性依赖 —— 正确性由
# appinfo_commit 的 check-and-rollback 保证。
wait_for_appinfo_quiet() {
    appinfo_wait_stable "$APPINFO" 3 2 30 >/dev/null 2>&1 && return 0
    return 1
}

apply_data_metadata() {
    data_target="$1"
    data_uid="$(stat -c %u "$DATA_PARENT" 2>/dev/null)"
    data_gid="$(stat -c %g "$DATA_PARENT" 2>/dev/null)"
    [ -n "$data_uid" ] && [ -n "$data_gid" ] \
        && chown -R "$data_uid:$data_gid" "$data_target" 2>/dev/null
    find "$data_target" -type d -exec chmod 0755 {} \; 2>/dev/null
    find "$data_target" -type f -exec chmod 0644 {} \; 2>/dev/null
    restorecon -RF "$data_target" 2>/dev/null
}

# ---------------------------------------------------------------------------
# 资源同步：没变就跳过复制。
#
# 判据（全部满足才跳过）：
#   1. 状态目录里的 resources.stamp 等于当前签名。签名 = module.prop 的
#      versionCode + 模块内资源清单（相对路径、大小、mtime）的 sha256。
#      升级 / 重装模块时 versionCode 或解包出来的 mtime 会变，签名随之变化。
#   2. data-root 下四个子树的「相对路径 + 大小」清单与模块内对应子树逐行相同
#      （防 data-root 被外部删改；同尺寸的内容篡改不在判据内）。
# 任何一项算不出来都按「不一致」处理，退回整份复制。
# 跳过复制时仍重新摆一遍属主 / 权限 / 上下文（只动元数据，不动内容）。
# ---------------------------------------------------------------------------
RESOURCE_MAP="media/rearscreen/appcard|appcard
precust_theme/theme/.data|precust_theme/theme/.data
media/rearscreen/template/normal|template/normal
media/rearscreen/template/default|template/default"

tree_listing() {
    [ -d "$1" ] || return 1
    ( cd "$1" && find . -type f -exec stat -c '%n %s' {} \; 2>/dev/null ) | LC_ALL=C sort
}

resource_signature() {
    sig_vc="$(sed -n 's/^versionCode=//p' "$MODDIR/module.prop" 2>/dev/null | head -1)"
    sig_list="$( ( cd "$resource_source" && find . -type f -exec stat -c '%n %s %Y' {} \; 2>/dev/null ) \
        | LC_ALL=C sort | sha256sum | awk '{print $1}')"
    [ -n "$sig_vc" ] && [ -n "$sig_list" ] || return 1
    printf 'versionCode=%s source=%s\n' "$sig_vc" "$sig_list"
}

resources_up_to_date() {
    [ -f "$RESOURCE_STAMP" ] && [ -d "$DATA_ROOT" ] || return 1
    want_sig="$(resource_signature)" || return 1
    [ "$(cat "$RESOURCE_STAMP" 2>/dev/null)" = "$want_sig" ] || return 1
    while IFS='|' read -r map_src map_dst; do
        [ -n "$map_src" ] || continue
        list_src="$(tree_listing "$resource_source/$map_src")" || return 1
        list_dst="$(tree_listing "$DATA_ROOT/$map_dst")" || return 1
        [ -n "$list_src" ] && [ "$list_src" = "$list_dst" ] || return 1
    done <<EOF
$RESOURCE_MAP
EOF
    return 0
}

stage_resources() {
    cp -af "$resource_source/media/rearscreen/appcard" "$resource_stage/appcard" || return 1
    mkdir -p "$resource_stage/precust_theme/theme" "$resource_stage/template" || return 1
    cp -af "$resource_source/precust_theme/theme/.data" \
        "$resource_stage/precust_theme/theme/.data" || return 1
    cp -af "$resource_source/media/rearscreen/template/normal" \
        "$resource_stage/template/normal" || return 1
    cp -af "$resource_source/media/rearscreen/template/default" \
        "$resource_stage/template/default" || return 1
    apply_data_metadata "$resource_stage"
    [ -f "$resource_stage/template/normal/rearScreen.json" ] || return 1
    [ -f "$resource_stage/template/default/rearScreen.json" ] || return 1
    return 0
}

sync_data_resources() {
    resource_source="$MODDIR/mibackscreen_resources"
    resource_stage="$DATA_PARENT/.mibackscreen_18.new.$$"
    resource_old="$DATA_PARENT/.mibackscreen_18.old.$$"

    [ -d "$resource_source" ] || return 1
    mkdir -p "$DATA_PARENT" || return 1
    # 以前被杀掉 / 失败留下的暂存与旧目录（名字带 pid，不清就永远没人回收）。
    # 服务锁保证此刻没有别的同步在用它们。
    rm -rf "$DATA_PARENT"/.mibackscreen_18.* 2>/dev/null

    if resources_up_to_date; then
        apply_data_metadata "$DATA_ROOT"
        logi "resource sync skipped: unchanged"
        return 0
    fi
    new_sig="$(resource_signature)"

    mkdir -p "$resource_stage" || return 1
    if ! stage_resources; then
        rm -rf "$resource_stage"
        return 1
    fi

    if [ -e "$DATA_ROOT" ]; then
        if ! mv "$DATA_ROOT" "$resource_old"; then
            rm -rf "$resource_stage"
            return 1
        fi
    fi
    if ! mv "$resource_stage" "$DATA_ROOT"; then
        [ -e "$resource_old" ] && mv "$resource_old" "$DATA_ROOT" 2>/dev/null
        rm -rf "$resource_stage"
        return 1
    fi
    rm -rf "$resource_old" 2>/dev/null
    if [ -n "$new_sig" ] && printf '%s\n' "$new_sig" > "$RESOURCE_STAMP" 2>/dev/null; then
        chmod 0600 "$RESOURCE_STAMP" 2>/dev/null
    else
        rm -f "$RESOURCE_STAMP"
        logi "resource stamp not written, next boot will copy again"
    fi
    logi "resource sync complete"
    return 0
}

remember_owned_id() {
    owned_id="$1"
    mkdir -p "$STATE_ROOT" || return 1
    if ! grep -Fxq "$owned_id" "$OWNED_IDS" 2>/dev/null; then
        printf '%s\n' "$owned_id" >> "$OWNED_IDS" || return 1
    fi
    chmod 0600 "$OWNED_IDS" 2>/dev/null
}

forget_owned_id() {
    [ -f "$OWNED_IDS" ] || return 0
    grep -Fxv "$1" "$OWNED_IDS" > "$OWNED_IDS.tmp.$$" 2>/dev/null
    mv -f "$OWNED_IDS.tmp.$$" "$OWNED_IDS" 2>/dev/null
    chmod 0600 "$OWNED_IDS" 2>/dev/null
}

remember_modified_id() {
    modified_id="$1"
    mkdir -p "$STATE_ROOT" || return 1
    if ! grep -Fxq "$modified_id" "$MODIFIED_IDS" 2>/dev/null; then
        printf '%s\n' "$modified_id" >> "$MODIFIED_IDS" || return 1
    fi
    chmod 0600 "$MODIFIED_IDS" 2>/dev/null
}

# ---------------------------------------------------------------------------
# 一次应用卡事务。
#
# 记账先于提交（write-ahead）：
#   · 替换宿主卡前先存原件、记进 modified_ids，再提交替换。提交失败时这份记录
#     无害（卸载只还原「当前仍指向本模块数据根」的卡）；反过来若先提交后记账、
#     记账又失败，下一轮会把这张宿主卡误当成「自己新增」，卸载时被删掉而不是还原。
#   · 追加新卡前先记进 owned_ids；追加失败就撤掉这条新记录。
#   已提交条目的记账一律不回滚。
#
# 回滚只在「本事务确实提交过」且是硬错误（rc=1）时做：用第一次提交前的快照，
# 经同目录临时文件 + mv 恢复；宿主在我们最后一次提交之后写过就放弃回滚。
# 冲突（rc=2）不回滚：文件已经是宿主的新内容。
# ---------------------------------------------------------------------------
sync_txn_finish() {
    finish_rc="${1:-0}"
    if [ "$finish_rc" = "1" ] && [ "$APPINFO_COMMITS" -gt "$txn_commits_before" ]; then
        appinfo_restore_snapshot "$APPINFO" "$APPINFO_TXN_SNAPSHOT" "$appinfo_last_commit_fp"
        case $? in
            0) logi "app card transaction rolled back" ;;
            2) logi "app card rollback skipped: $APPINFO_ERR" ;;
            *) logi "app card rollback failed: $APPINFO_ERR" ;;
        esac
    fi
    [ -n "$APPINFO_TXN_SNAPSHOT" ] && rm -f "$APPINFO_TXN_SNAPSHOT"
    APPINFO_TXN_SNAPSHOT=""
    rm -f "$STATE_ROOT/appInfo.extract.$$" 2>/dev/null
    return "$finish_rc"
}

sync_appcard_index() {
    [ -d "$ENTRY_DIR" ] || return 1
    [ -s "$APPINFO" ] || return 1
    appinfo_is_array "$APPINFO" || return 1

    APPINFO_TXN_SNAPSHOT="$STATE_ROOT/appInfo.transaction.$$"
    rm -f "$APPINFO_TXN_SNAPSHOT"
    txn_commits_before=$APPINFO_COMMITS
    appinfo_last_commit_fp=""

    # 首次同步前的整份 appInfo，只留作人工排障参考；卸载不再拿它整份覆盖。
    if [ ! -f "$BASELINE_APPINFO" ]; then
        cp -af "$APPINFO" "$BASELINE_APPINFO" 2>/dev/null \
            && chmod 0600 "$BASELINE_APPINFO" 2>/dev/null
    fi

    for entry in "$ENTRY_DIR"/*.json; do
        [ -f "$entry" ] || continue
        entry_name=${entry##*/}
        entry_id=${entry_name%.json}
        entry_path="$(sed -n 's/.*"mtzPath"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$entry" | head -1)"

        if appinfo_has_id "$APPINFO" "$entry_id"; then
            if appinfo_contains_path "$APPINFO" "$entry_path"; then
                if ! grep -Fxq "$entry_id" "$MODIFIED_IDS" 2>/dev/null; then
                    remember_owned_id "$entry_id"
                fi
                continue
            fi

            if ! mkdir -p "$ORIGINAL_ENTRIES"; then
                logi "app card fail: mkdir originals ($entry_id)"
                sync_txn_finish 1
                return 1
            fi
            chmod 0700 "$ORIGINAL_ENTRIES" 2>/dev/null
            original_entry="$ORIGINAL_ENTRIES/$entry_id.json"
            if [ ! -s "$original_entry" ]; then
                if ! appinfo_extract_entry "$APPINFO" "$entry_id" \
                        "$STATE_ROOT/appInfo.extract.$$"; then
                    logi "app card fail: extract original ($entry_id) $APPINFO_ERR"
                    sync_txn_finish 1
                    return 1
                fi
                if ! mv -f "$STATE_ROOT/appInfo.extract.$$" "$original_entry"; then
                    logi "app card fail: store original ($entry_id)"
                    sync_txn_finish 1
                    return 1
                fi
                chmod 0600 "$original_entry" 2>/dev/null
            fi
            if ! remember_modified_id "$entry_id"; then
                logi "app card fail: record modified ($entry_id)"
                sync_txn_finish 1
                return 1
            fi
            appinfo_replace_entry "$APPINFO" "$entry_id" "$entry"
            replace_rc=$?
            if [ "$replace_rc" != "0" ]; then
                logi "app card fail: replace rc=$replace_rc ($entry_id) $APPINFO_ERR"
                sync_txn_finish "$replace_rc"
                return "$replace_rc"
            fi
            SYNC_CHANGED=1
            logi "app card path updated: $entry_id"
            continue
        fi

        newly_owned=0
        grep -Fxq "$entry_id" "$OWNED_IDS" 2>/dev/null || newly_owned=1
        if ! remember_owned_id "$entry_id"; then
            logi "app card fail: record owned ($entry_id)"
            sync_txn_finish 1
            return 1
        fi
        appinfo_append_entry "$APPINFO" "$entry"
        append_rc=$?
        if [ "$append_rc" != "0" ]; then
            # 追加没成：撤掉刚记下的这条。万一其实已写入（提交后被宿主覆盖时
            # 卡仍可能留在宿主重写的版本里），下一轮「已存在且路径是我们的」
            # 分支会重新记账。
            [ "$newly_owned" = "1" ] && forget_owned_id "$entry_id"
            logi "app card fail: append rc=$append_rc ($entry_id) $APPINFO_ERR"
            sync_txn_finish "$append_rc"
            return "$append_rc"
        fi
        SYNC_CHANGED=1
        logi "app card added: $entry_id"
    done

    if [ "$SYNC_CHANGED" = "1" ]; then
        # 只做诊断展示用（status / 诊断脚本里对照），写失败不影响事务
        if sha256sum "$APPINFO" | awk '{print $1}' > "$PATCHED_HASH" 2>/dev/null; then
            chmod 0600 "$PATCHED_HASH" 2>/dev/null
        else
            logi "app card: write patched hash failed (diagnostic only)"
        fi
    fi
    sync_txn_finish 0
}

cleanup_mount_state() {
    rm -rf "$MODDIR/product" "$MODDIR/system" 2>/dev/null
    rm -rf "/data/adb/metamodule/mnt/$MODID" 2>/dev/null
    rm -rf "/data/adb/modules/meta-overlayfs/mnt/$MODID" 2>/dev/null
}

wait_for_boot || logi "boot completion wait timed out"
mkdir -p "$STATE_ROOT" || exit 1
chmod 0700 "$STATE_ROOT" 2>/dev/null
if ! acquire_service_lock; then
    logi "another sync is active"
    exit "$EXIT_BUSY"
fi

logi "service start"
# 上次卸载没清理干净、之后又重新装回了模块：状态记录继续沿用，
# 卸载未完成的标记与手动重试副本已经没有意义。
if [ -e "$STATE_ROOT/UNINSTALL_INCOMPLETE" ] || [ -d "$STATE_ROOT/uninstall_retry" ]; then
    rm -rf "$STATE_ROOT/UNINSTALL_INCOMPLETE" "$STATE_ROOT/uninstall_retry"
    logi "module reinstalled after an incomplete uninstall; state records kept"
fi
cleanup_mount_state
if ! sync_data_resources; then
    logi "resource sync failed"
    exit 1
fi

# ---------------------------------------------------------------------------
# 应用卡索引：与宿主并发写 appInfo.json。
#
# 为什么不能靠「固定次数重试」：
#   冷启动早期宿主（背屏中心/智能助理）持续重写 appInfo.json。固定重试是在
#   一个不断变化的窗口里盲目再试，宿主写得久它就必然耗尽次数然后失败退出。
#
# 改成三段：
#   1) 拿模块级文件锁，保证同一时刻只有本模块一个写者；每次真正改写时另拿
#      与 LSPosed 模块共用的锁（appinfo_utils.sh 内部，持有时间只覆盖提交段）；
#   2) 等索引稳定（指纹连续 3 次不变）—— 尽力而为，只减少重试；
#   3) 收敛循环：每轮先等稳定，再跑一次事务同步。事务内部若检测到宿主插写
#      会返回 2（冲突）并保持文件不动，此时继续下一轮；宿主停手后自然收敛。
#
# 注意：循环里**不能** pkill 宿主。乐观提交已经能正确处理并发，宿主在写就
# 下一轮再试；反过来杀掉背屏中心会（a）让用户看到宿主反复重启，（b）进程
# 重启期间目录/context 不稳定，反而把可恢复的 rc=2 变成 rc=1。
# ---------------------------------------------------------------------------
if appinfo_lock "$APPINFO_LOCK_DIR" 30 >/dev/null; then
    HOLD_APPINFO_LOCK=1
else
    logi "appinfo lock unavailable, proceeding without lock"
fi

appcard_settled=0
if appcard_index_ready; then
    appcard_settled=1
else
    logi "waiting for app card index to settle"
    wait_for_appinfo_quiet || \
        logi "app card index settle wait timed out, will converge with transactions"
fi

appcard_round=1
appcard_max_rounds=12
appcard_conflicts=0
while [ "$appcard_round" -le "$appcard_max_rounds" ]; do
    sync_appcard_index
    sync_rc=$?

    if [ "$sync_rc" = "0" ]; then
        appcard_settled=1
        break
    fi

    if [ "$sync_rc" = "2" ]; then
        appcard_conflicts=$((appcard_conflicts + 1))
        logi "app card transaction conflict: round=$appcard_round host is still writing"
    else
        logi "app card transaction error: round=$appcard_round rc=$sync_rc"
    fi

    # rc=1 与 rc=2 都重试：冷启动早期宿主重写、目录/context 短暂不稳定
    # 都会造成一次性失败，等一等下一轮往往就过了。重试次数用尽才算真失败。
    appcard_round=$((appcard_round + 1))
    [ "$appcard_round" -gt "$appcard_max_rounds" ] && break
    wait_for_appinfo_quiet || true
    sleep 2
done

if [ "$appcard_settled" != "1" ]; then
    logi "app card sync did not converge after $((appcard_round - 1)) rounds"
    exit 1
fi
[ "$appcard_conflicts" -gt 0 ] && logi "app card sync converged after $appcard_conflicts host conflicts"

if [ "$SYNC_CHANGED" = "1" ]; then
    kill_host com.xiaomi.subscreencenter
    kill_host com.android.thememanager
    kill_host com.miui.personalassistant
    logi "host processes restarted"
fi
logi "service finished changed=$SYNC_CHANGED"

# ---------------------------------------------------------------------------
# 开机后复查（守护窗口）。
#
# 为什么需要：
#   宿主（背屏中心 / 智能助理）会在自己进程启动/重启时，用**内存里的清单**
#   整体重写 appInfo.json。我们开机时注入的卡片，只要落在它这次重写之前，
#   就会被它一并抹掉 —— 表现就是「明明注入成功了，过一会儿卡片又没了」。
#
#   实测：宿主重写后，我们 append 的卡（米家摄像机/小米汽车）会被删掉。
#   （它自己认识的卡还在，只是路径被我们改过；这类 repair 结果能存活。）
#
# 做法：
#   开机后在一个有限时间窗内复查若干次 —— 每次都比对「资源是否都在」，
#   缺了就补。窗口有限，不是常驻守护，不会长期耗电。
#   每次同步都走同一套事务保护，宿主正在写就下一轮再说。
#
# 窗口结束时只有「守护窗口里真的补写过」才再重启一次背屏中心；第一阶段的改动
# 上面已经重启过宿主了，不用开机约 4.5 分钟后再重启一遍。
# ---------------------------------------------------------------------------
# MIBS_NO_GUARD=1 时跳过守护窗口 —— 给 action.sh 用。
# 用户点「操作」按钮要的是「立刻同步一次并看结果」，不是等 4 分半。
guard_round=1
guard_max_rounds=6
guard_interval=45
[ "${MIBS_NO_GUARD:-0}" = "1" ] && guard_max_rounds=0
SYNC_CHANGED=0

while [ "$guard_round" -le "$guard_max_rounds" ]; do
    sleep "$guard_interval"
    if appcard_index_ready; then
        guard_round=$((guard_round + 1))
        continue
    fi

    logi "app card guard: index drifted (round=$guard_round), re-syncing"
    guard_inner=1
    while [ "$guard_inner" -le 4 ]; do
        sync_appcard_index
        guard_rc=$?
        if [ "$guard_rc" = "0" ]; then
            logi "app card guard: restored (round=$guard_round)"
            break
        fi
        logi "app card guard: retry rc=$guard_rc (outer=$guard_round inner=$guard_inner)"
        guard_inner=$((guard_inner + 1))
        wait_for_appinfo_quiet || true
        sleep 3
    done

    guard_round=$((guard_round + 1))
done

if [ "$SYNC_CHANGED" = "1" ]; then
    kill_host com.xiaomi.subscreencenter
    logi "app card guard: host restarted to reload index"
fi
[ "$guard_max_rounds" -gt 0 ] && logi "guard window closed"

exit 0
