package com.brightblu.parc.reader

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.brightblu.parc.AppGraph
import com.brightblu.parc.configureParcWindow
import com.brightblu.parc.core.data.library.BookPosition
import com.brightblu.parc.model.Skin

/**
 * T207: the full-screen reader. One activity, no task affinity of its own, so it opens over the
 * launcher and back returns there. It keeps no reader code alive after [onDestroy].
 */
class ReaderActivity : ComponentActivity() {
    private var lastPosition: BookPosition = BookPosition()
    private var activeBookId: String? = null
    private var lastLightPage: Boolean = true

    /**
     * T223: the window insets controller must not be requested before the activity has a decor
     * view. Every chrome call is ignored until [setContent] has installed the content view; the
     * explicit call at the end of [onCreate] then applies the initial state.
     */
    private var contentViewReady: Boolean = false

    /**
     * T255: set by the open reader only while "Volume keys turn pages" is on. Return true when the
     * key was used. With no handler the volume keys keep their normal job.
     */
    internal var volumeKeyHandler: ((forward: Boolean) -> Boolean)? = null

    /** 0 hands brightness back to the phone; anything else is the reader's own, until it closes. */
    internal fun setReaderBrightness(value: Float) {
        val attributes = window.attributes
        attributes.screenBrightness = if (value <= 0f) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            else value.coerceIn(0.05f, 1f)
        window.attributes = attributes
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val handler = volumeKeyHandler
        if (handler != null && (event.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN ||
                event.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP)) {
            if (event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                handler(event.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // T210: the shell supplies the only open/close motion. Keep the platform from sliding the
        // activity in and let the see-through window reveal the shelf around the growing cover.
        // These window-attribute calls do not need a decor view and are safe before the content.
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.setWindowAnimations(0)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(0f)
        val request = if (intent?.action == Intent.ACTION_VIEW) {
            ReaderOpenWith.request(this, intent)
        } else ReaderContract.request(intent)
        if (request == null) {
            finish()
            return
        }
        val restored = if (savedInstanceState?.getString(ReaderContract.ExtraBookId) == request.book.id) {
            BookPosition(
                chapter = savedInstanceState.getInt(ReaderContract.ExtraChapter, request.initialPosition.chapter)
                    .coerceAtLeast(0),
                characterOffset = savedInstanceState.getInt(ReaderContract.ExtraOffset, request.initialPosition.characterOffset)
                    .coerceAtLeast(0),
                page = savedInstanceState.getInt(ReaderContract.ExtraPage, request.initialPosition.page)
                    .coerceAtLeast(0),
                pageOffset = savedInstanceState.getDouble(ReaderContract.ExtraPageOffset, request.initialPosition.pageOffset)
                    .coerceIn(0.0, 1.0),
            )
        } else request.initialPosition
        activeBookId = request.book.id
        lastPosition = restored
        val corrected = request.copy(
            initialPosition = restored,
            book = request.book.copy(position = restored),
        )
        val skin = AppGraph.activeSkin() ?: Skin.Midnight
        setContent {
            ReaderHost(
                request = corrected,
                skin = skin,
                onClose = {
                    finish()
                    overridePendingTransition(0, 0)
                },
                onPosition = { position -> lastPosition = position },
            )
        }
        contentViewReady = true
        // T223: setContent has installed the decor view, so the inset-controller request below is
        // safe. configureParcWindow() used to run before setContent and crashed in onCreate.
        configureParcWindow()
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        setReaderChrome(visible = false, light = lastLightPage)
    }

    /** Open-with can send another file while this reader is the top activity. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val next = if (intent.action == Intent.ACTION_VIEW) null else ReaderContract.request(intent)
        // T257: a notification tap for the book already open just brings the reader back; it must not
        // restart the page and interrupt the voice. A genuinely different book closes the old voice.
        if (next != null && next.book.id == activeBookId) {
            setIntent(intent)
            return
        }
        setIntent(intent)
        ReaderTtsService.stop(this)
        recreate()
    }

    override fun onDestroy() {
        // T257: leaving the reader ends its voice. A recreation or a background stop keeps it alive.
        if (isFinishing) ReaderTtsService.stop(this)
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(ReaderContract.ExtraBookId, activeBookId)
        outState.putInt(ReaderContract.ExtraChapter, lastPosition.chapter)
        outState.putInt(ReaderContract.ExtraOffset, lastPosition.characterOffset)
        outState.putInt(ReaderContract.ExtraPage, lastPosition.page)
        outState.putDouble(ReaderContract.ExtraPageOffset, lastPosition.pageOffset)
        super.onSaveInstanceState(outState)
    }

    /** Bar visible means the system bars are visible too; page colour decides the icon tint. */
    internal fun setReaderChrome(visible: Boolean, light: Boolean) {
        lastLightPage = light
        // T223: composition effects can run before onCreate returns. Never ask Window/PhoneWindow
        // for its controller until setContent has installed a decor view, and never touch a
        // finishing/destroyed window.
        if (!contentViewReady || isFinishing || isDestroyed) return
        val decor = window.peekDecorView() ?: return
        val controller = WindowCompat.getInsetsController(window, decor)
        controller.isAppearanceLightStatusBars = light
        controller.isAppearanceLightNavigationBars = light
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (visible) {
            controller.show(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}
