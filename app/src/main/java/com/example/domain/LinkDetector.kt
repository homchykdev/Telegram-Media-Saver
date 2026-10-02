package com.example.domain

import java.util.regex.Pattern

object LinkDetector {
    private val TIKTOK_PATTERN = Pattern.compile(
        """https?://(?:www\.|vm\.|vt\.)?tiktok\.com/\S+""",
        Pattern.CASE_INSENSITIVE
    )

    fun detectTikTokUrl(text: String?): String? {
        if (text == null) return null
        val matcher = TIKTOK_PATTERN.matcher(text)
        if (matcher.find()) {
            // Trim typical trailing punctuation from pasted or shared text URLs
            return matcher.group().trimEnd('.', ',', ')', '"', '\'')
        }
        return null
    }
}
