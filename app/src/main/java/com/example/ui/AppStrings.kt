package com.example.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf

/**
 * Context-aware user-visible strings for English and Russian.
 */
data class AppStrings(
    // ── General ──────────────────────────────────────────────────────────────
    val settings: String,
    val back: String,
    val save: String,
    val cancel: String,
    val language: String,
    val langEnglish: String,
    val langRussian: String,

    // ── Main Screen ──────────────────────────────────────────────────────────
    val mainTitle: String,
    val urlPlaceholder: String,
    val linkIconDesc: String,
    val pasteIconDesc: String,
    val sendButton: String,
    val sendIconDesc: String,
    val recentSends: String,
    val historyEmpty: String,
    val historyEmptyIconDesc: String,
    val statusSent: String,
    val statusError: String,
    val statusSending: String,
    val optionsIconDesc: String,
    val menuRetry: String,
    val menuDelete: String,
    val typePhoto: String,
    val typeVideo: String,
    val timeJustNow: String,
    val timeYesterday: String,

    // ── Settings Screen ───────────────────────────────────────────────────────
    val settingsTitle: String,
    val sectionLanguage: String,
    val sectionTelegramApi: String,
    val labelBotToken: String,
    val labelGroupId: String,
    val groupIdPlaceholder: String,
    val showToken: String,
    val hideToken: String,
    val testConnection: String,
    val testSuccess: String,
    val testError: String,
    val sectionCache: String,
    val cacheSize: String,
    val clearCache: String,
    val cacheLimitLabel: String,
    val cacheLimitUnit: String,
    val cacheLimitHint: String,
    val cacheExpirationLabel: String,
    val cacheExpirationUnit: String,
    val cacheExpirationHint: String,
    val saveAndReturn: String,

    // ── Onboarding ────────────────────────────────────────────────────────────
    val onboardingChooseLang: String,
    val onboardingContinue: String,
    val onboardingStep1Title: String,
    val onboardingStep1Subtitle: String,
    val onboardingStep1Instructions: List<String>,
    val onboardingStep2Title: String,
    val onboardingStep2Subtitle: String,
    val onboardingStep2Instructions: List<String>,
    val onboardingStep3Title: String,
    val onboardingStep3Subtitle: String,
    val onboardingGroupIdLabel: String,
    val onboardingGroupIdRowLabel: String,
    val onboardingChecking: String,
    val onboardingConnOk: String,
    val onboardingConnErr: String,
    val onboardingTestBtn: String,
    val onboardingFinishBtn: String,
    val onboardingFinishAnyway: String,
    val onboardingNext: String,
    val onboardingSkip: String
) {
    companion object {
        val EN = AppStrings(
            settings = "Settings",
            back = "Back",
            save = "Save",
            cancel = "Cancel",
            language = "Language",
            langEnglish = "English",
            langRussian = "Русский",

            mainTitle = "Media Downloader",
            urlPlaceholder = "Paste TikTok link",
            linkIconDesc = "Link",
            pasteIconDesc = "Paste",
            sendButton = "Send to Telegram",
            sendIconDesc = "Send",
            recentSends = "Recent downloads",
            historyEmpty = "No downloads yet. Send your first link!",
            historyEmptyIconDesc = "Empty",
            statusSent = "Sent to Telegram",
            statusError = "Send failed",
            statusSending = "Sending…",
            optionsIconDesc = "Options",
            menuRetry = "Retry",
            menuDelete = "Delete",
            typePhoto = "Photo (%d)",
            typeVideo = "Video",
            timeJustNow = "Just now",
            timeYesterday = "Yesterday",

            settingsTitle = "Settings",
            sectionLanguage = "Language",
            sectionTelegramApi = "Telegram API",
            labelBotToken = "Telegram Bot Token",
            labelGroupId = "Telegram Group ID",
            groupIdPlaceholder = "Starts with -100",
            showToken = "Show",
            hideToken = "Hide",
            testConnection = "Test connection",
            testSuccess = "✅ Connection successful",
            testError = "❌ Error: ",
            sectionCache = "Cache management",
            cacheSize = "Cache size:",
            clearCache = "Clear cache",
            cacheLimitLabel = "Cache size limit",
            cacheLimitUnit = "MB",
            cacheLimitHint = "Older files will be deleted when the limit is exceeded.",
            cacheExpirationLabel = "File retention period",
            cacheExpirationUnit = "d.",
            cacheExpirationHint = "Files older than this will be deleted automatically.",
            saveAndReturn = "Save and return",

            onboardingChooseLang = "Choose language / Выберите язык",
            onboardingContinue = "Continue / Продолжить",
            onboardingStep1Title = "Step 1 of 3",
            onboardingStep1Subtitle = "Telegram Bot Token",
            onboardingStep1Instructions = listOf(
                "Open @BotFather in Telegram",
                "Send /newbot, choose a name and username",
                "Copy the token (looks like 123456789:AAExxxxxx…)"
            ),
            onboardingStep2Title = "Step 2 of 3",
            onboardingStep2Subtitle = "Telegram Group / Channel ID",
            onboardingStep2Instructions = listOf(
                "Create or open a Telegram group (not a channel or direct chat), add your bot as a member",
                "Promote it to admin with the \"Post Messages\" permission",
                "Open @username_to_id_bot, tap the paperclip, select chat → choose your group — the bot replies with the Chat ID (starts with -100)"
            ),
            onboardingStep3Title = "Step 3 of 3",
            onboardingStep3Subtitle = "Test connection",
            onboardingGroupIdLabel = "Group / Channel ID",
            onboardingGroupIdRowLabel = "Group ID",
            onboardingChecking = "Checking…",
            onboardingConnOk = "✅ Connection successful! You're all set.",
            onboardingConnErr = "❌ Error: ",
            onboardingTestBtn = "Test connection",
            onboardingFinishBtn = "Finish setup 🎉",
            onboardingFinishAnyway = "Finish anyway",
            onboardingNext = "Next →",
            onboardingSkip = "Skip setup"
        )

        val RU = AppStrings(
            settings = "Настройки",
            back = "Назад",
            save = "Сохранить",
            cancel = "Отмена",
            language = "Язык",
            langEnglish = "English",
            langRussian = "Русский",

            mainTitle = "Media Downloader",
            urlPlaceholder = "Вставьте ссылку на TikTok",
            linkIconDesc = "Ссылка",
            pasteIconDesc = "Вставить",
            sendButton = "Отправить в Telegram",
            sendIconDesc = "Отправить",
            recentSends = "Последние отправки",
            historyEmpty = "История пуста. Отправьте ссылку первыми!",
            historyEmptyIconDesc = "Пусто",
            statusSent = "Отправлено в Telegram",
            statusError = "Ошибка отправки",
            statusSending = "Отправка…",
            optionsIconDesc = "Опции",
            menuRetry = "Повторить",
            menuDelete = "Удалить",
            typePhoto = "Фото (%d)",
            typeVideo = "Видео",
            timeJustNow = "Только что",
            timeYesterday = "Вчера",

            settingsTitle = "Настройки",
            sectionLanguage = "Язык",
            sectionTelegramApi = "Telegram API",
            labelBotToken = "Telegram Bot Token",
            labelGroupId = "Telegram Group ID",
            groupIdPlaceholder = "Начинается с -100",
            showToken = "Показать",
            hideToken = "Скрыть",
            testConnection = "Проверить доступ",
            testSuccess = "✅ Подключение успешно",
            testError = "❌ Ошибка: ",
            sectionCache = "Управление кэшем",
            cacheSize = "Размер кэша:",
            clearCache = "Очистить кеш",
            cacheLimitLabel = "Предел размера кэша",
            cacheLimitUnit = "МБ",
            cacheLimitHint = "Старые файлы будут удалены при превышении лимита.",
            cacheExpirationLabel = "Срок хранения файлов",
            cacheExpirationUnit = "дн.",
            cacheExpirationHint = "Файлы старше этого времени будут удаляться автоматически.",
            saveAndReturn = "Сохранить и вернуться",

            onboardingChooseLang = "Choose language / Выберите язык",
            onboardingContinue = "Continue / Продолжить",
            onboardingStep1Title = "Шаг 1 из 3",
            onboardingStep1Subtitle = "Telegram Bot Token",
            onboardingStep1Instructions = listOf(
                "Откройте @BotFather в Telegram",
                "Отправьте /newbot, придумайте имя и username",
                "Скопируйте токен (вида 123456789:AAExxxxxx…)"
            ),
            onboardingStep2Title = "Шаг 2 из 3",
            onboardingStep2Subtitle = "ID группы / канала Telegram",
            onboardingStep2Instructions = listOf(
                "Создайте или откройте Telegram-группу (не канал и не личный чат), добавьте бота участником",
                "Назначьте его администратором с правом «Публикации сообщений»",
                "Откройте @username_to_id_bot, нажмите скрепку, выберите чат → укажите группу — бот ответит Chat ID (начинается с -100)"
            ),
            onboardingStep3Title = "Шаг 3 из 3",
            onboardingStep3Subtitle = "Проверка подключения",
            onboardingGroupIdLabel = "ID группы / канала",
            onboardingGroupIdRowLabel = "ID группы",
            onboardingChecking = "Проверка…",
            onboardingConnOk = "✅ Подключение успешно! Всё готово к работе.",
            onboardingConnErr = "❌ Ошибка: ",
            onboardingTestBtn = "Проверить подключение",
            onboardingFinishBtn = "Завершить настройку 🎉",
            onboardingFinishAnyway = "Завершить без проверки",
            onboardingNext = "Далее →",
            onboardingSkip = "Пропустить настройку"
        )

        fun forCode(code: String): AppStrings = if (code.lowercase().startsWith("ru")) RU else EN
    }
}

val LocalStrings = compositionLocalOf { AppStrings.EN }

val strings: AppStrings
    @Composable
    @ReadOnlyComposable
    get() = LocalStrings.current

@Composable
fun ProvideStrings(langCode: String, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalStrings provides AppStrings.forCode(langCode)) {
        content()
    }
}
