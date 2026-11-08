package hook.HyperBackscreen.lyric

/**
 * 背屏实时歌词的纯逻辑：选哪个 SuperLyric 服务、会话匹配、什么时候把歌词写进音乐卡片、文本整形。
 * 不依赖 Android / 宿主类，单元测试直接覆盖。
 */

/**
 * SuperLyric 两代服务名。4.x 优先。
 *
 * - 4.1 Release 只提供 3.x 协议（`super_lyric`）；
 * - 4.2 起两代都提供，并在 system_server 里按调用方裁剪：系统应用（背屏中心）两代都给，第三方应用（模块 App）
 *   只给清单 meta-data `superlyricapi_version_code` 对应的那一代（4.0 库自带 40 → 只有 v4）。
 *   另外 4.2 对没有该 meta-data 的 3.x 接收方只推当前句、不推全量歌词，背屏中心走 3.x 就没有时间轴，所以必须优先 v4。
 */
internal object SuperLyricServices {
    const val V4 = "super_lyric.v4"
    const val LEGACY = "super_lyric"
}

/** 选中的订阅后端。两个后端只能选一个，不能同时注册（v4 拒绝时也不能退到 3.x 重复注册）。 */
internal enum class SuperLyricBackend {
    /** `super_lyric.v4`：用 SuperLyricApi 4.0 的 `SuperLyric.receiver()`。 */
    V4,

    /**
     * `super_lyric`：直连 3.x 冻结 ABI（`ISuperLyricManager.registerReceiver`）。
     * 不能用 4.0 库自带的旧协议适配：它遇到不带全量歌词的 3.x 单行包只生成一帧空 STATE，回调直接丢掉。
     */
    LEGACY,

    /** 两个服务都拿不到：SuperLyric 没装、没启用，或 system 作用域没生效 / 更新后还没重启。 */
    NONE;

    companion object {
        /** [lookup] 按服务名查 Binder（`ServiceManager.getService`），查不到返回 null。 */
        fun select(lookup: (String) -> Any?): SuperLyricBackend = when {
            runCatching { lookup(SuperLyricServices.V4) }.getOrNull() != null -> V4
            runCatching { lookup(SuperLyricServices.LEGACY) }.getOrNull() != null -> LEGACY
            else -> NONE
        }
    }
}

/** 一段歌词会话：发布方包名 + 歌词 id，两者都可能缺失。 */
internal data class LyricSession(
    val publisher: String?,
    val lyricId: String?,
) {
    /**
     * 收到 Stop 时是否结束这段会话。缺失的标识当作「未知」，而不是「去停掉另一段已知会话」：
     * 发布方都已知且不同 → 不是我；id 都已知且不同 → 不是我；其余都算。
     */
    fun acceptsStop(stop: LyricSession): Boolean {
        val mine = publisher?.takeIf { it.isNotBlank() }
        val theirs = stop.publisher?.takeIf { it.isNotBlank() }
        if (mine != null && theirs != null && mine != theirs) return false
        val myId = lyricId?.takeIf { it.isNotBlank() }
        val theirId = stop.lyricId?.takeIf { it.isNotBlank() }
        return myId == null || theirId == null || myId == theirId
    }
}

/** 带时间戳的一句歌词（毫秒）。 */
internal data class TimedLine(val text: String?, val start: Long, val end: Long)

/**
 * 一首歌的完整时间轴。SuperLyric 发布方有时只推一次全量歌词、之后不再逐句推送（例如 QQ 音乐冷启动后的
 * 第一首），这时背屏按播放进度自己从时间轴里取当前句，和状态栏歌词的本地时间轴一致。
 */
internal class LyricTimeline private constructor(
    val lines: List<TimedLine>,
    /** 全量歌词所属的歌名；和卡片正在播的歌名都已知且不同时不用这条时间轴（防止上一首残留）。 */
    val title: String?,
) {
    /** [position] 时正在唱的那一句的下标；还没到第一句返回 -1。 */
    fun indexAt(position: Long): Int {
        var low = 0
        var high = lines.size - 1
        var found = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (lines[mid].start <= position) {
                found = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return found
    }

    private val texts: Set<String> by lazy {
        lines.mapNotNullTo(HashSet()) { it.text?.trim()?.takeIf { text -> text.isNotEmpty() } }
    }

    /** 时间轴里有没有这一句（去掉首尾空白比较）。 */
    fun containsLine(text: String): Boolean = text.trim() in texts

    /** [position] 之后下一句开始的时间；已经是最后一句返回 null。 */
    fun nextStartAfter(position: Long): Long? = lines.getOrNull(indexAt(position) + 1)?.start

    companion object {
        /** 时间戳不可用（少于两句、全部同一时刻）时返回 null，只能依赖逐句推送。 */
        fun of(raw: List<TimedLine>?, title: String?): LyricTimeline? {
            if (raw == null) return null
            val lines = raw.filter { it.start >= 0 }.sortedBy { it.start }
            if (lines.size < 2 || lines.first().start == lines.last().start) return null
            return LyricTimeline(lines, title?.trim()?.takeIf { it.isNotEmpty() })
        }
    }
}

/**
 * 播放进度快照：[position] 是 [elapsedAt]（`SystemClock.elapsedRealtime()` 时钟）那一刻的进度，按 [speed] 外推。
 * 两个来源：卡片的 MediaController（PlaybackState.lastPositionUpdateTime），以及 v4 帧（收到帧的时刻）。
 */
internal data class ProgressAnchor(
    val position: Long,
    val speed: Float,
    val elapsedAt: Long,
    /** 只有明确在播放（PLAYING）时才外推；暂停 / 缓冲 / 未知都停在快照位置。 */
    val playing: Boolean,
) {
    fun positionAt(now: Long): Long {
        if (!playing) return position
        // v4 约定 speed <= 0 表示冻结或未知；已经确定在播放，未知按 1 倍速。
        val rate = if (speed > 0f) speed else 1f
        val elapsed = (now - elapsedAt).coerceAtLeast(0L)
        return position + (elapsed * rate).toLong()
    }

    companion object {
        /**
         * 取更新的那份快照。切歌时 SuperLyric 先推新歌的全量歌词，卡片的 MediaController 进度还停在上一首末尾，
         * 用旧进度在新歌时间轴上取句会闪一句错的；v4 帧的快照更新，用它。
         */
        fun freshest(a: ProgressAnchor?, b: ProgressAnchor?): ProgressAnchor? = when {
            a == null -> b
            b == null -> a
            b.elapsedAt > a.elapsedAt -> b
            else -> a
        }
    }
}

/** 最近一次收到的歌词行。[paused] 只有 v4 服务端能给（3.x 没有暂停信号，以卡片自己的播放状态为准）。 */
internal data class LyricLineUpdate(
    val session: LyricSession,
    val text: String?,
    val paused: Boolean = false,
    /**
     * 歌名。主动拉取（getLatestLyric）得到的歌词不带发布方包名，只能靠歌名和卡片当前播放的歌对上。
     */
    val title: String? = null,
    /** 这段会话的完整时间轴（拿到过全量歌词才有）。 */
    val timeline: LyricTimeline? = null,
    /** v4 帧里的进度快照；3.x 没有。 */
    val anchor: ProgressAnchor? = null,
)

/** 宿主 updateAlbum 的三个参数。 */
internal data class AlbumText(val title: String?, val artist: String?, val album: String?)

/** 一张音乐卡片此刻的状态，加上背屏桌面 / 息屏这两个全局状态。 */
internal data class LyricCardState(
    val enabled: Boolean,
    val cardPackage: String?,
    val cardPlaying: Boolean,
    val cardVisible: Boolean,
    val launcherResumed: Boolean,
    val inAod: Boolean,
    /** 卡片当前播放的歌名（MediaMetadata 的 title），用于核对主动拉取的歌词。 */
    val cardTitle: String? = null,
    /** 「息屏时也显示歌词」：AOD 下不看卡片可见性 / 背屏桌面前台（AOD 时卡片根是暂停的）。 */
    val showInAod: Boolean = false,
)

internal object RearLyricPolicy {
    /** 按播放进度取句时提前量：进度来自 MediaSession 外推，略早一点切句，避免逐句推送先到后又被时间轴拉回上一句。 */
    const val TIMELINE_LEAD_MS = 150L

    /**
     * 有可用时间轴和播放进度时，用时间轴里 [position] 处的那一句替换推送来的文本；
     * 否则原样返回（只能靠逐句推送）。还没到第一句时文本为 null（显示原歌手名）。
     */
    fun resolveLine(line: LyricLineUpdate, position: Long?, cardTitle: String?): LyricLineUpdate {
        val timeline = usableTimeline(line, position, cardTitle) ?: return line
        val index = timeline.indexAt(position!! + TIMELINE_LEAD_MS)
        val text = timeline.lines.getOrNull(index)?.text
        // 时间轴已经和卡片核对过是同一首：歌名对齐卡片，主动拉取（不带发布方）的歌词才能通过歌名核对。
        val title = cardTitle?.trim()?.takeIf { it.isNotEmpty() } ?: line.title ?: timeline.title
        return line.copy(text = text, title = title)
    }

    /**
     * 下一次需要按时间轴重新取句的延迟（毫秒，按播放速度换算）；不需要时间轴驱动时返回 null。
     * 最长 [maxDelay]：拖动进度条不一定有回调，定期按进度校正。
     */
    fun nextTimelineDelay(
        line: LyricLineUpdate,
        position: Long?,
        speed: Float,
        cardTitle: String?,
        maxDelay: Long,
    ): Long? {
        val timeline = usableTimeline(line, position, cardTitle) ?: return null
        val lead = position!! + TIMELINE_LEAD_MS
        val next = timeline.nextStartAfter(lead) ?: return maxDelay
        val rate = if (speed > 0f) speed else 1f
        return ((next - lead) / rate).toLong().coerceIn(16L, maxDelay)
    }

    private fun usableTimeline(line: LyricLineUpdate, position: Long?, cardTitle: String?): LyricTimeline? {
        val timeline = line.timeline ?: return null
        if (position == null || position < 0) return null
        val mine = timeline.title
        val theirs = cardTitle?.trim()?.takeIf { it.isNotEmpty() }
        // 开了「车载 / 蓝牙歌词」时播放器把当前句写进元数据标题：标题是这首歌里的一句也算同一首。
        if (mine != null && theirs != null && mine != theirs && !timeline.containsLine(theirs)) return null
        return timeline
    }

    /**
     * 这张卡片现在该显示的歌词；返回 null 表示恢复原来的歌手名。
     *
     * 全部条件同时成立才显示：开关开着、有歌词行、歌词来源与卡片当前播放的是同一个 App
     * （防止 A 的歌词出现在 B 的卡片上）、正在播放、卡片可见、背屏桌面在前台、不在息屏。
     */
    fun lyricToShow(line: LyricLineUpdate?, card: LyricCardState): String? {
        if (!card.enabled || line == null) return null
        val text = normalize(line.text) ?: return null
        val publisher = line.session.publisher?.takeIf { it.isNotBlank() }
        if (publisher != null) {
            if (publisher != card.cardPackage) return null
        } else {
            // 主动拉取的「当前这一句」没有发布方包名：歌名和卡片正在播的歌一致才显示。
            val title = line.title?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (title != card.cardTitle?.trim()) return null
        }
        if (!card.cardPlaying || line.paused) return null
        if (card.inAod) return if (card.showInAod) text else null
        if (!card.cardVisible || !card.launcherResumed) return null
        return text
    }

    /** [title] 是不是当前这首里的一句歌词（时间轴里有，或等于最近推送的那一句）。 */
    fun isLyricTitle(title: String?, line: LyricLineUpdate?): Boolean {
        val shown = title?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        if (line == null) return false
        return line.timeline?.containsLine(shown) == true || normalize(line.text) == shown
    }

    /**
     * [album] 的标题能不能当作真实歌名记下来：不是歌词；手上还没有歌词可比时，只认车载格式里
     * 歌手字段以「标题-」开头的（这时标题就是歌名）。
     */
    fun isTrustedTitle(album: AlbumText, line: LyricLineUpdate?): Boolean {
        val title = album.title?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        if (isLyricTitle(title, line)) return false
        if (line != null) return true
        val artist = album.artist?.trim() ?: return false
        return artist.startsWith("$title-") || artist.startsWith("$title - ")
    }

    /**
     * 播放器开了「车载 / 蓝牙歌词」时把元数据改成 标题=当前这句、歌手=「歌名-歌手」，背屏卡片就会
     * 歌名行显示歌词、和歌手行的歌词重复。标题是当前这首里的一句时，从「歌名-歌手」拆回歌名和歌手，
     * 返回改写后的参数；不是这种情况、或拆不出来时返回 null（原样交给宿主）。
     *
     * 歌名里也可能有「-」，所以先用候选歌名去对前缀：[knownTitle]（同一首歌里宿主最近一次收到的
     * 「不是歌词」的标题）、SuperLyric 给的歌名（服务端会用元数据标题覆盖，车载模式下常常也是歌词，
     * 所以同样要先排除歌词）。都对不上时，只有恰好一个「-」才直接拆。
     *
     * [line] 必须来自卡片正在播放的 App（[cardPackage]），否则不改。
     */
    fun restoreCarLyricAlbum(
        album: AlbumText,
        line: LyricLineUpdate?,
        cardPackage: String?,
        knownTitle: String?,
    ): AlbumText? {
        if (line == null) return null
        val publisher = line.session.publisher?.takeIf { it.isNotBlank() }
        if (publisher != null && publisher != cardPackage) return null
        if (!isLyricTitle(album.title, line)) return null
        val combined = album.artist?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val candidates = listOfNotNull(knownTitle, line.timeline?.title, line.title)
            .map { it.trim() }
            .filter { it.isNotEmpty() && !isLyricTitle(it, line) }
            .distinct()
        for (title in candidates) {
            for (separator in CAR_SEPARATORS) {
                if (!combined.startsWith(title + separator)) continue
                val artist = combined.removePrefix(title + separator).trim()
                if (artist.isNotEmpty()) return AlbumText(title, artist, album.album)
            }
        }
        val parts = combined.split('-')
        if (parts.size == 2 && parts.all { it.isNotBlank() }) {
            return AlbumText(parts[0].trim(), parts[1].trim(), album.album)
        }
        return null
    }

    private val CAR_SEPARATORS = listOf("-", " - ")

    /**
     * 显示歌词时歌手行的文字：「歌名 - 歌手」。歌名本身是歌词（车载歌词没能换回真实歌名）时，
     * 播放器给的歌手字段已经是「歌名-歌手」，原样用。
     */
    fun songLine(title: String?, artist: String?, line: LyricLineUpdate?): String {
        val song = title?.trim()?.takeIf { it.isNotEmpty() && !isLyricTitle(it, line) }
        val singer = artist?.trim()?.takeIf { it.isNotEmpty() }
        return when {
            song == null -> singer.orEmpty()
            singer == null -> song
            else -> "$song - $singer"
        }
    }

    /**
     * 歌手行只有一行：统一换行符后取第一行（有的发布方把翻译拼在第二行），去掉首尾空白，空串当没有。
     */
    fun normalize(raw: String?): String? {
        if (raw == null) return null
        val firstLine = raw.replace("\r\n", "\n").replace('\r', '\n')
            .split('\n')
            .firstOrNull { it.isNotBlank() }
            ?: return null
        return firstLine.trim().takeIf { it.isNotEmpty() }
    }
}
