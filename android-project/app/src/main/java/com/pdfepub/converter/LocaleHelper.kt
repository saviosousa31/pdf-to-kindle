package com.pdfepub.converter

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

object LocaleHelper {

    val SUPPORTED = listOf("system", "pt", "en", "es", "fr", "de", "it")

    /**
     * Envolve o contexto com o locale salvo.
     * Chamar em attachBaseContext() de TODA Activity e do Application.
     */
    fun wrap(context: Context): Context {
        val lang = getSavedLang(context)
        if (lang == "system") return context
        val locale = Locale(lang)
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }

    /**
     * Lê o idioma diretamente de SharedPreferences (sem passar pelo Prefs,
     * pois Prefs pode não estar inicializado no attachBaseContext do Application).
     */
    fun getSavedLang(context: Context): String {
        return context.getSharedPreferences("pdfepub_config", Context.MODE_PRIVATE)
            .getString("app_language", "system") ?: "system"
    }

    fun saveLang(context: Context, lang: String) {
        context.getSharedPreferences("pdfepub_config", Context.MODE_PRIVATE)
            .edit().putString("app_language", lang).apply()
    }
}
