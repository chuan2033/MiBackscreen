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
RUNLOG="$STATE_ROOT/service.log"
LOCK_DIR="$STATE_ROOT/lock"
APPINFO_LOCK_DIR="$STATE_ROOT/appinfo.lock"
SYNC_CHANGED=0

. "$MODDIR/appinfo_txn.sh" || exit 1
. "$MODDIR/appinfo_utils.sh" || exit 1

logi() {
    log -p i -t "$TAG" "$*"
    mkdir -p "$STATE_ROOT" 2>/dev/null
    chmod 0700 "$STATE_ROOT" 2>/dev/null
    printf '%s %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*" >> "$RUNLOG" 2>/dev/null
    chmod 0600 "$RUNLOG" 2>/dev/null
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
    settle_round=0
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

sync_data_resources() {
    resource_source="$MODDIR/mibackscreen_resources"
    resource_stage="$DATA_PARENT/.mibackscreen_18.new.$$"
    resource_old="$DATA_PARENT/.mibackscreen_18.old.$$"

    [ -d "$resource_source" ] || return 1
    mkdir -p "$DATA_PARENT" || return 1
    rm -rf "$resource_stage" "$resource_old" 2>/dev/null
    mkdir -p "$resource_stage" || return 1

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

    if [ -e "$DATA_ROOT" ]; then
        mv "$DATA_ROOT" "$resource_old" || return 1
    fi
    if ! mv "$resource_stage" "$DATA_ROOT"; then
        [ -e "$resource_old" ] && mv "$resource_old" "$DATA_ROOT" 2>/dev/null
        return 1
    fi
    rm -rf "$resource_old" 2>/dev/null
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

remember_modified_id() {
    modified_id="$1"
    mkdir -p "$STATE_ROOT" || return 1
    if ! grep -Fxq "$modified_id" "$MODIFIED_IDS" 2>/dev/null; then
        printf '%s\n' "$modified_id" >> "$MODIFIED_IDS" || return 1
    fi
    chmod 0600 "$MODIFIED_IDS" 2>/dev/null
}

sync_appcard_index() {
    [ -d "$ENTRY_DIR" ] || return 1
    [ -s "$APPINFO" ] || return 1
    appinfo_is_array "$APPINFO" || return 1

    transaction_backup="$STATE_ROOT/appInfo.transaction.$$"
    owned_backup="$STATE_ROOT/appcard_owned_ids.transaction.$$"
    modified_backup="$STATE_ROOT/appcard_modified_ids.transaction.$$"
    originals_backup="$STATE_ROOT/appcard_original_entries.transaction.$$"
    # 同一进程内 $$ 不变，这些备份路径每轮相同。上一轮若异常退出留下残骸，
    # cp -af 会把它当成已存在的目标而嵌套复制（dst/dir/...），后续全部错位。
    # 所以先清干净再复制。
    rm -rf "$transaction_backup" "$owned_backup" "$modified_backup" "$originals_backup"
    cp -af "$APPINFO" "$transaction_backup" || return 1
    if [ -f "$OWNED_IDS" ]; then
        cp -af "$OWNED_IDS" "$owned_backup" || return 1
        had_owned_ids=1
    else
        had_owned_ids=0
    fi
    if [ -f "$MODIFIED_IDS" ]; then
        cp -af "$MODIFIED_IDS" "$modified_backup" || return 1
        had_modified_ids=1
    else
        had_modified_ids=0
    fi
    if [ -d "$ORIGINAL_ENTRIES" ]; then
        cp -af "$ORIGINAL_ENTRIES" "$originals_backup" || return 1
        had_original_entries=1
    else
        had_original_entries=0
    fi
    if [ ! -f "$BASELINE_APPINFO" ]; then
        cp -af "$APPINFO" "$BASELINE_APPINFO" || return 1
        chmod 0600 "$BASELINE_APPINFO" 2>/dev/null
        baseline_created=1
    else
        baseline_created=0
    fi

    rollback_appinfo_transaction() {
        # 只回滚「我们写坏」的情况。冲突（rc=2）时 appInfo.json 根本没动过，
        # 回滚反而会把宿主刚写的内容盖掉，所以冲突时跳过 appInfo 回写。
        if [ "${1:-0}" != "2" ]; then
            cp -af "$transaction_backup" "$APPINFO" 2>/dev/null
            restorecon "$APPINFO" 2>/dev/null
        fi
        if [ "$had_owned_ids" = "1" ]; then
            cp -af "$owned_backup" "$OWNED_IDS" 2>/dev/null
        else
            rm -f "$OWNED_IDS"
        fi
        if [ "$had_modified_ids" = "1" ]; then
            cp -af "$modified_backup" "$MODIFIED_IDS" 2>/dev/null
        else
            rm -f "$MODIFIED_IDS"
        fi
        rm -rf "$ORIGINAL_ENTRIES"
        if [ "$had_original_entries" = "1" ]; then
            cp -af "$originals_backup" "$ORIGINAL_ENTRIES" 2>/dev/null
        fi
        [ "$baseline_created" = "1" ] && rm -f "$BASELINE_APPINFO"
        rm -rf "$transaction_backup" "$owned_backup" "$modified_backup" \
            "$originals_backup" "$STATE_ROOT/appInfo.append.$$" \
            "$STATE_ROOT/appInfo.extract.$$" "$STATE_ROOT/appInfo.replace.$$"
    }

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

            mkdir -p "$ORIGINAL_ENTRIES" || {
                logi "app card fail: mkdir originals ($entry_id)"
                rollback_appinfo_transaction
                return 1
            }
            chmod 0700 "$ORIGINAL_ENTRIES" 2>/dev/null
            original_entry="$ORIGINAL_ENTRIES/$entry_id.json"
            if [ ! -s "$original_entry" ]; then
                if ! appinfo_extract_entry "$APPINFO" "$entry_id" \
                        "$STATE_ROOT/appInfo.extract.$$"; then
                    logi "app card fail: extract original ($entry_id)"
                    rollback_appinfo_transaction
                    return 1
                fi
                mv -f "$STATE_ROOT/appInfo.extract.$$" "$original_entry" || {
                    logi "app card fail: store original ($entry_id)"
                    rollback_appinfo_transaction
                    return 1
                }
                chmod 0600 "$original_entry" 2>/dev/null
            fi
            appinfo_replace_entry "$APPINFO" "$entry_id" "$entry" \
                "$STATE_ROOT/appInfo.replace.$$"
            replace_rc=$?
            if [ "$replace_rc" != "0" ]; then
                logi "app card fail: replace rc=$replace_rc ($entry_id)"
                rollback_appinfo_transaction "$replace_rc"
                return "$replace_rc"
            fi
            if ! remember_modified_id "$entry_id"; then
                logi "app card fail: record modified ($entry_id)"
                rollback_appinfo_transaction
                return 1
            fi
            SYNC_CHANGED=1
            logi "app card path updated: $entry_id"
            continue
        fi

        append_tmp="$STATE_ROOT/appInfo.append.$$"
        appinfo_append_entry "$APPINFO" "$entry" "$append_tmp"
        append_rc=$?
        if [ "$append_rc" != "0" ]; then
            logi "app card fail: append rc=$append_rc ($entry_id)"
            rollback_appinfo_transaction "$append_rc"
            return "$append_rc"
        fi
        if ! remember_owned_id "$entry_id"; then
            logi "app card fail: record owned ($entry_id)"
            rollback_appinfo_transaction
            return 1
        fi
        SYNC_CHANGED=1
        logi "app card added: $entry_id"
    done

    if [ "$SYNC_CHANGED" = "1" ]; then
        sha256sum "$APPINFO" | awk '{print $1}' > "$PATCHED_HASH" || {
            logi "app card fail: write patched hash"
            rollback_appinfo_transaction
            return 1
        }
        chmod 0600 "$PATCHED_HASH" 2>/dev/null
    fi
    rm -rf "$transaction_backup" "$owned_backup" "$modified_backup" "$originals_backup"
    return 0
}

cleanup_mount_state() {
    rm -rf "$MODDIR/product" "$MODDIR/system" 2>/dev/null
    rm -rf "/data/adb/metamodule/mnt/$MODID" 2>/dev/null
    rm -rf "/data/adb/modules/meta-overlayfs/mnt/$MODID" 2>/dev/null
}

wait_for_boot || logi "boot completion wait timed out"
mkdir -p "$STATE_ROOT" || exit 1
chmod 0700 "$STATE_ROOT" 2>/dev/null
if ! mkdir "$LOCK_DIR" 2>/dev/null; then
    logi "another sync is active"
    exit 0
fi
trap 'rmdir "$LOCK_DIR" 2>/dev/null' EXIT

logi "service start"
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
#   1) 拿模块级文件锁，保证同一时刻只有本模块一个写者；
#   2) 等索引稳定（指纹连续 3 次不变）—— 尽力而为，只减少重试；
#   3) 收敛循环：每轮先等稳定，再跑一次事务同步。事务内部若检测到宿主插写
#      会返回 2（冲突）并保持文件不动，此时继续下一轮；宿主停手后自然收敛。
#
# 注意：循环里**不能** pkill 宿主。乐观提交已经能正确处理并发，宿主在写就
# 下一轮再试；反过来杀掉背屏中心会（a）让用户看到宿主反复重启，（b）进程
# 重启期间目录/context 不稳定，反而把可恢复的 rc=2 变成 rc=1。
# ---------------------------------------------------------------------------
if ! appinfo_lock "$APPINFO_LOCK_DIR" 30 >/dev/null; then
    logi "appinfo lock unavailable, proceeding without lock"
fi
trap 'rmdir "$LOCK_DIR" 2>/dev/null; appinfo_unlock "$APPINFO_LOCK_DIR"' EXIT

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
    pkill -f com.xiaomi.subscreencenter 2>/dev/null
    pkill -f com.android.thememanager 2>/dev/null
    pkill -f com.miui.personalassistant 2>/dev/null
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
# ---------------------------------------------------------------------------
# MIBS_NO_GUARD=1 时跳过守护窗口 —— 给 action.sh 用。
# 用户点「操作」按钮要的是「立刻同步一次并看结果」，不是等 4 分半。
guard_round=1
guard_max_rounds=6
guard_interval=45
[ "${MIBS_NO_GUARD:-0}" = "1" ] && guard_max_rounds=0

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
    pkill -f com.xiaomi.subscreencenter 2>/dev/null
    logi "app card guard: host restarted to reload index"
fi
logi "guard window closed"

rmdir "$LOCK_DIR" 2>/dev/null
exit 0
