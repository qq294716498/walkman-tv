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
import java.security.MessageDigest
import java.util.UUID

/** Direct, signed Kugou requests. No intermediary service receives the login token. */
class KugouAccount(context: Context, private val http: CatalogHttp) {
    data class State(val connected: Boolean = false, val userId: String = "")
    private val prefs = context.getSharedPreferences("kugou_account", Context.MODE_PRIVATE)
    private val mid = prefs.getString("mid", null) ?: md5(UUID.randomUUID().toString()).also {
        prefs.edit().putString("mid", it).apply()
    }
    private var token: String? = runCatching {
        prefs.getString("session", null)?.let(CloudCipher::decrypt)
    }.getOrNull()
    private var userId = prefs.getString("userid", "") ?: ""
    private val mutableState = MutableStateFlow(State(!token.isNullOrBlank(), userId))
    val state = mutableState.asStateFlow()

    suspend fun newQr(): String {
        val response = request("/v2/qrcode", "https://login-user.kugou.com",
            mapOf("appid" to "1001", "type" to "1", "plat" to "4",
                "qrcode_txt" to "https://h5.kugou.com/apps/loginQRCode/html/index.html?appid=1005&",
                "srcappid" to "2919"), web = true)
        val data = response.optJSONObject("data")
        val key = data?.optString("qrcode").orEmpty()
            .ifBlank { data?.optString("key").orEmpty() }
            .ifBlank { response.optString("qrcode") }
        if (key.isBlank()) throw IllegalStateException("无法生成酷狗登录二维码")
        return key
    }

    fun qrUrl(key: String): String =
        "https://h5.kugou.com/apps/loginQRCode/html/index.html?qrcode=\${urlEncode(key)}"

    /** 0 expired, 1 waiting, 2 scanned, 4 connected. */
    suspend fun pollQr(key: String): Int {
        val response = request("/v2/get_userinfo_qrcode", "https://login-user.kugou.com",
            mapOf("plat" to "4", "appid" to "1005", "srcappid" to "2919",
                "qrcode" to key, "dev" to mid), web = true)
        val data = response.optJSONObject("data") ?: return 1
        val status = data.optInt("status", 1)
        if (status == 4) {
            val value = data.optString("token")
            val uid = data.opt("userid")?.toString().orEmpty()
            if (value.isBlank() || uid.isBlank()) throw IllegalStateException("酷狗未返回登录凭据")
            token = value
            userId = uid
            prefs.edit().putString("session", CloudCipher.encrypt(value))
                .putString("userid", uid).apply()
            mutableState.value = State(true, uid)
        }
        return status
    }

    fun disconnect() {
        token = null
        userId = ""
        prefs.edit().remove("session").remove("userid").apply()
        mutableState.value = State()
    }

    suspend fun daily(): List<Track> {
        val response = request("/everyday_song_recommend", "https://gateway.kugou.com",
            data = JSONObject().put("platform", "android").put("userid", userId),
            router = "everydayrec.service.kugou.com")
        return tracks(findArray(response.optJSONObject("data") ?: response))
    }

    suspend fun fm(): List<Track> {
        val now = System.currentTimeMillis()
        val body = JSONObject()
            .put("appid", 1005).put("clienttime", now).put("mid", mid)
            .put("action", "play").put("recommend_source_locked", 0)
            .put("song_pool_id", 0).put("callerid", 0).put("m_type", 1)
            .put("platform", "ios").put("area_code", 1).put("remain_songcnt", 0)
            .put("clientver", 20489).put("is_overplay", 0).put("mode", "normal")
            .put("fakem", "ca981cfc583a4c37f28d2d49000013c16a0a")
            .put("userid", userId).put("kguid", userId).put("token", token)
        val response = request("/v2/personal_recommend", "https://gateway.kugou.com",
            data = body, router = "persnfm.service.kugou.com")
        return tracks(findArray(response.optJSONObject("data") ?: response))
    }

    suspend fun playlists(): List<SonglistInfo> {
        requireLogin()
        val response = request("/v7/get_all_list", "https://gateway.kugou.com",
            mapOf("plat" to "1"),
            JSONObject().put("userid", userId).put("token", token)
                .put("total_ver", 979).put("type", 2).put("page", 1).put("pagesize", 100),
            router = "cloudlist.service.kugou.com")
        val array = findArray(response.optJSONObject("data") ?: response) ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val item = array.optJSONObject(i) ?: return@mapNotNull null
            val id = item.optString("listid").ifBlank {
                item.optString("global_collection_id").ifBlank { item.optString("specialid") }
            }
            if (id.isBlank()) return@mapNotNull null
            SonglistInfo(id, SourceID.KG,
                item.optString("name").ifBlank { item.optString("specialname", "未命名歌单") },
                item.optString("nickname"),
                item.optString("pic").ifBlank { item.optString("imgurl") }.ifBlank { null },
                item.optInt("count").takeIf { it > 0 } ?: item.optInt("songcount"))
        }
    }

    suspend fun playlistTracks(id: String): List<Track> {
        requireLogin()
        val out = mutableListOf<Track>()
        for (page in 1..10) {
            val response = request("/v4/get_list_all_file_v3", "https://gateway.kugou.com",
                data = JSONObject().put("listid", id).put("userid", userId).put("type", 0)
                    .put("page", page).put("pagesize", 300).put("area_code", 1)
                    .put("allplatform", 1).put("show_cover", 1).put("token", token),
                router = "cloudlist.service.kugou.com")
            val batch = tracks(findArray(response.optJSONObject("data") ?: response))
            out.addAll(batch)
            if (batch.size < 300) break
        }
        return out
    }

    private fun requireLogin() {
        if (token.isNullOrBlank() || userId.isBlank())
            throw IllegalStateException("请先连接酷狗音乐账号")
    }

    private suspend fun request(
        path: String, base: String, extra: Map<String, String> = emptyMap(),
        data: JSONObject? = null, router: String? = null, web: Boolean = false,
    ): JSONObject {
        if (!web) requireLogin()
        val params = sortedMapOf(
            "dfid" to "-", "mid" to mid, "uuid" to "-",
            "appid" to "1005", "clientver" to "20489",
            "clienttime" to (System.currentTimeMillis() / 1000).toString())
        if (!web) {
            params["token"] = token.orEmpty()
            params["userid"] = userId
        }
        params.putAll(extra)
        val salt = if (web) "NVPh5oo715z5DIWAeQlhMDsWXXQV4hwt"
            else "OIlwieks28dk2k092lksi2UIkp"
        val body = data?.toString().orEmpty()
        val serialized = params.entries.joinToString("") { "\${it.key}=\${it.value}" }
        params["signature"] = md5(salt + serialized + body + salt)
        val query = params.entries.joinToString("&") { "\${urlEncode(it.key)}=\${urlEncode(it.value)}" }
        val headers = mutableMapOf(
            "User-Agent" to "Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi",
            "dfid" to "-", "mid" to mid, "clienttime" to params.getValue("clienttime"))
        if (router != null) headers["x-router"] = router
        val raw = if (data == null) http.getText("$base$path?$query", headers)
            else http.postJson("$base$path?$query", body, headers)
        val response = JSONObject(raw)
        if (response.optInt("status", 1) == 0 || response.optInt("error_code") != 0)
            throw IllegalStateException(response.optString("error_msg")
                .ifBlank { response.optString("msg", "酷狗请求失败") })
        return response
    }

    private fun findArray(data: JSONObject): JSONArray? {
        for (name in listOf("info", "list", "song_list", "songs", "data", "songlist")) {
            data.optJSONArray(name)?.let { return it }
            data.optJSONObject(name)?.let { nested ->
                for (sub in listOf("info", "list", "songs")) nested.optJSONArray(sub)?.let { return it }
            }
        }
        return null
    }

    private fun tracks(array: JSONArray?): List<Track> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val item = array.optJSONObject(i) ?: return@mapNotNull null
            val song = item.optJSONObject("song_info") ?: item
            val hash = song.optString("hash").ifBlank { song.optString("filehash") }
            if (hash.isBlank()) return@mapNotNull null
            val filename = song.optString("filename").ifBlank { song.optString("name") }
            val singer = song.optString("singername")
                .ifBlank { filename.substringBefore(" - ", "未知歌手") }
            val name = song.optString("songname")
                .ifBlank { filename.substringAfter(" - ", filename) }
            Track(Track.makeID(SourceID.KG, hash), name, singer,
                albumName = song.optString("album_name").ifBlank { null },
                albumId = song.optString("album_id").ifBlank { null },
                source = SourceID.KG, songmid = hash,
                duration = song.optInt("duration").takeIf { it > 0 },
                picURL = song.optString("img").ifBlank { null }?.replace("{size}", "240"),
                qualities = listOf(Quality.K128, Quality.K320),
                extras = mapOf("hash" to hash))
        }
    }

    private fun md5(value: String): String =
        MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
