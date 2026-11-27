package com.walkman.tv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import com.walkman.tv.App
import com.walkman.tv.data.model.Track
import com.walkman.tv.di.AppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Convenience accessor for the app-wide container from composables. */
val appContainer: AppContainer get() = App.container

/**
 * Whether the current subtree is visible on top (not obscured by a full-screen overlay like
 * PlayerScreen). Animated canvases (Waveform, MiniWaveform) pause their frame tickers when false.
 */
val LocalScreenActive = staticCompositionLocalOf { true }

/**
 * Subscribe only to the current playing track's ID so list screens don't recompose at 1Hz
 * whenever `PlaybackState.positionMs` ticks.
 */
@Composable
fun rememberCurrentTrackId(): String? {
    val flow = remember {
        appContainer.playbackController.state
            .map { it.currentTrack?.id }
            .distinctUntilChanged()
    }
    val id by flow.collectAsState(initial = appContainer.playbackController.state.value.currentTrack?.id)
    return id
}

/**
 * Subscribe only to `PlaybackState.isPlaying` so callers don't recompose on every 1Hz position tick.
 */
@Composable
fun rememberIsPlaying(): Boolean {
    val flow = remember {
        appContainer.playbackController.state
            .map { it.isPlaying }
            .distinctUntilChanged()
    }
    val playing by flow.collectAsState(initial = appContainer.playbackController.state.value.isPlaying)
    return playing
}

/**
 * Run a suspend fetch, mapping failures to [default] — but letting CancellationException
 * propagate. Inside a LaunchedEffect, a plain `runCatching` swallows the cancellation thrown
 * when the effect restarts on a key change; the doomed old coroutine then resumes past the
 * catch and stomps the state its replacement just wrote (songlist page showing 暂无歌单 on
 * first entry: the restarted fetch reused the warm connection and finished *before* the
 * cancelled one's tail ran). Always use this instead of runCatching around fetches that
 * write composition state.
 */
suspend fun <T> fetchOr(default: T, block: suspend () -> T): T = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    default
}

/**
 * Start playing [tracks] at [index] — unless the track at that index is already the current
 * track, in which case we leave the player alone (so re-tapping the now-playing row in any
 * list opens the full-screen player without restarting playback from 00:00).
 */
fun playList(tracks: List<Track>, index: Int, title: String? = null) {
    if (tracks.isEmpty()) return
    val target = tracks.getOrNull(index) ?: return
    val current = appContainer.playbackController.state.value.currentTrack
    if (current?.id == target.id) return
    appContainer.playbackController.setQueue(tracks, index, autoPlay = true, queueTitle = title)
}
