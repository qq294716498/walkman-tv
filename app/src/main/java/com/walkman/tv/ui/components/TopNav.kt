package com.walkman.tv.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.walkman.tv.R
import com.walkman.tv.ui.NavSection
import com.walkman.tv.ui.theme.AppColors

/**
 * Top navigation bar — brand on left, six nav items centered (4 sections + search + settings),
 * now-playing chip on right. Active item shows green text (no pill); focus state shows a
 * green pill background and slight scale (D-pad highlight).
 */
@Composable
fun TopNav(
    active: NavSection,
    onSelect: (NavSection) -> Unit,
    modifier: Modifier = Modifier,
    recommendFocusRequester: FocusRequester? = null,
    nowPlayingTitle: String? = null,
    nowPlayingPicURL: String? = null,
    nowPlayingIsPlaying: Boolean = false,
    onOpenPlayer: () -> Unit = {},
) {
    Box(modifier = modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 12.dp)) {
        // Left: brand
        Brand(modifier = Modifier.align(Alignment.CenterStart))

        // Center: 7 nav items ("登录歌单" on the left of "推荐"; default landing remains "推荐")
        Row(
            modifier = Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NavItem("登录歌单", active == NavSection.CloudLibrary) { onSelect(NavSection.CloudLibrary) }
            NavItem("推荐", active == NavSection.Recommend, focusRequester = recommendFocusRequester) {
                onSelect(NavSection.Recommend)
            }
            NavItem("排行榜", active == NavSection.Leaderboard) { onSelect(NavSection.Leaderboard) }
            NavItem("歌单", active == NavSection.Songlist) { onSelect(NavSection.Songlist) }
            NavItem("我的列表", active == NavSection.Library) { onSelect(NavSection.Library) }
            NavItem("搜索", active == NavSection.Search, icon = Icons.Filled.Search) { onSelect(NavSection.Search) }
            NavItemIconOnly(Icons.Filled.Settings, active == NavSection.Settings, contentDescription = "设置") {
                onSelect(NavSection.Settings)
            }
        }

        // Right: now-playing chip (only when a track is loaded)
        if (!nowPlayingTitle.isNullOrEmpty()) {
            NowPlayingChip(
                title = nowPlayingTitle,
                picURL = nowPlayingPicURL,
                isPlaying = nowPlayingIsPlaying,
                onClick = onOpenPlayer,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }
}

@Composable
private fun Brand(modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Image(
            painter = painterResource(R.drawable.haoting_icon),
            contentDescription = null,
            modifier = Modifier.size(26.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "好听-TV",
            color = AppColors.TextPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun NavItem(
    label: String,
    selected: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(50)
    Surface(
        onClick = onClick,
        modifier = (if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier).tvTouch(onClick),
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = AppColors.AccentGreen,
            pressedContainerColor = AppColors.AccentGreen,
            contentColor = if (selected) AppColors.AccentGreen else AppColors.TextPrimary,
            focusedContentColor = AppColors.BgDeep,
            pressedContentColor = AppColors.BgDeep,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(0.dp, Color.Transparent), shape = shape),
            focusedBorder = Border(BorderStroke(2.dp, AppColors.AccentGreen), shape = shape),
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(5.dp))
            }
            Text(
                text = label,
                fontSize = 15.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun NavItemIconOnly(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.tvTouch(onClick),
        shape = ClickableSurfaceDefaults.shape(shape = CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = AppColors.AccentGreen,
            pressedContainerColor = AppColors.AccentGreen,
            contentColor = if (selected) AppColors.AccentGreen else AppColors.TextPrimary,
            focusedContentColor = AppColors.BgDeep,
            pressedContentColor = AppColors.BgDeep,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(0.dp, Color.Transparent), shape = CircleShape),
            focusedBorder = Border(BorderStroke(2.dp, AppColors.AccentGreen), shape = CircleShape),
        ),
    ) {
        Box(modifier = Modifier.padding(10.dp), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun NowPlayingChip(
    title: String,
    picURL: String?,
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rotation = remember { Animatable(0f) }
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            while (true) {
                rotation.animateTo(
                    rotation.value + 360f,
                    animationSpec = tween(durationMillis = 6000, easing = LinearEasing),
                )
            }
        }
    }
    var focused by remember { androidx.compose.runtime.mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    androidx.tv.material3.Surface(
        onClick = onClick,
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .tvTouch(onClick),
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(shape = shape),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = AppColors.Card.copy(alpha = 0.72f),
            focusedContainerColor = AppColors.AccentGreen,
            pressedContainerColor = AppColors.AccentGreen,
            contentColor = AppColors.TextPrimary,
            focusedContentColor = AppColors.BgDeep,
            pressedContentColor = AppColors.BgDeep,
        ),
        scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
        border = androidx.tv.material3.ClickableSurfaceDefaults.border(
            border = androidx.tv.material3.Border(
                BorderStroke(1.dp, AppColors.AccentGreen.copy(alpha = 0.35f)),
                shape = shape,
            ),
            focusedBorder = androidx.tv.material3.Border(
                BorderStroke(2.dp, AppColors.Focus),
                shape = shape,
            ),
        ),
    ) {
        Row(
            modifier = Modifier.padding(start = 6.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(if (focused) AppColors.BgDeep.copy(alpha = 0.2f) else AppColors.AccentGreen.copy(alpha = 0.25f)),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .graphicsLayer { rotationZ = rotation.value }
                        .clip(CircleShape),
                ) {
                    com.walkman.tv.ui.components.Artwork(
                        picURL,
                        modifier = Modifier.size(24.dp),
                        shape = CircleShape,
                        decodeSizePx = 64,
                    )
                }
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(Color.Black),
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.width(108.dp)) {
                Text(
                    text = if (isPlaying) "正在播放" else "已暂停",
                    color = if (focused) AppColors.BgDeep.copy(alpha = 0.75f) else AppColors.AccentGreen,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
                Text(
                    text = title,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(6.dp))
            if (isPlaying) {
                MiniWaveform(
                    modifier = Modifier.size(width = 16.dp, height = 14.dp),
                    color = if (focused) AppColors.BgDeep else AppColors.AccentGreen,
                )
            } else {
                Icon(
                    imageVector = androidx.compose.material.icons.Icons.Filled.PlayArrow,
                    contentDescription = "继续播放",
                    tint = if (focused) AppColors.BgDeep else AppColors.TextSecondary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}
