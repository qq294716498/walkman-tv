package com.walkman.tv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text
import com.walkman.tv.data.store.FoliaAiPalette
import com.walkman.tv.data.store.FoliaBackground
import com.walkman.tv.data.store.FoliaRenderQuality
import com.walkman.tv.data.store.FoliaTemplate
import com.walkman.tv.data.store.LyricStyle
import com.walkman.tv.data.store.Settings
import com.walkman.tv.ui.components.TvFocusable
import com.walkman.tv.ui.components.TvPill
import com.walkman.tv.ui.theme.AppColors
import kotlinx.coroutines.delay

private enum class StudioTab(val label: String, val badge: String) {
    TEMPLATES("歌词模板", "20"),
    BACKGROUNDS("动态背景", "6"),
    AI_THEME("AI 主题", "✦"),
    EFFECTS("舞台调校", "FX"),
}

/**
 * Redesigned TV-native "Lyric & Stage Studio" modal opened from the player's「歌词样式」button.
 *
 * - When Folia WebView engine is OFF: compact glass panel with Classic / Apple + AI theme toggle
 *   and a subtle hint pointing to Settings.
 * - When Folia WebView engine is ON: wide dual-pane studio (left category rail + right visual
 *   grid) reproducing Folia's 18 lyric templates, 6 dynamic backgrounds, AI theme & remix button,
 *   beat-reactive / glow / film-grain / render-quality controls — all live-applied in real time.
 */
@Composable
fun LyricStyleStudioDialog(
    settings: Settings,
    activeFoliaTemplate: FoliaTemplate,
    activeFoliaBackground: FoliaBackground,
    currentMoodLabel: String?,
    currentAccent: Color,
    currentBright: Color,
    onUpdateSettings: ((Settings) -> Settings) -> Unit,
    onShuffleTemplate: () -> Unit,
    onShuffleBackground: () -> Unit,
    onRemixAiPalette: () -> Unit,
    onDismiss: () -> Unit,
) {
    val foliaOn = settings.foliaWebViewEnabled
    var activeTab by remember { mutableStateOf(StudioTab.TEMPLATES) }
    val initialFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(60)
        runCatching { initialFocus.requestFocus() }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val panelBg = Color(0xEB0B101C)
        val panelBorder = Color.White.copy(alpha = 0.14f)

        if (!foliaOn) {
            CompactLyricStyleDialog(
                settings = settings,
                panelBg = panelBg,
                panelBorder = panelBorder,
                initialFocus = initialFocus,
                onUpdateSettings = onUpdateSettings,
                onDismiss = onDismiss,
            )
            return@Dialog
        }

        Column(
            modifier = Modifier
                .width(880.dp)
                .height(530.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(panelBg)
                .border(1.dp, panelBorder, RoundedCornerShape(20.dp))
                .padding(horizontal = 24.dp, vertical = 20.dp),
        ) {
            // Top Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            "歌词样式与 Folia 舞台工坊",
                            color = AppColors.TextPrimary,
                            fontSize = 19.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(currentAccent.copy(alpha = 0.22f))
                                .padding(horizontal = 10.dp, vertical = 3.dp),
                        ) {
                            val activeLabel = when (settings.lyricStyle) {
                                LyricStyle.CLASSIC -> "经典"
                                LyricStyle.APPLE -> "类苹果"
                                LyricStyle.FOLIA_WEBVIEW -> if (settings.foliaRandomTemplate) {
                                    "随机 · ${activeFoliaTemplate.label.substringBefore(" · ")}"
                                } else {
                                    "Folia · ${activeFoliaTemplate.label}"
                                }
                            }
                            Text(
                                activeLabel,
                                color = currentBright,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "点击任意模板、背景或 AI 配色立即实时预览，按返回键收起面板",
                        color = AppColors.TextSecondary,
                        fontSize = 12.sp,
                    )
                }
                TvPill(
                    onClick = onDismiss,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text("完成", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(16.dp))

            // Dual-pane Studio Body
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                // Left Category Rail
                Column(
                    modifier = Modifier
                        .width(168.dp)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White.copy(alpha = 0.04f))
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    StudioTab.entries.forEachIndexed { idx, tab ->
                        val selected = activeTab == tab
                        TvFocusable(
                            onClick = { activeTab = tab },
                            modifier = Modifier
                                .fillMaxWidth()
                                .let { if (idx == 0) it.focusRequester(initialFocus) else it },
                            focusedScale = 1.02f,
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (selected) currentAccent.copy(alpha = 0.22f)
                                        else Color.Transparent
                                    )
                                    .padding(horizontal = 12.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    tab.label,
                                    color = if (selected) currentBright else AppColors.TextPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    modifier = Modifier.weight(1f),
                                )
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .background(
                                            if (selected) currentAccent.copy(alpha = 0.35f)
                                            else Color.White.copy(alpha = 0.08f)
                                        )
                                        .padding(horizontal = 7.dp, vertical = 2.dp),
                                ) {
                                    Text(
                                        tab.badge,
                                        color = if (selected) Color.White else AppColors.TextMuted,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.weight(1f))
                    // Live AI Palette Swatch Preview inside sidebar footer
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.White.copy(alpha = 0.04f))
                            .padding(10.dp),
                    ) {
                        Text(
                            if (settings.aiThemeEnabled) "✦ ${currentMoodLabel ?: settings.foliaAiPalette.label}"
                            else "主题配色已固定",
                            color = currentBright,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(6.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(50))
                                .background(
                                    Brush.horizontalGradient(listOf(currentAccent, currentBright))
                                ),
                        )
                    }
                }

                // Right Content Stage
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                ) {
                    when (activeTab) {
                        StudioTab.TEMPLATES -> TemplatesPane(
                            settings = settings,
                            activeTemplate = activeFoliaTemplate,
                            accent = currentAccent,
                            bright = currentBright,
                            onUpdateSettings = onUpdateSettings,
                            onShuffleTemplate = onShuffleTemplate,
                        )
                        StudioTab.BACKGROUNDS -> BackgroundsPane(
                            settings = settings,
                            activeBackground = activeFoliaBackground,
                            accent = currentAccent,
                            bright = currentBright,
                            onUpdateSettings = onUpdateSettings,
                            onShuffleBackground = onShuffleBackground,
                        )
                        StudioTab.AI_THEME -> AiThemePane(
                            settings = settings,
                            currentMoodLabel = currentMoodLabel,
                            accent = currentAccent,
                            bright = currentBright,
                            onUpdateSettings = onUpdateSettings,
                            onRemixAiPalette = onRemixAiPalette,
                        )
                        StudioTab.EFFECTS -> EffectsPane(
                            settings = settings,
                            accent = currentAccent,
                            bright = currentBright,
                            onUpdateSettings = onUpdateSettings,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactLyricStyleDialog(
    settings: Settings,
    panelBg: Color,
    panelBorder: Color,
    initialFocus: FocusRequester,
    onUpdateSettings: ((Settings) -> Settings) -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(460.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(panelBg)
            .border(1.dp, panelBorder, RoundedCornerShape(18.dp))
            .padding(horizontal = 24.dp, vertical = 20.dp),
    ) {
        Text("歌词样式与氛围主题", color = AppColors.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text("选择歌词排版或开关 AI 随歌智能配色", color = AppColors.TextSecondary, fontSize = 12.sp)
        Spacer(Modifier.height(14.dp))

        val baseStyles = listOf(
            LyricStyle.CLASSIC to "居中简洁排版，经典高亮",
            LyricStyle.APPLE to "左对齐粗体 + 弹簧缩放 + 60fps 像素扫光",
        )
        baseStyles.forEachIndexed { idx, (style, desc) ->
            val active = settings.lyricStyle == style
            TvFocusable(
                onClick = {
                    onUpdateSettings { it.copy(lyricStyle = style) }
                    onDismiss()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .let { if (active || (idx == 0 && settings.lyricStyle == LyricStyle.FOLIA_WEBVIEW)) it.focusRequester(initialFocus) else it },
                focusedScale = 1.02f,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (active) AppColors.AccentGreen.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.05f))
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            style.label,
                            color = if (active) AppColors.AccentGreen else AppColors.TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(desc, color = AppColors.TextSecondary, fontSize = 11.sp)
                    }
                    if (active) {
                        Text("当前 ✓", color = AppColors.AccentGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        TvFocusable(
            onClick = { onUpdateSettings { it.copy(aiThemeEnabled = !it.aiThemeEnabled) } },
            modifier = Modifier.fillMaxWidth(),
            focusedScale = 1.02f,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.05f))
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "✦ AI 氛围主题（随歌配色）",
                        color = if (settings.aiThemeEnabled) AppColors.AccentGreen else AppColors.TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text("自动提取封面主色与歌词情绪生成氛围光晕", color = AppColors.TextSecondary, fontSize = 11.sp)
                }
                Text(
                    if (settings.aiThemeEnabled) "已开启" else "已关闭",
                    color = if (settings.aiThemeEnabled) AppColors.AccentGreen else AppColors.TextMuted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(AppColors.Warning.copy(alpha = 0.12f))
                .padding(horizontal = 12.dp, vertical = 9.dp),
        ) {
            Text(
                "如需体验 Folia 全部 18 款动态舞台模板、6 种动态背景与 AI 舞台特效，请前往「设置 → Folia 渲染引擎」开启（性能要求极高）。",
                color = AppColors.Warning,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        }
    }
}

@Composable
private fun TemplatesPane(
    settings: Settings,
    activeTemplate: FoliaTemplate,
    accent: Color,
    bright: Color,
    onUpdateSettings: ((Settings) -> Settings) -> Unit,
    onShuffleTemplate: () -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(4.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(4) }) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                listOf(
                    LyricStyle.CLASSIC to "居中简洁 · 保留转盘",
                    LyricStyle.APPLE to "60fps 扫光 · 保留转盘",
                ).forEach { (style, desc) ->
                    val active = settings.lyricStyle == style
                    TvFocusable(
                        onClick = { onUpdateSettings { it.copy(lyricStyle = style) } },
                        modifier = Modifier.weight(1f),
                        focusedScale = 1.02f,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (active) accent.copy(alpha = 0.22f)
                                    else Color.White.copy(alpha = 0.05f)
                                )
                                .border(
                                    width = if (active) 1.5.dp else 1.dp,
                                    color = if (active) bright else Color.White.copy(alpha = 0.08f),
                                    shape = RoundedCornerShape(12.dp),
                                )
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            NativeStylePreviewThumb(
                                style = style,
                                accent = accent,
                                bright = bright,
                                modifier = Modifier
                                    .width(74.dp)
                                    .height(42.dp)
                                    .clip(RoundedCornerShape(7.dp)),
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    style.label,
                                    color = if (active) bright else AppColors.TextPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(desc, color = AppColors.TextSecondary, fontSize = 10.sp, maxLines = 1)
                            }
                            if (active) {
                                Text("✓", color = bright, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        item(span = { GridItemSpan(4) }) {
            Text(
                "Folia 动态舞台模板（18 款 + 随歌随机 · 全屏沉浸无转盘）",
                color = AppColors.TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        // 随机模板卡片
        item(key = "folia-random-template") {
            val active = settings.lyricStyle == LyricStyle.FOLIA_WEBVIEW && settings.foliaRandomTemplate
            TvFocusable(
                onClick = {
                    if (active) {
                        onShuffleTemplate()
                    } else {
                        onUpdateSettings {
                            it.copy(
                                lyricStyle = LyricStyle.FOLIA_WEBVIEW,
                                foliaRandomTemplate = true,
                            )
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                focusedScale = 1.04f,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (active) accent.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.04f))
                        .border(
                            width = if (active) 2.dp else 1.dp,
                            color = if (active) bright else Color.White.copy(alpha = 0.10f),
                            shape = RoundedCornerShape(12.dp),
                        )
                        .padding(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    ShufflePreviewThumb(
                        accent = accent,
                        bright = bright,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(66.dp)
                            .clip(RoundedCornerShape(8.dp)),
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        if (active) "随机 · ${activeTemplate.label.substringBefore(" · ")}" else "随机",
                        color = if (active) bright else AppColors.TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        items(FoliaTemplate.entries, key = { it.id }) { tpl ->
            val active = settings.lyricStyle == LyricStyle.FOLIA_WEBVIEW &&
                !settings.foliaRandomTemplate &&
                settings.foliaTemplate == tpl
            val zhName = tpl.label.substringBefore(" · ")
            TvFocusable(
                onClick = {
                    onUpdateSettings {
                        it.copy(
                            lyricStyle = LyricStyle.FOLIA_WEBVIEW,
                            foliaRandomTemplate = false,
                            foliaTemplate = tpl,
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                focusedScale = 1.04f,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (active) accent.copy(alpha = 0.22f)
                            else Color.White.copy(alpha = 0.04f)
                        )
                        .border(
                            width = if (active) 2.dp else 1.dp,
                            color = if (active) bright else Color.White.copy(alpha = 0.10f),
                            shape = RoundedCornerShape(12.dp),
                        )
                        .padding(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    FoliaTemplateThumb(
                        template = tpl,
                        accent = accent,
                        bright = bright,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(66.dp)
                            .clip(RoundedCornerShape(8.dp)),
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        if (active) "$zhName ✓" else zhName,
                        color = if (active) bright else AppColors.TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun BackgroundsPane(
    settings: Settings,
    activeBackground: FoliaBackground,
    accent: Color,
    bright: Color,
    onUpdateSettings: ((Settings) -> Settings) -> Unit,
    onShuffleBackground: () -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(4.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(3) }) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TvFocusable(
                    onClick = { onUpdateSettings { it.copy(foliaDaylight = !it.foliaDaylight) } },
                    modifier = Modifier.weight(1f),
                    focusedScale = 1.02f,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (settings.foliaDaylight) accent.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.05f))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("昼/夜模式（Daylight）", color = AppColors.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Text(if (settings.foliaDaylight) "当前：浅色艺术画廊风" else "当前：深色影院沉浸风", color = AppColors.TextSecondary, fontSize = 10.sp)
                        }
                        Text(if (settings.foliaDaylight) "浅色 ☀" else "深色 ☾", color = bright, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
                TvFocusable(
                    onClick = { onUpdateSettings { it.copy(foliaBgStatic = !it.foliaBgStatic) } },
                    modifier = Modifier.weight(1f),
                    focusedScale = 1.02f,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (settings.foliaBgStatic) accent.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.05f))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("静态背景节能", color = AppColors.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Text("背景静止不动，大幅降低 GPU 负载", color = AppColors.TextSecondary, fontSize = 10.sp)
                        }
                        Text(if (settings.foliaBgStatic) "已开启" else "已关闭", color = if (settings.foliaBgStatic) bright else AppColors.TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        item(span = { GridItemSpan(3) }) {
            Text(
                "Folia 原版动态背景着色器（6 种 + 随机，与当前模板实时叠加）",
                color = AppColors.TextSecondary,
                fontSize = 12.sp,
            )
        }
        // 随机背景卡片
        item(key = "folia-random-bg") {
            val active = settings.foliaRandomBackground
            TvFocusable(
                onClick = {
                    if (active) {
                        onShuffleBackground()
                    } else {
                        onUpdateSettings {
                            it.copy(
                                lyricStyle = LyricStyle.FOLIA_WEBVIEW,
                                foliaRandomBackground = true,
                            )
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                focusedScale = 1.03f,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (active) accent.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.04f))
                        .border(
                            width = if (active) 2.dp else 1.dp,
                            color = if (active) bright else Color.White.copy(alpha = 0.10f),
                            shape = RoundedCornerShape(12.dp),
                        )
                        .padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    ShufflePreviewThumb(
                        accent = accent,
                        bright = bright,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(78.dp)
                            .clip(RoundedCornerShape(8.dp)),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (active) "随机 · ${activeBackground.label.substringBefore(" · ")}" else "随机背景",
                        color = if (active) bright else AppColors.TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                    Text(
                        "切歌自动随机切换背景",
                        color = AppColors.TextSecondary,
                        fontSize = 10.sp,
                        maxLines = 1,
                    )
                }
            }
        }
        items(FoliaBackground.entries, key = { it.id }) { bg ->
            val active = !settings.foliaRandomBackground && settings.foliaBackground == bg
            val zhName = bg.label.substringBefore(" · ")
            TvFocusable(
                onClick = {
                    onUpdateSettings {
                        it.copy(
                            lyricStyle = LyricStyle.FOLIA_WEBVIEW,
                            foliaRandomBackground = false,
                            foliaBackground = bg,
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                focusedScale = 1.03f,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (active) accent.copy(alpha = 0.22f)
                            else Color.White.copy(alpha = 0.04f)
                        )
                        .border(
                            width = if (active) 2.dp else 1.dp,
                            color = if (active) bright else Color.White.copy(alpha = 0.10f),
                            shape = RoundedCornerShape(12.dp),
                        )
                        .padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    FoliaBackgroundThumb(
                        bg = bg,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(78.dp)
                            .clip(RoundedCornerShape(8.dp)),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (active) "$zhName ✓" else zhName,
                        color = if (active) bright else AppColors.TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                    Text(
                        bg.desc,
                        color = AppColors.TextSecondary,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun AiThemePane(
    settings: Settings,
    currentMoodLabel: String?,
    accent: Color,
    bright: Color,
    onUpdateSettings: ((Settings) -> Settings) -> Unit,
    onRemixAiPalette: () -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(4.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(2) }) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TvFocusable(
                    onClick = { onUpdateSettings { it.copy(aiThemeEnabled = !it.aiThemeEnabled) } },
                    modifier = Modifier.weight(1.3f),
                    focusedScale = 1.02f,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (settings.aiThemeEnabled) accent.copy(alpha = 0.22f)
                                else Color.White.copy(alpha = 0.05f)
                            )
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "✦ AI 氛围主题引擎",
                                color = if (settings.aiThemeEnabled) bright else AppColors.TextPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                currentMoodLabel?.let { "当前识别情绪：$it" } ?: "自动识别封面主色与歌词情绪",
                                color = AppColors.TextSecondary,
                                fontSize = 11.sp,
                            )
                        }
                        Text(
                            if (settings.aiThemeEnabled) "已开启" else "已关闭",
                            color = if (settings.aiThemeEnabled) bright else AppColors.TextMuted,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                TvFocusable(
                    onClick = {
                        onUpdateSettings { it.copy(aiThemeEnabled = true, foliaAiPalette = FoliaAiPalette.AUTO) }
                        onRemixAiPalette()
                    },
                    modifier = Modifier.weight(0.7f),
                    focusedScale = 1.02f,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(58.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                Brush.horizontalGradient(
                                    listOf(accent.copy(alpha = 0.35f), bright.copy(alpha = 0.25f))
                                )
                            )
                            .border(1.dp, bright.copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "✦ AI 灵感重绘",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }

        item(span = { GridItemSpan(2) }) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.05f))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("字体风格", color = AppColors.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                com.walkman.tv.data.store.FoliaFontStyle.entries.forEach { fs ->
                    TvPill(
                        onClick = { onUpdateSettings { it.copy(foliaFontStyle = fs) } },
                        selected = settings.foliaFontStyle == fs,
                        accent = accent,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text(fs.label, fontSize = 12.sp)
                    }
                }
            }
        }

        item(span = { GridItemSpan(2) }) {
            Text(
                "AI 风格色盘（支持自动随歌，或锁定专属艺术主题）",
                color = AppColors.TextSecondary,
                fontSize = 12.sp,
            )
        }

        items(FoliaAiPalette.entries, key = { it.name }) { pal ->
            val active = settings.aiThemeEnabled && settings.foliaAiPalette == pal
            TvFocusable(
                onClick = {
                    onUpdateSettings { it.copy(aiThemeEnabled = true, foliaAiPalette = pal) }
                },
                modifier = Modifier.fillMaxWidth(),
                focusedScale = 1.03f,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (active) Color(pal.accentArgb).copy(alpha = 0.20f)
                            else Color.White.copy(alpha = 0.05f)
                        )
                        .border(
                            width = if (active) 1.5.dp else 1.dp,
                            color = if (active) Color(pal.brightArgb) else Color.White.copy(alpha = 0.08f),
                            shape = RoundedCornerShape(12.dp),
                        )
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    listOf(Color(pal.accentArgb), Color(pal.brightArgb))
                                )
                            ),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            pal.label,
                            color = if (active) Color.White else AppColors.TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            pal.desc,
                            color = AppColors.TextSecondary,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (active) {
                        Text("✓", color = Color(pal.brightArgb), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun EffectsPane(
    settings: Settings,
    accent: Color,
    bright: Color,
    onUpdateSettings: ((Settings) -> Settings) -> Unit,
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(4.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.05f))
                    .padding(14.dp),
            ) {
                Text("歌词字号缩放（Font Scale）", color = AppColors.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    com.walkman.tv.data.store.FoliaFontScale.entries.forEach { sc ->
                        TvPill(
                            onClick = { onUpdateSettings { it.copy(foliaFontScale = sc) } },
                            selected = settings.foliaFontScale == sc,
                            accent = accent,
                        ) {
                            Text(sc.label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.05f))
                    .padding(14.dp),
            ) {
                Text("动画律动强度（Animation Intensity）", color = AppColors.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    com.walkman.tv.data.store.FoliaAnimIntensity.entries.forEach { inten ->
                        TvPill(
                            onClick = { onUpdateSettings { it.copy(foliaAnimIntensity = inten) } },
                            selected = settings.foliaAnimIntensity == inten,
                            accent = accent,
                        ) {
                            Text(inten.label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
        item {
            StudioToggleItem(
                title = "智能节拍律动（Beat Reactive）",
                desc = "驱动 5 频段与 64 段频谱，画面光晕与粒子随节拍脉冲",
                checked = settings.foliaBeatReactive,
                accent = accent,
                bright = bright,
                onClick = { onUpdateSettings { it.copy(foliaBeatReactive = !it.foliaBeatReactive) } },
            )
        }
        item {
            StudioToggleItem(
                title = "底部翻译字幕条（默认关闭）",
                desc = "开启后在画面底部以透明柔光文字显示外语歌词中文翻译",
                checked = settings.foliaShowBottomSubtitle,
                accent = accent,
                bright = bright,
                onClick = { onUpdateSettings { it.copy(foliaShowBottomSubtitle = !it.foliaShowBottomSubtitle) } },
            )
        }
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.05f))
                    .padding(14.dp),
            ) {
                Text("渲染分辨率档位（流畅度 vs 清晰度）", color = AppColors.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text("电视若出现掉帧，切到「0.5× 性能优先」可大幅提升帧率", color = AppColors.TextSecondary, fontSize = 11.sp)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FoliaRenderQuality.entries.forEach { q ->
                        TvPill(
                            onClick = { onUpdateSettings { it.copy(foliaRenderQuality = q) } },
                            selected = settings.foliaRenderQuality == q,
                            accent = accent,
                        ) {
                            Text(q.label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StudioToggleItem(
    title: String,
    desc: String,
    checked: Boolean,
    accent: Color,
    bright: Color,
    onClick: () -> Unit,
) {
    TvFocusable(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        focusedScale = 1.02f,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(if (checked) accent.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.05f))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    color = if (checked) bright else AppColors.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(2.dp))
                Text(desc, color = AppColors.TextSecondary, fontSize = 11.sp)
            }
            Text(
                if (checked) "已开启" else "已关闭",
                color = if (checked) bright else AppColors.TextMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
