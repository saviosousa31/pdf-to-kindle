package com.pdfepub.converter

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import java.util.Locale

object LocaleHelper {

    /** Códigos aceitos pelo app. "system" = seguir o dispositivo. */
    val SUPPORTED = listOf("system", "pt", "en", "es", "fr", "de", "it")

    /** Aplica o locale ao contexto. Chamar em attachBaseContext de cada Activity. */
    fun wrap(context: Context): Context {
        val lang = Prefs.get(context, Prefs.LANGUAGE, "system")
        if (lang == "system") return context
        val locale = Locale(lang)
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }

    /** Aplica globalmente (necessário no Application.onCreate). */
    fun applyLocale(context: Context) {
        val lang = Prefs.get(context, Prefs.LANGUAGE, "system")
        if (lang == "system") return
        val locale = Locale(lang)
        Locale.setDefault(locale)
        val config = context.resources.configuration
        config.setLocale(locale)
        @Suppress("DEPRECATION")
        context.resources.updateConfiguration(config, context.resources.displayMetrics)
    }
}
