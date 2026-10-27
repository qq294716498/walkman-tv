package com.walkman.tv.data.model

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * A list of tracks. Built-in lists use fixed ids ([LOVE], [HISTORY]); user lists get a UUID.
 * The former RN "试听列表" default list is replaced by the auto-recorded [HISTORY] list.
 */
@Serializable
data class Playlist(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val tracks: List<Track> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** Set for lists imported from a platform songlist, so 更新歌单 can re-fetch it. null for
     *  hand-made lists and for lists imported before this field existed. */
    val origin: PlaylistOrigin? = null,
) {
    companion object {
        const val LOVE = "love"
        const val HISTORY = "history"
        const val LOVE_NAME = "我喜欢的"
        const val HISTORY_NAME = "播放历史"
    }
}

/** Where an imported playlist came from — same shape as SonglistImporter's SonglistRef.
 *  [platformTrackIds] = the songs the platform list had at the last import / 更新歌单, so a refresh
 *  can tell them apart from songs the user added by hand (kept). Null for lists imported by 1.4.0. */
@Serializable
data class PlaylistOrigin(
    val source: SourceID,
    val id: String,
    val platformTrackIds: List<String>? = null,
)
