package com.brightblu.parc

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.brightblu.parc.core.data.focus.*
import com.brightblu.parc.feature.settings.ui.*
import com.brightblu.parc.model.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal val LocalFocusRepository = staticCompositionLocalOf<FocusRepository?> { null }

/** Prepared once per scene revision. The renderer's draw loop receives ordinary cached bitmaps. */
internal fun focusIcon(bitmap: Bitmap?, treatment: FocusIconTreatment): Bitmap? {
    if (bitmap == null || treatment == FocusIconTreatment.Normal) return bitmap
    val output = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    if (treatment == FocusIconTreatment.Greyscale) paint.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
    else paint.alpha = (255 * Metrics.searchDimAlpha).toInt()
    Canvas(output).drawBitmap(bitmap, 0f, 0f, paint)
    return output
}

@Composable
internal fun FocusLaunchGate(name: String, skin: Skin, repository: FocusRepository, mode: FocusMode,
    onDismiss: () -> Unit, onOpen: () -> Unit) {
    FocusExitDialog(repository, FocusExit.Pass, skin, onDismiss = onDismiss, onGranted = onOpen)
}

@Composable
internal fun FocusHubStatus(skin: Skin) {
    val repository = LocalFocusRepository.current ?: return
    val state by repository.state.collectAsState()
    val summary by repository.summary.collectAsState()
    val scope = rememberCoroutineScope()
    var exit by remember { mutableStateOf<FocusExit?>(null) }
    exit?.let { FocusExitDialog(repository, it, skin, onDismiss = { exit = null }) }
    state.active?.let { mode ->
        SettingsSection(FocusDisplay.clockLine(state), skin)
        SettingsSection(FocusFrictionRules.status(mode, state.evaluatedAtMillis, java.time.ZoneId.systemDefault()), skin)
        SettingRow("Pause", skin, SettingKind.Action) { exit = FocusExit.Pass }
        SettingRow("End", skin, SettingKind.Action) { exit = FocusExit.End }
    }
    if (state.breakUntilMillis != null) SettingsSection("Emergency pass active · Focus resumes automatically", skin)
    summary?.let { result ->
        SettingsSection(focusSummaryText(result), skin)
        SettingRow("Dismiss focus summary", skin, SettingKind.Action) { repository.consumeSummary() }
    }
}

/** Shared boundary for Focus UI actions: cancellation propagates, failures become page feedback. */
internal fun kotlinx.coroutines.CoroutineScope.focusAction(onFailure: (String) -> Unit, block: suspend () -> Unit) = launch {
    try { block() }
    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
    catch (_: Exception) { onFailure("Could not update Focus. Try again.") }
}
