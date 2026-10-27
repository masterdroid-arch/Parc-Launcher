package com.brightblu.parc.feature.search.debug

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.brightblu.parc.feature.search.LabelSearchEngine
import com.brightblu.parc.feature.search.SearchDoc
import com.brightblu.parc.feature.search.SearchSheet
import com.brightblu.parc.feature.search.SearchSheetState
import com.brightblu.parc.model.AppEntry
import com.brightblu.parc.model.AppId
import com.brightblu.parc.model.AppIndex
import com.brightblu.parc.model.AppShortcut
import com.brightblu.parc.model.FolderId
import com.brightblu.parc.model.Skin
import com.brightblu.parc.model.UsageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

/**
 * Debug-only host for the T67 search sheet, mirroring the review activities in `:feature:settings`.
 * The production host is `:app`; this exists so the calculator row, contact row, shortcut sub-rows,
 * recent searches and web row can be screenshotted on the emulator without a release manifest change.
 *
 * ```
 * adb shell am start -n com.brightblu.parc/com.brightblu.parc.feature.search.debug.SearchReviewActivity \
 *   --es query "2^8"
 * ```
 * Without `--es query` the sheet opens with a query that matches one app and shows its shortcuts.
 */
class SearchReviewActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val entries = DemoIndex.entries
        val index = DemoIndex(entries)
        val engine = LabelSearchEngine().apply {
            update(entries.map { SearchDoc(it.id, it.label, it.packageName) })
        }
        val state = SearchSheetState(engine, index, DemoUsage(), scope)
        state.open()
        state.onQueryChange(intent.getStringExtra(QueryExtra) ?: DefaultQuery)

        val skin = when (intent.getStringExtra("skin")) {
            "phosphor" -> com.brightblu.parc.model.Phosphor.Skin
            "field-atlas-light" -> com.brightblu.parc.model.FieldAtlas.Light
            "field-atlas-dark" -> com.brightblu.parc.model.FieldAtlas.Dark
            else -> Skin.Midnight
        }
        setContent {
            Box(Modifier.fillMaxSize().background(Color(skin.colors.bg.toInt()))) {
                SearchSheet(
                    state = state,
                    skin = skin,
                    onLaunch = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    private class DemoIndex(entries: List<AppEntry>) : AppIndex {
        private val state = MutableStateFlow(entries)
        override val apps: StateFlow<List<AppEntry>> = state.asStateFlow()
        override fun icon(id: AppId, sizePx: Int): Bitmap? = null
        override fun launch(id: AppId) = Unit
        override fun shortcuts(id: AppId): List<AppShortcut> =
            if (id == entries.first().id) {
                listOf(
                    AppShortcut("directions", "Directions", id),
                    AppShortcut("home", "Home", id),
                )
            } else {
                emptyList()
            }

        companion object {
            val entries: List<AppEntry> = listOf(
                entry("Maps", "com.example.maps"),
                entry("Music", "com.example.music"),
                entry("Notes", "com.example.notes"),
                entry("Camera", "com.example.camera"),
                entry("Files", "com.example.files"),
            )

            private fun entry(label: String, packageName: String) = AppEntry(
                AppId("$packageName/$packageName.Main@0"),
                label,
                packageName,
                isSystem = false,
                installTime = 0L,
            )
        }
    }

    private class DemoUsage : UsageRepository {
        private val launches = MutableStateFlow<List<AppId>>(emptyList())
        override fun recordLaunch(id: AppId) {
            launches.value = listOf(id) + (launches.value - id)
        }

        override fun recents(limit: Int): Flow<List<AppId>> = launches.map { it.take(limit) }
        override fun rank(folderId: FolderId): List<AppId> = emptyList()
    }

    private companion object {
        const val QueryExtra = "query"
        const val DefaultQuery = "ma"
    }
}
