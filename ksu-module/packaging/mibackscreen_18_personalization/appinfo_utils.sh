#!/system/bin/sh

# ---------------------------------------------------------------------------
# 事务提交：唯一允许写 appInfo.json 的出口。
#
# 与宿主并发写无法用「检查一下」根治 —— 宿主可以在我们 check 完、mv 前的
# 任意一刻写入。所以这里用「乐观提交 + 提交后回读确认」：
#
#   1. 进函数前调用方已取 appinfo_read_fp（读取那一刻的指纹）。
#   2. mv 前再取一次，不等 → 宿主已经改过 → 直接放弃（return 2），
#      文件保持宿主版本，一个字节都没动过。
#   3. mv 之后立刻回读，确认 (a) 内容合法、(b) 我们这次的写入确实在里面、
#      (c) 文件没有被宿主在 mv 后又覆盖。任一不满足 → return 2 让上层重试。
#
# 宿主无论如何都不会被我们静默吞掉：要么我们成功，要么宿主成功且我们重试。
# ---------------------------------------------------------------------------
appinfo_commit() {
    appinfo_commit_tmp="$1"
    appinfo_commit_file="$2"
    appinfo_commit_marker="$3"     # 本次写入应在文件里出现的唯一串（如 resId）

    [ -s "$appinfo_commit_tmp" ] || return 1
    [ -n "$appinfo_read_fp" ] || return 1

    # 先把临时文件的属主/权限/context 摆好（这一步只有 chown/chmod/restorecon，
    # 不碰 appInfo.json），再由下面的紧贴检查守住竞态窗口。
    appinfo_apply_metadata "$appinfo_commit_file" "$appinfo_commit_tmp"

    # 2) 紧贴 mv 之前的最后确认。这条检查必须紧挨 mv —— 中间任何耗时操作
    #    都会把窗口拉大。宿主在「读→改」期间动过文件就放弃，原文件一字未动。
    appinfo_pre_fp="$(appinfo_fingerprint "$appinfo_commit_file")"
    if [ "$appinfo_pre_fp" != "$appinfo_read_fp" ]; then
        rm -f "$appinfo_commit_tmp"
        return 2
    fi

    if ! mv -f "$appinfo_commit_tmp" "$appinfo_commit_file"; then
        rm -f "$appinfo_commit_tmp"
        return 1
    fi
    restorecon "$appinfo_commit_file" 2>/dev/null

    # 3) 提交后回读确认：我们的写入必须真的在文件里。
    #    宿主若在 mv 之后覆盖，这里就会读不到 marker → 判定冲突 → 重试。
    appinfo_is_array "$appinfo_commit_file" || return 1
    if [ -n "$appinfo_commit_marker" ] \
            && ! grep -Fq "$appinfo_commit_marker" "$appinfo_commit_file" 2>/dev/null; then
        return 2
    fi
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

appinfo_append_entry() {
    appinfo_file="$1"
    appinfo_entry="$2"
    appinfo_tmp="$3"

    [ -s "$appinfo_entry" ] || return 1
    appinfo_is_array "$appinfo_file" || return 1
    # 记录读取时的版本，commit 时据它判断是否被宿主插写
    appinfo_read_fp="$(appinfo_fingerprint "$appinfo_file")" || return 1

    awk '
        { data = data $0 "\n" }
        END {
            if (!sub(/[[:space:]]*\][[:space:]]*$/, "", data)) exit 1
            printf "%s", data
        }
    ' "$appinfo_file" > "$appinfo_tmp" || {
        rm -f "$appinfo_tmp"
        return 1
    }

    if ! grep -Eq '^[[:space:]]*\[[[:space:]]*\][[:space:]]*$' "$appinfo_file"; then
        printf ',\n' >> "$appinfo_tmp" || { rm -f "$appinfo_tmp"; return 1; }
    fi
    cat "$appinfo_entry" >> "$appinfo_tmp" || { rm -f "$appinfo_tmp"; return 1; }
    printf '\n]\n' >> "$appinfo_tmp" || { rm -f "$appinfo_tmp"; return 1; }

    # 确认读取之后文件没被宿主改过，再提交
    appinfo_now_fp="$(appinfo_fingerprint "$appinfo_file")"
    if [ "$appinfo_now_fp" != "$appinfo_read_fp" ]; then
        rm -f "$appinfo_tmp"
        return 2                     # 2 = 并发冲突，上层应重试
    fi

    # marker = 本条目的 resId，提交后回读时用它确认写入存活
    appinfo_marker="$(sed -n 's/.*"resId"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' \
        "$appinfo_entry" | head -1)"
    appinfo_commit "$appinfo_tmp" "$appinfo_file" "$appinfo_marker"
}

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
            gsub(/}[[:space:]]*,[[:space:]]*{[[:space:]]*"resId"/, "}\n{\"resId\"", text)
            count = split(text, elements, "\n")
            for (i = 1; i <= count; i++) emit(elements[i])
            if (matched != 1) exit 6
        }
    ' "$appinfo_file" > "$appinfo_output" || {
        rm -f "$appinfo_output"
        return 1
    }
    [ -s "$appinfo_output" ]
}

appinfo_replace_entry() {
    appinfo_file="$1"
    appinfo_id="$2"
    appinfo_entry="$3"
    appinfo_tmp="$4"

    [ -s "$appinfo_entry" ] || return 1
    appinfo_is_array "$appinfo_file" || return 1
    appinfo_read_fp="$(appinfo_fingerprint "$appinfo_file")" || return 1

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
            gsub(/}[[:space:]]*,[[:space:]]*{[[:space:]]*"resId"/, "}\n{\"resId\"", text)
            count = split(text, elements, "\n")
            for (i = 1; i <= count; i++) emit(elements[i])
            printf "\n]\n"
            if (matched != 1) exit 6
        }
    ' "$appinfo_entry" "$appinfo_file" > "$appinfo_tmp" || {
        rm -f "$appinfo_tmp"
        return 1
    }

    appinfo_now_fp="$(appinfo_fingerprint "$appinfo_file")"
    if [ "$appinfo_now_fp" != "$appinfo_read_fp" ]; then
        rm -f "$appinfo_tmp"
        return 2                     # 2 = 并发冲突，上层应重试
    fi

    appinfo_commit "$appinfo_tmp" "$appinfo_file" "$appinfo_id"
}

appinfo_restore_modified() {
    appinfo_file="$1"
    appinfo_modified_ids="$2"
    appinfo_original_dir="$3"
    appinfo_data_prefix="$4"
    appinfo_tmp_base="$5"

    [ -s "$appinfo_modified_ids" ] || return 0
    appinfo_is_array "$appinfo_file" || return 1

    while IFS= read -r appinfo_id; do
        [ -n "$appinfo_id" ] || continue
        appinfo_original="$appinfo_original_dir/$appinfo_id.json"
        [ -s "$appinfo_original" ] || continue

        appinfo_current="$appinfo_tmp_base.current"
        if ! appinfo_extract_entry "$appinfo_file" "$appinfo_id" "$appinfo_current"; then
            rm -f "$appinfo_current"
            continue
        fi
        if appinfo_contains_path "$appinfo_current" "$appinfo_data_prefix"; then
            if ! appinfo_replace_entry "$appinfo_file" "$appinfo_id" \
                    "$appinfo_original" "$appinfo_tmp_base.replace"; then
                rm -f "$appinfo_current" "$appinfo_tmp_base.replace"
                return 1
            fi
        fi
        rm -f "$appinfo_current"
    done < "$appinfo_modified_ids"
    return 0
}

appinfo_remove_owned() {
    appinfo_file="$1"
    appinfo_owned_ids="$2"
    appinfo_data_prefix="$3"
    appinfo_tmp="$4"

    [ -s "$appinfo_owned_ids" ] || return 0
    appinfo_is_array "$appinfo_file" || return 1
    appinfo_read_fp="$(appinfo_fingerprint "$appinfo_file")" || return 1

    appinfo_escaped_prefix="$(printf '%s' "$appinfo_data_prefix" | sed 's#/#\\/#g')"
    awk -v data_prefix="$appinfo_data_prefix" -v escaped_prefix="$appinfo_escaped_prefix" '
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
            gsub(/}[[:space:]]*,[[:space:]]*{[[:space:]]*"resId"/, "}\n{\"resId\"", text)
            count = split(text, elements, "\n")
            for (i = 1; i <= count; i++) emit(elements[i])
            printf "\n]\n"
        }
    ' "$appinfo_owned_ids" "$appinfo_file" > "$appinfo_tmp" || {
        rm -f "$appinfo_tmp"
        return 1
    }

    # 卸载路径同样要防宿主插写；但它是一次性收尾，冲突时直接重读重试一次。
    # 卸载是「移除」语义，没有正向 marker 可断言，故传空。
    appinfo_now_fp="$(appinfo_fingerprint "$appinfo_file")"
    if [ "$appinfo_now_fp" != "$appinfo_read_fp" ]; then
        rm -f "$appinfo_tmp"
        return 2
    fi

    appinfo_commit "$appinfo_tmp" "$appinfo_file" ""
}
