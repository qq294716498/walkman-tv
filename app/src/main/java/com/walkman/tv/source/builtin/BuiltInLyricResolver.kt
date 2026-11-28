package com.walkman.tv.source.builtin

import android.util.Base64
import com.walkman.tv.data.model.SourceID
import com.walkman.tv.data.model.Track
import com.walkman.tv.playback.LyricLine
import com.walkman.tv.playback.LyricParser
import com.walkman.tv.source.catalog.CatalogHttp
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Direct lyric fetchers per platform + universal keyword fallbacks (Kugou / NetEase / LRCLIB).
 */
class BuiltInLyricResolver(
    private val http: CatalogHttp,
    private val qishui: com.walkman.tv.cloud.QishuiAccount? = null,
) {

    private val ua = mapOf("User-Agent" to "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X)")

    suspend fun fetch(track: Track): List<LyricLine>? = when (track.source) {
        SourceID.QS -> qishui?.fetchLyrics(track.songmid)
        SourceID.KW -> kuwo(track.songmid)
        SourceID.TX -> qq(track.songmid)
        SourceID.WY -> netease(track.songmid)
        SourceID.KG -> kugou(
            name = track.name,
            singer = track.singer,
            hash = track.extras["hash"]?.takeIf { isMd5Hash(it) } ?: track.songmid.takeIf { isMd5Hash(it) },
            durationMs = (track.duration ?: 0) * 1000,
        )
        else -> null
    }

    /**
     * Universal keyword-based lyric fallback (works for ANY platform/local/imported track).
     * Queries Kugou Lyric Search, NetEase Search+Lyric, and LRCLIB in parallel and returns the
     * first valid timed lyric (preferring one with translation or more timed lines).
     */
    suspend fun fetchByKeyword(
        name: String,
        singer: String,
        durationSec: Int? = null,
    ): List<LyricLine>? {
        val cleanName = cleanTitle(name)
        val cleanSinger = cleanSingerName(singer)
        if (cleanName.isBlank()) return null

        return coroutineScope {
            val kgTask = async {
                runCatching {
                    kugou(
                        name = cleanName,
                        singer = cleanSinger,
                        hash = null,
                        durationMs = (durationSec ?: 0) * 1000,
                    )
                }.getOrNull()
            }
            val wyTask = async {
                runCatching { neteaseByKeyword(cleanName, cleanSinger, durationSec) }.getOrNull()
            }
            val lrclibTask = async {
                runCatching { lrclib(cleanName, cleanSinger, durationSec) }.getOrNull()
            }
            val results = listOfNotNull(wyTask.await(), kgTask.await(), lrclibTask.await())
                .filter { it.isNotEmpty() }
            // Prefer timed lyrics with translation, then timed lyrics with >= 3 lines, then anything non-empty.
            results.maxByOrNull { lines ->
                val timedCount = lines.count { it.time >= 0.0 }
                val hasTrans = lines.any { !it.translation.isNullOrBlank() }
                (if (timedCount >= 3) 10_000 else 0) +
                    (if (hasTrans) 1_000 else 0) +
                    timedCount.coerceAtMost(500)
            }
        }
    }

    private suspend fun kuwo(songmid: String): List<LyricLine>? {
        val json = runCatching {
            JSONObject(http.getText("http://m.kuwo.cn/newh5/singles/songinfoandlrc?musicId=$songmid", ua))
        }.getOrNull() ?: return null
        val lrclist = json.optJSONObject("data")?.optJSONArray("lrclist") ?: return null
        val rebuilt = StringBuilder()
        for (i in 0 until lrclist.length()) {
            val item = lrclist.optJSONObject(i) ?: continue
            val secs = item.optString("time").toDoubleOrNull() ?: continue
            val text = item.optString("lineLyric")
            val m = secs.toInt() / 60
            val s = secs.toInt() % 60
            val ms = ((secs - secs.toInt()) * 100).toInt()
            rebuilt.append(String.format("[%02d:%02d.%02d]%s\n", m, s, ms, text))
        }
        return LyricParser.parse(rebuilt.toString()).ifEmpty { null }
    }

    private suspend fun qq(songmid: String): List<LyricLine>? {
        val headers = ua + mapOf(
            "Referer" to "https://y.qq.com/portal/player.html",
            "Origin" to "https://y.qq.com",
        )
        val rawText = runCatching {
            http.getText(
                "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=$songmid&g_tk=5381&loginUin=0&hostUin=0&format=json&inCharset=utf8&outCharset=utf-8&platform=yqq",
                headers,
            )
        }.getOrNull()?.trim() ?: return null
        val jsonText = if (rawText.startsWith("{")) rawText
        else rawText.substringAfter("(", "").substringBeforeLast(")", "")
        val json = runCatching { JSONObject(jsonText) }.getOrNull() ?: return null
        if (json.optInt("code", -1) != 0) return null
        val rawLyric = json.optString("lyric")
        val lrc = (decodeB64(rawLyric) ?: rawLyric).takeIf { it.isNotBlank() } ?: return null
        val rawTrans = json.optString("trans").ifEmpty { null }
        val trans = rawTrans?.let { decodeB64(it) ?: it }
        return LyricParser.parse(lrc, trans).ifEmpty { null }
    }

    private suspend fun netease(songmid: String): List<LyricLine>? {
        val headers = ua + mapOf(
            "Referer" to "https://music.163.com/",
            "Cookie" to "os=pc",
        )
        val json = runCatching {
            JSONObject(http.getText("https://music.163.com/api/song/lyric?id=$songmid&lv=1&kv=1&tv=-1", headers))
        }.getOrNull() ?: return null
        val lyric = json.optJSONObject("lrc")?.optString("lyric").orEmpty()
        if (lyric.isBlank()) return null
        val tlyric = json.optJSONObject("tlyric")?.optString("lyric")?.ifEmpty { null }
        return LyricParser.parse(lyric, tlyric).ifEmpty { null }
    }

    private suspend fun neteaseByKeyword(
        name: String,
        singer: String,
        durationSec: Int?,
    ): List<LyricLine>? {
        val headers = ua + mapOf(
            "Referer" to "https://music.163.com/",
            "Cookie" to "os=pc",
        )
        val query = encode("$name $singer".trim())
        val searchJson = runCatching {
            JSONObject(http.getText("https://music.163.com/api/search/get?s=$query&type=1&limit=5&offset=0", headers))
        }.getOrNull() ?: return null
        val songs = searchJson.optJSONObject("result")?.optJSONArray("songs") ?: return null
        val candidates = mutableListOf<String>()
        for (i in 0 until songs.length()) {
            val s = songs.optJSONObject(i) ?: continue
            val id = s.opt("id")?.toString()?.ifEmpty { null } ?: continue
            val dur = (s.optInt("duration", 0) / 1000)
            if (durationSec != null && durationSec > 0 && dur > 0 && kotlin.math.abs(dur - durationSec) > 25) {
                continue
            }
            candidates += id
        }
        for (id in candidates.take(3)) {
            netease(id)?.takeIf { it.any { line -> line.time >= 0.0 } }?.let { return it }
        }
        return null
    }

    private suspend fun kugou(
        name: String,
        singer: String,
        hash: String?,
        durationMs: Int,
    ): List<LyricLine>? {
        val headers = mapOf(
            "KG-RC" to "1",
            "KG-THash" to "expand_search_manager.cpp:852736169:451",
            "User-Agent" to "KuGou2012-9020-ExpandSearchManager",
        )
        val keyword = if (singer.isNotBlank()) "$singer - $name" else name
        val kwEnc = encode(keyword)
        val searchUrl = buildString {
            append("http://lyrics.kugou.com/search?ver=1&man=yes&client=pc&keyword=").append(kwEnc)
            if (!hash.isNullOrBlank()) append("&hash=").append(hash)
            if (durationMs > 0) append("&timelength=").append(durationMs)
            append("&lrctxt=1")
        }
        val candidates = runCatching {
            JSONObject(http.getText(searchUrl, headers)).optJSONArray("candidates")
        }.getOrNull() ?: return null
        val limit = minOf(candidates.length(), 4)
        for (i in 0 until limit) {
            val c = candidates.optJSONObject(i) ?: continue
            val id = c.opt("id")?.toString()?.ifEmpty { null } ?: continue
            val accessKey = c.optString("accesskey")
            if (accessKey.isEmpty()) continue
            val dlUrl = "http://lyrics.kugou.com/download?ver=1&client=pc&id=$id&accesskey=$accessKey&fmt=lrc&charset=utf8"
            val dJson = runCatching { JSONObject(http.getText(dlUrl, headers)) }.getOrNull() ?: continue
            if (dJson.optInt("status") != 200) continue
            val lrc = decodeB64(dJson.optString("content")) ?: continue
            val parsed = LyricParser.parse(lrc)
            if (parsed.any { it.time >= 0.0 }) return parsed
        }
        return null
    }

    private suspend fun lrclib(
        name: String,
        singer: String,
        durationSec: Int?,
    ): List<LyricLine>? {
        val url = buildString {
            append("https://lrclib.net/api/search?track_name=").append(encode(name))
            if (singer.isNotBlank()) append("&artist_name=").append(encode(singer))
        }
        val text = runCatching {
            http.getText(url, mapOf("User-Agent" to "WalkmanTV/1.7 (Android TV)"))
        }.getOrNull()?.trim() ?: return null
        if (!text.startsWith("[")) return null
        val arr = runCatching { JSONArray(text) }.getOrNull() ?: return null
        for (i in 0 until minOf(arr.length(), 5)) {
            val item = arr.optJSONObject(i) ?: continue
            val dur = item.optDouble("duration", 0.0).toInt()
            if (durationSec != null && durationSec > 0 && dur > 0 && kotlin.math.abs(dur - durationSec) > 20) {
                continue
            }
            val synced = item.optString("syncedLyrics").trim()
            if (synced.isNotEmpty()) {
                val parsed = LyricParser.parse(synced)
                if (parsed.any { it.time >= 0.0 }) return parsed
            }
        }
        return null
    }

    private fun decodeB64(b64: String): String? {
        if (b64.isBlank()) return null
        return runCatching {
            val decoded = String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8)
            decoded.takeIf { it.isNotBlank() && (it.contains('[') || it.contains('\n')) }
        }.getOrNull()
    }

    private fun isMd5Hash(s: String): Boolean = s.length == 32 && s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    private fun cleanTitle(raw: String): String = raw
        .replace(Regex("""[（(].*?[)）]"""), " ")
        .replace(Regex("""\[.*?]"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
        .ifEmpty { raw.trim() }

    private fun cleanSingerName(raw: String): String = raw
        .split("/", "、", ",", "&", ";")
        .firstOrNull()
        ?.trim()
        .orEmpty()

    private fun encode(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
