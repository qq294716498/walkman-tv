package com.walkman.tv.cloud

import android.content.Context
import android.os.Build
import android.util.Log
import com.walkman.tv.data.model.Playlist
import com.walkman.tv.data.model.Quality
import com.walkman.tv.data.model.SourceID
import com.walkman.tv.data.model.Track
import com.walkman.tv.data.store.LibraryStore
import com.walkman.tv.source.catalog.urlEncode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Client for self-hosted `lx-music-sync-server` (v2.x, protocol v4 + message2call v0.1.3).
 *
 * Allows users to enter their own LX Music Sync Server URL + sync password and sync
 * favorites (`loveList`), default list (`defaultList`), and custom playlists (`userList`).
 */
class LxSyncClient(
    context: Context,
    private val httpClient: OkHttpClient,
    private val libraryStore: LibraryStore,
) {
    enum class SyncMode(val wireValue: String, val label: String, val desc: String) {
        MERGE_REMOTE_LOCAL(
            wireValue = "merge_remote_local",
            label = "拉取并合并（云端优先）",
            desc = "保留云端和本地双方歌单，冲突时优先保留云端",
        ),
        OVERWRITE_REMOTE_LOCAL(
            wireValue = "overwrite_remote_local",
            label = "仅用云端覆盖本地",
            desc = "完全同步服务器歌单到电视，不会修改服务器数据",
        ),
        MERGE_LOCAL_REMOTE(
            wireValue = "merge_local_remote",
            label = "合并（本地优先）",
            desc = "合并双方歌单，冲突时优先保留电视本地",
        ),
        OVERWRITE_LOCAL_REMOTE(
            wireValue = "overwrite_local_remote",
            label = "用本地覆盖云端",
            desc = "将电视当前歌单推送覆盖到服务器（慎用）",
        ),
    }

    data class State(
        val serverUrl: String = "",
        val authCode: String = "",
        val serverName: String = "",
        val syncMode: SyncMode = SyncMode.MERGE_REMOTE_LOCAL,
        val connected: Boolean = false,
        val syncing: Boolean = false,
        val lastSyncAt: Long = 0L,
        val statusMessage: String = "",
        val error: String? = null,
    )

    private val prefs = context.getSharedPreferences("lx_sync_client", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(restoreState())
    val state: StateFlow<State> = _state.asStateFlow()

    private val wsClient: OkHttpClient by lazy {
        httpClient.newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(25, TimeUnit.SECONDS)
            .build()
    }

    private var cachedServerId: String = prefs.getString("server_id", "").orEmpty()
    private var cachedClientId: String = prefs.getString("client_id", "").orEmpty()
    private var cachedClientKey: String = prefs.getString("client_key", "").orEmpty()

    private fun restoreState(): State {
        val url = prefs.getString("server_url", "").orEmpty()
        val code = prefs.getString("auth_code", "").orEmpty()
        val name = prefs.getString("server_name", "").orEmpty()
        val modeName = prefs.getString("sync_mode", SyncMode.MERGE_REMOTE_LOCAL.name)
        val mode = SyncMode.entries.firstOrNull { it.name == modeName } ?: SyncMode.MERGE_REMOTE_LOCAL
        val lastSync = prefs.getLong("last_sync_at", 0L)
        val lastMsg = prefs.getString("last_msg", "").orEmpty()
        val hasCred = !prefs.getString("client_id", null).isNullOrBlank() &&
            !prefs.getString("client_key", null).isNullOrBlank()
        return State(
            serverUrl = url,
            authCode = code,
            serverName = name,
            syncMode = mode,
            connected = url.isNotBlank() && hasCred,
            lastSyncAt = lastSync,
            statusMessage = lastMsg,
        )
    }

    fun disconnect() {
        cachedServerId = ""
        cachedClientId = ""
        cachedClientKey = ""
        prefs.edit().clear().apply()
        _state.value = State()
    }

    suspend fun connectAndSync(
        rawServerUrl: String,
        rawAuthCode: String,
        syncMode: SyncMode = _state.value.syncMode,
    ): Result<String> = withContext(Dispatchers.IO) {
        val baseUrl = LxSyncProtocol.normalizeServerUrl(rawServerUrl)
        val authCode = rawAuthCode.trim()
        if (baseUrl.isBlank()) {
            val msg = "请输入有效的服务器地址（例如 http://192.168.1.10:9527）"
            _state.value = _state.value.copy(syncing = false, error = msg)
            return@withContext Result.failure(IllegalArgumentException(msg))
        }

        _state.value = _state.value.copy(
            serverUrl = baseUrl,
            authCode = authCode,
            syncMode = syncMode,
            syncing = true,
            error = null,
            statusMessage = "正在连接同步服务器…",
        )

        runCatching {
            // 1. /hello
            val helloBody = httpGetText("$baseUrl/hello")
            if (helloBody.trim() != LxSyncProtocol.HELLO_MSG) {
                throw IllegalStateException("目标地址不是有效的 LX Sync Server（/hello 校验失败）")
            }

            // 2. /id
            val idBody = httpGetText("$baseUrl/id").trim()
            if (!idBody.startsWith(LxSyncProtocol.ID_PREFIX)) {
                throw IllegalStateException("无法获取服务器 ID")
            }
            val serverId = idBody.removePrefix(LxSyncProtocol.ID_PREFIX)
            if (serverId.isBlank()) throw IllegalStateException("服务器 ID 为空")

            // 3. /ah (cached key auth first if same serverId, else password auth)
            _state.value = _state.value.copy(statusMessage = "正在验证同步密钥…")
            val deviceName = "Walkman-TV (${Build.MODEL ?: "AndroidTV"})"
            var serverName = _state.value.serverName
            val needCodeAuth = serverId != cachedServerId || cachedClientId.isBlank() || cachedClientKey.isBlank()
            var authedByKey = false
            if (!needCodeAuth) {
                authedByKey = runCatching {
                    verifyByKey(baseUrl, cachedClientId, cachedClientKey, deviceName)
                }.getOrDefault(false)
            }
            if (!authedByKey) {
                if (authCode.isBlank()) {
                    throw IllegalStateException("首次连接或密钥已失效，请输入同步密码")
                }
                val info = authByCode(baseUrl, authCode, deviceName)
                cachedServerId = serverId
                cachedClientId = info.clientId
                cachedClientKey = info.key
                serverName = info.serverName
            }

            persistCredentials(baseUrl, authCode, serverId, cachedClientId, cachedClientKey, serverName, syncMode)

            // 4. WebSocket sync session
            _state.value = _state.value.copy(
                serverName = serverName,
                connected = true,
                statusMessage = "连接成功，正在同步歌单…",
            )
            val summary = runWsSync(baseUrl, cachedClientId, cachedClientKey, syncMode)
            val now = System.currentTimeMillis()
            prefs.edit()
                .putLong("last_sync_at", now)
                .putString("last_msg", summary)
                .apply()
            _state.value = _state.value.copy(
                connected = true,
                syncing = false,
                lastSyncAt = now,
                statusMessage = summary,
                error = null,
            )
            summary
        }.onFailure { err ->
            Log.e(TAG, "LX sync failed: ${err.message}", err)
            _state.value = _state.value.copy(
                syncing = false,
                error = err.message ?: "同步失败",
                statusMessage = "",
            )
        }
    }

    private fun httpGetText(url: String, headers: Map<String, String> = emptyMap()): String {
        val req = Request.Builder()
            .url(url)
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()
        httpClient.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (resp.code == 403 || body == LxSyncProtocol.MSG_BLOCKED_IP) {
                throw IllegalStateException("IP 已被服务器临时封禁，请稍后再试")
            }
            if (resp.code == 401 || body == LxSyncProtocol.MSG_AUTH_FAILED) {
                throw IllegalStateException("认证失败，请检查同步密码是否正确")
            }
            if (!resp.isSuccessful) {
                throw IllegalStateException("服务器返回 HTTP ${resp.code}")
            }
            return body
        }
    }

    private fun verifyByKey(baseUrl: String, clientId: String, keyB64: String, deviceName: String): Boolean {
        val keyBytes = keyB64.decodeBase64()?.toByteArray() ?: return false
        val m = LxSyncProtocol.aes128EcbEncryptB64(LxSyncProtocol.AUTH_MSG + deviceName, keyBytes)
        val body = httpGetText("$baseUrl/ah", mapOf("i" to clientId, "m" to m))
        val plain = LxSyncProtocol.aes128EcbDecrypt(body.trim(), keyBytes)
        return plain == LxSyncProtocol.HELLO_MSG
    }

    private data class AuthResult(val clientId: String, val key: String, val serverName: String)

    private fun authByCode(baseUrl: String, authCode: String, deviceName: String): AuthResult {
        val aesKeyBytes = LxSyncProtocol.deriveCodeAesKey(authCode)
        val kp: KeyPair = LxSyncProtocol.generateRsa2048KeyPair()
        val pubB64 = kp.public.encoded.toByteString().base64()
        val payload = "${LxSyncProtocol.AUTH_MSG}\n$pubB64\n$deviceName\nlx_music_mobile"
        val m = LxSyncProtocol.aes128EcbEncryptB64(payload, aesKeyBytes)
        val cipherResp = httpGetText("$baseUrl/ah", mapOf("m" to m)).trim()
        val plain = runCatching { LxSyncProtocol.rsaOaepDecrypt(cipherResp, kp.private) }
            .getOrElse { throw IllegalStateException("密钥解密失败，请检查同步密码") }
        val obj = LxSyncProtocol.json.parseToJsonElement(plain).jsonObject
        val clientId = obj["clientId"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val key = obj["key"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val serverName = obj["serverName"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (clientId.isBlank() || key.isBlank()) {
            throw IllegalStateException("服务器返回的认证信息不完整")
        }
        return AuthResult(clientId, key, serverName)
    }

    private suspend fun runWsSync(
        baseUrl: String,
        clientId: String,
        keyB64: String,
        syncMode: SyncMode,
    ): String {
        val keyBytes = keyB64.decodeBase64()?.toByteArray()
            ?: throw IllegalStateException("本地同步密钥无效")
        val token = LxSyncProtocol.aes128EcbEncryptB64(LxSyncProtocol.MSG_CONNECT, keyBytes)
        val wsUrl = "${LxSyncProtocol.toWsUrl(baseUrl)}/socket?i=${urlEncode(clientId)}&t=${urlEncode(token)}"

        val done = CompletableDeferred<String>()
        var syncedListsCount = 0
        var syncedTracksCount = 0
        var didReceiveRemoteData = false

        val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text == "ping") return
                scope.launch {
                    runCatching {
                        val decoded = LxSyncProtocol.decodeWsMessage(text)
                        val msg = LxSyncProtocol.json.parseToJsonElement(decoded).jsonObject
                        val eventName = msg["name"]?.jsonPrimitive?.contentOrNull ?: return@launch
                        val path = msg["path"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: return@launch
                        val args = msg["data"]?.jsonArray ?: JsonArray(emptyList())
                        val method = path.lastOrNull() ?: return@launch

                        val replyData: JsonElement = when (method) {
                            "getEnabledFeatures" -> {
                                val supported = args.getOrNull(1)?.jsonObject
                                val listVer = supported?.get("list")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 1
                                buildJsonObject {
                                    if (listVer == 1) {
                                        put("list", buildJsonObject { put("skipSnapshot", JsonPrimitive(false)) })
                                    }
                                }
                            }
                            "list_sync_get_md5" -> {
                                val localJson = LxSyncProtocol.buildLocalListDataJson(
                                    love = libraryStore.love.value.tracks,
                                    userLists = libraryStore.userLists.value,
                                )
                                JsonPrimitive(LxSyncProtocol.md5Hex(localJson))
                            }
                            "list_sync_get_sync_mode" -> {
                                JsonPrimitive(syncMode.wireValue)
                            }
                            "list_sync_get_list_data" -> {
                                LxSyncProtocol.buildLocalListDataElement(
                                    love = libraryStore.love.value.tracks,
                                    userLists = libraryStore.userLists.value,
                                )
                            }
                            "list_sync_set_list_data" -> {
                                val dataObj = args.firstOrNull()?.jsonObject
                                if (dataObj != null) {
                                    val parsed = LxSyncProtocol.parseRemoteListData(dataObj)
                                    val allUserLists = buildList {
                                        if (parsed.defaultList.isNotEmpty()) {
                                            add(
                                                Playlist(
                                                    id = "lx_default_list",
                                                    name = "LX 试听列表",
                                                    tracks = parsed.defaultList,
                                                ),
                                            )
                                        }
                                        addAll(parsed.userLists)
                                    }
                                    val replaceAll = syncMode == SyncMode.OVERWRITE_REMOTE_LOCAL
                                    libraryStore.applyLxSync(
                                        loveTracks = parsed.loveList,
                                        syncedUserLists = allUserLists,
                                        replaceAll = replaceAll,
                                    )
                                    didReceiveRemoteData = true
                                    syncedListsCount = allUserLists.size + if (parsed.loveList.isNotEmpty()) 1 else 0
                                    syncedTracksCount = parsed.loveList.size + allUserLists.sumOf { it.tracks.size }
                                }
                                JsonNull
                            }
                            "list_sync_finished" -> JsonNull
                            "finished" -> {
                                val summary = if (didReceiveRemoteData) {
                                    "同步完成：共 $syncedListsCount 个歌单 / $syncedTracksCount 首歌曲"
                                } else {
                                    "已是最新，无需更新"
                                }
                                sendRpcReply(webSocket, eventName, JsonNull, null)
                                webSocket.close(1000, "done")
                                done.complete(summary)
                                return@launch
                            }
                            else -> JsonNull
                        }
                        sendRpcReply(webSocket, eventName, replyData, null)
                    }.onFailure { err ->
                        Log.e(TAG, "WS RPC error: ${err.message}", err)
                        if (!done.isCompleted) done.completeExceptionally(err)
                        webSocket.close(4100, "error")
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!done.isCompleted) {
                    val code = response?.code
                    val msg = if (code == 401) "WebSocket 鉴权失败，请重新输入同步密码"
                    else t.message ?: "WebSocket 连接失败"
                    done.completeExceptionally(IllegalStateException(msg))
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!done.isCompleted) {
                    done.completeExceptionally(IllegalStateException("同步连接已中断 (code=$code)"))
                }
            }
        }

        val ws = wsClient.newWebSocket(Request.Builder().url(wsUrl).build(), listener)
        return try {
            withTimeout(45_000L) { done.await() }
        } catch (e: Throwable) {
            ws.cancel()
            throw e
        }
    }

    private fun sendRpcReply(ws: WebSocket, eventName: String, data: JsonElement, error: String?) {
        val reply = buildJsonObject {
            put("name", JsonPrimitive(eventName))
            if (error != null) {
                put("error", JsonPrimitive(error))
            } else {
                put("error", JsonNull)
                put("data", data)
            }
        }
        val encoded = LxSyncProtocol.encodeWsMessage(reply.toString())
        ws.send(encoded)
    }

    private fun persistCredentials(
        url: String,
        authCode: String,
        serverId: String,
        clientId: String,
        clientKey: String,
        serverName: String,
        syncMode: SyncMode,
    ) {
        prefs.edit()
            .putString("server_url", url)
            .putString("auth_code", authCode)
            .putString("server_id", serverId)
            .putString("client_id", clientId)
            .putString("client_key", clientKey)
            .putString("server_name", serverName)
            .putString("sync_mode", syncMode.name)
            .apply()
    }

    companion object {
        private const val TAG = "LxSyncClient"
    }
}

/**
 * Pure-JVM helpers for `lx-music-sync-server` protocol v4 (crypto, gzip framing, LX list serialization).
 */
object LxSyncProtocol {
    const val HELLO_MSG = "Hello~::^-^::~v4~"
    const val ID_PREFIX = "OjppZDo6"
    const val AUTH_MSG = "lx-music auth::"
    const val MSG_CONNECT = "lx-music connect"
    const val MSG_AUTH_FAILED = "Auth failed"
    const val MSG_BLOCKED_IP = "Blocked IP"

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    data class SyncedListData(
        val defaultList: List<Track>,
        val loveList: List<Track>,
        val userLists: List<Playlist>,
    )

    fun normalizeServerUrl(raw: String): String {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isEmpty()) return ""
        return if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            trimmed
        } else {
            "http://$trimmed"
        }
    }

    fun toWsUrl(httpBase: String): String = when {
        httpBase.startsWith("https://", ignoreCase = true) -> "wss://" + httpBase.substring(8)
        httpBase.startsWith("http://", ignoreCase = true) -> "ws://" + httpBase.substring(7)
        else -> "ws://$httpBase"
    }

    fun md5Hex(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun deriveCodeAesKey(authCode: String): ByteArray =
        md5Hex(authCode).substring(0, 16).toByteArray(Charsets.UTF_8)

    fun aes128EcbEncryptB64(plaintext: String, keyBytes: ByteArray): String {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"))
        return cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)).toByteString().base64()
    }

    fun aes128EcbDecrypt(cipherB64: String, keyBytes: ByteArray): String {
        val raw = cipherB64.decodeBase64()?.toByteArray()
            ?: throw IllegalArgumentException("Invalid base64 ciphertext")
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"))
        return String(cipher.doFinal(raw), Charsets.UTF_8)
    }

    fun generateRsa2048KeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        return kpg.generateKeyPair()
    }

    fun rsaOaepDecrypt(cipherB64: String, privateKey: PrivateKey): String {
        val raw = cipherB64.decodeBase64()?.toByteArray()
            ?: throw IllegalArgumentException("Invalid base64 RSA ciphertext")
        val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding")
        cipher.init(Cipher.DECRYPT_MODE, privateKey)
        return String(cipher.doFinal(raw), Charsets.UTF_8)
    }

    fun encodeWsMessage(msg: String): String {
        if (msg.length <= 1024) return msg
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(msg.toByteArray(Charsets.UTF_8)) }
        return "cg_" + bos.toByteArray().toByteString().base64()
    }

    fun decodeWsMessage(enMsg: String): String {
        if (!enMsg.startsWith("cg_")) return enMsg
        val gzBytes = enMsg.removePrefix("cg_").decodeBase64()?.toByteArray()
            ?: throw IllegalArgumentException("Invalid gzip base64 message")
        return GZIPInputStream(ByteArrayInputStream(gzBytes)).use {
            String(it.readBytes(), Charsets.UTF_8)
        }
    }

    fun buildLocalListDataElement(
        love: List<Track>,
        userLists: List<Playlist>,
    ): JsonObject {
        val defaultTracks = userLists.firstOrNull { it.id == "lx_default_list" }?.tracks.orEmpty()
        val normalLists = userLists.filter { it.id != "lx_default_list" }
        return buildJsonObject {
            put("defaultList", buildJsonArray { defaultTracks.forEach { trackToLxJson(it)?.let(::add) } })
            put("loveList", buildJsonArray { love.forEach { trackToLxJson(it)?.let(::add) } })
            put("userList", buildJsonArray {
                normalLists.forEach { pl ->
                    add(
                        buildJsonObject {
                            put("id", JsonPrimitive(pl.id))
                            put("name", JsonPrimitive(pl.name))
                            put("source", pl.origin?.source?.key?.let(::JsonPrimitive) ?: JsonNull)
                            put("sourceListId", pl.origin?.id?.let(::JsonPrimitive) ?: JsonNull)
                            put("locationUpdateTime", JsonPrimitive(pl.updatedAt))
                            put("list", buildJsonArray { pl.tracks.forEach { trackToLxJson(it)?.let(::add) } })
                        },
                    )
                }
            })
        }
    }

    fun buildLocalListDataJson(love: List<Track>, userLists: List<Playlist>): String =
        buildLocalListDataElement(love, userLists).toString()

    fun trackToLxJson(track: Track): JsonObject? {
        if (track.source == SourceID.LOCAL) return null
        val qs = track.qualities.ifEmpty { listOf(Quality.K128, Quality.K320) }
        return buildJsonObject {
            put("id", JsonPrimitive(track.id))
            put("name", JsonPrimitive(track.name))
            put("singer", JsonPrimitive(track.singer))
            put("source", JsonPrimitive(track.source.key))
            put("interval", JsonPrimitive(track.intervalText))
            put("meta", buildJsonObject {
                put("songId", JsonPrimitive(track.songmid))
                put("albumName", JsonPrimitive(track.albumName.orEmpty()))
                put("picUrl", JsonPrimitive(track.picURL.orEmpty()))
                put("qualitys", buildJsonArray {
                    qs.forEach { q ->
                        add(buildJsonObject { put("type", JsonPrimitive(q.key)); put("size", JsonPrimitive("")) })
                    }
                })
                put("_qualitys", buildJsonObject {
                    qs.forEach { q ->
                        put(q.key, buildJsonObject { put("size", JsonPrimitive("")) })
                    }
                })
                track.extras["hash"]?.let { put("hash", JsonPrimitive(it)) }
                track.extras["strMediaMid"]?.let { put("strMediaMid", JsonPrimitive(it)) }
                track.extras["albumMid"]?.let { put("albumMid", JsonPrimitive(it)) }
                track.extras["copyrightId"]?.let { put("copyrightId", JsonPrimitive(it)) }
            })
        }
    }

    fun parseRemoteListData(root: JsonObject): SyncedListData {
        val defaultList = parseTrackArray(root["defaultList"] as? JsonArray)
        val loveList = parseTrackArray(root["loveList"] as? JsonArray)
        val userLists = mutableListOf<Playlist>()
        (root["userList"] as? JsonArray)?.forEach { elem ->
            val obj = elem as? JsonObject ?: return@forEach
            val id = obj["id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val name = obj["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (id.isEmpty() || name.isEmpty()) return@forEach
            val tracks = parseTrackArray(obj["list"] as? JsonArray)
            userLists += Playlist(id = id, name = name, tracks = tracks)
        }
        return SyncedListData(defaultList = defaultList, loveList = loveList, userLists = userLists)
    }

    private fun parseTrackArray(arr: JsonArray?): List<Track> {
        if (arr == null) return emptyList()
        val out = mutableListOf<Track>()
        for (elem in arr) {
            val obj = elem as? JsonObject ?: continue
            parseLxTrack(obj)?.let(out::add)
        }
        return out.distinctBy { it.id }
    }

    private fun parseLxTrack(obj: JsonObject): Track? {
        val sourceKey = obj["source"]?.jsonPrimitive?.contentOrNull?.trim()
        val source = SourceID.fromKey(sourceKey) ?: return null
        if (source == SourceID.LOCAL) return null
        val name = obj["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (name.isEmpty()) return null
        val singer = obj["singer"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().ifBlank { "未知歌手" }
        val meta = obj["meta"] as? JsonObject
        val songmid = meta?.get("songId")?.jsonPrimitive?.contentOrNull?.trim()?.ifEmpty { null }
            ?: obj["songmid"]?.jsonPrimitive?.contentOrNull?.trim()?.ifEmpty { null }
            ?: obj["id"]?.jsonPrimitive?.contentOrNull?.trim()?.substringAfter('_')?.ifEmpty { null }
            ?: return null
        val albumName = meta?.get("albumName")?.jsonPrimitive?.contentOrNull?.trim()?.ifEmpty { null }
            ?: obj["albumName"]?.jsonPrimitive?.contentOrNull?.trim()?.ifEmpty { null }
        val picUrl = meta?.get("picUrl")?.jsonPrimitive?.contentOrNull?.trim()?.ifEmpty { null }
            ?: obj["img"]?.jsonPrimitive?.contentOrNull?.trim()?.ifEmpty { null }
        val intervalStr = obj["interval"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val durationSec = parseIntervalSeconds(intervalStr)
        val extras = mutableMapOf<String, String>()
        listOf("hash", "strMediaMid", "albumMid", "copyrightId", "lrcUrl").forEach { k ->
            val v = meta?.get(k)?.jsonPrimitive?.contentOrNull?.trim()
                ?: obj[k]?.jsonPrimitive?.contentOrNull?.trim()
            if (!v.isNullOrEmpty()) extras[k] = v
        }
        return Track(
            id = Track.makeID(source, songmid),
            name = name,
            singer = singer,
            albumName = albumName,
            source = source,
            songmid = songmid,
            duration = durationSec,
            picURL = picUrl,
            qualities = emptyList(),
            extras = extras,
        )
    }

    private fun parseIntervalSeconds(interval: String): Int? {
        if (interval.isBlank()) return null
        val parts = interval.split(":")
        if (parts.size == 2) {
            val m = parts[0].toIntOrNull() ?: return null
            val s = parts[1].toIntOrNull() ?: return null
            return (m * 60 + s).takeIf { it > 0 }
        }
        return interval.toIntOrNull()?.takeIf { it > 0 }
    }
}
