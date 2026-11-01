package dev.filmroom.ui

import android.app.Activity
import android.view.View
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** Main editor only. Never hides navigation, caption controls, or the keyboard. */
object EditorSystemBars {
    fun hidesStatusBar(activity:Activity):Boolean = activity.resources.configuration.let {
        it.screenWidthDp>it.screenHeightDp && !activity.isInMultiWindowMode
    }

    fun apply(activity:Activity){
        val controller=WindowCompat.getInsetsController(activity.window,activity.window.decorView)
        controller.systemBarsBehavior=WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if(hidesStatusBar(activity))controller.hide(WindowInsetsCompat.Type.statusBars())
        else controller.show(WindowInsetsCompat.Type.statusBars())
    }

    fun safeInsets(insets:WindowInsetsCompat,hideStatus:Boolean):Insets {
        val always=WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.captionBar() or
            WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime()
        // Typed union uses max per edge: IME and navigation are not added twice.
        return insets.getInsets(always or if(hideStatus)0 else WindowInsetsCompat.Type.statusBars())
    }

    fun attach(activity:Activity,root:View){
        WindowCompat.setDecorFitsSystemWindows(activity.window,false)
        ViewCompat.setOnApplyWindowInsetsListener(root){view,insets->
            val safe=safeInsets(insets,hidesStatusBar(activity))
            if(view.paddingLeft!=safe.left || view.paddingTop!=safe.top || view.paddingRight!=safe.right || view.paddingBottom!=safe.bottom)
                view.setPadding(safe.left,safe.top,safe.right,safe.bottom)
            // Do not re-hide here: swipe-revealed transient bars must remain usable.
            insets
        }
        ViewCompat.requestApplyInsets(root)
        apply(activity)
    }
}
