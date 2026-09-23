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
    data class Account(val id: String, val nickname: String)
    data class State(val accounts: List<Account> = emptyList(), val activeId: String? = null) {
        val connected: Boolean get() = activeId != null && accounts.any { it.id == activeId }
        val nickname: String get() = accounts.firstOrNull { it.id == activeId }?.nickname.orEmpty()
    }
    private data class Session(val id: String, val nickname: String, val cookie: String)
    private val prefs = context.getSharedPreferences("cloud_account", Context.MODE_PRIVATE)
    private val sessions = restoreSessions().toMutableList()
    private var activeId = prefs.getString("active_id", null)?.takeIf { id ->
        sessions.any { it.id == id }
    } ?: sessions.firstOrNull()?.id
    private var cookie: String? = sessions.firstOrNull { it.id == activeId }?.cookie
    private val mutableState = MutableStateFlow(snapshot())
    val state = mutableState.asStateFlow()
    private val qrCookies = mutableMapOf<String, String>()
    private val smsCookies = mutableMapOf<String, String>()
    private val qrHeaders = mapOf(
        "Referer" to "https://music.163.com/",
        "Origin" to "https://music.163.com",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120 Safari/537.36",
    )

    private fun snapshot(): State = State(
        sessions.map { Account(it.id, it.nickname) }, activeId
    )

    fun select(id: String) {
        val session = sessions.firstOrNull { it.id == id } ?: return
        activeId = session.id
        cookie = session.cookie
        prefs.edit().putString("active_id", id).apply()
        mutableState.value = snapshot()
    }

    suspend fun newQr(): String {
        val result = http.postFormResponse(
            "https://music.163.com/api/login/qrcode/unikey",
            "type=3",
            qrHeaders,
        )
        val response = JSONObject(result.text)
        val key = response.optJSONObject("data")?.optString("unikey")
            .orEmpty().ifBlank { response.optString("unikey") }
        if (key.isBlank()) throw IllegalStateException(
            response.optString("message").ifBlank { "无法生成网易云登录二维码" }
        )
        qrCookies[key] = mergeCookies(result.cookies.joinToString("; "))
        return key
    }

    fun qrUrl(key: String): String = "https://music.163.com/login?codekey=${urlEncode(key)}"

    /** 801 waiting, 802 scanned, 803 connected, 800 expired. */
    suspend fun pollQr(key: String): Int {
        val initialCookie = qrCookies[key].orEmpty()
        val headers = if (initialCookie.isBlank()) qrHeaders
            else qrHeaders + ("Cookie" to initialCookie)
        val result = http.postFormResponse(
            "https://music.163.com/api/login/qrcode/client/login",
            "key=${urlEncode(key)}&type=3",
            headers,
        )
        val response = JSONObject(result.text)
        val code = response.optInt("code")
        val receivedCookie = mergeCookies(
            initialCookie, result.cookies.joinToString("; "), response.optString("cookie")
        )
        if (code == 803) {
            qrCookies.remove(key)
            completeLogin(receivedCookie)
        } else if (code == 800) {
            qrCookies.remove(key)
        } else if (code == 801 || code == 802) {
            qrCookies[key] = receivedCookie
        } else {
            throw IllegalStateException(
                response.optString("message").ifBlank { "网易云扫码状态异常（$code）" }
            )
        }
        return code
    }

    private fun mergeCookies(vararg values: String): String {
        val merged = linkedMapOf<String, String>()
        values.forEach { value ->
            value.split(";").forEach { field ->
                val key = field.substringBefore("=", "").trim()
                val content = field.substringAfter("=", "").trim()
                if (key.isNotBlank() && content.isNotBlank()) merged[key] = content
            }
        }
        return merged.entries.joinToString("; ") { (key, value) -> "$key=$value" }
    }

    /** Send a verification code to a mainland China mobile number. */
    suspend fun sendCode(phone: String) {
        require(phone.length == 11 && phone.startsWith("1") && phone.all(Char::isDigit)) {
            "请输入 11 位手机号"
        }
        val (response, receivedCookie) = unauthPost(
            "/weapi/sms/captcha/sent",
            JSONObject().put("ctcode", "86")
                .put("secrete", "music_middleuser_pclogin")
                .put("cellphone", phone),
        )
        if (response.optInt("code") != 200)
            throw IllegalStateException(
                response.optString("message").ifBlank { "验证码发送失败（${response.optInt("code")}）" }
            )
        smsCookies[phone] = receivedCookie
    }

    suspend fun loginWithCode(phone: String, code: String) {
        require(phone.length == 11 && phone.startsWith("1") && phone.all(Char::isDigit)) {
            "请输入 11 位手机号"
        }
        require(code.length in 4..8 && code.all(Char::isDigit)) { "请输入短信验证码" }
        val (response, receivedCookie) = unauthPost(
            "/weapi/w/login/cellphone",
            JSONObject().put("type", "1").put("https", "true")
                .put("phone", phone).put("countrycode", "86")
                .put("captcha", code).put("remember", "true")
                .put("secureCaptcha", ""),
            smsCookies[phone].orEmpty(),
        )
        if (response.optInt("code") != 200)
            throw IllegalStateException(
                response.optString("message").ifBlank { "验证码登录失败（${response.optInt("code")}）" }
            )
        completeLogin(mergeCookies(receivedCookie, response.optString("cookie")))
        smsCookies.remove(phone)
    }

    private suspend fun unauthPost(
        path: String, payload: JSONObject, requestCookie: String = "",
    ): Pair<JSONObject, String> {
        val (params, key) = NeteaseWeApi.encode(payload.toString())
        val headers = if (requestCookie.isBlank()) qrHeaders
            else qrHeaders + ("Cookie" to requestCookie)
        val result = http.postFormResponse(
            "https://music.163.com$path?csrf_token=",
            "params=${urlEncode(params)}&encSecKey=${urlEncode(key)}",
            headers,
        )
        return JSONObject(result.text) to mergeCookies(
            requestCookie, result.cookies.joinToString("; ")
        )
    }

    private suspend fun completeLogin(receivedCookie: String) {
        if (!receivedCookie.split(";").any { it.trim().startsWith("MUSIC_U=") })
            throw IllegalStateException("平台未返回登录凭据")
        val profile = post("/weapi/nuser/account/get", JSONObject(), receivedCookie)
            .optJSONObject("profile")
        if (profile == null || profile.optLong("userId") <= 0L)
            throw IllegalStateException("登录成功但未能读取账号信息")
        val id = profile.optLong("userId").toString()
        val name = profile.optString("nickname").ifBlank { "网易云用户" }
        sessions.removeAll { it.id == id }
        sessions.add(Session(id, name, receivedCookie))
        activeId = id
        cookie = receivedCookie
        persist()
        mutableState.value = snapshot()
    }

    private fun sessionFor(accountId: String?): Session =
        sessions.firstOrNull { it.id == (accountId ?: activeId) }
            ?: throw IllegalStateException("请先连接网易云账号")

    fun disconnect() {
        sessions.removeAll { it.id == activeId }
        activeId = sessions.firstOrNull()?.id
        cookie = sessions.firstOrNull { it.id == activeId }?.cookie
        persist()
        mutableState.value = snapshot()
    }

    suspend fun daily(): List<Track> =
        tracks(api("/api/v3/discovery/recommend/songs")
            .optJSONObject("data")?.optJSONArray("dailySongs"))

    suspend fun fm(): List<Track> =
        tracks(post("/weapi/v1/radio/get", JSONObject()).optJSONArray("data"))

    suspend fun playlists(accountId: String? = null): List<SonglistInfo> {
        val session = sessionFor(accountId)
        val account = post("/weapi/nuser/account/get", JSONObject(), session.cookie)
        val userId = account.optJSONObject("profile")?.optLong("userId") ?: 0L
        if (userId <= 0L) throw IllegalStateException("账号已失效，请重新连接")
        val response = api("/api/user/playlist?uid=$userId&limit=500&offset=0", session.cookie)
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

    suspend fun playlistTracks(id: String, accountId: String? = null): List<Track> {
        val session = sessionFor(accountId)
        val playlist = post("/weapi/v6/playlist/detail",
            JSONObject().put("id", id).put("n", 1000).put("s", 8), session.cookie)
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
            val response = api("/api/song/detail/?ids=${urlEncode(list)}", session.cookie)
            tracks(response.optJSONArray("songs")).forEach { byId[it.songmid] = it }
        }
        return order.mapNotNull(byId::get)
    }

    private suspend fun api(path: String, overrideCookie: String? = null): JSONObject {
        val session = overrideCookie ?: cookie ?: throw IllegalStateException("请先连接网易云账号")
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
        val body = "params=${urlEncode(params)}&encSecKey=${urlEncode(key)}"
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

    private fun persist() {
        val array = JSONArray()
        sessions.forEach { session ->
            array.put(JSONObject().put("id", session.id).put("nickname", session.nickname)
                .put("cookie", session.cookie))
        }
        prefs.edit().putString("accounts", CloudCipher.encrypt(array.toString()))
            .putString("active_id", activeId)
            .remove("session").remove("nickname").apply()
    }

    private fun restoreSessions(): List<Session> = runCatching {
        val saved = prefs.getString("accounts", null)
        if (saved != null) {
            val array = JSONArray(CloudCipher.decrypt(saved))
            return@runCatching (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val id = item.optString("id")
                val value = item.optString("cookie")
                if (id.isBlank() || value.isBlank()) return@mapNotNull null
                Session(id, item.optString("nickname"), value)
            }
        }
        val legacy = prefs.getString("session", null)?.let(CloudCipher::decrypt)
        if (legacy.isNullOrBlank()) emptyList() else
            listOf(Session("legacy", prefs.getString("nickname", "") ?: "", legacy))
    }.getOrDefault(emptyList())
}
