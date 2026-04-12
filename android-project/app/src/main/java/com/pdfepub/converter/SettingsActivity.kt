package com.pdfepub.converter

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

class SettingsActivity : AppCompatActivity() {

    private lateinit var etSender       : TextInputEditText
    private lateinit var etPassword     : TextInputEditText
    private lateinit var etRecipient    : TextInputEditText
    private lateinit var etSmtpHost     : TextInputEditText
    private lateinit var etSmtpPort     : TextInputEditText
    private lateinit var btnSave        : MaterialButton
    private lateinit var btnHelp        : MaterialButton
    private lateinit var tvSmtpHint     : TextView
    private lateinit var headerAvancado : LinearLayout
    private lateinit var groupAvancado  : LinearLayout
    private lateinit var tvArrow        : TextView
    private lateinit var rgDarkMode     : RadioGroup
    private lateinit var rbSystem       : RadioButton
    private lateinit var rbLight        : RadioButton
    private lateinit var rbDark         : RadioButton
    private lateinit var tvSavePath     : TextView
    private lateinit var btnChoosePath  : MaterialButton
    private lateinit var btnResetPath   : MaterialButton

    private var avancadoExpanded = false
    private val REQ_TREE = 200

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        supportActionBar?.title = "Configurações"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        etSender       = findViewById(R.id.etSender)
        etPassword     = findViewById(R.id.etPassword)
        etRecipient    = findViewById(R.id.etRecipient)
        etSmtpHost     = findViewById(R.id.etSmtpHost)
        etSmtpPort     = findViewById(R.id.etSmtpPort)
        btnSave        = findViewById(R.id.btnSave)
        btnHelp        = findViewById(R.id.btnHelp)
        tvSmtpHint     = findViewById(R.id.tvSmtpHint)
        headerAvancado = findViewById(R.id.headerAvancado)
        groupAvancado  = findViewById(R.id.groupAvancado)
        tvArrow        = findViewById(R.id.tvAvancadoArrow)
        rgDarkMode     = findViewById(R.id.rgDarkMode)
        rbSystem       = findViewById(R.id.rbSystem)
        rbLight        = findViewById(R.id.rbLight)
        rbDark         = findViewById(R.id.rbDark)
        tvSavePath     = findViewById(R.id.tvSavePath)
        btnChoosePath  = findViewById(R.id.btnChoosePath)
        btnResetPath   = findViewById(R.id.btnResetPath)

        loadValues()

        etSender.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) updateSmtpHint() }
        headerAvancado.setOnClickListener { toggleAvancado() }
        btnHelp.setOnClickListener { startActivity(Intent(this, HelpActivity::class.java)) }

        rgDarkMode.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) { R.id.rbLight -> "light"; R.id.rbDark -> "dark"; else -> "system" }
            Prefs.set(this, Prefs.DARK_MODE, mode)
            Prefs.applyDarkMode(this)
        }

        btnChoosePath.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_TREE)
        }

        btnResetPath.setOnClickListener {
            Prefs.set(this, Prefs.SAVE_PATH, "")
            tvSavePath.text = "Downloads (padrão)"
            DialogHelper.success(this, "Pasta restaurada para Downloads.")
        }

        btnSave.setOnClickListener { saveValues() }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_TREE && resultCode == Activity.RESULT_OK) {
            val uri = data?.data ?: return
            // Persistir permissão
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            Prefs.set(this, Prefs.SAVE_PATH, uri.toString())
            tvSavePath.text = EpubBuilder.getSavePathLabel(this)
            DialogHelper.success(this, "Pasta de destino salva:\n${tvSavePath.text}")
        }
    }

    private fun toggleAvancado() {
        avancadoExpanded = !avancadoExpanded
        groupAvancado.visibility = if (avancadoExpanded) View.VISIBLE else View.GONE
        tvArrow.text = if (avancadoExpanded) "▼" else "▶"
    }

    private fun loadValues() {
        etSender.setText(Prefs.get(this, Prefs.SENDER))
        etPassword.setText(Prefs.get(this, Prefs.PASSWORD))
        etRecipient.setText(Prefs.get(this, Prefs.RECIPIENT))
        etSmtpHost.setText(Prefs.get(this, Prefs.SMTP_HOST))
        etSmtpPort.setText(Prefs.get(this, Prefs.SMTP_PORT))
        tvSavePath.text = EpubBuilder.getSavePathLabel(this)

        if (Prefs.get(this, Prefs.SMTP_HOST).isNotBlank()) {
            avancadoExpanded = true; groupAvancado.visibility = View.VISIBLE; tvArrow.text = "▼"
        }
        when (Prefs.get(this, Prefs.DARK_MODE, "system")) {
            "light" -> rbLight.isChecked = true
            "dark"  -> rbDark.isChecked  = true
            else    -> rbSystem.isChecked = true
        }
        updateSmtpHint()
    }

    private fun updateSmtpHint() {
        val sender = etSender.text.toString().trim()
        if (sender.contains("@")) {
            Prefs.set(this, Prefs.SENDER, sender)
            val smtp = Prefs.resolveSmtp(this)
            tvSmtpHint.text = "✅ SMTP detectado: ${smtp.host}:${smtp.port} (${if (smtp.useSsl) "SSL" else "STARTTLS"})\nDeixe os campos SMTP em branco para usar este."
        } else {
            tvSmtpHint.text = "Preencha o e-mail acima para detectar o SMTP automaticamente."
        }
    }

    private fun saveValues() {
        val sender    = etSender.text.toString().trim()
        val password  = etPassword.text.toString().trim()
        val recipient = etRecipient.text.toString().trim()
        if (sender.isBlank() || !sender.contains("@")) { etSender.error = "E-mail inválido"; return }
        if (password.isBlank()) { etPassword.error = "Informe a senha"; return }
        if (recipient.isBlank() || !recipient.contains("@")) { etRecipient.error = "E-mail de destino inválido"; return }
        Prefs.set(this, Prefs.SENDER,    sender)
        Prefs.set(this, Prefs.PASSWORD,  password)
        Prefs.set(this, Prefs.RECIPIENT, recipient)
        Prefs.set(this, Prefs.SMTP_HOST, etSmtpHost.text.toString().trim())
        Prefs.set(this, Prefs.SMTP_PORT, etSmtpPort.text.toString().trim())
        updateSmtpHint()
        DialogHelper.success(this, "Configurações salvas com sucesso!")
    }

    override fun onSupportNavigateUp(): Boolean { onBackPressedDispatcher.onBackPressed(); return true }
}
