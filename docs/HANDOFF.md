# Handoff

Read this at the start of every session (after CLAUDE.md). It is the short memory of the project:
current state, how to work, dead ends and repeated mistakes. Keep it under ~200 lines: update it
at the end of every round, move finished items into the build log as one line, delete what is no
longer true. Details live in ARCHITECTURE.md (code map, read the section you need) and FEATURES.md
(user-facing list).

## Current state

- Latest release: build 96 (round 16: crash report + Diagnose page, calm titles, hide watched,
  watched marks in search, mini player restore by holding "Abos"). Before: build 95. Repo `Tra1c0/YT-App` is PUBLIC (needed: when
  private, GitHub Actions does not start jobs because of billing). Patch repo
  `Tra1c0/YT-Revanced-Revanced-` is a separate, older project (branch `ccr-06da63aa-8a5ovt` only).
- Waiting for the user's phone: whether the update 95 → 96 works, and round-16 feedback;
  the app now stores the last install status in prefs `yt_self_updater/last_result`.
- Roadmap: the "Ideen" list in FEATURES.md (★ = chosen by the user). Next round planned: intro/outro
  per channel (with tolerance sliders), gesture edge margin, hidden/paused collection page, dead
  subscriptions, description link groups + sponsor links, mini restore animation + After design.
- Queue restore after restart: paused by the user; unreviewed WIP commit only on local branch
  `r16/queue` (not pushed, lost with this container). Restart it from `main` when asked.
- `MODIFICATIONS.md` (GPL change list) is current up to build 95 (rows split into LibreTube /
  YT files); add a row for every change to an upstream file.
- Open bug (build 96, not reproducible yet): after a long session without restart, holding the
  video (long press 2x fast forward) left the picture running without sound until the next seek;
  gone after restarting the app, not tied to the loudness switch alone (a switch change applies only
  at the next video). Idea: add audio pipeline state (speed, processors active, last audio error)
  to the Diagnose text so the next report carries it.
- Unverified on a phone: most features of rounds 12-15 (see FEATURES.md status column).

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
   push → CI builds `build-N` (~15 min) → download the asset with `gh api` + curl
   (`Accept: application/octet-stream`), check SHA-256 against the release digest and the signer
   (`python3 -I scripts/apk-signer.py <apk>`, expected C3:8D:B9:09:…:5A:86), then send the user the download link
   `https://github.com/Tra1c0/YT-App/releases/download/build-N/YT-32.1-yt.N.apk`.
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
- Running many agents at once: weekly/5-hour usage limits hit mid-run. Prefer ≤6 parallel agents;
  resume a workflow with `resumeFromRunId`.

## Repeated mistakes (check these in every review)

- Text colour from `palette.text*` on a surface of different brightness (happened 3 times: intent
  chooser, quick sheet, Feed-Regler/account sheet). Use `Y/YtInk.kt` / `YtContrast`, aim 4.5:1.
- Gesture state not reset on CANCEL/abort/detach (hold tiles stuck after the minimize drag).
  Every gesture needs a reset on cancel, detach and when the parent takes the touch.
- MotionLayout: `setProgress` before attach is cached and jumps later; `transitionToStart()` before
  attach does nothing; keyframes are copied only on setup (re-apply dock offset on bar changes).
- SurfaceView video cannot be masked or blurred; draw shapes over it, and make those layers follow
  the picture's live bounds during drags (Formen cookie glitch).
- Compose spinner keyed on an equal data-class list never stops.
- `awaitHorizontalTouchSlopOrCancellation` ignores the vertical part (diagonal scrolls swiped cards).
- media3: empty timeline between queue items drops the foreground service; Android 12+ then refuses
  to restart it from the background (`YtForegroundHold`).
- Google Sans needs `includeFontPadding=false` in the classic screens (lines twice as tall).
- Agents guess APIs when they cannot compile: always compile locally now.
- A fresh container has no Android SDK: install cmdline-tools to `/opt/android-sdk`
  (`platforms;android-36`, `build-tools;36.0.0`) and write `local.properties` per worktree.
  Parallel agents compile through one `flock` lock file (4 cores, 15 GB).

## Build log (one line per build, newest first)

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
