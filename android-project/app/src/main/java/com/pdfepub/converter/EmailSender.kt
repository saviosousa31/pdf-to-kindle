package com.pdfepub.converter

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.util.Properties
import javax.activation.DataHandler
import javax.activation.DataSource
import javax.mail.Authenticator
import javax.mail.AuthenticationFailedException
import javax.mail.Message
import javax.mail.MessagingException
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart
import javax.mail.internet.MimeUtility

object EmailSender {

    data class Result(val success: Boolean, val error: String = "")

    suspend fun send(
        context: Context,
        epubUri: Uri,
        filename: String
    ): Result = withContext(Dispatchers.IO) {

        val sender    = Prefs.get(context, Prefs.SENDER)
        val password  = Prefs.get(context, Prefs.PASSWORD)
        val recipient = Prefs.get(context, Prefs.RECIPIENT)
        val smtp      = Prefs.resolveSmtp(context)

        if (sender.isBlank() || password.isBlank() || recipient.isBlank()) {
            return@withContext Result(false, "Configure o e-mail nas Configurações.")
        }

        val epubBytes: ByteArray = context.contentResolver.openInputStream(epubUri)
            ?.use { it.readBytes() }
            ?: return@withContext Result(false, "Não foi possível ler o arquivo EPUB.")

        try {
            val props = Properties().apply {
                put("mail.smtp.auth", "true")
                put("mail.smtp.host", smtp.host)
                put("mail.smtp.port", smtp.port.toString())
                put("mail.smtp.timeout", "30000")
                put("mail.smtp.connectiontimeout", "30000")

                when {
                    smtp.useSsl -> {
                        // SSL puro — Gmail (465), Yahoo, Zoho
                        put("mail.smtp.ssl.enable", "true")
                        // Configura o SocketFactory para Android
                        put("mail.smtp.socketFactory.port", "465")
                        put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory")
                        put("mail.smtp.socketFactory.fallback", "false")
                    
                        // Evita erros de certificado e define timeouts (boa prática)
                        put("mail.smtp.ssl.trust", "smtp.gmail.com")
                    }
                    smtp.useStartTls -> {
                        // STARTTLS — Outlook, iCloud, ProtonMail (587)
                        put("mail.smtp.starttls.enable", "true")
                        put("mail.smtp.starttls.required", "true")
                        put("mail.smtp.ssl.protocols", "TLSv1.2 TLSv1.3")
                    }
                    else -> {
                        put("mail.smtp.ssl.enable", "true")
                        put("mail.smtp.ssl.protocols", "TLSv1.2 TLSv1.3")
                    }
                }
            }

            val session = Session.getInstance(props, object : Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication =
                    PasswordAuthentication(sender, password)
            })

            val msg = MimeMessage(session).apply {
                setFrom(InternetAddress(sender))
                setRecipient(Message.RecipientType.TO, InternetAddress(recipient))
                subject = filename
            }

            val encodedFilename: String = try {
                MimeUtility.encodeText(filename, "UTF-8", "B")
            } catch (e: Exception) { filename }

            val epubData: ByteArray = epubBytes
            val part = MimeBodyPart()
            part.dataHandler = DataHandler(object : DataSource {
                override fun getInputStream(): InputStream = epubData.inputStream()
                override fun getOutputStream(): OutputStream = throw UnsupportedOperationException()
                override fun getContentType(): String = "application/epub+zip"
                override fun getName(): String = filename
            })
            part.fileName = encodedFilename

            val mp = MimeMultipart()
            mp.addBodyPart(part)
            msg.setContent(mp)

            Transport.send(msg)
            Result(true)

        } catch (e: AuthenticationFailedException) {
            Result(
                false,
                "Falha de autenticação.\n" +
                "• Gmail: acesse myaccount.google.com/apppasswords e use uma Senha de App.\n" +
                "• Outlook: verifique se o SMTP está habilitado.\n" +
                "Detalhe: ${e.message}"
            )
        } catch (e: MessagingException) {
            Result(false, "Erro ao enviar: ${e.message}\n\nHost: ${smtp.host}:${smtp.port}")
        } catch (e: Exception) {
            Result(false, "Erro inesperado: ${e.message}")
        }
    }
}
