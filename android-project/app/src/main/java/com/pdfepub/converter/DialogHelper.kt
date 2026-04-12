package com.pdfepub.converter

import android.content.Context
import androidx.appcompat.app.AlertDialog

/**
 * Exibe diálogos modais (popup) com ícone contextual e botão OK.
 * Substitui Snackbars em todo o app.
 */
object DialogHelper {

    fun success(ctx: Context, message: String, onOk: (() -> Unit)? = null) {
        show(ctx, "✅  Sucesso", message, onOk)
    }

    fun error(ctx: Context, message: String, onOk: (() -> Unit)? = null) {
        show(ctx, "❌  Erro", message, onOk)
    }

    fun warning(ctx: Context, message: String, onOk: (() -> Unit)? = null) {
        show(ctx, "⚠️  Atenção", message, onOk)
    }

    fun info(ctx: Context, message: String, onOk: (() -> Unit)? = null) {
        show(ctx, "ℹ️  Informação", message, onOk)
    }

    private fun show(ctx: Context, title: String, message: String, onOk: (() -> Unit)?) {
        AlertDialog.Builder(ctx)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK") { dialog, _ ->
                dialog.dismiss()
                onOk?.invoke()
            }
            .setCancelable(false)
            .show()
    }
}
