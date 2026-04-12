package com.pdfepub.converter

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(applicationContext)
        Prefs.applyDarkMode(this)
    }
}
