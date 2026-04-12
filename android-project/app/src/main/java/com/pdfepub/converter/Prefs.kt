package com.pdfepub.converter

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

object Prefs {
    // ── Chaves públicas (não-sensíveis) — SharedPreferences normal ─────────
    private const val FILE_PUBLIC = "pdfepub_config"

    const val DARK_MODE  = "dark_mode"
    const val SAVE_PATH  = "save_path_uri"

    // ── Chaves sensíveis — armazenadas criptografadas em SecurePrefs ───────
    const val SENDER     = "email_sender"
    const val PASSWORD   = "email_password"
    const val RECIPIENT  = "email_recipient"
    const val SMTP_HOST  = "smtp_host"
    const val SMTP_PORT  = "smtp_port"
    const val SMTP_TLS   = "smtp_tls"

    private val SENSITIVE_KEYS = setOf(SENDER, PASSWORD, RECIPIENT, SMTP_HOST, SMTP_PORT, SMTP_TLS)

    // ── API pública ─────────────────────────────────────────────────────────

    fun set(ctx: Context, key: String, value: String) {
        if (key in SENSITIVE_KEYS) {
            SecurePrefs.set(ctx, key, value)
        } else {
            ctx.getSharedPreferences(FILE_PUBLIC, Context.MODE_PRIVATE)
                .edit().putString(key, value).apply()
        }
    }

    fun get(ctx: Context, key: String, default: String = ""): String {
        return if (key in SENSITIVE_KEYS) {
            SecurePrefs.get(ctx, key, default)
        } else {
            ctx.getSharedPreferences(FILE_PUBLIC, Context.MODE_PRIVATE)
                .getString(key, default) ?: default
        }
    }

    fun isEmailConfigured(ctx: Context): Boolean {
        val s = get(ctx, SENDER)
        val p = get(ctx, PASSWORD)
        val r = get(ctx, RECIPIENT)
        return s.isNotBlank() && p.isNotBlank() && r.isNotBlank()
    }

    fun applyDarkMode(ctx: Context) {
        when (get(ctx, DARK_MODE, "system")) {
            "dark"  -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            "light" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            else    -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }

    // ── Resolução SMTP ──────────────────────────────────────────────────────

    private val PROVIDERS = mapOf(
        "gmail.com"      to listOf("smtp.gmail.com",      "465","false","true"),
        "googlemail.com" to listOf("smtp.gmail.com",      "465","false","true"),
        "outlook.com"    to listOf("smtp.office365.com",  "587","true", "false"),
        "hotmail.com"    to listOf("smtp.office365.com",  "587","true", "false"),
        "live.com"       to listOf("smtp.office365.com",  "587","true", "false"),
        "yahoo.com"      to listOf("smtp.mail.yahoo.com", "465","false","true"),
        "yahoo.com.br"   to listOf("smtp.mail.yahoo.com", "465","false","true"),
        "icloud.com"     to listOf("smtp.mail.me.com",    "587","true", "false"),
        "me.com"         to listOf("smtp.mail.me.com",    "587","true", "false"),
        "uol.com.br"     to listOf("smtp.uol.com.br",     "587","true", "false"),
        "terra.com.br"   to listOf("smtp.terra.com.br",   "587","true", "false"),
        "protonmail.com" to listOf("smtp.protonmail.ch",  "587","true", "false"),
        "proton.me"      to listOf("smtp.protonmail.ch",  "587","true", "false"),
        "zoho.com"       to listOf("smtp.zoho.com",       "465","false","true"),
    )

    data class SmtpConfig(val host: String, val port: Int, val useStartTls: Boolean, val useSsl: Boolean)

    fun resolveSmtp(ctx: Context): SmtpConfig {
        val customHost = get(ctx, SMTP_HOST)
        val customPort = get(ctx, SMTP_PORT)
        val customTls  = get(ctx, SMTP_TLS)
        if (customHost.isNotBlank()) {
            val port = customPort.toIntOrNull() ?: 587
            return if (port == 465) SmtpConfig(customHost, 465, false, true)
            else SmtpConfig(customHost, port, customTls.lowercase() !in listOf("false","0","no"), false)
        }
        val sender = get(ctx, SENDER)
        val domain = if ("@" in sender) sender.substringAfter("@").lowercase() else ""
        val p = PROVIDERS[domain]
        return if (p != null) SmtpConfig(p[0], p[1].toInt(), p[2]=="true", p[3]=="true")
        else SmtpConfig("smtp.$domain", 465, false, true)
    }
}
