package com.walkman.tv.data.store

import android.content.Context
import com.walkman.tv.data.model.UserScript
import com.walkman.tv.source.SourceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/** Stores imported custom-source scripts and (re)loads enabled ones into [SourceManager]. */
class ScriptStore(private val context: Context, private val sourceManager: SourceManager) {
    private val store = JsonStore(
        File(context.filesDir, "scripts.json"),
        ListSerializer(UserScript.serializer()),
        emptyList(),
    )

    private val _scripts = MutableStateFlow<List<UserScript>>(emptyList())
    val scripts: StateFlow<List<UserScript>> = _scripts.asStateFlow()
    private val mutex = Mutex()
    private val updating = mutableSetOf<String>()
    private val _updateStatus = MutableStateFlow<String?>(null)
    val updateStatus: StateFlow<String?> = _updateStatus.asStateFlow()

    /** Load persisted scripts and start the enabled ones. First-run users start with no
     *  custom source — they configure their own via Settings (URL / paste / file / QR upload). */
    suspend fun loadAll() = mutex.withLock {
        val list = withContext(Dispatchers.IO) { store.load() }
        _scripts.value = list
        list.filter { it.enabled }.forEach { sourceManager.load(it) }
    }

    /** Result of [import]: [replaced] is the older copy of the same script (same name + author)
     *  that this import updated in place, or null when it was added as a new source. */
    data class Imported(val script: UserScript, val replaced: UserScript?) {
        val message: String
            get() = if (replaced == null) "已添加音源：${script.name}"
            else "已更新音源：${script.name}（替换了同名旧版本${replaced.version.takeIf { it.isNotBlank() }?.let { " v$it" } ?: ""}）"
    }

    /**
     * Import a raw script: parse its header, load it, persist. Several sources can coexist; only
     * a script with the same name AND author counts as a new version of an existing one and
     * replaces it (keeping its place in the list). The old runtime is unloaded — previously it
     * stayed loaded in [SourceManager] invisibly — and same-named scripts by different authors
     * are kept side by side instead of silently overwriting each other.
     */
    suspend fun import(raw: String): Result<Imported> = mutex.withLock {
        val meta = parseHeader(raw)
        val script = UserScript(
            name = meta["name"] ?: "未命名脚本",
            description = meta["description"] ?: "",
            version = meta["version"] ?: "",
            author = meta["author"] ?: "",
            homepage = meta["homepage"] ?: "",
            rawScript = raw,
            enabled = true,
        )
        val old = _scripts.value.firstOrNull { it.name == script.name && it.author == script.author }
        val result = sourceManager.load(script)
        result.fold(
            onSuccess = {
                if (old != null) {
                    sourceManager.unload(old.id)
                    _scripts.value = _scripts.value.map { if (it.id == old.id) script else it }
                } else {
                    _scripts.value = _scripts.value + script
                }
                persist()
                Result.success(Imported(script, old))
            },
            onFailure = { Result.failure(it) },
        )
    }

    /** Follow an update notice from the currently installed script, keeping its identity. */
    suspend fun updateFromAlert(
        origin: UserScript,
        log: String,
        updateUrl: String?,
        fetch: suspend (String) -> String,
    ) {
        // AppContainer dispatches these calls on its Main scope. Suppress repeated notices
        // while a download is running (including a notice emitted by the replacement script).
        if (!updating.add(origin.id)) return
        try {
            mutex.withLock {
                val old = _scripts.value.firstOrNull {
                    it.id == origin.id && it.enabled && it.rawScript == origin.rawScript
                } ?: return@withLock
                val links = ScriptUpdateLinks.candidates(updateUrl, log)
                if (links.isEmpty()) {
                    _updateStatus.value = "${old.name}：更新提示中没有 HTTPS 下载地址"
                    return@withLock
                }
                _updateStatus.value = "正在更新音源：${old.name}…"
                var alreadyCurrent = false
                for (link in links) {
                    // A notice may contain a web page and a direct .js link. Try each one;
                    // only a script declaring the same @name is eligible for replacement.
                    val raw = try {
                        fetch(link)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        continue
                    }
                    if (raw == old.rawScript) {
                        alreadyCurrent = true
                        continue
                    }
                    val meta = parseHeader(raw)
                    if (meta["name"] != old.name) continue
                    val replacement = old.copy(
                        description = meta["description"] ?: old.description,
                        version = meta["version"] ?: old.version,
                        author = meta["author"] ?: old.author,
                        homepage = meta["homepage"] ?: old.homepage,
                        rawScript = raw,
                    )
                    if (sourceManager.load(replacement).isFailure) continue
                    _scripts.value = _scripts.value.map { if (it.id == origin.id) replacement else it }
                    persist()
                    _updateStatus.value = "${old.name}：音源已自动更新至 v${replacement.version}"
                    return@withLock
                }
                _updateStatus.value = if (alreadyCurrent) "${old.name}：已是最新音源"
                    else "${old.name}：自动更新失败，已保留原音源"
            }
        } finally {
            updating.remove(origin.id)
        }
    }

    suspend fun setEnabled(id: String, enabled: Boolean) = mutex.withLock {
        val script = _scripts.value.firstOrNull { it.id == id } ?: return@withLock
        _scripts.value = _scripts.value.map { if (it.id == id) it.copy(enabled = enabled) else it }
        if (enabled) sourceManager.load(script.copy(enabled = true)) else sourceManager.unload(id)
        persist()
    }

    suspend fun remove(id: String) = mutex.withLock {
        sourceManager.unload(id)
        _scripts.value = _scripts.value.filter { it.id != id }
        persist()
    }

    private suspend fun persist() = withContext(Dispatchers.IO) { store.save(_scripts.value) }

    companion object {
        private val headerBlock = Regex("""/\*[\s\S]*?\*/""")
        private val tagLine = Regex("""@(\w+)\s+(.+)""")

        /** Parse the leading KDoc-style header of an lx v4 script (@name / @version / etc). */
        fun parseHeader(raw: String): Map<String, String> {
            val block = headerBlock.find(raw)?.value ?: return emptyMap()
            val out = mutableMapOf<String, String>()
            tagLine.findAll(block).forEach { m ->
                out[m.groupValues[1].lowercase()] = m.groupValues[2].trim().removeSuffix("*").trim()
            }
            return out
        }
    }
}
