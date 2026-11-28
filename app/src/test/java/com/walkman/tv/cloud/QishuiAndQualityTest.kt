package com.walkman.tv.cloud

import com.walkman.tv.data.model.Quality
import com.walkman.tv.data.model.SourceID
import com.walkman.tv.source.SourceManager
import okio.ByteString.Companion.decodeBase64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec

class QishuiAndQualityTest {

    @Test
    fun sourceIdFromKeyMapsQishuiAliases() {
        assertEquals(SourceID.QS, SourceID.fromKey("qs"))
        assertEquals(SourceID.QS, SourceID.fromKey("QS"))
        assertEquals(SourceID.QS, SourceID.fromKey("qishui"))
        assertEquals(SourceID.QS, SourceID.fromKey("dy"))
        assertEquals(SourceID.QS, SourceID.fromKey("luna"))
        assertEquals(SourceID.WY, SourceID.fromKey("wy"))
        assertEquals(SourceID.TX, SourceID.fromKey("tx"))
    }

    @Test
    fun qualityCascadeStartsFromPreferredEvenWhenTrackQualitiesEmpty() {
        val fullScript = Quality.orderedHighToLow.toSet()

        // Preferred = MASTER, empty trackQs (e.g. Qishui radio / cloud playlist)
        val firstMaster = SourceManager.pickPlayQuality(Quality.MASTER, emptySet(), fullScript)
        assertEquals(Quality.MASTER, firstMaster)
        assertEquals(
            Quality.orderedHighToLow,
            SourceManager.qualityCascade(firstMaster, emptySet(), fullScript),
        )

        // Preferred = FLAC, empty trackQs — must NOT skip FLAC or K320!
        val firstFlac = SourceManager.pickPlayQuality(Quality.FLAC, emptySet(), fullScript)
        assertEquals(Quality.FLAC, firstFlac)
        assertEquals(
            listOf(Quality.FLAC, Quality.K320, Quality.K128),
            SourceManager.qualityCascade(firstFlac, emptySet(), fullScript),
        )
    }

    @Test
    fun qualityCascadeStepsDownToHighestScriptTierAtOrBelowPreferred() {
        val limitedScript = setOf(Quality.FLAC, Quality.K320, Quality.K128)
        val first = SourceManager.pickPlayQuality(Quality.MASTER, setOf(Quality.K128), limitedScript)
        assertEquals(Quality.FLAC, first)
        assertEquals(
            listOf(Quality.FLAC, Quality.K320, Quality.K128),
            SourceManager.qualityCascade(first, setOf(Quality.K128), limitedScript),
        )
    }

    @Test
    fun qualityCascadeIncludesFloor128kEvenIfScriptOmitsIt() {
        val flacOnly = setOf(Quality.FLAC)
        val first = SourceManager.pickPlayQuality(Quality.FLAC24, emptySet(), flacOnly)
        assertEquals(Quality.FLAC, first)
        assertEquals(
            listOf(Quality.FLAC, Quality.K128),
            SourceManager.qualityCascade(first, emptySet(), flacOnly),
        )
    }

    @Test
    fun qishuiDeviceIdIs19Digits() {
        repeat(10) {
            val id = QishuiProtocol.createDeviceId()
            assertTrue("Expected 19 digits, got $id", id.matches(Regex("^\\d{19}$")))
        }
    }

    @Test
    fun qishuiGuardGeneratesValidEcKeyCsrAndSignedHeaders() {
        val guard = QishuiProtocol.createGuard()
        val keyBytes = guard.privateKeyPkcs8B64.decodeBase64()?.toByteArray()
        assertNotNull(keyBytes)
        val privateKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(keyBytes))
        assertEquals("EC", privateKey.algorithm)

        val csrPem = guard.csrB64.decodeBase64()?.utf8().orEmpty()
        assertTrue(csrPem.startsWith("-----BEGIN CERTIFICATE REQUEST-----\n"))
        assertTrue(csrPem.trimEnd().endsWith("-----END CERTIFICATE REQUEST-----"))

        val headers = QishuiProtocol.buildGuardHeaders(
            ticket = "test-ticket",
            guardKeyB64 = guard.privateKeyPkcs8B64,
            guardCert = "test-cert",
            guardTsSign = "test-ts-sign",
            path = "/me/info/",
            timestampSec = 1_700_000_000L,
        )
        assertEquals("test-ticket", headers["access-token"])
        assertEquals("1", headers["bd-ticket-guard-version"])
        assertEquals("test-cert", headers["bd-ticket-guard-client-cert"])
        val clientData = headers["bd-ticket-guard-client-data"]?.decodeBase64()?.utf8().orEmpty()
        assertTrue(clientData.contains("\"ts_sign\":\"test-ts-sign\""))
        assertTrue(clientData.contains("\"req_content\":\"ticket,path,timestamp\""))
        assertTrue(clientData.contains("\"timestamp\":1700000000"))
        assertTrue(clientData.contains("\"req_sign\":\""))
    }

    @Test
    fun qishuiKrcToLrcStripsWordTimingsAndFormatsTimestamps() {
        val krc = """
            [12500,3200]<0,400,0>你<400,500,0>好<900,800,0>世界
            [65040,2100]<0,500,0>汽水<500,600,0>音乐
        """.trimIndent()
        val lrc = QishuiProtocol.krcToLrc(krc)
        assertEquals(
            "[00:12.50]你好世界\n[01:05.04]汽水音乐\n",
            lrc,
        )
    }

    @Test
    fun qishuiParseExpiryMsHandlesAllFormats() {
        val now = 1_700_000_000_000L
        assertEquals(1_700_050_000_000L, QishuiProtocol.parseExpiryMs(1_700_050_000_000L, now))
        assertEquals(1_700_050_000_000L, QishuiProtocol.parseExpiryMs(1_700_050_000L, now))
        assertEquals(now + 3600_000L, QishuiProtocol.parseExpiryMs(3600, now))
        assertEquals(now + 6L * 3600_000L, QishuiProtocol.parseExpiryMs(null, now))
    }
}
