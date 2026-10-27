package com.brightblu.parc

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.brightblu.parc.model.AppEntry
import com.brightblu.parc.model.AppId
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin

/** T118 device checks of production timer scheduling and uninstall routing, with no private fixtures. */
class DistributionReviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra("uninstall", false)) {
            LauncherAppActions(this).uninstall(AppEntry(AppId("$packageName/.MainActivity"), "Parc", packageName, false, 0))
            finish()
            return
        }
        val timerMs = intent.getLongExtra("timerMs", 0)
        if (timerMs > 0) TimerEngine.launch(this) { state, at ->
            state.book.start(timerMs, at)?.let { state.copy(book = it) } ?: state
        }
        android.util.Log.i("T118", "${BuildConfig.FLAVOR}: exact=${TimerScheduler.canScheduleExact(this)}, files=${BuildConfig.FILE_SEARCH_ENABLED}, contacts=${BuildConfig.CONTACT_SEARCH_ENABLED}")
        setContent {
            val skin = Skin.Midnight
            Box(Modifier.fillMaxSize().background(Color(skin.colors.bg.toInt()))
                .padding(horizontal = Metrics.textGutterDp.dp, vertical = Metrics.keepOutTopDp.dp)) {
                TimersHubPage(true, listOf("timers", "stopwatch"), true, skin)
            }
        }
    }
}
