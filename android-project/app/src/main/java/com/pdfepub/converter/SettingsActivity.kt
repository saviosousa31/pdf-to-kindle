package com.pdfepub.converter

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText

class SettingsActivity : AppCompatActivity() {

    private lateinit var etSender       : TextInputEditText
    private lateinit var etPassword     : TextInputEditText
    private lateinit var etRecipient    : TextInputEditText
    private lateinit var etSmtpHost     : TextInputEditText
    private lateinit var etSmtpPort     : TextInputEditText
    private lateinit var btnSave        : MaterialButton
    private lateinit var tvSmtpHint     : TextView
    private lateinit var headerAvancado : LinearLayout
    private lateinit var groupAvancado  : LinearLayout
    private lateinit var tvArrow        : TextView

    private var avancadoExpanded = false

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
        tvSmtpHint     = findViewById(R.id.tvSmtpHint)
        headerAvancado = findViewById(R.id.headerAvancado)
        groupAvancado  = findViewById(R.id.groupAvancado)
        tvArrow        = findViewById(R.id.tvAvancadoArrow)

        loadValues()

        etSender.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) updateSmtpHint()
        }

        // Expansão/colapso da seção Avançado
        headerAvancado.setOnClickListener { toggleAvancado() }

        btnSave.setOnClickListener { saveValues() }
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

        // Se já há host personalizado salvo, expandir a seção automaticamente
        if (Prefs.get(this, Prefs.SMTP_HOST).isNotBlank()) {
            avancadoExpanded = true
            groupAvancado.visibility = View.VISIBLE
            tvArrow.text = "▼"
        }

        updateSmtpHint()
    }

    private fun updateSmtpHint() {
        val sender = etSender.text.toString().trim()
        if (sender.contains("@")) {
            Prefs.set(this, Prefs.SENDER, sender)
            val smtp = Prefs.resolveSmtp(this)
            tvSmtpHint.text =
                "✅ SMTP detectado: ${smtp.host}:${smtp.port} " +
                "(${if (smtp.useSsl) "SSL" else "STARTTLS"})\n" +
                "Deixe os campos de Avançado em branco para usar este."
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
            etSender.error = "E-mail inválido"; return
        }
        if (password.isBlank()) {
            etPassword.error = "Informe a senha"; return
        }
        if (recipient.isBlank() || !recipient.contains("@")) {
            etRecipient.error = "E-mail de destino inválido"; return
        }

        Prefs.set(this, Prefs.SENDER,    sender)
        Prefs.set(this, Prefs.PASSWORD,  password)
        Prefs.set(this, Prefs.RECIPIENT, recipient)
        Prefs.set(this, Prefs.SMTP_HOST, smtpHost)
        Prefs.set(this, Prefs.SMTP_PORT, smtpPort)

        updateSmtpHint()
        Snackbar.make(
            findViewById(android.R.id.content),
            "✓ Configurações salvas!",
            Snackbar.LENGTH_SHORT
        ).show()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
