package hook.HyperBackscreen.tile

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import android.widget.Toast
import hook.HyperBackscreen.R
import hook.HyperBackscreen.common.Constants
import hook.HyperBackscreen.ui.recorder.RearRecordService
import hook.HyperBackscreen.ui.recorder.RearRecorderState
import hook.HyperBackscreen.ui.recorder.RearScreenRecorder
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 控制中心「背屏录屏」磁贴：点一下开始录制，录制中再点一下停止并保存（结果用 Toast 告知）。
 *
 * 录制在 [RearRecordService] 前台服务里跑，控制中心收起、磁贴解绑都不影响。准备 / 保存期间磁贴置灰，
 * 系统不再派发点击。不启动 Activity，也不要求解锁：只录背屏，视频进相册，看仍要解锁。
 */
class RearRecordTileService : TileService() {
    private val scope = MainScope()
    private var listening: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        listening?.cancel()
        listening = scope.launch { RearScreenRecorder.state.collect(::render) }
    }

    override fun onStopListening() {
        listening?.cancel()
        listening = null
        super.onStopListening()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        when (RearScreenRecorder.state.value) {
            RearRecorderState.Idle -> if (!RearRecordService.start(this)) {
                toast(getString(R.string.rear_record_tile_start_blocked))
            }
            is RearRecorderState.Recording -> RearScreenRecorder.stop(this)
            else -> Unit
        }
    }

    private fun render(state: RearRecorderState) {
        val (tileState, subtitle) = when (state) {
            RearRecorderState.Idle -> Tile.STATE_INACTIVE to null
            RearRecorderState.Starting -> Tile.STATE_UNAVAILABLE to getString(R.string.rear_record_starting)
            is RearRecorderState.Recording -> Tile.STATE_ACTIVE to getString(R.string.rear_record_state_recording)
            RearRecorderState.Saving -> Tile.STATE_UNAVAILABLE to getString(R.string.rear_record_saving)
        }
        try {
            val tile = qsTile ?: return
            tile.state = tileState
            tile.label = getString(R.string.rear_record_tile_label)
            tile.subtitle = subtitle
            tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_rear_record)
            tile.updateTile()
        } catch (t: Throwable) {
            Log.d(Constants.LOG_TAG, "Rear record tile update skipped: $t")
        }
    }

    private fun toast(text: String) {
        runCatching { Toast.makeText(applicationContext, text, Toast.LENGTH_SHORT).show() }
    }
}
