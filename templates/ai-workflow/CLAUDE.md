# <PROJECT> – instructions for every session

<One sentence: what the project is.> These rules hold for every session; each rule is stated here
once, details live in the file named next to it.

## Read order (read only what the task needs)

1. The session-start output (current state, ARCHITECTURE section lines, docs check summary).
2. `docs/HANDOFF.md`: state, open points, how to work (docs check, release), dead ends.
3. `docs/ARCHITECTURE.md`: only the section of the area you touch, and "Pitfalls" before a review.
   It is the code map: do not explore the code from scratch when the map answers it.
4. `docs/FEATURES.md` (what exists) and `docs/ROADMAP.md` (what is not built), written for the
   owner.

## Terms

- Owner: the person the project is for; writes in <LANGUAGE>.
- Lead: the session talking to the owner. It merges, pushes and updates the docs.
- Agent: a session the lead starts for one track (one worktree and branch). It writes code and
  tests only.
- Round: one batch of owner requests, from the first task to the push to `main`.
- Build: <what CI makes from a push to `main`, and where the owner gets it>.

## Do

- Answer the owner in <LANGUAGE>, short. Code comments, commits and internal docs in English.
- Build only what the owner asked for or what is under "Decided" in `docs/ROADMAP.md`; move an item
  out of the roadmap when it is built.
- Run the build and the tests locally before every code commit and before every push
  (commands: ARCHITECTURE.md → Build).
- Lead, in the same push as the change: update the docs as the table below says; run
  `python3 -I scripts/docs_sync.py --check` (fix errors; `--write` regenerates generated blocks).
- Set FEATURES.md status letters (meaning at its top) only from what was really checked.

## Don't

- Don't claim something was tested where it was not.
- Agents: don't edit any doc (`CLAUDE.md`, `docs/*`, `release-notes/`) and don't push; end with a
  report that lists new or renamed files with a one-line purpose and the user-visible changes.
- Don't push to `main` before the round is merged and checked.
- Don't commit secrets (keys, passwords, tokens).
- Don't edit text between `<!-- gen:… -->` and `<!-- /gen:… -->`: it is generated.

## Keep the docs current (lead, same push)

| When you … | update |
| --- | --- |
| add, change or remove a feature | `docs/FEATURES.md`, `docs/ROADMAP.md` |
| add, rename or delete a file, hook or pitfall | `docs/ARCHITECTURE.md` (its area) |
| push code that ships | `release-notes/next.md` (replace with this push's notes) |
| end a round | `docs/HANDOFF.md` (state, open points, dead ends, one build-log line) |

Prefer a new session per round over one endless session (context fills up and gets summarised).
