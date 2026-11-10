# Handoff

Read this at the start of every session (after CLAUDE.md). It is the short memory of the project:
current state, how to work, dead ends and repeated mistakes. Keep it under ~200 lines: update it
at the end of every round, move finished items into the build log as one line, delete what is no
longer true. Details live in ARCHITECTURE.md (code map, read the section you need) and FEATURES.md
(user-facing list).

## Current state

- Latest release: build 118 (Frequency reacts clearly: morph, bounce, visualizer, tiers). 117:
  Frequency + sound engine. 115: watched bar on hold everywhere. 114: mini player never
  parked half behind the bar. 111: no hard edge after
  a design switch + throw-away stays behind the bar (run 110 cancelled by CI concurrency).
- NEXT SESSION (user's quota resets 2026-11-11), agreed to do then:
  1. (done in 114: mini player half behind the bar — guarded, cause inferred from code.)
  2. (done in 113: time left on the grown bar.)
  3. Music mode for the other designs: the user wanted the separate theme first — done as
     "Frequency" (build 117). The sound engine is ready (Round 21 notes); the music mode for the 12
     other designs is NOT started — ask before starting. Tune Frequency's amplitudes after the
     user hears it. 109: guide buttons jump to the
  setting. 108: guide in settings. 107: release notes in the update dialog. 106: landscape page after
  fullscreen, stuck minimize drag. 105: watch
  position ledger. 104: battery review fixes. 103: battery cue preview. 102 = round 18 ( chapter double tap, battery quality cap + cue, storage
  page). 101: soft close (dismissed mini player kept paused 60 s, instant restore). 100: restore starts when the old service is gone. 99: rise fallback,
  restore times in Diagnose. 98: rise animation. 97: fix for
  silent sound after the 2x hold. Docs-only pushes build nothing. Build 96 = round 16 ( crash report + Diagnose page, calm titles, hide watched,
  watched marks in search, mini player restore by holding "Abos"). Before: build 95. Repo `Tra1c0/YT-App` is PUBLIC (needed: when
  private, GitHub Actions does not start jobs because of billing). Patch repo
  `Tra1c0/YT-Revanced-Revanced-` is a separate, older project (branch `ccr-06da63aa-8a5ovt` only).
- Waiting for the user's phone: whether the update 95 → 96 works, and round-16 feedback;
  the app now stores the last install status in prefs `yt_self_updater/last_result`.
- The user reports only what does not work: no report = fine, not a priority, or untested but
  harmless. Do not wait for confirmations.
- Soft close touches the service (session removal, forwarder guards): if anything odd happens
  around closing/restoring or media buttons, look there first (ARCHITECTURE "Round 17 notes").
- Roadmap: the "Ideen" list in FEATURES.md (★ = chosen by the user). Next round planned: intro/outro
  per channel (with tolerance sliders), gesture edge margin, hidden/paused collection page, dead
  subscriptions, description link groups + sponsor links, mini restore for After + tab re-select quirk.
- Queue restore after restart: paused by the user; unreviewed WIP commit only on local branch
  `r16/queue` (not pushed, lost with this container). Restart it from `main` when asked.
- `MODIFICATIONS.md` (GPL change list) is current up to build 95 (rows split into LibreTube /
  YT files); add a row for every change to an upstream file.
- Unverified on a phone: most features of rounds 12-15 (see FEATURES.md status column).
- `J/helpers/YtDeviceEgg.kt` (hooks: YtActivityHooks.onStart, MainSettings.onResume): intentional
  device-gated one-time local behaviour, no network/data touched, no-op on every other device and
  after it has run once. Keep it; not a bug, not for user-facing notes.

## How to work (cheap and safe)

1. Read CLAUDE.md, this file, then only the ARCHITECTURE.md section for the area you touch.
2. Parallel work: one git worktree per track under `/home/user/wt/<name>` from `main`, one agent per
   track, agents do not edit FEATURES/ARCHITECTURE/HANDOFF (the lead merges and updates them once).
   Give agents the exact user quote, the files to start from and "compile before commit".
3. Compile locally before pushing: `export ANDROID_HOME=/opt/android-sdk; ./gradlew
   :app:compileReleaseKotlin --no-daemon -q --no-configuration-cache -Dorg.gradle.jvmargs=-Xmx6g
   -Pkotlin.daemon.jvmargs=-Xmx4g` plus the YT unit tests (command in ARCHITECTURE.md).
   Several agents compiling at once can run out of memory or hit Maven 429: wait and retry.
4. Release: merge into `main`, update FEATURES.md (+ ARCHITECTURE.md, this file) in the same push,
   and REPLACE `release-notes/next.md` with the user-facing German notes of this push
   (`## Neu` / `## Behoben` / `## Geändert`, bullets; no marker — CI adds it). The app collects the
   builds in between itself; a docs-only push gets a short note or none. A new user-facing
   feature also gets an item in the guide (`Y/YtGuide.kt` + strings).
   push → CI builds `build-N` (~15 min) → download the asset with `gh api` + curl
   (`Accept: application/octet-stream`), check SHA-256 against the release digest and the signer
   (`python3 -I scripts/apk-signer.py <apk>`, expected C3:8D:B9:09:…:5A:86), then send the user the
   Actions RUN link (`https://github.com/Tra1c0/YT-App/actions/runs/<id>`, artifact on that page).
   Not the direct release-asset link: on the phone that download hangs at the last percent.
5. Emulator checks only when worth it (they cost ~25-45 min and often fail for emulator reasons):
   `gh workflow run crash-check.yml -R Tra1c0/yt-app --ref <branch> -f build=<tag> -f design=...
   -f target=default -f test_video=true [-f screens=true] [-f gestures=true] -f signin_probe=false`.
   Read job logs with the MCP tool `get_job_logs` (a subagent, logs are long); screenshots come as
   release `screens-<run>`.
6. Answer the user in German, short, honest about what was tested. Never promise phone-tested.

## Dead ends (do not try again)

- Emulator `-prop ro.hw_timeout_multiplier=…`: ignored (only `qemu.*` props are accepted).
- Downloading Actions artifacts or raw job logs from here: blob storage is blocked (403). Use the MCP
  `get_job_logs` tool; publish needed files as release assets instead.
- `gpu=guest` emulator mode does not fix the Glas/Frost cold-start ANR; `target=default` (no Play
  services) plus the "Wait" watcher in crash-check.sh does.
- Hidden error dialogs (`hide_error_dialogs 1`) make Android kill a not-responding app at once.
- uiautomator cannot read the screen while the app hangs; find dialog buttons from window frames.
- GitHub Actions on a private repo: jobs never start (billing). Keep the repo public or fix billing.
- Signing locally: the release key exists only in the GitHub secret; never ask for it in chat.
- Self-update with `USER_ACTION_NOT_REQUIRED`: Play Protect takes the session and ends it as
  "aborted" even after "Installieren". Build 95 asks normally, retries twice, then hands the APK to
  the system installer.
- Relaunching the app from `MY_PACKAGE_REPLACED`: an OEM security app killed it; only a notification.
- CI cancels a running build when a newer push to main arrives: its release never exists. Push a
  round's fixes together, or carry the cancelled build's notes into the next release-notes/next.md.
- Finding a new release with `releases?per_page=N`: the list does not come newest first
  (build-99 sorted above build-102). Ask for the tag directly: `releases/tags/build-N`.
- Running many agents at once: weekly/5-hour usage limits hit mid-run. Prefer ≤6 parallel agents;
  resume a workflow with `resumeFromRunId`.

## Repeated mistakes (check these in every review)

- Text colour from `palette.text*` on a surface of different brightness (happened 3 times: intent
  chooser, quick sheet, Feed-Regler/account sheet). Use `Y/YtInk.kt` / `YtContrast`, aim 4.5:1.
- Gesture state not reset on CANCEL/abort/detach (hold tiles stuck after the minimize drag).
  Every gesture needs a reset on cancel, detach and when the parent takes the touch.
- MotionLayout copies constraints when attached: a recreate in front attaches at setContentView,
  so later constraint changes need `YtMotionAccess.takeViewsAgain` (design switch edge, build 111).
- MotionLayout: `setProgress` before attach is cached and jumps later; `transitionToStart()` before
  attach does nothing; keyframes are copied only on setup (re-apply dock offset on bar changes).
- SurfaceView video cannot be masked or blurred; draw shapes over it, and make those layers follow
  the picture's live bounds during drags (Formen cookie glitch).
- Compose spinner keyed on an equal data-class list never stops.
- `awaitHorizontalTouchSlopOrCancellation` ignores the vertical part (diagonal scrolls swiped cards).
- media3: empty timeline between queue items drops the foreground service; Android 12+ then refuses
  to restart it from the background (`YtForegroundHold`).
- Google Sans needs `includeFontPadding=false` in the classic screens (lines twice as tall).
- media3 audio processors: `queueEndOfStream` is called again on every pipeline pass while the
  stage before is ended; output for the end must be produced once (reset in onFlush), or the drain
  that every speed change runs never ends (sound gone, picture runs on). Test: YtLoudnessDrainTest.
- MainActivity handles rotation itself: a page that turned inside fullscreen must be rebuilt when
  fullscreen ends; cached geometry must be invalidated per configuration, not per size.
- Agents guess APIs when they cannot compile: always compile locally now.
- A fresh container has no Android SDK: install cmdline-tools to `/opt/android-sdk`
  (`platforms;android-36`, `build-tools;36.0.0`) and write `local.properties` per worktree.
  Parallel agents compile through one `flock` lock file (4 cores, 15 GB).

## Build log (one line per build, newest first)

- 118: Frequency rework: motion vs brightness channels, morph/bounce/spectrum/blobs, device tiers,
  live sound readout in Diagnose. If it still looks weak, ask for the Diagnose "Sound" line.

- 117: Frequency (13th design, reacts to sound) + sound engine.

- 115: watched bar on hold: all cards incl. Glas/Abos/shorts, pill above chips/gradients, per-instance.

- 114: guards against a mini player left shifted/cut behind the bar (parked-page watch, throw limit).
- 113: watched bar grows on hold + time-left pill (112 cancelled; its notes carried over).

- 112+: watched bar on home cards grows while the card is held (`YtCardDrag.held`), shrinks on release.

- 111: design switch no longer leaves a hard edge above the floating bar (MotionLayout re-read).

- 110: thrown mini player stays cut behind the bar for the whole fall (first throw after start).

- 109: guide buttons scroll to the exact setting and highlight it once.

- 108: guide ("Anleitung") at the top of settings; first-start screen with visuals is later.

- 107: update dialog shows release notes of all builds since the installed one (scrollable).

- 106: leaving fullscreen in landscape rebuilds a usable landscape page; minimize drag after
  rotating no longer sticks (stale geometry). Cause of the stuck drag inferred from code, not logged.

- 105: watch positions saved every 5 s and on every pause/seek/switch/end/close, old video's
  snapshot on a switch; Shorts excluded on purpose.

- 104: battery fixes from a review: cap from the first frame and on pref change, exemption only
  for the picked height, Shorts capped, cap shown in the quality sheet, honest texts, no silent
  vibration promise (power saver/silent → stronger visual).

- 103: battery cue preview in Player settings → Akku.

- 102: chapter jump on double tap, quality cap on low battery + battery cue (20/15/10/5 %),
  storage page.

- 101: soft close of the mini player (60 s, paused, no notification, instant restore via "Abos").

- 100: restore starts when the old player service is gone instead of a fixed 1.5 s after the
  close (Diagnose showed hold→navigate 450–800 ms; page→landed only ~250 ms).
- 99: restore rise no longer waits for a late docking completion (450 ms fallback); restore
  step times in Diagnose (line "Mini restore": hold/navigate/page/armed/docked|fallback/rise/landed,
  ms since the long press); ask the user for it when a restore feels slow.

- 98: mini player restore rises from behind the bar and lands with each design's animation.

- 97: loudness stage sent its limiter tail on every repeated end-of-stream, so the drain before a
  speed change (2x hold) never ended and the sound stopped until a seek (the docs-only push before built nothing).

- 96: crash report + Diagnose, calm titles (caps, !!!, emoji), hide watched (Feed-Regler), watched
  marks in search, mini player restore by holding "Abos" (no animation yet).

- 95: update flow (ask, retry ×2, system installer), speed/pitch recovery + per-channel speed,
  RYD bar and dislike count, loudness levelling, music audio-only/from start, watched marks,
  swipe sensitivity + calibration.
- 94: diagonal scroll no longer swipes cards, stuck hold tiles fixed, readable sheets (YtInk),
  channel page + compact numbers, landscape/rotation mini player, Abos tab speed + shuffled "Alle",
  update status "Wird installiert…", notification instead of relaunch.
- 93: like/dislike hold tiles, relative upload age, background queue crash (foreground hold), live
  recovery, search field morph, Formen drag glitches, quick sheet contrast.
- 92: dark mode for the light designs, in-app update download/install.
- 90: round 12 on `main` (14 interaction features, 12 mini players, Shorts view, search per design,
  user-report fixes); update check only offers higher builds.
- 60 and earlier: rounds 1-11 (designs, homes, watch pages, mini players, floating player with sound
  wave, sign-in, security audit); see docs/DESIGNS.md and git history.
