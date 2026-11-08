package hook.HyperBackscreen.ui.recorder

import hook.HyperBackscreen.common.Constants

/**
 * 背屏的两套 display id。
 *
 * [logicalId] 是 DisplayManager 的逻辑 id（`input -d` 用它），[physicalId] 是 SurfaceFlinger 的
 * 物理 id（`screenrecord --display-id` 用它）。物理 id 是 64 位无符号数，按字符串保存，不做数值运算。
 */
internal data class RearDisplayIds(val logicalId: Int, val physicalId: String)

/**
 * 从 `dumpsys display` / `dumpsys SurfaceFlinger --display-id` 的输出里解析背屏 id。纯文本解析，不碰设备。
 *
 * 优先用 `dumpsys display`：每个逻辑 display 的 DisplayInfo 一行里同时有
 * `displayId N` 与 `uniqueId "local:<物理 id>"`，按逻辑 id 1（背屏）直接取。
 * 取不到时退回 SurfaceFlinger 的物理 display 列表：去掉主屏（逻辑 0 对应的物理 id，或
 * 未知时去掉 `HWC display 0`）后只剩唯一一个才采用，否则判失败，绝不猜。
 */
internal object RearDisplayIdParser {
    private val logicalIdPattern = Regex("""(?<![A-Za-z])displayId (\d+)""")
    private val localUniqueIdPattern = Regex("""uniqueId "local:(\d{1,20})"""")
    private val surfaceFlingerPattern = Regex("""^\s*Display (\d{1,20}) \(HWC display (\d+)\)""")

    /** 逻辑 id → 物理 id；只收 `local:` 的内建屏，虚拟屏 / 投屏不算。 */
    fun parseLogicalMapping(dumpsysDisplay: String): Map<Int, String> {
        val mapping = linkedMapOf<Int, String>()
        dumpsysDisplay.lineSequence().forEach { line ->
            if (!line.contains("DisplayInfo{")) return@forEach
            val logical = logicalIdPattern.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: return@forEach
            val physical = localUniqueIdPattern.find(line)?.groupValues?.get(1) ?: return@forEach
            mapping.putIfAbsent(logical, physical)
        }
        return mapping
    }

    /** SurfaceFlinger 的物理 display 列表：物理 id 与 HWC 序号，按输出顺序。 */
    fun parseSurfaceFlingerDisplays(dumpsysSurfaceFlinger: String): List<Pair<String, Int>> =
        dumpsysSurfaceFlinger.lineSequence().mapNotNull { line ->
            val match = surfaceFlingerPattern.find(line) ?: return@mapNotNull null
            val hwc = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
            match.groupValues[1] to hwc
        }.distinctBy { it.first }.toList()

    fun resolve(
        dumpsysDisplay: String,
        dumpsysSurfaceFlinger: String?,
        rearLogicalId: Int = Constants.SYSTEM_SECONDARY_DISPLAY_ID,
    ): RearDisplayIds? {
        val mapping = parseLogicalMapping(dumpsysDisplay)
        mapping[rearLogicalId]?.let { return RearDisplayIds(rearLogicalId, it) }
        if (dumpsysSurfaceFlinger.isNullOrBlank()) return null
        val physical = parseSurfaceFlingerDisplays(dumpsysSurfaceFlinger)
        val primary = mapping[0]
        val candidates = if (primary != null) {
            physical.filter { it.first != primary }
        } else {
            physical.filter { it.second != 0 }
        }
        val rear = candidates.singleOrNull() ?: return null
        return RearDisplayIds(rearLogicalId, rear.first)
    }
}
