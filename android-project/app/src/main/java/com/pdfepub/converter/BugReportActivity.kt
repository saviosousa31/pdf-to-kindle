package com.pdfepub.converter

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.Properties
import javax.activation.DataHandler
import javax.activation.DataSource
import javax.mail.Authenticator
import javax.mail.AuthenticationFailedException
import javax.mail.Message
import javax.mail.MessagingException
import javax.mail.Multipart
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart
import javax.mail.internet.MimeUtility

class BugReportActivity : AppCompatActivity() {

    private lateinit var etTitle       : TextInputEditText
    private lateinit var etDescription : TextInputEditText
    private lateinit var btnAddFile    : MaterialButton
    private lateinit var btnSend       : MaterialButton
    private lateinit var rvAttachments : RecyclerView
    private lateinit var tvNoEmail     : TextView
    private lateinit var layoutForm    : LinearLayout

    private val attachments = mutableListOf<Pair<String, Uri>>() // filename → uri
    private val PICK_FILE = 301

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bug_report)
        supportActionBar?.title = getString(R.string.bug_report_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        etTitle       = findViewById(R.id.etBugTitle)
        etDescription = findViewById(R.id.etBugDescription)
        btnAddFile    = findViewById(R.id.btnAddAttachment)
        btnSend       = findViewById(R.id.btnSendBugReport)
        rvAttachments = findViewById(R.id.rvAttachments)
        tvNoEmail     = findViewById(R.id.tvNoEmailWarning)
        layoutForm    = findViewById(R.id.layoutBugForm)

        rvAttachments.layoutManager = LinearLayoutManager(this)
        rvAttachments.adapter = AttachmentAdapter(attachments) { pos ->
            attachments.removeAt(pos)
            rvAttachments.adapter?.notifyItemRemoved(pos)
        }

        // Verificar se e-mail está configurado
        if (!Prefs.isEmailConfigured(this)) {
            layoutForm.visibility = View.GONE
            tvNoEmail.visibility  = View.VISIBLE
        }

        btnAddFile.setOnClickListener {
            @Suppress("DEPRECATION")
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*", "image/gif"))
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }, PICK_FILE
            )
        }

        btnSend.setOnClickListener { sendBugReport() }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_FILE && resultCode == Activity.RESULT_OK) {
            val clip = data?.clipData
            if (clip != null) {
                for (i in 0 until clip.itemCount) addAttachment(clip.getItemAt(i).uri)
            } else {
                data?.data?.let { addAttachment(it) }
            }
        }
    }

    private fun addAttachment(uri: Uri) {
        if (attachments.size >= 5) {
            DialogHelper.warning(this, getString(R.string.bug_max_attachments)); return
        }
        val name = getFileName(uri)
        // Tomar permissão persistente se possível
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {}
        attachments.add(Pair(name, uri))
        rvAttachments.adapter?.notifyItemInserted(attachments.size - 1)
    }

    private fun getFileName(uri: Uri): String {
        var name = "anexo_${System.currentTimeMillis()}"
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val col = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (col >= 0) name = c.getString(col) ?: name
            }
        }
        return name
    }

    private fun sendBugReport() {
        val title = etTitle.text.toString().trim()
        val desc  = etDescription.text.toString().trim()

        if (title.isBlank()) { etTitle.error = getString(R.string.bug_title_required); return }
        if (desc.isBlank())  { etDescription.error = getString(R.string.bug_desc_required); return }

        btnSend.isEnabled = false
        btnSend.text = getString(R.string.sending)

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { doSendBugReport(title, desc) }
            btnSend.isEnabled = true
            btnSend.text = getString(R.string.bug_send)
            if (result.success) {
                DialogHelper.success(this@BugReportActivity, getString(R.string.bug_sent_ok)) {
                    finish()
                }
            } else {
                DialogHelper.error(this@BugReportActivity, result.error)
            }
        }
    }

    private fun doSendBugReport(title: String, desc: String): EmailSender.Result {
        val sender   = Prefs.get(this, Prefs.SENDER)
        val password = Prefs.get(this, Prefs.PASSWORD)
        val smtp     = Prefs.resolveSmtp(this)
        val bugEmail = getString(R.string.bug_report_email)

        try {
            val props = Properties().apply {
                put("mail.smtp.auth", "true")
                put("mail.smtp.host", smtp.host)
                put("mail.smtp.port", smtp.port.toString())
                put("mail.smtp.timeout", "30000")
                put("mail.smtp.connectiontimeout", "30000")
                when {
                    smtp.useSsl -> {
                        put("mail.smtp.ssl.enable", "true")
                        put("mail.smtp.socketFactory.port", "465")
                        put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory")
                        put("mail.smtp.socketFactory.fallback", "false")
                        put("mail.smtp.ssl.trust", smtp.host)
                    }
                    smtp.useStartTls -> {
                        put("mail.smtp.starttls.enable", "true")
                        put("mail.smtp.starttls.required", "true")
                        put("mail.smtp.ssl.protocols", "TLSv1.2 TLSv1.3")
                    }
                    else -> put("mail.smtp.ssl.enable", "true")
                }
            }

            val session = Session.getInstance(props, object : Authenticator() {
                override fun getPasswordAuthentication() = PasswordAuthentication(sender, password)
            })

            val msg = MimeMessage(session).apply {
                setFrom(InternetAddress(sender))
                setRecipient(Message.RecipientType.TO, InternetAddress(bugEmail))
                subject = MimeUtility.encodeText("[Bug Report] $title", "UTF-8", "B")
            }

            val mp = MimeMultipart()

            // Corpo do e-mail
            val body = MimeBodyPart()
            body.setText(
                "Title: $title\n\nDescription:\n$desc\n\n---\nApp: EPUB Maker\nRemetente: $sender",
                "UTF-8"
            )
            mp.addBodyPart(body)

            // Anexos
            for ((name, uri) in attachments) {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: continue
                val part = MimeBodyPart()
                part.dataHandler = DataHandler(object : DataSource {
                    override fun getInputStream(): InputStream = bytes.inputStream()
                    override fun getOutputStream(): OutputStream = throw UnsupportedOperationException()
                    override fun getContentType(): String = contentResolver.getType(uri) ?: "application/octet-stream"
                    override fun getName(): String = name
                })
                part.fileName = MimeUtility.encodeText(name, "UTF-8", "B")
                mp.addBodyPart(part)
            }

            msg.setContent(mp)
            Transport.send(msg)
            return EmailSender.Result(true)

        } catch (e: AuthenticationFailedException) {
            return EmailSender.Result(false, getString(R.string.auth_failed, e.message ?: ""))
        } catch (e: MessagingException) {
            return EmailSender.Result(false, getString(R.string.send_error, e.message ?: "", smtp.host, smtp.port))
        } catch (e: Exception) {
            return EmailSender.Result(false, getString(R.string.unexpected_error, e.message ?: ""))
        }
    }

    override fun onSupportNavigateUp(): Boolean { onBackPressedDispatcher.onBackPressed(); return true }

    // ── Adapter de anexos ─────────────────────────────────────────────────
    private class AttachmentAdapter(
        private val items: List<Pair<String, Uri>>,
        private val onRemove: (Int) -> Unit
    ) : RecyclerView.Adapter<AttachmentAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvName: TextView = v.findViewById(R.id.tvAttachmentName)
            val btnRemove: MaterialButton = v.findViewById(R.id.btnRemoveAttachment)
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, t: Int) =
            VH(android.view.LayoutInflater.from(parent.context).inflate(R.layout.item_attachment, parent, false))

        override fun onBindViewHolder(h: VH, pos: Int) {
            h.tvName.text = items[pos].first
            h.btnRemove.setOnClickListener { onRemove(h.bindingAdapterPosition) }
        }

        override fun getItemCount() = items.size
    }
}
