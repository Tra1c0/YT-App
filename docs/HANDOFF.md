# Handoff

The project's short memory, for the lead (terms: `CLAUDE.md` → Terms). Read it at the start of
every session after `CLAUDE.md`. The lead updates it at the end of every round: current state,
open points, new dead ends and repeated mistakes, one build-log line per build. Delete what is no
longer true; history stays in git. Code facts and code pitfalls belong in `docs/ARCHITECTURE.md`,
not here.

## Current state

- Latest release: build 129 (News stage 1: pinch-to-open card overlay) on `main`. 128: update
  notification. 127: Synthwave grid reaches the horizon. News stages 2–4 are in ROADMAP
  "Beschlossen"; start stage 2 after the user has seen stage 1. 126: queue survives
  restarts. Build 125 = docs system only
  (`scripts/docs_sync.py`, hooks, `templates/ai-workflow/`), app code identical to 124.
- Next work: `docs/ROADMAP.md` → "Beschlossen", top to bottom. The music mode for the designs
  that do not react to sound yet is in that list but NOT released by the user: ask before
  starting it.
- Frequency: tune its amplitudes only after the user has heard it and asks.
- Queue restore after restart is built (build 126). Open: starting a download replaces a waiting
  restored online queue; emulator screenshots may show the "Weiter" pill from an earlier run.
- The user reports only what does not work: no report = fine, not a priority, or untested but
  harmless. Do not wait for confirmations.

## Open points

- Not verified on a real phone: most features of rounds 12–15 (FEATURES.md status column).
- Shorts: swiping to the next short did not react in the emulator.
- Like/dislike InnerTube calls are untested against YouTube.
- Live streams: if errors remain, a missing User-Agent on segment requests is the main suspect.
- Mini restore: missing in After; in Pop/Glas/Frost the active tab's blob also takes the hold and
  re-selects Abos (ROADMAP "1 (Rest)").
- Gesture checks still show failures in Glas/YouTube that were not fully explained (no crash).
- Causes inferred from code only, not seen in a log: the stuck minimize drag after rotating
  (build 106) and the mini player left half behind the bar (build 114).
- If Frequency looks weak or Synthwave stutters on the phone: ask the user for the Diagnose lines
  "Sound" and "Frequency".

## How to work

### Session start

The SessionStart hook (`.claude/hooks/session-start.sh`, registered in `.claude/settings.json`)
prints the "Current state" block above, the start line of every ARCHITECTURE.md section (use it as
the Read offset) and the docs check summary. It never blocks a session.

### Repositories

- `Tra1c0/YT-App`: this app. It is public and must stay public (GitHub Actions do not start jobs
  on a private repo here because of billing).
- `Tra1c0/YT-Revanced-Revanced-`: a separate, older patch project (branch `ccr-06da63aa-8a5ovt`
  only). Do not touch it unless the user asks.

### Parallel work (lead and agents)

- One git worktree per track: `/home/user/wt/<track>` on branch `r<round>/<track>` from `main`; one
  agent per track; at most 6 agents at once (usage limits hit mid-run otherwise; a workflow
  resumes with `resumeFromRunId`).
- Give each agent the exact user quote, the files to start from, "compile before commit" and the
  agent rules from `CLAUDE.md`.
- The lead merges the branches into `main`, then updates the docs once (see Release).

### Compile and test

- Commands and SDK setup: ARCHITECTURE.md → "Build, CI, tests, docs system".
- Parallel agents compile through one `flock` lock file (4 cores, 15 GB): out-of-memory or Maven
  429 → wait and retry.

### Docs check (`scripts/docs_sync.py`, config `scripts/docs_sync.json`)

- `python3 -I scripts/docs_sync.py --check`: prints errors and warnings, exits 1 on errors.
- `python3 -I scripts/docs_sync.py --write`: regenerates the generated blocks (design lists,
  ARCHITECTURE contents and file inventory) and the template copies (error 5), then checks.
- `python3 -I scripts/docs_sync.py --toc docs/ARCHITECTURE.md`: the sections with line ranges.
- Errors (fail CI on `main` and the pre-commit hook on `main`):
  1. a generated block is out of date → run `--write`;
  2. a YT Kotlin file (`Yt*.kt` anywhere in `app/src/main/java`, or anything under `Y/`) is not
     named in ARCHITECTURE.md → add it to its area: file name, class stem or a backticked glob
     (`YtMiniClay*.kt`); generated blocks do not count;
  3. FEATURES.md says another number of designs than the `YtDesign` enum has;
  4. string parity (`scripts/check-strings.sh`) fails;
  5. a copy in `templates/ai-workflow/` (`scripts/docs_sync.py`, `scripts/install-hooks.sh`,
     `.claude/hooks/session-start.sh`) differs from the repo's file → edit the repo's file, then
     `--write` copies it.
- Warnings (never fail): app code changed but `release-notes/next.md` is empty; an upstream
  LibreTube file changed but `MODIFICATIONS.md` did not; ARCHITECTURE.md names a file or glob that
  does not exist; HANDOFF.md over 400 lines. The diff for these is: staged changes (pre-commit),
  else everything since the merge base with `main` plus uncommitted changes, else the last commit.
- Where it runs: session start (summary only); CI `build.yml` before the Gradle build (fails the
  job only on `main`; test branches get annotations); CI `docs-check.yml` on pushes to `main` that
  build nothing (docs, scripts); optional pre-commit hook: `bash scripts/install-hooks.sh` (blocks
  commits on `main` only; on other branches it prints the problems). Bypass once: `git commit
  --no-verify`.

### Release (lead only)

1. Merge the round's branches into `main`; compile and run the unit tests on `main`.
2. Update the docs as `CLAUDE.md` → "Keep the docs current" says, in the same push. Release
   notes: REPLACE `release-notes/next.md` with the German notes of this push (`## Neu` /
   `## Behoben` / `## Geändert`, bullets, no marker: CI adds it). Never leave the previous build's
   notes in it: the app would show them twice. A push that builds nothing (docs, scripts) leaves
   the file as it is; the next push that builds replaces it.
3. `python3 -I scripts/docs_sync.py --check` must be green; then push `main` once (push a round's
   fixes together: a newer push cancels the running build and its release never exists; if that
   happens, carry the cancelled build's notes into the next `release-notes/next.md`).
4. CI builds `build-N` (~15 min). Fetch the release by tag (`gh api
   repos/Tra1c0/YT-App/releases/tags/build-N`, never a `?per_page` list: it is not sorted newest
   first), download the APK with `curl -H "Accept: application/octet-stream"`, check its SHA-256
   against the release digest and its signer with `python3 -I scripts/apk-signer.py <apk>`:
   expected
   `C3:8D:B9:09:4F:B4:67:51:FE:C3:45:FB:DB:66:49:2F:28:5A:D1:B5:CA:02:7D:DC:51:08:8B:49:55:B8:5A:86`.
5. Send the user the run link (`https://github.com/Tra1c0/YT-App/actions/runs/<id>`, the APK is
   an artifact on that page). Never the direct release-asset link: on the phone that download
   hangs at the last percent.

### Emulator checks (only when worth it: 25–45 min, often fail for emulator reasons)

`gh workflow run crash-check.yml -R Tra1c0/yt-app --ref <branch> -f build=<tag> -f design=...
-f target=default -f test_video=true [-f screens=true] [-f gestures=true] -f signin_probe=false`.
Read job logs with the MCP tool `get_job_logs` in a subagent (logs are long); screenshots come as
release `screens-<run>`.

## Dead ends (process and environment; code dead ends are in ARCHITECTURE.md)

- Emulator `-prop ro.hw_timeout_multiplier=…`: ignored (only `qemu.*` props are accepted).
- Downloading Actions artifacts or raw job logs from here: blob storage is blocked (403). Use the MCP
  `get_job_logs` tool; publish needed files as release assets instead.
- `gpu=guest` emulator mode does not fix the Glas/Frost cold-start ANR; `target=default` (no Play
  services) plus the "Wait" watcher in `crash-check.sh` does.
- Hidden error dialogs (`hide_error_dialogs 1`) make Android kill a not-responding app at once.
- uiautomator cannot read the screen while the app hangs; find dialog buttons from window frames.
- Signing locally: the release key exists only in the GitHub secret; never ask for it in chat.

## Repeated mistakes (process; code pitfalls: ARCHITECTURE.md → Pitfalls)

- Agents guessed APIs when they could not compile: always compile locally.
- A fresh container has no Android SDK: install it first (ARCHITECTURE.md → Build) and write
  `local.properties` per worktree.

## Build log (one line per build, newest first; older builds in git)

- 129: News stage 1 (pinch apart on the home opens a card pager; news channels + topics).
- 128: background update check + notification per new build.
- 127: Synthwave floor grid reaches the horizon (rows up to ROW_MIN, tier count = glow only).
- 126: queue survives every restart (saved file, "Weiter" pill, mini restore uses the same copy).
- 125: docs system: self-checking code map, generated blocks, session-start hook, template.
- 124: Synthwave: slim feathered edge palms, calmer sky, home starts under the header.
- 123: Synthwave (14th design, animated retro scene, subtle music reaction).
- 122: feed topics with keyword suggestions; landscape stays windowed, back rebuilds on first try.
- 121: Frequency subscribe pill shows a Deezer-style equalizer (heights only, label ≥ 4.5:1).
- 120: removed a one-off device-gated startup behaviour again (build 119 still carries it).
- 118: Frequency rework: motion vs brightness channels, morph/bounce/spectrum/blobs, device tiers,
  live sound readout in Diagnose.
- 117: Frequency (13th design, reacts to sound) + sound engine.
- 115: watched bar on hold: all cards incl. Glas/Abos/Shorts, pill above chips/gradients, per instance.
- 114: guards against a mini player left shifted/cut behind the bar (parked-page watch, throw limit).
- 113: watched bar grows on hold + time-left pill (112 cancelled; its notes carried over).
- 111 and earlier: 111 design switch edge, 110 throw cut, 109/108 guide, 107 release notes in the
  update dialog, 106 landscape after fullscreen, 105 position ledger, 102–104 chapter tap, battery
  cap and cue, storage, 97–101 mini restore, rise, soft close, loudness drain fix, 96 crash
  report + Diagnose, 95 update flow, ≤ 94 rounds 1–14 (designs, homes, watch pages, mini players,
  floating player, sign-in, security audit).
