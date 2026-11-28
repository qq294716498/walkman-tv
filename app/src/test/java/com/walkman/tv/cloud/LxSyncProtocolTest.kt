package com.walkman.tv.cloud

import com.walkman.tv.data.model.Playlist
import com.walkman.tv.data.model.Quality
import com.walkman.tv.data.model.SourceID
import com.walkman.tv.data.model.Track
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.Cipher

class LxSyncProtocolTest {

    @Test
    fun urlNormalizationAndWsConversion() {
        assertEquals("http://192.168.1.10:9527", LxSyncProtocol.normalizeServerUrl("192.168.1.10:9527/"))
        assertEquals("https://sync.example.com/lx", LxSyncProtocol.normalizeServerUrl("https://sync.example.com/lx/"))
        assertEquals("ws://192.168.1.10:9527", LxSyncProtocol.toWsUrl("http://192.168.1.10:9527"))
        assertEquals("wss://sync.example.com/lx", LxSyncProtocol.toWsUrl("https://sync.example.com/lx"))
    }

    @Test
    fun aes128EcbRoundTripMatchesServerSpec() {
        val key = LxSyncProtocol.deriveCodeAesKey("my-secret-pass")
        assertEquals(16, key.size)
        val encrypted = LxSyncProtocol.aes128EcbEncryptB64("lx-music connect", key)
        val decrypted = LxSyncProtocol.aes128EcbDecrypt(encrypted, key)
        assertEquals("lx-music connect", decrypted)
    }

    @Test
    fun rsaOaepSha1RoundTripMatchesNodePublicEncrypt() {
        val kp = LxSyncProtocol.generateRsa2048KeyPair()
        val payload = """{"clientId":"c1","key":"MTIzNDU2Nzg5MDEyMzQ1Ng==","serverName":"mangu"}"""
        val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, kp.public)
        val encryptedB64 = cipher.doFinal(payload.toByteArray(Charsets.UTF_8)).toByteString().base64()
        assertEquals(payload, LxSyncProtocol.rsaOaepDecrypt(encryptedB64, kp.private))
    }

    @Test
    fun wsMessageCompressionThresholdAndRoundTrip() {
        val shortMsg = """{"name":"x","error":null,"data":"ok"}"""
        assertEquals(shortMsg, LxSyncProtocol.encodeWsMessage(shortMsg))
        assertEquals(shortMsg, LxSyncProtocol.decodeWsMessage(shortMsg))

        val longMsg = "A".repeat(2500)
        val encoded = LxSyncProtocol.encodeWsMessage(longMsg)
        assertTrue(encoded.startsWith("cg_"))
        assertEquals(longMsg, LxSyncProtocol.decodeWsMessage(encoded))
    }

    @Test
    fun localListDataRoundTripsCleanly() {
        val track = Track(
            id = "wy_12345",
            name = "我不难过",
            singer = "孙燕姿",
            albumName = "未完成",
            source = SourceID.WY,
            songmid = "12345",
            duration = 320,
            picURL = "https://example.com/a.jpg",
            qualities = listOf(Quality.FLAC, Quality.K320),
        )
        val love = listOf(track)
        val userLists = listOf(Playlist(id = "userlist_1", name = "经典华语", tracks = listOf(track)))
        val elem = LxSyncProtocol.buildLocalListDataElement(love, userLists)
        val parsed = LxSyncProtocol.parseRemoteListData(elem)

        assertEquals(1, parsed.loveList.size)
        assertEquals("我不难过", parsed.loveList[0].name)
        assertEquals("孙燕姿", parsed.loveList[0].singer)
        assertEquals(320, parsed.loveList[0].duration)
        assertEquals(1, parsed.userLists.size)
        assertEquals("userlist_1", parsed.userLists[0].id)
        assertEquals("经典华语", parsed.userLists[0].name)
    }
}
