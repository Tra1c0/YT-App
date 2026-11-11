# YT – instructions for every session

YT is an Android app, a LibreTube fork. These rules hold for every session; each rule is stated
here once, details live in the file named next to it.

## Read order (read only what the task needs)

1. The session-start output (current state, ARCHITECTURE section lines, docs check summary).
2. `docs/HANDOFF.md`: state, open points, how to work (docs check, release, emulator), dead ends.
3. `docs/ARCHITECTURE.md`: only the section of the area you touch, and "Pitfalls" before a review.
   It is the code map: do not explore the code from scratch when the map answers it.
4. `docs/FEATURES.md` (what exists) and `docs/ROADMAP.md` (what is not built), both German, for the
   user. `docs/DESIGNS.md` only for design work.

## Terms

- User: the owner; writes German, tests on their phone.
- Lead: the session talking to the user. It merges, pushes and updates the docs.
- Agent: a session the lead starts for one track (one worktree and branch). It writes code and
  tests only.
- Round: one batch of user requests, from the first task to the push to `main`.
- build-N: the release CI makes from a push to `main`; N is the CI run number (tag `build-N`,
  app version `32.1-yt.N`). Run link: that CI run's page, `https://github.com/Tra1c0/YT-App/actions/runs/<id>`.

## Do

- Answer the user in German, short. Code comments, commits and internal docs in English;
  user-visible strings in `values/yt_*.xml` and `values-de/yt_*.xml` (same keys).
- Build only what the user asked for or what is under "Beschlossen" in `docs/ROADMAP.md`; move an
  item out of the roadmap when it is built.
- Compile and run the YT unit tests locally before every code commit and before every push.
- Lead, in the same push as the change: update the docs as the table below says; run
  `python3 -I scripts/docs_sync.py --check` (fix errors; `--write` regenerates generated blocks).
- Lead, after the push: verify the APK (SHA-256, signer) and send the user the run link
  (procedure: HANDOFF.md → Release).
- Set FEATURES.md status letters (meaning at its top) only from what was really checked.

## Don't

- Don't claim a feature is phone-tested unless the user said so.
- Agents: don't edit any doc (`CLAUDE.md`, `docs/*`, `MODIFICATIONS.md`, `release-notes/`) and
  don't push; end with a report that lists new or renamed files with a one-line purpose, the
  upstream (non-YT) files changed and the user-visible changes.
- Don't push to `main` before the round is merged and checked (a newer push cancels a running
  build).
- Don't put model names or AI identifiers in code, docs or commits.
- Don't commit signing keys or passwords; the release key lives only in the GitHub secret.
- Don't edit text between `<!-- gen:… -->` and `<!-- /gen:… -->`: it is generated.

## Keep the docs current (lead, same push)

| When you … | update |
| --- | --- |
| add, change or remove a feature | `docs/FEATURES.md`, guide item in `Y/YtGuide.kt`, `docs/ROADMAP.md` |
| add, rename or delete a file, hook or pitfall | `docs/ARCHITECTURE.md` (its area) |
| change an upstream LibreTube file | `MODIFICATIONS.md` (one row) |
| push code that builds | `release-notes/next.md` (replace with this push's German notes) |
| end a round | `docs/HANDOFF.md` (state, open points, dead ends, one build-log line) |

Prefer a new session per round over one endless session (context fills up and gets summarised).
