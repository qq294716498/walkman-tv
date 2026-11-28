package com.walkman.tv.ui.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.drawable.BitmapDrawable
import androidx.compose.ui.graphics.Color
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.walkman.tv.data.model.Track
import com.walkman.tv.playback.LyricLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Lightweight on-device "AI / Smart Ambient Theme" generator for Android TV:
 * combines fast cover-art HSV palette extraction (24x24 downsample, <3ms on IO/Default)
 * with lyric + title mood analysis to produce a cohesive dark-mode color theme per song.
 */
data class SongTheme(
    val accent: Color,
    val bright: Color,
    val orbPrimary: Color,
    val orbSecondary: Color,
    val moodLabel: String,
)

object SongThemeEngine {
    private val cache = ConcurrentHashMap<String, SongTheme>()

    private data class MoodProfile(
        val label: String,
        val fallbackHue: Float,
        val secondaryShift: Float,
        val keywords: List<String>,
    )

    private val moods = listOf(
        MoodProfile("炽热 · 燃", 352f, 34f, listOf("燃", "火", "热", "狂", "怒", "摇滚", "rock", "fire", "burn", "fight", "野", "炸", "电音", "舞")),
        MoodProfile("夜色 · 深邃", 225f, 45f, listOf("夜", "黑", "梦", "星", "月", "孤", "独", "寂", "寞", "暗", "night", "moon", "star", "dream", "dark", "蓝")),
        MoodProfile("微雨 · 忧郁", 202f, -26f, listOf("雨", "泪", "哭", "痛", "别", "离", "伤", "忘", "散", "冷", "雪", "rain", "tear", "cry", "sad", "miss", "灰")),
        MoodProfile("清风 · 治愈", 162f, 32f, listOf("风", "海", "晴", "天", "春", "夏", "花", "笑", "甜", "暖", "光", "爱", "喜", "love", "sun", "wind", "sea", "sky", "happy")),
        MoodProfile("琥珀 · 复古", 36f, -22f, listOf("旧", "老", "年", "秋", "酒", "咖啡", "爵士", "慢", "时光", "岁月", "回忆", "jazz", "time", "gold", "vintage")),
        MoodProfile("微醺 · 迷幻", 278f, 38f, listOf("霓虹", "迷", "醉", "幻", "紫", "玫瑰", "吻", "心动", "neon", "rose", "kiss", "vibe", "chill")),
    )

    suspend fun resolve(
        context: Context,
        track: Track,
        lyrics: List<LyricLine>,
    ): SongTheme = withContext(Dispatchers.Default) {
        val cacheKey = "${track.id}:${lyrics.isNotEmpty()}"
        cache[cacheKey]?.let { return@withContext it }

        val mood = detectMood(track, lyrics)
        val coverHue = extractVibrantHue(context, track.picURL)

        // Blend cover hue (when vibrant) with mood profile for a cohesive, TV-legible dark palette.
        val primaryHue = coverHue ?: mood.fallbackHue
        val secondaryHue = (primaryHue + mood.secondaryShift + 360f) % 360f

        val accent = hslColor(primaryHue, saturation = 0.78f, lightness = 0.64f)
        val bright = hslColor(primaryHue, saturation = 0.85f, lightness = 0.78f)
        val orbPrimary = hslColor(primaryHue, saturation = 0.72f, lightness = 0.44f)
        val orbSecondary = hslColor(secondaryHue, saturation = 0.68f, lightness = 0.36f)

        val theme = SongTheme(
            accent = accent,
            bright = bright,
            orbPrimary = orbPrimary,
            orbSecondary = orbSecondary,
            moodLabel = mood.label,
        )
        if (cache.size > 64) cache.clear()
        cache[cacheKey] = theme
        theme
    }

    /** Generates a fresh harmonious dark-mode AI palette variation on demand ("AI 灵感重绘"). */
    fun remix(seed: Int): SongTheme {
        val mood = moods[abs(seed) % moods.size]
        val primaryHue = ((seed * 137.508f) % 360f + 360f) % 360f
        val secondaryHue = (primaryHue + 42f + (seed % 3) * 18f) % 360f
        return SongTheme(
            accent = hslColor(primaryHue, saturation = 0.80f, lightness = 0.64f),
            bright = hslColor(primaryHue, saturation = 0.86f, lightness = 0.78f),
            orbPrimary = hslColor(primaryHue, saturation = 0.72f, lightness = 0.42f),
            orbSecondary = hslColor(secondaryHue, saturation = 0.68f, lightness = 0.34f),
            moodLabel = "${mood.label} · 灵感 #${abs(seed % 99) + 1}",
        )
    }

    private fun detectMood(track: Track, lyrics: List<LyricLine>): MoodProfile {
        val corpus = buildString {
            append(track.name.lowercase())
            append(' ')
            append(track.singer.lowercase())
            append(' ')
            // Sample first 18 lyric lines — plenty to infer mood without scanning huge strings.
            lyrics.take(18).forEach {
                append(it.text.lowercase())
                append(' ')
            }
        }
        var best: MoodProfile? = null
        var bestScore = 0
        for (m in moods) {
            var score = 0
            for (kw in m.keywords) {
                if (corpus.contains(kw)) score++
            }
            if (score > bestScore) {
                bestScore = score
                best = m
            }
        }
        return best ?: MoodProfile("极光 · 氛围", 198f, 42f, emptyList())
    }

    private suspend fun extractVibrantHue(context: Context, picURL: String?): Float? {
        if (picURL.isNullOrBlank()) return null
        return runCatching {
            val request = ImageRequest.Builder(context)
                .data(picURL)
                .size(24, 24)
                .allowHardware(false)
                .build()
            val result = context.imageLoader.execute(request) as? SuccessResult ?: return null
            val bmp = (result.drawable as? BitmapDrawable)?.bitmap ?: return null
            dominantVibrantHue(bmp)
        }.getOrNull()
    }

    private fun dominantVibrantHue(bitmap: Bitmap): Float? {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return null
        val buckets = FloatArray(12) // 30-degree hue buckets
        val hueSum = FloatArray(12)
        val hsv = FloatArray(3)
        val stepX = (w / 12).coerceAtLeast(1)
        val stepY = (h / 12).coerceAtLeast(1)
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val pixel = bitmap.getPixel(x, y)
                AndroidColor.colorToHSV(pixel, hsv)
                val sat = hsv[1]
                val value = hsv[2]
                // Ignore near-gray, pure black, or blown-out white pixels.
                if (sat >= 0.22f && value in 0.18f..0.95f) {
                    val weight = sat * sat * (1f - abs(value - 0.58f))
                    val idx = ((hsv[0] / 30f).toInt()).coerceIn(0, 11)
                    buckets[idx] += weight
                    hueSum[idx] += hsv[0] * weight
                }
                x += stepX
            }
            y += stepY
        }
        var bestIdx = -1
        var bestWeight = 0f
        for (i in buckets.indices) {
            if (buckets[i] > bestWeight) {
                bestWeight = buckets[i]
                bestIdx = i
            }
        }
        if (bestIdx < 0 || bestWeight < 0.05f) return null
        return (hueSum[bestIdx] / bestWeight) % 360f
    }

    private fun hslColor(hue: Float, saturation: Float, lightness: Float): Color {
        val c = (1f - abs(2f * lightness - 1f)) * saturation
        val x = c * (1f - abs((hue / 60f) % 2f - 1f))
        val m = lightness - c / 2f
        val (r1, g1, b1) = when {
            hue < 60f -> Triple(c, x, 0f)
            hue < 120f -> Triple(x, c, 0f)
            hue < 180f -> Triple(0f, c, x)
            hue < 240f -> Triple(0f, x, c)
            hue < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return Color(
            red = (r1 + m).coerceIn(0f, 1f),
            green = (g1 + m).coerceIn(0f, 1f),
            blue = (b1 + m).coerceIn(0f, 1f),
            alpha = 1f,
        )
    }
}
