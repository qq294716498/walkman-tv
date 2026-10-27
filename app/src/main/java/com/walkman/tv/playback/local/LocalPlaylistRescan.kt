package com.walkman.tv.playback.local

import com.walkman.tv.data.model.Playlist
import com.walkman.tv.data.store.LibraryStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 重新扫描 for playlists holding local-folder tracks. Works for lists imported before this
 * feature too: every local track's songmid already names its folder (`lf://<folderID>/…`).
 *
 * Per folder referenced by the list: tracks still on disk stay where they are, files gone from
 * disk are dropped, new files are appended at the end. Non-local tracks (songs the user added
 * from search etc.) and tracks of folders we can't resolve are left untouched. Any unreachable
 * folder aborts the whole rescan without writing, so an unplugged USB drive never wipes a list.
 */
object LocalPlaylistRescan {

    /** playlistId → scan progress 0..1 for rescans in flight. */
    private val _progress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val progress: StateFlow<Map<String, Float>> = _progress.asStateFlow()

    /** Folder ids in [playlist] that we can rescan (record still known). Empty ⇒ hide the button.
     *  Built-in lists (我喜欢的 / 播放历史) are excluded — only user lists are rewritten here. */
    fun rescannableFolders(playlist: Playlist, local: LocalMusicStore): Set<String> =
        if (playlist.id == Playlist.LOVE || playlist.id == Playlist.HISTORY) emptySet()
        else playlist.tracks.mapNotNullTo(LinkedHashSet()) { local.folderIdOf(it) }
            .filterTo(LinkedHashSet()) { local.hasFolder(it) }

    /** Fire-and-forget on an app-lifetime [scope]; [onDone] gets the user-facing message. */
    fun start(
        playlist: Playlist,
        local: LocalMusicStore,
        library: LibraryStore,
        scope: CoroutineScope,
        onDone: (String) -> Unit,
    ) {
        if (playlist.id in _progress.value) return
        val folders = rescannableFolders(playlist, local).toList()
        if (folders.isEmpty()) return
        _progress.update { it + (playlist.id to 0f) }
        scope.launch {
            val msg = try {
                val known = playlist.tracks.associateBy { it.id }
                val scanned = folders.mapIndexed { i, folderId ->
                    folderId to local.rescanFolder(folderId, known) { p ->
                        _progress.update { it + (playlist.id to (i + p) / folders.size) }
                    }
                }.toMap()
                val onDisk = scanned.values.flatten().mapTo(HashSet()) { it.id }
                val kept = playlist.tracks.filter { t ->
                    val folder = local.folderIdOf(t)
                    folder == null || folder !in scanned || t.id in onDisk
                }
                val keptIds = kept.mapTo(HashSet()) { it.id }
                val added = scanned.values.flatten().filter { it.id !in keptIds }
                val removed = playlist.tracks.size - kept.size
                // Re-read the live list so songs added/removed by the user during the scan survive.
                val live = library.userLists.value.firstOrNull { it.id == playlist.id }
                    ?: throw IllegalStateException("歌单已被删除")
                val liveKept = live.tracks.filter { t ->
                    val folder = local.folderIdOf(t)
                    folder == null || folder !in scanned || t.id in onDisk
                }
                library.replaceTracks(playlist.id, liveKept + added)
                if (added.isEmpty() && removed == 0) "「${playlist.name}」已是最新"
                else "「${playlist.name}」扫描完成：新增 ${added.size}，移除 $removed"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "「${playlist.name}」扫描失败：${e.message ?: e}"
            } finally {
                _progress.update { it - playlist.id }
            }
            onDone(msg)
        }
    }
}
