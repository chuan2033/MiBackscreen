package hook.HyperBackscreen.lyric

/**
 * 背屏实时歌词的纯逻辑：选哪个 SuperLyric 服务、会话匹配、什么时候把歌词写进音乐卡片、文本整形。
 * 不依赖 Android / 宿主类，单元测试直接覆盖。
 */

/** SuperLyric 两代服务名。4.x 优先；手机上的 4.1 Release（API 37）其实只提供 3.x 协议。 */
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

/** 最近一次收到的歌词行。[paused] 只有 v4 服务端能给（3.x 没有暂停信号，以卡片自己的播放状态为准）。 */
internal data class LyricLineUpdate(
    val session: LyricSession,
    val text: String?,
    val paused: Boolean = false,
    /**
     * 歌名。主动拉取（getLatestLyric）得到的歌词不带发布方包名，只能靠歌名和卡片当前播放的歌对上。
     */
    val title: String? = null,
)

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
)

internal object RearLyricPolicy {
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
        if (!card.cardVisible || !card.launcherResumed || card.inAod) return null
        return text
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
