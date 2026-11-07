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

- `J/helpers/YtDesign.kt`: enum of the 12 designs (key, overlay style, brand font, `light`).
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

## Open points

- Not verified on a real phone: most round-12/13 features (the user tests on their phone).
- Shorts: swipe to the next short did not react in the emulator.
- Like/dislike InnerTube calls untested against YouTube; Movo and Side have no like button.
- Live streams: if errors remain, a missing User-Agent on segment requests is the main suspect.
- Gesture checks still show failures in Glas/YouTube that were not fully explained (no crash).
