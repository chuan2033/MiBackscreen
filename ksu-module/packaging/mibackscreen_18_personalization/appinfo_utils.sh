#!/system/bin/sh

# 依赖 appinfo_txn.sh（指纹、临时文件、共用锁），调用方须先 source 它。
#
# 返回码约定（所有改写 appInfo.json 的函数一致）：
#   0 成功
#   1 硬错误（格式不认识 / 不变量不符 / 写盘失败），文件未被改动
#   2 并发冲突（宿主插写、共用锁等不到），文件保持宿主版本，上层应重试
# 失败原因写在 APPINFO_ERR 里，调用方记日志用。
APPINFO_ERR=""
APPINFO_COMMITS=0
appinfo_last_commit_fp=""

# ---------------------------------------------------------------------------
# 结构统计：appinfo_json_stats <文件>
# 输出 "<resId 键数> <顶层数组里的对象数> <顶层括号>"，结构不配平返回 1。
#
# 为什么要它：数组元素是用 awk 按 `},{"resId"` 切的（没有真正的 JSON 解析器）。
# 宿主写出的元素若第一个键不是 resId、或嵌套对象以 resId 开头，切分就会把相邻
# 元素粘在一起或从中间切开。这里不求解析 JSON，只做提交前后的不变量校验：
#   · 先按 JSON 规则去掉转义、剥掉字符串，再数 {} [] —— 必须正确嵌套且配平，
#     并且整份文件只有一个顶层值；
#   · 数 "resId" 键与顶层对象数，交给 appinfo_check_delta 比对增量。
# 粘连 / 切错在这两项上一定会露馅（个数对不上或括号不配平），一律判失败不提交。
# ---------------------------------------------------------------------------
appinfo_json_stats() {
    [ -s "$1" ] || return 1
    awk '
        {
            line = $0
            gsub(/\r/, "", line)
            gsub(/\\./, "", line)                 # 转义序列（\" \\ \/ \uXXXX 的前缀）
            q = line
            if (gsub(/"/, "", q) % 2) bad = 1     # JSON 字符串不能跨行
            rid += gsub(/"resId"[[:space:]]*:/, "", line)
            gsub(/"[^"]*"/, "", line)             # 剥掉字符串，剩下的括号才是结构
            gsub(/[^][{}]/, "", line)
            n = length(line)
            for (i = 1; i <= n; i++) {
                c = substr(line, i, 1)
                if (c == "[" || c == "{") {
                    if (done) bad = 1             # 顶层值之后又出现了第二个值
                    if (depth == 0) top = c
                    if (c == "{" && depth == 1 && top == "[") objs++
                    st[++depth] = c
                } else {
                    if (depth == 0) { bad = 1; continue }
                    if ((c == "]") != (st[depth] == "[")) bad = 1
                    depth--
                    if (depth == 0) done = 1
                }
            }
        }
        END {
            if (bad || depth != 0 || !done) exit 1
            printf "%d %d %s\n", rid, objs, top
        }
    ' "$1"
}

# 下面几个 awk 切分元素时用 [{] / [}] 写花括号：裸写的 `{` 在 glibc / musl 的
# regcomp 里会被当成区间量词起点而报 "Unmatched \{"，只有 bionic 的 Spencer
# 正则把它当普通字符。方括号写法在所有实现里含义相同。

# 把 "R O T" 拆到 _js_r / _js_o / _js_t（不 fork）
appinfo_split_stats() {
    _js_r=${1%% *}
    _js_rest=${1#* }
    _js_o=${_js_rest%% *}
    _js_t=${_js_rest#* }
}

# appinfo_check_delta <改前统计> <改后统计> <resId 增量> <对象增量>
appinfo_check_delta() {
    appinfo_split_stats "$1"
    _cd_r=$_js_r; _cd_o=$_js_o; _cd_t=$_js_t
    appinfo_split_stats "$2"
    if [ "$_cd_t" != "[" ] || [ "$_js_t" != "[" ]; then
        APPINFO_ERR="invariant: top-level is not an array"
        return 1
    fi
    if [ "$_js_r" -ne $((_cd_r + $3)) ] || [ "$_js_o" -ne $((_cd_o + $4)) ]; then
        APPINFO_ERR="invariant: resId $_cd_r->$_js_r (want ${3}), objects $_cd_o->$_js_o (want ${4})"
        return 1
    fi
    return 0
}

# ---------------------------------------------------------------------------
# 事务提交：唯一允许写 appInfo.json 的出口。调用方必须已持有共用锁。
#
# 与宿主并发写无法用「检查一下」根治 —— 宿主（不走共用锁的原生写入）可以在
# 我们 check 完、mv 前的任意一刻写入。所以这里用「乐观提交 + 提交后回读确认」：
#
#   1. 进函数前调用方已取 appinfo_read_fp（读取那一刻的指纹）。
#   2. mv 前再取一次，不等 → 宿主已经改过 → 直接放弃（return 2），
#      文件保持宿主版本，一个字节都没动过。
#   3. mv 之后立刻回读内容哈希，必须等于我们校验过的那份临时文件；
#      不等 = 宿主在 mv 后又覆盖了 → return 2 让上层重试。
#
# APPINFO_TXN_SNAPSHOT 非空时，第一次提交前把当时的原文件存一份，供上层回滚。
# ---------------------------------------------------------------------------
appinfo_commit() {
    appinfo_commit_tmp="$1"
    appinfo_commit_file="$2"

    if [ ! -s "$appinfo_commit_tmp" ] || [ -z "$appinfo_read_fp" ] \
            || ! appinfo_is_array "$appinfo_commit_tmp"; then
        rm -f "$appinfo_commit_tmp"
        APPINFO_ERR="commit: temp file invalid"
        return 1
    fi
    appinfo_commit_hash="$(sha256sum "$appinfo_commit_tmp" 2>/dev/null | awk '{print $1}')"
    if [ -z "$appinfo_commit_hash" ]; then
        rm -f "$appinfo_commit_tmp"
        APPINFO_ERR="commit: hash temp failed"
        return 1
    fi

    # 先把临时文件的属主/权限/context 摆好（这一步只有 chown/chmod/restorecon，
    # 不碰 appInfo.json），再由下面的紧贴检查守住竞态窗口。
    appinfo_apply_metadata "$appinfo_commit_file" "$appinfo_commit_tmp"

    # 紧贴 mv 之前的最后确认。宿主在「读→改」期间动过文件就放弃，原文件一字未动。
    appinfo_pre_fp="$(appinfo_fingerprint "$appinfo_commit_file")"
    if [ "$appinfo_pre_fp" != "$appinfo_read_fp" ]; then
        rm -f "$appinfo_commit_tmp"
        APPINFO_ERR="commit: host wrote before mv"
        return 2
    fi

    if [ -n "${APPINFO_TXN_SNAPSHOT:-}" ] && [ ! -e "$APPINFO_TXN_SNAPSHOT" ]; then
        if ! cp -f "$appinfo_commit_file" "$APPINFO_TXN_SNAPSHOT" 2>/dev/null; then
            rm -f "$appinfo_commit_tmp" "$APPINFO_TXN_SNAPSHOT"
            APPINFO_ERR="commit: snapshot failed"
            return 1
        fi
        chmod 0600 "$APPINFO_TXN_SNAPSHOT" 2>/dev/null
    fi

    if ! mv -f "$appinfo_commit_tmp" "$appinfo_commit_file"; then
        rm -f "$appinfo_commit_tmp"
        APPINFO_ERR="commit: mv failed"
        return 1
    fi
    APPINFO_ACTIVE_TMP=""
    APPINFO_COMMITS=$((APPINFO_COMMITS + 1))
    restorecon "$appinfo_commit_file" 2>/dev/null

    # 提交后回读：文件内容必须正是我们校验过的那份
    appinfo_post_hash="$(sha256sum "$appinfo_commit_file" 2>/dev/null | awk '{print $1}')"
    if [ "$appinfo_post_hash" != "$appinfo_commit_hash" ]; then
        appinfo_last_commit_fp=""
        APPINFO_ERR="commit: overwritten after mv"
        return 2
    fi
    appinfo_last_commit_fp="$(appinfo_fingerprint "$appinfo_commit_file")"
    return 0
}

appinfo_has_id() {
    appinfo_file="$1"
    appinfo_id="$2"
    [ -f "$appinfo_file" ] || return 1
    grep -Eq '"resId"[[:space:]]*:[[:space:]]*"'"$appinfo_id"'"' \
        "$appinfo_file" 2>/dev/null
}

appinfo_contains_path() {
    appinfo_contains_file="$1"
    appinfo_contains_value="$2"
    [ -f "$appinfo_contains_file" ] || return 1
    [ -n "$appinfo_contains_value" ] || return 1

    grep -Fq "$appinfo_contains_value" "$appinfo_contains_file" 2>/dev/null && return 0
    appinfo_contains_escaped="$(printf '%s' "$appinfo_contains_value" | sed 's#/#\\/#g')"
    grep -Fq "$appinfo_contains_escaped" "$appinfo_contains_file" 2>/dev/null
}

appinfo_is_array() {
    appinfo_file="$1"
    [ -s "$appinfo_file" ] || return 1
    awk '
        { data = data $0 "\n" }
        END {
            trimmed = data
            gsub(/^[[:space:]]+|[[:space:]]+$/, "", trimmed)
            if (substr(trimmed, 1, 1) != "[" || substr(trimmed, length(trimmed), 1) != "]") {
                exit 1
            }
        }
    ' "$appinfo_file"
}

appinfo_apply_metadata() {
    appinfo_reference="$1"
    appinfo_target="$2"

    appinfo_uid="$(stat -c %u "$appinfo_reference" 2>/dev/null)"
    appinfo_gid="$(stat -c %g "$appinfo_reference" 2>/dev/null)"
    appinfo_mode="$(stat -c %a "$appinfo_reference" 2>/dev/null)"
    [ -n "$appinfo_uid" ] && [ -n "$appinfo_gid" ] \
        && chown "$appinfo_uid:$appinfo_gid" "$appinfo_target" 2>/dev/null
    [ -n "$appinfo_mode" ] && chmod "$appinfo_mode" "$appinfo_target" 2>/dev/null
    restorecon "$appinfo_target" 2>/dev/null
}

# ---------------------------------------------------------------------------
# 带锁的入口：拿共用锁 → 读指纹 → 改 → 提交 → 回读，然后立刻放锁。
# 锁等不到返回 2，交给上层现有的冲突重试逻辑。
# ---------------------------------------------------------------------------
appinfo_locked_call() {
    _lc_file="$1"
    shift
    APPINFO_ERR=""
    if ! appinfo_shared_lock "$_lc_file"; then
        APPINFO_ERR="shared lock unavailable"
        return 2
    fi
    "$@"
    _lc_rc=$?
    appinfo_txn_cleanup
    return "$_lc_rc"
}

# appinfo_append_entry <appInfo.json> <条目 json>
appinfo_append_entry() {
    appinfo_locked_call "$1" _appinfo_append_entry "$@"
}

_appinfo_append_entry() {
    appinfo_file="$1"
    appinfo_entry="$2"

    [ -s "$appinfo_entry" ] || { APPINFO_ERR="append: entry missing"; return 1; }
    appinfo_is_array "$appinfo_file" || { APPINFO_ERR="append: not an array"; return 1; }
    # 记录读取时的版本，commit 时据它判断是否被宿主插写
    appinfo_read_fp="$(appinfo_fingerprint "$appinfo_file")" || return 1
    appinfo_before="$(appinfo_json_stats "$appinfo_file")" \
        || { APPINFO_ERR="append: appInfo structure unbalanced"; return 1; }
    appinfo_entry_stats="$(appinfo_json_stats "$appinfo_entry")" \
        || { APPINFO_ERR="append: entry structure unbalanced"; return 1; }
    appinfo_split_stats "$appinfo_entry_stats"
    if [ "$_js_t" != "{" ] || [ "$_js_r" != "1" ]; then
        APPINFO_ERR="append: entry is not a single object with one resId"
        return 1
    fi

    appinfo_tmp="$(appinfo_tmp_path "$appinfo_file" append)"
    APPINFO_ACTIVE_TMP=$appinfo_tmp
    awk '
        { data = data $0 "\n" }
        END {
            if (!sub(/[[:space:]]*\][[:space:]]*$/, "", data)) exit 1
            printf "%s", data
        }
    ' "$appinfo_file" > "$appinfo_tmp" || {
        rm -f "$appinfo_tmp"
        APPINFO_ERR="append: awk failed"
        return 1
    }

    if ! grep -Eq '^[[:space:]]*\[[[:space:]]*\][[:space:]]*$' "$appinfo_file"; then
        printf ',\n' >> "$appinfo_tmp" || { rm -f "$appinfo_tmp"; return 1; }
    fi
    cat "$appinfo_entry" >> "$appinfo_tmp" || { rm -f "$appinfo_tmp"; return 1; }
    printf '\n]\n' >> "$appinfo_tmp" || { rm -f "$appinfo_tmp"; return 1; }

    # 不变量：resId +1、顶层对象 +1、括号配平
    appinfo_after="$(appinfo_json_stats "$appinfo_tmp")" || {
        rm -f "$appinfo_tmp"
        APPINFO_ERR="append: result structure unbalanced"
        return 1
    }
    appinfo_check_delta "$appinfo_before" "$appinfo_after" 1 1 || {
        rm -f "$appinfo_tmp"
        return 1
    }

    # 确认读取之后文件没被宿主改过，再提交
    appinfo_now_fp="$(appinfo_fingerprint "$appinfo_file")"
    if [ "$appinfo_now_fp" != "$appinfo_read_fp" ]; then
        rm -f "$appinfo_tmp"
        APPINFO_ERR="append: host wrote during edit"
        return 2                     # 2 = 并发冲突，上层应重试
    fi

    appinfo_commit "$appinfo_tmp" "$appinfo_file"
}

# 只读，不拿锁：rename 是原子的，读到的要么是旧版要么是新版
appinfo_extract_entry() {
    appinfo_file="$1"
    appinfo_id="$2"
    appinfo_output="$3"

    appinfo_is_array "$appinfo_file" || return 1

    awk -v target_id="$appinfo_id" '
        { data = data $0 "\n" }

        function trim(value) {
            gsub(/^[[:space:]]+|[[:space:]]+$/, "", value)
            return value
        }

        function emit(value, pattern) {
            value = trim(value)
            if (value == "") return
            pattern = "\\\"resId\\\"[[:space:]]*:[[:space:]]*\\\"" target_id "\\\""
            if (value ~ pattern) {
                matched++
                printf "%s\n", value
            }
        }

        END {
            text = trim(data)
            if (substr(text, 1, 1) != "[" || substr(text, length(text), 1) != "]") exit 2
            text = substr(text, 2, length(text) - 2)
            gsub(/[\r\n]/, "", text)
            gsub(/[}][[:space:]]*,[[:space:]]*[{][[:space:]]*"resId"/, "}\n{\"resId\"", text)
            count = split(text, elements, "\n")
            for (i = 1; i <= count; i++) emit(elements[i])
            if (matched != 1) exit 6
        }
    ' "$appinfo_file" > "$appinfo_output" || {
        rm -f "$appinfo_output"
        return 1
    }
    # 切出来的必须是「一个」配平的对象、且只含一个 resId；
    # 粘连了相邻元素或从嵌套处切开都不算，宁可不动这张卡。
    appinfo_extract_stats="$(appinfo_json_stats "$appinfo_output")" || {
        rm -f "$appinfo_output"
        APPINFO_ERR="extract: element structure unbalanced ($appinfo_id)"
        return 1
    }
    appinfo_split_stats "$appinfo_extract_stats"
    if [ "$_js_t" != "{" ] || [ "$_js_r" != "1" ]; then
        rm -f "$appinfo_output"
        APPINFO_ERR="extract: element is not a single object with one resId ($appinfo_id)"
        return 1
    fi
    [ -s "$appinfo_output" ]
}

# appinfo_replace_entry <appInfo.json> <resId> <新条目 json>
appinfo_replace_entry() {
    appinfo_locked_call "$1" _appinfo_replace_entry "$@"
}

_appinfo_replace_entry() {
    appinfo_file="$1"
    appinfo_id="$2"
    appinfo_entry="$3"

    [ -s "$appinfo_entry" ] || { APPINFO_ERR="replace: entry missing"; return 1; }
    appinfo_is_array "$appinfo_file" || { APPINFO_ERR="replace: not an array"; return 1; }
    appinfo_read_fp="$(appinfo_fingerprint "$appinfo_file")" || return 1
    appinfo_before="$(appinfo_json_stats "$appinfo_file")" \
        || { APPINFO_ERR="replace: appInfo structure unbalanced"; return 1; }
    appinfo_entry_stats="$(appinfo_json_stats "$appinfo_entry")" \
        || { APPINFO_ERR="replace: entry structure unbalanced"; return 1; }
    appinfo_split_stats "$appinfo_entry_stats"
    if [ "$_js_t" != "{" ] || [ "$_js_r" != "1" ]; then
        APPINFO_ERR="replace: entry is not a single object with one resId"
        return 1
    fi

    appinfo_tmp="$(appinfo_tmp_path "$appinfo_file" replace)"
    APPINFO_ACTIVE_TMP=$appinfo_tmp
    awk -v target_id="$appinfo_id" '
        NR == FNR {
            replacement = replacement $0 "\n"
            next
        }
        { data = data $0 "\n" }

        function trim(value) {
            gsub(/^[[:space:]]+|[[:space:]]+$/, "", value)
            return value
        }

        function emit(value, pattern) {
            value = trim(value)
            if (value == "") return
            pattern = "\\\"resId\\\"[[:space:]]*:[[:space:]]*\\\"" target_id "\\\""
            if (kept++) printf ",\n"
            if (value ~ pattern) {
                matched++
                printf "%s", trim(replacement)
            } else {
                printf "%s", value
            }
        }

        END {
            text = trim(data)
            if (substr(text, 1, 1) != "[" || substr(text, length(text), 1) != "]") exit 2
            text = substr(text, 2, length(text) - 2)
            gsub(/[\r\n]/, "", text)

            printf "[\n"
            gsub(/[}][[:space:]]*,[[:space:]]*[{][[:space:]]*"resId"/, "}\n{\"resId\"", text)
            count = split(text, elements, "\n")
            for (i = 1; i <= count; i++) emit(elements[i])
            printf "\n]\n"
            if (matched != 1) exit 6
        }
    ' "$appinfo_entry" "$appinfo_file" > "$appinfo_tmp" || {
        rm -f "$appinfo_tmp"
        APPINFO_ERR="replace: target not found exactly once ($appinfo_id)"
        return 1
    }

    # 不变量：一换一，resId 数与顶层对象数都不变、括号配平
    appinfo_after="$(appinfo_json_stats "$appinfo_tmp")" || {
        rm -f "$appinfo_tmp"
        APPINFO_ERR="replace: result structure unbalanced"
        return 1
    }
    appinfo_check_delta "$appinfo_before" "$appinfo_after" 0 0 || {
        rm -f "$appinfo_tmp"
        return 1
    }

    appinfo_now_fp="$(appinfo_fingerprint "$appinfo_file")"
    if [ "$appinfo_now_fp" != "$appinfo_read_fp" ]; then
        rm -f "$appinfo_tmp"
        APPINFO_ERR="replace: host wrote during edit"
        return 2                     # 2 = 并发冲突，上层应重试
    fi

    appinfo_commit "$appinfo_tmp" "$appinfo_file"
}

# appinfo_restore_modified <appInfo.json> <modified_ids> <原件目录> <数据根前缀>
# 把被本模块改过路径的宿主卡还原成原件。卡已不在索引里 → 无需还原；
# 卡还在、但切不出来或还原失败 → 返回非 0（上层保留状态，别丢原件）。
appinfo_restore_modified() {
    appinfo_rm_file="$1"
    appinfo_modified_ids="$2"
    appinfo_original_dir="$3"
    appinfo_data_prefix="$4"

    [ -s "$appinfo_modified_ids" ] || return 0
    appinfo_is_array "$appinfo_rm_file" || return 1
    appinfo_current="$appinfo_original_dir/.current.$$"

    while IFS= read -r appinfo_rm_id; do
        [ -n "$appinfo_rm_id" ] || continue
        appinfo_original="$appinfo_original_dir/$appinfo_rm_id.json"
        [ -s "$appinfo_original" ] || continue
        appinfo_has_id "$appinfo_rm_file" "$appinfo_rm_id" || continue

        if ! appinfo_extract_entry "$appinfo_rm_file" "$appinfo_rm_id" "$appinfo_current"; then
            rm -f "$appinfo_current"
            [ -n "$APPINFO_ERR" ] || APPINFO_ERR="restore: extract failed ($appinfo_rm_id)"
            return 1
        fi
        if appinfo_contains_path "$appinfo_current" "$appinfo_data_prefix"; then
            appinfo_replace_entry "$appinfo_rm_file" "$appinfo_rm_id" "$appinfo_original"
            appinfo_rm_rc=$?
            if [ "$appinfo_rm_rc" != "0" ]; then
                rm -f "$appinfo_current"
                return "$appinfo_rm_rc"
            fi
        fi
        rm -f "$appinfo_current"
    done < "$appinfo_modified_ids"
    return 0
}

# appinfo_remove_owned <appInfo.json> <owned_ids> <数据根前缀>
# 删掉本模块自己新增、且仍指向本模块数据根的卡。
appinfo_remove_owned() {
    appinfo_locked_call "$1" _appinfo_remove_owned "$@"
}

_appinfo_remove_owned() {
    appinfo_file="$1"
    appinfo_owned_ids="$2"
    appinfo_data_prefix="$3"

    [ -s "$appinfo_owned_ids" ] || return 0
    appinfo_is_array "$appinfo_file" || { APPINFO_ERR="remove: not an array"; return 1; }
    appinfo_read_fp="$(appinfo_fingerprint "$appinfo_file")" || return 1
    appinfo_before="$(appinfo_json_stats "$appinfo_file")" \
        || { APPINFO_ERR="remove: appInfo structure unbalanced"; return 1; }

    appinfo_tmp="$(appinfo_tmp_path "$appinfo_file" remove)"
    APPINFO_ACTIVE_TMP=$appinfo_tmp
    # 宿主 / LSPosed 用 Java 的 JSONArray.toString() 写文件，路径里的 / 会写成 \/。
    # 转义形式的前缀必须经环境变量传给 awk：-v 赋值会按字符串字面量处理转义，
    # "\/" 进去就变回 "/"，转义形式的卡永远匹配不上、卸载时删不掉。
    appinfo_escaped_prefix="$(printf '%s' "$appinfo_data_prefix" | sed 's#/#\\/#g')"
    APPINFO_ESCAPED_PREFIX=$appinfo_escaped_prefix \
    awk -v data_prefix="$appinfo_data_prefix" -v count_file="$appinfo_tmp.n" '
        BEGIN { escaped_prefix = ENVIRON["APPINFO_ESCAPED_PREFIX"] }
        NR == FNR {
            gsub(/^[[:space:]]+|[[:space:]]+$/, "", $0)
            if ($0 != "") owned[$0] = 1
            next
        }
        { data = data $0 "\n" }

        function trim(value) {
            gsub(/^[[:space:]]+|[[:space:]]+$/, "", value)
            return value
        }

        function emit(value, id, pattern) {
            value = trim(value)
            if (value == "") return
            for (id in owned) {
                pattern = "\\\"resId\\\"[[:space:]]*:[[:space:]]*\\\"" id "\\\""
                if (value ~ pattern && (index(value, data_prefix) > 0 || index(value, escaped_prefix) > 0)) {
                    removed++
                    return
                }
            }
            if (kept++) printf ",\n"
            printf "%s", value
        }

        END {
            text = trim(data)
            if (substr(text, 1, 1) != "[" || substr(text, length(text), 1) != "]") exit 2
            text = substr(text, 2, length(text) - 2)
            gsub(/[\r\n]/, "", text)

            printf "[\n"
            gsub(/[}][[:space:]]*,[[:space:]]*[{][[:space:]]*"resId"/, "}\n{\"resId\"", text)
            count = split(text, elements, "\n")
            for (i = 1; i <= count; i++) emit(elements[i])
            printf "\n]\n"
            printf "%d\n", removed > count_file
        }
    ' "$appinfo_owned_ids" "$appinfo_file" > "$appinfo_tmp" || {
        rm -f "$appinfo_tmp" "$appinfo_tmp.n"
        APPINFO_ERR="remove: awk failed"
        return 1
    }
    appinfo_removed="$(cat "$appinfo_tmp.n" 2>/dev/null)"
    rm -f "$appinfo_tmp.n"
    case "$appinfo_removed" in
        ''|*[!0-9]*)
            rm -f "$appinfo_tmp"
            APPINFO_ERR="remove: removed count unreadable"
            return 1
            ;;
    esac
    if [ "$appinfo_removed" = "0" ]; then
        # 一张都不用删：不重写文件，免得白白触发宿主的文件监听
        rm -f "$appinfo_tmp"
        return 0
    fi

    # 不变量：删了 N 个元素，resId 与顶层对象都必须正好少 N。
    # 若某个被删的「元素」其实粘了相邻的卡，这里个数对不上，整次判失败。
    appinfo_after="$(appinfo_json_stats "$appinfo_tmp")" || {
        rm -f "$appinfo_tmp"
        APPINFO_ERR="remove: result structure unbalanced"
        return 1
    }
    appinfo_check_delta "$appinfo_before" "$appinfo_after" \
        "-$appinfo_removed" "-$appinfo_removed" || {
        rm -f "$appinfo_tmp"
        return 1
    }

    # 卸载路径同样要防宿主插写；冲突时上层重读重试。
    appinfo_now_fp="$(appinfo_fingerprint "$appinfo_file")"
    if [ "$appinfo_now_fp" != "$appinfo_read_fp" ]; then
        rm -f "$appinfo_tmp"
        APPINFO_ERR="remove: host wrote during edit"
        return 2
    fi

    appinfo_commit "$appinfo_tmp" "$appinfo_file"
}

# appinfo_restore_snapshot <appInfo.json> <快照> <期望指纹>
# 事务回滚：只有当前文件仍是「我们最后一次提交」的那份（期望指纹）才回滚，
# 宿主在那之后写过就放弃（返回 2），绝不覆盖宿主的新内容。
# 走同目录临时文件 + mv，和正常提交一样原子。
appinfo_restore_snapshot() {
    appinfo_locked_call "$1" _appinfo_restore_snapshot "$@"
}

_appinfo_restore_snapshot() {
    appinfo_file="$1"
    appinfo_snapshot="$2"
    appinfo_expect_fp="$3"

    [ -s "$appinfo_snapshot" ] || { APPINFO_ERR="rollback: snapshot missing"; return 1; }
    [ -n "$appinfo_expect_fp" ] || { APPINFO_ERR="rollback: no commit fingerprint"; return 2; }
    appinfo_read_fp="$(appinfo_fingerprint "$appinfo_file")" || return 1
    if [ "$appinfo_read_fp" != "$appinfo_expect_fp" ]; then
        APPINFO_ERR="rollback: host wrote after our commit"
        return 2
    fi
    appinfo_json_stats "$appinfo_snapshot" >/dev/null \
        || { APPINFO_ERR="rollback: snapshot structure unbalanced"; return 1; }

    appinfo_tmp="$(appinfo_tmp_path "$appinfo_file" rollback)"
    APPINFO_ACTIVE_TMP=$appinfo_tmp
    cp -f "$appinfo_snapshot" "$appinfo_tmp" 2>/dev/null || {
        rm -f "$appinfo_tmp"
        APPINFO_ERR="rollback: copy failed"
        return 1
    }
    appinfo_commit "$appinfo_tmp" "$appinfo_file"
}
