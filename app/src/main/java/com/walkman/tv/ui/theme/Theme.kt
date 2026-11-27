package com.walkman.tv.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ColorScheme
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme
import com.walkman.tv.data.store.ThemeColor
import com.walkman.tv.ui.appContainer
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private fun walkmanColorScheme(theme: ThemeColor): ColorScheme = darkColorScheme(
    primary = androidx.compose.ui.graphics.Color(theme.primaryArgb),
    onPrimary = AppColors.BgDeep,
    secondary = androidx.compose.ui.graphics.Color(theme.primaryArgb),
    background = AppColors.BgDeep,
    onBackground = AppColors.TextPrimary,
    surface = AppColors.BgPanel,
    onSurface = AppColors.TextPrimary,
    surfaceVariant = AppColors.Card,
    onSurfaceVariant = AppColors.TextSecondary,
    border = androidx.compose.ui.graphics.Color(theme.brightArgb),
)

/** Spacing / radius tokens (mirrors DS.Spacing / DS.Radius). */
object Dimens {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp

    val radiusSmall = 8.dp
    val radiusMedium = 12.dp
    val radiusLarge = 18.dp
    val radiusXLarge = 28.dp
}

@Composable
fun WalkmanTvTheme(content: @Composable () -> Unit) {
    @Suppress("UNUSED_EXPRESSION") isSystemInDarkTheme() // TV is always dark here
    val themeColor by remember {
        appContainer.settingsStore.settings
            .map { it.themeColor }
            .distinctUntilChanged()
    }.collectAsState(initial = appContainer.settingsStore.settings.value.themeColor)
    if (AppColors.themeColor != themeColor) {
        AppColors.themeColor = themeColor
    }
    val colorScheme = remember(themeColor) { walkmanColorScheme(themeColor) }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = WalkmanTypography,
        content = content,
    )
}

private val WalkmanTypography = Typography().let { base ->
    base.copy(
        displayMedium = base.displayMedium.copy(fontSize = 32.sp),
        titleLarge = base.titleLarge.copy(fontSize = 22.sp),
        bodyLarge = base.bodyLarge.copy(fontSize = 16.sp),
        bodyMedium = base.bodyMedium.copy(fontSize = 13.sp),
    )
}
