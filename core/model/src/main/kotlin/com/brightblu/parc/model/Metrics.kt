package com.brightblu.parc.model

/** Thread-safe immutable dp tokens from DESIGN §2.3; no nullable values. */
object Metrics {
    const val hubGlassTintAlpha = 179 // 70 percent; cached frost only.
    const val hubCardMinContrast = 4.5 // T171: ink on a hub card at the lowest opacity the user may pick
    // Settings gallery and swipe-back arbitration (all distances in dp).
    const val settingsCardWidthDp = 272f
    const val settingsCardHeightDp = 440f
    const val settingsEdgeZoneDp = 112f
    const val settingsExitTravelDp = 48f
    const val settingsExitVelocityDp = 700f
    const val settingsAxisRatio = 1.5f
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
    const val settingsValueMaxWidthDp = 140f
    const val settingsChipMinWidthDp = 76f
    const val settingsPreviewDebounceMs = 120L

    const val noteSaveDebounceMs = 400L
    const val noteUndoMs = 8_000L
    const val notePreviewLines = 3

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
    const val focusSaveDebounceMs = 400L
    const val focusUndoMs = 8_000L
    const val focusControlDp = 48f
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
    const val commsMaxLines = 3
    const val commsPromptLines = 1
    const val commsRecentMessages = 3
    const val commsHeaderSp = 11f
    const val commsMessageSp = 13f
    const val commsMessageGapDp = 16f
    const val commsActionDp = 48f // T166: raised from 40 to the minimum touch target
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
    // T158 (DESIGN 3.4): the hub's tab strip. A pill is half the 48 dp touch row; its corner is half its height.
    const val hubTabPillHeightDp = 24f
    const val hubTabPillPadDp = 12f // text to the pill edge, left and right
    const val hubTabGapDp = 4f // between pills; the touch rows of neighbours meet, the pills do not
    const val hubTabSelectedMix = 0.24f // share of `accent` mixed into `surface` for the selected pill
    const val hubTabNameSp = 13f
    /** T167: room kept between a stats page's last row and the bottom fade at the end of its scroll. */
    const val hubScrollEndGapDp = 16f
    /** T167: a drag is a page swipe only when it is this many times wider than tall; otherwise it scrolls. */
    const val hubDirectionLockRatio = 1.25f
    const val hubPageSwipeDp = 48f
    const val hudDotDp = 8f
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
    const val settingsRowMinHeightDp = 56f
    const val settingsRowInsetDp = screenGutterDp // Settings rows: left, right, and the trailing switch or value
    const val menuRowHeightDp = 48f
    const val menuCornerDp = 20f
    const val menuMinWidthDp = 220f
    const val menuGapDp = 8f
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
    const val hintCardTopDp = 96f
    const val hintCardLineGapDp = 10f
    const val hintCornerDp = 20f
    const val editNoticeMs = 2_500L
    const val snackbarMs = 3_000L
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
}
