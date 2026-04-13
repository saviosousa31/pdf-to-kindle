package com.pdfepub.converter

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Wrapper de SharedPreferences criptografado com AES-256-GCM (EncryptedSharedPreferences).
 * Substitui o SharedPreferences plano para dados sensíveis (senha, e-mail, configurações SMTP).
 * Em caso de falha na inicialização do EncryptedSharedPreferences (ex: migração de dispositivo),
 * faz fallback para o SharedPreferences normal com log de aviso.
 */
object SecurePrefs {

    private const val ENCRYPTED_FILE = "pdfepub_secure"
    private const val TAG = "SecurePrefs"

    @Volatile
    private var instance: SharedPreferences? = null

    fun getPrefs(ctx: Context): SharedPreferences {
        return instance ?: synchronized(this) {
            instance ?: createPrefs(ctx.applicationContext).also { instance = it }
        }
    }

    private fun createPrefs(ctx: Context): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(ctx)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                ctx,
                ENCRYPTED_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao criar EncryptedSharedPreferences, usando fallback: ${e.message}")
            // Fallback: SharedPreferences normal (melhor do que travar o app)
            ctx.getSharedPreferences(ENCRYPTED_FILE + "_fallback", Context.MODE_PRIVATE)
        }
    }

    fun set(ctx: Context, key: String, value: String) {
        getPrefs(ctx).edit().putString(key, value).apply()
    }

    fun get(ctx: Context, key: String, default: String = ""): String =
        getPrefs(ctx).getString(key, default) ?: default

    fun clear(ctx: Context) {
        getPrefs(ctx).edit().clear().apply()
    }
}
