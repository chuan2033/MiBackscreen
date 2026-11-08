package hook.HyperBackscreen.ui.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import hook.HyperBackscreen.common.ActivityLaunch

/** Callers keep their retry UI when no handler exists or launch is denied. */
internal fun openUrl(context: Context, url: String): Boolean =
    ActivityLaunch.attempt {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
