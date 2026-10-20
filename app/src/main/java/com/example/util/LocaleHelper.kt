package com.example.util

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

object LocaleHelper {
    fun applyLocale(context: Context, langCode: String) {
        val locale = Locale.forLanguageTag(langCode)
        Locale.setDefault(locale)
        val res = context.resources
        val config = Configuration(res.configuration)
        config.setLocale(locale)
        @Suppress("DEPRECATION")
        res.updateConfiguration(config, res.displayMetrics)
        val appRes = context.applicationContext.resources
        if (appRes !== res) {
            val appConfig = Configuration(appRes.configuration)
            appConfig.setLocale(locale)
            @Suppress("DEPRECATION")
            appRes.updateConfiguration(appConfig, appRes.displayMetrics)
        }
    }
}
