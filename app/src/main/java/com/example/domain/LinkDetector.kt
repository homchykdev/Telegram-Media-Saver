package com.example.domain

import java.util.regex.Pattern

object LinkDetector {

    /**
     * Matches tiktok.com short links (vm./vt./m./www./bare) inside arbitrary shared
     * text. Captures only up to the first whitespace so that trailing prose from the
     * sharing app is not swallowed into the URL.
     */
    private val TIKTOK_PATTERN: Pattern = Pattern.compile(
        """https?://(?:[\w-]+\.)*tiktok\.com/[^\s<>"']+""",
        Pattern.CASE_INSENSITIVE
    )

    private val TRAILING_JUNK = charArrayOf('.', ',', ';', ':', '!', '?', ')', ']', '}', '"', '\'', '>', '、', '，', '。')

    fun detectTikTokUrl(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val matcher = TIKTOK_PATTERN.matcher(text)
        if (!matcher.find()) return null

        var url = matcher.group().trimEnd(*TRAILING_JUNK)

        // Balance brackets that were part of the URL itself, e.g. a "(...)" in the path.
        while (url.isNotEmpty() && url.last() == ')') {
            val open = url.count { it == '(' }
            val close = url.count { it == ')' }
            if (close <= open) break
            url = url.dropLast(1)
        }
        return url.ifEmpty { null }
    }

    /** True when the text contains any TikTok link, regardless of position. */
    fun containsTikTokUrl(text: String?): Boolean = detectTikTokUrl(text) != null
}