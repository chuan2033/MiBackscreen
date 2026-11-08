package hook.HyperBackscreen.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import hook.HyperBackscreen.R
import top.yukonga.miuix.kmp.basic.Badge
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun FunctionCountBadge(count: Int) {
    val description = pluralStringResource(R.plurals.enabled_function_count, count, count)
    Badge(
        modifier = Modifier.clearAndSetSemantics { contentDescription = description },
        containerColor = MiuixTheme.colorScheme.primary,
        contentColor = MiuixTheme.colorScheme.onPrimary,
    ) {
        Text(count.toString())
    }
}
