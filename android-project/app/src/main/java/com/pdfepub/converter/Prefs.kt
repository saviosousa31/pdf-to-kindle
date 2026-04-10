package com.pdfepub.converter

import android.content.Context

object Prefs {
    private const val FILE = "pdfepub_config"

    const val SENDER    = "email_sender"
    const val PASSWORD  = "email_password"
    const val RECIPIENT = "email_recipient"
    const val SMTP_HOST = "smtp_host"
    const val SMTP_PORT = "smtp_port"
    const val SMTP_TLS  = "smtp_tls"

    fun set(ctx: Context, key: String, value: String) =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(key, value).apply()

    fun get(ctx: Context, key: String, default: String = ""): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(key, default) ?: default

    fun isEmailConfigured(ctx: Context): Boolean {
        val s = get(ctx, SENDER)
        val p = get(ctx, PASSWORD)
        val r = get(ctx, RECIPIENT)
        return s.isNotBlank() && s != "seu@email.com" &&
               p.isNotBlank() && p != "sua_senha" &&
               r.isNotBlank()
    }

    // host, port, useStartTls, useSsl
    // Gmail usa SSL puro (porta 465) — NÃO usa STARTTLS
    // Outlook/Yahoo usam STARTTLS (porta 587)
    private val PROVIDERS = mapOf(
        "gmail.com"      to listOf("smtp.gmail.com",       "465", "false", "true"),
        "googlemail.com" to listOf("smtp.gmail.com",       "465", "false", "true"),
        "outlook.com"    to listOf("smtp.office365.com",   "587", "true",  "false"),
        "hotmail.com"    to listOf("smtp.office365.com",   "587", "true",  "false"),
        "live.com"       to listOf("smtp.office365.com",   "587", "true",  "false"),
        "yahoo.com"      to listOf("smtp.mail.yahoo.com",  "465", "false", "true"),
        "yahoo.com.br"   to listOf("smtp.mail.yahoo.com",  "465", "false", "true"),
        "icloud.com"     to listOf("smtp.mail.me.com",     "587", "true",  "false"),
        "me.com"         to listOf("smtp.mail.me.com",     "587", "true",  "false"),
        "uol.com.br"     to listOf("smtp.uol.com.br",      "587", "true",  "false"),
        "terra.com.br"   to listOf("smtp.terra.com.br",    "587", "true",  "false"),
        "protonmail.com" to listOf("smtp.protonmail.ch",   "587", "true",  "false"),
        "proton.me"      to listOf("smtp.protonmail.ch",   "587", "true",  "false"),
        "zoho.com"       to listOf("smtp.zoho.com",        "465", "false", "true"),
    )

    data class SmtpConfig(
        val host: String,
        val port: Int,
        val useStartTls: Boolean,
        val useSsl: Boolean
    )

    fun resolveSmtp(ctx: Context): SmtpConfig {
        val customHost = get(ctx, SMTP_HOST)
        val customPort = get(ctx, SMTP_PORT)
        val customTls  = get(ctx, SMTP_TLS)

        if (customHost.isNotBlank()) {
            val port = customPort.toIntOrNull() ?: 587
            val tls  = customTls.lowercase() !in listOf("false", "0", "no")
            // Para porta 465 manual, assumir SSL; para 587, assumir STARTTLS
            return if (port == 465) {
                SmtpConfig(host = customHost, port = 465, useStartTls = false, useSsl = true)
            } else {
                SmtpConfig(host = customHost, port = port, useStartTls = tls, useSsl = false)
            }
        }

        val sender = get(ctx, SENDER)
        val domain = if ("@" in sender) sender.substringAfter("@").lowercase() else ""
        val p = PROVIDERS[domain]

        return if (p != null) {
            SmtpConfig(
                host        = p[0],
                port        = p[1].toInt(),
                useStartTls = p[2] == "true",
                useSsl      = p[3] == "true"
            )
        } else {
            // Domínio desconhecido: tentar SSL 465
            SmtpConfig(host = "smtp.$domain", port = 465, useStartTls = false, useSsl = true)
        }
    }
}
