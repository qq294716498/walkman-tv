package com.walkman.tv.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode as AnimRepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.sin
import androidx.tv.material3.Text
import com.walkman.tv.data.model.Track
import com.walkman.tv.ui.theme.AppColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One focusable track row: index, cover, name/singer, quality badge + source chip.
 *  [downloaded] shows a small green ✓ download icon — opt-in, only set by 我的列表.
 *  [onDownload] / [onAddToPlaylist] / [onRemove] add always-visible icon buttons at the row end (D-pad Right
 *  from the row reaches them); [modifier] applies to the main (play) focusable. */
@Composable
fun TrackRow(
    track: Track,
    index: Int,
    modifier: Modifier = Modifier,
    nowPlaying: Boolean = false,
    downloaded: Boolean = false,
    onDownload: (() -> Unit)? = null,
    onAddToPlaylist: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
    removeLabel: String = "删除",
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TvFocusable(onClick = onClick, modifier = modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.width(28.dp), contentAlignment = Alignment.Center) {
                    if (nowPlaying) {
                        MiniWaveform(modifier = Modifier.size(width = 22.dp, height = 18.dp))
                    } else {
                        Text(
                            "${index + 1}",
                            color = AppColors.TextMuted,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                Artwork(track.picURL, modifier = Modifier.size(44.dp))
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        track.name,
                        color = if (nowPlaying) AppColors.AccentGreen else AppColors.TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        track.subtitle,
                        color = AppColors.TextSecondary,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                if (downloaded) {
                    androidx.tv.material3.Icon(
                        imageVector = Icons.Filled.Download,
                        contentDescription = "已下载",
                        tint = AppColors.AccentGreen,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                if (track.hasMv) {
                    MvBadge()
                    Spacer(Modifier.width(6.dp))
                }
                QualityBadge(track.qualities)
                Spacer(Modifier.width(8.dp))
                SourceChip(track.source)
            }
        }
        if (onDownload != null || onAddToPlaylist != null || onRemove != null) {
            // Tight cluster: the buttons sit closer to each other than to the row.
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                onDownload?.let { RowAction(Icons.Filled.Download, "下载", it) }
                onAddToPlaylist?.let { RowAction(Icons.AutoMirrored.Filled.PlaylistAdd, "添加到歌单", it) }
                onRemove?.let { RowAction(Icons.Filled.DeleteOutline, removeLabel, it, danger = true) }
            }
        }
    }
}

/** Small icon button at a track row's end. Dimmed until focused so a column of them stays quiet. */
@Composable
private fun RowAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    danger: Boolean = false,
) {
    var focused by remember { androidx.compose.runtime.mutableStateOf(false) }
    TvPill(
        onClick = onClick,
        modifier = Modifier.onFocusChanged { focused = it.isFocused },
        shape = androidx.compose.foundation.shape.CircleShape,
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        accent = if (danger) AppColors.Danger else AppColors.BrandPrimary,
        contentPadding = PaddingValues(8.dp),
    ) {
        androidx.tv.material3.Icon(
            imageVector = icon,
            contentDescription = label,
            tint = androidx.tv.material3.LocalContentColor.current.copy(alpha = if (focused) 1f else 0.5f),
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * Tiny 6-bar animated equaliser used as the "now playing" indicator in list rows.
 * Each bar runs its own sine phase so they don't move in lockstep.
 *
 * Public so other surfaces (e.g. the player queue drawer) can reuse the same indicator.
 */
@Composable
internal fun MiniWaveform(modifier: Modifier = Modifier) {
    val bars = 6
    val transition = rememberInfiniteTransition(label = "row-eq")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = AnimRepeatMode.Restart,
        ),
        label = "row-eq-phase",
    )
    Canvas(modifier = modifier) {
        val totalW = size.width
        val barWidth = totalW / bars * 0.55f
        val gap = (totalW - barWidth * bars) / (bars - 1).coerceAtLeast(1)
        val centerY = size.height / 2
        val maxH = size.height * 0.95f
        val minH = size.height * 0.25f
        for (i in 0 until bars) {
            val x = i * (barWidth + gap)
            val v = ((sin(phase * (1f + (i % 3) * 0.55f) + i * 0.7f) + 1f) / 2f)
            val h = (minH + (maxH - minH) * v).coerceAtLeast(2.dp.toPx())
            drawRoundRect(
                color = AppColors.AccentGreen,
                topLeft = Offset(x, centerY - h / 2),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(barWidth / 2, barWidth / 2),
            )
        }
    }
}

@Composable
private fun MvBadge() {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(AppColors.AccentGreen.copy(alpha = 0.18f))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Text("MV", color = AppColors.AccentGreen, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * A focusable list of tracks. Back-key behavior: when the list has been scrolled away from
 * the top, the first press scrolls back to row 0 and refocuses it; only when already at the
 * top does back fall through to the parent BackHandler (which navigates out one layer).
 *
 * Uses `Modifier.focusRestorer()` on the LazyColumn so the previously-focused row regains
 * focus when the user returns from the player overlay (or any other modal that took focus).
 * If [initialFocus] is true the list grabs focus on its first row on first composition —
 * used by overlays (songlist detail) where the list appears on top of something focused.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun TrackList(
    tracks: List<Track>,
    modifier: Modifier = Modifier,
    nowPlayingId: String? = null,
    contentPadding: PaddingValues = PaddingValues(vertical = 8.dp),
    initialFocus: Boolean = false,
    showDownloadedBadge: Boolean = false,
    /** Non-null ⇒ every row gets a delete button (从歌单移除 / 删除播放历史 …). */
    onRemove: ((Track) -> Unit)? = null,
    removeLabel: String = "删除",
    onPlay: (index: Int) -> Unit,
) {
    // Collect once here (shared StateFlows) and derive the set of tracks that are downloaded AND
    // whose file is actually present, so rows don't each subscribe. Reactive: a finished download,
    // a removed download, or a play-time "文件缺失" discovery all update it. Used to hide the row's
    // 下载 button everywhere; the ✓ badge only where [showDownloadedBadge] (我的列表) opts in.
    val records by com.walkman.tv.ui.appContainer.downloadStore.records.collectAsState()
    val missing by com.walkman.tv.ui.appContainer.downloadStore.missingDownloads.collectAsState()
    val completedIds: Set<String> = remember(records, missing) {
        records.values
            .filter { it.status == com.walkman.tv.data.model.DownloadStatus.COMPLETED }
            .map { it.track.id }
            .filter { it !in missing }
            .toSet()
    }
    val listState = rememberLazyListState()
    val firstFocus = remember { FocusRequester() }
    // Holds a FocusRequester for the row most recently clicked-to-play. When the player
    // overlay closes we re-request focus on this row so the user returns to the same line
    // they launched playback from.
    val lastClickedFocus = remember { FocusRequester() }
    var lastClickedIndex by remember { androidx.compose.runtime.mutableStateOf(-1) }
    val scope = rememberCoroutineScope()
    val atTop by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
        }
    }

    BackHandler(enabled = !atTop) {
        scope.launch {
            listState.animateScrollToItem(0)
            // Wait a frame so item 0 is composed/visible before requesting focus.
            delay(80)
            runCatching { firstFocus.requestFocus() }
        }
    }

    if (initialFocus) {
        LaunchedEffect(tracks.firstOrNull()?.id) {
            // Wait for item 0 to compose before requesting focus.
            delay(80)
            runCatching { firstFocus.requestFocus() }
        }
    }

    // Restore focus to the most-recently clicked row when the player overlay closes.
    LaunchedEffect(Unit) {
        com.walkman.tv.ui.appContainer.events.playerClosed.collect {
            if (lastClickedIndex >= 0) {
                // Scroll it into view, wait for compose, then request focus.
                runCatching { listState.animateScrollToItem(lastClickedIndex.coerceAtMost(tracks.size - 1)) }
                repeat(5) {
                    delay(60)
                    if (runCatching { lastClickedFocus.requestFocus() }.isSuccess) return@collect
                }
            }
        }
    }

    // Row-end 下载 / ＋歌单 buttons open their dialogs. Owned here so every TrackList consumer
    // (search / library / songlist / leaderboard / recommend) gets them.
    var pickerTrack by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Track?>(null) }
    var downloadTrack by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Track?>(null) }

    val rowKeys = remember(tracks) { uniqueKeys(tracks.map { it.id }) }

    LazyColumn(
        state = listState,
        // focusRestorer() remembers which row was last focused; when the player overlay closes
        // and focus drops back into the list, it lands on that same row (instead of resetting).
        modifier = modifier.focusRestorer(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = contentPadding,
    ) {
        itemsIndexed(tracks, key = { i, _ -> rowKeys[i] }) { index, track ->
            // Two FocusRequesters can collide on the same row (row 0 == lastClicked). Compose
            // doesn't error, but the later-applied one wins; layering them with .then() makes
            // the priority explicit (lastClicked > firstFocus).
            val rowModifier = when {
                index == lastClickedIndex && index == 0 ->
                    Modifier.focusRequester(lastClickedFocus).focusRequester(firstFocus)
                index == lastClickedIndex -> Modifier.focusRequester(lastClickedFocus)
                index == 0 -> Modifier.focusRequester(firstFocus)
                else -> Modifier
            }
            TrackRow(
                track = track,
                index = index,
                modifier = rowModifier,
                nowPlaying = track.id == nowPlayingId,
                downloaded = showDownloadedBadge && track.id in completedIds,
                onDownload = if (track.canDownload && track.id !in completedIds) ({ downloadTrack = track }) else null,
                onAddToPlaylist = { pickerTrack = track },
                onRemove = onRemove?.let { remove -> { remove(track) } },
                removeLabel = removeLabel,
                onClick = {
                    lastClickedIndex = index
                    onPlay(index)
                },
            )
        }
    }

    pickerTrack?.let { t ->
        com.walkman.tv.ui.components.PlaylistPickerDialog(track = t, onDismiss = { pickerTrack = null })
    }
    downloadTrack?.let { t ->
        DownloadDialog(track = t, onDismiss = { downloadTrack = null })
    }
}

/** Local-folder tracks are already files on the device — no 下载 button for them. */
internal val Track.canDownload: Boolean
    get() = !songmid.startsWith("lf://")
