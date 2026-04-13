package com.pdfepub.converter

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

object LocaleHelper {

    val SUPPORTED = listOf("system", "pt", "en", "es", "fr", "de", "it")

    private const val PREFS_FILE = "pdfepub_config"
    private const val KEY_LANG   = "app_language"

    /**
     * Envolve o contexto com o locale salvo.
     * Chamar em attachBaseContext() de TODA Activity e do Application.
     */
    fun wrap(context: Context): Context {
        val lang = getSavedLang(context)
        return applyLocale(context, lang)
    }

    /**
     * Aplica um locale específico num contexto e retorna o novo contexto.
     */
    fun applyLocale(context: Context, lang: String): Context {
        if (lang == "system") return context
        
        // ADICIONE ESTA VALIDAÇÃO:
        if (!SUPPORTED.contains(lang)) {
            return context  // Retorna contexto original se idioma inválido
        }
        
        val locale = Locale(lang)
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }

    /**
     * Lê o idioma diretamente de SharedPreferences.
     */
    fun getSavedLang(context: Context): String {
        return context.applicationContext
            .getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            .getString(KEY_LANG, "system") ?: "system"
    }

    /**
     * Salva o idioma em SharedPreferences E já aplica Locale.setDefault
     * imediatamente, para que o próximo attachBaseContext use o novo valor.
     */
    fun saveLang(context: Context, lang: String) {
        context.applicationContext
            .getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            .edit().putString(KEY_LANG, lang).commit()  // commit() sync, não apply() async

        // Aplica Locale.setDefault imediatamente para que qualquer inflate
        // posterior já use o novo locale antes do restart completo.
        if (lang != "system") {
            val locale = Locale(lang)
            Locale.setDefault(locale)
        }
    }
}
