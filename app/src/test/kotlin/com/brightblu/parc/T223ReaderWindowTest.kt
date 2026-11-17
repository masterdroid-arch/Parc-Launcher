package com.brightblu.parc

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T223 regression guard. The reader used to call configureParcWindow() (which requests
 * Window.getInsetsController()) before setContent(). On Android that ended onCreate with
 * "DecorView.getWindowInsetsController() on a null object reference" for every book. This
 * project has no instrumented tests, so this is the source-order seam test the card allows:
 * it fails if any decor/insets access, or the shared window helper, moves back before the
 * content view is installed.
 */
class T223ReaderWindowTest {
    private val source = File("src/main/kotlin/com/brightblu/parc/reader/ReaderActivity.kt").readText()
    private val onCreate = source
        .substringAfter("override fun onCreate(savedInstanceState: Bundle?)")
        .substringBefore("override fun onNewIntent(")
    private val content = onCreate.indexOf("setContent {")

    @Test fun onCreateInstallsTheContentViewBeforeAnythingThatNeedsTheDecorView() {
        assertTrue("ReaderActivity must call setContent", content >= 0)
        val beforeContent = onCreate.substring(0, content)
        assertFalse("no decor view before setContentView", beforeContent.contains("decorView"))
        assertFalse("no insets controller before setContentView", beforeContent.contains("insetsController"))
        assertFalse("no getInsetsController before setContentView", beforeContent.contains("getInsetsController"))
        assertFalse("no configureParcWindow before setContentView", beforeContent.contains("configureParcWindow"))
        assertTrue("configureParcWindow() must run after setContent",
            onCreate.indexOf("configureParcWindow()") > content)
        assertTrue("initial chrome must be applied after setContent",
            onCreate.indexOf("setReaderChrome(") > content)
    }

    @Test fun theReaderUsesTheCompatControllerOnlyAfterTheDecorViewExistsAndGuardsLaterCalls() {
        assertFalse("must not use the platform Window.insetsController", source.contains("window.insetsController"))
        assertTrue("the reader must get the controller from the installed decor view",
            source.contains("WindowCompat.getInsetsController(window, decor)"))
        val chrome = source
            .substringAfter("internal fun setReaderChrome(")
            .substringBefore("\n    }\n")
        assertTrue("setReaderChrome must refuse to run before the content view exists",
            chrome.contains("contentViewReady"))
        assertTrue("setReaderChrome must check that a decor view is present", chrome.contains("peekDecorView"))
        assertTrue("setReaderChrome must not touch a finishing window", chrome.contains("isFinishing"))
        assertTrue("setReaderChrome must not touch a destroyed window", chrome.contains("isDestroyed"))
    }
}
