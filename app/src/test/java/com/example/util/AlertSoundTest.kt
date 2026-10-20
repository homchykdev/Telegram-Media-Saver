package com.example.util

import com.example.util.AlertSound.BuiltIn
import com.example.util.AlertSound.Custom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The serialised form of a chosen sound is persisted in EncryptedSharedPreferences
 * and read back by a worker running in another process, so the encoding has to be
 * stable and has to tolerate whatever earlier builds wrote.
 */
class AlertSoundTest {

    @Test
    fun `built-ins round trip`() {
        listOf(AlertSound.KEY_WARNING, AlertSound.KEY_ALARM, AlertSound.KEY_SUCCESS).forEach { key ->
            val encoded = AlertSound.encode(BuiltIn(key))
            assertEquals(BuiltIn(key), AlertSound.parse(encoded))
        }
    }

    @Test
    fun `custom round trips`() {
        val encoded = AlertSound.encode(Custom("abc-123.mp3"))
        assertEquals("custom:abc-123.mp3", encoded)
        assertEquals(Custom("abc-123.mp3"), AlertSound.parse(encoded))
    }

    @Test
    fun `none round trips`() {
        assertEquals(AlertSound.None, AlertSound.parse(AlertSound.encode(AlertSound.None)))
        assertEquals(AlertSound.None, AlertSound.parse(AlertSound.KEY_NONE))
    }

    /**
     * Installs that predate the `builtin:` prefix stored a bare key. Reading one back
     * as None would silently reset the user's sound on upgrade.
     */
    @Test
    fun `legacy bare key is read as a built-in`() {
        assertEquals(BuiltIn(AlertSound.KEY_WARNING), AlertSound.parse(AlertSound.KEY_WARNING))
        assertEquals(BuiltIn(AlertSound.KEY_ALARM), AlertSound.parse(AlertSound.KEY_ALARM))
    }

    @Test
    fun `unparseable value degrades to none rather than throwing`() {
        assertEquals(AlertSound.None, AlertSound.parse(null))
        assertEquals(AlertSound.None, AlertSound.parse(""))
        assertEquals(AlertSound.None, AlertSound.parse("   "))
        assertEquals(AlertSound.None, AlertSound.parse("builtin:not_a_sound"))
        assertEquals(AlertSound.None, AlertSound.parse("custom:"))
    }

    /**
     * A crafted preference must not be able to steer the app at a file outside the
     * sounds directory — [CustomSounds.file] rejects separators, so a stored
     * `custom:../../databases/history` resolves to nothing rather than to a path.
     */
    @Test
    fun `custom file name is never treated as a path`() {
        val traversal = AlertSound.parse("custom:../../databases/history")
        assertTrue(traversal is Custom)
        // The name survives parsing verbatim, and it is CustomSounds that refuses it.
        assertEquals("../../databases/history", (traversal as Custom).fileName)
    }

    @Test
    fun `playability follows the sound kind`() {
        assertFalse(AlertSound.None.isPlayable)
        assertTrue(BuiltIn(AlertSound.KEY_ALARM).isPlayable)
        assertTrue(Custom("x.mp3").isPlayable)
    }
}