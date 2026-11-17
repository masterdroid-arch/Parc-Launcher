package com.brightblu.parc

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.brightblu.parc.core.data.library.BookFiles
import com.brightblu.parc.core.data.library.BookFormat
import com.brightblu.parc.core.data.library.BookScanResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal enum class FindBooksSource { Scan, Picker }

/** T206's Find books screen owns this controller. It performs no work on composition. */
internal class FindBooksAccess(
    val find: (Set<BookFormat>) -> Unit,
    val chooseFiles: (Set<BookFormat>) -> Unit,
    /** The screen must show the supplied sentence and call continue only on an explicit tap. */
    val requestAllFilesAccess: (Set<BookFormat>, explain: (String, continueToSettings: () -> Unit) -> Unit) -> Unit,
) {
    companion object {
        const val Explanation = "Allow file access so Find books can list PDF, EPUB, text and comic files on shared storage."
    }
}

/** Refusal or an unavailable settings activity always falls back to OpenMultipleDocuments. */
@Composable
internal fun rememberFindBooksAccess(
    onResult: (BookScanResult.Files, FindBooksSource) -> Unit,
    onPickerRejected: (Int) -> Unit,
): FindBooksAccess {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val result by rememberUpdatedState(onResult)
    val rejected by rememberUpdatedState(onPickerRejected)
    val files = remember(context) { BookFiles(context) }
    val scanner = remember(files) { files.scanner(BuildConfig.LIBRARY_ALL_FILES_ALLOWED) }
    var selected by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var scanJob by remember { mutableStateOf<Job?>(null) }
    fun types() = selected.mapNotNull { name -> BookFormat.entries.find { it.name == name } }.toSet()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val requested = types()
        if (uris.isNotEmpty()) scope.launch {
            val picked = files.picked(uris, requested)
            result(BookScanResult.Files(picked.files), FindBooksSource.Picker)
            if (picked.rejectedCount > 0) rejected(picked.rejectedCount)
        }
    }
    fun pick(chosen: Set<BookFormat>) {
        if (chosen.isEmpty()) { result(BookScanResult.Files(emptyList()), FindBooksSource.Picker); return }
        selected = ArrayList(chosen.map { it.name })
        picker.launch(BookFormat.mimeTypes(chosen))
    }
    fun find(chosen: Set<BookFormat>) {
        scanJob?.cancel()
        selected = ArrayList(chosen.map { it.name })
        scanJob = scope.launch {
            when (val found = scanner.find(chosen)) {
                is BookScanResult.Files -> result(found, FindBooksSource.Scan)
                BookScanResult.PickerRequired -> pick(chosen)
            }
        }
    }
    val accessSettings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        find(types())
    }
    return FindBooksAccess(::find, { chosen -> scanJob?.cancel(); pick(chosen) }) { chosen, explain ->
        scanJob?.cancel()
        if (chosen.isEmpty() || !BuildConfig.LIBRARY_ALL_FILES_ALLOWED) pick(chosen)
        else explain(FindBooksAccess.Explanation) {
            selected = ArrayList(chosen.map { it.name })
            try {
                accessSettings.launch(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${context.packageName}")))
            } catch (_: ActivityNotFoundException) { pick(chosen) }
            catch (_: SecurityException) { pick(chosen) }
        }
    }
}
