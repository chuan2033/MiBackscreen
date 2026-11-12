package hook.HyperBackscreen.ui.about

import android.content.Context
import android.net.Uri
import hook.HyperBackscreen.BuildConfig
import hook.HyperBackscreen.R
import hook.HyperBackscreen.common.Constants
import hook.HyperBackscreen.ui.util.currentDeviceName
import hook.HyperBackscreen.ui.util.currentHyperOSVersion
import hook.HyperBackscreen.ui.util.currentPackageVersion
import hook.HyperBackscreen.ui.util.currentSystemVersion

/**
 * 拼「去 GitHub 反馈问题」的新建 Issue 链接。
 *
 * GitHub 不能通过链接附带文件，所以只预填标题和正文：环境信息自动填好，日志包由用户在网页里自己添加。
 * 仓库里不要加 issue 模板：有模板时 `/issues/new` 可能先落到模板选择页，这里预填的内容会丢。
 */
internal object FeedbackIssue {
    private const val NEW_ISSUE_URL = "https://github.com/chuan2033/MiBackscreen/issues/new"

    fun buildUrl(context: Context, logFileName: String?): String {
        val body = context.getString(
            R.string.feedback_issue_body,
            "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            currentDeviceName(),
            currentHyperOSVersion(),
            currentSystemVersion(),
            currentPackageVersion(context, Constants.TARGET_PACKAGE),
            currentPackageVersion(context, Constants.THEME_STORE_PACKAGE),
            logFileName ?: "—",
        )
        return Uri.parse(NEW_ISSUE_URL).buildUpon()
            .appendQueryParameter("title", context.getString(R.string.feedback_issue_title))
            .appendQueryParameter("body", body)
            .build()
            .toString()
    }
}
