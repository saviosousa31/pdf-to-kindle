package com.pdfepub.converter

import android.content.Context
import androidx.appcompat.app.AlertDialog

/**
 * Exibe diálogos modais com ícone contextual e botão OK.
 * Títulos e botão OK obtidos de string resources para suportar i18n.
 */
object DialogHelper {

    fun success(ctx: Context, message: String, onOk: (() -> Unit)? = null) {
        show(ctx, ctx.getString(R.string.dialog_success), message, onOk)
    }

    fun error(ctx: Context, message: String, onOk: (() -> Unit)? = null) {
        show(ctx, ctx.getString(R.string.dialog_error), message, onOk)
    }

    fun warning(ctx: Context, message: String, onOk: (() -> Unit)? = null) {
        show(ctx, ctx.getString(R.string.dialog_warning), message, onOk)
    }

    fun info(ctx: Context, message: String, onOk: (() -> Unit)? = null) {
        show(ctx, ctx.getString(R.string.dialog_info), message, onOk)
    }

    private fun show(ctx: Context, title: String, message: String, onOk: (() -> Unit)?) {
        AlertDialog.Builder(ctx)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(ctx.getString(R.string.dialog_ok)) { dialog, _ ->
                dialog.dismiss()
                onOk?.invoke()
            }
            .setCancelable(false)
            .show()
    }
}
