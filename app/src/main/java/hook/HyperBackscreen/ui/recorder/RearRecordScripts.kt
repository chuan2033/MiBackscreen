package hook.HyperBackscreen.ui.recorder

/**
 * 录屏用到的 root shell 脚本（纯字符串拼装，便于单元测试）。脚本经 stdin 交给 su，
 * 里面只出现解析出来的数字 id 与固定路径，不含任何用户输入。
 */
internal object RearRecordScripts {
    /** 录制上限；与 screenrecord 自身的 --time-limit 上限（180 秒）一致，两边谁先到都会停。 */
    const val MAX_DURATION_SECONDS = 180L
    const val WAKE_INTERVAL_SECONDS = 5
    const val TEMP_FILE = "/data/local/tmp/mibackscreen_rear_record.mp4"

    const val UID_MARKER = "__MIBS_UID__"
    const val DISPLAY_MARKER = "__MIBS_DISPLAY__"
    const val SURFACE_FLINGER_MARKER = "__MIBS_SURFACEFLINGER__"
    val RESOLVE = """echo "$UID_MARKER=${'$'}(id -u)"
echo "$DISPLAY_MARKER"
dumpsys display 2>/dev/null | grep -F 'DisplayInfo{'
echo "$SURFACE_FLINGER_MARKER"
dumpsys SurfaceFlinger --display-id 2>/dev/null
"""

    /** 录屏脚本。id 都来自解析结果（纯数字），这里再校验一次，防止任何意外字符进入脚本。 */
    fun record(ids: RearDisplayIds): String {
        require(ids.physicalId.matches(Regex("\\d{1,20}"))) { "bad physical id" }
        val wake = "input -d ${ids.logicalId} keyevent KEYCODE_WAKEUP >/dev/null 2>&1"
        return buildString {
            appendLine("rm -f $TEMP_FILE")
            appendLine(wake)
            appendLine("sleep 1")
            appendLine("echo $$")
            // 后台保活：screenrecord（exec 后与本 shell 同 pid）还在就每隔几秒唤醒一次背屏。
            appendLine(
                "( while sleep $WAKE_INTERVAL_SECONDS && kill -0 $$ 2>/dev/null; do $wake; done ) " +
                        "</dev/null >/dev/null 2>&1 &"
            )
            appendLine(
                "exec screenrecord --display-id ${ids.physicalId} " +
                        "--time-limit $MAX_DURATION_SECONDS $TEMP_FILE"
            )
        }
    }

    fun signal(pid: Int, signal: String): String =
        "[ \"$(cat /proc/$pid/comm 2>/dev/null)\" = screenrecord ] && kill -$signal $pid\n"
}
