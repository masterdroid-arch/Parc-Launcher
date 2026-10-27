package com.brightblu.parc

import android.app.Activity
import android.app.AlarmManager
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.WindowManager
import com.brightblu.parc.model.LookSettings
import com.brightblu.parc.model.Skin
import com.brightblu.parc.model.WallpaperStyle

/**
 * T71 wallpaper side of the look. Black and solid paint the window itself, so nothing changes for
 * the default. System adds the platform's wallpaper flag and clears the window background; the
 * renderer then paints only a translucent dim over it. No permission is needed to show the
 * wallpaper behind a home activity.
 */
@Suppress("DEPRECATION")
internal fun applyLookWindow(activity: Activity, look: LookSettings, skin: Skin) {
    val window = activity.window
    when (look.wallpaper) {
        WallpaperStyle.System -> {
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        WallpaperStyle.Image -> {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
            window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        }
        WallpaperStyle.Black -> {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
            window.setBackgroundDrawable(ColorDrawable(skin.colors.bg.toInt()))
        }
        WallpaperStyle.Solid -> {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
            window.setBackgroundDrawable(ColorDrawable(skin.colors.surface.toInt()))
        }
    }
    window.statusBarColor = skin.colors.bg.toInt()
    window.navigationBarColor = skin.colors.bg.toInt()
    applySkinSystemBars(activity, skin)
}

/** Contrast follows the actual skin background, including imported light palettes. */
internal fun applySkinSystemBars(activity: Activity, skin: Skin) {
    val bg = skin.colors.bg
    val bright = (((bg shr 16) and 255) * 299 + ((bg shr 8) and 255) * 587 + (bg and 255) * 114) / 1000 >= 128
    val mask = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
        android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
    // PhoneWindow's controller getter requires decor to exist, including hosts before setContent.
    activity.window.decorView
    activity.window.insetsController?.setSystemBarsAppearance(if (bright) mask else 0, mask)
}

/** The next alarm's trigger time, or null when none is set. Never throws to the caller. */
internal fun nextAlarmMillis(context: Context): Long? = try {
    val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
    manager?.nextAlarmClock?.triggerTime
} catch (_: SecurityException) {
    null
} catch (_: RuntimeException) {
    null
}
