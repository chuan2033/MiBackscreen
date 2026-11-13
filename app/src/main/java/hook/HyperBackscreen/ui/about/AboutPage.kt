package hook.HyperBackscreen.ui.about

import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import hook.HyperBackscreen.BuildConfig
import hook.HyperBackscreen.R
import top.yukonga.miuix.kmp.icon.extended.ContactsCircle
import top.yukonga.miuix.kmp.icon.extended.Contacts
import hook.HyperBackscreen.ui.components.LocalUiFeedback
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import hook.HyperBackscreen.ui.components.AboutArrowPreference
import hook.HyperBackscreen.ui.components.CardBlock
import hook.HyperBackscreen.ui.theme.HomeUiTokens
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Update
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

@Composable
internal fun AboutPage(
    onLicenseClick: () -> Unit = {},
    onAppUpdateClick: () -> Unit = {}
) {
    AboutHeader(onVersionClick = onAppUpdateClick)

    val context = LocalContext.current
    val feedback = LocalUiFeedback.current
    // 打不开 QQ 时号码已经复制好了，提示用户自己去 QQ 里搜。
    fun openQq(uri: String, number: String) {
        if (!openInQq(context, uri, number)) {
            val message = context.getString(R.string.about_qq_open_failed, number)
            if (feedback != null) feedback.show(message)
            else Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    // 成员的昵称、简介、头像读各自的 GitHub 主页；读不到时用代码里的名字和角色。点一下打开 GitHub 主页。
    SmallTitle(text = stringResource(R.string.about_developer_title), insideMargin = PaddingValues(16.dp, 8.dp))
    CardBlock(pressFeedbackType = PressFeedbackType.None) {
        TEAM.forEach { member ->
            val profile = rememberGitHubProfile(member.github)
            AboutArrowPreference(
                title = profile?.name ?: member.name,
                summary = profile?.bio ?: member.role,
                url = member.profileUrl,
                startAction = {
                    // 头像地址来自 GitHub 资料；资料还没读到时先显示图标。
                    AboutAvatar(url = profile?.avatarUrl, fallback = MiuixIcons.Contacts)
                },
            )
        }
    }

    // 交流群：点一下打开 QQ 群名片，加群走 QQ 自己的确认页。
    SmallTitle(text = stringResource(R.string.about_group_title), insideMargin = PaddingValues(16.dp, 8.dp))
    CardBlock(pressFeedbackType = PressFeedbackType.None) {
        AboutArrowPreference(
            title = stringResource(R.string.about_group_name),
            summary = GROUP_QQ,
            startAction = {
                AboutAvatar(url = GROUP_AVATAR_URL, fallback = MiuixIcons.ContactsCircle)
            },
            onClick = { openQq(GROUP_CARD_URI, GROUP_QQ) }
        )
    }

    // 项目地址和应用更新都是「这个模块本身」的信息，放进同一张卡，不再单独占一块。
    SmallTitle(text = stringResource(R.string.about_module_title), insideMargin = PaddingValues(16.dp, 8.dp))
    CardBlock(pressFeedbackType = PressFeedbackType.None) {
        AboutArrowPreference(
            title = "MiBackscreen",
            summary = null,
            url = "https://github.com/chuan2033/MiBackscreen",
            startAction = {
                Image(
                    painter = painterResource(R.drawable.ic_github),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    contentScale = ContentScale.Fit,
                    colorFilter = ColorFilter.tint(
                        if (MiuixTheme.colorScheme.surface.luminance() < 0.5f) Color.White else Color.Black
                    )
                )
            }
        )
        AboutArrowPreference(
            title = stringResource(R.string.about_update_title),
            summary = null,
            startAction = {
                Icon(
                    imageVector = MiuixIcons.Update,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MiuixTheme.colorScheme.onSurface
                )
            },
            onClick = onAppUpdateClick
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
private fun AboutHeader(onVersionClick: () -> Unit) {
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
                contentDescription = null,
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
        // 版本号是纯文字，没有底色和箭头；点它仍然直接进「应用更新」。
        Text(
            text = stringResource(R.string.about_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .clickable(
                    onClickLabel = stringResource(R.string.about_update_title),
                    role = Role.Button,
                    onClick = onVersionClick
                )
                .padding(horizontal = 12.dp, vertical = 4.dp),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            style = MiuixTheme.textStyles.body2
        )
    }
}
