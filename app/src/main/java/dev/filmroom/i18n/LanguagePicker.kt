package dev.filmroom.i18n

import android.app.AlertDialog
import android.content.Context

/** Choices use their native names so users can recover from choosing an unfamiliar language. */
object LanguagePicker {
    fun show(context:Context,required:Boolean,initialSelection:Int?=null,onApplied:()->Unit):AlertDialog {
        var selected=initialSelection?.coerceIn(L10n.supported.indices) ?: L10n.supported.indexOf(L10n.language(context)).coerceAtLeast(0)
        val dialog=AlertDialog.Builder(context)
            .setTitle("语言 / Language\n言語 / 언어")
            .setSingleChoiceItems(L10n.nativeNames.toTypedArray(),selected){d,which->
                selected=which
                (d as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).text=confirmNames[selected]
            }
            .setPositiveButton(confirmNames[selected],null)
            .apply { if(!required)setNegativeButton(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_2cd0f3be8738),null) }
            .create()
        dialog.setCancelable(!required);dialog.setCanceledOnTouchOutside(!required)
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                L10n.choose(context,L10n.supported[selected]);dialog.dismiss();onApplied()
            }
        }
        dialog.show();return dialog
    }
    private val confirmNames=listOf("继续","Continue","続ける","계속")
}
