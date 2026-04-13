package com.pdfepub.converter

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Properties
import javax.mail.AuthenticationFailedException
import javax.mail.Authenticator
import javax.mail.MessagingException
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport

class SettingsActivity : AppCompatActivity() {

    private lateinit var etSender         : TextInputEditText
    private lateinit var etPassword       : TextInputEditText
    private lateinit var etRecipient      : TextInputEditText
    private lateinit var etSmtpHost       : TextInputEditText
    private lateinit var etSmtpPort       : TextInputEditText
    private lateinit var btnSave          : MaterialButton
    private lateinit var btnHelp          : MaterialButton
    private lateinit var btnTestConnection: MaterialButton
    private lateinit var tvSmtpHint       : TextView
    private lateinit var headerAvancado   : LinearLayout
    private lateinit var groupAvancado    : LinearLayout
    private lateinit var tvArrow          : TextView
    private lateinit var rgDarkMode       : RadioGroup
    private lateinit var rbSystem         : RadioButton
    private lateinit var rbLight          : RadioButton
    private lateinit var rbDark           : RadioButton
    private lateinit var tvSavePath       : TextView
    private lateinit var btnChoosePath    : MaterialButton
    private lateinit var btnResetPath     : MaterialButton
    private lateinit var spinnerLanguage  : Spinner

    private var avancadoExpanded = false
    private val REQ_TREE = 200

    // Mapeamento código → posição no spinner
    private val LANG_CODES = listOf("system", "pt", "en", "es", "fr", "de", "it")

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        setContentView(R.layout.activity_settings)
        supportActionBar?.title = getString(R.string.settings_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        etSender          = findViewById(R.id.etSender)
        etPassword        = findViewById(R.id.etPassword)
        etRecipient       = findViewById(R.id.etRecipient)
        etSmtpHost        = findViewById(R.id.etSmtpHost)
        etSmtpPort        = findViewById(R.id.etSmtpPort)
        btnSave           = findViewById(R.id.btnSave)
        btnHelp           = findViewById(R.id.btnHelp)
        btnTestConnection = findViewById(R.id.btnTestConnection)
        tvSmtpHint        = findViewById(R.id.tvSmtpHint)
        headerAvancado    = findViewById(R.id.headerAvancado)
        groupAvancado     = findViewById(R.id.groupAvancado)
        tvArrow           = findViewById(R.id.tvAvancadoArrow)
        rgDarkMode        = findViewById(R.id.rgDarkMode)
        rbSystem          = findViewById(R.id.rbSystem)
        rbLight           = findViewById(R.id.rbLight)
        rbDark            = findViewById(R.id.rbDark)
        tvSavePath        = findViewById(R.id.tvSavePath)
        btnChoosePath     = findViewById(R.id.btnChoosePath)
        btnResetPath      = findViewById(R.id.btnResetPath)
        spinnerLanguage   = findViewById(R.id.spinnerLanguage)

        setupLanguageSpinner()
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
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).also {
                it.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }, REQ_TREE)
        }

        btnResetPath.setOnClickListener {
            Prefs.set(this, Prefs.SAVE_PATH, "")
            tvSavePath.text = getString(R.string.save_path_default)
            DialogHelper.success(this, getString(R.string.path_restored))
        }

        btnTestConnection.setOnClickListener { testEmailConnection() }
        btnSave.setOnClickListener { saveValues() }
    }

    private fun setupLanguageSpinner() {
        // Nomes visíveis no spinner
        val langNames = listOf(
            getString(R.string.lang_system),
            getString(R.string.lang_pt),
            getString(R.string.lang_en),
            getString(R.string.lang_es),
            getString(R.string.lang_fr),
            getString(R.string.lang_de),
            getString(R.string.lang_it)
        )
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, langNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerLanguage.adapter = adapter

        // Selecionar o idioma atual
        val currentLang = Prefs.get(this, Prefs.LANGUAGE, "system")
        val idx = LANG_CODES.indexOf(currentLang).coerceAtLeast(0)
        spinnerLanguage.setSelection(idx, false)

        spinnerLanguage.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            var initialized = false
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!initialized) { initialized = true; return }
                val newLang = LANG_CODES[position]
                val oldLang = Prefs.get(this@SettingsActivity, Prefs.LANGUAGE, "system")
                if (newLang != oldLang) {
                    Prefs.set(this@SettingsActivity, Prefs.LANGUAGE, newLang)
                    // Reiniciar app para aplicar idioma
                    DialogHelper.info(this@SettingsActivity, getString(R.string.language_changed)) {
                        val intent = packageManager.getLaunchIntentForPackage(packageName)
                        intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(intent)
                        finishAffinity()
                    }
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_TREE && resultCode == Activity.RESULT_OK) {
            val uri = data?.data ?: return
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            Prefs.set(this, Prefs.SAVE_PATH, uri.toString())
            tvSavePath.text = EpubBuilder.getSavePathLabel(this)
            DialogHelper.success(this, getString(R.string.path_saved, tvSavePath.text))
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
            avancadoExpanded = true
            groupAvancado.visibility = View.VISIBLE
            tvArrow.text = "▼"
        }
        when (Prefs.get(this, Prefs.DARK_MODE, "system")) {
            "light" -> rbLight.isChecked  = true
            "dark"  -> rbDark.isChecked   = true
            else    -> rbSystem.isChecked = true
        }
        updateSmtpHint()
    }

    private fun updateSmtpHint() {
        val sender = etSender.text.toString().trim()
        if (sender.contains("@")) {
            Prefs.set(this, Prefs.SENDER, sender)
            val smtp = Prefs.resolveSmtp(this)
            tvSmtpHint.text = getString(
                R.string.smtp_detected,
                smtp.host, smtp.port, if (smtp.useSsl) "SSL" else "STARTTLS"
            )
        } else {
            tvSmtpHint.text = getString(R.string.smtp_auto_hint)
        }
    }

    private fun testEmailConnection() {
        val sender   = etSender.text.toString().trim()
        val password = etPassword.text.toString().trim()

        if (sender.isBlank() || !sender.contains("@")) {
            DialogHelper.warning(this, getString(R.string.warn_fill_email)); return
        }
        if (password.isBlank()) {
            DialogHelper.warning(this, getString(R.string.warn_fill_password)); return
        }

        Prefs.set(this, Prefs.SENDER,    sender)
        Prefs.set(this, Prefs.SMTP_HOST, etSmtpHost.text.toString().trim())
        Prefs.set(this, Prefs.SMTP_PORT, etSmtpPort.text.toString().trim())
        val smtp = Prefs.resolveSmtp(this)

        btnTestConnection.isEnabled = false
        btnTestConnection.text = getString(R.string.testing)

        lifecycleScope.launch {
            val (ok, msg) = withContext(Dispatchers.IO) {
                try {
                    val props = Properties().apply {
                        put("mail.smtp.auth",              "true")
                        put("mail.smtp.host",              smtp.host)
                        put("mail.smtp.port",              smtp.port.toString())
                        put("mail.smtp.timeout",           "30000")
                        put("mail.smtp.connectiontimeout", "30000")
                        when {
                            smtp.useSsl -> {
                                put("mail.smtp.ssl.enable", "true")
                                put("mail.smtp.socketFactory.port",  "465")
                                put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory")
                                put("mail.smtp.socketFactory.fallback", "false")
                                put("mail.smtp.ssl.trust", "smtp.gmail.com")
                            }
                            smtp.useStartTls -> {
                                put("mail.smtp.starttls.enable",   "true")
                                put("mail.smtp.starttls.required", "true")
                                put("mail.smtp.ssl.protocols",     "TLSv1.2 TLSv1.3")
                            }
                            else -> put("mail.smtp.ssl.enable", "true")
                        }
                    }
                    val session = Session.getInstance(props, object : Authenticator() {
                        override fun getPasswordAuthentication() =
                            PasswordAuthentication(sender, password)
                    })
                    val transport = session.getTransport("smtp")
                    transport.connect(smtp.host, smtp.port, sender, password)
                    transport.close()
                    Pair(true, "")
                } catch (e: AuthenticationFailedException) {
                    Pair(false, getString(R.string.auth_failed, e.message ?: ""))
                } catch (e: MessagingException) {
                    Pair(false, "Não foi possível conectar.\nVerifique host, porta e conexão.\n\nDetalhe: ${e.message}")
                } catch (e: Exception) {
                    Pair(false, getString(R.string.unexpected_error, e.message ?: ""))
                }
            }

            btnTestConnection.isEnabled = true
            btnTestConnection.text = getString(R.string.btn_test_connection)

            if (ok) {
                DialogHelper.success(this@SettingsActivity,
                    getString(R.string.test_success, smtp.host, smtp.port,
                        if (smtp.useSsl) "SSL" else "STARTTLS"))
            } else {
                DialogHelper.error(this@SettingsActivity, msg)
            }
        }
    }

    private fun saveValues() {
        val sender    = etSender.text.toString().trim()
        val password  = etPassword.text.toString().trim()
        val recipient = etRecipient.text.toString().trim()
        if (sender.isBlank()    || !sender.contains("@"))    { etSender.error    = getString(R.string.error_invalid_email);    return }
        if (password.isBlank())                               { etPassword.error  = getString(R.string.error_no_password);     return }
        if (recipient.isBlank() || !recipient.contains("@")) { etRecipient.error = getString(R.string.error_invalid_recipient); return }
        Prefs.set(this, Prefs.SENDER,    sender)
        Prefs.set(this, Prefs.PASSWORD,  password)
        Prefs.set(this, Prefs.RECIPIENT, recipient)
        Prefs.set(this, Prefs.SMTP_HOST, etSmtpHost.text.toString().trim())
        Prefs.set(this, Prefs.SMTP_PORT, etSmtpPort.text.toString().trim())
        updateSmtpHint()
        DialogHelper.success(this, getString(R.string.settings_saved))
    }

    override fun onSupportNavigateUp(): Boolean { onBackPressedDispatcher.onBackPressed(); return true }
}
