package com.pdfepub.converter

import android.app.Application
import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            PDFBoxResourceLoader.init(applicationContext)
        } catch (e: Exception) {
            android.util.Log.e("App", "Erro ao inicializar PDFBox: ${e.message}")
        }
        try {
            Prefs.applyDarkMode(this)
        } catch (e: Exception) {
            android.util.Log.e("App", "Erro ao aplicar dark mode: ${e.message}")
        }
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.wrap(base))
    }
}
