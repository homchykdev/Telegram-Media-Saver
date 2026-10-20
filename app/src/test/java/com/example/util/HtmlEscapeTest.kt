package com.example.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlEscapeTest {

    // --- ampersand and ordering ---------------------------------------------

    @Test
    fun `escapes a bare ampersand`() {
        assertEquals("Tom &amp; Jerry", HtmlEscape.escape("Tom & Jerry"))
    }

    @Test
    fun `an ampersand is not double escaped`() {
        // The ampersand replacement must run first, otherwise "&" would become
        // "&amp;amp;".
        val once = HtmlEscape.escape("&")
        assertEquals("&amp;", once)
        assertFalse(once.contains("&amp;amp;"))
    }

    @Test
    fun `an already escaped entity is escaped once more and stays parseable`() {
        // A caption literally containing "&amp;" must survive as "&amp;amp;"
        // rather than turning into a raw "<" style break-out.
        assertEquals("&amp;amp;", HtmlEscape.escape("&amp;"))
    }

    @Test
    fun `does not let later replacements corrupt the ampersand entity`() {
        assertEquals("&lt;b&gt;", HtmlEscape.escape("<b>"))
        assertEquals(
            "&amp;lt;b&amp;gt;",
            HtmlEscape.escape("&lt;b&gt;")
        )
    }

    @Test
    fun `a raw ampersand that would produce a tag is neutralised`() {
        // Classic injection: "&lt;script&gt;" -> must not decode back to a tag.
        val out = HtmlEscape.escape("<script>alert(1)</script>")
        assertEquals("&lt;script&gt;alert(1)&lt;/script&gt;", out)
    }

    // --- tag-like user text --------------------------------------------------

    @Test
    fun `escapes bold tags coming from user text`() {
        assertEquals("&lt;b&gt;", HtmlEscape.escape("<b>"))
    }

    @Test
    fun `escapes every character of a closing tag`() {
        assertEquals("&lt;/b&gt;", HtmlEscape.escape("</b>"))
    }

    @Test
    fun `escapes greater than separately from less than`() {
        assertEquals("a&gt;b", HtmlEscape.escape("a>b"))
    }

    @Test
    fun `escapes an attribute style tag with quotes`() {
        assertEquals(
            "&lt;a href=&quot;x&quot;&gt;link&lt;/a&gt;",
            HtmlEscape.escape("<a href=\"x\">link</a>")
        )
    }

    // --- quotes and apostrophes ----------------------------------------------

    @Test
    fun `escapes a double quote`() {
        assertEquals("say &quot;hi&quot;", HtmlEscape.escape("say \"hi\""))
    }

    @Test
    fun `escapes an apostrophe as a numeric entity`() {
        assertEquals("it&#39;s", HtmlEscape.escape("it's"))
    }

    @Test
    fun `escapes a curly apostrophe literally`() {
        // The source only escapes the ASCII apostrophe.
        assertEquals("it’s", HtmlEscape.escape("it’s"))
    }

    @Test
    fun `escapes every special character at once`() {
        assertEquals(
            "&amp;&lt;&gt;&quot;&#39;",
            HtmlEscape.escape("&<>\"'")
        )
    }

    // --- degenerate input ----------------------------------------------------

    @Test
    fun `escapes an empty string to an empty string`() {
        assertEquals("", HtmlEscape.escape(""))
    }

    @Test
    fun `escapes plain text unchanged`() {
        assertEquals("nothing to escape here 123", HtmlEscape.escape("nothing to escape here 123"))
    }

    @Test
    fun `preserves emoji`() {
        assertEquals("cool 🔥", HtmlEscape.escape("cool 🔥"))
    }

    // --- escapeAndClamp ------------------------------------------------------

    @Test
    fun `escapeAndClamp does not truncate when under the limit`() {
        val raw = "short caption"
        assertEquals("short caption", HtmlEscape.escapeAndClamp(raw, 4096))
    }

    @Test
    fun `escapeAndClamp returns the escaped text when it exactly fits`() {
        val raw = "abc"
        assertEquals("abc", HtmlEscape.escapeAndClamp(raw, 3))
    }

    @Test
    fun `escapeAndClamp clamps to the limit and appends an ellipsis`() {
        val raw = "x".repeat(100)
        val out = HtmlEscape.escapeAndClamp(raw, 10)
        assertEquals(10, out.length)
        assertTrue(out.endsWith("…"))
    }

    @Test
    fun `escapeAndClamp never exceeds the limit for various limits`() {
        val raw = "y".repeat(500)
        for (limit in 1..40) {
            val out = HtmlEscape.escapeAndClamp(raw, limit)
            assertTrue("limit=$limit length=${out.length}", out.length <= limit)
        }
    }

    @Test
    fun `escapeAndClamp measures the escaped length not the raw length`() {
        // "&" expands 1 -> 5, so 5 raw ampersands blow past a limit of 10 even
        // though the raw string is far shorter.
        val raw = "&&&&&"
        val out = HtmlEscape.escapeAndClamp(raw, 10)
        assertEquals(10, out.length)
        assertEquals("&amp;&amp…", out)
    }

    @Test
    fun `escapeAndClamp escapes before clamping`() {
        val out = HtmlEscape.escapeAndClamp("<b>bold</b>", 4096)
        assertEquals("&lt;b&gt;bold&lt;/b&gt;", out)
    }

    @Test
    fun `escapeAndClamp on empty input stays empty`() {
        assertEquals("", HtmlEscape.escapeAndClamp("", 10))
    }

    @Test
    fun `escapeAndClamp only trims whitespace that survives the cut`() {
        // "abc   d" has no trailing whitespace, so trimEnd() is a no-op and the
        // ellipsis lands on a full-width result.
        assertEquals("abc   d…", HtmlEscape.escapeAndClamp("abc   defghij", 8))
    }

    @Test
    fun `escapeAndClamp trims trailing whitespace before the ellipsis`() {
        // Cutting at 3 leaves "ab ", trimEnd() drops the space.
        assertEquals("ab…", HtmlEscape.escapeAndClamp("ab cd", 4))
    }

    @Test
    fun `escapeAndClamp of an empty input stays empty`() {
        assertEquals("", HtmlEscape.escapeAndClamp("", 1))
    }
}