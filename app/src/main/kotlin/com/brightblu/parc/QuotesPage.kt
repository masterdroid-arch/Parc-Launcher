package com.brightblu.parc

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.brightblu.parc.feature.settings.ui.*
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal object QuotesPage { const val Id = "quotes" }

@Composable
internal fun rememberQuotesStore(): QuotesStore {
    val context = LocalContext.current
    return remember(context) { QuotesStore.create(context) }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun QuotePageBody(quote: Quote?, skin: Skin, empty: String = "", next: () -> Unit, add: () -> Unit, menu: () -> Unit) {
    val scroll = rememberHubScrollState()
    val nextNow by rememberUpdatedState(next)
    val threshold = with(LocalDensity.current) { Metrics.quoteSwipeDp.dp.toPx() }
    val swipe = remember(scroll, threshold) {
        object : NestedScrollConnection {
            var travel = 0f
            var started = false
            var atEnd = false
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                if (!started) { started = true; atEnd = !scroll.canScrollForward }
                // A long passage scrolls first. Start another upward swipe at its end to turn the page.
                if (atEnd && available.y < 0f) { travel -= available.y; return Offset(0f, available.y) }
                return Offset.Zero
            }
            override suspend fun onPreFling(available: Velocity): Velocity {
                val turn = travel >= threshold
                if (turn) nextNow()
                travel = 0f; started = false
                return if (turn) available else Velocity.Zero
            }
        }
    }
    LaunchedEffect(quote?.id) { scroll.scrollTo(0) }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxWidth()
            .background(Color(skin.colors.paper.toInt()), RoundedCornerShape(skin.shape.surfaceCornerDp.dp))
            .combinedClickable(role = Role.Button, onClickLabel = "Another quote", onClick = next,
                onLongClickLabel = "Quote actions", onLongClick = menu)
            .nestedScroll(swipe).hubPageScroll(scroll)
            .padding(Metrics.quoteInsetDp.dp), verticalArrangement = Arrangement.spacedBy(Metrics.quoteCreditGapDp.dp)) {
            BasicText(if (empty.startsWith("Could not")) empty else quote?.text ?: empty,
                style = TextStyle(color = Color(skin.colors.ink.toInt()), fontFamily = FontFamily.Serif,
                    fontSize = Metrics.quoteTextSp.sp, lineHeight = Metrics.quoteLineSp.sp))
            if (quote != null && quote.credit.isNotBlank()) BasicText(quote.credit,
                style = TextStyle(color = Color(skin.colors.ink.toInt()), fontFamily = FontFamily.Serif,
                    fontSize = skin.typography.caption.sizeSp.sp, lineHeight = Metrics.quoteCreditLineSp.sp))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            NoteAction("+", skin, "Add quote", action = add)
        }
    }
}

@Composable
internal fun QuotesOptionChoices(skin: Skin) {
    val store = rememberQuotesStore()
    val scope = rememberCoroutineScope()
    var library by remember { mutableStateOf(QuoteLibrary()) }
    var error by remember { mutableStateOf(false) }
    LaunchedEffect(store) {
        try { store.library.collect { library = it } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = true }
    }
    fun change(action: suspend () -> Unit) { scope.launch {
        try { action(); error = false }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = true }
    } }
    SettingsCard(skin) {
        SettingRow("Restore starter quotes", skin, SettingKind.Action,
            description = "Show the original bundled passages again. Personal quotes are kept.", descriptionMaxLines = Int.MAX_VALUE) {
            change { store.restoreStarters() }
        }
        library.own.filter { it.id in library.hidden }.forEach { quote ->
            SettingRow("Show hidden quote", skin, SettingKind.Action, description = quote.text, descriptionMaxLines = 2) {
                change { store.show(quote.id) }
            }
        }
        if (error) BasicText("Could not save or read quotes. Try again.", style = settingsText(skin))
    }
}

@Composable
internal fun DemoQuotesPage(skin: Skin) {
    val examples = remember { listOf(
        Quote("demo-a", "Leave a little room in the day for the things you did not plan.", "A sample voice", "Demo notebook"),
        Quote("demo-b", "For a moment, the only task is to notice the light on the table.", "A sample voice", "Demo notebook")) }
    var index by remember { mutableIntStateOf(0) }
    QuotePageBody(examples[index], skin, next = { index = (index + 1) % examples.size }, add = {}, menu = {})
}
