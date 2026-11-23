package com.brightblu.parc

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** T390: the Page settings sheet floats over the whole screen, not inside the hub card that raised it. */
class T390Test {
    private fun source(name: String) = File("src/main/kotlin/com/brightblu/parc/$name").readText()

    @Test fun thePageSheetIsHandedToTheScreenLevelHost() {
        val options = source("HubPageOptions.kt")
        val sheet = options.substringAfter("internal fun HubPageOptionsSheet")
        assertTrue(sheet.substringBefore("SettingsSheet(").contains("ScreenSheet {"))
    }

    @Test fun homeProvidesTheHostAndDrawsItInsideTheSharedLocals() {
        val home = source("LauncherHome.kt")
        val provider = home.indexOf("LocalHomePresses provides homePresses")
        val draw = home.indexOf("ScreenSheetHost()")
        assertTrue(draw > provider)
        // Drawn before the next sibling after the provider's box, i.e. still inside the provider block.
        assertTrue(home.indexOf("if (edit.undoAvailable && !edit.enabled) {", draw) > draw)
    }

    @Test fun withoutAHostTheSheetIsDrawnInPlace() {
        val host = source("SheetHost.kt")
        assertTrue(host.contains("if (!SheetHost.attached)"))
        assertTrue(host.contains("content()"))
        assertTrue(host.contains("onDispose { SheetHost.content = null }"))
    }

    @Test fun theSheetConsumesLeftoverTouchesSoHomeBehindStaysStill() {
        val sheet = source("SettingsSheet.kt")
        assertTrue(sheet.contains("awaitPointerEvent().changes.forEach { it.consume() }"))
    }
}
