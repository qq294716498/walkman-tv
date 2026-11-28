package com.walkman.tv.source

import android.util.Log
import com.walkman.tv.data.model.Quality
import com.walkman.tv.data.model.ScriptCapabilities
import com.walkman.tv.data.model.SourceID
import com.walkman.tv.data.model.Track
import com.walkman.tv.data.model.UserScript
import com.walkman.tv.source.builtin.BuiltInResolver
import com.walkman.tv.source.js.JsScriptRuntime
import com.walkman.tv.source.js.ScriptHttpClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Owns loaded user scripts and resolves play URLs, ported from iOS `SourceManager`.
 * Resolve order: user script (with quality cascade) → other-source search → built-in direct.
 */
class SourceManager(
    private val preload: String,
    private val http: ScriptHttpClient,
    private val otherSourceFinder: OtherSourceFinder,
    private val builtIn: BuiltInResolver,
) {
    data class LoadedScript(
        val script: UserScript,
        val runtime: JsScriptRuntime,
        val capabilities: ScriptCapabilities,
    )

    private val _loaded = MutableStateFlow<List<LoadedScript>>(emptyList())
    val loaded: StateFlow<List<LoadedScript>> = _loaded.asStateFlow()

    @Volatile var fallbackEnabled: Boolean = true

    /** Raised by an installed source script when it announces a newer script. */
    var onUpdateAlert: ((script: UserScript, log: String, updateUrl: String?) -> Unit)? = null

    class SourceException(message: String) : Exception(message)

    suspend fun load(script: UserScript): Result<ScriptCapabilities> {
        val runtime = JsScriptRuntime(script, preload, http) { log, url ->
            onUpdateAlert?.invoke(script, log, url)
        }
        return try {
            val caps = runtime.load()
            val previous = _loaded.value.firstOrNull { it.script.id == script.id }
            val next = _loaded.value.toMutableList()
            val index = next.indexOfFirst { it.script.id == script.id }
            val loaded = LoadedScript(script, runtime, caps)
            if (index >= 0) next[index] = loaded else next.add(loaded)
            _loaded.value = next
            previous?.runtime?.destroy()
            Result.success(caps)
        } catch (e: Exception) {
            runtime.destroy()
            Log.e(TAG, "load failed: ${e.message}")
            Result.failure(e)
        }
    }

    fun unload(scriptId: String) {
        _loaded.value.firstOrNull { it.script.id == scriptId }?.runtime?.destroy()
        _loaded.value = _loaded.value.filter { it.script.id != scriptId }
    }

    private fun scriptsFor(source: SourceID, action: String): List<LoadedScript> =
        _loaded.value.filter { it.capabilities.sources[source]?.actions?.contains(action) == true }

    private fun scriptFor(source: SourceID, action: String): LoadedScript? =
        scriptsFor(source, action).firstOrNull()

    /**
     * Resolve a play URL with **tier-first across platforms**:
     * 1. Try [preferred] quality on the original platform's scripts.
     * 2. If unavailable, search other platforms (same title + same singer via [OtherSourceFinder])
     *    and try the **exact same [tier]** before downgrading quality.
     * 3. Only if no platform can provide [tier], step down one quality tier and repeat.
     * 4. Built-in direct fallback if all scripts fail and [fallbackEnabled] is true.
     */
    suspend fun resolveMusicURL(track: Track, quality: Quality? = null): ResolvedTrack {
        val preferred = quality ?: Quality.MASTER
        val tiers = qualityCascade(preferred, track.qualities.toSet(), emptySet())
        val originalScripts = scriptsFor(track.source, "musicUrl")
        val timedOutScripts = HashSet<String>()

        var alternativesLoaded = false
        var alternatives: List<Track> = emptyList()
        suspend fun loadAlternatives(): List<Track> {
            if (!alternativesLoaded) {
                alternativesLoaded = true
                alternatives = runCatching { otherSourceFinder.findMatches(track) }
                    .getOrDefault(emptyList())
                    .take(5)
            }
            return alternatives
        }

        fun scriptSupportsTier(ls: LoadedScript, source: SourceID, tier: Quality): Boolean {
            val qs = ls.capabilities.sources[source]?.qualities?.toSet() ?: emptySet()
            return tier == Quality.K128 || qs.isEmpty() || qs.contains(tier)
        }

        for (tier in tiers) {
            // 1. Original platform at current tier
            originalScripts.forEachIndexed { scriptIdx, ls ->
                if (ls.script.id in timedOutScripts) return@forEachIndexed
                if (!scriptSupportsTier(ls, track.source, tier)) return@forEachIndexed
                runCatching { resolveViaScript(track, tier, ls) }
                    .onSuccess { url ->
                        val warn = when {
                            scriptIdx > 0 -> "已切换到音源「${ls.script.name}」"
                            tier != preferred -> "${preferred.displayName} 不可用，已自动降档至 ${tier.displayName}"
                            else -> null
                        }
                        return ResolvedTrack(url, ResolveOrigin.Script(ls.script.name), tier, warn)
                    }
                    .onFailure {
                        Log.d(TAG, "script '${ls.script.name}' @${tier.key} failed: ${it.message}")
                        if (it is JsScriptRuntime.ScriptTimeoutException) timedOutScripts.add(ls.script.id)
                    }
            }

            // 2. Other platforms (same title + same singer) at the SAME tier before downgrading!
            val alts = loadAlternatives()
            for (alt in alts) {
                val altScripts = scriptsFor(alt.source, "musicUrl")
                for (altLs in altScripts) {
                    if (altLs.script.id in timedOutScripts) continue
                    if (!scriptSupportsTier(altLs, alt.source, tier)) continue
                    runCatching { resolveViaScript(alt, tier, altLs) }
                        .onSuccess { url ->
                            val warn = buildString {
                                append("原 ${track.source.displayName} 源无 ${tier.displayName}，已换到 ${alt.source.displayName}")
                                if (tier != preferred) append("（降档至 ${tier.displayName}）")
                            }
                            return ResolvedTrack(url, ResolveOrigin.OtherSource(alt.source), tier, warn)
                        }
                        .onFailure {
                            Log.d(TAG, "other-source ${alt.source.key} '${altLs.script.name}' @${tier.key} failed: ${it.message}")
                            if (it is JsScriptRuntime.ScriptTimeoutException) timedOutScripts.add(altLs.script.id)
                        }
                }
            }
        }

        if (fallbackEnabled) {
            runCatching { builtIn.resolve(track, preferred) }.getOrNull()?.let { res ->
                return ResolvedTrack(res.url, ResolveOrigin.DirectFallback, preferred, res.warning)
            }
            for (alt in loadAlternatives()) {
                runCatching { builtIn.resolve(alt, preferred) }.getOrNull()?.let { res ->
                    return ResolvedTrack(
                        res.url,
                        ResolveOrigin.OtherSource(alt.source),
                        preferred,
                        res.warning ?: "已换到 ${alt.source.displayName} 直连播放",
                    )
                }
            }
        }

        if (originalScripts.isEmpty() && _loaded.value.none { it.capabilities.sources.values.any { c -> "musicUrl" in c.actions } }) {
            throw SourceException("没有脚本提供 ${track.source.displayName} 源，请到设置 → 自定义音源 导入脚本")
        }
        throw SourceException("这首 ${track.source.displayName} 歌曲在各平台均无法获取播放地址，换一首或检查自定义音源")
    }

    private suspend fun resolveViaScript(track: Track, quality: Quality, ls: LoadedScript): String {
        val rawKey = ls.runtime.rawSourceKey(track.source)
        val info = JSONObject()
            .put("type", quality.key)
            .put("musicInfo", makeOldMusicInfo(track, rawKey))
        val result = ls.runtime.requestAction(track.source, "musicUrl", info)
        val obj = result as? JSONObject
        val url = obj?.optJSONObject("data")?.optString("url")?.ifEmpty { null }
            ?: obj?.optString("url")?.ifEmpty { null }
        return url ?: throw SourceException("脚本返回了无效结果")
    }

    /** Ask a user script for lyric data — raw `{lyric, tlyric, rlyric, lxlyric}` (iOS `requestLyric`). */
    suspend fun requestLyric(track: Track): JSONObject? {
        val ls = scriptFor(track.source, "lyric") ?: throw SourceException("没有脚本提供 ${track.source.displayName} 歌词")
        val rawKey = ls.runtime.rawSourceKey(track.source)
        val info = JSONObject().put("type", "").put("musicInfo", makeOldMusicInfo(track, rawKey))
        return ls.runtime.requestAction(track.source, "lyric", info) as? JSONObject
    }

    fun availableQualities(source: SourceID): List<Quality> {
        val set = mutableSetOf<Quality>()
        _loaded.value.forEach { it.capabilities.sources[source]?.qualities?.forEach(set::add) }
        return Quality.entries.filter { set.contains(it) }
    }

    fun supportedSources(): List<SourceID> {
        val set = mutableSetOf<SourceID>()
        _loaded.value.forEach { ls ->
            ls.capabilities.sources.forEach { (src, cap) -> if (cap.actions.contains("musicUrl")) set.add(src) }
        }
        return SourceID.entries.filter { set.contains(it) }
    }

    /** Build the v4 "old format" musicInfo a script expects (iOS `makeOldMusicInfo`). */
    fun makeOldMusicInfo(track: Track, sourceKeyOverride: String? = null): JSONObject {
        val types = JSONArray()
        val typesObj = JSONObject()
        Quality.orderedHighToLow.filter { track.qualities.contains(it) }.forEach { q ->
            types.put(JSONObject().put("type", q.key).put("size", ""))
            typesObj.put(q.key, JSONObject().put("size", ""))
        }
        val info = JSONObject()
            .put("name", track.name)
            .put("singer", track.singer)
            .put("source", sourceKeyOverride ?: track.source.key)
            .put("songmid", track.songmid)
            .put("interval", track.intervalText)
            .put("albumName", track.albumName ?: "")
            .put("img", track.picURL ?: "")
            .put("typeUrl", JSONObject())
            .put("albumId", track.albumId ?: "")
            .put("types", types)
            .put("_types", typesObj)
        track.extras.forEach { (k, v) -> info.put(k, v) }
        return info
    }

    companion object {
        private const val TAG = "SourceManager"

        /**
         * Pick the starting playback tier for a track.
         *
         * Always prioritizes [preferred]: walks [Quality.orderedHighToLow] starting at [preferred]
         * and returns the first tier declared by [scriptQs] (or [preferred] itself if [scriptQs] is
         * empty), never prematurely dropping tiers just because catalog/playlist metadata omitted
         * [trackQs]. Missing or failed tiers then step down automatically via [qualityCascade].
         */
        @Suppress("UNUSED_PARAMETER")
        fun pickPlayQuality(preferred: Quality, trackQs: Set<Quality>, scriptQs: Set<Quality>): Quality {
            if (scriptQs.isEmpty()) return preferred
            val full = Quality.orderedHighToLow
            val startIdx = full.indexOf(preferred).coerceAtLeast(0)
            for (i in startIdx until full.size) {
                val q = full[i]
                if (q == Quality.K128 || scriptQs.contains(q)) return q
            }
            return Quality.K128
        }

        /**
         * Build the descending cascade from [first] down to [Quality.K128].
         * Every tier declared by [scriptQs] (or all tiers when [scriptQs] is empty) is tried in
         * descending order; [Quality.K128] is always included as the universal floor.
         */
        @Suppress("UNUSED_PARAMETER")
        fun qualityCascade(first: Quality, trackQs: Set<Quality>, scriptQs: Set<Quality>): List<Quality> {
            val full = Quality.orderedHighToLow
            val startIdx = full.indexOf(first).coerceAtLeast(0)
            val out = mutableListOf<Quality>()
            for (i in startIdx until full.size) {
                val q = full[i]
                val eligible = q == Quality.K128 || scriptQs.isEmpty() || scriptQs.contains(q)
                if (eligible) out.add(q)
            }
            return out.ifEmpty { listOf(first) }
        }
    }
}
