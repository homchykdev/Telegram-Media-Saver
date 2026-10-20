package com.example.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkDetectorTest {

    // --- canonical / full length links -------------------------------------

    @Test
    fun `detects a bare www video url`() {
        assertEquals(
            "https://www.tiktok.com/@user/video/123",
            LinkDetector.detectTikTokUrl("https://www.tiktok.com/@user/video/123")
        )
    }

    @Test
    fun `detects a vm short link`() {
        assertEquals(
            "https://vm.tiktok.com/ZMabcdef/",
            LinkDetector.detectTikTokUrl("https://vm.tiktok.com/ZMabcdef/")
        )
    }

    @Test
    fun `detects a vt short link`() {
        assertEquals(
            "https://vt.tiktok.com/ZSabc123/",
            LinkDetector.detectTikTokUrl("https://vt.tiktok.com/ZSabc123/")
        )
    }

    @Test
    fun `detects a short link without trailing slash`() {
        assertEquals(
            "https://vm.tiktok.com/ZMabcdef",
            LinkDetector.detectTikTokUrl("https://vm.tiktok.com/ZMabcdef")
        )
    }

    @Test
    fun `detects a bare apex host link`() {
        assertEquals(
            "https://tiktok.com/@user/video/1",
            LinkDetector.detectTikTokUrl("https://tiktok.com/@user/video/1")
        )
    }

    @Test
    fun `keeps query string and fragment`() {
        val url = "https://www.tiktok.com/@user/video/123?is_from_webapp=1&sender_device=pc"
        assertEquals(url, LinkDetector.detectTikTokUrl(url))
    }

    // --- extraction from surrounding prose ---------------------------------

    @Test
    fun `extracts a url embedded in prose`() {
        assertEquals(
            "https://vm.tiktok.com/ZMabc/",
            LinkDetector.detectTikTokUrl("Check this out https://vm.tiktok.com/ZMabc/ so cool")
        )
    }

    @Test
    fun `extracts a url that starts the text and is followed by more text`() {
        assertEquals(
            "https://vm.tiktok.com/ZMabc/",
            LinkDetector.detectTikTokUrl("https://vm.tiktok.com/ZMabc/ is the one")
        )
    }

    @Test
    fun `returns the first match when several urls are present`() {
        assertEquals(
            "https://vm.tiktok.com/first/",
            LinkDetector.detectTikTokUrl(
                "https://vm.tiktok.com/first/ and also https://vm.tiktok.com/second/"
            )
        )
    }

    @Test
    fun `detects a url inside a tiktok app scheme-less share string`() {
        assertEquals(
            "https://vm.tiktok.com/ZM-R8abc/",
            LinkDetector.detectTikTokUrl("TikTok video https://vm.tiktok.com/ZM-R8abc/")
        )
    }

    // --- trailing junk trimming ---------------------------------------------

    @Test
    fun `trims a trailing full stop`() {
        assertEquals(
            "https://www.tiktok.com/@a/video/1",
            LinkDetector.detectTikTokUrl("link: https://www.tiktok.com/@a/video/1.")
        )
    }

    @Test
    fun `trims several trailing junk characters`() {
        assertEquals(
            "https://www.tiktok.com/@a/video/1",
            LinkDetector.detectTikTokUrl("https://www.tiktok.com/@a/video/1.)\"")
        )
    }

    @Test
    fun `trims trailing comma`() {
        assertEquals(
            "https://www.tiktok.com/@a/video/1",
            LinkDetector.detectTikTokUrl("see https://www.tiktok.com/@a/video/1,")
        )
    }

    @Test
    fun `a double quote terminates the url and is not part of it`() {
        assertEquals(
            "https://www.tiktok.com/@a/video/1",
            LinkDetector.detectTikTokUrl("look at \"https://www.tiktok.com/@a/video/1\"")
        )
    }

    @Test
    fun `trims trailing question mark and exclamation mark`() {
        assertEquals(
            "https://www.tiktok.com/@a/video/1",
            LinkDetector.detectTikTokUrl("https://www.tiktok.com/@a/video/1?!")
        )
    }

    @Test
    fun `trims cjk full stop`() {
        assertEquals(
            "https://www.tiktok.com/@a/video/1",
            LinkDetector.detectTikTokUrl("https://www.tiktok.com/@a/video/1。")
        )
    }

    @Test
    fun `drops unbalanced closing paren added by prose`() {
        assertEquals(
            "https://vm.tiktok.com/ZMabc/",
            LinkDetector.detectTikTokUrl("(https://vm.tiktok.com/ZMabc/)")
        )
    }

    @Test
    fun `keeps a balanced paren that belongs to the url`() {
        assertEquals(
            "https://vm.tiktok.com/ZMabc()/",
            LinkDetector.detectTikTokUrl("https://vm.tiktok.com/ZMabc()/")
        )
    }

    @Test
    fun `a url ending in parens loses them because they are trailing junk`() {
        // ')' is part of TRAILING_JUNK, so trimEnd() removes it before the
        // balancing loop can notice that the pair was balanced. The parens survive
        // only when the url does not end with them.
        assertEquals(
            "https://vm.tiktok.com/ZMabc(",
            LinkDetector.detectTikTokUrl("see https://vm.tiktok.com/ZMabc() please")
        )
    }

    // --- case insensitivity -------------------------------------------------

    @Test
    fun `matches an uppercase scheme and host and preserves it`() {
        assertEquals(
            "HTTPS://VM.TIKTOK.COM/ZMabc/",
            LinkDetector.detectTikTokUrl("HTTPS://VM.TIKTOK.COM/ZMabc/")
        )
    }

    @Test
    fun `matches a mixed case host`() {
        assertEquals(
            "https://Vm.TikTok.Com/ZMabc/",
            LinkDetector.detectTikTokUrl("https://Vm.TikTok.Com/ZMabc/")
        )
    }

    // --- negatives ----------------------------------------------------------

    @Test
    fun `returns null for null input`() {
        assertNull(LinkDetector.detectTikTokUrl(null))
    }

    @Test
    fun `returns null for empty input`() {
        assertNull(LinkDetector.detectTikTokUrl(""))
    }

    @Test
    fun `returns null for blank input`() {
        assertNull(LinkDetector.detectTikTokUrl("   \n\t "))
    }

    @Test
    fun `returns null for a non tiktok url`() {
        assertNull(LinkDetector.detectTikTokUrl("https://youtube.com/watch?v=x"))
    }

    @Test
    fun `returns null for a url that merely contains tiktok in the path`() {
        assertNull(LinkDetector.detectTikTokUrl("https://example.com/tiktok.com/@user/video/1"))
    }

    @Test
    fun `returns null for a tiktok mention without a scheme`() {
        assertNull(LinkDetector.detectTikTokUrl("tiktok.com/@user/video/123"))
    }

    @Test
    fun `returns null for plain prose`() {
        assertNull(LinkDetector.detectTikTokUrl("just some text, no links here"))
    }

    @Test
    fun `keeps the bare host url and only drops the trailing dots`() {
        // Sanity check that junk trimming is a trim, not a strip of everything
        // after the host.
        assertEquals(
            "https://tiktok.com/",
            LinkDetector.detectTikTokUrl("https://tiktok.com/...")
        )
    }

    // --- containsTikTokUrl --------------------------------------------------

    @Test
    fun `contains returns true for a real link`() {
        assertTrue(LinkDetector.containsTikTokUrl("watch https://vm.tiktok.com/ZMabc/ now"))
    }

    @Test
    fun `contains returns false for a foreign link`() {
        assertFalse(LinkDetector.containsTikTokUrl("https://youtube.com/watch?v=x"))
    }

    @Test
    fun `contains returns false for null`() {
        assertFalse(LinkDetector.containsTikTokUrl(null))
    }

    @Test
    fun `contains returns true even when only junk follows the url`() {
        assertTrue(LinkDetector.containsTikTokUrl("https://www.tiktok.com/@a/video/1."))
    }
}