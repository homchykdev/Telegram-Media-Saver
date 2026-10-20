package com.example.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalizationTest {

    @Test
    fun `AppStrings provides proper strings for EN and RU`() {
        val en = AppStrings.forCode("en")
        val ru = AppStrings.forCode("ru")

        assertEquals("Settings", en.settingsTitle)
        assertEquals("Настройки", ru.settingsTitle)

        assertEquals("Send to Telegram", en.sendButton)
        assertEquals("Отправить в Telegram", ru.sendButton)

        assertEquals("Recent downloads", en.recentSends)
        assertEquals("Последние отправки", ru.recentSends)

        assertEquals("Language", en.sectionLanguage)
        assertEquals("Язык", ru.sectionLanguage)

        assertEquals("Paste TikTok link", en.urlPlaceholder)
        assertEquals("Вставьте ссылку на TikTok", ru.urlPlaceholder)
    }

    @Test
    fun `AppStrings code matching works correctly`() {
        assertEquals(AppStrings.RU, AppStrings.forCode("ru"))
        assertEquals(AppStrings.RU, AppStrings.forCode("ru-RU"))
        assertEquals(AppStrings.EN, AppStrings.forCode("en"))
        assertEquals(AppStrings.EN, AppStrings.forCode("fr"))
    }
}
