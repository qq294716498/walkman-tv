package com.walkman.tv.playback.imports

import com.walkman.tv.data.model.Playlist
import com.walkman.tv.data.model.PlaylistOrigin
import com.walkman.tv.data.model.SonglistDetail
import com.walkman.tv.data.model.SonglistInfo
import com.walkman.tv.data.model.SourceID
import com.walkman.tv.data.store.LibraryStore
import com.walkman.tv.source.catalog.Songlists
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** URL/ID parser result. Source + id uniquely identifies a platform playlist. */
data class SonglistRef(val source: SourceID, val id: String)

sealed class ImportError(message: String) : Throwable(message) {
    object UnrecognizedURL : ImportError("无法识别歌单链接，请粘贴酷我 / 酷狗 / QQ / 网易云的歌单分享链接")
    class FetchFailed(detail: String) : ImportError("获取歌单失败：$detail")
    object EmptyPlaylist : ImportError("歌单为空、私密或暂时无法读取（私密歌单需创建者设为公开后才能导入）")
}

data class ImportResult(val playlistId: String, val count: Int)

data class RefreshResult(val total: Int, val added: Int, val removed: Int, val kept: Int)

/**
 * URL → SonglistRef parser + import orchestrator. Spec docs/songlist-import-spec-android-tv.md.
 *
 * Parse rules are domain-anchored so there's no cross-platform mis-match risk; order is
 * irrelevant. UI layer calls [parse] reactively on every keystroke and [pureIdOrNull] as a
 * fallback when [parse] fails (lets the user pair a bare ID with a manual source picker).
 */
object SonglistImporter {

    private val rules: List<Pair<SourceID, List<Regex>>> = listOf(
        SourceID.WY to listOf(
            Regex("music\\.163\\.com[^\\s]*?[?#&]id=(\\d+)", RegexOption.IGNORE_CASE),
        ),
        SourceID.TX to listOf(
            Regex("y\\.qq\\.com/n/ryqq/playlist/(\\d+)", RegexOption.IGNORE_CASE),
            Regex("qq\\.com[^\\s]*?taoge\\.html[^\\s]*?[?&]id=(\\d+)", RegexOption.IGNORE_CASE),
            Regex("y\\.qq\\.com[^\\s]*?/playsquare/(\\d+)", RegexOption.IGNORE_CASE),
            // iPhone 微信分享的新格式：i2.y.qq.com/n3/other/pages/details/playlist.html?...&id=123
            Regex("qq\\.com[^\\s]*?playlist\\.html[^\\s]*?[?#&]id=(\\d+)", RegexOption.IGNORE_CASE),
        ),
        SourceID.KG to listOf(
            Regex("kugou\\.com/songlist/(\\d+)", RegexOption.IGNORE_CASE),
            Regex("kugou\\.com[^\\s]*?[?&]listid=(\\d+)", RegexOption.IGNORE_CASE),
            // 概念版：完整链接里带 global_specialid / global_collection_id=collection_..._2_0
            Regex("[?&]global_(?:specialid|collection_id)=(collection_[0-9_]+)", RegexOption.IGNORE_CASE),
            Regex("kugou\\.com[^\\s]*?[?&]global_collection_id=([0-9A-Fa-f]+)", RegexOption.IGNORE_CASE),
            // 概念版短链 t1.kugou.com/xxxx —— id 存整条短链，fetchDetail 联网解析成 collection_ id
            Regex("(https?://t1\\.kugou\\.com/[0-9A-Za-z]+)", RegexOption.IGNORE_CASE),
        ),
        SourceID.KW to listOf(
            Regex("kuwo\\.cn[^\\s]*?/playlist_detail/(\\d+)", RegexOption.IGNORE_CASE),
            Regex("kuwo\\.cn[^\\s]*?[?&]pid=(\\d+)", RegexOption.IGNORE_CASE),
        ),
    )

    fun parse(raw: String): SonglistRef? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        for ((source, patterns) in rules) {
            for (pat in patterns) {
                val m = pat.find(s) ?: continue
                val id = m.groupValues.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: continue
                return SonglistRef(source, id)
            }
        }
        return null
    }

    fun pureIdOrNull(raw: String): String? {
        val s = raw.trim()
        return if (s.isNotEmpty() && s.all { it.isDigit() }) s else null
    }

    /** Orchestrator: fetch detail → create user playlist → dump tracks via LibraryStore. */
    suspend fun importPlaylist(
        ref: SonglistRef,
        customName: String?,
        songlists: Songlists,
        library: LibraryStore,
    ): ImportResult {
        val detail = fetch(ref, songlists)

        val trimmed = customName?.trim().orEmpty()
        val name = when {
            trimmed.isNotEmpty() -> trimmed
            detail.info.name.isNotEmpty() -> detail.info.name
            else -> "导入的歌单"
        }
        val playlist = library.createList(name, PlaylistOrigin(ref.source, ref.id, detail.tracks.map { it.id }))
        // Batch write: one StateFlow update + one library.json save for the whole songlist.
        library.addAllToList(playlist.id, detail.tracks)
        return ImportResult(playlistId = playlist.id, count = detail.tracks.size)
    }

    private val _refreshing = MutableStateFlow<Set<String>>(emptySet())
    /** Ids of playlists with a 更新歌单 in flight — lets the detail page show 更新中… again if the
     *  user leaves and comes back mid-refresh. */
    val refreshing: StateFlow<Set<String>> = _refreshing.asStateFlow()

    /**
     * Fire-and-forget 更新歌单 on an app-lifetime [scope], so leaving the page doesn't cancel it
     * halfway (which could also leave library.json unsaved). [onDone] gets the user-facing
     * result message on the main thread. No-op if this list is already refreshing.
     */
    fun startRefresh(
        playlist: Playlist,
        songlists: Songlists,
        library: LibraryStore,
        scope: CoroutineScope,
        onDone: (String) -> Unit,
    ) {
        if (playlist.id in _refreshing.value) return
        _refreshing.update { it + playlist.id }
        scope.launch {
            val msg = try {
                val r = refreshPlaylist(playlist, songlists, library)
                if (r.added == 0 && r.removed == 0) "「${playlist.name}」已是最新（${r.total} 首）"
                else "「${playlist.name}」已更新：共 ${r.total} 首，新增 ${r.added}，移除 ${r.removed}" +
                    if (r.kept > 0) "（保留手动添加的 ${r.kept} 首）" else ""
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "「${playlist.name}」更新失败：${e.localizedMessage ?: e}"
            } finally {
                _refreshing.update { it - playlist.id }
            }
            onDone(msg)
        }
    }

    /**
     * 更新歌单: re-fetch an imported list's platform songlist and mirror it (order included).
     * Songs the user added by hand (＋歌单 / local import into this list) are kept, appended after
     * the platform songs. "By hand" = not in the platform snapshot from the last import/refresh;
     * lists imported by 1.4.0 have no snapshot, so there it falls back to "from another source"
     * (local songs, other platforms) — same-platform songs added by hand can't be told apart.
     */
    private suspend fun refreshPlaylist(
        playlist: Playlist,
        songlists: Songlists,
        library: LibraryStore,
    ): RefreshResult {
        val origin = playlist.origin ?: throw ImportError.UnrecognizedURL
        val detail = fetch(SonglistRef(origin.source, origin.id), songlists)
        // Re-read after the (slow) fetch so edits made meanwhile aren't lost.
        val live = library.userLists.value.firstOrNull { it.id == playlist.id }
            ?: throw IllegalStateException("歌单已被删除")
        val snapshot = origin.platformTrackIds?.toHashSet()
        val (fromPlatform, byHand) = live.tracks.partition { t ->
            if (snapshot != null) t.id in snapshot else t.source == origin.source
        }
        val newIds = detail.tracks.mapTo(HashSet()) { it.id }
        val kept = byHand.filter { it.id !in newIds }
        val oldIds = fromPlatform.mapTo(HashSet()) { it.id }
        library.replaceTracks(
            playlist.id,
            detail.tracks + kept,
            origin.copy(platformTrackIds = detail.tracks.map { it.id }),
        )
        return RefreshResult(
            total = newIds.size + kept.size,
            added = newIds.count { it !in oldIds },
            removed = oldIds.count { it !in newIds },
            kept = kept.size,
        )
    }

    private suspend fun fetch
(ref: SonglistRef, songlists: Songlists): SonglistDetail {
        val svc = songlists.serviceFor(ref.source) ?: throw ImportError.UnrecognizedURL
        // Stub only needs id + source — fetchDetail looks at those, the rest get overwritten.
        val stub = SonglistInfo(
            id = ref.id, source = ref.source,
            name = "", author = "", picURL = null, trackCount = null, playCount = null,
        )
        val detail = try {
            svc.fetchDetail(stub)
        } catch (e: Exception) {
            throw ImportError.FetchFailed(e.localizedMessage ?: e.toString())
        }
        if (detail.tracks.isEmpty()) throw ImportError.EmptyPlaylist
        return detail
    }
}
