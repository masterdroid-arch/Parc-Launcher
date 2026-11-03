package com.brightblu.parc.feature.search

import com.brightblu.parc.model.textFamily
import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brightblu.parc.core.data.search.SearchPrefs
import com.brightblu.parc.core.data.search.SearchPrefsSnapshot
import com.brightblu.parc.feature.search.calculator.Calculation
import com.brightblu.parc.feature.search.contacts.AndroidContactsSource
import com.brightblu.parc.feature.search.contacts.ContactHit
import com.brightblu.parc.feature.search.contacts.ContactRanker
import com.brightblu.parc.feature.search.contacts.ContactSource
import com.brightblu.parc.feature.search.extras.AndroidEventSource
import com.brightblu.parc.feature.search.extras.AndroidFilesSource
import com.brightblu.parc.feature.search.extras.AndroidSettingsAvailability
import com.brightblu.parc.feature.search.extras.EventHit
import com.brightblu.parc.feature.search.extras.ExtraIntents
import com.brightblu.parc.feature.search.extras.ExtraResults
import com.brightblu.parc.feature.search.extras.ExtraSearch
import com.brightblu.parc.feature.search.extras.ExtraText
import com.brightblu.parc.feature.search.extras.ExtraToggles
import com.brightblu.parc.feature.search.extras.FileHit
import com.brightblu.parc.feature.search.extras.PhoneSettingHit
import com.brightblu.parc.model.AppId
import com.brightblu.parc.model.MatchRange
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.parcMinTouch
import com.brightblu.parc.model.parcPress
import com.brightblu.parc.model.Skin
import com.brightblu.parc.ring.module.frostedBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Result rows visible at once; more scroll. Not a token: a count, not a size (DESIGN §2.3). */
private const val SheetVisibleRows = 6

/** Phone rows read from the contacts provider before the pure ranker keeps the best four. */
private const val ContactProviderRows = 200

/** Lets a fast typist settle before a provider query (T102: 150 ms for every source); a duration, not a layout size. */
private const val ContactDebounceMs = 150L

/** How long the calculator row says "Copied". A duration, not a layout size. */
private const val CopiedNoticeMs = 1_500L

/**
 * Bottom-anchored search sheet: field at the bottom, results above it, one "All apps" link
 * (DESIGN §4, S6–S7). The host draws it above the ring field, owns the swipe-down detector, the
 * keyboard and Back, and shows [AbcListScreen] while [SearchUiState.abcListOpen] is set.
 *
 * T67 extras, in reading order from the top: a calculator answer (tap copies it), app results with
 * the top hit's shortcuts as indented sub-rows, matching contacts, a "Search contacts" permission
 * row while `READ_CONTACTS` is not granted, and a final "Search the web for …" row. When the query
 * is blank the sheet shows the last eight searches (tap re-runs one, Clear removes them) above the
 * usage recents.
 *
 * [onLaunchShortcut] defaults to launching the app itself, so a host that has not wired shortcuts
 * yet still gets a working row; `:app` should pass `ShortcutHost::launchShortcut`. Both
 * [contactSource] and [searchPrefs] default to Android implementations, so the sheet needs no new
 * host parameter to work.
 *
 * All colours and sizes come from [Skin] and [Metrics]. Contact names and numbers are rendered,
 * never logged.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SearchSheet(
    state: SearchSheetState,
    skin: Skin,
    onLaunch: (AppId) -> Unit,
    modifier: Modifier = Modifier,
    onLaunchShortcut: (AppId, String) -> Unit = { id, _ -> onLaunch(id) },
    contactSource: ContactSource? = null,
    searchPrefs: SearchPrefs? = null,
    /** T102: phone settings, files and calendar events. Defaults to the Android sources. */
    extraSearch: ExtraSearch? = null,
    /** T98: labels of apps hidden by a locked folder; remembered searches that would reveal one are not listed. */
    hiddenLabels: Collection<String> = emptyList(),
    /** OPUS8 (T133): Parc's own settings pages; empty offers none. */
    parcSettings: List<com.brightblu.parc.feature.search.extras.ParcSettingEntry> = emptyList(),
    onOpenParcSetting: (String) -> Unit = {},
    /** T157: 0 (below the screen) to 1 (shown), on `Springs.sheet`; read only inside layer and draw lambdas. */
    progress: () -> Float = { 1f },
    /** T157: the state has closed but the sheet is still springing away; it keeps the last open content and takes no touches. */
    closing: Boolean = false,
) {
    val live = state.state.collectAsState().value
    val lastOpen = remember { arrayOfNulls<SearchUiState>(1) }
    if (live.isOpen) lastOpen[0] = live
    val ui = if (live.isOpen) live else lastOpen[0] ?: return
    if (!live.isOpen && !closing) return
    val focus = remember { FocusRequester() }
    LaunchedEffect(live.isOpen) { if (live.isOpen) focus.requestFocus() }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember(context, searchPrefs) { searchPrefs ?: SearchPrefs.create(context) }
    val loadedSnapshot by prefs.snapshot.collectAsState<SearchPrefsSnapshot, SearchPrefsSnapshot?>(initial = null)
    val prefsSnapshot = loadedSnapshot ?: SearchPrefsSnapshot()
    val contactsEnabled = remember(context) { com.brightblu.parc.core.data.DistributionFeatures.contacts(context) }
    val filesEnabled = remember(context) { com.brightblu.parc.core.data.DistributionFeatures.files(context) }
    val contactsSource = remember(context, contactSource) { contactSource ?: AndroidContactsSource(context) }
    var contactsGranted by remember(contactsSource) { mutableStateOf(contactsSource.hasPermission()) }
    val permissionRequest = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { contactsGranted = contactsSource.hasPermission() }

    val contacts by produceState(
        initialValue = emptyList<ContactHit>(),
        ui.query,
        contactsGranted,
        contactsSource,
    ) {
        value = emptyList()
        if (contactsEnabled && contactsGranted && ui.query.isNotBlank()) {
            delay(ContactDebounceMs)
            value = withContext(Dispatchers.IO) {
                ContactRanker.rank(ui.query, contactsSource.search(ui.query, ContactProviderRows))
            }
        }
    }
    val extras = remember(context, extraSearch) {
        extraSearch ?: ExtraSearch(AndroidSettingsAvailability(context), AndroidFilesSource(context), AndroidEventSource(context))
    }
    var filesGranted by remember(extras) { mutableStateOf(extras.filesGranted()) }
    var calendarGranted by remember(extras) { mutableStateOf(extras.calendarGranted()) }
    val toggles = ExtraToggles(prefsSnapshot.phoneSettingsEnabled, filesEnabled && prefsSnapshot.filesEnabled, prefsSnapshot.eventsEnabled)
    val extraResults by produceState(
        initialValue = ExtraResults.Empty,
        ui.query,
        toggles,
        filesGranted,
        calendarGranted,
        extras,
    ) {
        value = ExtraResults.Empty
        if (ui.query.isNotBlank()) {
            delay(ContactDebounceMs)
            value = withContext(Dispatchers.IO) { extras.search(ui.query, toggles) }
        }
    }
    // The one-time row: shown only while an enabled source still lacks its permission, until used once.
    val wantFiles = toggles.files && !filesGranted
    val wantCalendar = toggles.events && !calendarGranted
    val showExtrasPrompt = loadedSnapshot != null && !prefsSnapshot.extrasPromptDone && (wantFiles || wantCalendar)
    val extrasRequest = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        filesGranted = extras.filesGranted()
        calendarGranted = extras.calendarGranted()
        scope.launch { prefs.markExtrasPromptDone() }
    }
    var copied by remember(ui.query) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(CopiedNoticeMs)
            copied = false
        }
    }

    val record: () -> Unit = {
        val query = ui.query
        if (query.isNotBlank()) scope.launch { prefs.recordSearch(query) }
    }
    val earlierQueries = if (prefsSnapshot.recentsEnabled) {
        prefsSnapshot.recentSearches.filterNot {
            com.brightblu.parc.core.data.lock.LockedApps.queryRevealsLockedApp(it, hiddenLabels)
        }
    } else emptyList()
    val parcHits = remember(ui.query, parcSettings) {
        com.brightblu.parc.feature.search.extras.ParcSettingsRanking.rank(ui.query, parcSettings)
    }
    val hasResults = ui.rows.isNotEmpty() || contacts.isNotEmpty() || ui.calculation != null || !extraResults.isEmpty ||
        parcHits.isNotEmpty()

    val statusBarDp = WindowInsets.statusBars.getTop(LocalDensity.current) / LocalDensity.current.density
    val scrim = Color((if (skin.style.header == com.brightblu.parc.model.HeaderStyle.ruled) skin.colors.bg else skin.colors.scrim).toInt())
    Box(modifier.fillMaxSize()
        .drawBehind { drawRect(scrim, alpha = progress().coerceIn(0f, 1f)) }
        .searchSkinDecoration(skin)
        .then(if (live.isOpen) Modifier else Modifier.pointerInput(Unit) {
            awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
        })) {
        // OPUS1: the sheet never grows under the status bar or the pull-down search bar. The
        // result list is the only part that shrinks (weight, not a fixed cap), so the field, the
        // links and the first result always stay on screen, with or without the keyboard and at
        // large text sizes; anything that does not fit scrolls inside the list.
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(top = SearchSheetLayout.topClearanceDp(statusBarDp).dp)
                .fillMaxWidth()
                .graphicsLayer { translationY = (1f - progress().coerceIn(0f, 1f)) * size.height }
                .surface(skin),
        ) {
            // OPUS3: links and permission prompts sit above the results, so the best result is the
            // row right above the field, under the thumb.
            if (showExtrasPrompt) {
                LinkRow(extrasPromptLabel(wantFiles, wantCalendar), skin) {
                    val wanted = ArrayList<String>(4)
                    if (wantFiles) wanted += com.brightblu.parc.feature.search.extras.FilePermissions.all
                    if (wantCalendar) wanted += Manifest.permission.READ_CALENDAR
                    extrasRequest.launch(wanted.toTypedArray())
                }
            }
            if (contactsEnabled && !contactsGranted) {
                LinkRow("Allow contacts: find names and open contact cards on this phone", skin) {
                    permissionRequest.launch(Manifest.permission.READ_CONTACTS)
                }
            }
            ui.webQuery?.let { query ->
                WebRow(query, skin) {
                    record()
                    prefsSnapshot.webEngine.searchUrl(query)?.let { url -> openWeb(context, url) }
                }
            }
            LinkRow("All apps", skin, state::openAbcList)
            val items = remember(ui.query, earlierQueries, ui.calculation, ui.rows, contacts, extraResults, parcHits) {
                SearchSheetItems.build(ui.query, earlierQueries, ui.calculation, ui.rows, contacts, extraResults, parcHits)
            }
            val listState = rememberLazyListState()
            // OPUS3: back to the first (best) item whenever the query or the best item changes. The
            // request is applied in the same frame's layout, so stale rows never flash.
            val lastShown = remember { arrayOfNulls<String>(2) }
            SideEffect {
                val firstKey = items.firstOrNull()?.key
                if (SearchSheetItems.resetScroll(lastShown[0], lastShown[1], ui.query, firstKey)) {
                    lastShown[0] = ui.query
                    lastShown[1] = firstKey
                    listState.requestScrollToItem(0)
                }
            }
            val hidden by remember(listState) {
                derivedStateOf {
                    val info = listState.layoutInfo
                    SearchSheetItems.hiddenAbove(info.totalItemsCount,
                        info.visibleItemsInfo.lastOrNull { it.offset + it.size <= info.viewportEndOffset }?.index ?: -1)
                }
            }
            if (hidden > 0) MoreAboveRow(hidden, skin) {
                scope.launch { listState.animateScrollToItem((listState.firstVisibleItemIndex + 1).coerceAtMost(items.lastIndex)) }
            }
            LazyColumn(
                Modifier
                    .weight(1f, fill = false)
                    .fillMaxWidth()
                    .heightIn(max = (Metrics.searchRowHeightDp * SheetVisibleRows).dp),
                state = listState,
                reverseLayout = true,
            ) {
                items(items, key = { it.key }) { item ->
                    when (item) {
                        SheetItem.RecentHeader -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) { SheetCaption("Recent searches", skin) }
                            Box(Modifier.weight(1f)) {
                                LinkRow("Clear", skin) { scope.launch { prefs.clearRecentSearches() } }
                            }
                        }
                        is SheetItem.RecentQuery -> LinkRow(item.text, skin) { state.onQueryChange(item.text) }
                        is SheetItem.Calculator -> CalculatorRow(item.calculation, skin, copied) {
                            copyToClipboard(context, item.calculation.display)
                            copied = true
                        }
                        is SheetItem.App -> ResultRow(item.row, skin) {
                            record()
                            onLaunch(item.row.appId)
                        }
                        is SheetItem.Shortcut -> ShortcutRow(item.shortcut.label, skin) {
                            record()
                            onLaunchShortcut(item.appId, item.shortcut.id)
                        }
                        is SheetItem.Contact -> ContactRow(
                            hit = item.hit,
                            skin = skin,
                            onOpen = {
                                record()
                                openContactCard(context, item.hit.contact.id)
                            },
                            onCall = { number ->
                                record()
                                dial(context, number)
                            },
                            onMessage = { number ->
                                record()
                                message(context, number)
                            },
                        )
                        is SheetItem.Header -> SheetCaption(item.title, skin)
                        is SheetItem.Setting -> RowBox({
                            record()
                            startSafely(context, ExtraIntents.settings(item.hit.action))
                        }) { BasicText(item.hit.title, style = searchStyle(skin), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        is SheetItem.ParcSetting -> RowBox({
                            record()
                            onOpenParcSetting(item.hit.key)
                        }) { BasicText(item.hit.title, style = searchStyle(skin), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        is SheetItem.File -> TwoLineRow(item.hit.file.name, ExtraText.fileKind(item.hit.file.kind), skin) {
                            record()
                            startSafely(context, ExtraIntents.file(item.hit.file))
                        }
                        is SheetItem.Event -> {
                            val whenText = remember(item.hit) {
                                ExtraText.eventWhen(item.hit.event, System.currentTimeMillis(), java.time.ZoneId.systemDefault(),
                                    android.text.format.DateFormat.is24HourFormat(context))
                            }
                            TwoLineRow(item.hit.event.title, whenText, skin) {
                                record()
                                ExtraIntents.event(item.hit.event)?.let { startSafely(context, it) }
                            }
                        }
                    }
                }
            }
            if (ui.query.isNotBlank() && !hasResults) {
                SheetMessage("No matches", skin)
            }
            SearchField(
                query = ui.query,
                skin = skin,
                focus = focus,
                onValueChange = state::onQueryChange,
                onSubmit = { state.topHit()?.let(onLaunch) },
            )
        }
    }
}

/**
 * The persistent handle that opens the sheet (DESIGN §2.3): a 36 × 4 dp pill inside a 48 × 32 dp
 * touch target. The host places it with [Metrics.handleLeftOffsetDp] and
 * [Metrics.handleBottomOffsetDp].
 */
@Composable
fun SearchHandle(skin: Skin, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(Metrics.handleTouchWidthDp.dp, Metrics.handleTouchHeightDp.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(Metrics.handleWidthDp.dp, Metrics.handleHeightDp.dp)
                .background(
                    Color(skin.colors.lineActive.toInt()),
                    RoundedCornerShape(Metrics.handleHeightDp.dp / 2),
                ),
        )
    }
}

@Composable
private fun ResultRow(row: SearchRow, skin: Skin, onClick: () -> Unit) {
    val text = remember(row, skin) { highlighted(row.label, row.matchRanges, skin) }
    RowBox(onClick) {
        BasicText(text, style = searchStyle(skin))
    }
}

/** Indented under the matched app: one of its static or dynamic shortcuts. */
@Composable
private fun ShortcutRow(label: String, skin: Skin, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = Metrics.searchRowHeightDp.dp)
            .padding(start = Metrics.nestedRowInsetDp.dp, end = Metrics.screenGutterDp.dp, top = Metrics.rowGapDp.dp, bottom = Metrics.rowGapDp.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicText(
            label,
            style = TextStyle(
                fontFamily = skin.textFamily,
                color = Color(skin.colors.textSecondary.toInt()),
                fontSize = skin.typography.menu.sizeSp.sp,
                fontWeight = FontWeight(skin.typography.menu.weight),
                letterSpacing = skin.typography.menu.trackingSp.sp,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The calculator answer; tap copies [calculation.display] and the caption says so for a moment. */
@Composable
private fun CalculatorRow(
    calculation: Calculation,
    skin: Skin,
    copied: Boolean,
    onCopy: () -> Unit,
) {
    RowBox(onCopy) {
        Column(verticalArrangement = Arrangement.Center) {
            BasicText(
                "= ${calculation.display}",
                style = searchStyle(skin).copy(color = Color(skin.colors.accent.toInt())),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            BasicText(
                if (copied) "Copied" else calculation.expression,
                style = TextStyle(
                fontFamily = skin.textFamily,
                    color = Color(skin.colors.textSecondary.toInt()),
                    fontSize = skin.typography.caption.sizeSp.sp,
                    fontWeight = FontWeight(skin.typography.caption.weight),
                    letterSpacing = skin.typography.caption.trackingSp.sp,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * One matched contact. Tap opens the system contact card. Long-press reveals Call and Message
 * inline, because a popup would need a second anchored surface for two rows.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContactRow(
    hit: ContactHit,
    skin: Skin,
    onOpen: () -> Unit,
    onCall: (String) -> Unit,
    onMessage: (String) -> Unit,
) {
    var expanded by remember(hit.contact.id, hit.matchedNumber) { mutableStateOf(false) }
    val number = (hit.matchedNumber ?: hit.contact.numbers.firstOrNull())?.takeIf { it.isNotBlank() }
    Column(
        Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onOpen,
                onLongClick = { if (number != null) expanded = !expanded },
            )
            .heightIn(min = Metrics.searchRowHeightDp.dp)
            .padding(horizontal = Metrics.textGutterDp.dp, vertical = Metrics.rowGapDp.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        BasicText(
            highlighted(hit.contact.name, hit.matchRanges, skin),
            style = searchStyle(skin),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (number != null) {
            BasicText(
                number,
                style = TextStyle(
                fontFamily = skin.textFamily,
                    color = Color(skin.colors.textSecondary.toInt()),
                    fontSize = skin.typography.caption.sizeSp.sp,
                    fontWeight = FontWeight(skin.typography.caption.weight),
                    letterSpacing = skin.typography.caption.trackingSp.sp,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (expanded && number != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(Metrics.cardInsetDp.dp)) {
                ContactAction("Call", skin) { onCall(number) }
                ContactAction("Message", skin) { onMessage(number) }
            }
        }
    }
}

@Composable
private fun ContactAction(label: String, skin: Skin, onClick: () -> Unit) {
    Box(
        Modifier
            .height(Metrics.menuRowHeightDp.dp)
            .clickable(onClick = onClick)
            .parcMinTouch(),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicText(
            label,
            style = TextStyle(
                fontFamily = skin.textFamily,
                color = Color(skin.colors.accent.toInt()),
                fontSize = skin.typography.caption.sizeSp.sp,
                fontWeight = FontWeight(skin.typography.caption.weight),
                letterSpacing = skin.typography.caption.trackingSp.sp,
            ),
        )
    }
}

/** Last row: hands the query to the chosen engine with ACTION_VIEW (no INTERNET permission). */
@Composable
private fun WebRow(query: String, skin: Skin, onClick: () -> Unit) {
    RowBox(onClick) {
        BasicText(
            "Search the web for \"$query\"",
            style = TextStyle(
                fontFamily = skin.textFamily,
                color = Color(skin.colors.textSecondary.toInt()),
                fontSize = skin.typography.caption.sizeSp.sp,
                fontWeight = FontWeight(skin.typography.caption.weight),
                letterSpacing = skin.typography.caption.trackingSp.sp,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Title over one caption line; used by file and calendar results. */
@Composable
private fun TwoLineRow(title: String, caption: String, skin: Skin, onClick: () -> Unit) {
    RowBox(onClick) {
        Column(verticalArrangement = Arrangement.Center) {
            BasicText(title, style = searchStyle(skin), maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicText(caption, style = captionStyle(skin), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun extrasPromptLabel(files: Boolean, calendar: Boolean): String = when {
    files && calendar -> "Allow file names and calendar events for on-device search"
    files -> "Allow photo, video and music names for on-device search"
    else -> "Allow calendar events for on-device search"
}

@Composable
private fun LinkRow(label: String, skin: Skin, onClick: () -> Unit) {
    RowBox(onClick) { BasicText(label, style = captionStyle(skin), maxLines = 2, overflow = TextOverflow.Ellipsis) }
}

@Composable
private fun SheetCaption(text: String, skin: Skin) {
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Metrics.menuRowHeightDp.dp)
            .padding(horizontal = Metrics.textGutterDp.dp, vertical = Metrics.cardInsetDp.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicText(
            text,
            style = captionStyle(skin).copy(color = Color(skin.colors.accent.toInt())),
        )
    }
}

@Composable
private fun RowBox(onClick: () -> Unit, content: @Composable () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Box(
        Modifier
            .fillMaxWidth()
            .parcPress(source)
            .clickable(interactionSource = source, indication = LocalIndication.current, onClick = onClick)
            .heightIn(min = Metrics.searchRowHeightDp.dp)
            .padding(horizontal = Metrics.textGutterDp.dp, vertical = Metrics.rowGapDp.dp),
        contentAlignment = Alignment.CenterStart,
    ) { content() }
}

/** OPUS3: the sign that more results sit above; tapping it scrolls up by one row. */
@Composable
private fun MoreAboveRow(hidden: Int, skin: Skin, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = Metrics.touchTargetDp.dp)
            .padding(horizontal = Metrics.textGutterDp.dp, vertical = Metrics.rowGapDp.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicText(
            "▲  $hidden more ${if (hidden == 1) "result" else "results"} above",
            style = captionStyle(skin).copy(color = Color(skin.colors.accent.toInt())),
        )
    }
}

@Composable
private fun SheetMessage(text: String, skin: Skin) {
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Metrics.menuRowHeightDp.dp)
            .padding(horizontal = Metrics.textGutterDp.dp, vertical = Metrics.cardInsetDp.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicText(text, style = captionStyle(skin))
    }
}

/** OPUS1: pure sizing rules of the bottom-anchored sheet, kept out of Compose so they are testable. */
object SearchSheetLayout {
    /**
     * Space kept free above the sheet: the status bar or the pull-down search bar's band
     * (`keepOutTop` plus the bar), whichever reaches lower. The sheet's top edge never goes above it.
     */
    fun topClearanceDp(statusBarDp: Float): Float =
        maxOf(statusBarDp.coerceAtLeast(0f), Metrics.keepOutTopDp + Metrics.searchPullBarHeightDp)

}

@Composable
private fun SearchField(
    query: String,
    skin: Skin,
    focus: FocusRequester,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    val textStyle = searchStyle(skin).copy(fontSize = Metrics.searchFieldTextSp.sp)
    val pill = RoundedCornerShape(Metrics.searchFieldCornerDp.dp)
    BasicTextField(
        value = query,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Metrics.rowGapDp.dp, vertical = Metrics.rowGapDp.dp)
            .height(Metrics.searchFieldHeightDp.dp)
            .background(Color(skin.colors.bg.toInt()), pill)
            .border(skin.shape.surfaceBorderDp.dp, Color(skin.colors.lineBase.toInt()), pill)
            .padding(horizontal = Metrics.screenGutterDp.dp)
            .focusRequester(focus),
        textStyle = textStyle,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { onSubmit() }),
        singleLine = true,
        cursorBrush = SolidColor(Color(skin.colors.accent.toInt())),
        decorationBox = { innerTextField ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) {
                    BasicText(
                        "Search",
                        style = textStyle.copy(color = Color(skin.colors.textSecondary.toInt())),
                    )
                }
                innerTextField()
            }
        },
    )
}

private fun searchStyle(skin: Skin): TextStyle = TextStyle(
    fontFamily = if (skin.style.annotationFamily == "monospace" && skin.style.clockFamily == "monospace") androidx.compose.ui.text.font.FontFamily.Monospace else if (skin.style.header == com.brightblu.parc.model.HeaderStyle.ruled) androidx.compose.ui.text.font.FontFamily.Serif else skin.textFamily,
    color = Color(skin.colors.textPrimary.toInt()),
    fontSize = skin.typography.search.sizeSp.sp,
    fontWeight = FontWeight(skin.typography.search.weight),
    letterSpacing = skin.typography.search.trackingSp.sp,
)

private fun captionStyle(skin: Skin): TextStyle = TextStyle(
    fontFamily = if (skin.style.annotationFamily == "monospace") androidx.compose.ui.text.font.FontFamily.Monospace else skin.textFamily,
    color = Color(skin.colors.textSecondary.toInt()),
    fontSize = skin.typography.caption.sizeSp.sp,
    fontWeight = FontWeight(skin.typography.caption.weight),
    letterSpacing = skin.typography.caption.trackingSp.sp,
)

/** Match ranges are UTF-16 offsets into the label (T01 contract), so they map straight onto spans. */
private fun highlighted(label: String, ranges: List<MatchRange>, skin: Skin): AnnotatedString {
    if (ranges.isEmpty()) return AnnotatedString(label)
    val accent = SpanStyle(color = Color(skin.colors.accent.toInt()))
    return buildAnnotatedString {
        var cursor = 0
        for (range in ranges) {
            val start = range.start.coerceIn(0, label.length)
            val end = range.endExclusive.coerceIn(start, label.length)
            if (start > cursor) append(label.substring(cursor, start))
            if (end > start) withStyle(accent) { append(label.substring(start, end)) }
            cursor = end
        }
        if (cursor < label.length) append(label.substring(cursor))
    }
}

private fun copyToClipboard(context: Context, value: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("Result", value))
}

private fun openContactCard(context: Context, id: Long) {
    val uri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_URI, id.toString())
    startSafely(context, Intent(Intent.ACTION_VIEW, uri))
}

private fun dial(context: Context, number: String) {
    startSafely(context, Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)))
}

private fun message(context: Context, number: String) {
    startSafely(context, Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null)))
}

// core-ktx is not a dependency of this module, so the platform Uri.parse is the honest call.
@SuppressLint("UseKtx")
private fun openWeb(context: Context, url: String) {
    startSafely(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}

/** No matching handler is not an error the sheet can fix; stay open instead of crashing. */
private fun startSafely(context: Context, intent: Intent) {
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: android.content.ActivityNotFoundException) {
        // No dialer, messenger, contacts app or browser installed.
    } catch (_: SecurityException) {
        // A vendor ROM withheld the target; nothing to do from the sheet.
    }
}

private fun Modifier.surface(skin: Skin): Modifier {
    // T157: a pebble sheet: 32 dp top corners, square bottom (it sits on the keyboard or the navigation bar).
    val top = Metrics.searchSheetCornerDp.dp
    val corner = RoundedCornerShape(topStart = top, topEnd = top, bottomStart = 0.dp, bottomEnd = 0.dp)
    // T154: frosted skins (Glass) get the pre-rendered frost; every other skin keeps the flat surface fill.
    val fill = if (skin.shape.moduleStyle == com.brightblu.parc.model.ModuleStyle.frosted) {
        this.frostedBackground(skin, Metrics.searchSheetCornerDp, roundBottom = false)
    } else {
        this.background(Color(skin.colors.surface.toInt()).copy(alpha = skin.shape.surfaceAlpha), corner)
    }
    return fill
        .border(skin.shape.surfaceBorderDp.dp, Color(skin.colors.lineBase.toInt()), corner)
}
