package com.walkman.tv.playback.imports

import com.walkman.tv.data.model.Quality
import com.walkman.tv.data.model.SourceID
import com.walkman.tv.data.model.Track
import com.walkman.tv.data.store.LibraryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

/**
 * Parser & importer for LX Music (落雪音乐 Desktop & Mobile) exported playlists.
 *
 * Supports:
 * - `.lxmc` (gzip-compressed UTF-8 JSON) and plain `.json`
 * - `allData` (full backup), `playList_v2` / `playList` (all playlists),
 *   `playListPart_v2` / `playListPart` (single playlist), or raw playlist/track JSON.
 * - Both LX Music v2 (`meta.songId`, `meta.albumName`, `meta.picUrl`, `meta.qualitys`)
 *   and v1 (`songmid`, `albumName`, `img`, `types`) track objects.
 */
object LxPlaylistImporter {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    data class ParsedPlaylist(
        val name: String,
        val isLoveList: Boolean = false,
        val tracks: List<Track>,
    )

    data class ImportSummary(
        val playlistCount: Int,
        val trackCount: Int,
        val loveCount: Int,
        val skippedCount: Int,
    )

    fun parseBytes(rawBytes: ByteArray): Result<Pair<List<ParsedPlaylist>, Int>> = runCatching {
        require(rawBytes.isNotEmpty()) { "文件为空" }
        val jsonText = decodePayload(rawBytes).trim()
        require(jsonText.isNotEmpty()) { "文件内容为空" }
        parseJsonText(jsonText)
    }

    suspend fun importBytes(
        rawBytes: ByteArray,
        libraryStore: LibraryStore,
    ): Result<ImportSummary> = withContext(Dispatchers.IO) {
        parseBytes(rawBytes).map { (playlists, skipped) ->
            var importedLists = 0
            var importedTracks = 0
            var loveCount = 0

            for (pl in playlists) {
                if (pl.tracks.isEmpty()) continue
                if (pl.isLoveList) {
                    libraryStore.addAllToFavorites(pl.tracks)
                    loveCount += pl.tracks.size
                    importedTracks += pl.tracks.size
                    importedLists++
                } else {
                    val existing = libraryStore.userLists.value.firstOrNull { it.name == pl.name }
                    val target = existing ?: libraryStore.createList(pl.name)
                    libraryStore.addAllToList(target.id, pl.tracks)
                    importedTracks += pl.tracks.size
                    importedLists++
                }
            }

            if (importedTracks == 0) {
                throw IllegalArgumentException("未在文件中找到可导入的在线歌曲（仅本地路径歌曲已跳过）")
            }

            ImportSummary(
                playlistCount = importedLists,
                trackCount = importedTracks,
                loveCount = loveCount,
                skippedCount = skipped,
            )
        }
    }

    internal fun decodePayload(rawBytes: ByteArray): String {
        val isGzip = rawBytes.size >= 2 &&
            rawBytes[0] == 0x1f.toByte() &&
            rawBytes[1] == 0x8b.toByte()
        val bytes = if (isGzip) {
            GZIPInputStream(ByteArrayInputStream(rawBytes)).use { it.readBytes() }
        } else {
            rawBytes
        }
        return String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")
    }

    internal fun parseJsonText(jsonText: String): Pair<List<ParsedPlaylist>, Int> {
        val result = mutableListOf<ParsedPlaylist>()
        var skipped = 0
        val elem: JsonElement = json.parseToJsonElement(jsonText)

        if (elem is JsonArray) {
            val (tracks, skip) = parseTrackArray(elem)
            skipped += skip
            if (tracks.isNotEmpty()) {
                result += ParsedPlaylist(name = "LX 导入歌单", tracks = tracks)
            }
            return result to skipped
        }

        val root = elem.jsonObject
        val type = root.str("type")
        val dataObj = root.obj("data")

        when {
            // Single exported playlist: { type: "playListPart_v2", data: { id, name, list: [...] } }
            dataObj != null && dataObj.containsKey("list") -> {
                val name = dataObj.str("name").ifBlank { "LX 导入歌单" }
                val (tracks, skip) = parseTrackArray(dataObj.arr("list"))
                skipped += skip
                if (tracks.isNotEmpty()) {
                    result += ParsedPlaylist(name = name, isLoveList = dataObj.str("id") == "love", tracks = tracks)
                }
            }
            // Full backup or all playlists: data.playList or data itself contains defaultList / loveList / userList
            dataObj != null -> {
                val listContainer = dataObj.obj("playList")
                    ?: dataObj.obj("listData")
                    ?: dataObj
                skipped += extractListContainer(listContainer, result)
            }
            // Root directly has defaultList / loveList / userList
            root.containsKey("loveList") || root.containsKey("userList") || root.containsKey("defaultList") -> {
                skipped += extractListContainer(root, result)
            }
            // Root directly is a single playlist { name, list: [...] }
            root.containsKey("list") -> {
                val name = root.str("name").ifBlank { "LX 导入歌单" }
                val (tracks, skip) = parseTrackArray(root.arr("list"))
                skipped += skip
                if (tracks.isNotEmpty()) {
                    result += ParsedPlaylist(name = name, tracks = tracks)
                }
            }
            else -> {
                throw IllegalArgumentException("无法识别的 LX 歌单格式（type=$type）")
            }
        }

        return result to skipped
    }

    private fun extractListContainer(container: JsonObject, out: MutableList<ParsedPlaylist>): Int {
        var skipped = 0
        container.arr("loveList")?.let { arr ->
            val (tracks, skip) = parseTrackArray(arr)
            skipped += skip
            if (tracks.isNotEmpty()) {
                out += ParsedPlaylist(name = "我喜欢的", isLoveList = true, tracks = tracks)
            }
        }
        container.arr("defaultList")?.let { arr ->
            val (tracks, skip) = parseTrackArray(arr)
            skipped += skip
            if (tracks.isNotEmpty()) {
                out += ParsedPlaylist(name = "LX 试听列表", tracks = tracks)
            }
        }
        container.arr("userList")?.let { userArr ->
            userArr.forEachIndexed { idx, el ->
                val item = el as? JsonObject ?: return@forEachIndexed
                val name = item.str("name").ifBlank { "LX 歌单 ${idx + 1}" }
                val (tracks, skip) = parseTrackArray(item.arr("list"))
                skipped += skip
                if (tracks.isNotEmpty()) {
                    out += ParsedPlaylist(name = name, tracks = tracks)
                }
            }
        }
        return skipped
    }

    private fun parseTrackArray(arr: JsonArray?): Pair<List<Track>, Int> {
        if (arr == null) return emptyList<Track>() to 0
        val out = ArrayList<Track>(arr.size)
        var skipped = 0
        for (el in arr) {
            val obj = el as? JsonObject
            val track = obj?.let { parseTrack(it) }
            if (track != null) out += track else skipped++
        }
        return out to skipped
    }

    private fun parseTrack(obj: JsonObject): Track? {
        val sourceKey = obj.str("source").trim().lowercase()
        if (sourceKey == "local") return null
        val source = SourceID.fromKey(sourceKey) ?: return null
        if (source == SourceID.LOCAL) return null
        val name = obj.str("name").trim()
        if (name.isEmpty()) return null
        val singer = obj.str("singer").trim().ifEmpty { "未知歌手" }

        val meta = obj.obj("meta")
        val rawSongmid = meta?.str("songId")?.takeIf { it.isNotBlank() }
            ?: obj.str("songmid").takeIf { it.isNotBlank() }
            ?: obj.str("hash").takeIf { it.isNotBlank() }
            ?: obj.str("id").removePrefix("${source.key}_").takeIf { it.isNotBlank() }
            ?: return null

        val albumName = meta?.str("albumName")?.takeIf { it.isNotBlank() }
            ?: obj.str("albumName").takeIf { it.isNotBlank() }
        val albumId = meta?.str("albumId")?.takeIf { it.isNotBlank() }
            ?: obj.str("albumId").takeIf { it.isNotBlank() }
        val picUrl = meta?.str("picUrl")?.takeIf { it.isNotBlank() }
            ?: obj.str("img").takeIf { it.isNotBlank() }

        val duration = parseIntervalSec(obj.str("interval"))
        val qualities = parseQualities(meta?.arr("qualitys") ?: obj.arr("types"))

        val extras = mutableMapOf<String, String>()
        val hash = meta?.str("hash")?.takeIf { it.isNotBlank() } ?: obj.str("hash").takeIf { it.isNotBlank() }
        if (hash != null) extras["hash"] = hash
        val strMediaMid = meta?.str("strMediaMid")?.takeIf { it.isNotBlank() } ?: obj.str("strMediaMid").takeIf { it.isNotBlank() }
        if (strMediaMid != null) extras["strMediaMid"] = strMediaMid
        val albumMid = meta?.str("albumMid")?.takeIf { it.isNotBlank() } ?: obj.str("albumMid").takeIf { it.isNotBlank() }
        if (albumMid != null) extras["albumMid"] = albumMid
        val copyrightId = meta?.str("copyrightId")?.takeIf { it.isNotBlank() } ?: obj.str("copyrightId").takeIf { it.isNotBlank() }
        if (copyrightId != null) extras["copyrightId"] = copyrightId

        return Track(
            id = Track.makeID(source, rawSongmid),
            name = name,
            singer = singer,
            albumName = albumName,
            albumId = albumId,
            source = source,
            songmid = rawSongmid,
            duration = duration,
            picURL = picUrl,
            qualities = qualities,
            extras = extras,
        )
    }

    private fun parseIntervalSec(interval: String?): Int? {
        if (interval.isNullOrBlank()) return null
        val parts = interval.trim().split(":")
        return when (parts.size) {
            2 -> {
                val m = parts[0].toIntOrNull() ?: return null
                val s = parts[1].toIntOrNull() ?: return null
                m * 60 + s
            }
            3 -> {
                val h = parts[0].toIntOrNull() ?: return null
                val m = parts[1].toIntOrNull() ?: return null
                val s = parts[2].toIntOrNull() ?: return null
                h * 3600 + m * 60 + s
            }
            else -> interval.toIntOrNull()
        }
    }

    private fun parseQualities(arr: JsonArray?): List<Quality> {
        if (arr == null) return emptyList()
        val out = mutableListOf<Quality>()
        for (el in arr) {
            val typeStr = (el as? JsonObject)?.str("type")?.lowercase() ?: continue
            val q = when (typeStr) {
                "128k" -> Quality.K128
                "320k" -> Quality.K320
                "flac" -> Quality.FLAC
                "flac24bit", "hires" -> Quality.HIRES
                "atmos" -> Quality.ATMOS
                "atmos_plus" -> Quality.ATMOS_PLUS
                "master" -> Quality.MASTER
                else -> null
            }
            if (q != null && q !in out) out += q
        }
        return out
    }

    private fun JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun JsonObject.obj(key: String): JsonObject? =
        this[key] as? JsonObject

    private fun JsonObject.arr(key: String): JsonArray? =
        this[key] as? JsonArray
}
