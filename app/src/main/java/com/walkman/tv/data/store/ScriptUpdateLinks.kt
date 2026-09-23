package com.walkman.tv.data.store

import java.net.URI

/** Extract direct download candidates from lx-music's updateUrl and notice text. */
internal object ScriptUpdateLinks {
    private val url = Regex("""https://[^\s<>\"'，。！？、）]+""", RegexOption.IGNORE_CASE)

    fun candidates(updateUrl: String?, log: String): List<String> =
        sequenceOf(updateUrl.orEmpty(), log)
            .flatMap { url.findAll(it).map { match -> match.value.trimEnd(')', ']', '.', ';', ',') } }
            .filter { candidate ->
                runCatching {
                    val parsed = URI(candidate)
                    parsed.scheme.equals("https", ignoreCase = true) &&
                        !parsed.host.isNullOrBlank() && parsed.userInfo == null
                }.getOrDefault(false)
            }
            .distinct()
            .toList()
}
