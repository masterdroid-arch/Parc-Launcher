package com.brightblu.parc.feature.settings.skins

import com.brightblu.parc.feature.settings.ui.SettingsAccessHint
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
 * T375: the one Search settings page. Sources (phone settings, files, calendar, contacts) come first, each with the
 * access it needs directly under its own switch, then the web engine and recent searches. The values are the same
 * [SearchPrefs] the registry rows write, so the Settings search, the page and Home search all read one setting.
 * [anchor] wraps a source row by its registry id so a search result can scroll to it.
 */
@Composable
fun SearchSettingsSection(skin: Skin, anchor: @Composable (String, @Composable () -> Unit) -> Unit = { _, content -> content() }) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember(context) { SearchPrefs.create(context) }
    val snapshot by prefs.snapshot.collectAsState(initial = SearchPrefsSnapshot())
    val filesEnabled = remember(context) { com.brightblu.parc.core.data.DistributionFeatures.files(context) }
    val contactsEnabled = remember(context) { com.brightblu.parc.core.data.DistributionFeatures.contacts(context) }
    var filesGranted by remember { mutableStateOf(filesAllowed(context)) }
    var calendarGranted by remember { mutableStateOf(context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED) }
    var contactsGranted by remember { mutableStateOf(context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) }
    // Coming back from Android's permission screen re-reads the grants, so a hint disappears once its access is given.
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                filesGranted = filesAllowed(context)
                calendarGranted = context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
                contactsGranted = context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val moreRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        filesGranted = filesAllowed(context)
        calendarGranted = context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
        scope.launch { prefs.markExtrasPromptDone() }
    }
    val contactsRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { contactsGranted = it }

    SettingsGroup("Sources", skin) {
        anchor("SearchPhoneSettings") {
            SettingRow("Phone settings", skin, SettingKind.Toggle, description = "Find pages such as Wi-Fi or Battery",
                checked = snapshot.phoneSettingsEnabled) { scope.launch { prefs.setPhoneSettingsEnabled(!snapshot.phoneSettingsEnabled) } }
        }
        if (filesEnabled) {
            anchor("SearchFiles") {
                Column {
                    SettingRow("Files", skin, SettingKind.Toggle,
                        description = if (filesGranted) "Photos, videos and music by name" else "Needs permission. Off until you allow it",
                        checked = snapshot.filesEnabled) { scope.launch { prefs.setFilesEnabled(!snapshot.filesEnabled) } }
                    if (!filesGranted) SettingsAccessHint("Allow files access", "Photos, videos and music. Names only", skin) {
                        moreRequest.launch(arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO))
                    }
                }
            }
        }
        anchor("SearchEvents") {
            Column {
                SettingRow("Calendar", skin, SettingKind.Toggle,
                    description = if (calendarGranted) "Events in the next 60 days" else "Needs permission. Off until you allow it",
                    checked = snapshot.eventsEnabled) { scope.launch { prefs.setEventsEnabled(!snapshot.eventsEnabled) } }
                if (!calendarGranted) SettingsAccessHint("Allow calendar access", "Read only. Shared with the Agenda page", skin) {
                    moreRequest.launch(arrayOf(Manifest.permission.READ_CALENDAR))
                }
            }
        }
        if (contactsEnabled) SettingRow("Contacts", skin, SettingKind.Action, divider = false,
            description = if (contactsGranted) "Allowed. Manage access in Android app permissions" else "Allow contacts to appear in search",
            icon = "↗") {
            if (contactsGranted) context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:" + context.packageName)))
            else contactsRequest.launch(Manifest.permission.READ_CONTACTS)
        }
    }
    SettingsGroup("Web & history", skin) {
        SettingsChoice("Web search engine", snapshot.webEngine.label, WebSearchEngine.entries.map { it.label }, skin) {
            scope.launch { prefs.setWebEngine(WebSearchEngine.entries[it]) }
        }
        SettingRow("Recent searches", skin, SettingKind.Toggle, description = "Remember searches on this device",
            checked = snapshot.recentsEnabled) { scope.launch { prefs.setRecentsEnabled(!snapshot.recentsEnabled) } }
        SettingRow("Clear recent searches", skin, SettingKind.Action, destructive = true, icon = "×", divider = false,
            enabled = snapshot.recentSearches.isNotEmpty()) { scope.launch { prefs.clearRecentSearches() } }
    }
}

/** T102: true when at least one of the three media permissions is granted. */
private fun filesAllowed(context: android.content.Context): Boolean = listOf(
    Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO,
).any { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
