# Handoff

The project's short memory, for the lead (terms: `CLAUDE.md` → Terms). Read it at the start of
every session after `CLAUDE.md`. The lead updates it at the end of every round: current state,
open points, new dead ends and repeated mistakes, one build-log line per build. Delete what is no
longer true; history stays in git. Code facts and code pitfalls belong in `docs/ARCHITECTURE.md`,
not here.

## Current state

<!-- The session-start hook prints this section (up to ~10 lines): keep it short and current. -->
- Latest release: <build/version> on `main`.
- Next work: `docs/ROADMAP.md` → "Decided", top to bottom.
- Waiting for the owner: <nothing / what>.

## Open points

- <Known problem or unverified behaviour, with where to look.>

## How to work

### Session start

The SessionStart hook (`.claude/hooks/session-start.sh`, registered in `.claude/settings.json`)
prints the "Current state" block above, the start line of every ARCHITECTURE.md section (use it as
the Read offset) and the docs check summary. It never blocks a session.

### Parallel work (lead and agents)

- One git worktree per track, on its own branch from `main`; one agent per track.
- Give each agent the exact owner quote, the files to start from, "build and test before commit"
  and the agent rules from `CLAUDE.md`.
- The lead merges the branches into `main`, then updates the docs once (see Release).

### Docs check (`scripts/docs_sync.py`, config `scripts/docs_sync.json`)

- `python3 -I scripts/docs_sync.py --check`: prints errors and warnings, exits 1 on errors.
- `python3 -I scripts/docs_sync.py --write`: regenerates the generated blocks, then checks.
- `python3 -I scripts/docs_sync.py --toc docs/ARCHITECTURE.md`: the sections with line ranges.
- Errors: a generated block is out of date (→ `--write`); a source file is not named in
  ARCHITECTURE.md (→ add it to its area by name, stem or backticked glob); a configured count or
  command fails; a configured copy differs (→ `--write`).
- Warnings (never fail): code changed but `release-notes/next.md` is empty; a name in the map
  points at nothing; HANDOFF.md is very long.
- Where it runs: session start (summary), CI (`.github/workflows/docs-check.yml`), optional
  pre-commit hook (`bash scripts/install-hooks.sh`; blocks on `main` only).

### Release (lead only)

1. Merge the round's branches into `main`; build and test on `main`.
2. Update the docs as `CLAUDE.md` → "Keep the docs current" says, in the same push. Replace
   `release-notes/next.md` with this push's notes (never keep the previous ones).
3. `python3 -I scripts/docs_sync.py --check` must be green; push `main` once.
4. <How a build is verified and what link the owner gets.>

## Dead ends (process and environment; code dead ends are in ARCHITECTURE.md)

- <What was tried, why it does not work, what to do instead.>

## Repeated mistakes (process; code pitfalls: ARCHITECTURE.md → Pitfalls)

- <Mistake that happened more than once, and the rule that prevents it.>

## Build log (one line per build, newest first; older builds in git)

- <N>: <what changed, in a few words>.
