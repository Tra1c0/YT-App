# YT – notes for Claude

Start every session by reading, in this order (keep reading cheap: only what you need):

1. `docs/HANDOFF.md`: current state, how to work, dead ends, repeated mistakes, build log.
2. The section of `docs/ARCHITECTURE.md` for the area you touch (it is a code map with paths;
   do not explore the code from scratch when the map answers it).
3. `docs/FEATURES.md` when you need to know what exists (the user reads this one);
   `docs/ROADMAP.md` for what is not built yet (build only from "Beschlossen"; move items when built).
`docs/DESIGNS.md` has the 12 designs in detail.

## Keep them current

- A change that adds, removes or changes a feature updates `docs/FEATURES.md` (and
  `docs/ARCHITECTURE.md` when files, hooks or pitfalls change) in the same push.
- At the end of every round update `docs/HANDOFF.md`: state, open points, new dead ends and
  repeated mistakes, one build-log line. Keep it under ~200 lines; prune what is done or untrue.
- Status columns honestly: E = emulator, T = tests, – = not checked. Never claim phone-tested
  unless the user said so.

## Working rules

- The user writes German; answer in German, short. Code comments, commits and internal docs in
  English; user-visible strings in `values/` and `values-de/`.
- No model names or AI identifiers in code, docs or commits.
- Never commit signing keys or passwords. The release key lives only in the GitHub secret.
- Compile locally before pushing; builds for the phone come from CI (`main` → `build-N`); verify
  the APK (SHA-256 and signer) before sending the link.
- Prefer a new session per round over one endless session (context fills up and gets summarised).
