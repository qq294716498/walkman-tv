package com.walkman.tv.cloud

import android.content.Context
import android.util.Log
import com.walkman.tv.data.model.Quality
import com.walkman.tv.data.model.SonglistInfo
import com.walkman.tv.data.model.SourceID
import com.walkman.tv.data.model.Track
import com.walkman.tv.playback.LyricLine
import com.walkman.tv.playback.LyricParser
import com.walkman.tv.source.catalog.urlEncode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Qishui Music (汽水音乐) Car OpenSDK account, radio/FM feed, playlist catalog,
 * lyric resolver, and plaintext fallback resolver.
 *
 * Authentication uses Douyin/Qishui Car OAuth QR + P-256 ECDSA ticket-guard signing.
 * Playback itself goes through the user's LX custom source script first (via SourceManager),
 * with cross-source fallback and plaintext car-API audio as last-resort fallback.
 */
class QishuiAccount(
    context: Context,
    private val client: OkHttpClient,
) {
    data class Account(val id: String, val nickname: String, val isVip: Boolean = false)

    data class State(val accounts: List<Account> = emptyList(), val activeId: String? = null) {
        val connected: Boolean get() = activeId != null && accounts.any { it.id == activeId }
        val nickname: String get() = accounts.firstOrNull { it.id == activeId }?.nickname.orEmpty()
    }

    data class Qr(val token: String, val image: ByteArray)

    enum class QrStatus { WAITING, SCANNED, CONFIRMED, EXPIRED, REFUSED }

    data class FeedMode(
        val id: String,
        val name: String,
        val group: String = "",
        val coverUrl: String? = null,
        val preference: String? = null,
    ) {
        val isDefault: Boolean get() = id == DEFAULT_MODE_ID && preference == null
    }

    private data class Session(
        val id: String,
        val nickname: String,
        val isVip: Boolean,
        val deviceId: String,
        val accessCode: String,
        val openId: String,
        val ticket: String,
        val guardKeyB64: String,
        val guardCert: String,
        val guardTsSign: String,
        val expiresAt: Long,
    )

    private data class FeedCursor(var count: Int = 0, val selectedAt: Long = System.currentTimeMillis())

    private val prefs = context.getSharedPreferences("qishui_account", Context.MODE_PRIVATE)
    private val deviceId: String = prefs.getString("device_id", null)
        ?.takeIf { it.matches(Regex("^\\d{19}$")) }
        ?: QishuiProtocol.createDeviceId().also {
            prefs.edit().putString("device_id", it).apply()
        }

    private val sessions = restore().toMutableList()
    private var activeId: String? = prefs.getString("active_id", null)?.takeIf { id ->
        sessions.any { it.id == id }
    } ?: sessions.firstOrNull()?.id

    private val mutableState = MutableStateFlow(snapshot())
    val state = mutableState.asStateFlow()

    private val tokenMutex = Mutex()
    @Volatile private var cachedClientToken: String? = null

    private val feedMutex = Mutex()
    private val feedCursors = ConcurrentHashMap<String, FeedCursor>()
    private var feedTotalRequests = 0

    private val noRedirectClient: OkHttpClient by lazy {
        client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    }

    private fun snapshot() = State(sessions.map { Account(it.id, it.nickname, it.isVip) }, activeId)

    private fun activeSession(): Session? = sessions.firstOrNull { it.id == activeId }

    private fun sessionFor(accountId: String?): Session =
        sessions.firstOrNull { it.id == (accountId ?: activeId) }
            ?: throw IllegalStateException("请先登录汽水音乐账号")

    fun select(id: String) {
        if (sessions.none { it.id == id }) return
        activeId = id
        resetFeedState()
        persist()
        mutableState.value = snapshot()
    }

    fun disconnect() {
        sessions.removeAll { it.id == activeId }
        activeId = sessions.firstOrNull()?.id
        resetFeedState()
        persist()
        mutableState.value = snapshot()
    }

    private fun resetFeedState() {
        feedCursors.clear()
        feedTotalRequests = 0
    }

    // MARK: - QR Login

    suspend fun newQr(): Qr = withContext(Dispatchers.IO) {
        val url = "https://open.douyin.com/oauth/get_qrcode/?${buildQuery(qrBaseParams())}"
        val json = executeJson(Request.Builder().url(url).header("User-Agent", QishuiProtocol.UA).build())
        val data = json.optJSONObject("data") ?: throw IllegalStateException("无法获取汽水登录二维码")
        val token = data.optString("token")
        val qrB64 = data.optString("qrcode")
        val bytes = qrB64.decodeBase64()?.toByteArray() ?: ByteArray(0)
        if (token.isBlank() || bytes.isEmpty()) throw IllegalStateException("汽水登录二维码无效")
        Qr(token, bytes)
    }

    suspend fun pollQr(qr: Qr): QrStatus = withContext(Dispatchers.IO) {
        val params = qrBaseParams() + mapOf(
            "token" to qr.token,
            "timestamp" to System.currentTimeMillis().toString(),
        )
        val url = "https://open.douyin.com/oauth/check_qrcode/?${buildQuery(params)}"
        val json = executeJson(Request.Builder().url(url).header("User-Agent", QishuiProtocol.UA).build())
        val data = json.optJSONObject("data") ?: throw IllegalStateException("二维码状态无效")
        when (data.optString("status")) {
            "new" -> QrStatus.WAITING
            "scanned" -> QrStatus.SCANNED
            "expired" -> QrStatus.EXPIRED
            "refused" -> QrStatus.REFUSED
            "confirmed" -> {
                val code = data.optString("code").ifBlank { data.optString("auth_code") }
                if (code.isBlank()) throw IllegalStateException("未获取到汽水授权码")
                completeLogin(code)
                QrStatus.CONFIRMED
            }
            else -> throw IllegalStateException("未知的汽水扫码状态")
        }
    }

    private suspend fun completeLogin(authCode: String) {
        val accessUrl = platformUrl(
            base = "https://open.qishui.com",
            path = "/access/code/get/",
            extras = mapOf("auth_code" to authCode),
            devId = deviceId,
        )
        val accessJson = executeJson(Request.Builder().url(accessUrl).header("User-Agent", QishuiProtocol.UA).build())
        val access = accessJson.optJSONObject("data") ?: accessJson
        val accessCode = access.optString("access_code")
        val openId = access.optString("open_id").ifBlank { access.optString("open_uid") }
        if (accessCode.isBlank()) throw IllegalStateException("汽水授权凭据缺失")

        val guard = QishuiProtocol.createGuard()
        val guardResp = requestAccessTicket(
            accessCode = accessCode,
            openId = openId,
            csrB64 = guard.csrB64,
            clientCert = null,
        )
        val expiresAt = QishuiProtocol.parseExpiryMs(access.opt("expires_time"))
        val provisional = Session(
            id = "",
            nickname = "",
            isVip = false,
            deviceId = deviceId,
            accessCode = accessCode,
            openId = openId,
            ticket = guardResp.ticket,
            guardKeyB64 = guard.privateKeyPkcs8B64,
            guardCert = guardResp.guardCert,
            guardTsSign = guardResp.guardTsSign,
            expiresAt = expiresAt,
        )
        val meData = platformPost("/me/info/", JSONObject(), requireAuth = true, explicitSession = provisional)
        val user = meData.optJSONObject("my_info") ?: throw IllegalStateException("未能读取汽水账号信息")
        val userId = user.opt("id")?.toString()?.trim().orEmpty()
        if (userId.isBlank()) throw IllegalStateException("汽水账号 ID 缺失")
        val nickname = user.optString("nickname").ifBlank { "汽水用户 $userId" }
        val isVip = user.optBoolean("is_vip", false)

        val finalSession = provisional.copy(id = userId, nickname = nickname, isVip = isVip)
        sessions.removeAll { it.id == userId }
        sessions.add(0, finalSession)
        activeId = userId
        resetFeedState()
        persist()
        mutableState.value = snapshot()
    }

    private fun requestAccessTicket(
        accessCode: String,
        openId: String,
        csrB64: String?,
        clientCert: String?,
    ): QishuiProtocol.GuardServerData {
        val url = "https://open-client.douyin.com/passport/open/get_access_ticket/?" + buildQuery(
            mapOf(
                "aid" to "1128",
                "client_key" to QishuiProtocol.CLIENT_KEY,
                "device_platform" to "android",
            )
        )
        val form = FormBody.Builder()
            .add("access_code", accessCode)
            .add("open_id", openId)
            .build()
        val reqBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", QishuiProtocol.UA)
            .header("bd-ticket-guard-version", "1")
            .post(form)
        if (!csrB64.isNullOrBlank()) {
            reqBuilder.header("bd-ticket-guard-client-csr", csrB64)
        } else if (!clientCert.isNullOrBlank()) {
            reqBuilder.header("bd-ticket-guard-client-cert", clientCert)
        }
        noRedirectClient.newCall(reqBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("汽水票据签发失败 (HTTP ${resp.code})")
            val serverHeader = resp.header("bd-ticket-guard-server-data")
                ?: throw IllegalStateException("汽水票据头缺失")
            return QishuiProtocol.parseGuardServerData(serverHeader)
        }
    }

    private suspend fun ensureFreshSession(session: Session): Session {
        if (session.expiresAt >= System.currentTimeMillis() + 3600_000L) return session
        return runCatching {
            withContext(Dispatchers.IO) {
                val url = platformUrl(
                    base = "https://open.qishui.com",
                    path = "/access/code/refresh/",
                    extras = mapOf("access_code" to session.accessCode),
                    devId = session.deviceId,
                )
                val json = executeJson(Request.Builder().url(url).header("User-Agent", QishuiProtocol.UA).build())
                val access = json.optJSONObject("data") ?: json
                val newAccessCode = access.optString("access_code").ifBlank { session.accessCode }
                val newOpenId = access.optString("open_id")
                    .ifBlank { access.optString("open_uid") }
                    .ifBlank { session.openId }
                val guardResp = requestAccessTicket(
                    accessCode = newAccessCode,
                    openId = newOpenId,
                    csrB64 = null,
                    clientCert = session.guardCert,
                )
                val refreshed = session.copy(
                    accessCode = newAccessCode,
                    openId = newOpenId,
                    ticket = guardResp.ticket,
                    guardCert = guardResp.guardCert,
                    guardTsSign = guardResp.guardTsSign,
                    expiresAt = QishuiProtocol.parseExpiryMs(access.opt("expires_time")),
                )
                val idx = sessions.indexOfFirst { it.id == session.id }
                if (idx >= 0) {
                    sessions[idx] = refreshed
                    persist()
                }
                refreshed
            }
        }.getOrElse {
            Log.w(TAG, "session refresh failed, continuing with current ticket: ${it.message}")
            session
        }
    }

    // MARK: - Radio / FM Feed

    /** Fetch all scene/mood radio modes from Qishui `/feed/mode/`. Works both logged-in and guest. */
    suspend fun fetchFeedModes(): List<FeedMode> = withContext(Dispatchers.IO) {
        val data = platformPost("/feed/mode/", JSONObject(), requireAuth = false)
        val blocks = data.optJSONArray("feed_mode_block") ?: return@withContext emptyList()
        val out = mutableListOf<FeedMode>()
        for (i in 0 until blocks.length()) {
            val block = blocks.optJSONObject(i) ?: continue
            val group = block.optString("title")
            val modes = block.optJSONArray("feed_mode") ?: continue
            for (j in 0 until modes.length()) {
                val item = modes.optJSONObject(j) ?: continue
                val mode = item.optJSONObject("entity")?.optJSONObject("feed_mode") ?: continue
                val id = mode.optJSONObject("scene_mode")?.opt("scene_mode_id")?.toString()?.trim().orEmpty()
                val name = mode.optString("text").trim()
                if (id.isNotEmpty() && name.isNotEmpty()) {
                    out += FeedMode(
                        id = id,
                        name = name,
                        group = group,
                        coverUrl = QishuiProtocol.normalizeImage(mode.opt("url_info")),
                    )
                }
            }
        }
        out.distinctBy { it.id }
    }

    /** Fetch a batch of radio tracks for default mode, preference mode (familiar/fresh), or scene mode. */
    suspend fun fetchFeedSongs(
        mode: FeedMode? = null,
        first: Boolean = false,
        count: Int = 20,
    ): List<Track> = feedMutex.withLock {
        withContext(Dispatchers.IO) {
            val isDefault = mode == null || mode.isDefault
            val preference = mode?.preference?.takeIf { it.isNotBlank() }
            val sceneId = if (!isDefault && preference == null) mode?.id?.toLongOrNull() else null
            val cursorKey = when {
                sceneId != null -> "scene:$sceneId"
                preference != null -> "pref:$preference"
                else -> "default"
            }
            if (feedCursors.size > 60) feedCursors.clear()
            val prev = feedCursors[cursorKey]
            val cursor = if (first || prev == null) FeedCursor() else prev
            val totalReq = ++feedTotalRequests
            val prefObj = JSONObject()
                .put("scene_mode_id", sceneId ?: JSONObject.NULL)
                .put("scene_mode_select_time", if (sceneId != null) cursor.selectedAt else JSONObject.NULL)
                .put("preference_mode", preference ?: JSONObject.NULL)
                .put("total_request_count", totalReq)
                .put("preference_mode_request_count", cursor.count + 1)
                .put("current_mode_request_count", cursor.count + 1)
            val body = JSONObject()
                .put("played_media", JSONArray())
                .put("is_first_request", cursor.count == 0)
                .put("with_client_cache", cursor.count != 0)
                .put("is_did_first_request", false)
                .put("count", count.coerceIn(1, 50))
                .put("feed_preference", prefObj)
            val data = platformPost("/feed/song-tab/", body, requireAuth = false)
            cursor.count++
            feedCursors[cursorKey] = cursor
            val items = data.optJSONArray("items") ?: return@withContext emptyList()
            val tracks = mutableListOf<Track>()
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val type = item.optString("type")
                if (type.isNotEmpty() && type != "track") continue
                parseTrack(item)?.let(tracks::add)
            }
            tracks.distinctBy { it.id }
        }
    }

    // MARK: - Account Playlists

    suspend fun playlists(accountId: String? = null): List<SonglistInfo> = withContext(Dispatchers.IO) {
        val s = ensureFreshSession(sessionFor(accountId))
        val liked = mutableListOf<SonglistInfo>()
        val owned = mutableListOf<SonglistInfo>()
        val collected = mutableListOf<SonglistInfo>()

        // 1. Owned & Liked playlists (/me/playlist/)
        walkUserPlaylists(
            path = "/me/playlist/",
            fields = listOf("playlists", "playlist_list"),
            session = s,
        ).forEach { (info, isLiked) ->
            if (isLiked) liked += info else owned += info
        }

        // 2. Collected playlists (/me/collection/mixed/)
        runCatching {
            walkUserPlaylists(
                path = "/me/collection/mixed/",
                fields = listOf("mixed_collections"),
                session = s,
            ).forEach { (info, _) -> collected += info }
        }

        (liked + owned + collected).filter { it.name.isNotBlank() }.distinctBy { it.id }
    }

    private suspend fun walkUserPlaylists(
        path: String,
        fields: List<String>,
        session: Session,
    ): List<Pair<SonglistInfo, Boolean>> {
        val out = mutableListOf<Pair<SonglistInfo, Boolean>>()
        var cursor = ""
        val seen = HashSet<String>()
        for (page in 0 until 20) {
            seen.add(cursor)
            val body = JSONObject()
                .put("count", 100)
                .put("cursor", cursor)
                .put("item_types", JSONArray().put("playlist"))
            val data = platformPost(path, body, requireAuth = true, explicitSession = session)
            val rows = fields.firstNotNullOfOrNull { data.optJSONArray(it) } ?: break
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                val pl = row.optJSONObject("playlist") ?: row
                parsePlaylist(pl)?.let { (info, isLiked) -> out += info to isLiked }
            }
            if (!data.optBoolean("has_more", false)) break
            val next = data.opt("next_cursor")?.toString().orEmpty()
            if (next.isBlank() || next in seen) break
            cursor = next
        }
        return out
    }

    /** Fetch tracks for any Qishui playlist (account-owned or public recommended). */
    suspend fun playlistTracks(
        playlistId: String,
        ownerId: String = "",
        maxTracks: Int = 300,
    ): List<Track> = withContext(Dispatchers.IO) {
        val out = mutableListOf<Track>()
        var cursor = "0"
        val seen = HashSet<String>()
        for (page in 0 until 10) {
            if (out.size >= maxTracks) break
            seen.add(cursor)
            val body = JSONObject()
                .put("playlist_id", playlistId)
                .put("owner_id", ownerId)
                .put("count", 100)
                .put("cursor", cursor)
                .put("type", JSONObject.NULL)
            val data = platformPost("/playlist/detail/", body, requireAuth = false)
            val arr = data.optJSONArray("media_entities") ?: data.optJSONArray("media_list") ?: break
            for (i in 0 until arr.length()) {
                parseTrack(arr.optJSONObject(i))?.let(out::add)
            }
            if (!data.optBoolean("has_more", false)) break
            val next = data.opt("next_cursor")?.toString().orEmpty()
            if (next.isBlank() || next in seen) break
            cursor = next
        }
        out.distinctBy { it.id }.take(maxTracks)
    }

    // MARK: - Search & Recommended Playlists

    suspend fun searchPlaylists(keyword: String, limit: Int = 24, offset: Int = 0): List<SonglistInfo> =
        withContext(Dispatchers.IO) {
            val q = keyword.trim()
            if (q.isEmpty()) return@withContext emptyList()
            val searchId = UUID.randomUUID().toString()
            val out = mutableListOf<SonglistInfo>()
            var cursor = offset
            for (step in 0 until 3) {
                if (out.size >= limit) break
                val body = JSONObject()
                    .put("q", q)
                    .put("cursor", cursor.toString())
                    .put("search_id", searchId)
                    .put("from_search_id", searchId)
                    .put("search_method", "input")
                    .put("search_type", "playlist")
                    .put("search_scene", "normal")
                    .put("scene_name", "search")
                    .put("ab_param", "")
                    .put("last_viewed_media", JSONObject())
                val data = platformPost("/search/", body, requireAuth = false)
                val groups = data.optJSONArray("result_groups") ?: break
                val group = findSearchGroup(groups, "playlists") ?: break
                val rows = group.optJSONArray("data") ?: break
                var added = 0
                for (i in 0 until rows.length()) {
                    val pl = rows.optJSONObject(i)?.optJSONObject("entity")?.optJSONObject("playlist") ?: continue
                    parsePlaylist(pl)?.first?.let {
                        out += it
                        added++
                    }
                }
                if (added == 0 || !group.optBoolean("has_more", false)) break
                val next = group.optString("next_cursor").toIntOrNull() ?: break
                if (next <= cursor) break
                cursor = next
            }
            out.distinctBy { it.id }.take(limit)
        }

    suspend fun searchTracks(keyword: String, limit: Int = 30, offset: Int = 0): List<Track> =
        withContext(Dispatchers.IO) {
            val q = keyword.trim()
            if (q.isEmpty()) return@withContext emptyList()
            val searchId = UUID.randomUUID().toString()
            val body = JSONObject()
                .put("q", q)
                .put("cursor", offset.toString())
                .put("search_id", searchId)
                .put("from_search_id", searchId)
                .put("search_method", "input")
                .put("search_type", "track")
                .put("search_scene", "normal")
                .put("scene_name", "search")
                .put("ab_param", "")
                .put("last_viewed_media", JSONObject())
            val data = platformPost("/search/", body, requireAuth = false)
            val groups = data.optJSONArray("result_groups") ?: return@withContext emptyList()
            val group = findSearchGroup(groups, "tracks") ?: return@withContext emptyList()
            val rows = group.optJSONArray("data") ?: return@withContext emptyList()
            val out = mutableListOf<Track>()
            for (i in 0 until rows.length()) {
                val trackObj = rows.optJSONObject(i)?.optJSONObject("entity")?.optJSONObject("track") ?: continue
                parseTrack(trackObj)?.let(out::add)
            }
            out.distinctBy { it.id }.take(limit)
        }

    // MARK: - Lyrics & Plaintext Audio Fallback

    suspend fun fetchLyrics(trackId: String): List<LyricLine>? = withContext(Dispatchers.IO) {
        val entity = fetchTrackEntity(trackId) ?: return@withContext null
        val lyricInfo = entity.optJSONObject("display_info")?.optJSONObject("lyric_info") ?: return@withContext null
        val mainEntity = lyricInfo.optJSONObject("lyric_entity") ?: return@withContext null
        val rawContent = mainEntity.optString("content").replace("\u0000", "").trim()
        if (rawContent.isEmpty()) return@withContext null
        val mainLrc = if (mainEntity.optString("type").equals("krc", ignoreCase = true)) {
            QishuiProtocol.krcToLrc(rawContent)
        } else {
            rawContent
        }
        val translations = lyricInfo.optJSONObject("lang_translations")
        val transObj = translations?.optJSONObject("zh-CN")
            ?: translations?.optJSONObject("ZH-HANS-CN")
            ?: translations?.keys()?.asSequence()?.firstOrNull()?.let { translations.optJSONObject(it) }
        val rawTrans = transObj?.optString("content")?.replace("\u0000", "")?.trim().orEmpty()
        val transLrc = if (rawTrans.isEmpty()) null else {
            if (transObj?.optString("type").equals("krc", ignoreCase = true)) {
                QishuiProtocol.krcToLrc(rawTrans)
            } else {
                rawTrans
            }
        }
        LyricParser.parse(mainLrc, transLrc).ifEmpty { null }
    }

    /**
     * Last-resort plaintext audio URL resolver via Qishui car `/media/mget/`.
     * Never decrypts CENC/spade_a streams — only returns unencrypted URLs when present.
     */
    suspend fun resolvePlaintextAudio(trackId: String, preferred: Quality): String? = withContext(Dispatchers.IO) {
        val entity = fetchTrackEntity(trackId) ?: return@withContext null
        val player = entity.optJSONObject("player_info") ?: return@withContext null
        val requestedRank = when (preferred) {
            Quality.K128 -> 0
            Quality.K320 -> 1
            Quality.FLAC -> 2
            Quality.FLAC24, Quality.HIRES, Quality.ATMOS, Quality.ATMOS_PLUS, Quality.MASTER -> 3
        }
        data class Candidate(val url: String, val rank: Int, val bitrate: Long)
        for (variant in listOf("full", "preview")) {
            val vmInfo = player.optJSONObject(variant)?.optJSONObject("video_model_info") ?: continue
            val candidates = mutableListOf<Candidate>()
            val rawVm = vmInfo.opt("video_model")
            val vmObj = when (rawVm) {
                is JSONObject -> rawVm
                is String -> runCatching { JSONObject(rawVm) }.getOrNull()
                else -> null
            }
            val list = vmObj?.optJSONArray("video_list")
            if (list != null) {
                for (i in 0 until list.length()) {
                    val item = list.optJSONObject(i) ?: continue
                    if (isEncryptedAudio(item)) continue
                    val u = item.optString("main_url").trim()
                    if (!u.startsWith("https://")) continue
                    val meta = item.optJSONObject("video_meta")
                    val qRank = qishuiQualityRank(meta?.optString("quality"))
                    val br = meta?.optLong("bitrate", 0L) ?: 0L
                    candidates += Candidate(u, qRank, br)
                }
            }
            if (candidates.isEmpty()) {
                val playInfoUrl = vmInfo.optString("url_player_info").trim()
                if (playInfoUrl.startsWith("https://")) {
                    val infoJson = runCatching {
                        executeJson(Request.Builder().url(playInfoUrl).header("User-Agent", QishuiProtocol.UA).build())
                    }.getOrNull()
                    val piList = infoJson?.optJSONObject("Result")?.optJSONObject("Data")?.optJSONArray("PlayInfoList")
                        ?: infoJson?.optJSONObject("data")?.optJSONArray("PlayInfoList")
                        ?: infoJson?.optJSONArray("PlayInfoList")
                    if (piList != null) {
                        for (i in 0 until piList.length()) {
                            val item = piList.optJSONObject(i) ?: continue
                            if (isEncryptedAudio(item)) continue
                            val u = item.optString("MainPlayUrl").trim()
                            if (!u.startsWith("https://")) continue
                            candidates += Candidate(
                                url = u,
                                rank = qishuiQualityRank(item.optString("Quality")),
                                bitrate = item.optLong("Bitrate", 0L),
                            )
                        }
                    }
                }
            }
            if (candidates.isNotEmpty()) {
                val allowed = candidates.filter { it.rank <= requestedRank }
                val pool = allowed.ifEmpty { candidates }
                val best = pool.maxWithOrNull(compareBy<Candidate> { it.rank }.thenBy { it.bitrate })
                if (best != null) return@withContext best.url
            }
        }
        null
    }

    private suspend fun fetchTrackEntity(trackId: String): JSONObject? {
        if (!trackId.matches(Regex("^\\d{1,30}$"))) return null
        val body = JSONObject()
            .put("media_list", JSONArray().put(JSONObject().put("id", trackId).put("type", "track")))
            .put("card_context", JSONObject())
        val data = platformPost("/media/mget/", body, requireAuth = false)
        val arr = data.optJSONArray("media_list") ?: return null
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val entity = item.optJSONObject("track_entity") ?: continue
            val mid = item.optString("media_id").ifBlank { entity.optJSONObject("base_info")?.optString("id").orEmpty() }
            if (mid == trackId) return entity
        }
        return arr.optJSONObject(0)?.optJSONObject("track_entity")
    }

    private fun isEncryptedAudio(item: JSONObject): Boolean {
        val encInfo = item.optJSONObject("encrypt_info")
        if (encInfo != null) {
            if (encInfo.optBoolean("encrypt", false)) return true
            if (encInfo.optString("spade_a").isNotBlank()) return true
        }
        if (item.optString("PlayAuth").isNotBlank()) return true
        if (item.optInt("Encrypt", 0) != 0) return true
        return false
    }

    private fun qishuiQualityRank(raw: String?): Int = when (raw?.lowercase()) {
        "hi_res", "hires" -> 3
        "lossless" -> 2
        "higher", "highest", "high" -> 1
        else -> 0
    }

    // MARK: - HTTP / Protocol Plumbing

    private suspend fun clientToken(forceRefresh: Boolean = false): String = tokenMutex.withLock {
        if (!forceRefresh) cachedClientToken?.let { return@withLock it }
        val url = platformUrl(
            base = "https://open.qishui.com",
            path = "/client/token/get/",
            extras = mapOf("trigger_type" to "cold_start"),
            devId = activeSession()?.deviceId ?: deviceId,
        )
        val json = executeJson(Request.Builder().url(url).header("User-Agent", QishuiProtocol.UA).build())
        val token = json.optString("client_token")
            .ifBlank { json.optJSONObject("data")?.optString("client_token").orEmpty() }
        if (token.isBlank()) throw IllegalStateException("汽水访客令牌获取失败")
        cachedClientToken = token
        token
    }

    private suspend fun platformPost(
        path: String,
        body: JSONObject,
        requireAuth: Boolean,
        explicitSession: Session? = null,
    ): JSONObject {
        val authSession = when {
            explicitSession != null -> explicitSession
            requireAuth -> ensureFreshSession(activeSession() ?: throw IllegalStateException("请先登录汽水音乐账号"))
            else -> null
        }
        val contextSession = authSession ?: activeSession()
        val devId = contextSession?.deviceId ?: deviceId
        val payload = JSONObject(body.toString())
        val commonParams = JSONObject()
        if (!contextSession?.id.isNullOrBlank()) {
            commonParams.put("user_id", contextSession!!.id)
        }
        payload.put("common_params", commonParams)
        val payloadBytes = payload.toString().toRequestBody(JSON_MEDIA)

        if (authSession != null) {
            val url = platformUrl("https://open.douyin.com", path, emptyMap(), devId)
            val headers = QishuiProtocol.buildGuardHeaders(
                ticket = authSession.ticket,
                guardKeyB64 = authSession.guardKeyB64,
                guardCert = authSession.guardCert,
                guardTsSign = authSession.guardTsSign,
                path = path,
            )
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", QishuiProtocol.UA)
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .post(payloadBytes)
                .build()
            val resp = executeJson(req)
            return resp.optJSONObject("data") ?: JSONObject()
        }

        // Public / feed / catalog call using client_token (+ optional user_id in common_params).
        var lastError: Throwable? = null
        for (attempt in 0..1) {
            val token = clientToken(forceRefresh = attempt > 0)
            val url = platformUrl("https://open.douyin.com", path, emptyMap(), devId)
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", QishuiProtocol.UA)
                .header("access-token", token)
                .post(payloadBytes)
                .build()
            try {
                val resp = executeJson(req)
                return resp.optJSONObject("data") ?: JSONObject()
            } catch (e: QishuiUpstreamException) {
                lastError = e
                if (e.code == 28001008 && attempt == 0) {
                    cachedClientToken = null
                    continue
                }
                throw e
            }
        }
        throw lastError ?: IllegalStateException("汽水音乐接口请求失败")
    }

    private class QishuiUpstreamException(val code: Int, message: String) : IllegalStateException(message)

    private fun executeJson(request: Request): JSONObject {
        noRedirectClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("汽水接口 HTTP ${resp.code}")
            val text = resp.body?.string().orEmpty()
            val json = JSONObject(text)
            val errNo = if (json.has("err_no")) json.optInt("err_no", 0)
            else json.optJSONObject("data")?.optInt("error_code", 0) ?: 0
            if (errNo != 0) {
                val msg = json.optString("err_tips")
                    .ifBlank { json.optString("message") }
                    .ifBlank { "汽水接口返回错误 ($errNo)" }
                throw QishuiUpstreamException(errNo, msg)
            }
            return json
        }
    }

    // MARK: - JSON Normalizers

    private fun parseTrack(raw: JSONObject?): Track? {
        raw ?: return null
        val entity = raw.optJSONObject("track_entity")
            ?: raw.optJSONObject("entity")?.optJSONObject("media")?.optJSONObject("track_entity")
            ?: raw.optJSONObject("entity")?.optJSONObject("track")
            ?: raw.optJSONObject("track")
            ?: raw
        val base = entity.optJSONObject("base_info")
        val id = (base?.opt("id") ?: entity.opt("id"))?.toString()?.trim().orEmpty()
        if (id.isEmpty() || !id.all { it.isDigit() }) return null
        val name = base?.optString("name")?.trim().orEmpty().ifBlank { "未知曲目" }
        val durationSec = ((base?.optLong("duration_ms", 0L) ?: 0L) / 1000L).toInt().takeIf { it > 0 }
        val related = entity.optJSONObject("related_info")
        val album = related?.optJSONObject("album_link")
        val artistArr = related?.optJSONArray("artist_links")
            ?: base?.optJSONObject("song_maker_team")?.optJSONArray("composers")
        val artists = mutableListOf<String>()
        if (artistArr != null) {
            for (i in 0 until artistArr.length()) {
                val n = artistArr.optJSONObject(i)?.optString("name")?.trim().orEmpty()
                if (n.isNotEmpty()) artists += n
            }
        }
        val singer = artists.joinToString(" / ").ifBlank { "未知歌手" }
        val cover = QishuiProtocol.normalizeImage(entity.optJSONObject("display_info")?.opt("cover_url"))
        return Track(
            id = Track.makeID(SourceID.QS, id),
            name = name,
            singer = singer,
            albumName = album?.optString("name")?.trim()?.ifEmpty { null },
            albumId = album?.opt("id")?.toString()?.trim()?.ifEmpty { null },
            source = SourceID.QS,
            songmid = id,
            duration = durationSec,
            picURL = cover,
            // Leave qualities empty so SourceManager's preferred-quality cascade tries the user's
            // preferred quality on their LX script and steps down smoothly if unavailable.
            qualities = emptyList(),
        )
    }

    private fun parsePlaylist(raw: JSONObject?): Pair<SonglistInfo, Boolean>? {
        raw ?: return null
        val base = raw.optJSONObject("base_info") ?: return null
        val id = base.opt("id")?.toString()?.trim().orEmpty()
        if (id.isEmpty()) return null
        val name = base.optString("title").ifBlank { base.optString("name") }.trim()
        if (name.isEmpty()) return null
        val owner = raw.optJSONObject("relation_info")?.optJSONObject("owner")
        val ownerId = base.opt("owner_id")?.toString()?.trim()?.ifEmpty { null }
            ?: owner?.opt("id")?.toString()?.trim()?.ifEmpty { null }
        val author = owner?.optString("nickname")?.trim().orEmpty()
        val cover = QishuiProtocol.normalizeImage(raw.optJSONObject("display_info")?.opt("url_cover"))
        val trackCount = raw.optJSONObject("resource_cnt")?.optInt("track_cnt", 0)?.takeIf { it > 0 }
        val isLiked = base.optInt("type", 0) == 1
        val info = SonglistInfo(
            id = id,
            source = SourceID.QS,
            name = name,
            author = author,
            picURL = cover,
            trackCount = trackCount,
            ownerId = ownerId,
        )
        return info to isLiked
    }

    private fun findSearchGroup(groups: JSONArray, expectedId: String): JSONObject? {
        for (i in 0 until groups.length()) {
            val g = groups.optJSONObject(i) ?: continue
            if (g.optString("id") == expectedId) return g
        }
        return groups.optJSONObject(0)
    }

    private fun qrBaseParams(): Map<String, String> = linkedMapOf(
        "aid" to "1128",
        "client_key" to QishuiProtocol.CLIENT_KEY,
        "scope" to "user_info,luna.openapi.platform.interaction,luna.openapi.platform.me_tab,luna.commerce.openapi_platform.vip_info",
        "state" to "",
        "next" to "",
        "jump_type" to "native",
        "optional_scope_check" to "",
        "optional_scope_uncheck" to "",
        "customize_params" to """{"source":"pc_auth","not_skip_confirm":"true"}""",
        "source_from" to "native",
        "device_platform" to "android",
        "app_identity" to QishuiProtocol.md5Hex("com.luna.music.car"),
        "signature" to "cf9024ea72d9c71813238b13ea107a3d",
    )

    private fun platformUrl(
        base: String,
        path: String,
        extras: Map<String, String>,
        devId: String,
    ): String {
        val params = QishuiProtocol.commonParams(devId) + extras
        return "$base${QishuiProtocol.PREFIX}$path?${buildQuery(params)}"
    }

    private fun buildQuery(params: Map<String, String>): String =
        params.entries.joinToString("&") { (k, v) -> "${urlEncode(k)}=${urlEncode(v)}" }

    // MARK: - Persistence

    private fun persist() {
        val arr = JSONArray()
        sessions.forEach { s ->
            arr.put(
                JSONObject()
                    .put("id", s.id)
                    .put("nickname", s.nickname)
                    .put("isVip", s.isVip)
                    .put("deviceId", s.deviceId)
                    .put("accessCode", s.accessCode)
                    .put("openId", s.openId)
                    .put("ticket", s.ticket)
                    .put("guardKeyB64", s.guardKeyB64)
                    .put("guardCert", s.guardCert)
                    .put("guardTsSign", s.guardTsSign)
                    .put("expiresAt", s.expiresAt),
            )
        }
        prefs.edit()
            .putString("sessions", arr.toString())
            .putString("active_id", activeId)
            .apply()
    }

    private fun restore(): List<Session> {
        val raw = prefs.getString("sessions", null) ?: return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id")
            val ticket = o.optString("ticket")
            val guardKey = o.optString("guardKeyB64")
            val guardCert = o.optString("guardCert")
            val guardTsSign = o.optString("guardTsSign")
            if (id.isBlank() || ticket.isBlank() || guardKey.isBlank() || guardCert.isBlank() || guardTsSign.isBlank()) {
                return@mapNotNull null
            }
            Session(
                id = id,
                nickname = o.optString("nickname").ifBlank { "汽水用户 $id" },
                isVip = o.optBoolean("isVip", false),
                deviceId = o.optString("deviceId").ifBlank { deviceId },
                accessCode = o.optString("accessCode"),
                openId = o.optString("openId"),
                ticket = ticket,
                guardKeyB64 = guardKey,
                guardCert = guardCert,
                guardTsSign = guardTsSign,
                expiresAt = o.optLong("expiresAt", 0L),
            )
        }
    }

    companion object {
        private const val TAG = "QishuiAccount"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        const val DEFAULT_MODE_ID = "default"

        val DEFAULT_FEED_MODE = FeedMode(id = DEFAULT_MODE_ID, name = "默认模式", group = "默认")
        val BUILTIN_PREFERENCE_MODES = listOf(
            FeedMode(id = "pref_familiar", name = "熟悉模式", group = "口味偏好", preference = "familiar"),
            FeedMode(id = "pref_fresh", name = "新鲜探索", group = "口味偏好", preference = "fresh"),
        )
    }
}

/**
 * Pure-JVM cryptographic & normalization helpers for Qishui Car OpenSDK protocol
 * (separated from Android Context so they can be verified directly by unit tests).
 */
object QishuiProtocol {
    const val UA = "LunaOpenSDK/1.0.10"
    const val PREFIX = "/api/luna/v1/platform"
    const val CLIENT_KEY = "aw4bbxfujo741d09"

    private val random = SecureRandom()
    private val krcLineRegex = Regex("""^\[(\d+),(\d+)](.*)$""")
    private val krcWordTagRegex = Regex("""<\d+,\d+,\d+>""")

    data class GeneratedGuard(val privateKeyPkcs8B64: String, val csrB64: String)
    data class GuardServerData(val ticket: String, val guardTsSign: String, val guardCert: String)

    fun createDeviceId(): String {
        val bytes = ByteArray(8)
        random.nextBytes(bytes)
        val unsigned = BigInteger(1, bytes)
        val mod = BigInteger("9000000000000000000")
        val base = BigInteger("1000000000000000000")
        return unsigned.mod(mod).add(base).toString()
    }

    fun commonParams(deviceId: String): Map<String, String> = linkedMapOf(
        "aid" to "765213",
        "app_id" to "765213",
        "app_name" to "byte_autoservice_lite",
        "version_code" to "100010",
        "version_name" to "1.0.10",
        "channel" to "bydSong",
        "sdk_aid" to "753999",
        "caller_name" to "luna_open_sdk",
        "sdk_version_code" to "100000100",
        "sdk_version_name" to "0.0.1",
        "sdk_channel" to "Test",
        "vehicle_type" to "BYD",
        "device_brand" to "BYD",
        "device_type" to "BYD",
        "device_platform" to "android",
        "resolution" to "1920x1080",
        "os_version" to "13",
        "ac" to "wifi",
        "device_id" to deviceId,
        "did" to deviceId,
        "iid" to deviceId,
        "vehicle_id" to "qishui-$deviceId",
        "_timestamp" to (System.currentTimeMillis() / 1000L).toString(),
        "_nonce" to random.nextInt(65536).toString(),
    )

    fun md5Hex(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** Generate a fresh EC P-256 (secp256r1) keypair and PKCS#10 DER/PEM CSR for ticket-guard. */
    fun createGuard(): GeneratedGuard {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"), random)
        val pair = kpg.generateKeyPair()

        val subjectEntries = listOf(3 to "bd-ticket-guard", 11 to "", 10 to "").map { (id, value) ->
            der(
                0x31,
                der(
                    0x30,
                    der(0x06, byteArrayOf(0x55, 0x04, id.toByte())),
                    der(0x0C, value.toByteArray(Charsets.UTF_8)),
                ),
            )
        }
        val subject = der(0x30, *subjectEntries.toTypedArray())
        val info = der(
            0x30,
            der(0x02, byteArrayOf(0x00)),
            subject,
            pair.public.encoded,
            der(0xA0),
        )
        // OID 1.2.840.10045.4.3.2 (ecdsa-with-SHA256)
        val ecdsaSha256Oid = byteArrayOf(
            0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x04, 0x03, 0x02,
        )
        val algorithm = der(0x30, der(0x06, ecdsaSha256Oid))
        val sig = Signature.getInstance("SHA256withECDSA").apply {
            initSign(pair.private, random)
            update(info)
        }.sign()
        val requestDer = der(0x30, info, algorithm, der(0x03, byteArrayOf(0x00), sig))
        val b64 = requestDer.toByteString().base64()
        val pem = buildString {
            append("-----BEGIN CERTIFICATE REQUEST-----\n")
            b64.chunked(64).forEach { append(it).append('\n') }
            append("-----END CERTIFICATE REQUEST-----\n")
        }
        return GeneratedGuard(
            privateKeyPkcs8B64 = pair.private.encoded.toByteString().base64(),
            csrB64 = pem.toByteArray(Charsets.UTF_8).toByteString().base64(),
        )
    }

    fun buildGuardHeaders(
        ticket: String,
        guardKeyB64: String,
        guardCert: String,
        guardTsSign: String,
        path: String,
        timestampSec: Long = System.currentTimeMillis() / 1000L,
    ): Map<String, String> {
        val keyBytes = guardKeyB64.decodeBase64()?.toByteArray()
            ?: throw IllegalStateException("无效的汽水签名密钥")
        val privateKey: PrivateKey = KeyFactory.getInstance("EC")
            .generatePrivate(PKCS8EncodedKeySpec(keyBytes))
        val content = "ticket=$ticket&path=$PREFIX$path&timestamp=$timestampSec"
        val sigBytes = Signature.getInstance("SHA256withECDSA").apply {
            initSign(privateKey, random)
            update(content.toByteArray(Charsets.UTF_8))
        }.sign()
        val reqSign = sigBytes.toByteString().base64()
        val clientDataJson = buildString {
            append("{\"ts_sign\":\"").append(jsonEscape(guardTsSign))
            append("\",\"req_content\":\"ticket,path,timestamp\",\"req_sign\":\"")
            append(jsonEscape(reqSign))
            append("\",\"timestamp\":").append(timestampSec).append('}')
        }
        val clientDataB64 = clientDataJson.toByteArray(Charsets.UTF_8).toByteString().base64()
        return linkedMapOf(
            "access-token" to ticket,
            "bd-ticket-guard-version" to "1",
            "bd-ticket-guard-client-cert" to guardCert,
            "bd-ticket-guard-client-data" to clientDataB64,
        )
    }

    fun parseGuardServerData(rawBase64: String): GuardServerData {
        val decoded = rawBase64.decodeBase64()?.utf8()
            ?: throw IllegalStateException("无法解析汽水服务端票据")
        val obj = JSONObject(decoded)
        val ticket = obj.optString("ticket")
        val tsSign = obj.optString("ts_sign")
        val cert = obj.optString("client_cert")
        if (ticket.isBlank() || tsSign.isBlank() || cert.isBlank()) {
            throw IllegalStateException("汽水票据字段不完整")
        }
        val normalizedCert = if (cert.contains("-----BEGIN")) {
            cert.toByteArray(Charsets.UTF_8).toByteString().base64()
        } else {
            cert
        }
        return GuardServerData(ticket = ticket, guardTsSign = tsSign, guardCert = normalizedCert)
    }

    fun parseExpiryMs(raw: Any?, nowMs: Long = System.currentTimeMillis()): Long {
        val value = raw?.toString()?.toDoubleOrNull()?.toLong() ?: 0L
        return when {
            value > 1_000_000_000_000L -> value
            value > 1_000_000_000L -> value * 1000L
            value > 0L -> nowMs + value * 1000L
            else -> nowMs + 6L * 3600_000L
        }
    }

    /** Normalize Qishui image descriptor ({urls, uri, template_prefix} or direct URL string). */
    fun normalizeImage(raw: Any?): String? {
        if (raw is String) {
            return raw.trim().takeIf { it.startsWith("https://") || it.startsWith("http://") }
        }
        val obj = raw as? JSONObject ?: return null
        val urls = obj.optJSONArray("urls") ?: return null
        var base: String? = null
        for (i in 0 until urls.length()) {
            val candidate = urls.optString(i).trim()
            if (candidate.startsWith("https://")) {
                base = candidate
                break
            }
        }
        val url = base ?: return null
        val uri = obj.optString("uri").trim()
        val template = obj.optString("template_prefix").trim()
        return if (uri.isNotEmpty() && url.endsWith("/")) {
            "$url$uri${if (template.isNotEmpty()) "~$template-image.image" else ""}"
        } else {
            url
        }
    }

    /** Convert plaintext Qishui KRC (`[startMs,durMs]<w,d,0>word...`) into standard LRC lines. */
    fun krcToLrc(krc: String): String {
        val out = StringBuilder()
        krc.replace("\r\n", "\n").replace("\r", "\n").split("\n").forEach { line ->
            val m = krcLineRegex.matchEntire(line.trim()) ?: return@forEach
            val startMs = m.groupValues[1].toLongOrNull() ?: return@forEach
            val text = krcWordTagRegex.replace(m.groupValues[3], "").trim()
            if (text.isEmpty()) return@forEach
            val mins = startMs / 60_000
            val secs = (startMs % 60_000) / 1000
            val cs = (startMs % 1000) / 10
            out.append("[%02d:%02d.%02d]%s\n".format(mins, secs, cs, text))
        }
        return out.toString()
    }

    private fun der(tag: Int, vararg parts: ByteArray): ByteArray {
        val body = ByteArrayOutputStream()
        parts.forEach { body.write(it) }
        val value = body.toByteArray()
        val out = ByteArrayOutputStream()
        out.write(tag)
        when {
            value.size < 128 -> out.write(value.size)
            value.size < 256 -> {
                out.write(0x81)
                out.write(value.size)
            }
            else -> {
                out.write(0x82)
                out.write((value.size shr 8) and 0xFF)
                out.write(value.size and 0xFF)
            }
        }
        out.write(value)
        return out.toByteArray()
    }

    private fun jsonEscape(s: String): String = buildString(s.length) {
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(c)
        }
    }
}
