package hook.HyperBackscreen.ui.about

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import hook.HyperBackscreen.R
import hook.HyperBackscreen.ui.components.BlurredBar
import hook.HyperBackscreen.ui.components.CardBlock
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.utils.overScrollVertical

private fun donateQrRes(index: Int): Int = when (index) {
    0 -> R.drawable.qr_wechat
    else -> R.drawable.qr_alipay
}

@Composable
internal fun DonatePage(onBack: () -> Unit) {
    val tabs = listOf(
        stringResource(R.string.about_donate_wechat),
        stringResource(R.string.about_donate_alipay)
    )
    var selectedIndex by rememberSaveable { mutableStateOf(0) }
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val surfaceColor = MiuixTheme.colorScheme.surface
    val backdrop = rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MiuixTheme.colorScheme.surface,
        topBar = {
            BlurredBar(backdrop) {
                TopAppBar(
                    title = stringResource(R.string.about_donate_title),
                    largeTitle = stringResource(R.string.about_donate_title),
                    color = Color.Transparent,
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = MiuixIcons.Back,
                                contentDescription = stringResource(R.string.common_back),
                                tint = MiuixTheme.colorScheme.onBackground
                            )
                        }
                    }
                )
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier.layerBackdrop(backdrop)) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .padding(horizontal = 12.dp),
                contentPadding = PaddingValues(
                    top = paddingValues.calculateTopPadding()
                )
            ) {
                item {
                    Spacer(Modifier.height(12.dp))
                }
                item {
                    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                        val tabWidth = (maxWidth - 9.dp) / 2
                        TabRow(
                            tabs = tabs,
                            selectedTabIndex = selectedIndex,
                            onTabSelected = { selectedIndex = it },
                            modifier = Modifier.fillMaxWidth(),
                            minWidth = tabWidth,
                            maxWidth = tabWidth
                        )
                    }
                }
                item {
                    Spacer(Modifier.height(16.dp))
                }
                item {
                    CardBlock(pressFeedbackType = PressFeedbackType.None) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                painter = painterResource(id = donateQrRes(selectedIndex)),
                                contentDescription = stringResource(R.string.about_donate_qr_description),
                                modifier = Modifier.size(400.dp),
                                contentScale = ContentScale.Fit
                            )
                        }
                    }
                }
                item {
                    Spacer(Modifier.height(24.dp).navigationBarsPadding())
                }
            }
        }
    }
}
