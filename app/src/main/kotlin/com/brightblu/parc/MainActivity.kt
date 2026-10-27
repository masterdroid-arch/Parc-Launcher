package com.brightblu.parc

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brightblu.parc.model.Metrics
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.brightblu.parc.ring.RingLayout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** HOME host, sharing T03's activity, crash guard and navigation boundary. */
class MainActivity : ComponentActivity() {
    private val navigation by lazy {
        ShellNavigation(ShellState(layer = ParcUiPrefs(this).mode()))
    }
    private var session: LauncherSession? = null
    private val _resumed = MutableStateFlow(false)
    val resumed: StateFlow<Boolean> = _resumed.asStateFlow()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (CrashGuard(this).isSafeMode()) {
            startActivity(Intent(this, SafeModeActivity::class.java))
            finish()
            return
        }
        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> { AppGraph.refreshWallpaper(this); AppGraph.checkFolderLock(); _resumed.value = true }
                Lifecycle.Event.ON_PAUSE -> { AppGraph.folderLockHostLeft(); session?.onHostPaused(); _resumed.value = false }
                else -> Unit
            }
        })
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                session?.back() ?: navigation.emit(ShellEvent.Back)
            }
        })
        setContent {
            val configuration = LocalConfiguration.current
            val density = LocalDensity.current.density
            val geometry = remember(configuration, density) {
                val metrics = windowManager.currentWindowMetrics
                RingLayout(metrics.bounds.width() / density, metrics.bounds.height() / density,
                    metrics.windowInsets.parcInsets(density))
            }
            val services by produceState<LauncherServices?>(null, geometry) {
                value = AppGraph.get(applicationContext, geometry)
            }
            services?.let { host ->
                Box(Modifier.fillMaxSize()) {
                    LauncherHome(host, geometry, navigation, resumed, ::hideKeyboard,
                        { startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) }) { session = it }
                    if (DemoMode.enabled) {
                        val skin by host.skinProvider.active.collectAsState()
                        BasicText("DEMO", Modifier.align(Alignment.TopEnd).statusBarsPadding()
                            .padding(horizontal = Metrics.textGutterDp.dp, vertical = Metrics.gridDp.dp)
                            .background(Color(skin.colors.surface.toInt()))
                            .padding(horizontal = Metrics.gridDp.dp * 2, vertical = Metrics.gridDp.dp),
                            style = TextStyle(color = Color(skin.colors.accent.toInt()),
                                fontSize = skin.typography.caption.sizeSp.sp))
                    }
                }
            }
        }
        configureParcWindow()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // OPUS8 (T133): the Home key. LauncherSession.home is the one place that closes every
        // layer and sends the hub back to its first page, also on an already clean Home.
        if (intent.hasCategory(Intent.CATEGORY_HOME)) session?.home() ?: navigation.emit(ShellEvent.ResetToBase)
    }

    private fun hideKeyboard() {
        WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.ime())
    }

}
