package com.brightblu.parc.feature.settings.skins

import com.brightblu.parc.feature.settings.ui.*
import androidx.compose.foundation.layout.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.runtime.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.brightblu.parc.core.data.search.SearchPrefs
import com.brightblu.parc.core.data.search.SearchPrefsSnapshot
import com.brightblu.parc.core.data.search.WebSearchEngine
import com.brightblu.parc.model.Skin
import kotlinx.coroutines.launch

/**
 * Search extras settings (T67): recent searches on/off, the clear button and the web engine.
 *
 * Lives inside [ParcSettingsScreen] so it is reachable from the existing Settings surface without a
 * new `:app` route. It reads and writes the DataStore-backed [SearchPrefs] directly, mirroring the
 * main-apps section; every value has the documented default (recents on, Google).
 */
@Composable
fun SearchSettingsSection(skin: Skin) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember(context) { SearchPrefs.create(context) }
    val snapshot by prefs.snapshot.collectAsState(initial = SearchPrefsSnapshot())

    Section("Search", skin)
    SettingsCard(skin) {
        SettingsChoice("Web search engine", snapshot.webEngine.label, WebSearchEngine.entries.map { it.label }, skin) {
            scope.launch { prefs.setWebEngine(WebSearchEngine.entries[it]) }
        }
        SettingRow("Recent searches", skin, SettingKind.Toggle, description = "Remember searches on this device",
            checked = snapshot.recentsEnabled) { scope.launch { prefs.setRecentsEnabled(!snapshot.recentsEnabled) } }
        SettingRow("Clear recent searches", skin, SettingKind.Action, destructive = true, icon = "×",
            enabled = snapshot.recentSearches.isNotEmpty()) { scope.launch { prefs.clearRecentSearches() } }
    }
    val filesEnabled = remember(context) { com.brightblu.parc.core.data.DistributionFeatures.files(context) }
    val contactsEnabled = remember(context) { com.brightblu.parc.core.data.DistributionFeatures.contacts(context) }
    Section("Search more of the phone", skin)
    var filesGranted by remember { mutableStateOf(filesAllowed(context)) }
    var calendarGranted by remember { mutableStateOf(context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED) }
    val moreRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        filesGranted = filesAllowed(context)
        calendarGranted = context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
        scope.launch { prefs.markExtrasPromptDone() }
    }
    SettingsCard(skin) {
        SettingRow("Phone settings", skin, SettingKind.Toggle, description = "Find pages such as Wi-Fi or Battery",
            checked = snapshot.phoneSettingsEnabled) { scope.launch { prefs.setPhoneSettingsEnabled(!snapshot.phoneSettingsEnabled) } }
        if (filesEnabled) SettingRow("Files", skin, SettingKind.Toggle,
            description = if (filesGranted) "Photos, videos and music by name" else "Needs permission. Off until you allow it",
            checked = snapshot.filesEnabled) { scope.launch { prefs.setFilesEnabled(!snapshot.filesEnabled) } }
        SettingRow("Calendar events", skin, SettingKind.Toggle,
            description = if (calendarGranted) "Events in the next 60 days" else "Needs permission. Off until you allow it",
            checked = snapshot.eventsEnabled) { scope.launch { prefs.setEventsEnabled(!snapshot.eventsEnabled) } }
        if (filesEnabled && !filesGranted) {
            SettingRow("Allow files access", skin, SettingKind.Action, description = "Photos, videos and music. Names only", icon = "↗") {
                moreRequest.launch(arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO))
            }
        }
        if (!calendarGranted) {
            SettingRow("Allow calendar access", skin, SettingKind.Action, description = "Read only. Shared with the Agenda page", icon = "↗") {
                moreRequest.launch(arrayOf(Manifest.permission.READ_CALENDAR))
            }
        }
    }
    if (contactsEnabled) {
        Section("Contacts", skin)
        var granted by remember { mutableStateOf(context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) }
        val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
        SettingsCard(skin) {
            SettingRow("Contacts access", skin, SettingKind.Action,
                description = if (granted) "Allowed. Manage access in Android app permissions" else "Allow contacts to appear in search", icon = "↗") {
                if (granted) context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:" + context.packageName)))
                else request.launch(Manifest.permission.READ_CONTACTS)
            }
        }
    }
}

/** T102: true when at least one of the three media permissions is granted. */
private fun filesAllowed(context: android.content.Context): Boolean = listOf(
    Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO,
).any { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
