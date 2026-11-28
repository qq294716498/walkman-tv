package com.walkman.tv.playback.imports

import com.walkman.tv.data.model.Quality
import com.walkman.tv.data.model.SourceID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class LxPlaylistImporterTest {

    @Test
    fun parsesGzipLxmcAllDataV2() {
        val json = """
            {
              "type": "allData",
              "data": {
                "playList": {
                  "loveList": [
                    {
                      "id": "tx_001abc",
                      "name": "晴天",
                      "singer": "周杰伦",
                      "source": "tx",
                      "interval": "04:29",
                      "meta": {
                        "songId": "001abc",
                        "strMediaMid": "001media",
                        "albumName": "叶惠美",
                        "picUrl": "https://example.com/a.jpg",
                        "qualitys": [{"type":"128k"},{"type":"flac"},{"type":"flac24bit"}]
                      }
                    },
                    {
                      "id": "local_1",
                      "name": "本地歌曲",
                      "singer": "本地",
                      "source": "local"
                    }
                  ],
                  "userList": [
                    {
                      "id": "u1",
                      "name": "开车必听",
                      "list": [
                        {
                          "id": "wy_123456",
                          "name": "夜曲",
                          "singer": "周杰伦",
                          "source": "wy",
                          "interval": "03:46",
                          "meta": { "songId": "123456", "albumName": "十一月的萧邦" }
                        }
                      ]
                    }
                  ]
                }
              }
            }
        """.trimIndent()

        val gzBytes = ByteArrayOutputStream().use { bos ->
            GZIPOutputStream(bos).use { it.write(json.toByteArray(Charsets.UTF_8)) }
            bos.toByteArray()
        }

        val (lists, skipped) = LxPlaylistImporter.parseBytes(gzBytes).getOrThrow()
        assertEquals(2, lists.size)
        assertEquals(1, skipped) // local track skipped

        val love = lists[0]
        assertTrue(love.isLoveList)
        assertEquals(1, love.tracks.size)
        assertEquals(SourceID.TX, love.tracks[0].source)
        assertEquals("001abc", love.tracks[0].songmid)
        assertEquals("001media", love.tracks[0].extras["strMediaMid"])
        assertEquals(269, love.tracks[0].duration)
        assertTrue(Quality.HIRES in love.tracks[0].qualities)

        val user = lists[1]
        assertEquals("开车必听", user.name)
        assertEquals(1, user.tracks.size)
        assertEquals(SourceID.WY, user.tracks[0].source)
        assertEquals("123456", user.tracks[0].songmid)
    }

    @Test
    fun parsesSinglePlaylistPartV2() {
        val json = """
            {
              "type": "playListPart_v2",
              "data": {
                "id": "userlist_1",
                "name": "深夜民谣",
                "list": [
                  {
                    "id": "kw_998877",
                    "name": "安和桥",
                    "singer": "宋冬野",
                    "source": "kw",
                    "interval": "04:10",
                    "meta": { "songId": "998877", "albumName": "安和桥北" }
                  }
                ]
              }
            }
        """.trimIndent()

        val (lists, skipped) = LxPlaylistImporter.parseBytes(json.toByteArray()).getOrThrow()
        assertEquals(0, skipped)
        assertEquals(1, lists.size)
        assertEquals("深夜民谣", lists[0].name)
        assertEquals("kw_998877", lists[0].tracks[0].id)
    }
}
