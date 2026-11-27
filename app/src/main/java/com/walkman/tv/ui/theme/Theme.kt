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

private fun material3DarkColorScheme(theme: ThemeColor): androidx.compose.material3.ColorScheme =
    androidx.compose.material3.darkColorScheme(
        primary = androidx.compose.ui.graphics.Color(theme.primaryArgb),
        onPrimary = AppColors.BgDeep,
        secondary = androidx.compose.ui.graphics.Color(theme.primaryArgb),
        onSecondary = AppColors.BgDeep,
        background = AppColors.BgDeep,
        onBackground = AppColors.TextPrimary,
        surface = AppColors.BgPanel,
        onSurface = AppColors.TextPrimary,
        surfaceVariant = AppColors.Card,
        onSurfaceVariant = AppColors.TextSecondary,
        outline = AppColors.TextMuted,
        error = AppColors.Danger,
    )

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
    val tvScheme = remember(themeColor) { walkmanColorScheme(themeColor) }
    val m3Scheme = remember(themeColor) { material3DarkColorScheme(themeColor) }
    androidx.compose.material3.MaterialTheme(colorScheme = m3Scheme) {
        MaterialTheme(
            colorScheme = tvScheme,
            typography = WalkmanTypography,
            content = content,
        )
    }
}

private val WalkmanTypography = Typography().let { base ->
    base.copy(
        displayMedium = base.displayMedium.copy(fontSize = 32.sp),
        titleLarge = base.titleLarge.copy(fontSize = 22.sp),
        bodyLarge = base.bodyLarge.copy(fontSize = 16.sp),
        bodyMedium = base.bodyMedium.copy(fontSize = 13.sp),
    )
}
