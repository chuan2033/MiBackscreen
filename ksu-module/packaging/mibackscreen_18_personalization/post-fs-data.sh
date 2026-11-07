#!/system/bin/sh
# ============================================================================
# 开机极早期（post-fs-data 阶段）
#
# 这里**只搬资源**，不碰 appInfo.json。
#
# 为什么不在这里写 appInfo.json：
#   post-fs-data 阶段 /data 刚挂上，宿主（背屏中心/智能助理）还没起来，
#   此时写入既没有并发保护的意义（宿主还没开始写），又会在宿主启动后
#   被它按自己的认知覆盖掉。应用卡索引的注入仍然交给 service.sh
#   （late_start 阶段，宿主起来了、有事务保护）。
#
# 这里搬资源的价值：万一 service.sh 因为「装完模块没重启」被跳过，
# 至少个性化资源（壁纸/模板/应用卡图标）已经就位；且把这一步提前，
# 能缩短开机后卡片可用的等待时间。
#
# 注意：本脚本里不能 source service.sh —— 那个文件的顶层语句会直接
# 执行同步流程（含 appInfo 写入），不是纯函数库。
# ============================================================================

MODDIR=${0%/*}
MODID=mibackscreen_18_personalization
DATA_ROOT=/data/system/theme_magic/users/0/mibackscreen_18
DATA_PARENT=${DATA_ROOT%/*}
STATE_ROOT=/data/adb/mibackscreen_18_personalization_state
RUNLOG="$STATE_ROOT/post-fs-data.log"

logi() {
    mkdir -p "$STATE_ROOT" 2>/dev/null
    chmod 0700 "$STATE_ROOT" 2>/dev/null
    printf '%s %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*" >> "$RUNLOG" 2>/dev/null
    chmod 0600 "$RUNLOG" 2>/dev/null
}

# 资源已经在位就跳过 —— 避免每次开机都全量重搬（68MB，很慢且伤闪存）。
# 用模板文件当探针：它们是资源的必要组成部分，缺了就是没搬完。
if [ -f "$DATA_ROOT/template/normal/rearScreen.json" ] \
        && [ -f "$DATA_ROOT/template/default/rearScreen.json" ] \
        && [ -d "$DATA_ROOT/appcard" ]; then
    logi "post-fs-data: resources already in place, skip"
    exit 0
fi

resource_source="$MODDIR/mibackscreen_resources"
if [ ! -d "$resource_source" ]; then
    logi "post-fs-data: resource source missing"
    exit 0
fi

resource_stage="$DATA_PARENT/.mibackscreen_18.pfs.$$"
resource_old="$DATA_PARENT/.mibackscreen_18.pfsold.$$"

logi "post-fs-data: staging resources"
mkdir -p "$DATA_PARENT" || exit 0
rm -rf "$resource_stage" "$resource_old" 2>/dev/null
mkdir -p "$resource_stage" || exit 0

cp -af "$resource_source/media/rearscreen/appcard" "$resource_stage/appcard" || {
    logi "post-fs-data: copy appcard failed"
    rm -rf "$resource_stage"
    exit 0
}
mkdir -p "$resource_stage/precust_theme/theme" "$resource_stage/template" || exit 0
cp -af "$resource_source/precust_theme/theme/.data" \
    "$resource_stage/precust_theme/theme/.data" || {
    logi "post-fs-data: copy precust_theme failed"
    rm -rf "$resource_stage"
    exit 0
}
cp -af "$resource_source/media/rearscreen/template/normal" \
    "$resource_stage/template/normal" || {
    logi "post-fs-data: copy template/normal failed"
    rm -rf "$resource_stage"
    exit 0
}
cp -af "$resource_source/media/rearscreen/template/default" \
    "$resource_stage/template/default" || {
    logi "post-fs-data: copy template/default failed"
    rm -rf "$resource_stage"
    exit 0
}

# 属主/权限/上下文跟随父目录，否则宿主（u0_a212 等）读不到
data_uid="$(stat -c %u "$DATA_PARENT" 2>/dev/null)"
data_gid="$(stat -c %g "$DATA_PARENT" 2>/dev/null)"
[ -n "$data_uid" ] && [ -n "$data_gid" ] \
    && chown -R "$data_uid:$data_gid" "$resource_stage" 2>/dev/null
find "$resource_stage" -type d -exec chmod 0755 {} \; 2>/dev/null
find "$resource_stage" -type f -exec chmod 0644 {} \; 2>/dev/null
restorecon -RF "$resource_stage" 2>/dev/null

[ -f "$resource_stage/template/normal/rearScreen.json" ] || {
    logi "post-fs-data: staged content incomplete"
    rm -rf "$resource_stage"
    exit 0
}

# 原子替换：先把旧的挪走，再改名，最后删旧。中途失败把旧的原样放回。
if [ -e "$DATA_ROOT" ]; then
    mv "$DATA_ROOT" "$resource_old" || {
        logi "post-fs-data: move old aside failed"
        rm -rf "$resource_stage"
        exit 0
    }
fi
if ! mv "$resource_stage" "$DATA_ROOT"; then
    logi "post-fs-data: activate staged failed"
    [ -e "$resource_old" ] && mv "$resource_old" "$DATA_ROOT" 2>/dev/null
    rm -rf "$resource_stage"
    exit 0
fi
rm -rf "$resource_old" 2>/dev/null

logi "post-fs-data: resources staged"
exit 0
