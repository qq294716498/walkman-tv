package com.walkman.tv.data.store

import org.junit.Assert.assertEquals
import org.junit.Test

class ScriptUpdateLinksTest {
    @Test
    fun prefersUpdateUrlThenFindsDirectLinkInNotice() {
        val page = "https://example.org/releases"
        val script = "https://source.example.org/api/script/lx?key=abc-123"
        assertEquals(
            listOf(page, script),
            ScriptUpdateLinks.candidates(page, "新版下载地址：$script。"),
        )
    }

    @Test
    fun ignoresInsecureAndMalformedLinks() {
        assertEquals(
            listOf("https://valid.example.org/source.js"),
            ScriptUpdateLinks.candidates(
                "http://unsafe.example.org/source.js",
                "https://user:pass@bad.example.org/a.js https://valid.example.org/source.js",
            ),
        )
    }

    @Test
    fun deduplicatesRepeatedLink() {
        val script = "https://source.example.org/source.js"
        assertEquals(listOf(script), ScriptUpdateLinks.candidates(script, "请下载 $script"))
    }
}
