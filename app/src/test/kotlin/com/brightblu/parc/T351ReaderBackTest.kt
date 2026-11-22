package com.brightblu.parc

import com.brightblu.parc.core.data.library.BookPosition
import com.brightblu.parc.core.data.library.ReaderPositionSession
import com.brightblu.parc.reader.ReaderBack
import com.brightblu.parc.reader.ReaderChrome
import com.brightblu.parc.reader.ReaderJump
import org.junit.Assert.assertEquals
import org.junit.Test

class T351ReaderBackTest {
    @Test fun arrowRemainsAvailableForExitAfterDismissingThePanel() {
        val expanded = ReaderChrome.Hidden.toggled()
        fun back(chrome: ReaderChrome, ime: Boolean = false, sheet: Boolean = false) =
            ReaderJump.backAction(ime, sheet, false, chrome == ReaderChrome.Panel)

        assertEquals(ReaderBack.HideKeyboard, back(expanded, ime = true, sheet = true))
        assertEquals(ReaderBack.CloseSheet, back(expanded, sheet = true))
        assertEquals(ReaderBack.HidePanel, back(expanded))
        assertEquals(ReaderBack.ExitReader, back(ReaderChrome.Toolbar))
        assertEquals(ReaderChrome.Hidden, ReaderChrome.Toolbar.toggled())
        assertEquals(ReaderBack.ExitReader, back(ReaderChrome.Hidden))
    }

    @Test fun exitFlushesTheLatestComicPageAndAnImmediateExitKeepsIt() {
        val restoredPage = BookPosition(page = 1)
        var stored = restoredPage
        fun session() = ReaderPositionSession { checkpoint, _ -> stored = checkpoint }
        val reader = session()
        val latest = BookPosition(page = 2)
        reader.update(latest, 1.0)
        reader.flush()
        assertEquals(latest, stored)
        session().flush() // Back before the reopened comic draws a page.
        assertEquals(latest, stored)
    }
}
