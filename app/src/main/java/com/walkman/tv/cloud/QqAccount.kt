package com.walkman.tv.cloud

import android.content.Context
import com.walkman.tv.data.model.Quality
import com.walkman.tv.data.model.SonglistInfo
import com.walkman.tv.data.model.SourceID
import com.walkman.tv.data.model.Track
import com.walkman.tv.source.catalog.CatalogHttp
import com.walkman.tv.source.catalog.urlEncode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.util.UUID

/** QQ QR OAuth, per-account credentials, and account-scoped music data. */
class QqAccount(
    context: Context,
    private val client: OkHttpClient,
    private val http: CatalogHttp,
) {
    data class Account(val id: String, val name: String)
    data class State(val accounts: List<Account> = emptyList(), val activeId: String? = null) {
        val connected get() = activeId != null && accounts.any { it.id == activeId }
        val name get() = accounts.firstOrNull { it.id == activeId }?.name.orEmpty()
    }
    data class Qr(val image: ByteArray, val qrsig: String)
    private data class Session(val id: String, val name: String, val key: String,
        val encryptUin: String)
    private val prefs = context.getSharedPreferences("qq_account", Context.MODE_PRIVATE)
    private val sessions = restore().toMutableList()
    private var activeId = prefs.getString("active_id", null)?.takeIf { id ->
        sessions.any { it.id == id }
    } ?: sessions.firstOrNull()?.id
    private val mutableState = MutableStateFlow(snapshot())
    val state = mutableState.asStateFlow()

    private fun snapshot() = State(sessions.map { Account(it.id, it.name) }, activeId)
    private fun current(): Session = sessions.firstOrNull { it.id == activeId }
        ?: throw IllegalStateException("请先连接 QQ 音乐账号")

    fun select(id: String) {
        if (sessions.none { it.id == id }) return
        activeId = id
        persist()
        mutableState.value = snapshot()
    }

    fun disconnect() {
        sessions.removeAll { it.id == activeId }
        activeId = sessions.firstOrNull()?.id
        persist()
        mutableState.value = snapshot()
    }

    suspend fun newQr(): Qr = withContext(Dispatchers.IO) {
        val url = "https://ssl.ptlogin2.qq.com/ptqrshow?" + query(mapOf(
            "appid" to "716027609", "e" to "2", "l" to "M", "s" to "3",
            "d" to "72", "v" to "4", "t" to Math.random().toString(),
            "daid" to "383", "pt_3rd_aid" to "100497308"))
        client.newCall(Request.Builder().url(url)
            .header("Referer", "https://xui.ptlogin2.qq.com/").build())
            .execute().use { response ->
                if (!response.isSuccessful) throw IllegalStateException("无法生成 QQ 登录二维码")
                val sig = response.headers.values("Set-Cookie")
                    .firstOrNull { it.startsWith("qrsig=") }?.substringAfter("qrsig=")
                    ?.substringBefore(";").orEmpty()
                val bytes = response.body?.bytes() ?: ByteArray(0)
                if (sig.isBlank() || bytes.isEmpty()) throw IllegalStateException("QQ 登录二维码无效")
                Qr(bytes, sig)
            }
    }

    /** 66 waiting, 67 scanned, 65 expired, 0 connected. */
    suspend fun pollQr(qr: Qr): Int = withContext(Dispatchers.IO) {
        val params = mapOf(
            "u1" to "https://graph.qq.com/oauth2.0/login_jump",
            "ptqrtoken" to hash33(qr.qrsig).toString(), "ptredirect" to "0",
            "h" to "1", "t" to "1", "g" to "1", "from_ui" to "1",
            "ptlang" to "2052", "action" to "0-0-${System.currentTimeMillis()}",
            "js_ver" to "20102616", "js_type" to "1", "pt_uistyle" to "40",
            "aid" to "716027609", "daid" to "383",
            "pt_3rd_aid" to "100497308", "has_onekey" to "1")
        val request = Request.Builder()
            .url("https://ssl.ptlogin2.qq.com/ptqrlogin?${query(params)}")
            .header("Referer", "https://xui.ptlogin2.qq.com/")
            .header("Cookie", "qrsig=${qr.qrsig}").build()
        val body = client.newCall(request).execute().use {
            if (!it.isSuccessful) throw IllegalStateException("QQ 二维码状态获取失败")
            it.body?.string().orEmpty()
        }
        val values = Regex("'([^']*)'").findAll(body).map { it.groupValues[1] }.toList()
        val code = values.firstOrNull()?.toIntOrNull()
            ?: throw IllegalStateException("QQ 二维码状态无效")
        if (code == 0) {
            val redirect = values.getOrNull(2).orEmpty()
            val uin = Regex("[?&]uin=([^&]+)").find(redirect)?.groupValues?.get(1)
                ?: throw IllegalStateException("QQ 未返回账号")
            val sigx = Regex("[?&]ptsigx=([^&]+)").find(redirect)?.groupValues?.get(1)
                ?: throw IllegalStateException("QQ 未返回授权信息")
            authorize(uin, sigx)
        }
        code
    }

    private suspend fun authorize(uin: String, sigx: String) {
        val noRedirect = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
        val check = mapOf(
            "uin" to uin, "pttype" to "1", "service" to "ptqrlogin",
            "nodirect" to "0", "ptsigx" to sigx,
            "s_url" to "https://graph.qq.com/oauth2.0/login_jump",
            "ptlang" to "2052", "ptredirect" to "100", "aid" to "716027609",
            "daid" to "383", "j_later" to "0", "low_login_hour" to "0",
            "regmaster" to "0", "pt_login_type" to "3", "pt_aid" to "0",
            "pt_aaid" to "16", "pt_light" to "0", "pt_3rd_aid" to "100497308")
        val oauthCookies = withContext(Dispatchers.IO) {
            noRedirect.newCall(Request.Builder()
                .url("https://ssl.ptlogin2.graph.qq.com/check_sig?${query(check)}")
                .header("Referer", "https://xui.ptlogin2.qq.com/").build())
                .execute().use { response ->
                    response.headers.values("Set-Cookie").map { it.substringBefore(";") }
                }
        }
        val pSkey = oauthCookies.firstOrNull { it.startsWith("p_skey=") }
            ?.substringAfter("p_skey=").orEmpty()
        if (pSkey.isBlank()) throw IllegalStateException("QQ 授权凭据缺失")
        val form = FormBody.Builder()
            .add("response_type", "code").add("client_id", "100497308")
            .add("redirect_uri", "https://y.qq.com/portal/wx_redirect.html?login_type=1&surl=https://y.qq.com/")
            .add("scope", "get_user_info,get_app_friends")
            .add("state", "state").add("switch", "").add("from_ptlogin", "1")
            .add("src", "1").add("update_auth", "1").add("openapi", "1010_1030")
            .add("g_tk", hash33(pSkey, 5381).toString())
            .add("auth_time", System.currentTimeMillis().toString())
            .add("ui", UUID.randomUUID().toString()).build()
        val location = withContext(Dispatchers.IO) {
            noRedirect.newCall(Request.Builder()
                .url("https://graph.qq.com/oauth2.0/authorize")
                .header("Cookie", (oauthCookies + "uin=$uin").joinToString("; "))
                .post(form).build()).execute().use { it.header("Location").orEmpty() }
        }
        val code = Regex("[?&]code=([^&]+)").find(location)?.groupValues?.get(1)
            ?.let { URLDecoder.decode(it, "UTF-8") }
            ?: throw IllegalStateException("QQ 授权码获取失败")
        val data = cgi("QQConnectLogin.LoginServer", "QQLogin",
            JSONObject().put("code", code), loginType = 2)
        val id = data.opt("musicid")?.toString().orEmpty()
        val key = data.optString("musickey")
        if (id.isBlank() || key.isBlank()) throw IllegalStateException("QQ 音乐登录失败")
        sessions.removeAll { it.id == id }
        sessions.add(Session(id, "QQ $id", key, data.optString("encryptUin")))
        activeId = id
        persist()
        mutableState.value = snapshot()
    }

    suspend fun playlists(): List<SonglistInfo> {
        val s = current()
        val created = cgi("music.musicasset.PlaylistBaseRead", "GetPlaylistByUin",
            JSONObject().put("uin", s.id))
        val favourites = runCatching {
            cgi("music.musicasset.PlaylistFavRead", "CgiGetPlaylistFavInfo",
                JSONObject().put("uin", s.encryptUin).put("offset", 0).put("size", 100))
        }.getOrNull()
        fun parse(array: JSONArray?): List<SonglistInfo> =
            (0 until (array?.length() ?: 0)).mapNotNull { i ->
                val item = array?.optJSONObject(i) ?: return@mapNotNull null
                val id = item.optString("tid").ifBlank {
                    item.optString("dissid").ifBlank { item.optString("id") }
                }
                if (id.isBlank()) return@mapNotNull null
                SonglistInfo(id, SourceID.TX,
                    item.optString("title").ifBlank { item.optString("dissname") },
                    item.optString("nick").ifBlank { item.optString("nickname") },
                    item.optString("picurl").ifBlank { item.optString("logo") }.ifBlank { null },
                    item.optInt("songnum").takeIf { it > 0 })
            }
        return (parse(created.optJSONArray("v_playlist")) +
            parse(favourites?.optJSONArray("v_list"))).distinctBy { it.id }
    }

    suspend fun playlistTracks(id: String): List<Track> {
        val out = mutableListOf<Track>()
        for (page in 0..9) {
            val data = cgi("music.srfDissInfo.DissInfo", "CgiGetDiss",
                JSONObject().put("disstid", id).put("dirid", 0).put("tag", 1)
                    .put("song_begin", page * 100).put("song_num", 100)
                    .put("userinfo", 1).put("orderlist", 1).put("onlysonglist", 0))
            val batch = tracks(data.optJSONArray("songlist"))
            out.addAll(batch)
            if (batch.size < 100) break
        }
        return out
    }

    suspend fun daily(): List<Track> {
        val data = cgi("music.recommend.TrackRelationServer", "GetRadarSong",
            JSONObject().put("Page", 1).put("ReqType", 0)
                .put("FavSongs", JSONArray()).put("EntranceSongs", JSONArray()))
        val rows = data.optJSONArray("VecSongs") ?: return emptyList()
        val songs = JSONArray()
        for (i in 0 until rows.length()) rows.optJSONObject(i)?.optJSONObject("Track")?.let { songs.put(it) }
        return tracks(songs)
    }

    suspend fun guessLike(): List<Track> {
        val data = cgi("music.radioProxy.MbTrackRadioSvr", "get_radio_track",
            JSONObject().put("id", 99).put("num", 30).put("from", 0)
                .put("scene", 0).put("song_ids", JSONArray()))
        return tracks(data.optJSONArray("tracks"))
    }

    suspend fun heart(): List<Track> {
        val s = current()
        val data = cgi("music.srfDissInfo.DissInfo", "CgiGetDiss",
            JSONObject().put("disstid", 0).put("dirid", 201).put("tag", 1)
                .put("song_begin", 0).put("song_num", 100).put("userinfo", 1)
                .put("orderlist", 1).put("enc_host_uin", s.encryptUin))
        return tracks(data.optJSONArray("songlist"))
    }

    private suspend fun cgi(module: String, method: String, param: JSONObject,
        loginType: Int? = null): JSONObject {
        val s = if (loginType == null) current() else null
        val comm = JSONObject().put("ct", 24).put("cv", 4747474)
            .put("platform", "yqq.json").put("chid", "0")
            .put("uin", s?.id ?: "0")
            .put("g_tk", if (s == null) 5381 else hash33(s.key, 5381))
            .put("g_tk_new_20200303", if (s == null) 5381 else hash33(s.key, 5381))
            .put("format", "json").put("inCharset", "utf-8")
            .put("outCharset", "utf-8").put("notice", 0).put("needNewCode", 1)
        if (loginType != null) comm.put("tmeLoginType", loginType)
        val payload = JSONObject().put("comm", comm)
            .put("req_0", JSONObject().put("module", module)
                .put("method", method).put("param", param))
        val headers = if (s == null) emptyMap() else
            mapOf("Cookie" to "uin=${s.id}; qm_keyst=${s.key}; qqmusic_key=${s.key}")
        val response = JSONObject(http.postJson(
            "https://u.y.qq.com/cgi-bin/musicu.fcg", payload.toString(), headers))
            .optJSONObject("req_0") ?: throw IllegalStateException("QQ 音乐响应无效")
        if (response.optInt("code") != 0)
            throw IllegalStateException("QQ 音乐请求失败（${response.optInt("code")}）")
        return response.optJSONObject("data") ?: JSONObject()
    }

    private fun tracks(array: JSONArray?): List<Track> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val song = array.optJSONObject(i) ?: return@mapNotNull null
            val mid = song.optString("mid").ifBlank { song.optString("songmid") }
            if (mid.isBlank()) return@mapNotNull null
            val singers = song.optJSONArray("singer")
            val singer = (0 until (singers?.length() ?: 0))
                .mapNotNull { singers?.optJSONObject(it)?.optString("name") }
                .joinToString(" / ").ifBlank { "未知歌手" }
            val album = song.optJSONObject("album")
            Track(Track.makeID(SourceID.TX, mid),
                song.optString("name").ifBlank { song.optString("title", "未知歌曲") },
                singer, albumName = album?.optString("name")?.ifBlank { null },
                albumId = album?.optString("id")?.ifBlank { null },
                source = SourceID.TX, songmid = mid,
                duration = song.optInt("interval").takeIf { it > 0 },
                picURL = album?.optString("mid")?.takeIf { it.isNotBlank() }
                    ?.let { "https://y.gtimg.cn/music/photo_new/T002R300x300M000$it.jpg" },
                qualities = listOf(Quality.K128, Quality.K320),
                extras = mapOf("songmid" to mid))
        }
    }

    private fun persist() {
        val array = JSONArray()
        sessions.forEach { s ->
            array.put(JSONObject().put("id", s.id).put("name", s.name)
                .put("key", s.key).put("encryptUin", s.encryptUin))
        }
        prefs.edit().putString("accounts", CloudCipher.encrypt(array.toString()))
            .putString("active_id", activeId).apply()
    }

    private fun restore(): List<Session> = runCatching {
        val saved = prefs.getString("accounts", null) ?: return@runCatching emptyList()
        val array = JSONArray(CloudCipher.decrypt(saved))
        (0 until array.length()).mapNotNull { i ->
            val row = array.optJSONObject(i) ?: return@mapNotNull null
            val id = row.optString("id")
            val key = row.optString("key")
            if (id.isBlank() || key.isBlank()) return@mapNotNull null
            Session(id, row.optString("name"), key, row.optString("encryptUin"))
        }
    }.getOrDefault(emptyList())

    private fun hash33(value: String, seed: Long = 0L): Int {
        var result = seed
        value.forEach { result += (result shl 5) + it.code }
        return (result and 0x7fffffff).toInt()
    }

    private fun query(values: Map<String, String>): String =
        values.entries.joinToString("&") { "${urlEncode(it.key)}=${urlEncode(it.value)}" }
}
