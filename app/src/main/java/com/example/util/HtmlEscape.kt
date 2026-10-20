package com.example.util

/**
 * Escaping for Telegram's HTML parse mode.
 *
 * TikTok captions are arbitrary user text and routinely contain `&`, `<` and `>`.
 * Telegram rejects the whole message with 400 "can't parse entities" when those are
 * passed through unescaped, so every user-controlled string must go through here
 * before being wrapped in a tag or handed to the Bot API.
 */
object HtmlEscape {

    private const val REPLACEMENTS = "&#39;" // must be applied first

    fun escape(raw: String): String {
        var out = raw.replace("&", "&amp;")
        out = out.replace("<", "&lt;")
        out = out.replace(">", "&gt;")
        out = out.replace("\"", "&quot;")
        out = out.replace("'", REPLACEMENTS)
        return out
    }

    /** Escapes and clamps to Telegram's per-message character limit. */
    fun escapeAndClamp(raw: String, limit: Int): String {
        val escaped = escape(raw)
        if (escaped.length <= limit) return escaped
        // Leave room for the ellipsis so the result never exceeds the limit.
        return escaped.take(limit - 1).trimEnd() + "…"
    }
}