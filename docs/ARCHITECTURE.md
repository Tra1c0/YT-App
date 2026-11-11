# YT – architecture notes (for whoever works on the code)

Companion to [FEATURES.md](FEATURES.md) (user-facing list). Keep both current in the same commit
as any change (see `/CLAUDE.md`). Short forms: `J` = `app/src/main/java/com/github/libretube`,
`Y` = `J/ui/yt`, `R` = `app/src/main/res`, `T` = `app/src/test/java/com/github/libretube`.

## Basics

- Fork of LibreTube 32.1 (commit 9380ab045). Package `app.tra1c0.yt`, version name `32.1-yt.N`,
  version code `72*100000 + N` (N = CI run number, env `YT_BUILD_NUMBER`).
- GPL change log against upstream: `MODIFICATIONS.md`. Keep YT code in new `Yt*` files with small
  hunks in upstream files, so upstream merges stay possible.
- Kotlin, Views + Jetpack Compose, media3 (version in `gradle/libs.versions.toml`), minSdk 26,
  targetSdk 36. Comments in English, plain, explaining why. Strings: `R/values/yt_*.xml` and the same
  keys in `R/values-de/` (CI checks parity). No model names in code, docs or commits.

## Designs

- `J/helpers/YtDesign.kt`: enum of the 13 designs (key, overlay style, brand font, `light`).
  Pref `yt_design`. `STANDARD` = classic LibreTube look; YT code mostly checks `palette == null`.
- `Y/YtTheme.kt`: `YtPalette` per design (+ dark variants of the light designs, `ytDayNight`),
  `YtFonts`, `ytScale` (width scaling, see `docs/DESIGN-MASSE.md`), `YtTheme()` composable.
- `J/helpers/YtDesignDark.kt` (pref `yt_design_dark` L/D/A) sets the night mode in
  `BaseActivity.attachBaseContext`; `J/helpers/YtThemeRefresh.kt` bumps a generation so activities
  rebuild without a restart; `J/helpers/YtAppearanceOptions.kt` decides which settings rows show.
- Motion: `Y/YtMotion.kt` (`YtMotionPrefs`, `ytSpec` snaps when motion is off). Haptics:
  `Y/YtHaptics.kt` (+ `YtVibes`, `YtHapticTexture`, per-design `*Textures.kt`), pref `yt_haptics`.
- Glass: vendored Kyant backdrop (`third_party/backdrop/`, sources under `app/src/main/java/com/kyant`),
  `Y/YtLiquidKit.kt`, `Y/YtLiquidNav.kt`, `Y/liquid/`.
- Classic screens: `R/values/yt_classic.xml` (+ `values-night`), `Y/YtClassic.kt`.
- Navs `Y/YtNavBar.kt`, `Y/YtNavDesigns.kt`; headers `Y/YtHeader.kt` (`YtChrome` holds the
  search callbacks); homes `Y/*Home.kt`, data dispatch `Y/YtHomeRound2.kt`, `Y/YtHome.kt`.
- Watch pages `Y/YtWatchPages.kt`, `YtWatchDesigns.kt`, `MovoWatch.kt`, `SideWatch.kt`,
  `YouTubeWatch.kt`; edge under the video `Y/YtWatchEdge.kt`.

## Round-12 hook points (package 0)

- Page side: `Y/player/YtPlayerHost.kt` (PlayerFragment implements it), `Y/player/YtPlayerModules.kt`
  (fixed module list: mini, minimize, player gestures, scene). Service side:
  `J/services/YtServiceModules.kt` (`yt_cmd` bundle key; modules `YtLoopModule`, `YtScrubModule`,
  `YtSessionModule`).
- Shared state: `Y/YtDock.kt` (dock progress, peek, slab, hover, drops), `Y/YtDrag.kt` (drag sessions,
  `miniBounds`, drop cards, `YtDragLayer` overlay that never takes touches),
  `Y/YtPlaybackClock.kt` (position flow + tagged seeks), `Y/YtOverlays.kt` (open-overlay counter).
- Queue: `J/util/PlayingQueue.kt` (+ `revision`, `upcomingCount`, `ytInsertNext`, `ytAppend`,
  `ytKeepQueue`), pure rules `J/util/YtQueueEdit.kt`; `PlayerData.keepQueue` honoured in
  `NavigationHelper`.
- Touch on the player page: `J/ui/views/SingleViewTouchableMotionLayout.kt`, a state machine
  (IDLE, PENDING, LEGACY, PEEK, DRAG) with delegates `MiniTouch` and `MinimizeDrag`; with no
  delegate it behaves like upstream.
- Settings screen "Gesten & Interaktion": `J/ui/preferences/YtInteractionSettings.kt`, root
  `R/xml/yt_interaction_settings.xml`, one `R/xml/yt_r12_*_prefs.xml` per package.

## Mini player

- Framework `Y/mini/`: `YtMiniDesign` (per-design object: geometry, `constrain`, `Under`/`Over`
  composables, motion, peek, haptics, `underAlpha`/`overAlpha`), `YtMiniHost` (wires it into the
  MotionLayout end/peek sets, publishes bounds, crop, landing), `YtMiniGeometry` (pure px geometry,
  tested), registry `YtMiniDesigns`, one family of files per design (`YtMini<Design>*.kt`).
- Peek: `yt_peek_set` / `yt_peek` in `R/xml/player_scene.xml`, spring-driven, muted listener
  fan-out while peeking. Drag under the finger: `YtMinimizeDrag.kt` + pure solver `YtUnderFinger.kt`.
- The activity container's dock offset is re-applied by `YtMotionAccess.reapply()` when the bar's
  height changes (MotionLayout copies keyframes only on setup).
- **SurfaceView limits:** the video cannot be masked, blurred or captured into a backdrop; only
  clipped by an outline (round rect; path on newer APIs). Shapes like the Formen cookie are drawn
  over the video in the body colour. Glas' soft edges copy frames with PixelCopy (`YtMiniBleed`).

## Player features

- Gestures: `Y/player/` (scrub precision + lens + live picture, rewind memory + chip, loop plan /
  controller / sheet / chip, hold direction + tiles view + style, share scrub plan / source / token).
  Time bar classes `J/ui/views/{DismissableTimeBar,MarkableTimeBar,ChapterTimeBar}.kt`. Upstream
  gestures `J/ui/listeners/PlayerGestureController.kt`. Fullscreen moves the player into a plain
  Dialog: overlays there are Views (no Compose owners).
- Ratings: `Y/YtRateHold.kt`, `Y/YtRatings.kt` (pref `yt_ratings`), InnerTube like endpoints in
  `Y/account/YtInnerTube.kt` (not verified against YouTube).
- Upload age: `Y/YtAge.kt` (pure, tested).
- Comments scene: `J/helpers/YtCommentMoments.kt`, `J/ui/models/CommentScene.kt`, `Y/scene/`.
- RYD dislikes: fetched in `NewPipeMediaServiceRepository` (`ExternalApi.getVotes`), shown only in
  the classic `DescriptionLayout` (STANDARD).

## Playback service

- `J/services/AbstractPlayerService.kt`, `OnlinePlayerService.kt`, `OfflinePlayerService.kt`;
  notification `J/util/NowPlayingNotification.kt`.
- `J/services/YtPlaybackGuards.kt`: `YtForegroundHold` (keeps the service in the foreground while
  `navigateVideo` clears and reloads the player; media3 would otherwise drop foreground on the empty
  timeline and Android 12+ refuses `startForegroundService` from the background) and
  `YtLiveRecovery` (up to 3 retries for live errors). Fallback notification
  `J/services/YtResumeNotification.kt`.
- Offline audio-only without an audio file plays the video file's sound (`YtOfflineAudio.kt`).
- Floating player + sound wave: `Y/YtFloatingPlayer.kt` (TYPE_APPLICATION_OVERLAY window,
  `PlayerView.switchTargetView`), `Y/YtWaveView.kt` (CPU-drawn: GPU path programs stalled the
  render thread), `J/helpers/YtAudioTap.kt`, `J/helpers/YtSpectrum.kt`; fallback `Y/YtPipVisualizer.kt`.
  Needs SYSTEM_ALERT_WINDOW; Android 15 locks it for APK installs ("restricted settings").

## Home, feed, search, shorts

- Feed: `Y/feed/` (`YtFeed` sources and extras, `YtFeedEngine` pure ranking, `YtFeedRules` JSON pref
  `yt_feed_rules`, `YtFeedFresh` seen-on-top memory `yt_feed_seen`, `YtTimeBox` + `YtSession`,
  `YtIntent` chooser), sheet `Y/YtFeedSheet.kt`, chooser `Y/YtIntentChooser.kt`.
- Cards: `Y/YtCardSwipe.kt`, `Y/YtCardDrag.kt`, `Y/YtSearchDrag.kt`, `Y/YtUndoPill.kt`,
  `Y/YtQuickSheet.kt`; hidden `J/helpers/YtHidden.kt`, paused channels `J/helpers/YtChannelPause.kt`.
- Search: `Y/search/` (entry layer over the screen, UI per design, data, rules, inks); morph
  `Y/YtSearchMorph.kt`. Submitting goes through `YtChrome.searchFor` (same path as the toolbar).
- Shorts: `Y/shorts/` (own ExoPlayer on a TextureView, not the service; pager, timeline, autoscroll).
- Text contrast helper `Y/YtContrast.kt` (aim 4.5:1); tests check palettes.

## Account, update, security

- Sign-in `J/ui/activities/YtSignInActivity.kt`, account `Y/account/` (session AES-encrypted,
  excluded from backups), docs `docs/KONTO.md`.
- In-app update: `J/util/UpdateChecker.kt` (only a higher build number), `J/util/YtSelfUpdater.kt`
  (download to cache, size + SHA-256 + signer check, PackageInstaller session),
  `J/util/YtUpdateRules.kt`, `J/receivers/YtUpdateReceiver.kt` (MY_PACKAGE_REPLACED → relaunch or
  notification). Releases: GitHub `Tra1c0/YT-App`, tag `build-N`, asset `YT-32.1-yt.N.apk`.
- Security notes `docs/SICHERHEIT.md`; emulator-only switches guarded by `J/helpers/YtGuard.kt`.

## Build, CI, tests

- Local build: Android SDK at `/opt/android-sdk` (`local.properties`), network needs `dl.google.com`
  and `jitpack.io`. `./gradlew :app:compileReleaseKotlin --no-daemon -q --no-configuration-cache`;
  unit tests `./gradlew :app:testReleaseUnitTest --no-daemon -q --no-configuration-cache --tests
  'com.github.libretube.ui.yt.*' --tests 'com.github.libretube.helpers.Yt*' --tests
  'com.github.libretube.services.Yt*' --tests 'com.github.libretube.util.Yt*'`. If Gradle runs out
  of memory: `-Dorg.gradle.jvmargs=-Xmx6g -Pkotlin.daemon.jvmargs=-Xmx4g`. Maven Central sometimes
  answers 429: wait and retry. The release signing key is only in the GitHub secret `YT_SIGNING`,
  so APKs for the phone come from CI.
- `.github/workflows/build.yml`: push to `main` → release `build-N` (latest); push to `r12/**` →
  pre-release `check-N-<sha>` (last three kept). Runs the YT string parity check and YT unit tests.
- **GitHub Actions only run while the repo is public** (private: billing/spending limit blocks jobs).
- `.github/workflows/crash-check.yml` (manual): emulator check; inputs `build`, `design`, `target`
  (use `default`, the plain image is much lighter), `screens`, `test_video`, `gestures`, `pip`,
  `float`, `lock`, … Script `scripts/crash-check.sh`, gesture checks `scripts/gestures/*.sh`
  (helpers `lib.sh`), test video `scripts/fixtures/yt-test.mp4`, emulator-only test hooks
  `Y/YtTestHooks.kt` + per-package `*TestHooks.kt`.
- Emulator pitfalls: Glas/Frost compile GPU programs over the slow GL pipe on a cold start → ANR;
  the script keeps error dialogs on and presses "Wait" (reported as a warning). Online videos do
  not play there, use the test video. uiautomator dumps can hang on never-idle screens. Logcat
  artifacts cannot be downloaded from this environment (blob storage blocked); read job logs instead.
- Releases and screenshots are fetched with `gh api` + `curl -H "Accept: application/octet-stream"`.
  Check every APK before handing it over: SHA-256 against the release digest, signer
  `C3:8D:B9:09:4F:B4:67:51:FE:C3:45:FB:DB:66:49:2F:28:5A:D1:B5:CA:02:7D:DC:51:08:8B:49:55:B8:5A:86`.

## Round 14 notes

- Card swipe: `Y/YtCardSwipe.kt` claims the finger in its own loop; rules in
  `Y/feed/YtSwipePhysics.kt` (`claim` needs |dx| ≥ 2·|dy|, `drifted` > 24 dp or steeper than 30°,
  `steepRelease`). Pitfall: `awaitHorizontalTouchSlopOrCancellation` ignores the vertical part, so
  a child of a vertical list wins slanted scrolls.
- Player touch: `Y/player/YtPlayerTouch.kt` tracks finger + long press for
  `PlayerGestureController`; `abort()` ends a gesture when `useController` turns off mid-gesture
  (the minimize drag disables the controller before the parent's CANCEL arrives).
- Sheet ink: `Y/YtInk.kt` (`YtInk.on(palette, back)`, `LocalYtInk`, `ytSurfaceInk`). Palette text
  roles are made for `background`; words on any other surface take their colour from `YtInk`.
  Test `T/ui/yt/YtSheetInkTest.kt`.
- Counts: `Y/YtCount.kt` (pure, tested), used by `formatShort()` and `TextUtils.formatViewsString`.
  Pitfall: Google Sans has a very tall bounding box; the Glas and Formen themes set
  `android:includeFontPadding=false`, otherwise classic text lines are twice as high.
- Landscape / rotation: a page re-created for the same player starts through `Y/mini/YtPageStart`
  once attached (docked again if `PlayerViewModel.ytDocked`). Pitfall: `MotionLayout.setProgress`
  before attach is cached and applied as a jump in `onAttachedToWindow`; `transitionToStart()`
  before attach is a no-op. Side insets: `Y/YtSideInsets`.
- Subscriptions: `J/repo/YtFeedFlight.kt` (one network refresh at a time, callers join);
  `LocalFeedRepository` refreshes when older than 3 h with 8 channels at a time;
  `SubscriptionsViewModel.feedLoading` drives the spinner. Order of "Alle": `Y/feed/YtSubsOrder.kt`.
  Pitfall: a Compose spinner keyed on an equal data-class list never stops.
- Update: `YtUpdateRules.installVerdict` treats FAILURE/BLOCKED/TIMEOUT as unsure ("Wird
  installiert…"); after the replace only a notification is posted (an OEM security app killed the
  background relaunch). Play Protect warnings come from sideloading, not from removable code.

## Round 15 notes

- Update: `YtUpdateRules.nextStep` decides done / confirm / retry (`MAX_AUTO_RETRIES` = 2, fresh
  session with the kept, verified `cache/update/update.apk`) / system installer (`ACTION_VIEW` via
  `${applicationId}.fileprovider`, path `update/`) / declined / failed. Sessions use
  `USER_ACTION_REQUIRED`; the last result is kept in prefs `yt_self_updater/last_result`.
  Pitfall: `USER_ACTION_NOT_REQUIRED` let Play Protect scan outside any dialog and end the session.
- Speed/pitch: `J/helpers/YtSpeedRules.kt` (clamps, slider grid, `YtThrottle` 150 ms in
  `PlaybackOptionsSheet`). Playback errors 1000/1004/4001/4003/5001/5002 on VOD → `prepare()` again
  via `J/services/YtPlayRecovery.kt` + `AbstractPlayerService.tryRecover`. Pitfall: the service used
  to end itself on any IDLE error while the page called play(), leaving a running clock over a dead
  player. Speed per channel: `J/helpers/YtChannelSpeed.kt` (pref `yt_channel_speeds`), applied in
  `OnlinePlayerService.applyChannelSpeed`; Material Slider throws for values off its step grid.
- RYD in the designs: `Y/YtSentiment.kt` (pure: share, colour stops, per-design bar), drawn by
  `ytRateHold` in `Y/YtRateHold.kt`; 600 ms hover reveals the count in the dislike tile.
- Loudness: pure DSP `J/helpers/YtLoudnessMeter.kt` (BS.1770 gated), `YtPeakLimiter.kt` (look-ahead,
  −1 dBTP), `YtLeveller.kt`; media3 glue `J/helpers/YtLoudness.kt` (prefs `yt_loud*`, processor after
  Sonic). Pitfall: set per-format state in `onFlush`, not `onConfigure`; `isActive` is fixed per
  configure, so switching off takes effect at the next video. The limiter tail goes out once per
  end of stream (`tailSent`): media3 re-queues the end on every pass, and a fresh tail each time
  kept the speed-change drain from ending (sound stopped after the 2x hold). NewPipe exposes no loudness value.
- Music: `J/util/YtMusicRules.kt` (also used by `YtFeedEngine.isMusic`), `J/helpers/YtMusicPlayback.kt`
  (prefs `yt_music_audio_background`, `yt_music_from_start`); `OnlinePlayerService.configurePlayer`
  skips the watch-position restore for music.
- Watched marks: `Y/YtSeen.kt` (one shared `WatchPositionDao.observeAll()` flow, `Modifier.ytSeen`,
  pref `yt_seen_marks`), rules `Y/feed/YtSeenMark.kt`. Swipe tuning: `Y/feed/YtSwipeTuning.kt`,
  `YtSwipeCalibration.kt`, `YtSwipeCalibrationScreen.kt`, `YtSwipeSettings.kt` (keys `yt_swipe_*`).

## Round 16 notes

- Crash report: upstream `J/util/ExceptionHandler.kt` calls `J/util/YtCrashLog.record` first (files in
  `filesDir/yt_crash/`, newest 3, `new` marker); pure text/scrub/truncate in `J/util/YtCrashText.kt`.
  Dialogs (crash on next start, Settings → Diagnose) in `Y/YtDiagnosisUi.kt`, text in
  `J/util/YtDiagnosis.kt`. Showing the YT crash dialog clears upstream `error_log` (no double dialog).
- Titles: pure `J/util/YtTitleCalm.kt`, display only via `Y/YtTitles.kt` (`StreamItem.ytTitle`, LRU),
  prefs `yt_title_case`, `yt_title_marks`. Mini players still show the raw title.
- Hide watched: `Y/feed/YtSeenHide.kt`, flag `hideSeen` in `yt_feed_rules`, checked in
  `YtFeedEngine.passes` and `rememberYtVisible` (YtHomeKit). Search marks `Y/search/YtSeenRow.kt`.
- Mini restore: `Y/mini/YtMiniRestore.kt` (+ pure `YtMiniRestoreRules.kt`) keeps the closed session
  10 min in memory; `ytRememberClosed()` at the 4 close points in PlayerFragment, `forget()` in
  `NavigationHelper.navigateVideo`. The restart waits for the old service's end
  (`AbstractPlayerService.onDestroy` → `YtMiniRestore.serviceGone()`), at most the 1.5 s settle
  time since the close. Long-press: `Y/YtNavHold.kt` (`ytNavHoldArea` on the bar,
  `ytNavHoldTarget(id)` on the Abos tab, initial pass); classic bar via `setOnLongClickListener`.
  Start docked through `YtPageStart.start(docked = true)` (`takeStartDocked`), 1.5 s settle after close
  so the dying service does not clear the restored queue; host dock haptic muted once.
  Rise animation: `YtPageStart.start(rise = true)` → `YtMiniHost.armRise` hides the page after
  attach, the docking runs unseen, then `startRise` moves it up from behind the bar (reverse of the
  shared throw-away, cut by clipToBar) with the design's minimize curve, then `design.land()`.
  Pure rules `Y/mini/YtMiniRiseRules.kt`; motion off or rotation → no rise. The rise also starts
  450 ms after `armRise` if the docking's completion has not come (phone: every other restore
  waited long hidden). Step times (`YtMiniRestore.mark`) show in Diagnose as "Mini restore".

## Round 17 notes

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

## Round 18 notes

- Chapter double tap: pure `Y/player/YtChapterTap.kt` (reuses `YtHoldDirection` chapter maths),
  label `Y/player/YtChapterTapLabel.kt`, pref `yt_double_tap_chapter`; `CustomExoPlayerView`
  rewind/forward take an optional chapter target, `PlayerGestureController.ytDoubleTapFraction`.
- Battery: pure `J/helpers/YtBatteryRules.kt` (cap hysteresis, crossings, vibration tables,
  colours `YtBatteryCues`), prefs/receivers `J/helpers/YtBattery.kt` (`yt_battery_*`). Quality cap
  in the service module `J/services/YtBatteryQualityModule.kt` via `YtBatteryCapWatch` (in
  `YtBattery.kt`: battery, power saver and `yt_battery_*` prefs, alive for the player's whole life,
  state read synchronously so the first SET_RESOLUTION is capped); a manual pick exempts only its own
  height (`YtBatteryCapRules.pickAfter/exempt`). The Shorts player has its own watch
  (`YtShortsPlayer.applyBatteryCap`). The quality sheet shows the cap (`Y/player/YtBatteryCapLabel.kt`).
  Android drops USAGE_NOTIFICATION vibration in battery saver and silent mode: `YtBatteryVibeRules`
  decides, and the cue then shows a stronger visual instead (no ALARM-usage workaround). Cue in
  the page module `Y/player/YtBatteryCueModule.kt` + `YtBatteryCueView.kt` (scales the video
  frame, never the surface), vibration `Y/YtBatteryVibes.kt`. Preview dialog `Y/YtBatteryPreview.kt`
  (reuses YtBatteryCueView + YtBatteryVibes), pure timing/notes `J/helpers/YtBatteryPreviewRules.kt`.
- Storage: pure `J/util/YtStorageRules.kt`, `J/util/YtStorage.kt`, dialog `Y/YtStorageUi.kt`.
  Image cache via Coil's `diskCache.clear()`, WebView via `clearCache(true)` (cookies/`app_webview`
  never touched), `YtSelfUpdater.holdsApk()` protects a running update. LibreTube has no media or
  HTTP disk cache.

## Round 19 notes

- Watch positions: `J/services/YtPositionLedger.kt` (owned by AbstractPlayerService, listener on the
  player, 5 s tick on the service handler while playing, coalesced IO writes through the existing
  `updateWatchHistoryEntry`), pure `J/services/YtPositionRules.kt` (persist decision, < 5 s only
  after a seek, finished = DatabaseHelper rule or ≥ 95 %, switch/transition snapshot choice).
  The video id comes from the media item's extras, a snapshot only after that item was READY.
  Triggers: tick, pause, seek, `navigateVideo` (`beforeSwitch`), media item transition, ended,
  error, soft close, onDestroy (immediate), onTrimMemory. `saveWatchPosition()` now flushes the
  ledger; `PlayerHelper.saveWatchPosition` is unused. With a sync server every write is an HTTP call.

- Landscape / fullscreen exit: MainActivity handles rotation itself (manifest `configChanges`), so a
  page that turned inside fullscreen keeps its old layout. `unsetFullscreen(rebuildAfter = true)`
  rebuilds it via `restartActivityIfNeeded`, decided by pure `Y/player/YtPageLayoutRules.kt` (uses
  the orientation locked NOW, not the setting); `PlayerViewModel.ytWindowed` keeps it windowed while landscape (set by every user exit, cleared by portrait or explicit fullscreen; `YtPageLayoutRules.windowedAfter`, `autoFullscreenOnChange` = only a real rotation). The fullscreen Dialog's back also rebuilds; no portrait request during a recreate (`BaseActivity` checks `lastNonConfigurationInstance`).
  Cutout strips painted by `Y/YtSideFill.kt`. Minimize drag: `YtMinimizeModule.onConfigurationChanged`
  (module hook) drops remembered places; `Y/mini/YtMinimizeGeometry.drivable` (docked ≥ 48 dp below
  open) guards every drag start, else the classic drag takes over.

- Release notes: `release-notes/next.md` (German, `## Neu / Behoben / Geändert` + bullets) →
  `scripts/release-body.sh` in CI → release body (notes, `<!-- yt-details -->`, technical lines) →
  `UpdateChecker` fetches the bodies of builds (installed, offered], max 10, via
  `ExternalApi.getReleaseByTag`; pure parsing `J/util/YtReleaseNotes.kt`, rendering
  `Y/YtNotesText.kt`, scroll cap `Y/YtMaxHeightScrollView.kt`.

- Guide: content as data in `Y/YtGuide.kt` (sections → items, string ids, path text, target; stable
  keys, meant for the later first-start screen), pure filter/grouping `Y/YtGuideRules.kt`, screen
  `Y/YtGuideFragment.kt` in `nav_settings` (`ytGuide`), strings `yt_r19_guide_strings.xml`. A new
  user-facing feature gets a guide item in the same push. Items may carry a preference key
  (`setting`): passed as fragment arg `yt_jump_key`, consumed once in
  `BasePreferenceFragment.onViewCreated` → `Y/YtSettingJumpUi.kt` (scroll to centre, overlay pulse;
  pure `Y/YtSettingJump.kt`; a test checks every key exists in `res/xml`).

- Throw clip: `clipToBar` cuts while `YtDockMath.clipsToBar` says so — the bar's `YtDock.progress`
  OR the page's own transition at rest at the end (not peeking). `undock()` lets the bar go in a
  300 ms animator while the page still falls; slow first frames used to drop the cut mid-fall.

- Design switch without restart: `BaseActivity.ytApplyNewLook()` recreates while in front, so the
  window stays attached and MotionLayout copies its start/end sets from the XML before
  `YtNav.attach`/`float()` changed the constraints. `YtMotionAccess.takeViewsAgain` (called at the end
  of the YT layout setup in `MainActivity.onCreate`) reloads the scene when already attached.

- Parked page: `YtMiniHost.watchParked` (in the preDraw `boundsCheck`) moves a docked page that
  rests at its end but is still shifted, with no owner (drag/throw/rise), back to 0 after 300 ms
  (`YtDockMath.parked`, `PARKED_MS`). A throw always ends closed (`YtDockMath.throwOverdue`, 1500 ms,
  plus a posted 1600 ms fallback); the classic `closeMiniPlayer` closes on animation end or a 600 ms
  fallback; `SingleViewTouchableMotionLayout` releases a drag whose UP never came at the next DOWN.
  `MainActivity.writeContainerKeys()` re-writes the dock keyframes after `takeViewsAgain` reloads.
  The container (20 dp) is above the bar (4 dp): a mini player half BEHIND the bar = page shifted
  down and cut by `clipToBar`, not a wrong dock offset.

- Watched bar on hold: `Y/YtSeen.kt` — `ytSeen` (dim + badge) and `ytSeenTop` (bar + time-left
  pill, a Modifier.Node placed on the thumbnail Box AFTER its children, grow animated in the node,
  read in draw only). `Y/YtHeld.kt`: `YtHeldToken` per card instance, `ytHeldScope(token)` on the card
  root (found by ancestor lookup), `ytHeldMark()` for cards without the drag gesture (never consumes),
  `ytHeldFade()` for chips/play buttons; `YtCardDrag.held` is a set of tokens (pure `YtHeldSet`).
  Time left from the real position (`YtSeenMark.left`, `pickFit` for small thumbnails).
  Pitfall: anything drawn on the thumbnail image sits under the Box's later children.

## Round 21 notes

- Sound engine: contract `Y/sound/YtSound.kt` (`YtSoundFrame` level/bass/mid/high/punch/playing,
  `YtSound.frame` read in draw/layout only, `setReactive`). Analysis in `YtAudioTap` (shares the
  wave's FFT, runs while `soundListening`), features `J/helpers/YtSoundFeatures.kt` (K-weighted
  level, adaptive ranges, spectral-flux onset, silence gate), main-thread `Y/sound/YtSoundEngine.kt`
  (Choreographer, heard-moment sync via `leadMs`/`startOf`, idle when paused/background/motion off,
  half rate in power saver), shaping `Y/sound/YtSoundShaping.kt` (envelopes, punch budget 330 ms,
  `YtSwingGuard` ≤ 3 swings/s), player hook `J/services/YtSoundModule.kt`.
- Frequency (13th design): `YtDesign.FREQUENCY`, palette `YtPalettes.Frequency`, `YtSignature.FREQUENCY`,
  `Y/FrequencyHome.kt`, `FrequencyChrome.kt` (logo), `FrequencyTimeBar.kt`, `Y/mini/YtMiniFrequency*.kt`,
  pulse `Y/FrequencyPulse.kt` (+ pure `FrequencyPulseMath.kt`, slew-limited). `YtDesignRegistryTest`
  checks every design has palette, signature, mini player, nav, search kind, picker entry.
  A new design must be added there and everywhere the compiler/registry test points to.
- Frequency rework (build 118): fast motion channels `YtSound.motion` / `YtSound.bands` (unguarded:
  size, bounce, morph, rays) vs the guarded `YtSound.frame` (brightness: glow, veils, aura, time bar
  colour). Pure maths `Y/FrequencyMotion.kt` (stretch, kick detector, spring, sweeps ≥ 0.45 s apart,
  morph, tiers), visuals `Y/FrequencyVisuals.kt` (backdrop blobs: RuntimeShader on 33+, gradients
  below; header spectrum; nav edge wave). Tiers `FrequencyTiers.pick` (HIGH/MID/LOW, re-read 5 s).
  Diagnose shows a live "Sound" and "Frequency" line. Watch action buttons have no tap ripple here.
- Subscribe pill equalizer: `Y/FrequencyPillBars.kt` (pure geometry, rest arch, band->bar via
  `FrequencyMotion.barLevel`, contrast-safe alpha 0.16 on coral), hooked in `FrequencySubscribe`
  (YtWatchDesigns) between fill and label; heights only, alpha constant.

## Round 23 notes

- Feed topics: `YtFeedTopic` in `YtFeedPersonal.feedTopics` (same `yt_feed_rules` JSON; holds for all
  intents), session rule `Y/feed/YtFeedTopicsSession.kt` (30 min away), fetch `YtFeed.loadTopics`
  via `searchVideos`, mixing `YtFeedTopics.mix` (share accumulator, spread, dedup) inside
  `YtFeedEngine.rank`, keyword scorer `YtFeedTopicsWords.kt`, card chip in `ytSeenTop`
  (`Y/YtTopicChip.kt`), suggestion pill `Y/YtTopicSuggest.kt` (from `YtWatch.attach`, after 10 s
  played, 2nd video per topic per session). Old `YtTopic` searches renamed "Suchen" in strings only.

## Open points

- Not verified on a real phone: most round-12/13 features (the user tests on their phone).
- Shorts: swipe to the next short did not react in the emulator.
- Like/dislike InnerTube calls untested against YouTube; Movo and Side have no like button.
- Live streams: if errors remain, a missing User-Agent on segment requests is the main suspect.
- Mini restore: in Pop/Glas/Frost the active tab's blob also takes the hold and re-selects Abos.
- Gesture checks still show failures in Glas/YouTube that were not fully explained (no crash).
