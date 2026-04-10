package com.pdfepub.converter

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.snackbar.Snackbar

class SettingsActivity : AppCompatActivity() {

    private lateinit var etSender    : TextInputEditText
    private lateinit var etPassword  : TextInputEditText
    private lateinit var etRecipient : TextInputEditText
    private lateinit var etSmtpHost  : TextInputEditText
    private lateinit var etSmtpPort  : TextInputEditText
    private lateinit var btnSave     : MaterialButton
    private lateinit var tvSmtpHint  : TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        supportActionBar?.title = "Configurações"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        etSender    = findViewById(R.id.etSender)
        etPassword  = findViewById(R.id.etPassword)
        etRecipient = findViewById(R.id.etRecipient)
        etSmtpHost  = findViewById(R.id.etSmtpHost)
        etSmtpPort  = findViewById(R.id.etSmtpPort)
        btnSave     = findViewById(R.id.btnSave)
        tvSmtpHint  = findViewById(R.id.tvSmtpHint)

        loadValues()

        // Live SMTP hint based on sender domain
        etSender.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) updateSmtpHint()
        }

        btnSave.setOnClickListener { saveValues() }
    }

    private fun loadValues() {
        etSender.setText(Prefs.get(this, Prefs.SENDER))
        etPassword.setText(Prefs.get(this, Prefs.PASSWORD))
        etRecipient.setText(Prefs.get(this, Prefs.RECIPIENT))
        etSmtpHost.setText(Prefs.get(this, Prefs.SMTP_HOST))
        etSmtpPort.setText(Prefs.get(this, Prefs.SMTP_PORT))
        updateSmtpHint()
    }

    private fun updateSmtpHint() {
        val sender = etSender.text.toString().trim()
        if (sender.contains("@")) {
            Prefs.set(this, Prefs.SENDER, sender)
            val smtp = Prefs.resolveSmtp(this)
            tvSmtpHint.text = "SMTP detectado automaticamente: ${smtp.host}:${smtp.port} (${if (smtp.useSsl) "SSL" else "STARTTLS"})\nDeixe os campos SMTP em branco para usar este."
        } else {
            tvSmtpHint.text = "Preencha o e-mail acima para detectar o SMTP automaticamente."
        }
    }

    private fun saveValues() {
        val sender    = etSender.text.toString().trim()
        val password  = etPassword.text.toString().trim()
        val recipient = etRecipient.text.toString().trim()
        val smtpHost  = etSmtpHost.text.toString().trim()
        val smtpPort  = etSmtpPort.text.toString().trim()

        if (sender.isBlank() || !sender.contains("@")) {
            etSender.error = "E-mail inválido"
            return
        }
        if (password.isBlank()) {
            etPassword.error = "Informe a senha"
            return
        }
        if (recipient.isBlank() || !recipient.contains("@")) {
            etRecipient.error = "E-mail de destino inválido"
            return
        }

        Prefs.set(this, Prefs.SENDER,    sender)
        Prefs.set(this, Prefs.PASSWORD,  password)
        Prefs.set(this, Prefs.RECIPIENT, recipient)
        Prefs.set(this, Prefs.SMTP_HOST, smtpHost)
        Prefs.set(this, Prefs.SMTP_PORT, smtpPort)

        updateSmtpHint()
        Snackbar.make(findViewById(android.R.id.content),
            "✓ Configurações salvas!", Snackbar.LENGTH_SHORT).show()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
