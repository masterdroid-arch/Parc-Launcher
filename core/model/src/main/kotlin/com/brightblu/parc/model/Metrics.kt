package com.brightblu.parc.model

/** Thread-safe immutable dp tokens from DESIGN §2.3; no nullable values. */
object Metrics {
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
    const val panelGutterDp = 16f
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
    const val commsActionDp = 40f
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
    const val hubGripLineDp = 4f
    const val hubDotActiveWidthDp = 20f
    const val hubNameSp = 14f
    const val hubClockSp = 64f
    const val hubValueSp = 40f
    const val hubLineSp = 15f
    const val hubNoteSp = 12f
    const val hubDotsHeightDp = 48f
    const val hubFadeHeightDp = 12f
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
    // T37: 8 characters at 11 sp need about 56 dp; the caption is still clipped by the one-line rule.
    const val folderLabelMaxWidthDp = 56f
    const val folderLabelGapDp = 0f
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
    const val searchPullCancelMs = 160
    const val searchPullBarHeightDp = 48f
    const val searchDimAlpha = 0.15f
    const val searchIdleAlpha = 0.6f
    const val collapsedScale = 0.3f
    const val outgoingFadeEnd = 0.45f
    const val incomingFadeStart = 0.35f
    const val folderRadius2Dp = 200f
    const val folderRadius3Dp = 268f
    const val folderRadius4Dp = 360f
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
    const val hubTapTravelDp = 12f
    const val keepOutTopDp = 56f
    const val keepOutRightDp = 48f
    const val releaseNotesMaxHeightFraction = 0.85f
    const val keepOutBottomDp = 56f
    const val keepOutLeftDp = 16f
    const val textGutterDp = 20f
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
    const val folderTransitionMs = 360
    const val folderStaggerMs = 18
    const val folderStaggerMax = 8
    const val homeRecedeScale = 0.92f
    // T37: the home behind an open folder is a 6 % ghost; its labels are dropped while a folder is open.
    const val folderGhostAlpha = 0.06f
    const val folderOvershoot = 1.4f
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
    // Dial rotation (T29). Band/slop are dp; gain is slots per (slots/s); stiffness is ω².
    const val rotateBandDp = 28f
    const val rotateSlopDp = 10f
    const val rotateFlingGain = 0.45f
    const val rotateSpringStiffness = 180f
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
    const val hudCellGapDp = 8f
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
