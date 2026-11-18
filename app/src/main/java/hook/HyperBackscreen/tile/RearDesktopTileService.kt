package hook.HyperBackscreen.tile

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import hook.HyperBackscreen.R
import hook.HyperBackscreen.common.Constants
import hook.HyperBackscreen.common.RearCastPolicy
import hook.HyperBackscreen.desktop.RearDesktopController
import hook.HyperBackscreen.desktop.RearDesktopState

/**
 * 控制中心「背屏桌面」磁贴：没在运行时点一下在背屏打开背屏桌面，运行中点一下退出。
 * 打开由 system_server 以系统身份启动背屏桌面，磁贴本身不启动 Activity。
 */
class RearDesktopTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        render(RearDesktopState.running.value)
    }

    override fun onClick() {
        super.onClick()
        if (RearDesktopState.running.value) {
            RearDesktopController.exit(this)
            render(false)
            return
        }
        // 锁屏时系统不允许应用显示到背屏，先解锁再打开。
        if (isLocked) unlockAndRun { open() } else open()
    }

    private fun open() {
        render(true)
        // 打开成功时桌面 Activity 还在启动，服务稍后才运行：按打开结果显示。
        RearDesktopController.open(this) { outcome ->
            render(outcome == RearCastPolicy.Outcome.DONE || RearDesktopState.running.value)
        }
    }

    private fun render(active: Boolean) {
        try {
            val tile = qsTile ?: return
            tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.label = getString(R.string.rear_desktop_tile_label)
            tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_rear_desktop)
            tile.updateTile()
        } catch (t: Throwable) {
            Log.d(Constants.LOG_TAG, "Rear desktop tile update skipped: $t")
        }
    }
}
