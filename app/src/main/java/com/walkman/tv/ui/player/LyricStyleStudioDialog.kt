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
    currentMoodLabel: String?,
    currentAccent: Color,
    currentBright: Color,
    onUpdateSettings: ((Settings) -> Settings) -> Unit,
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
                                LyricStyle.FOLIA_WEBVIEW -> "Folia · ${settings.foliaTemplate.label}"
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
                            accent = currentAccent,
                            bright = currentBright,
                            onUpdateSettings = onUpdateSettings,
                        )
                        StudioTab.BACKGROUNDS -> BackgroundsPane(
                            settings = settings,
                            accent = currentAccent,
                            bright = currentBright,
                            onUpdateSettings = onUpdateSettings,
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
    accent: Color,
    bright: Color,
    onUpdateSettings: ((Settings) -> Settings) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(4.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(3) }) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                listOf(
                    LyricStyle.CLASSIC to "居中简洁 · 保留黑胶转盘",
                    LyricStyle.APPLE to "60fps 像素扫光 · 保留黑胶转盘",
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
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
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

        item(span = { GridItemSpan(3) }) {
            Text(
                "Folia 动态舞台模板（18 款 · 全屏沉浸无转盘）",
                color = AppColors.TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
            )
        }

        items(FoliaTemplate.entries, key = { it.id }) { tpl ->
            val active = settings.lyricStyle == LyricStyle.FOLIA_WEBVIEW && settings.foliaTemplate == tpl
            val parts = tpl.label.split(" · ")
            val zhName = parts.firstOrNull() ?: tpl.label
            val enName = parts.getOrNull(1) ?: tpl.id
            TvFocusable(
                onClick = {
                    onUpdateSettings {
                        it.copy(
                            lyricStyle = LyricStyle.FOLIA_WEBVIEW,
                            foliaTemplate = tpl,
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                focusedScale = 1.03f,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(82.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (active) accent.copy(alpha = 0.24f)
                            else Color.White.copy(alpha = 0.05f)
                        )
                        .border(
                            width = if (active) 1.5.dp else 1.dp,
                            color = if (active) bright else Color.White.copy(alpha = 0.08f),
                            shape = RoundedCornerShape(12.dp),
                        )
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            zhName,
                            color = if (active) Color.White else AppColors.TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            if (active) "✓" else enName,
                            color = if (active) bright else AppColors.TextMuted,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Text(
                        tpl.desc,
                        color = if (active) Color.White.copy(alpha = 0.85f) else AppColors.TextSecondary,
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                        maxLines = 2,
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
    accent: Color,
    bright: Color,
    onUpdateSettings: ((Settings) -> Settings) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(4.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(2) }) {
            Text(
                "选择 Folia 舞台动态背景（共 6 种，与当前模板实时叠加）",
                color = AppColors.TextSecondary,
                fontSize = 12.sp,
            )
        }
        items(FoliaBackground.entries, key = { it.id }) { bg ->
            val active = settings.foliaBackground == bg
            TvFocusable(
                onClick = {
                    onUpdateSettings {
                        it.copy(
                            lyricStyle = LyricStyle.FOLIA_WEBVIEW,
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
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            if (active) accent.copy(alpha = 0.22f)
                            else Color.White.copy(alpha = 0.05f)
                        )
                        .border(
                            width = if (active) 1.5.dp else 1.dp,
                            color = if (active) bright else Color.White.copy(alpha = 0.08f),
                            shape = RoundedCornerShape(14.dp),
                        )
                        .padding(12.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(26.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                Brush.horizontalGradient(
                                    listOf(Color(bg.previewColorA), Color(bg.previewColorB))
                                )
                            ),
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            bg.label,
                            color = if (active) Color.White else AppColors.TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                        )
                        if (active) {
                            Text("当前 ✓", color = bright, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(
                        bg.desc,
                        color = AppColors.TextSecondary,
                        fontSize = 11.sp,
                        maxLines = 2,
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
            Text(
                "AI 风格配色方案（支持自动随歌，或锁定专属艺术色盘）",
                color = AppColors.TextSecondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 2.dp),
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
            StudioToggleItem(
                title = "智能节拍律动（Beat Reactive）",
                desc = "背景光晕、镜台缩放与粒子随音乐实时能量律动",
                checked = settings.foliaBeatReactive,
                accent = accent,
                bright = bright,
                onClick = { onUpdateSettings { it.copy(foliaBeatReactive = !it.foliaBeatReactive) } },
            )
        }
        item {
            StudioToggleItem(
                title = "歌词辉光特效（Word Glow）",
                desc = "为当前句开启柔光弥散与体积光效，关闭可减轻 GPU 负担",
                checked = settings.foliaGlowEnabled,
                accent = accent,
                bright = bright,
                onClick = { onUpdateSettings { it.copy(foliaGlowEnabled = !it.foliaGlowEnabled) } },
            )
        }
        item {
            StudioToggleItem(
                title = "电影胶片质感（Film Grain）",
                desc = "在流体极光着色器上叠加细微胶片颗粒与暗角",
                checked = settings.foliaFilmGrain,
                accent = accent,
                bright = bright,
                onClick = { onUpdateSettings { it.copy(foliaFilmGrain = !it.foliaFilmGrain) } },
            )
        }
        item {
            StudioToggleItem(
                title = "显示外语歌词翻译",
                desc = "在当前歌词下方同步显示中文翻译",
                checked = settings.showLyricTranslation,
                accent = accent,
                bright = bright,
                onClick = { onUpdateSettings { it.copy(showLyricTranslation = !it.showLyricTranslation) } },
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
                Text("渲染画质档位（流畅度 vs 清晰度）", color = AppColors.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text("若电视出现掉帧，切换为「性能优先」可大幅提升帧率", color = AppColors.TextSecondary, fontSize = 11.sp)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FoliaRenderQuality.entries.forEach { q ->
                        TvPill(
                            onClick = { onUpdateSettings { it.copy(foliaRenderQuality = q) } },
                            selected = settings.foliaRenderQuality == q,
                            accent = accent,
                        ) {
                            Text(q.label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
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
