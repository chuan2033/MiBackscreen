package hook.HyperBackscreen.tile

import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import android.widget.Toast
import hook.HyperBackscreen.R
import hook.HyperBackscreen.bridge.RearCastClient
import hook.HyperBackscreen.common.Constants
import hook.HyperBackscreen.common.RearCastPolicy

/**
 * 控制中心「投到背屏」磁贴。
 *
 * 点一下执行「切换」：背屏上有被投放的应用就拉回主屏，否则把主屏当前应用移到背屏。
 * 真正的执行者在 system_server（RearCastHost），这里只发请求；结果用 Toast 告知。
 * 磁贴状态在每次控制中心展开时查询一次：背屏上有投放的应用为 ACTIVE，否则（含查不到）INACTIVE。
 * 不启动任何 Activity（Android 14+ 对磁贴启动 Activity 有限制，这里也不需要）。
 */
class RearCastTileService : TileService() {
    /** 执行中：磁贴置为 UNAVAILABLE（系统不再派发点击），结果回来前不接受第二次请求。 */
    private var busy = false
    /** 查询序号：控制中心快速开关时，晚到的旧查询不覆盖新状态。 */
    private var querySeq = 0

    override fun onStartListening() {
        super.onStartListening()
        if (!busy) refresh()
    }

    override fun onClick() {
        super.onClick()
        // 锁屏下主屏前台应用不可见、投放没有意义；拉回主屏也不该绕过锁屏。先解锁再执行。
        if (isLocked) unlockAndRun { toggle() } else toggle()
    }

    private fun toggle() {
        if (busy) return
        busy = true
        render(Tile.STATE_UNAVAILABLE, getString(R.string.rear_cast_tile_busy))
        RearCastClient.send(this, Constants.REAR_CAST_OP_TOGGLE) { outcome, packageName ->
            busy = false
            showToast(message(outcome, packageName))
            renderState(outcome.rearHasApp())
            // 拉回主屏后背屏上可能还有别的投放应用，以一次查询为准。
            if (outcome == RearCastPolicy.Outcome.MOVED_TO_MAIN) refresh()
        }
    }

    private fun refresh() {
        val seq = ++querySeq
        RearCastClient.send(this, Constants.REAR_CAST_OP_QUERY) { outcome, _ ->
            if (seq == querySeq && !busy) renderState(outcome.rearHasApp())
        }
    }

    /** null = 查不到（系统侧未生效等），保持 inactive、不写副标题。 */
    private fun renderState(rearHasApp: Boolean?) {
        when (rearHasApp) {
            true -> render(Tile.STATE_ACTIVE, getString(R.string.panel_tile_rear_cast_to_main))
            false -> render(Tile.STATE_INACTIVE, getString(R.string.panel_tile_rear_cast_to_rear))
            null -> render(Tile.STATE_INACTIVE, null)
        }
    }

    private fun render(state: Int, subtitle: CharSequence?) {
        try {
            val tile = qsTile ?: return
            tile.state = state
            tile.label = getString(R.string.rear_cast_tile_label)
            tile.subtitle = subtitle
            tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_rear_cast)
            tile.updateTile()
        } catch (t: Throwable) {
            // 磁贴已解绑（控制中心收起）时更新会失败，下次展开会重新查询。
            Log.d(Constants.LOG_TAG, "Rear cast tile update skipped: $t")
        }
    }

    private fun message(outcome: RearCastPolicy.Outcome, packageName: String?): String = when (outcome) {
        RearCastPolicy.Outcome.MOVED_TO_REAR -> getString(R.string.rear_cast_toast_to_rear, appLabel(packageName))
        RearCastPolicy.Outcome.MOVED_TO_MAIN -> getString(R.string.rear_cast_toast_to_main, appLabel(packageName))
        RearCastPolicy.Outcome.NO_APP_ON_MAIN -> getString(R.string.rear_cast_no_app_on_main)
        RearCastPolicy.Outcome.NO_APP_ON_REAR -> getString(R.string.rear_cast_no_app_on_rear)
        RearCastPolicy.Outcome.API_UNAVAILABLE -> getString(R.string.rear_cast_api_unavailable)
        RearCastPolicy.Outcome.NOT_ACTIVE -> getString(R.string.rear_cast_not_active)
        RearCastPolicy.Outcome.TIMEOUT -> getString(R.string.rear_cast_timeout)
        RearCastPolicy.Outcome.LOCKED -> getString(R.string.rear_cast_locked)
        RearCastPolicy.Outcome.DENIED -> getString(R.string.rear_cast_denied)
        else -> getString(R.string.rear_cast_move_failed)
    }

    private fun appLabel(packageName: String?): String {
        if (packageName.isNullOrEmpty()) return ""
        return try {
            val info = packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
            packageManager.getApplicationLabel(info).toString()
        } catch (_: Throwable) {
            packageName
        }
    }

    private fun showToast(text: String) {
        try {
            Toast.makeText(applicationContext, text, Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            Log.w(Constants.LOG_TAG, "Rear cast toast failed", t)
        }
    }
}
