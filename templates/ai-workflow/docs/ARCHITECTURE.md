# <PROJECT> – code map

The code map for whoever changes the code: where each part lives, how the parts connect and what
broke before. Read only the section of the area you touch: its start line is printed at session
start, or run `python3 -I scripts/docs_sync.py --toc docs/ARCHITECTURE.md`.

Rules for this file: every file matched by `map.sources` in `scripts/docs_sync.json` is named here,
by file name, stem or a backticked glob such as `Name*.ext`; `--check` fails otherwise. Facts are
sorted by area, never by date (history lives in git). Each `## ` section starts with a `> ` line:
that line becomes its entry in the contents. Blocks between `<!-- gen:… -->` and
`<!-- /gen:… -->` are generated: do not edit them, run `python3 -I scripts/docs_sync.py --write`.

Short forms: <`S/` = `src/myapp/` (also set in `aliases` in the config)>.

## Contents

<!-- gen:toc -->
<!-- /gen:toc -->

## Basics

> Stack, conventions, entry points, shared state.

- <Language, framework, versions; where configuration lives.>
- <Entry point(s) and how modules plug in.>

## <Area 1>

> <One line: what this area covers.>

- `<path/file>`: <what it does; the non-obvious rule it follows>.

## <Area 2>

> <One line: what this area covers.>

- `<path/file>`: <…>.

## Build, CI, tests, docs system

> Local build, workflows, tests, the docs check.

- Build: `<command>`. Tests: `<command>`.
- CI: `.github/workflows/docs-check.yml` runs `python3 -I scripts/docs_sync.py --check`.
- Docs system: `scripts/docs_sync.py` (config `scripts/docs_sync.json`), `scripts/install-hooks.sh`,
  `.claude/hooks/session-start.sh` (registered in `.claude/settings.json`). How to use it:
  `docs/HANDOFF.md` → How to work.

## Pitfalls

> Mistakes that happened more than once: check them in every review.

- **P1** <What went wrong, where, and the rule that prevents it.>

## File inventory

> Generated list of every mapped source file by folder.

<!-- gen:inventory -->
<!-- /gen:inventory -->
