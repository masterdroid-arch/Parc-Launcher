package com.brightblu.parc

import com.brightblu.parc.core.data.focus.*
import com.brightblu.parc.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FocusV2IntegrationTest {
    private val phone = AppEntry(AppId("test.phone/Main"), "Phone", "test.phone", false, 0)
    private val social = AppEntry(AppId("test.social/Main"), "Social", "test.social", false, 0)
    private val tool = AppEntry(AppId("test.tool/Main"), "Tool", "test.tool", false, 0)
    private val apps = listOf(phone, social, tool)
    private val folder = Folder(FolderId("tools"), "Tools", appIds = listOf(tool.id))

    @Test fun folderProjectionRestoresExactOriginalLayoutWhenFocusEnds() {
        val layout = ParcLayout(folders = listOf(folder))
        val mode = FocusMode("Quiet", folderIds = setOf("tools"))
        assertEquals(setOf("test.tool"), focusBlockedPackages(mode, apps, layout))
        assertEquals(emptySet<String>(), focusBlockedPackages(null, apps, layout))
        assertEquals(listOf(tool.id), layout.folders.single().appIds)
        assertEquals(setOf("test.social"), focusBlockedPackages(mode.copy(appPolicy = FocusAppPolicy.AllowList,
            alwaysAllowed = setOf("test.phone")), apps, layout))
    }

    @Test fun strictRuleChecksExactPhrase() {
        val mode = FocusMode("Quiet", strict = true, confirmationPhrase = "I am done")
        assertFalse(FocusPolicies.canEnd(mode, "i am done"))
        assertFalse(FocusPolicies.canEnd(mode, ""))
        assertTrue(FocusPolicies.canEnd(mode, "I am done"))
    }

    @Test fun presetsKeepInstalledPhoneMessagesAndMapsAsAlwaysAllowed() {
        val systemPhone = phone.copy(packageName = "com.android.dialer")
        val maps = tool.copy(packageName = "com.google.android.apps.maps")
        assertEquals(setOf(systemPhone.packageName, maps.packageName), essentialFocusPackages(listOf(systemPhone, maps, social)))
    }
}
