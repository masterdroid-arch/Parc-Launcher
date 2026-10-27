package com.brightblu.parc.core.data.media

import org.junit.Assert.*
import org.junit.Test

class MediaRulesTest {
    private fun s(id: String, state: MediaPlayState = MediaPlayState.Paused, pos: Long = 0, dur: Long = 100_000, at: Long = 0, speed: Float = 1f,
        title: String? = "T", artist: String? = "A", album: String? = null) =
        MediaSessionInfo(id, "pkg.$id", title, artist, album, state, pos, at, speed, dur)

    @Test fun playingSessionsComeFirstAndSystemOrderIsKept() {
        val list = listOf(s("a", MediaPlayState.Stopped), s("b", MediaPlayState.Paused), s("c", MediaPlayState.Playing), s("d", MediaPlayState.Paused), s("e", MediaPlayState.Buffering))
        assertEquals(listOf("c", "e", "b", "d", "a"), MediaRules.order(list).map { it.id })
    }

    @Test fun selectionKeepsPickWhileItExistsElseFirst() {
        val list = listOf(s("a", MediaPlayState.Paused), s("b", MediaPlayState.Playing))
        assertEquals("b", MediaRules.selected(list, null)?.id)
        assertEquals("a", MediaRules.selected(list, "a")?.id)
        assertEquals("b", MediaRules.selected(list, "gone")?.id)
        assertNull(MediaRules.selected(emptyList(), "a"))
    }

    @Test fun playingPositionAdvancesWithSpeedAndClampsToDuration() {
        val p = s("p", MediaPlayState.Playing, pos = 10_000, at = 1_000, speed = 2f)
        assertEquals(14_000, MediaRules.positionMs(p, 3_000))
        assertEquals(100_000, MediaRules.positionMs(p, 1_000_000))
        assertEquals(10_000, MediaRules.positionMs(p, 500))
    }

    @Test fun pausedAndBufferingDoNotAdvance() {
        assertEquals(10_000, MediaRules.positionMs(s("a", MediaPlayState.Paused, pos = 10_000, at = 0), 50_000))
        assertEquals(10_000, MediaRules.positionMs(s("a", MediaPlayState.Buffering, pos = 10_000, at = 0), 50_000))
        assertEquals(0, MediaRules.positionMs(s("a", MediaPlayState.Paused, pos = -5), 0))
    }

    @Test fun unknownDurationHasNoProgressAndNoClamp() {
        val p = s("a", MediaPlayState.Playing, pos = 5_000, dur = 0, at = 0)
        assertNull(MediaRules.progress(p, 1_000))
        assertEquals(6_000, MediaRules.positionMs(p, 1_000))
        assertEquals("--:--", MediaRules.durationText(p))
    }

    @Test fun progressFractionIsClamped() {
        assertEquals(0.25f, MediaRules.progress(s("a", pos = 25_000), 0)!!, 0.0001f)
        assertEquals(1f, MediaRules.progress(s("a", pos = 900_000), 0)!!, 0.0001f)
    }

    @Test fun timeFormatting() {
        assertEquals("0:00", MediaRules.time(0))
        assertEquals("0:59", MediaRules.time(59_999))
        assertEquals("1:05", MediaRules.time(65_000))
        assertEquals("59:59", MediaRules.time(3_599_000))
        assertEquals("1:00:00", MediaRules.time(3_600_000))
        assertEquals("--:--", MediaRules.time(-1))
    }

    @Test fun textsFallBackSafely() {
        assertEquals("Unknown title", MediaRules.titleText(s("a", title = null)))
        assertEquals("Unknown title", MediaRules.titleText(s("a", title = "  ")))
        assertEquals("A · Alb", MediaRules.subtitleText(s("a", album = "Alb")))
        assertEquals("A", MediaRules.subtitleText(s("a")))
        assertEquals("", MediaRules.subtitleText(s("a", artist = null)))
        assertTrue(MediaRules.showsPause(MediaPlayState.Buffering))
        assertFalse(MediaRules.showsPause(MediaPlayState.Paused))
    }

    @Test fun idleUntitledSessionsAreHidden() {
        val list = listOf(s("a", MediaPlayState.Stopped, title = null), s("b", MediaPlayState.Stopped, title = "Kept"), s("c", MediaPlayState.Paused, title = null))
        assertEquals(listOf("b", "c"), MediaRules.visible(list).map { it.id })
    }

    @Test fun emptyStates() {
        assertEquals("Loading…", MediaRules.emptyLine(null))
        assertEquals("Needs notification access", MediaRules.emptyLine(MediaRead.NoAccess))
        assertEquals("Nothing playing", MediaRules.emptyLine(MediaRead.Sessions(emptyList())))
        assertNull(MediaRules.emptyLine(MediaRead.Sessions(listOf(s("a")))))
    }

    @Test fun fakeSourceFlowsThroughRules() {
        val source = object : MediaSource { override fun read() = MediaRead.Sessions(listOf(s("a"), s("b", MediaPlayState.Playing))) }
        val read = source.read() as MediaRead.Sessions
        assertEquals("b", MediaRules.selected(read.sessions, null)?.id)
    }

    // OPUS6 (audit F2): a locked or Focus-blocked app playing music is never shown or opened.
    @Test fun hiddenAppsSessionsAreRemovedBeforeAnythingElse() {
        val read = MediaRead.Sessions(listOf(s("spotify", MediaPlayState.Playing), s("radio")))
        val shown = MediaRules.withoutHidden(read, setOf("pkg.spotify")) as MediaRead.Sessions
        assertEquals(listOf("radio"), shown.sessions.map { it.id })
        assertEquals("radio", MediaRules.selected(MediaRules.order(shown.sessions), "spotify")?.id)
        val all = MediaRules.withoutHidden(read, setOf("pkg.spotify", "pkg.radio"))
        assertEquals("Nothing playing", MediaRules.emptyLine(all))
        assertSame(MediaRead.NoAccess, MediaRules.withoutHidden(MediaRead.NoAccess, setOf("pkg.x")))
        assertSame(read, MediaRules.withoutHidden(read, emptySet()))
    }
}
