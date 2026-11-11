# YT – code map

The code map for whoever changes the code: where each part lives, how the parts connect and what
broke before. Read only the section of the area you touch: its start line is printed at session
start, or run `python3 -I scripts/docs_sync.py --toc docs/ARCHITECTURE.md`.

Rules for this file: every YT Kotlin file (`Yt*.kt` anywhere, everything under `Y/`) is named
here, by file name, class stem or a glob such as `YtMiniClay*.kt`; `--check` fails otherwise.
Facts are sorted by area, never by round (history lives in git). Blocks between
`<!-- gen:… -->` and `<!-- /gen:… -->` are generated: do not edit them, run
`python3 -I scripts/docs_sync.py --write`.

Short forms: `J/` = `app/src/main/java/com/github/libretube/`, `Y/` = `J/ui/yt/`,
`R/` = `app/src/main/res/`, `T/` = `app/src/test/java/com/github/libretube/`.
"Package A–E" = the five feature packages of round 12, still used in file comments and pref
files: A mini player, B minimize drag, C player gestures, D home and feed, E comments scene.

## Contents

<!-- gen:toc -->
- **Basics** – Fork facts, conventions, the module hooks every feature plugs into, shared state.
- **Designs** – The design enum, palettes, fonts, motion, haptics, glass, navigation, headers, design switch.
- **Mini player** – Docked mini player per design, peek, minimize drag, throw, parked page, restore, soft close.
- **Player page** – Watch pages, player gestures (package C), ratings, comments scene, rotation and fullscreen.
- **Playback service** – Service, modules, queue, positions, speed, loudness, music, battery, floating player.
- **Home, feed, search, shorts** – Tabs and screens, feed engine and rules, cards, watched marks, titles, search, Shorts.
- **Sound, Frequency, Synthwave** – Sound engine and the two sound-reactive designs.
- **Account, update, settings, diagnostics** – Sign-in, in-app update and release notes, settings screens, guide, crash report, storage, security.
- **Build, CI, tests, docs system** – Local build, workflows, emulator checks, tests, the docs check.
- **Pitfalls** – Mistakes that happened more than once: check them in every review.
- **File inventory** – Generated list of every YT Kotlin file by package.
<!-- /gen:toc -->

## Basics

> Fork facts, conventions, the module hooks every feature plugs into, shared state.

- Fork of LibreTube 32.1 (commit 9380ab045). Package `app.tra1c0.yt`, version name `32.1-yt.N`,
  version code `72*100000 + N` (N = CI run number, env `YT_BUILD_NUMBER`).
- Kotlin, Views + Jetpack Compose, media3 (version in `gradle/libs.versions.toml`), minSdk 26,
  targetSdk 36. Comments in English, plain, explaining why.
- YT code goes into new `Yt*` files (or under `Y/`); upstream LibreTube files get small hunks
  only, so upstream merges stay possible. Every change to an upstream file gets a row in
  `MODIFICATIONS.md` (German GPL change list; columns date, change, `**LibreTube:**` files /
  `**YT:**` files).
- Strings: `R/values/yt_*.xml` and the same file with the same keys in `R/values-de/` (checked by
  `scripts/check-strings.sh`). Newer files are named per round and topic (`yt_r24_synthwave_strings.xml`).
- Page modules (player page side): `Y/player/YtPlayerHost.kt` (PlayerFragment implements it),
  `Y/player/YtPlayerModules.kt` (fixed module list: mini `YtMiniModule`, minimize
  `YtMinimizeModule`, player gestures `YtPlayerGestureModule`, scene `YtSceneModule`, battery cue
  `YtBatteryCueModule`). Module hooks include `onConfigurationChanged`.
- Service modules: `J/services/YtServiceModules.kt` (commands travel in the `yt_cmd` bundle key);
  modules `YtLoopModule`, `YtScrubModule`, `YtSessionModule` (time box), `YtSoftCloseModule`,
  `YtBatteryQualityModule`, `YtSoundModule` (details in their areas).
- Activity hooks: `Y/YtActivityHooks.kt` (main activity lifecycle for round-12 features; package D
  opens the intent chooser on a cold start and after 30 min away; empty, it changes nothing).
- Shared state: `Y/YtDock.kt` (dock progress, peek, slab, hover, drops), `Y/YtDrag.kt` (drag
  sessions, `miniBounds`, drop cards, `YtDragLayer` overlay that never takes touches),
  `Y/YtPlaybackClock.kt` (position flow + tagged seeks), `Y/YtOverlays.kt` (open-overlay counter).
- Settings screen "Gesten & Interaktion": `J/ui/preferences/YtInteractionSettings.kt`, root
  `R/xml/yt_interaction_settings.xml`, one `R/xml/yt_r12_*_prefs.xml` per package
  (`a`…`e`, `search`, `shorts`).
- Other docs (read only when the task concerns them): `docs/DESIGNS.md` (designs in detail,
  German), `docs/DESIGN-MASSE.md` (reference measures), `docs/BUTTONS.md` (inventory of every
  control), `docs/KONTO.md` (sign-in), `docs/SICHERHEIT.md` (security), `docs/INSTALLATION.md`,
  `docs/RUNDE-7.md` (motion rules of round 7).

## Designs

> The design enum, palettes, fonts, motion, haptics, glass, navigation, headers, design switch.

<!-- gen:designs -->
14 designs (enum `YtDesign`, in picker order): `STANDARD`, `YOUTUBE`, `MOVO`, `AFTER`, `SIDE`, `CLAY`, `EDITORIAL`, `GLASS`, `POP`, `SHAPES`, `FROST`, `KIESEL`, `FREQUENCY`, `SYNTHWAVE`.
<!-- /gen:designs -->

- `J/helpers/YtDesign.kt`: the enum (key, overlay style, brand font, `labeledNavigation`, `light`),
  plus `hasShortsTab`, `soundReactive`, wordmark and timeline colour. Pref `yt_design`. `STANDARD`
  = classic LibreTube look; YT code mostly checks `palette == null`. User-facing names: Formen =
  `SHAPES`, Glas = `GLASS`, YouTube neu = `YOUTUBE`.
- A new design: add it to `T/ui/yt/YtDesignRegistryTest.kt` (checks every design has palette,
  signature, mini player, nav, search kind, picker entry) and everywhere the compiler and that test
  point to.
- `Y/YtTheme.kt`: `YtPalette` per design (`YtPalettes.*`, dark variants of the light designs via
  `ytDayNight`), `YtFonts`, `ytScale` (width scaling, see `docs/DESIGN-MASSE.md`), `YtSignature`,
  `YtTheme()` composable.
- Light/dark: `J/helpers/YtDesignDark.kt` (pref `yt_design_dark` L/D/A) sets the night mode in
  `BaseActivity.attachBaseContext`; `J/helpers/YtThemeRefresh.kt` bumps a generation so activities
  rebuild without a restart; `J/helpers/YtAppearanceOptions.kt` decides which settings rows show.
- Design switch without restart: `BaseActivity.ytApplyNewLook()` recreates while in front, so the
  window stays attached and MotionLayout copies its start/end sets from the XML before
  `YtNav.attach`/`float()` changed the constraints. `YtMotionAccess.takeViewsAgain`
  (`app/src/main/java/androidx/constraintlayout/motion/widget/YtMotionAccess.kt`, called at the end
  of the YT layout setup in `MainActivity.onCreate`) reloads the scene when already attached
  (Pitfall P3).
- Motion: `Y/YtMotion.kt` (`YtMotionPrefs`, `ytSpec` snaps when motion is off). Gesture toolkit
  `Y/YtGestures.kt` (`ytRubberBand`, `ytProject`, `ytSwipe`); phone tilt `Y/YtTiltSensor.kt`
  (`rememberYtGravity`, `ytLightAngle` for the glass light edge). Motion rules: `docs/RUNDE-7.md`.
- Haptics: `Y/YtHaptics.kt` (+ `Y/YtVibes.kt`, `Y/YtHapticTexture.kt`, per-design `*Textures.kt`),
  pref `yt_haptics`.
- Glass: vendored Kyant backdrop (`third_party/backdrop/`, sources under
  `app/src/main/java/com/kyant`), `Y/YtLiquidKit.kt`, `Y/YtLiquidNav.kt`, `Y/liquid/`
  (`DampedDragAnimation.kt` selection spring, `InteractiveHighlight.kt` glow under the finger,
  `LiquidGestures.kt` press/drag tracking, `YtViewBackdrop.kt` the screens below the floating nav
  as the glass backdrop).
- Classic screens in the design: `R/values/yt_classic.xml` (+ `values-night`), `Y/YtClassic.kt`;
  layouts `R/layout/yt_preference*.xml`.
- Shared shapes and surfaces: `Y/YtShapes.kt` (`CookieShape`, `BlobShape`, `MorphRoundRectShape`,
  `WavyBarShape`, `FeatureShape`), `Y/YtSurfaces.kt` (pills and cards of the shared screens per
  design), `Y/YtPebbles.kt` (Kiesel pebble physics), `Y/YtImages.kt` (largest thumbnail for
  full-bleed images), `Y/YtContrast.kt` + `Y/YtInk.kt` (text contrast, Pitfall P1).
- Navigation: `Y/YtNavBar.kt`, `Y/YtNavDesigns.kt`, long press `Y/YtNavHold.kt`;
  `J/ui/views/YtBottomNavigationView.kt` (floating bar: touches beside the drawn bar reach the
  content). Headers: `Y/YtHeader.kt` (`YtChrome` holds the search callbacks; `overlayHome`).

## Mini player

> Docked mini player per design, peek, minimize drag, throw, parked page, restore, soft close.

- Framework `Y/mini/`: `YtMiniDesign` (per-design object: geometry, `constrain`, `Under`/`Over`
  composables, motion, peek, haptics, `underAlpha`/`overAlpha`, `land()`), `YtMiniHost` (wires it
  into the MotionLayout end/peek sets, publishes bounds, crop, landing), `YtMiniGeometry` (pure px
  geometry, tested), registry `YtMiniDesigns`, neutral parts `YtMiniParts` (preview, drop hover,
  glyph flash), vocabulary `YtMiniLook` (marks, bars, frames, landings), moments with a feel
  `YtMiniTextures`, page module `YtMiniModule` (package A: long-press preview, double tap, next
  video, drop feedback), touches handed over by MotionLayout `YtMiniTouch`, dock maths
  `YtDockMath`, bar corner under a docked player `YtMiniDockBar`.
- The mini player as part of the nav bar: `Y/YtMiniPlayer.kt` (`YtMiniPlayer.attach`, three
  joints `YtMiniJoint`: stacked, bulging, tucked).
- One family of files per design in `Y/mini/` — `YtMini` + design name: the design object, `…Layout` px
  geometry, `…Under` body composable, `…Over` layer over the picture, `…Textures` haptics,
  `…Parts`/`…Math` helpers:
  - Standard (classic LibreTube look + preview): `YtMiniStandard.kt`
  - YouTube neu "Rote Naht": `YtMiniYouTube.kt`, `YtMiniYoutube*.kt` (`YtMiniYoutubeParts` is
    shared with Frequency)
  - Movo `YtMiniMovo*.kt`, After `YtMiniAfter*.kt`, Side `YtMiniSide*.kt`, Clay `YtMiniClay*.kt`,
    Editorial `YtMiniEditorial*.kt`, Pop `YtMiniPop*.kt`, Formen `YtMiniShapes*.kt`, Frost
    `YtMiniFrost*.kt`, Kiesel `YtMiniKiesel*.kt`, Frequency `YtMiniFrequency*.kt`, Synthwave
    `YtMiniSynthwave*.kt`
  - Glas: `YtMiniGlass.kt` (slab shared with the Glas bar), `YtGlassLayout.kt`, `YtGlassOver.kt`,
    `YtGlassSlabShape.kt`; soft edges copy video frames with PixelCopy: `YtMiniBleed.kt` +
    pure `YtBleedMath.kt`.
- Video limits: the SurfaceView is clipped by an outline only (round rect; path on newer APIs);
  shapes like the Formen cookie are drawn over the video in the body colour (Pitfall P5).
- Touch on the player page: `J/ui/views/SingleViewTouchableMotionLayout.kt`, a state machine (IDLE,
  PENDING, LEGACY, PEEK, DRAG) with delegates `MiniTouch` and `MinimizeDrag`; with no delegate it
  behaves like upstream. A drag whose UP never came is released at the next DOWN.
- Peek: `yt_peek_set` / `yt_peek` in `R/xml/player_scene.xml`, spring-driven, listener fan-out
  muted while peeking.
- Minimize drag (package B) under the finger: `Y/mini/YtMinimizeDrag.kt` + pure solver
  `Y/mini/YtUnderFinger.kt`, module `Y/mini/YtMinimizeModule.kt` (`onConfigurationChanged` drops
  remembered places), `Y/mini/YtMinimizeGeometry.kt` (`drivable`: docked ≥ 48 dp below open,
  guards every drag start, else the classic drag takes over).
- Dock offset: the activity container's dock offset is re-applied by `YtMotionAccess.reapply()`
  when the bar's height changes; `MainActivity.writeContainerKeys()` re-writes the dock keyframes
  after `takeViewsAgain` reloads (Pitfall P4). The container (20 dp) sits above the bar (4 dp): a
  mini player half BEHIND the bar means the page is shifted down and cut by `clipToBar`, not a
  wrong dock offset.
- Landscape / rotation: a page re-created for the same player starts through `Y/mini/YtPageStart.kt`
  once attached (docked again if `PlayerViewModel.ytDocked`).
- Throw away: `clipToBar` cuts while `YtDockMath.clipsToBar` says so (the bar's `YtDock.progress`
  OR the page's own transition at rest at the end, not peeking). `undock()` lets the bar go in a
  300 ms animator while the page still falls. A throw always ends closed
  (`YtDockMath.throwOverdue`, 1500 ms, plus a posted 1600 ms fallback); the classic
  `closeMiniPlayer` closes on animation end or a 600 ms fallback.
- Parked page: `YtMiniHost.watchParked` (in the preDraw `boundsCheck`) moves a docked page that
  rests at its end but is still shifted, with no owner (drag/throw/rise), back to 0 after 300 ms
  (`YtDockMath.parked`, `PARKED_MS`).
- Restore ("Abos" held after a close): `Y/mini/YtMiniRestore.kt` (+ pure `YtMiniRestoreRules.kt`)
  keeps the closed session 10 min in memory; `ytRememberClosed()` at the 4 close points in
  PlayerFragment, `forget()` in `NavigationHelper.navigateVideo`. The restart waits for the old
  service's end (`AbstractPlayerService.onDestroy` → `YtMiniRestore.serviceGone()`), at most the
  1.5 s settle time since the close, so the dying service does not clear the restored queue.
  Long press: `ytNavHoldArea` on the bar, `ytNavHoldTarget(id)` on the Abos tab (initial pass);
  classic bar via `setOnLongClickListener`. Starts docked through
  `YtPageStart.start(docked = true)` (`takeStartDocked`); the host's dock haptic is muted once.
  Step times (`YtMiniRestore.mark`) show in Diagnose as "Mini restore"
  (hold/navigate/page/armed/docked|fallback/rise/landed, ms since the long press): ask the user
  for that line when a restore feels slow.
- Rise animation: `YtPageStart.start(rise = true)` → `YtMiniHost.armRise` hides the page after
  attach, the docking runs unseen, then `startRise` moves it up from behind the bar (reverse of the
  throw-away, cut by `clipToBar`) with the design's minimize curve, then `design.land()`. Pure
  rules `Y/mini/YtMiniRiseRules.kt`; motion off or rotation → no rise. The rise also starts 450 ms
  after `armRise` if the docking's completion has not come.
- Soft close (60 s): page side `Y/mini/YtSoftClose.kt` (own MediaController keeps the service,
  60 s timer, resume, release, `beforeNavigate` gate in `NavigationHelper.navigateVideo`), service
  side `J/services/YtServiceSoftClose.kt` + `YtSoftCloseModule` (commands `soft_close`/`soft_open`),
  pure `Y/mini/YtSoftCloseRules.kt`. The fragment is removed as on a normal close; only service,
  ExoPlayer, buffer and queue live on. Soft close: pause, audio focus off, `removeSession` (media3
  drops the notification and foreground), backstop end after 65 s. While soft-closed the session
  forwarder ignores play/next/prev, media buttons are swallowed, other packages get no session,
  `onTrimMemory` (moderate and worse) releases. Resume: new PlayerFragment with `alreadyStarted`,
  docked + rise, `soft_open(play)` once its controller connects. Another video: the kept player is
  released first, navigation reruns on `serviceGone` (max 1.5 s, then the START path reuses the
  service and ends the soft close). Only online non-live videos in READY/BUFFERING are kept.
  Debugging: anything odd around closing, restoring or media buttons → look here first.

## Player page

> Watch pages, player gestures (package C), ratings, comments scene, rotation and fullscreen.

- Watch pages: `Y/YtWatchPages.kt`, `Y/YtWatchDesigns.kt` (per-design parts, e.g.
  `FrequencySubscribe`), `Y/MovoWatch.kt`, `Y/SideWatch.kt`, `Y/YouTubeWatch.kt`, attach
  `Y/YtWatch.kt` (`YtWatch.attach`). Edge under the video: `Y/YtWatchEdge.kt` drawn on
  `J/ui/views/YtEdgeScrollView.kt` (the scrolling area below the player). Video colours under the
  player: `J/helpers/YtVideoColors.kt`. Side insets `Y/YtSideInsets.kt`; cutout strips painted by
  `Y/YtSideFill.kt`.
- Time bars `J/ui/views/{DismissableTimeBar,MarkableTimeBar,ChapterTimeBar}.kt`; upstream gestures
  `J/ui/listeners/PlayerGestureController.kt`. Fullscreen moves the player into a plain Dialog:
  overlays there are Views (no Compose owners).
- Player gestures (package C) in `Y/player/`: module `YtPlayerGestureModule`, switches
  `YtPlayerGesturePrefs` (`R/xml/yt_r12_c_prefs.xml`), HUD look `YtPlayerHudStyle`, HUD spring
  `YtSpring`.
  - Time magnifier: levels `YtScrubPrecision`, lens `YtScrubLensView`, live picture `YtScrubLive`
    (with the service's `YtScrubModule`: fast seeks, no sound).
  - Rewind memory: rule `YtRewindMemory`, page glue `YtRewindChip`, chip `YtReturnChipView`.
  - Explanation loop: `YtLoopPlan`, `YtLoopController`, `YtLoopSheet`
    (`R/layout/yt_r12_c_loop_sheet.xml`), `YtLoopChipView`, saved loops `YtLoops`; the service
    runs it (`YtLoopModule`).
  - Hold and drag on the video: `YtHoldGesture`, `YtHoldDirection`, `YtHoldLayout`,
    `YtHoldTilesView` (scales the content frame, never the surface), `YtHoldTileStyle`.
  - Share onto the timeline: rules `YtSharePlan`, `YtShareScrub`, `YtShareScrubSource`,
    token `YtShareTokenView`.
  - `YtPlayerTouch` tracks finger + long press for `PlayerGestureController`; `abort()` ends a
    gesture when `useController` turns off mid-gesture (the minimize drag disables the controller
    before the parent's CANCEL arrives).
- Chapter double tap: pure `Y/player/YtChapterTap.kt` (reuses `YtHoldDirection` chapter maths),
  label `Y/player/YtChapterTapLabel.kt`, pref `yt_double_tap_chapter`; `CustomExoPlayerView`
  rewind/forward take an optional chapter target, `PlayerGestureController.ytDoubleTapFraction`.
- Ratings: `Y/YtRateHold.kt` (`ytRateHold`, like/dislike hold tiles), `Y/YtRatings.kt` (pref
  `yt_ratings`), InnerTube like endpoints in `Y/account/YtInnerTube.kt` (not verified against
  YouTube; Movo and Side have no like button).
- RYD dislikes: fetched in `NewPipeMediaServiceRepository` (`ExternalApi.getVotes`); classic
  `DescriptionLayout` (STANDARD) shows them; in the designs pure `Y/YtSentiment.kt` (share, colour
  stops, per-design bar) drawn by `ytRateHold`; 600 ms hover reveals the count in the dislike tile.
- Comments follow the scene (package E): `J/helpers/YtCommentMoments.kt`, spoiler mark
  `J/helpers/YtSceneUnlock.kt` (grows only by watching), state `J/ui/models/CommentScene.kt`,
  `Y/scene/` (`YtSceneModule` feeds the clock, `YtSceneCompose` chips/reveal/text, `YtSceneSheet`
  scene part of `CommentsMainFragment`), adapters `J/ui/adapters/CommentsSceneAdapter.kt`,
  `CommentRowBinder.kt`. Private notes at a video position: `J/helpers/YtNotes.kt`.
- Landscape / fullscreen exit: MainActivity handles rotation itself (manifest `configChanges`, Pitfall
  P11). `unsetFullscreen(rebuildAfter = true)` rebuilds the page via `restartActivityIfNeeded`,
  decided by pure `Y/player/YtPageLayoutRules.kt` (uses the orientation locked NOW, not the
  setting). `PlayerViewModel.ytWindowed` keeps the page windowed while landscape (set by every user
  exit, cleared by portrait or explicit fullscreen; `YtPageLayoutRules.windowedAfter`,
  `autoFullscreenOnChange` = only a real rotation). The fullscreen Dialog's back also rebuilds; no
  portrait request during a recreate (`BaseActivity` checks `lastNonConfigurationInstance`).

## Playback service

> Service, modules, queue, positions, speed, loudness, music, battery, floating player.

- `J/services/AbstractPlayerService.kt`, `OnlinePlayerService.kt`, `OfflinePlayerService.kt`;
  notification `J/util/NowPlayingNotification.kt`.
- `J/services/YtPlaybackGuards.kt`: `YtForegroundHold` (keeps the service in the foreground while
  `navigateVideo` clears and reloads the player, Pitfall P8) and `YtLiveRecovery` (up to 3 retries
  for live errors). Fallback notification `J/services/YtResumeNotification.kt`.
- VOD errors 1000/1004/4001/4003/5001/5002 → `prepare()` again via `J/services/YtPlayRecovery.kt` +
  `AbstractPlayerService.tryRecover`. Before this, the service ended itself on any IDLE error while
  the page called play(), leaving a running clock over a dead player.
- Offline audio-only without an audio file plays the video file's sound
  (`J/services/YtOfflineAudio.kt`).
- Time box in the service (package D): `J/services/YtSessionModule.kt` (counts played time, planned
  end points as PlayerMessages, holds autoplay when the budget is used).
- Queue: `J/util/PlayingQueue.kt` (+ `revision`, `upcomingCount`, `ytInsertNext`, `ytAppend`,
  `ytKeepQueue`), pure rules `J/util/YtQueueEdit.kt`; `PlayerData.keepQueue` honoured in
  `NavigationHelper`. Cards handed to the queue: `Y/YtQueue.kt` + rule `Y/feed/YtQueueRule.kt`.
- Watch positions: `J/services/YtPositionLedger.kt` (owned by AbstractPlayerService, listener on the
  player, 5 s tick on the service handler while playing, coalesced IO writes through the existing
  `updateWatchHistoryEntry`), pure `J/services/YtPositionRules.kt` (persist decision, < 5 s only
  after a seek, finished = DatabaseHelper rule or ≥ 95 %, switch/transition snapshot choice).
  The video id comes from the media item's extras, a snapshot only after that item was READY.
  Triggers: tick, pause, seek, `navigateVideo` (`beforeSwitch`), media item transition, ended,
  error, soft close, onDestroy (immediate), onTrimMemory. `saveWatchPosition()` flushes the ledger;
  `PlayerHelper.saveWatchPosition` is unused. With a sync server every write is an HTTP call.
  Shorts are excluded on purpose.
- Speed/pitch: `J/helpers/YtSpeedRules.kt` (clamps, slider grid, `YtThrottle` 150 ms in
  `PlaybackOptionsSheet`; Material Slider throws for values off its step grid). Speed per channel:
  `J/helpers/YtChannelSpeed.kt` (pref `yt_channel_speeds`), applied in
  `OnlinePlayerService.applyChannelSpeed`, list in `J/ui/preferences/YtChannelSpeedsPreference.kt`.
- Loudness: pure DSP `J/helpers/YtLoudnessMeter.kt` (BS.1770 gated), `J/helpers/YtPeakLimiter.kt`
  (look-ahead, −1 dBTP), `J/helpers/YtLeveller.kt`; media3 glue `J/helpers/YtLoudness.kt` (prefs
  `yt_loud*`, processor after Sonic). Set per-format state in `onFlush`, not `onConfigure`;
  `isActive` is fixed per configure, so switching off takes effect at the next video. The limiter
  tail goes out once per end of stream (`tailSent`, Pitfall P10; test `YtLoudnessDrainTest`).
  NewPipe exposes no loudness value.
- Music: `J/util/YtMusicRules.kt` (also used by `YtFeedEngine.isMusic`),
  `J/helpers/YtMusicPlayback.kt` (prefs `yt_music_audio_background`, `yt_music_from_start`);
  `OnlinePlayerService.configurePlayer` skips the watch-position restore for music.
- Battery: pure `J/helpers/YtBatteryRules.kt` (cap hysteresis, crossings, vibration tables,
  colours `YtBatteryCues`), prefs/receivers `J/helpers/YtBattery.kt` (`yt_battery_*`). Quality cap in
  the service module `J/services/YtBatteryQualityModule.kt` via `YtBatteryCapWatch` (in
  `YtBattery.kt`: battery, power saver and `yt_battery_*` prefs, alive for the player's whole life,
  state read synchronously so the first SET_RESOLUTION is capped); a manual pick exempts only its
  own height (`YtBatteryCapRules.pickAfter/exempt`). The Shorts player has its own watch
  (`YtShortsPlayer.applyBatteryCap`). The quality sheet shows the cap
  (`Y/player/YtBatteryCapLabel.kt`). Android drops USAGE_NOTIFICATION vibration in battery saver
  and silent mode: `YtBatteryVibeRules` decides, and the cue then shows a stronger visual instead
  (no ALARM-usage workaround). Cue in the page module `Y/player/YtBatteryCueModule.kt` +
  `Y/player/YtBatteryCueView.kt` (scales the video frame, never the surface), vibration
  `Y/YtBatteryVibes.kt`. Preview dialog `Y/YtBatteryPreview.kt` (reuses YtBatteryCueView +
  YtBatteryVibes), pure timing/notes `J/helpers/YtBatteryPreviewRules.kt`.
- Floating player + sound wave: `Y/YtFloatingPlayer.kt` (TYPE_APPLICATION_OVERLAY window,
  `PlayerView.switchTargetView`, `R/layout/yt_floating_player.xml`), `Y/YtWaveView.kt` (CPU-drawn:
  GPU path programs stalled the render thread), pure wave shape `Y/YtWaveShape.kt`, analysis
  `J/helpers/YtAudioTap.kt` + `J/helpers/YtSpectrum.kt`; fallback `Y/YtPipVisualizer.kt`. Needs
  SYSTEM_ALERT_WINDOW; Android 15 locks it for APK installs ("restricted settings").
- Queue survives restarts: pure `J/util/YtQueuePersist.kt` (versioned JSON `YtSavedQueue` v1,
  trim to 500 items, resumeIndex, write debounce, end action, restore decision, join), store
  `J/util/YtQueueStore.kt` (`filesDir/yt_queue.json`, atomic tmp+rename on one background thread;
  watches `PlayingQueue.revision`, writes 500 ms after the last change / ≤ 3 s; flushes on service
  onDestroy, onTrimMemory, soft close, activity onStop; deleted ONLY by the queue sheet's "clear"
  or when the queue played to its end via `itemEnded`; an empty queue is never written), resume
  `Y/YtQueueResume.kt` (restore on process start / fresh activity only when PlayingQueue is empty
  and no service runs; never autoplays; home pill "Weiter: … · N" in `YtHomePills`; a normal start
  goes before the restored current item, `NavigationHelper` join hook). Positions come from the
  position ledger, not this file. `YtMiniRestore` uses the store's copy (one source of truth).
  `AbstractPlayerService` clears the queue only on a service's FIRST onDestroy and only if no newer
  service runs (Android calls onDestroy twice). Limit: starting a download replaces a waiting
  restored online queue (online/offline never mix in one queue).

## Home, feed, search, shorts

> Tabs and screens, feed engine and rules, cards, watched marks, titles, search, Shorts.

- Homes: `Y/*Home.kt` (one per design), data dispatch `Y/YtHomeRound2.kt`, `Y/YtHome.kt`, shared
  kit `Y/YtHomeKit.kt` (`YtHomeData`, `rememberYtVisible`, item motion, durations/views),
  discovery when subscriptions are thin `Y/YtDiscovery.kt`, Kiesel's discover screen
  `Y/KieselDiscover.kt`, pull to refresh per design `Y/YtRefresh.kt` (`YtRefreshBox`).
- Other tabs: Live `Y/YtLive.kt`; After's "Räume" `Y/YtRooms.kt` + `Y/AfterRooms.kt`; Abos tab screen
  `Y/YtSubscriptions.kt`; Shorts tab grid `Y/YtShorts.kt`; personal area "Du" `Y/YtLibrary.kt`,
  `Y/YouTubeLibrary.kt` (`YouTubeLibraryScreen`, continue watching), `Y/YtLibraryDesigns.kt`
  (per-design screens, `YtScreenSurface`).
- Feed engine `Y/feed/`: `YtFeed` (sources and extras, `loadTopics`), `YtFeedEngine` (pure ranking,
  `passes`, `rank`, `isMusic`), `YtFeedRules` (JSON pref `yt_feed_rules`, incl. `hideSeen` and
  `YtFeedPersonal.feedTopics`), `YtFeedFresh` (seen-on-top memory `yt_feed_seen`), `YtTimeBox` +
  `YtSession` (time box), intents. Sheet ("Feed-Regler") `Y/YtFeedSheet.kt`, its buttons
  `Y/YtFeedButtons.kt`; intent chooser `Y/YtIntentChooser.kt`, when it opens `Y/YtIntentTrigger.kt`
  (pref `yt_intent_ask`); time box UI `Y/YtTimeBoxUi.kt`, end card `Y/YtTimeUp.kt`.
- Feed topics: `YtFeedTopic` in `YtFeedPersonal.feedTopics` (same `yt_feed_rules` JSON; holds for
  all intents), session rule `Y/feed/YtFeedTopicsSession.kt` (30 min away), fetch
  `YtFeed.loadTopics` via `searchVideos`, mixing `YtFeedTopics.mix` (share accumulator, spread,
  dedup) inside `YtFeedEngine.rank`, keyword scorer `Y/feed/YtFeedTopicsWords.kt`, card chip in
  `ytSeenTop` (`Y/YtTopicChip.kt`), suggestion pill `Y/YtTopicSuggest.kt` (from `YtWatch.attach`,
  after 10 s played, 2nd video per topic per session). The old `YtTopic` searches are called
  "Suchen" in the strings only.
- Subscriptions: `J/repo/YtFeedFlight.kt` (one network refresh at a time, callers join);
  `LocalFeedRepository` refreshes when older than 3 h with 8 channels at a time;
  `SubscriptionsViewModel.feedLoading` drives the spinner (Pitfall P6). Order of "Alle":
  `Y/feed/YtSubsOrder.kt`.
- Cards: swipe `Y/YtCardSwipe.kt` claims the finger in its own loop; rules
  `Y/feed/YtSwipePhysics.kt` (`claim` needs |dx| ≥ 2·|dy|, `drifted` > 24 dp or steeper than 30°,
  `steepRelease`; Pitfall P7). Swipe tuning: `Y/feed/YtSwipeTuning.kt`, `YtSwipeCalibration.kt`,
  `YtSwipeCalibrationScreen.kt`, `YtSwipeSettings.kt` (keys `yt_swipe_*`). Drag `Y/YtCardDrag.kt`
  (`held` = set of tokens), magnifier drag `Y/YtSearchDrag.kt`, pure drop rules
  `Y/feed/YtDragRules.kt`, undo `Y/YtUndoPill.kt`, hold menu `Y/YtQuickSheet.kt`.
- Hidden videos and paused channels: `J/helpers/YtHidden.kt`, `J/helpers/YtChannelPause.kt`, pure
  `Y/feed/YtHideRules.kt` (`YtHiddenRules`, `YtPauseRules`, `YtVisibility`), settings list
  `Y/feed/YtHomePreferences.kt`. Hide watched: `Y/feed/YtSeenHide.kt`, flag `hideSeen`, checked in
  `YtFeedEngine.passes` and `rememberYtVisible`.
- Watched marks: `Y/YtSeen.kt` (one shared `WatchPositionDao.observeAll()` flow, `Modifier.ytSeen`
  dim + badge, pref `yt_seen_marks`; `ytSeenTop` = bar + time-left pill, a Modifier.Node placed on
  the thumbnail Box AFTER its children, grow animated in the node, read in draw only; Pitfall P12),
  rules `Y/feed/YtSeenMark.kt` (`left`, `pickFit` for small thumbnails). Held cards `Y/YtHeld.kt`:
  `YtHeldToken` per card instance, `ytHeldScope(token)` on the card root (found by ancestor
  lookup), `ytHeldMark()` for cards without the drag gesture (never consumes), `ytHeldFade()` for
  chips/play buttons; pure `YtHeldSet`.
- Text on cards: upload age `Y/YtAge.kt` (pure, tested); counts `Y/YtCount.kt` (pure, tested, used
  by `formatShort()` and `TextUtils.formatViewsString`); titles pure `J/util/YtTitleCalm.kt`,
  display only via `Y/YtTitles.kt` (`StreamItem.ytTitle`, LRU), prefs `yt_title_case`,
  `yt_title_marks` (mini players still show the raw title).
- Search `Y/search/`: entry layer over the screen `YtSearchEntry` (`YtSearchSession`, pref
  "Eigene Suche je Design" in `R/xml/yt_r12_search_prefs.xml`), UI per design `YtSearchUi`
  (`YtSearchLayer`), data `YtSearchData`, rules `YtSearchRules`, inks per design `YtSearchInks`
  (`YtSearchKind`), watched marks in results `YtSeenRow`. Morph `Y/YtSearchMorph.kt`. Submitting goes
  through `YtChrome.searchFor` (same path as the toolbar).
- Shorts view `Y/shorts/`: own ExoPlayer on a TextureView, not the service (`YtShortsPlayer`); pager
  `YtShortsView` + `YtShortsScreen`, hidden timeline `YtShortsTimeline`, pure rules `YtShortsMath`
  (autoscroll, scrub), look per design `YtShortsLook` + `YtShortsStyle`, what a short plays
  `YtShortsSource`, settings `YtShortsPrefs` (`R/xml/yt_r12_shorts_prefs.xml`).
- News overlay `Y/news/` (stage 1 of 4; all designs with a Compose home, not STANDARD): pinch
  apart on the home root opens a card pager. Model `YtNewsModel` (`YtNewsItem`, `YtNewsCard` with
  `Single`; stage 3 adds `Story`), sources `YtNewsMerge` (pure: news-channel + topic videos from the
  feed cache and `YtFeed.topicVideo`, no Shorts/live, ≤ 7 days, never-words, dedup, caps 5 per
  channel / 30 total), card text `YtNewsText` (pure description cleaning), gesture rules
  `YtNewsGestureRules` (pure, thresholds in `Spec`), channel rules `YtNewsChannelRules` and store
  `YtNewsChannels` (pref `yt_news_channels`; auto-marks "News & Politics" via `noteWatched` from
  `YtWatch`; mark/unmark in `YtQuickSheet` and `YtFeedSheet`), shared state + `YtNewsSource` in
  `YtNews` (deck frozen while open, hint pref `yt_news_hint_seen`), pinch `YtNewsGestures`
  (`Modifier.ytNewsPinch`, Initial pass, consumes only after claim, resets in `finally`, P2), home
  hook + hint pill `YtNewsHome`, overlay `YtNewsLayer` (activity layer at 23.5 dp like
  `YtTimeUpLayer`, touch only while `layerUp`). Attached in `Y/YtHome.kt`.
- News stage 2 (grow out of the card, depth, haptics): `Y/YtCardFrames.kt` (registry of on-screen
  card roots; every `ytHeldScope` node registers on attach; `at(point…)` picks the card at pinch
  claim; the lent card records into a `GraphicsLayer` on API 29+ that `YtNewsLayer` draws, and hides
  on the home while shown), pure motion `YtNewsMotion` (window rect card→full, return-into-card rule,
  page depth, parallax, tilt, home sink/blur/scrim), haptics `YtNewsTextures` (`YtNewsHaptic` ×
  every `YtDesign`, checked in `YtDesignRegistryTest`). Home blur API 31+ on non-weak phones
  (`FrequencyDeviceProbe`); motion switch off = no depth/parallax.
- News stage 3 (stories): pure clustering `YtNewsStory` (folded title words, stop words, light
  stemmer, IDF, 48 h window, ≥ 2 shared words, key `story:<oldest id>`), user edits
  `YtNewsStoryEdits` (links/apart, 8 days), seen list `YtNewsSeen` (per video id, 8 days), store
  `YtNewsStories` (prefs `yt_news_story_edits`, `yt_news_seen`; `showing` deck), UI `YtNewsStoryPage`
  (`YtNewsStoryBlock` timeline, `YtNewsStoryMenu` ⋮ merge/split). `YtNewsDeck.cards` groups `items`
  with the current edits; `YtNewsMerge.cards` is test-only now. Hooks in `YtNewsLayer`: SideEffect
  `showing`, menu in `Header`, story block in `NewsCardPage`.
- News stage 4 (cited summaries, opt-in key): pure captions/transcript `YtNewsTranscript`
  (TTML/VTT/SRT, track pick, `[m:ss]` document with offset→second index), pure model
  `YtNewsSummaryModel` (citations→statements, cache rules), API `YtNewsSummaryApi` (official Java SDK
  `anthropic-java`, model in `MODEL`, effort low, server-side fallbacks, citations on a text
  document, typed errors; key test via models API), runner + disk cache `YtNewsSummary`
  (`cacheDir/yt_newssum`, 7 days, one request, cancelled on close; `summarizeStory` ready for story
  pages), key store `YtNewsSummaryKey` (Keystore AES/GCM, own prefs file yt_newssum, excluded from
  backups, Diagnose shows set/not set only, `YtCrashText.scrub` masks `sk-ant-`), UI
  `YtNewsSummaryUi` (block replaces the description box in `NewsCardPage`; statements play from the
  cited second), settings row `YtNewsSummaryKeyPreference` (`R/xml/yt_r28_newssum_prefs.xml`, in
  `YtInteractionSettings`). Network only on tapping "Kurzfassung"; R8 needs
  `-dontwarn com.google.auto.value.AutoValue`.

## Sound, Frequency, Synthwave

> Sound engine and the two sound-reactive designs.

- Sound engine: contract `Y/sound/YtSound.kt` (`YtSoundFrame` level/bass/mid/high/punch/playing,
  `YtSound.frame` read in draw/layout only, `setReactive`). Analysis in `YtAudioTap` (shares the
  wave's FFT, runs while `soundListening`), features `J/helpers/YtSoundFeatures.kt` (K-weighted
  level, adaptive ranges, spectral-flux onset, silence gate), main-thread `Y/sound/YtSoundEngine.kt`
  (Choreographer, heard-moment sync via `leadMs`/`startOf`, idle when paused/background/motion
  off, half rate in power saver), shaping `Y/sound/YtSoundShaping.kt` (envelopes, punch budget
  330 ms, `YtSwingGuard` ≤ 3 swings/s), player hook `J/services/YtSoundModule.kt`.
- Two channels: fast motion `YtSound.motion` / `YtSound.bands` (unguarded: size, bounce, morph,
  rays) vs the guarded `YtSound.frame` (brightness: glow, veils, aura, time bar colour).
- Frequency (`YtDesign.FREQUENCY`): palette `YtPalettes.Frequency`, `YtSignature.FREQUENCY`,
  `Y/FrequencyHome.kt`, `Y/FrequencyChrome.kt` (logo), `Y/FrequencyTimeBar.kt`,
  `Y/mini/YtMiniFrequency*.kt`, pulse `Y/FrequencyPulse.kt` (+ pure `Y/FrequencyPulseMath.kt`,
  slew-limited; generalised for both sound designs: `FrequencyDeviceProbe`, `YtHoldWhileStarted`).
  Pure maths `Y/FrequencyMotion.kt` (stretch, kick detector, spring, sweeps ≥ 0.45 s apart, morph,
  tiers, `barLevel`), visuals `Y/FrequencyVisuals.kt` (backdrop blobs: RuntimeShader on 33+,
  gradients below; header spectrum; nav edge wave). Tiers `FrequencyTiers.pick` (HIGH/MID/LOW,
  re-read every 5 s). Diagnose shows live "Sound" and "Frequency" lines. Watch action buttons have
  no tap ripple here.
- Subscribe pill equalizer: `Y/FrequencyPillBars.kt` (pure geometry, rest arch, band → bar via
  `FrequencyMotion.barLevel`, contrast-safe alpha 0.16 on coral), hooked in `FrequencySubscribe`
  (`YtWatchDesigns`) between fill and label; heights only, alpha constant.
- Synthwave (`YtDesign.SYNTHWAVE`, `soundReactive` like Frequency): palette `YtPalettes.Synthwave`,
  scene `Y/SynthwaveScene.kt` (Canvas, draw phase, own graphicsLayers; stars, striped sun, halo,
  ridges, perspective floor, haze, palms, parallax ≤ 40 dp), maths `Y/SynthwaveMath.kt`
  (`SynthwaveTiers` reuses FrequencyTiers; palms `palms`/`palm`: edge bands ≤ 27 % width, never
  within 0.6 sun radii, own layer each, sway ≤ 1.2°), `Y/SynthwaveChrome.kt`, `Y/SynthwaveHome.kt`
  (home headline is the first list item, no sun-reveal spacer), `Y/mini/YtMiniSynthwave*.kt`, font
  Audiowide (OFL, `fonts/`). Text over the scene: glass cards on home, `TAB_VEIL` 0.86 on
  Du/Abos/Shorts.
- Music mode for the other designs: not built (see `docs/ROADMAP.md`); the engine above is ready
  for it.

## Account, update, settings, diagnostics

> Sign-in, in-app update and release notes, settings screens, guide, crash report, storage, security.

- Sign-in `J/ui/activities/YtSignInActivity.kt`; account `Y/account/` (`YtAccount` state, sheet
  behind every profile button `YtAccountSheet`, import + Takeout `YtImport`, InnerTube calls
  `YtInnerTube`); session AES-encrypted, excluded from backups (`R/xml/yt_backup_rules.xml`,
  `R/xml/yt_data_extraction_rules.xml`). Details `docs/KONTO.md`.
- In-app update: `J/util/UpdateChecker.kt` (offers only a higher build number), `J/util/YtSelfUpdater.kt`
  (download to cache, size + SHA-256 + signer check, PackageInstaller session; `holdsApk()`),
  dialog `R/layout/dialog_yt_update.xml`, rules `J/util/YtUpdateRules.kt`:
  `installVerdict` treats FAILURE/BLOCKED/TIMEOUT as unsure ("Wird installiert…"); `nextStep`
  decides done / confirm / retry (`MAX_AUTO_RETRIES` = 2, fresh session with the kept, verified
  `cache/update/update.apk`) / system installer (`ACTION_VIEW` via `${applicationId}.fileprovider`,
  path `update/`) / declined / failed. Sessions use `USER_ACTION_REQUIRED` (with
  `USER_ACTION_NOT_REQUIRED` Play Protect scanned outside any dialog and ended the session as
  "aborted"). The last result is kept in prefs `yt_self_updater/last_result`.
  `J/receivers/YtUpdateReceiver.kt` (MY_PACKAGE_REPLACED) only posts a notification (an OEM
  security app killed a background relaunch). Play Protect warnings come from sideloading, not
  from removable code.
- Releases: GitHub `Tra1c0/YT-App`, tag `build-N`, asset `YT-32.1-yt.N.apk`. Release notes:
  `release-notes/next.md` (German) → `scripts/release-body.sh` in CI → release body (notes, marker
  `<!-- yt-details -->`, technical lines) → `UpdateChecker` fetches the bodies of builds
  (installed, offered], max 10, via `ExternalApi.getReleaseByTag`; pure parsing
  `J/util/YtReleaseNotes.kt`, rendering `Y/YtNotesText.kt`, scroll cap `Y/YtMaxHeightScrollView.kt`.
- Guide ("Anleitung"): content as data in `Y/YtGuide.kt` (sections → items, string ids, path
  text, target; stable keys, meant for the later first-start screen), pure filter/grouping
  `Y/YtGuideRules.kt`, screen `Y/YtGuideFragment.kt` (`R/layout/yt_guide_fragment.xml`) in
  `nav_settings` (`ytGuide`), strings `yt_r19_guide_strings.xml`. Items may carry a preference key
  (`setting`): passed as fragment arg `yt_jump_key`, consumed once in
  `BasePreferenceFragment.onViewCreated` → `Y/YtSettingJumpUi.kt` (scroll to centre, overlay pulse;
  pure `Y/YtSettingJump.kt`; a test checks every key exists in `res/xml`).
- Crash report: upstream `J/util/ExceptionHandler.kt` calls `J/util/YtCrashLog.kt` (`record`) first
  (files in `filesDir/yt_crash/`, newest 3, `new` marker); pure text/scrub/truncate
  `J/util/YtCrashText.kt`. Dialogs (crash on next start, Settings → Diagnose) in
  `Y/YtDiagnosisUi.kt`, text in `J/util/YtDiagnosis.kt`. Showing the YT crash dialog clears
  upstream `error_log` (no double dialog).
- Storage page: pure `J/util/YtStorageRules.kt`, `J/util/YtStorage.kt`, dialog `Y/YtStorageUi.kt`.
  Image cache via Coil's `diskCache.clear()`, WebView via `clearCache(true)` (cookies and
  `app_webview` never touched); `YtSelfUpdater.holdsApk()` protects a running update. LibreTube has
  no media or HTTP disk cache.
- Update notification: periodic WorkManager job `J/workers/YtUpdateCheckWorker.kt` (unique name
  `YtUpdateCheck`, network + battery-not-low, 3/6/12/24 h, default 6 h; scheduled on every app
  start, KEEP unless the stored interval differs → UPDATE; off cancels it). Same check as the dialog
  via `UpdateChecker.newerRelease()` / `installedBuild()`. Android side `J/util/YtUpdateNotifier.kt`
  (channel `yt_update_available` "App-Updates", one notification per build, notified only once
  posted, tap/"Jetzt laden" → MainActivity re-checks and shows `UpdateAvailableDialog`, Diagnose
  line), pure rules `J/util/YtUpdateNotifyRules.kt`, settings binding
  `J/ui/preferences/YtUpdateNotifyPrefs.kt` (switch `yt_update_notify`, interval, "allow" row with
  the POST_NOTIFICATIONS request / system-page fallback). A build shown in the in-app dialog counts
  as notified. Guide target `YtGuideTarget.SETTINGS` opens MainSettings on top.
- Security: notes `docs/SICHERHEIT.md`; emulator-only switches guarded by `J/helpers/YtGuard.kt`.

## Build, CI, tests, docs system

> Local build, workflows, emulator checks, tests, the docs check.

- Local build: Android SDK at `/opt/android-sdk` (`local.properties` per worktree; a fresh
  container needs cmdline-tools with `platforms;android-36` and `build-tools;36.0.0`), network
  needs `dl.google.com` and `jitpack.io`. Compile (with `export ANDROID_HOME=/opt/android-sdk`):
  `./gradlew :app:compileReleaseKotlin --no-daemon -q --no-configuration-cache`. YT unit tests:
  `./gradlew :app:testReleaseUnitTest --no-daemon -q --no-configuration-cache --tests
  'com.github.libretube.ui.yt.*' --tests 'com.github.libretube.helpers.Yt*' --tests
  'com.github.libretube.services.Yt*' --tests 'com.github.libretube.util.Yt*'`. Out of memory:
  add `-Dorg.gradle.jvmargs=-Xmx6g -Pkotlin.daemon.jvmargs=-Xmx4g`. Maven Central sometimes
  answers 429: wait and retry. The release signing key exists only in the GitHub secret
  `YT_SIGNING`, so APKs for the phone come from CI.
- Tests live in `T/` (mirrors the packages); pure rules are kept free of Android so they run on
  the JVM.
- `.github/workflows/build.yml`: push to `main` → release `build-N` (latest); push to `r12/**` →
  pre-release `check-N-<sha>` (last three kept). Steps: docs check (fails only on `main`), string
  parity (`scripts/check-strings.sh`), YT unit tests (`helpers.Yt*`, `ui.yt.*`), release APK,
  release body. Pushes that change only `**.md`, `scripts/**`, `fastlane/**`, `assets/**` build
  nothing; `.github/workflows/docs-check.yml` runs the docs check for those.
  A newer push to the same branch cancels a running build (its release never exists).
  GitHub Actions only run while the repo is public (private: billing blocks jobs).
- `.github/workflows/signing-key.yml` creates the signing key once; `scripts/apk-signer.py <apk>`
  prints an APK's signer.
- `.github/workflows/crash-check.yml` (manual): emulator check; inputs `build`, `design`, `target`
  (use `default`, the plain image is much lighter), `screens`, `test_video`, `gestures`, `pip`,
  `float`, `lock`, … Script `scripts/crash-check.sh`, gesture checks `scripts/gestures/*.sh`
  (helpers `lib.sh`; `a.sh`…`e.sh` per package, `search.sh`, `shorts.sh`), wave check
  `scripts/wave-check.py`, test video `scripts/fixtures/yt-test.mp4`. Emulator-only test hooks:
  `Y/YtTestHooks.kt` reads the per-package `Y/**/Yt*TestHooks.kt`; `Y/YtTestTag.kt` exposes test
  tags as resource-ids for uiautomator.
- Emulator behaviour: Glas/Frost compile GPU programs over the slow GL pipe on a cold start → ANR;
  the script keeps error dialogs on and presses "Wait" (reported as a warning). Online videos do
  not play there, use the test video. uiautomator dumps can hang on never-idle screens.
- Docs system: `scripts/docs_sync.py` (stdlib Python; config `scripts/docs_sync.json`),
  `scripts/check-strings.sh`, git hook installer `scripts/install-hooks.sh`, session-start hook
  `.claude/hooks/session-start.sh` (registered in `.claude/settings.json`). Reusable,
  project-neutral template: `templates/ai-workflow/` (README there); its copies of the script, the
  hook installer and the session hook must stay identical to the repo's (the check enforces it).
  How to use it: `docs/HANDOFF.md` → How to work.

## Pitfalls

> Mistakes that happened more than once: check them in every review.

- **P1** Text colour from `palette.text*` on a surface of different brightness (3 times: intent
  chooser, quick sheet, Feed-Regler/account sheet). Palette text roles are made for `background`;
  words on any other surface take their colour from `Y/YtInk.kt` (`YtInk.on(palette, back)`,
  `LocalYtInk`, `ytSurfaceInk`) or `YtContrast`, aim 4.5:1. Test `T/ui/yt/YtSheetInkTest.kt`.
- **P2** Gesture state not reset on CANCEL/abort/detach (hold tiles stuck after the minimize drag).
  Every gesture needs a reset on cancel, on detach and when the parent takes the touch.
- **P3** MotionLayout copies constraints when attached: a recreate in front attaches at
  setContentView, so later constraint changes need `YtMotionAccess.takeViewsAgain`.
- **P4** MotionLayout: `setProgress` before attach is cached and applied as a jump in
  `onAttachedToWindow`; `transitionToStart()` before attach does nothing; keyframes are copied only
  on setup (re-apply the dock offset on bar changes).
- **P5** SurfaceView video cannot be masked, blurred or captured into a backdrop; draw shapes over
  it, and make those layers follow the picture's live bounds during drags (Formen cookie glitch).
- **P6** A Compose spinner keyed on an equal data-class list never stops.
- **P7** `awaitHorizontalTouchSlopOrCancellation` ignores the vertical part, so a child of a
  vertical list wins slanted scrolls (diagonal scrolls swiped cards).
- **P8** media3: an empty timeline between queue items drops the foreground service; Android 12+
  then refuses `startForegroundService` from the background (fix: `YtForegroundHold`).
- **P9** Google Sans has a very tall bounding box: the classic screens of Glas and Formen set
  `android:includeFontPadding=false`, otherwise lines are twice as tall.
- **P10** media3 audio processors: `queueEndOfStream` is called again on every pipeline pass while
  the stage before is ended; output for the end must be produced once (reset in `onFlush`), or the
  drain that every speed change runs never ends (sound gone, picture runs on).
- **P11** MainActivity handles rotation itself: a page that turned inside fullscreen must be
  rebuilt when fullscreen ends; cached geometry must be invalidated per configuration, not per size.
- **P12** Anything drawn on a thumbnail image sits under the Box's later children: draw overlays
  in a node placed after them.

## File inventory

> Generated list of every YT Kotlin file by package.

<!-- gen:inventory -->
<details><summary>All YT Kotlin files by package (369 files; generated, do not edit)</summary>

- `J/helpers/` (24): YtAppearanceOptions, YtAudioTap, YtBattery, YtBatteryPreviewRules,
  YtBatteryRules, YtChannelPause, YtChannelSpeed, YtCommentMoments, YtDesign, YtDesignDark, YtGuard,
  YtHidden, YtLeveller, YtLoudness, YtLoudnessMeter, YtMusicPlayback, YtNotes, YtPeakLimiter,
  YtSceneUnlock, YtSoundFeatures, YtSpectrum, YtSpeedRules, YtThemeRefresh, YtVideoColors
- `J/receivers/` (1): YtUpdateReceiver
- `J/repo/` (1): YtFeedFlight
- `J/services/` (13): YtBatteryQualityModule, YtLoopModule, YtOfflineAudio, YtPlayRecovery,
  YtPlaybackGuards, YtPositionLedger, YtPositionRules, YtResumeNotification, YtScrubModule,
  YtServiceModules, YtServiceSoftClose, YtSessionModule, YtSoundModule
- `J/ui/activities/` (1): YtSignInActivity
- `J/ui/preferences/` (3): YtChannelSpeedsPreference, YtInteractionSettings, YtUpdateNotifyPrefs
- `J/ui/views/` (2): YtBottomNavigationView, YtEdgeScrollView
- `J/util/` (15): YtCrashLog, YtCrashText, YtDiagnosis, YtMusicRules, YtQueueEdit, YtQueuePersist,
  YtQueueStore, YtReleaseNotes, YtSelfUpdater, YtStorage, YtStorageRules, YtTitleCalm,
  YtUpdateNotifier, YtUpdateNotifyRules, YtUpdateRules
- `J/workers/` (1): YtUpdateCheckWorker
- `Y/` (114): AfterHome, AfterRooms, ClayHome, EditorialHome, FrequencyChrome, FrequencyHome,
  FrequencyMotion, FrequencyPillBars, FrequencyPulse, FrequencyPulseMath, FrequencyTimeBar,
  FrequencyVisuals, FrostHome, GlassHome, KieselDiscover, KieselHome, MovoHome, MovoWatch, PopHome,
  ShapesHome, SideHome, SideWatch, SynthwaveChrome, SynthwaveHome, SynthwaveMath, SynthwaveScene,
  YouTubeHome, YouTubeLibrary, YouTubeWatch, YtActivityHooks, YtAge, YtBatteryPreview,
  YtBatteryVibes, YtCardDrag, YtCardFrames, YtCardSwipe, YtClassic, YtContrast, YtCount,
  YtDiagnosisUi, YtDiscovery, YtDock, YtDrag, YtFeedButtons, YtFeedSheet, YtFloatingPlayer,
  YtGestures, YtGuide, YtGuideFragment, YtGuideRules, YtHapticTexture, YtHaptics, YtHeader, YtHeld,
  YtHome, YtHomeKit, YtHomeRound2, YtImages, YtInk, YtIntentChooser, YtIntentTrigger, YtLibrary,
  YtLibraryDesigns, YtLiquidKit, YtLiquidNav, YtLive, YtMaxHeightScrollView, YtMiniPlayer, YtMotion,
  YtNavBar, YtNavDesigns, YtNavHold, YtNotesText, YtOverlays, YtPebbles, YtPipVisualizer,
  YtPlaybackClock, YtQueue, YtQueueResume, YtQuickSheet, YtRateHold, YtRatings, YtRefresh, YtRooms,
  YtSearchDrag, YtSearchMorph, YtSeen, YtSentiment, YtSettingJump, YtSettingJumpUi, YtShapes,
  YtShorts, YtSideFill, YtSideInsets, YtStorageUi, YtSubscriptions, YtSurfaces, YtTestHooks,
  YtTestTag, YtTheme, YtTiltSensor, YtTimeBoxUi, YtTimeUp, YtTitles, YtTopicChip, YtTopicSuggest,
  YtUndoPill, YtVibes, YtWatch, YtWatchDesigns, YtWatchEdge, YtWatchPages, YtWaveShape, YtWaveView
- `Y/account/` (4): YtAccount, YtAccountSheet, YtImport, YtInnerTube
- `Y/feed/` (22): YtDragRules, YtFeed, YtFeedEngine, YtFeedFresh, YtFeedRules, YtFeedTopics,
  YtFeedTopicsSession, YtFeedTopicsWords, YtHideRules, YtHomePreferences, YtHomeTestHooks,
  YtQueueRule, YtSeenHide, YtSeenMark, YtSession, YtSubsOrder, YtSwipeCalibration,
  YtSwipeCalibrationScreen, YtSwipePhysics, YtSwipeSettings, YtSwipeTuning, YtTimeBox
- `Y/liquid/` (4): DampedDragAnimation, InteractiveHighlight, LiquidGestures, YtViewBackdrop
- `Y/mini/` (82): YtBleedMath, YtDockMath, YtGlassLayout, YtGlassOver, YtGlassSlabShape,
  YtMiniAfter, YtMiniAfterLayout, YtMiniAfterOver, YtMiniAfterTextures, YtMiniAfterUnder,
  YtMiniBleed, YtMiniClay, YtMiniClayLayout, YtMiniClayOver, YtMiniClayTextures, YtMiniClayUnder,
  YtMiniDesign, YtMiniDesigns, YtMiniDockBar, YtMiniEditorial, YtMiniEditorialLayout,
  YtMiniEditorialOver, YtMiniEditorialTextures, YtMiniEditorialUnder, YtMiniFrequency,
  YtMiniFrequencyTextures, YtMiniFrost, YtMiniFrostLayout, YtMiniFrostOver, YtMiniFrostTextures,
  YtMiniFrostUnder, YtMiniGeometry, YtMiniGlass, YtMiniHost, YtMiniKiesel, YtMiniKieselLayout,
  YtMiniKieselOver, YtMiniKieselTextures, YtMiniKieselUnder, YtMiniLook, YtMiniModule, YtMiniMovo,
  YtMiniMovoLayout, YtMiniMovoOver, YtMiniMovoTextures, YtMiniMovoUnder, YtMiniParts, YtMiniPop,
  YtMiniPopLayout, YtMiniPopOver, YtMiniPopTextures, YtMiniPopUnder, YtMiniRestore,
  YtMiniRestoreRules, YtMiniRiseRules, YtMiniShapes, YtMiniShapesMath, YtMiniShapesOver,
  YtMiniShapesTextures, YtMiniShapesUnder, YtMiniSide, YtMiniSideLayout, YtMiniSideParts,
  YtMiniSideTextures, YtMiniStandard, YtMiniSynthwave, YtMiniSynthwaveTextures, YtMiniTestHooks,
  YtMiniTextures, YtMiniTouch, YtMiniYouTube, YtMiniYoutubeLayout, YtMiniYoutubeParts,
  YtMiniYoutubeTextures, YtMinimizeDrag, YtMinimizeGeometry, YtMinimizeModule, YtMinimizeTestHooks,
  YtPageStart, YtSoftClose, YtSoftCloseRules, YtUnderFinger
- `Y/news/` (24): YtNews, YtNewsChannelRules, YtNewsChannels, YtNewsGestureRules, YtNewsGestures,
  YtNewsHome, YtNewsLayer, YtNewsMerge, YtNewsModel, YtNewsMotion, YtNewsSeen, YtNewsStories,
  YtNewsStory, YtNewsStoryEdits, YtNewsStoryPage, YtNewsSummary, YtNewsSummaryApi, YtNewsSummaryKey,
  YtNewsSummaryKeyPreference, YtNewsSummaryModel, YtNewsSummaryUi, YtNewsText, YtNewsTextures,
  YtNewsTranscript
- `Y/player/` (34): YtBatteryCapLabel, YtBatteryCueModule, YtBatteryCueView, YtChapterTap,
  YtChapterTapLabel, YtHoldDirection, YtHoldGesture, YtHoldLayout, YtHoldTileStyle, YtHoldTilesView,
  YtLoopChipView, YtLoopController, YtLoopPlan, YtLoopSheet, YtLoops, YtPageLayoutRules,
  YtPlayerGestureModule, YtPlayerGesturePrefs, YtPlayerGestureTestHooks, YtPlayerHost,
  YtPlayerHudStyle, YtPlayerModules, YtPlayerTouch, YtReturnChipView, YtRewindChip, YtRewindMemory,
  YtScrubLensView, YtScrubLive, YtScrubPrecision, YtSharePlan, YtShareScrub, YtShareScrubSource,
  YtShareTokenView, YtSpring
- `Y/scene/` (4): YtSceneCompose, YtSceneModule, YtSceneSheet, YtSceneTestHooks
- `Y/search/` (6): YtSearchData, YtSearchEntry, YtSearchInks, YtSearchRules, YtSearchUi, YtSeenRow
- `Y/shorts/` (10): YtShortsLook, YtShortsMath, YtShortsPlayer, YtShortsPrefs, YtShortsScreen,
  YtShortsSource, YtShortsStyle, YtShortsTestHooks, YtShortsTimeline, YtShortsView
- `Y/sound/` (3): YtSound, YtSoundEngine, YtSoundShaping
- `app/src/main/java/androidx/constraintlayout/motion/widget/` (1): YtMotionAccess

</details>
<!-- /gen:inventory -->
