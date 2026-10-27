package com.brightblu.parc

import android.app.Activity
import android.view.WindowInsetsController
import android.view.WindowManager
import com.brightblu.parc.model.Skin

@Suppress("DEPRECATION")
internal fun Activity.configureParcWindow() {
    window.setDecorFitsSystemWindows(false)
    window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    window.statusBarColor = Skin.Midnight.colors.bg.toInt()
    window.navigationBarColor = Skin.Midnight.colors.bg.toInt()
    window.isStatusBarContrastEnforced = false
    window.isNavigationBarContrastEnforced = false
    window.insetsController?.setSystemBarsAppearance(0,
        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
}
