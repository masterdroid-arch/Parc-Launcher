package com.brightblu.parc

import com.brightblu.parc.model.textFamily
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
    private var sharedQuote by mutableStateOf<String?>(null)
    private var sharedNote by mutableStateOf<String?>(null)
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
        if (savedInstanceState == null) postNoteRequest(intent)
        sharedQuote = savedInstanceState?.getString("quote_share")
        sharedNote = savedInstanceState?.getString("note_share")
        // A recreation (rotation, process death) hands back the original launch intent: a share is read
        // only on a fresh start, so it can never post a second note.
        if (savedInstanceState == null) postQuoteShare(intent)
        if (savedInstanceState == null) postNoteShare(intent)
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
                    sharedQuote?.let { shared ->
                        val skin by host.skinProvider.active.collectAsState()
                        key(shared) {
                            val draft = remember { QuoteDraft.shared(shared) }
                            QuoteEditorSheet(draft, skin, rememberQuotesStore()) { sharedQuote = null }
                        }
                    }
                    sharedNote?.takeIf { it.isNotBlank() }?.let { shared ->
                        val skin by host.skinProvider.active.collectAsState()
                        val noteStore = remember { NotesStore.get(this@MainActivity) }
                        key(shared) {
                            val note = remember(shared) {
                                Note(java.util.UUID.randomUUID().toString(), shared, updatedAt = System.currentTimeMillis())
                            }
                            NoteEditor(note, skin, noteStore, changed = {}, close = { sharedNote = null }, saveOnOpen = true)
                        }
                    }
                    if (DemoMode.enabled) {
                        val skin by host.skinProvider.active.collectAsState()
                        BasicText("DEMO", Modifier.align(Alignment.TopEnd).statusBarsPadding()
                            .padding(horizontal = Metrics.textGutterDp.dp, vertical = Metrics.lineGapDp.dp)
                            .background(Color(skin.colors.surface.toInt()))
                            .padding(horizontal = Metrics.itemGapDp.dp, vertical = Metrics.lineGapDp.dp),
                            style = TextStyle(fontFamily = skin.textFamily, color = Color(skin.colors.accent.toInt()),
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
        postNoteRequest(intent)
        postQuoteShare(intent)
        postNoteShare(intent)
        // T241: a Home press resets the hub only while Parc is already in front. A HOME intent
        // that brings Parc back from another app, the reader or recents keeps the page the user left.
        val alreadyInFront = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        if (HomeKey.shouldResetOnHomeIntent(intent.hasCategory(Intent.CATEGORY_HOME), alreadyInFront)) {
            session?.home() ?: navigation.emit(ShellEvent.ResetToBase)
        }
    }

    /** T183: a tapped reminder or finished note timer asks to open its note. */
    private fun postNoteRequest(intent: Intent?) {
        val id = intent?.getStringExtra(NoteOpenRequests.ExtraNoteId) ?: return
        intent.removeExtra(NoteOpenRequests.ExtraNoteId)
        NoteOpenRequests.post(id)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("quote_share", sharedQuote)
        outState.putString("note_share", sharedNote)
        super.onSaveInstanceState(outState)
    }

    private fun postQuoteShare(intent: Intent?) {
        if (intent == null) return
        val text = runCatching { QuoteRules.sharedText(intent.action, intent.type,
            intent.getCharSequenceExtra(Intent.EXTRA_TEXT)) }.getOrNull() ?: return
        sharedQuote = text
        intent.removeExtra(Intent.EXTRA_TEXT)
    }

    /** T250: text forwarded by [ShareNoteActivity]; removed so a recreation cannot post it twice. */
    private fun postNoteShare(intent: Intent?) {
        val text = intent?.getStringExtra(NoteShareRules.ExtraText) ?: return
        intent.removeExtra(NoteShareRules.ExtraText)
        sharedNote = text
    }

    private fun hideKeyboard() {
        WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.ime())
    }

}
