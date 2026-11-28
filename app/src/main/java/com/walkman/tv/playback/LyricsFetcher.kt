package com.walkman.tv.playback

import com.walkman.tv.data.model.Track
import com.walkman.tv.source.OtherSourceFinder
import com.walkman.tv.source.SourceManager
import com.walkman.tv.source.builtin.BuiltInLyricResolver
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Fetches lyrics: user script → direct platform → cross-source match → universal keyword fallback
 * (NetEase / Kugou / LRCLIB). Only caches usable timed lyrics so transient failures can retry.
 */
class LyricsFetcher(
    private val sources: SourceManager,
    private val builtIn: BuiltInLyricResolver,
    private val otherSourceFinder: OtherSourceFinder,
) {
    private val cache = ConcurrentHashMap<String, List<LyricLine>>()

    suspend fun fetch(track: Track): List<LyricLine> {
        cache[track.id]?.let { return it }

        var untimedFallback: List<LyricLine>? = null
        fun accept(lines: List<LyricLine>?): List<LyricLine>? {
            if (lines.isNullOrEmpty()) return null
            if (hasTimedLyrics(lines)) {
                cache[track.id] = lines
                return lines
            }
            if (untimedFallback == null && lines.any { it.text.isNotBlank() }) {
                untimedFallback = lines
            }
            return null
        }

        accept(tryScript(track))?.let { return it }
        accept(runCatching { builtIn.fetch(track) }.getOrNull())?.let { return it }
        accept(tryOtherSources(track))?.let { return it }
        accept(runCatching { builtIn.fetchByKeyword(track.name, track.singer, track.duration) }.getOrNull())?.let {
            return it
        }

        return untimedFallback ?: emptyList()
    }

    private fun hasTimedLyrics(lines: List<LyricLine>): Boolean {
        val timed = lines.filter { it.time >= 0.0 && it.text.isNotBlank() }
        if (timed.size >= 2) return true
        // Reject single-line placeholders like "暂无歌词" so we keep searching other platforms.
        if (timed.size == 1) {
            val t = timed[0].text
            if (t.contains("暂无歌词") || t.contains("无歌词")) return false
            return true
        }
        return false
    }

    private suspend fun tryScript(track: Track): List<LyricLine>? {
        val result = runCatching { sources.requestLyric(track) }.getOrNull() ?: return null
        val lyric = result.optString("lyric")
        val tlyric = result.optString("tlyric").ifEmpty { null }
        return LyricParser.parse(lyric, tlyric).ifEmpty { null }
    }

    private suspend fun tryOtherSources(track: Track): List<LyricLine>? {
        val candidates = runCatching { otherSourceFinder.findMatches(track, maxIntervalDiff = 20) }
            .getOrDefault(emptyList())
        for (alt in candidates.take(8)) {
            val d1 = track.duration
            val d2 = alt.duration
            if (d1 != null && d2 != null && d1 > 0 && d2 > 0 && abs(d1 - d2) > 20) continue
            runCatching { builtIn.fetch(alt) }.getOrNull()?.takeIf { hasTimedLyrics(it) }?.let { return it }
            tryScript(alt)?.takeIf { hasTimedLyrics(it) }?.let { return it }
        }
        return null
    }

    fun clear() = cache.clear()
}
