package com.walkman.tv.cloud

import android.content.Context
import com.walkman.tv.data.model.Quality
import com.walkman.tv.data.model.SonglistInfo
import com.walkman.tv.data.model.SourceID
import com.walkman.tv.data.model.Track
import com.walkman.tv.source.catalog.CatalogHttp
import com.walkman.tv.source.catalog.urlEncode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Account session stays on this device; platform cookies never enter the repository. */
class NeteaseAccount(private val context: Context, private val http: CatalogHttp) {
    data class State(val connected: Boolean = false, val nickname: String = "")
    private val prefs = context.getSharedPreferences("cloud_account", Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()
    private var cookie: String? = restore()

    init { if (cookie != null) mutableState.value = State(true, prefs.getString("nickname", "") ?: "") }

    suspend fun newQr(): String {
        val response = post("/weapi/login/qrcode/unikey", JSONObject().put("type", 1))
        val key = response.optJSONObject("data")?.optString("unikey")
            .orEmpty().ifBlank { response.optString("unikey") }
        if (key.isBlank()) throw IllegalStateException("无法生成网易云登录二维码")
        return key
    }

    fun qrUrl(key: String): String = "https://music.163.com/login?codekey=\${urlEncode(key)}"

    /** 801 waiting, 802 scanned, 803 connected, 800 expired. */
    suspend fun pollQr(key: String): Int {
        val response = post("/weapi/login/qrcode/client/login",
            JSONObject().put("key", key).put("type", 1))
        val code = response.optInt("code")
        if (code == 803) {
            val newCookie = response.optString("cookie")
            if (newCookie.isBlank()) throw IllegalStateException("平台未返回登录凭据")
            val profile = post("/weapi/nuser/account/get", JSONObject(), newCookie)
                .optJSONObject("profile")
            if (profile == null || profile.optLong("userId") <= 0L)
                throw IllegalStateException("登录成功但未能读取账号信息")
            cookie = newCookie
            val name = profile.optString("nickname").ifBlank { "网易云用户" }
            prefs.edit().putString("session", CloudCipher.encrypt(newCookie))
                .putString("nickname", name).apply()
            mutableState.value = State(true, name)
        }
        return code
    }

    fun disconnect() {
        cookie = null
        prefs.edit().remove("session").remove("nickname").apply()
        mutableState.value = State()
    }

    suspend fun daily(): List<Track> =
        tracks(api("/api/v3/discovery/recommend/songs")
            .optJSONObject("data")?.optJSONArray("dailySongs"))

    suspend fun fm(): List<Track> =
        tracks(post("/weapi/v1/radio/get", JSONObject()).optJSONArray("data"))

    suspend fun playlists(): List<SonglistInfo> {
        val account = post("/weapi/nuser/account/get", JSONObject())
        val userId = account.optJSONObject("profile")?.optLong("userId") ?: 0L
        if (userId <= 0L) throw IllegalStateException("账号已失效，请重新连接")
        val response = api("/api/user/playlist?uid=$userId&limit=500&offset=0")
        val array = response.optJSONArray("playlist") ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val item = array.optJSONObject(i) ?: return@mapNotNull null
            val id = item.optLong("id").takeIf { it > 0 }?.toString() ?: return@mapNotNull null
            SonglistInfo(id, SourceID.WY, item.optString("name"),
                item.optJSONObject("creator")?.optString("nickname").orEmpty(),
                item.optString("coverImgUrl").ifBlank { null },
                item.optInt("trackCount"))
        }
    }

    suspend fun heart(): List<Track> {
        val lists = playlists()
        val liked = lists.firstOrNull { it.name.contains("喜欢") } ?: lists.firstOrNull()
            ?: return emptyList()
        val response = post("/weapi/v6/playlist/detail",
            JSONObject().put("id", liked.id).put("n", 100).put("s", 8))
        val sourceTracks = response.optJSONObject("playlist")?.optJSONArray("tracks")
        val seed = sourceTracks?.optJSONObject(0)?.optLong("id") ?: 0L
        if (seed <= 0L) return emptyList()
        val intelligence = post("/weapi/playmode/intelligence/list",
            JSONObject().put("songId", seed).put("playlistId", liked.id)
                .put("startMusicId", seed).put("count", 30))
        val data = intelligence.optJSONArray("data") ?: return emptyList()
        val songs = JSONArray()
        for (i in 0 until data.length()) data.optJSONObject(i)?.optJSONObject("songInfo")?.let { songs.put(it) }
        return tracks(songs)
    }

    suspend fun guessLike(): List<Track> {
        val response = post("/weapi/v1/discovery/recommend/resource", JSONObject())
        val lists = response.optJSONArray("recommend") ?: return emptyList()
        val firstId = lists.optJSONObject(0)?.optLong("id") ?: return emptyList()
        val detail = post("/weapi/v6/playlist/detail",
            JSONObject().put("id", firstId).put("n", 100).put("s", 8))
        return tracks(detail.optJSONObject("playlist")?.optJSONArray("tracks"))
    }

    suspend fun playlistTracks(id: String): List<Track> {
        val playlist = post("/weapi/v6/playlist/detail",
            JSONObject().put("id", id).put("n", 1000).put("s", 8))
            .optJSONObject("playlist") ?: return emptyList()
        val full = tracks(playlist.optJSONArray("tracks"))
        val ids = playlist.optJSONArray("trackIds") ?: return full
        if (ids.length() <= full.size) return full
        val order = (0 until ids.length()).mapNotNull {
            ids.optJSONObject(it)?.optLong("id")?.takeIf { value -> value > 0L }?.toString()
        }
        val byId = full.associateBy { it.songmid }.toMutableMap()
        for (chunk in order.filterNot { byId.containsKey(it) }.chunked(100)) {
            val list = "[${chunk.joinToString(",")}]"
            val response = api("/api/song/detail/?ids=${urlEncode(list)}")
            tracks(response.optJSONArray("songs")).forEach { byId[it.songmid] = it }
        }
        return order.mapNotNull(byId::get)
    }

    private suspend fun api(path: String): JSONObject {
        val session = cookie ?: throw IllegalStateException("请先连接网易云账号")
        val response = JSONObject(http.getText("https://music.163.com$path",
            mapOf("Cookie" to session, "Referer" to "https://music.163.com/")))
        if (response.optInt("code", 200) != 200)
            throw IllegalStateException(response.optString("message").ifBlank { "网易云请求失败" })
        return response
    }

    private suspend fun post(path: String, payload: JSONObject, overrideCookie: String? = null): JSONObject {
        val session = overrideCookie ?: cookie
        if (path !in listOf("/weapi/login/qrcode/unikey", "/weapi/login/qrcode/client/login")
            && session.isNullOrBlank()) throw IllegalStateException("请先连接网易云账号")
        val csrf = session?.split(";")?.map { it.trim() }
            ?.firstOrNull { it.startsWith("__csrf=") }?.substringAfter("=") ?: ""
        if (csrf.isNotBlank()) payload.put("csrf_token", csrf)
        val (params, key) = NeteaseWeApi.encode(payload.toString())
        val headers = mutableMapOf("Referer" to "https://music.163.com/",
            "Origin" to "https://music.163.com",
            "User-Agent" to "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
        if (!session.isNullOrBlank()) headers["Cookie"] = session
        val body = "params=\${urlEncode(params)}&encSecKey=\${urlEncode(key)}"
        val response = JSONObject(http.postForm("https://music.163.com$path?csrf_token=${urlEncode(csrf)}", body, headers))
        val code = response.optInt("code", 200)
        if (code != 200 && code !in listOf(800, 801, 802, 803))
            throw IllegalStateException(response.optString("message").ifBlank { "网易云请求失败（$code）" })
        return response
    }

    private fun tracks(array: JSONArray?): List<Track> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val song = array.optJSONObject(i) ?: return@mapNotNull null
            val id = song.optLong("id").takeIf { it > 0 }?.toString() ?: return@mapNotNull null
            val artists = song.optJSONArray("ar") ?: song.optJSONArray("artists")
            val singer = (0 until (artists?.length() ?: 0))
                .mapNotNull { artists?.optJSONObject(it)?.optString("name")?.takeIf(String::isNotBlank) }
                .joinToString(" / ").ifBlank { "未知歌手" }
            val album = song.optJSONObject("al") ?: song.optJSONObject("album")
            Track(id = Track.makeID(SourceID.WY, id), name = song.optString("name", "未知歌曲"),
                singer = singer, albumName = album?.optString("name")?.ifBlank { null },
                albumId = album?.optLong("id")?.takeIf { it > 0 }?.toString(),
                source = SourceID.WY, songmid = id,
                duration = (song.optLong("dt").takeIf { it > 0 } ?: song.optLong("duration"))
                    .takeIf { it > 0 }?.div(1000)?.toInt(),
                picURL = album?.optString("picUrl")?.ifBlank { null },
                qualities = listOf(Quality.K128, Quality.K320, Quality.FLAC))
        }
    }

    private fun restore(): String? = runCatching {
        prefs.getString("session", null)?.let(CloudCipher::decrypt)
    }.getOrNull()
}
