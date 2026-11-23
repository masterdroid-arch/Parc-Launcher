package com.brightblu.parc.model

/** Thread-safe immutable dp tokens from DESIGN §2.3; no nullable values. */
object Metrics {
    // T191: a quiet book page and its editor.
    const val todayRefreshMs = 30_000L
    const val quoteTextSp = 20f
    const val quoteLineSp = 31f
    const val quoteCreditLineSp = 18f
    const val quoteInsetDp = 20f
    const val quoteCreditGapDp = 24f
    const val quoteSwipeDp = 48f
    const val quoteSheetFraction = 0.94f
    const val quoteFieldsWeight = 1f
    const val quoteDayCheckMs = 30_000L

    const val hubGlassTintAlpha = 179 // 70 percent; cached frost only.
    /** T391: a flat panel that floats over other content and carries text or taps is fully solid (the card asks for at least 92 percent; at 96 a bright clock still ghosts through a near-black panel). */
    const val floatingPanelMinAlpha = 1f
    const val floatingPanelMinTintAlpha = 245 // 96 percent: the floor for the cached frost tint (the frost bitmap itself is opaque, so nothing behind shows)
    const val hubCardMinContrast = 4.5 // T171: ink on a hub card at the lowest opacity the user may pick
    // Settings gallery (all distances in dp).
    const val settingsCardWidthDp = 272f
    const val settingsCardHeightDp = 440f
    const val settingsParallaxDp = 12f
    const val settingsSpringDamping = 0.8f
    const val settingsSpringStiffness = 400f
    // OPUS4: All-apps letters (OPUS5 moved them onto an arc; the strip is gone).
    const val letterStripTextSp = 12f
    const val letterStripDimAlpha = 0.45f
    const val letterBubbleDp = 64f
    const val letterBubbleTextSp = 32f
    // OPUS5: the letters on an inner arc around the hub.
    const val letterArcBandDp = 24f
    const val letterArcHubGapDp = 24f
    const val letterArcStartDeg = 181f
    const val letterArcEndDeg = 267f
    const val letterArcMaxRadiusDp = 240f
    const val letterArcGuideOffsetDp = 14f
    // OPUS1 settings design system: one preview frame, one sample size, one value column.
    const val settingsPreviewHeightDp = 236f
    const val settingsClockPreviewHeightDp = 132f
    const val settingsSampleDp = 40f
    const val settingsIconDp = 24f
    const val glyphStrokeDp = 1.5f
    const val focusGlyphDp = 18f
    const val settingsValueMaxWidthDp = 140f
    const val settingsChipMinWidthDp = 76f
    const val settingsPreviewDebounceMs = 120L
    /** T229: page options settle in from this scale on Springs.sheet; no tween, no blur. */
    const val pageOptionsSettleScale = 0.985f

    const val noteSaveDebounceMs = 400L
    const val noteUndoMs = 8_000L
    const val notePreviewLines = 3
    /** All notes together, as stored text. Raised from 1,000,000 in 1.2.0 (T182). */
    const val notesMaxChars = 5_000_000
    const val noteHeadingScale = 1.35f
    const val noteToolbarGapDp = 4f
    // T386: the writing surfaces. Notes gets one 52 dp formatting strip; each of its seven tools
    // keeps a 48 dp square target and the strip scrolls sideways to reach the last one.
    const val noteToolStripDp = 52f
    const val noteToolTargetDp = 48f
    const val noteToolGapDp = 8f
    const val noteToolStripInsetDp = 16f
    /** Space kept under the caret, so the last line never sits against the formatting strip. */
    const val noteCaretGapDp = 8f
    /** T183: the bell drawn beside a note's reminder (size and line width). */
    const val noteBellDp = 18f
    const val noteBellStrokeDp = 1.5f

    const val wallpaperMaxSide = 1440
    const val wallpaperMaxBytes = 2 * 1024 * 1024
    const val wallpaperQuality = 85
    const val wallpaperMaxZoom = 4f
    const val wallpaperBrightThreshold = 0.65
    const val wallpaperCropGuideAlpha = 0.35f

    const val wallpaperLabelScrim = 0.24f
    const val panelGutterDp = 12f // T158: the hub card keeps the module card's 12 dp edge inset (DESIGN 2.3)
    const val panelInsetDp = 8f
    const val panelGradientLift = 0.04f
    const val panelBorderAlpha = 0.35f
    const val wallpaperKnownScrimScale = 0.9f
    const val wallpaperTopScrim = 0.92f
    const val wallpaperArcScrim = 0.24f
    val panelOpacitySteps = (40..100 step 5).toList()
    // T381: the People page's round avatars. The visible circle is 48 dp inside a 56 dp touch box.
    const val peopleAvatarDp = 48f
    const val peopleAvatarBoxDp = 56f
    const val peopleAvatarColumnDp = 64f
    const val peopleAvatarGapDp = 10f
    const val peopleAvatarLabelDp = 16f
    const val focusSaveDebounceMs = 400L
    const val focusUndoMs = 8_000L
    const val focusControlDp = 48f
    // T219: the hub grip's Focus chip. The 32 dp chip sits inside the 48 dp touch row; its dot is drawn, not a glyph.
    const val focusChipHeightDp = 32f
    const val focusChipDotDp = 8f
    const val focusFirmDelayMs = 5_000L
    const val focusFirstDelayMs = 30_000L
    const val focusSecondDelayMs = 120_000L
    const val focusLaterDelayMs = 300_000L
    const val focusPassCooldownMs = 600_000L
    const val focusHoldMs = 20_000L
    const val focusCountdownTickMs = 1_000L
    const val focusPauseSeconds = 5
    const val focusBreakMs = 5 * 60_000L
    const val focusRefreshMs = 30_000L
    // T382: the personal "pause before opening" page. Five slow seconds, then the open button wakes.
    const val pauseBreathMs = 5_000L
    const val pauseBreathPeriodMs = 5_000
    const val pauseCountdownTickMs = 50L
    const val pauseCircleDp = 132f
    const val pauseExtraMinutes = 5
    const val commsMaxLines = 3
    const val commsPromptLines = 1
    const val commsRecentMessages = 3
    const val commsHeaderSp = 11f
    const val commsHeaderTitleSp = 18f
    const val commsMessageSp = 13f
    const val commsMessageGapDp = 16f
    // T233: the compact Messages header, its inline text tabs and the card layout model. The
    // header is one row; the footer is one quiet line and scrolls as the last list item.
    const val commsHeaderRowDp = 48f
    const val commsFooterDp = 20f
    const val commsSectionGapDp = 8f
    const val commsTabUnderlineDp = 2f
    const val commsCardHeaderDp = 24f
    const val commsCardInnerGapDp = 4f
    const val commsMessageLineDp = 18f
    const val commsCountLineDp = 16f
    const val commsActionDp = 48f // T166: raised from 40 to the minimum touch target
    // T386: the reply composer above the keyboard. The draft row keeps 48 dp and may grow for a
    // two-line reply at enlarged type; the sheet uses the kit's 20 dp menu corner and 16 dp inset.
    const val commsReplyRowMinDp = 48f
    const val commsReplySheetInsetDp = 16f
    const val commsReplyNoticeGapDp = 4f
    const val commsNoticeMs = 2_000L
    const val commsImageDp = 64f
    // T77: the quick-reply chips row and the dimmed history view. Chip height reuses
    // commsActionDp so a chip and an action control share one touch target.
    const val commsChipGapDp = 6f
    const val commsChipCornerDp = 8f
    const val commsChipMaxWidthDp = 160f
    const val commsHistoryTextAlpha = 0.55f
    // T77: how long to wait for the IME to finish resizing before the canned-reply field asks its
    // scrolling parent to bring it above the keyboard.
    const val commsImeSettleMs = 320L
    const val hubNetworkRefreshMillis = 30_000L
    const val hubArcMarginDp = 12f
    const val hubTopGapDp = 8f
    const val hubTileMaxHeightDp = 160f
    // T330: the full-screen hub. Pulling the grip up this far opens it; swiping down this far closes it.
    const val hubFullOpenPullDp = 72f
    const val hubFullCloseSwipeDp = 96f
    /** T330: in the full-screen view a tile may grow past the card's 160 dp, so the page uses the extra height. */
    const val hubFullTileMaxHeightDp = 220f
    const val hubArtworkMaxDp = 96f
    const val hubHeaderHeightDp = 24f
    const val hubCompactHeightDp = 176f
    const val hubGripHeightDp = 48f
    const val hubGripWidthDp = 36f
    const val hubGripTouchWidthDp = 96f // T158: the grip's touch width; the page action and options take the rest of the row
    const val hubGripLineDp = 4f
    const val hubDotActiveWidthDp = 20f
    const val hubNameSp = 14f
    const val hubClockSp = 64f
    const val hubValueSp = 40f
    const val hubLineSp = 15f
    const val hubNoteSp = 12f
    const val hubDotsHeightDp = 48f
    const val hubFadeHeightDp = 12f
    // 1.5.1 hub type scale: one figure (the skin's countDot numeral, 28 sp), one title (17 sp, used where a row has words
    // instead of a number), one body line (hubLineSp, 15 sp) and one caption (the skin's caption, 11 sp). Rows are 56 dp.
    const val hubTitleSp = 17f
    // 1.5.1: kept passages on the Commonplace page: smaller than Today's quote card so several are visible.
    const val commonplaceTextSp = 16f
    const val commonplaceLineSp = 23f
    const val commonplaceCaptionSp = 12f
    const val commonplaceInsetDp = 14f
    const val commonplaceGapDp = 8f
    const val commonplaceMaxLines = 6
    const val hubStatRowDp = 52f
    const val hubStatRowPadDp = 6f
    const val hubStatSideMaxDp = 140f
    const val hubMutedMix = 0.4f // share of textPrimary mixed into textSecondary for labels, so they stay readable on glass
    const val hubSectionGapDp = 4f
    const val hubBaselineNudgeDp = 2f // lifts a right-hand caption onto the figure's baseline
    const val hubSearchInsetDp = 12f
    const val hubSearchHeightDp = 36f
    const val hubIconButtonDp = 40f
    const val hubSortGlyphDp = 20f
    /**
     * T378: the slim overflow thumb at a hub page's trailing edge. It sits in its own gutter, so a
     * right-aligned value is never covered; it fades once scrolling settles and hides at the end.
     */
    const val hubScrollThumbWidthDp = 3f
    const val hubScrollThumbInsetDp = 1.5f
    const val hubScrollThumbMinDp = 36f
    const val hubScrollEdgeGutterDp = 6f
    const val hubScrollThumbSettleMs = 650L
    const val hubScrollThumbAlpha = 0.55f
    // T158 (DESIGN 3.4): the hub's tab strip. A pill is half the 48 dp touch row; its corner is half its height.
    const val hubTabPillHeightDp = 24f
    const val hubTabPillPadDp = 12f // text to the pill edge, left and right
    const val hubTabGapDp = 4f // between pills; the touch rows of neighbours meet, the pills do not
    const val hubTabSelectedMix = 0.24f // share of `accent` mixed into `surface` for the selected pill
    const val hubTabNameSp = 13f
    const val hubTabLiftScale = 1.06f // T186: a tab held for reordering grows a little
    const val hubTabEdgeZoneDp = 40f // T186: a dragged tab this close to the strip's edge scrolls the strip
    const val hubTabEdgeScrollDpPerSecond = 520f // T186: strip scroll speed at the very edge
    /** T167: room kept between a stats page's last row and the bottom fade at the end of its scroll. */
    const val hubScrollEndGapDp = 16f
    /** T167: a drag is a page swipe only when it is this many times wider than tall; otherwise it scrolls. */
    const val hubDirectionLockRatio = 1.25f
    const val hubPageSwipeDp = 48f
    const val hudDotDp = 8f
    // T187: the Steps page. The goal arc is drawn at one size per hub size; the week bars share one height.
    const val stepsArcCompactDp = 56f
    const val stepsArcStandardDp = 88f
    const val stepsArcTallDp = 112f
    const val stepsArcStrokeDp = 5f
    const val stepsBarsHeightDp = 56f
    const val stepsBarsCompactHeightDp = 40f
    const val stepsBarFill = 0.5f
    const val stepsGoalLineAlpha = 0.5f
    const val stepsGoalLineDp = 1f
    // T188: the Rhythm page. The 24-hour ring is drawn at one size per hub size.
    const val rhythmRingCompactDp = 112f
    const val rhythmRingStandardDp = 148f
    const val rhythmRingTallDp = 184f
    const val rhythmRingStrokeDp = 12f
    const val rhythmTickDp = 5f
    const val rhythmTickAlpha = 0.6f
    const val rhythmNowDotDp = 4f
    // T197: the Records page. A record row is a label line over a value line; the gap between rows shrinks to the minimum to fit the panel.
    const val recordsRowDp = 40f
    const val recordsRowGapMinDp = 2f
    // T198: pages only some builds carry. A stat is a label line over a value line; the clock value is a little larger.
    const val extraPageRowDp = 40f
    const val extraPageRowGapMinDp = 2f
    const val extraPageTimeSp = 22f
    // T199: a flip card page. The card takes what the panel leaves after the header and the answer row; the word is large.
    const val flipCardHeaderDp = 20f
    const val flipCardCardMinDp = 96f
    const val flipCardWordSp = 26f
    const val flipCardSwipeDp = 40f
    const val flipCardDistance = 12f
    const val sessionProgressHeightDp = 4f
    const val rhythmSwatchDp = 8f // 1.2.1: the small lit swatch in the legend
    const val rhythmRingBelowMinDp = 120f // 1.5.1
    const val rhythmRingMinDp = 72f // 1.2.1: the ring never draws smaller than this at Standard or Tall (the page scrolls instead)
    const val rhythmRingCompactMinDp = 64f
    const val rhythmLabelBandDp = 16f // 1.2.1: room around the ring for the 00 / 06 / 12 / 18 hour labels
    const val rhythmHeaderDp = 80f // 1.2.1: the day switch row (arrows are 48 dp touch targets)
    const val rhythmFactsRowDp = 56f // 1.2.1: one row of facts
    const val rhythmSideFactsMinWidthDp = 120f // 1.2.1: the facts beside the ring need at least this much width
    const val rhythmStrokeFraction = 0.1f // 1.2.1: ring stroke as a share of the ring, capped at rhythmRingStrokeDp
    // 1.2.1: dragging a row in a settings list starts only after a press and hold (Metrics.longPressMs) on its handle.
    const val settingsDragEdgeZoneDp = 72f // a lifted row this near the top or bottom of the list scrolls it
    const val settingsDragScrollDpPerSecond = 640f
    const val settingsHandleWidthDp = 56f
    // T190: the Year page. One dot per day, a row per month; the row height is per hub size, the dot is 70 % of the smaller cell side.
    const val yearRowCompactDp = 9f
    const val yearRowStandardDp = 11f
    const val yearRowTallDp = 13f
    const val yearDotFill = 0.7f
    const val yearMonthLabelWidthDp = 12f
    const val yearMonthLabelSp = 8f
    const val yearRingDp = 1.2f
    const val yearMarkFill = 0.32f
    /** T238: a day that is empty (future, or no number) is a thin outline ring at this alpha; filled days are solid. */
    const val yearFutureAlpha = 0.45f
    const val yearEmptyRingAlpha = 0.7f
    const val yearEmptyRingDp = 1f
    const val yearLegendDotDp = 9f
    /** T238: the weakest step (step 1) is the palette colour faded toward the card only until it still has this contrast on the card. */
    const val yearStepFloorContrast = 3.2f
    /** T238: where steps 2 and 3 sit between step 1 (0) and step 4 (1). */
    const val yearStepRamp2 = 0.4f
    const val yearStepRamp3 = 0.7f
    /** T238: fixed hues, each with a light form (for dark skins) and a dark form (for light skins). The better one is used. */
    const val yearGreenLight = 0xFF8FD99CL
    const val yearGreenDark = 0xFF1F6B34L
    const val yearBlueLight = 0xFF86B6F2L
    const val yearBlueDark = 0xFF1D4F9CL
    const val yearAmberLight = 0xFFF0C050L
    const val yearAmberDark = 0xFF8A5A00L
    const val yearRoseLight = 0xFFF0A0B4L
    const val yearRoseDark = 0xFF9E2A4AL
    // T189: the Sky page. The sun's day as a curve over a horizon line, and the moon as a lit disc.
    const val skyArcCompactDp = 84f
    const val skyArcStandardDp = 108f
    const val skyArcTallDp = 140f
    const val skyCurveStrokeDp = 2f
    const val skyHorizonDp = 1f
    const val skyNowDotDp = 5f
    const val skyBelowHorizonAlpha = 0.55f
    const val skyMoonCompactDp = 48f
    const val skyMoonStandardDp = 60f
    const val skyMoonTallDp = 76f
    const val skyMoonUnlitAlpha = 0.5f
    const val skyMoonOutlineDp = 1f
    const val badgeDotRadiusDp = 3f
    const val badgeCountRadiusDp = 8f
    const val badgeCornerFraction = 0.72f
    const val badgeSwitchWidthDp = 36f
    const val badgeSwitchHeightDp = 20f
    const val badgeSwitchThumbDp = 14f
    // Work-profile badge (T75): a small briefcase on the lower-right of a work app's icon, clear of
    // the notification dot on the upper-right. The disc and the glyph are drawn, never bitmaps.
    const val workBadgeRadiusDp = 7f
    const val workBadgeBodyWidthDp = 8f
    const val workBadgeBodyHeightDp = 5f
    const val workBadgeHandleWidthDp = 3.6f
    const val workBadgeHandleHeightDp = 1.9f
    const val workBadgeHandleLiftDp = 1.4f
    const val editPlusRadiusDp = 6f
    const val editPlusStrokeDp = 2f
    // Renderer geometry/presentation tokens, including the T02 review's sequential fade.
    const val springMaxStepMs = 64L
    const val glowSettlePx = 0.1f
    const val glowSettleVelocity = 0.1f
    const val pulseRadiusScale = 1.12f
    const val discRimDp = 1f
    const val discLiftFraction = 0.12f
    const val contactShadowDp = 4f
    const val contactShadowAlpha = 0.32f
    const val emptyDiscAlpha = 0.22f
    const val iconBitmapPx = 144
    const val previewPlaceholderTextFraction = 0.6f
    const val folderPreviewIconDp = 18f
    const val folderPreviewGapDp = 2f
    const val folderPreviewColumns = 2
    const val folderPreviewCount = 4
    const val folderLabelScreenMarginDp = 8f
    const val folderLabelMaxChars = 8
    // T37/T170: 8 characters at 12 sp need about 60 dp plus the tint padding; still clipped to one line.
    const val folderLabelMaxWidthDp = 72f
    const val folderLabelGapDp = 2f
    const val folderLabelBracketMaxWidthDp = 104f // "[ Getting… ]" in a monospace skin
    // T170: plain 12 sp medium text in the card ink over a faint tint of the card colour, kept 12 dp from the card edge.
    const val folderLabelSp = 12f
    const val folderLabelWeight = 500
    const val folderLabelTintAlpha = 0.10f
    const val folderLabelCornerDp = 6f
    const val folderLabelPaddingHorizontalDp = 6f
    const val folderLabelPaddingVerticalDp = 2f
    const val folderLabelCardInsetDp = 12f
    const val gearTeeth = 8
    const val gearInnerRatio = 0.62f
    const val gearHoleRatio = 0.28f
    const val gearRadiusRatio = 0.32f
    const val labelGapDp = 4f
    const val labelMaxWidthDp = 160f
    const val labelPaddingHorizontalDp = 8f
    const val labelPaddingVerticalDp = 4f
    const val longPressMs = 350L
    const val searchSwipeDp = 48f // Existing search-sheet overscroll/dismiss distance.
    const val searchOpenSwipeDp = 110f
    const val searchSwipeDominance = 1.5f
    const val searchFlingMinDp = 60f
    const val searchFlingVelocityDpPerSecond = 1_200f
    const val searchHubPullResistance = 0.5f
    const val searchPullMinHubAlpha = 0.6f
    const val searchPullBarHeightDp = 48f
    const val searchDimAlpha = 0.15f
    const val searchIdleAlpha = 0.6f
    // T157 (DESIGN 2.3, 3.4, S21): the pebble search sheet. Motion is `Springs.sheet`; nothing here is a duration.
    const val searchSheetCornerDp = 32f
    const val searchFieldHeightDp = 56f
    const val searchFieldCornerDp = 28f // half the field height
    const val searchFieldTextSp = 20f
    const val searchRowHeightDp = 56f
    /** T247: the cached arc icon shown at the start of an app search row. */
    const val searchRowIconDp = 28f
    /** T247: the quiet arc sketch used by the Add to arc picker. */
    const val searchArcLineAlpha = 0.55f
    const val searchArcSpotRadiusDp = 14f
    const val searchArcSpotAlpha = 0.18f
    /** T194: alpha of the accent pill that slides under the selected search mode chip. */
    const val searchModeHighlightAlpha = 0.14f
    /** T195: how long "Copied" stays under a calculator result. */
    const val searchCopiedNoticeMs = 1_500L
    /** T195: the note preview card in Note mode: its corner, inner padding and a long note's visible height. */
    const val searchNoteCardCornerDp = 14f
    const val searchNoteCardPaddingDp = 14f
    const val searchNoteCardMaxHeightDp = 220f
    const val searchNoteCardFillAlpha = 0.5f
    /** T196: Translate mode. Pause after the last key before sending, shortest text sent, longest text sent, saved translations, wait before the one retry. */
    const val translateDebounceMs = 600L
    const val translateMinChars = 2
    const val translateMaxChars = 5_000
    const val translateHistoryMax = 20
    const val translateRetryDelayMs = 1_500L
    /** Alpha of the module while the sheet is fully open (DESIGN 3.4). */
    const val searchModuleAlpha = 0.35f
    /** A fast flick down dismisses from this distance (the plain drag needs `searchSwipeDp`). */
    const val searchFlickMinDp = 24f
    /** Scale a pressed menu or result row springs to on `Springs.press`; the slot uses `pressSlotScale`. */
    const val rowPressScale = 0.97f
    /** A menu grows from this scale to 1 on `Springs.sheet` while it fades in. */
    const val menuOpenFromScale = 0.92f
    const val collapsedScale = 0.3f
    const val outgoingFadeEnd = 0.45f
    const val incomingFadeStart = 0.35f
    const val folderInnerRadiusDp = 150f
    const val folderOuterRadiusDp = 250f
    const val folderInnerCapacity = 3
    const val folderOuterCapacity = 5
    const val folderInnerPitchDegrees = 23.1f
    const val folderOuterPitchDegrees = 17f
    const val folderCardMarginDp = 8f
    const val fineSpokeDegrees = 7.5f
    const val gridDp = 4f
    const val iconDp = 44f
    // T71 digital clock: all sizes are fractions of the skin's clock size except the gap/line.
    const val digitalClockHeightRatio = 0.82f
    const val digitalClockDigitWidthRatio = 0.54f
    const val digitalClockStrokeRatio = 0.14f
    const val digitalClockGapRatio = 0.10f
    const val digitalClockColonWidthRatio = 0.30f
    const val digitalClockDotRadiusRatio = 0.55f
    const val clockSublineGapDp = 2f
    const val touchTargetDp = 56f
    const val minSlotPitchDp = 60f
    const val homeRadius1Dp = 120f
    const val homeRadius2Dp = 190f
    const val homeRadius3Dp = 255f
    const val homeRadius4Dp = 315f
    const val hubRadiusDp = 22f
    const val hubTouchDp = 72f // 36 dp hit radius; the visible ring stays 22 dp.
    const val hubDoubleTapMs = 280L
    // Module card (T143, DESIGN 3.1). Offsets are from the hub origin, so the card follows the hub on every screen.
    const val moduleCardWidthDp = 360f
    const val moduleCardRightOfOriginDp = 36f
    const val moduleCardBelowOriginDp = 68f
    const val moduleCardAboveOriginDp = 357f
    const val moduleCardMinLeftDp = 4f
    const val moduleCardPanelGapDp = 8f // T165: least clear space between the stats panel's bottom edge and the card top.
    const val moduleGuideDp = 1.5f
    const val moduleLeanDp = 6f
    const val moduleLeanAlpha = 0.7f
    // Frost (T153, DESIGN 2.1 Glass): pre-rendered, never live. Blur runs on a bitmap one eighth the size.
    const val frostDownscale = 8
    const val frostBlurPasses = 3
    const val frostBlurRadiusPx = 3 // on the small bitmap, so about 24 px per pass at full size
    const val frostHazeScale = 4 // the haze stage is drawn at a quarter of the screen size and scaled up
    const val frostHazeAlphaMin = 0.12f
    const val frostHazeAlphaMax = 0.18f
    const val frostCacheEntries = 8
    const val frostRenderBudgetMs = 60L
    // Glass over the real wallpaper (T169): the wallpaper stays visible, the cards show a blurred copy of the part behind them.
    const val glassWallpaperTint = 0.06f // flat bg tint over the wallpaper; never above glassWallpaperTintMax
    const val glassWallpaperTintMax = 0.08f
    const val glassEdgeDp = 1f
    const val glassEdgeAlpha = 0.35f // white
    const val glassGrainAlpha = 0.04f // noise tile; never above 4 percent
    const val glassGrainTilePx = 64
    const val glassGrainSeed = 0x6C61A5
    const val glassStageSamples = 64 // per axis, for the wallpaper identity hash
    // Glass refraction (T173): a pre-rendered lens band along the card edge, never per frame.
    const val glassRefractBandDp = 16f // 12 to 20 dp
    const val glassRefractMagnify = 1.06f // 1.04 to 1.08 at the rim
    const val glassRefractMaxShiftOfBand = 0.45f // the picture never moves more than this share of the band, so it never folds
    const val glassInnerShadowDp = 3f // just inside the bright rim
    const val glassInnerShadowAlpha = 0.14f // black
    const val glassRefractBudgetMs = 80L
    const val glassSystemStageScale = 4 // a system wallpaper read is drawn at a quarter of the screen size
    const val hubTapTravelDp = 12f
    const val keepOutTopDp = 56f
    const val keepOutRightDp = 48f
    const val releaseNotesMaxHeightFraction = 0.85f
    const val keepOutBottomDp = 56f
    const val keepOutLeftDp = 16f
    const val textGutterDp = 20f
    // T166 spacing system (DESIGN 2.3). Every screen takes its padding from these tokens and from
    // ParcSpacing, never from dp literals, so no text or control touches an edge anywhere in Parc.
    const val screenGutterDp = 20f // page text and cards from the left and right screen edge (same as textGutterDp)
    const val cardInsetDp = 16f // text and controls from the edge of the card they sit in
    const val rowGapDp = 12f // above and below the content of a row, and between rows
    const val sectionGapDp = 24f // above a section header
    const val sectionHeaderGapDp = 8f // under a section header, before its content
    const val minTouchDp = 48f // smallest touch target; the module keeps touchTargetDp (56)
    const val narrowCardDp = 112f // a card narrower than this (a Quick toggle tile) takes a row gap, not a card inset
    const val actionEdgeGapDp = 12f // nearest an action's touch area comes to the edge of its card or screen
    const val itemGapDp = 8f // between neighbours in a row or a wrapped group (chips, buttons, icon and text)
    const val lineGapDp = 4f // between the lines of one item (title, value, description)
    const val nestedRowInsetDp = 40f // a nested row (a shortcut under its app) starts one more gutter in
    const val settingsRowMinHeightDp = 52f
    const val settingsHelperRowDp = 64f
    const val settingsRowInsetDp = cardInsetDp // Settings rows: left, right, and the trailing switch or value
    // T373: the Settings root. Six value tiles, three across (two at enlarged text), glass over the wallpaper.
    const val settingsTileMinHeightDp = 104f
    const val settingsTileGapDp = 8f
    const val settingsTileCornerDp = 20f
    const val settingsTilePaddingDp = 12f // inside a tile, so a word like Wallpaper fits three across
    const val settingsTileColumns = 3
    const val settingsFocusReturnDelayMs = 450L // Back waits for the page cross-fade before focus returns to the tile
    const val settingsTileColumnsLarge = 2
    const val settingsTileLargeFontScale = 1.3f // from this font scale the tiles go two across
    const val settingsBackdropScrimAlpha = 0.5f // the dark veil over the wallpaper behind Settings, so text stays readable
    // T374: the live Home sample (a whole-Home thumbnail beside one or two zoomed crops of the same Home) and the skin tiles.
    const val settingsSampleHeightDp = 148f // inside the sample card with one crop
    const val settingsSampleTwoCropHeightDp = 204f // with two crops (clock above arcs), so each crop can hold its lines
    const val settingsSampleThumbWidthDp = 66f // the whole-Home thumbnail, beside the crops
    const val settingsSampleGapDp = 8f // between the thumbnail and the crops, and between two crops
    const val settingsSampleCornerDp = 12f // each pane of the sample
    const val settingsSampleCropWidthDp = 290f // how much of Home's width a zoomed crop shows
    const val settingsSampleArcsBottomInsetDp = 40f // the arcs crop ends this far above Home's bottom edge
    const val settingsSampleClockTopDp = 62f // the clock crop starts this far below the stats card's top, under its tab strip
    const val settingsSkinTileColumns = 3
    const val settingsSkinTileImageHeightDp = 120f // the skin tile's picture; the name sits under it
    const val settingsSkinTileCornerDp = 16f
    const val settingsSelectedStrokeDp = 2f // the outline of the chosen skin tile
    const val settingsApplyButtonMinDp = 56f
    const val menuRowHeightDp = 48f
    const val menuCornerDp = 20f
    const val menuMinWidthDp = 220f
    // T372: a segment is a pill inside the segmented container, inset from its edge.
    const val segmentInsetDp = 4f
    const val segmentCornerDp = 16f
    const val menuGapDp = 8f
    // T324: the wallpaper picker sheet. It is a chooser sheet like the empty-spot and arc sheets, so it
    // keeps the menu corner; the body never grows past this share of the screen, then it scrolls.
    const val wallpaperSheetMaxHeightFraction = 0.8f
    const val editDotRadiusDp = 2f
    const val editLiftDp = 12f
    const val editLiftScale = 1.12f
    // Retired search pill (kept only so feature/search still compiles; the launcher no longer draws it).
    const val handleWidthDp = 36f
    const val handleHeightDp = 4f
    const val handleTouchWidthDp = 48f
    const val handleTouchHeightDp = 48f
    const val handleBottomOffsetDp = 40f
    const val handleLeftOffsetDp = 16f
    // Circular search button (T35): a hub-style magnifier in the bottom-left, clear of slots and nav strips.
    const val searchButtonTouchDp = 44f
    const val searchButtonRadiusDp = 22f
    const val searchButtonLeftDp = 16f
    const val searchButtonBottomOffsetDp = 40f
    const val searchButtonGlyphRadiusDp = 7f
    const val searchButtonGlyphHandleDp = 6f
    const val searchButtonCaptionGapDp = 8f
    // Usage-layer swap: a quick cross-fade plus a one-time accent swell on the newcomer.
    const val swapFadeMs = 240L
    // Shared folder transition and joystick Back (T27).
    const val homeRecedeScale = 0.92f
    // T37: the home behind an open folder is a 6 % ghost; its labels are dropped while a folder is open.
    const val folderGhostAlpha = 0.06f
    // T244: while an app is dragged out of an open folder, the Home arcs and their free spots show
    // as a quiet drop guide behind the folder arcs.
    const val folderDropGuideAlpha = 0.42f
    // T144: press scales and the size a slot grows from when a ring opens (DESIGN 2.6).
    const val pressSlotScale = 0.92f
    const val pressHubScale = 0.88f
    const val openFromScale = 0.5f
    // T149: magnify under thumb (DESIGN 3.3). Scale of the slot under the thumb and how far it is pushed away from the hub.
    const val magnifySubtleScale = 1.2f
    const val magnifySubtlePushDp = 8f
    const val magnifyDockScale = 1.45f
    const val magnifyDockPushDp = 16f
    const val magnifyCardMarginDp = 4f
    const val sharedDiscSwell = 0.6f
    const val aimRingGapDp = 3f
    const val backOffsetDp = 30f
    const val backChevronDp = 6f
    // Hub joystick (T22). Reach bands are measured from the hub centre, not the arc radii.
    const val aimDeadZoneDp = 20f
    const val aimBandStartDp = 20f
    const val aimBandEndDp = 140f
    const val aimHysteresisFraction = 0.15f
    const val aimDragStartDp = 10f
    // Dial rotation (T29, T148). Band/slop are dp; release maths is in ArcRelease, the spring in ArcMotion.
    const val rotateBandDp = 28f
    const val rotateSlopDp = 10f
    const val rotateVelocityMin = 3f
    const val rotateSettleEps = 0.001f
    const val rotateEdgeScaleMin = 0.85f
    const val modeGlyphDotDp = 4f
    const val modeGlyphPitchDp = 8f
    const val modeGlyphOffsetDp = 36f
    // Editing UX (T30). First-run hint card, one-step undo snackbar and the inline rename field.
    const val hintCardWidthDp = 300f
    const val hintCardPaddingDp = 16f
    const val hintCardTopDp = 150f // T271: below the page-name strip, so the hint never covers the page names
    const val hintCardLineGapDp = 10f
    const val hintCornerDp = 20f
    const val editNoticeMs = 2_500L
    const val snackbarMs = 3_000L
    /** T246: how long the quiet "Add <app> to an arc?" line stays; it is marked shown at once. */
    const val newAppPromptMs = 8_000L
    const val snackbarBottomDp = 32f
    const val snackbarCornerDp = 12f
    const val settingsSubtitleGapDp = 6f
    // Stats HUD (T33). Temperature thresholds are °C; the rest are dp or counts.
    const val statsHistoryWindowMillis = 24L * 60 * 60_000L
    const val statsHistoryRefreshMillis = 60_000L
    const val statsHistoryGapMillis = 10 * 60_000L
    const val hudTempWarnC = 42f
    const val hudTempHotC = 55f
    const val hudTempMaxC = 90f
    const val hudStorageWarnPercent = 90
    const val hudBatterySegments = 10
    const val hudWifiBars = 4
    const val hudGaugeDp = 40f
    const val hudGaugeStrokeDp = 3f
    const val hudBarHeightDp = 5f
    const val hudSparklineHeightDp = 24f
    const val hudCellGapDp = 12f // T158: 12 dp between the rows of a hub page (equals rowGapDp)
    const val hudCellHeightDp = 56f
    const val hudCollapsedGapDp = 10f
    // T377: labelled bars and the battery ring. One label width is reserved so the bars on Controls,
    // Device and Records align on a common edge, and one ring size for the battery percentage inside.
    const val hudBarLabelDp = 64f
    const val hudBatteryRingDp = 96f
    const val hudBatteryRingStrokeDp = 6f
    // Cartographic ornaments. These never participate in layout/hit testing.
    const val atlasTickStepDegrees = 5
    const val atlasBearingStepDegrees = 30
    const val atlasTickDp = 4f
    const val atlasMajorTickDp = 8f
    const val atlasBearingGapDp = 14f
    const val atlasContourSpacingDp = 18f
    const val atlasContourAmplitudeDp = 14f
    const val atlasPatternAlpha = 0.16f
    const val atlasGridDp = 32f
    const val atlasTilePx = 64
    const val atlasRegistrationInset = 0.08f
    const val atlasRegistrationLength = 0.13f
    const val atlasPreviewHeightDp = 96f
    const val atlasPreviewWidthDp = 104f
    // Hidden arcade (T200). Orbit is drawn in one Canvas on a pure black square; sizes are dp, ring radii and the score are fractions of the field's side.
    const val arcadeRingInnerFraction = 0.22f
    const val arcadeRingMiddleFraction = 0.34f
    const val arcadeRingOuterFraction = 0.46f
    const val arcadeDotPitchDp = 9f
    const val arcadeDotDp = 1.5f
    const val arcadeMarkDp = 2.6f
    const val arcadeMarkEveryDeg = 30f
    const val arcadeBarrierDotDp = 3.4f
    const val arcadeBarrierPitchDp = 7f
    const val arcadePlayerDp = 4.8f
    const val arcadeLightDp = 3.4f
    const val arcadeLightHaloDp = 7f
    const val arcadeLightPulseTicks = 90
    const val arcadeDimAlpha = 0.3f
    const val arcadeMarkAlpha = 0.7f
    const val arcadeScoreFraction = 0.13f
    const val arcadeScoreDimAlpha = 0.55f
    const val arcadeFieldMinDp = 120f
    // T211: Lander's dots and its small labelled gauges inside the field.
    const val arcadeLanderPadDotDp = 2.4f
    const val arcadeLanderPadPitchDp = 7f
    const val arcadeLanderShipDp = 3.0f
    const val arcadeLanderFlameDp = 2.4f
    const val arcadeLanderGaugeDotDp = 2.0f
    const val arcadeLanderGaugePitchDp = 9f
    const val arcadeLanderHudMarginFraction = 0.05f
    const val arcadeLanderLabelFraction = 0.032f
    const val arcadeLanderValueFraction = 0.052f
    // T205: disk thumbnail dimensions and typographic cover proportions (not screen layout).
    const val bookCoverWidthPx = 320
    const val bookCoverHeightPx = 448
    const val bookCoverInsetFraction = 0.09f
    const val bookCoverTitleFraction = 0.085f
    const val bookCoverAuthorFraction = 0.05f
    const val bookCoverLineSpacing = 1.15f
    const val bookCoverTitleLines = 7
    // T206: the Library shelf and the Find books sheet. Covers keep the reader's 5:7 shape;
    // the shelf sizes itself from the panel height and adds columns on wide screens.
    // T249: the Continue reading row on Today. A small 5:7 cover beside a title and percent.
    const val todayCoverWidthDp = 30f
    const val todayCoverHeightDp = 42f

    const val bookShelfAspect = 0.714f
    const val bookShelfCoverMinDp = 56f
    const val bookShelfCoverMaxDp = 140f
    const val bookShelfTallMinCoverDp = 84f // T271: Tall's second row only when covers stay at least this tall
    const val bookShelfLabelHeightDp = 44f
    const val bookShelfGapDp = 12f
    const val bookShelfProgressDp = 2f
    const val bookShelfProgressTrackAlpha = 0.35f
    const val bookShelfCornerDp = 6f
    const val bookShelfMissingAlpha = 0.55f
    // T227: the "+" tile that follows the last book on the shelf. Its size follows the cover; only
    // the drawn glyph, its inset and the label gap are their own quiet values.
    const val bookShelfAddGlyphFraction = 0.42f
    const val bookShelfAddArmFraction = 0.30f
    const val bookShelfAddStrokeDp = 1.6f
    const val bookShelfAddInsetDp = 4f
    const val bookShelfAddGapDp = 4f
    const val bookFindChipHeightDp = 48f
    const val bookFindChipInsetDp = 10f
    const val bookFindSearchHeightDp = 48f
    const val bookFindRowGapDp = 4f
    const val bookFindTickDp = 20f
    const val bookFindTickStrokeDp = 1.5f
    const val bookFindNoticeMs = 3_000L
    // T226: the redesigned Find books screen. One compact filter, quiet rows and a sticky selection bar.
    const val bookFindSegmentHeightDp = 40f
    const val bookFindSegmentGapDp = 2f
    const val bookFindBadgeWidthDp = 38f
    const val bookFindBadgeHeightDp = 20f
    const val bookFindBadgeCornerDp = 4f
    const val bookFindSelectedTintAlpha = 0.12f
    const val bookFindGroupHeaderHeightDp = 48f
    const val bookFindStickyBarClearanceDp = 72f
    /** T346: extra room while the selection bar is shown, so the last row can scroll clear of it. */
    const val bookFindSelectionBarExtraDp = 56f
    const val bookFindRowSettleOffsetDp = 8f
    const val bookFindRowSettleDelayMs = 18
    const val bookFindSortMenuWidthDp = 160f
    const val bookFindSortButtonWidthDp = 96f
    // T207: the full-screen reader. Page margins and type sizes are reader settings, not screen tokens;
    // these are the bar, sheets and touch geometry.
    const val readerBarSideInsetDp = 12f
    const val readerHairlineDp = 1f
    const val readerBarTopInsetDp = 8f
    const val readerBarMinHeightDp = 48f
    const val readerProgressHeightDp = 2f
    const val readerProgressGapDp = 6f
    const val readerSheetInsetDp = 12f
    const val readerSheetCornerDp = 20f
    const val readerSheetMaxWidthDp = 480f
    const val readerSheetMaxHeightFraction = 0.78f
    const val readerContentsHeightFraction = 0.62f
    const val readerRowGapDp = 8f
    const val readerChipHeightDp = 44f
    const val readerChipInsetDp = 10f
    const val readerChipGapDp = 6f
    const val readerTouchTargetDp = 48f
    const val readerEdgeFraction = 0.30f
    const val readerSwipeDp = 48f
    const val readerBarScrimAlpha = 0.94f
    const val readerDefaultFontSp = 19f
    const val readerMinFontSp = 15f
    const val readerMaxFontSp = 30f
    const val readerFontStepSp = 1f
    const val readerDefaultLineSpacing = 1.55f
    const val readerMinLineSpacing = 1.2f
    const val readerMaxLineSpacing = 2.2f
    const val readerLineStep = 0.15f
    const val readerDefaultMarginFraction = 0.07f
    const val readerMinMarginFraction = 0.03f
    const val readerMaxMarginFraction = 0.14f
    const val readerMarginStep = 0.01f
    const val readerPageLightPaper = 0xFFF3EDE2L
    const val readerPageLightInk = 0xFF24211CL
    const val readerPageLightSecondary = 0xFF6B6258L
    const val readerPageSepiaPaper = 0xFFE8D8B8L
    const val readerPageSepiaInk = 0xFF3A2E1EL
    const val readerPageSepiaSecondary = 0xFF746349L
    const val readerPageDarkPaper = 0xFF17191DL
    const val readerPageDarkInk = 0xFFE6EAF0L
    const val readerPageDarkSecondary = 0xFF9AA3AEL
    const val readerPageBlackPaper = 0xFF000000L
    const val readerPageBlackInk = 0xFFE6EAF0L
    const val readerPageBlackSecondary = 0xFF8C97A6L
    // T345: paired pure-paper modes. Body ink and paper are exactly #000000 and #FFFFFF; the
    // secondary tone is a neutral grey for rules and captions, not body text.
    const val readerPageBlackOnWhitePaper = 0xFFFFFFFFL
    const val readerPageBlackOnWhiteInk = 0xFF000000L
    const val readerPageBlackOnWhiteSecondary = 0xFF666666L
    const val readerPageWhiteOnBlackPaper = 0xFF000000L
    const val readerPageWhiteOnBlackInk = 0xFFFFFFFFL
    const val readerPageWhiteOnBlackSecondary = 0xFFB3B3B3L
    // T255: Night is dimmer and warmer than Black, so it is easy on the eyes in the dark.
    const val readerNightPaper = 0xFF0A0908L
    const val readerNightInk = 0xFFB5A998L
    const val readerNightSecondary = 0xFF7A7062L
    const val readerMaxDocumentChars = 8_000_000
    const val readerMaxBookHtmlChars = 24_000_000
    const val readerMaxImageBytes = 4_000_000
    const val readerMaxImagesBytes = 16_000_000
    const val readerMaxSourceBytes = 128_000_000L
    // T343: reflowable text is laid out in small chunks. Only the current chunk and one neighbour
    // either side stay in the DOM, so an unusual chapter cannot make the whole book lay out.
    // Page starts are cached per chunk; unseen chunks use the measured characters per page.
    const val readerChunkChars = 16_000
    const val readerChunkWindow = 3
    const val readerCharsPerPageFallback = 900.0
    // T344: the shared bottom panel. The slider is a touch target in its own right; the bubble
    // follows the finger above it and reports the destination without moving the page.
    const val readerJumpSliderHeightDp = 40f
    const val readerJumpTrackHeightDp = 3f
    const val readerJumpThumbDp = 8f
    const val readerJumpBubbleHeightDp = 28f
    const val readerJumpBubbleWidthDp = 240f
    const val readerJumpBubbleInsetDp = 6f
    const val readerJumpPercentStep = 0.01f
    const val readerJumpEntryWidthDp = 280f
    // T350: the always-visible page badge in the corner of the reading surface. The chip is tiny;
    // the touch target behind it is the standard 48 dp.
    const val readerPageBadgeInsetDp = 8f
    const val readerPageBadgeHorizontalPaddingDp = 8f
    const val readerPageBadgeVerticalPaddingDp = 4f
    const val readerPageBadgeCornerDp = 8f
    // T209: the Files search mode lists a lazy, bounded set of files above the field.
    const val searchFilesMaxHeightDp = 320f
    const val searchFilesRowGapDp = 4f
    // T208: PDF rendering. Pages render at screen width but never taller than a factor of the
    // screen, so a very tall page cannot allocate an unbounded bitmap. Only a small window of
    // neighbours is kept; evicted bitmaps are recycled. Zoom and the scrubber are gestures, not
    // stored values.
    const val pdfMaxRenderHeightFactor = 2.0f
    const val pdfCacheMinPages = 2
    const val pdfCacheMaxPages = 5
    const val pdfCacheMaxBytes = 32 * 1024 * 1024
    /** T235: a zoomed render may use at most half the cache budget for one page (ARGB_8888). */
    const val pdfMaxRenderPixels = pdfCacheMaxBytes / pdfCacheMinPages / 4
    /** T235: the zoom limit, and a sharper re-render once a pinch or double tap settles. */
    const val pdfMaxZoom = 5f
    const val pdfDoubleTapZoom = 2f
    const val pdfZoomLabelThreshold = 1.01f
    /** T235: a pan is at the page edge when it is this close to its limit, in pixels. */
    const val pdfPanEdgeSlopPx = 1f
    const val pdfPageGapDp = 8f
    const val pdfScrubberHitDp = 44f
    const val pdfScrubberTrackDp = 3f
    const val pdfScrubberThumbDp = 7f
    // T210: reader motion. A drag turns a page at 18 % of the screen width; a flick turns it
    // earlier. At the first/last page the finger meets quiet resistance. Pulling from the top
    // edge closes the reader once the page has moved 28 % of the way into its cover (or with a
    // downward flick). The cover overlay only appears near the end of the close.
    const val readerPullZoneFraction = 0.18f
    const val readerPullDistanceFraction = 0.55f
    const val readerPullCloseFraction = 0.28f
    const val readerPullFlingPxPerSec = 1_200f
    const val readerPageTurnFraction = 0.18f
    const val readerPageFlingPxPerSec = 900f
    const val readerPageEdgeResistance = 0.18f
    const val readerCoverRevealStart = 0.62f
    const val readerCoverHoldMaxMs = 1_500L
    const val readerShelfSettleScale = 0.96f
    const val readerShelfSettleAlpha = 0.60f
    // T236: highlights, selection handles, the quiet bookmark ribbon and the Marks panel. The
    // highlight colours themselves are skin tokens; only their wash strength is geometry.
    const val readerHighlightAlpha = 0.38f
    // T257: the follow-along highlight under the spoken word or sentence.
    const val readerSpeakingAlpha = 0.24f
    const val readerSelectionAlpha = 0.26f
    const val readerSelectionHandleDp = 9f
    const val readerSelectionHandleGrabDp = 30f
    const val readerSelectionMenuInsetDp = 12f
    const val readerSelectionSwatchDp = 34f
    const val readerMarkRibbonWidthDp = 18f
    const val readerMarkRibbonHeightDp = 28f
    const val readerMarkRibbonInsetDp = 10f
    const val readerMarkRibbonNotchFraction = 0.72f
    const val readerMarkRibbonAlpha = 0.82f
    const val readerSelectionHaloDp = 1.5f
    const val readerMarkRowSwipeDp = 72f
    const val readerMarkRowMaxLines = 2
    // T395: the editable text on Report a problem; tall enough to read the template without scrolling inside the field.
    const val reportFieldMinHeightDp = 240f
    // T396: the thin download bar in the hub's update row.
    const val updateProgressDp = 3f
}
