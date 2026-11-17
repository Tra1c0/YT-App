# Handoff

The project's short memory, for the lead (terms: `CLAUDE.md` → Terms). Read it at the start of
every session after `CLAUDE.md`. The lead updates it at the end of every round: current state,
open points, new dead ends and repeated mistakes, one build-log line per build. Delete what is no
longer true; history stays in git. Code facts and code pitfalls belong in `docs/ARCHITECTURE.md`,
not here.

## Current state

- Latest release: build 145 on `main` (per-build details: Build log below). Recent rounds built:
  News overlay (pinch on the home; stories; cited summaries with a provider choice: OpenAI,
  Anthropic, Gemini, Mistral, OpenAI-compatible), update notification + UnifiedPush/ntfy push +
  watch mode, notification split (app updates vs new videos), queue survives restarts, Synthwave.
- The user still has to add the GitHub secret `YT_PUSH_URL`; until then CI skips both push steps.
- Round 29 built: seamless minimize (shared part + Formen pilot), description link groups with ad
  folding (25/26), dead subscriptions finder (22). Seamless minimize for the other designs is PAUSED by the
  user ("gerade unwichtig"); when resumed: Frequency and Synthwave first, each with its own character
  (not a copy of Formen). The user uses Formen, Frequency, Synthwave most.
- SSI and theme builder (TB) follow `docs/ETAPPEN.md` strictly: stage N+1 of either starts only after
  the user wrote "SSI-N abgenommen" AND "TB-N abgenommen"; no report is NOT acceptance there.
- Round 38 (2026-11-17) was a quality pass over the whole app (tool-driven: lint, R8 report, emulator
  run, the user's Diagnose dump). Waiting for the user: SSI-3 and TB-3 acceptance, then the criteria
  for SSI-4 and TB-4. Start a NEW session for the next round: the long session cost the user most of
  a weekly quota (context over 600k tokens; every step re-reads it).
- Focus (user, 2026-11-15): the SSI/TB stages only; roadmap items are paused. Nothing usable exists
  for items 1 (Rest), 8/9, 12, 18 (the `r34/*` worktrees held no changes); only the seamless
  minimize follow-up had uncommitted work in a local worktree. Restart all of them fresh.
- Round 39 (2026-11-17): the user asked for two things (seen marks that vanish; update dialog pauses
  and blooms) and gave a broad mandate ("Versetzt dich in die Rolle eines Software engineers … Meine
  Basis ist nur der Anfang"), then narrowed it: "Aber bitte mit Absprache". Reading (the lead's):
  propose freely, build only after the user's yes. The proposals and four open questions are in
  `docs/ROADMAP.md` → "Vorschläge vom 17.11.2026". One proposal was built before the narrowing and
  is NOT released: "Kurzfassung" for any video (branch `r39/kurz`, kept on the session branch, see
  Open points); merge it only when the user says yes.
- Next work: `docs/ROADMAP.md` → "Beschlossen", top to bottom. The music mode for the designs
  that do not react to sound yet is in that list but NOT released by the user: ask before
  starting it.
- Frequency: tune its amplitudes only after the user has heard it and asks.
- Queue restore after restart is built (build 126). Open: starting a download replaces a waiting
  restored online queue; emulator screenshots may show the "Weiter" pill from an earlier run.
- The user reports only what does not work: no report = fine, not a priority, or untested but
  harmless. Do not wait for confirmations.

## Open points

- Round 39, untested on the phone: the seen ledger (a finished video keeps its mark after it is
  opened again; marks on cards without a duration; the hold menu rows; LibreTube's "mark as
  watched/unwatched"; the history's "also clear watch positions" box; seeding on the first start:
  Diagnose "Gesehen" shows the entries) and the update dialog's pause and bloom (pause from the
  player page, the mini player, audio-only and with the dialog opened in the settings; resume only
  when the dialog paused it and no update was started; the video frame inside the bloom; no leftover
  overlay). The bloom's look was only judged on emulator screenshots run through the maths.
  Emulator runs 62 and 63 (build 145): no crash in shapes, frequency, custom and synthwave; start,
  player page and mini player look normal. In run 62 the emulator itself crashed twice during the
  synthwave leg (segmentation fault of the emulator process), yet `scripts/crash-check.sh` printed
  "No crash in any of the designs" and the run was green: a design whose emulator went away has no
  verdict, so check the log for "the emulator went during the run" and the screenshots release for
  missing designs (run 63, synthwave alone, passed). The script should report such a leg as
  undecided (proposal A in the roadmap).
- Seen rule, known limits: a video left more than 60 s before its end (long credits) gets no mark
  (the user can mark it by hand; a more lenient rule is one of the open questions); a mark needs
  playing in the same process (a stretch of min(60 s, 10 %) without a jump, or half of the video),
  so a skip over long credits right after a restart falls back to the old position rule.
- "Kurzfassung" for any video (round 39): built by an agent, compiles and its tests pass, not
  reviewed by the lead, not merged. It waits for the user's decision.
- Round 38 (quality pass), untested on the phone: start guards (Diagnose "Start"), lazy SSI and
  ambilight layers, fetch gate (Diagnose "Abruf-Gate"), SSI index health (follow-up runs while
  charging, window + cap + tombstones with DB v3, vector release; check that the backlog in the
  Diagnose "SSI" text shrinks), storage rows, archive text retry and "Mehr laden", Synthwave section
  headers, the own design's page background. Emulator runs 60 (build 143) and 61 (build 144):
  no crash in shapes, frequency, synthwave and custom; the screenshots of run 61 confirm the Synthwave
  header and the own design's home.
- What the user's phone proved (Diagnose of 2026-11-17, build 143): archive downloads against live
  YouTube, AI risk and script with the user's provider, SSI model download (hub-listed checksum),
  word timings (json3), the model's charsmap. Never used on the phone so far: animator, vector pen,
  SSI evidence views, hit feedback, benchmark. SSI-1..2 and TB-1..2 were accepted on the user's word
  without measured values; SSI-3 and TB-3 wait for acceptance (docs/ETAPPEN.md).
- SSI leftovers: model checksum not pinned in code (the hub's listing is used); the semantic cut-off
  stays untuned until the user imports benchmark examples; AI providers may reject the stance enum
  schema (lenient parser); if the 250,000-passage cap gets tight, binary vectors with rescoring would
  cut vector memory about 8 times.
- Theme builder leftovers: gradient stop colours and icon tint cannot animate; the editor's mini
  preview draws a THUMBNAIL slot at its layout place; no free transform origin; booleans of big paths
  take milliseconds.
- Lint after round 38: 170 findings in YT files (2295 before; app/lint.xml ignores MissingTranslation
  for the yt_ files). Errors left: UnsafeOptInUsageError 16 (media3: lint wants
  `androidx.annotation.OptIn`, the Kotlin `@OptIn` added in round 38 does not count), NewApi 10 in
  `FrequencyVisuals.kt` (10) (check the SDK_INT guards; harmless on the user's Android 16),
  StringFormatInvalid 9 (a literal % that is never formatted). Left on purpose: StaticFieldLeak (6
  singletons that dispose their references), PluralsCandidate, UseKtx.
- APK size 29 MB: 12.9 MB is the ONNX runtime (arm64 only). Ideas in ROADMAP "Eventuell": load the
  runtime on demand; replace the Anthropic Java SDK by a plain HTTP call (needs a live key to test).
- Commit trailers: CLAUDE.md forbids model names and AI identifiers in commits, yet commits up to
  2026-11-16 carry Co-Authored-By and session lines. Since round 38 commits have none; history is not
  rewritten. The user was asked to confirm this reading.
- Archive, untested paths: oEmbed answers for private/deleted videos, export and player UI, the three
  audio AI paths (endpoints from search summaries; OpenAI's diarize model reportedly retires
  2027-02-26).
- Untested on the phone from earlier rounds: APK clean-up (build 137), stream error card (135; YouTube
  answers private videos with LOGIN_REQUIRED "Please sign in"), round 29 (Formen minimize constants in
  `YtMiniMerge.kt`, description ad detection, dead subs in account mode, `@handle` channels), News
  (129–131: card picture during the grow, caption download, default models per provider), most
  features of rounds 12–15 (FEATURES.md status column).
- Update push (build 133): the user must add the GitHub secret `YT_PUSH_URL`; the app is registered
  with ntfy but no push has arrived yet (Diagnose 2026-11-17).
- Keyboard integration (YT command interface, deep links, PROCESS_TEXT, summary for pasted links)
  is only an idea in ROADMAP "Unsortiert"; ask the user what their keyboard app can call and
  whether it is signed with the same key (signature permission) before building.
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
- WebFetch of platform.openai.com, developers.openai.com, unifiedpush.org, codeberg.org and
  github.com pages is blocked here: use WebSearch summaries or artifacts on repo1.maven.org, and say
  that the source was not opened.
- Deleting a remote branch (`git push --delete`) is refused by the proxy (403); leave merged
  helper branches (e.g. `r28/round`) for the user to delete.

## Repeated mistakes (process; code pitfalls: ARCHITECTURE.md → Pitfalls)

- Agents guessed APIs when they could not compile: always compile locally.
- Integrating a third-party service without asking which one (the summaries were built on
  Anthropic; the user wanted ChatGPT): ask the provider/vendor before building.
- Pushing an unfinished round to `main` because a stop hook nagged: keep the round on a local
  branch (`rNN/round`) and back it up to the session branch instead; `main` only gets merged work.
- A fresh container has no Android SDK: install it first (ARCHITECTURE.md → Build) and write
  `local.properties` per worktree.
- Accepting a stage on the user's word alone: ask for the Diagnose dump first; it shows what was really
  used and it revealed two real SSI problems in round 38 (vector backlog, unbounded index).
- Auditing by reading code: run `bash scripts/audit.sh` and the emulator check first; they cost no
  model tokens and found what reviews had missed (own design without a page background, Synthwave
  header cut off).
- One endless session: use a new session per round (CLAUDE.md) and give agents tight briefs.

## Build log (one line per build, newest first; older builds in git)

- 145: seen ledger (marks stay after a video is opened again, manual mark, backup, the history's "also clear watch positions" box works), update dialog pauses playback and blooms the app behind it. "Kurzfassung" for any video is built but NOT in this build (session branch, waits for the user).
- 144: quality pass (round 38): start guards, fetch gate, SSI index health (backlog first, window + cap, vector release), storage rows, backup rules, lint fixes, Synthwave header fit, own design page background.
- 143: SSI-3 (evidence: relations with verified quotes, comparison, timeline, report, saved research), TB-3 (vector: pen, booleans, masks, morph, motion paths, recording).
- 142: SSI/TB stage 1/2 gap fixes (classic SSI entry, charsmap, calibration, json3 gate, parallel scan; mini player node tree, in-app mini/bar animations, state looks, image import, reorder spring).
- 141: SSI-2 (AI re-rank, meaning-based passages, checked spans, feedback learning, in-app benchmark), TB-2 (animator).
- 140: SSI model checksum from the hub's file listing (139 failed: stale generated docs block; 140 started by hand).
- 138: SSI-1 (moment search), TB-1 (own design "Eigenes" + theme editor), Ambilight; APK 29.4 MB.
- 137: old YT APK / old build clean-up on the storage page, quiet clean-up after updates.
- 136: archive of vanished videos (tiers, capacity slider, risk estimator with local learning + optional AI, script with speakers, settings, player, badge).
- 135: stream error card instead of endless loading, queue kept, private/deleted videos leave feed and queue.
- 134: seamless minimize (shared part, Formen pilot), description link groups + ad folding, dead subscriptions finder.
- 133: update push (UnifiedPush/ntfy, CI secret `YT_PUSH_URL`), watch mode, notification split; CI runs util/services YT tests too.
- 132: second finger cancels card swipe, hold menu, drag and search lens.
- 131: news summaries: provider choice with quote verification for non-Anthropic providers.
- 130: News stages 2–4 (grow out of the card, depth, design haptics; stories; cited summaries).
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
