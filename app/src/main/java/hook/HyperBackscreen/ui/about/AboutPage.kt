package hook.HyperBackscreen.ui.about

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import top.yukonga.miuix.kmp.squircle.squircleClip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import hook.HyperBackscreen.BuildConfig
import hook.HyperBackscreen.R
import hook.HyperBackscreen.ui.components.AboutArrowPreference
import hook.HyperBackscreen.ui.components.CardBlock
import hook.HyperBackscreen.ui.theme.HomeUiTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.widget.Toast
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

@Composable
internal fun AboutPage(onLicenseClick: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var generatingFeedback by remember { mutableStateOf(false) }

    AboutHeader()

    SmallTitle(text = stringResource(R.string.about_developer_title), insideMargin = PaddingValues(16.dp, 8.dp))
    CardBlock(pressFeedbackType = PressFeedbackType.None) {
        AboutArrowPreference(title = "AxlQ", summary = null, url = "https://github.com/chuan2033")
    }

    SmallTitle(text = stringResource(R.string.about_project_url_title), insideMargin = PaddingValues(16.dp, 8.dp))
    CardBlock(pressFeedbackType = PressFeedbackType.None) {
        AboutArrowPreference(
            title = "MiBackscreen",
            summary = null,
            url = "https://github.com/chuan2033/MiBackscreen"
        )
    }

    SmallTitle(text = stringResource(R.string.about_feedback_title), insideMargin = PaddingValues(16.dp, 8.dp))
    CardBlock(pressFeedbackType = PressFeedbackType.None) {
        AboutArrowPreference(
            title = stringResource(R.string.about_feedback_log),
            summary = null,
            onClick = {
                if (generatingFeedback) return@AboutArrowPreference
                generatingFeedback = true
                Toast.makeText(context, R.string.feedback_log_generating, Toast.LENGTH_SHORT).show()
                scope.launch {
                    val file = withContext(Dispatchers.IO) {
                        FeedbackLogExporter.create(context)
                    }
                    generatingFeedback = false
                    if (file != null) {
                        FeedbackLogExporter.share(context, file)
                    } else {
                        Toast.makeText(context, R.string.feedback_log_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            },
        )
    }

    SmallTitle(text = stringResource(R.string.about_credits_title), insideMargin = PaddingValues(16.dp, 8.dp))
    CardBlock(pressFeedbackType = PressFeedbackType.None) {
        AboutArrowPreference(
            title = stringResource(R.string.about_miuix_summary),
            summary = stringResource(R.string.about_miuix_lib),
            url = "https://github.com/compose-miuix-ui/miuix"
        )
        AboutArrowPreference(
            title = stringResource(R.string.about_open_source_licenses),
            summary = stringResource(R.string.about_view_licenses),
            onClick = onLicenseClick
        )
    }
}

@Composable
private fun AboutHeader() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                top = HomeUiTokens.AboutHeaderTopPadding,
                bottom = HomeUiTokens.AboutHeaderBottomPadding
            ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(HomeUiTokens.AboutLogoSize)
                .squircleClip(HomeUiTokens.AboutLogoCornerRadius),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_about_logo),
                contentDescription = "App Icon",
                modifier = Modifier.size(HomeUiTokens.AboutLogoSize),
                contentScale = ContentScale.Fit
            )
        }
        Spacer(modifier = Modifier.height(HomeUiTokens.AboutHeaderSpacing))
        Text(
            text = "MiBackscreen",
            color = MiuixTheme.colorScheme.onBackground,
            style = MiuixTheme.textStyles.title1
        )
        Spacer(modifier = Modifier.height(HomeUiTokens.AboutVersionSpacing))
        Text(
            text = stringResource(R.string.common_version, BuildConfig.VERSION_NAME),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            style = MiuixTheme.textStyles.body2
        )
    }
}
