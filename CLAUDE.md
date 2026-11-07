# YT – notes for Claude

Read these first, they are the project's memory between sessions:

- `docs/FEATURES.md`: every feature with a short German explanation and its status, plus the list
  of ideas not built yet. This is what the user reads.
- `docs/ARCHITECTURE.md`: where each feature lives in the code, the shared building blocks, the build
  and CI setup, pitfalls and open points.
- `docs/DESIGNS.md`: the 12 designs in detail.

## Keep them current

- Every change that adds, removes or changes a feature updates `docs/FEATURES.md` (and
  `docs/ARCHITECTURE.md` when files, hooks or pitfalls change) **in the same commit**.
- When an idea from the "Ideen" list is built, move it into the feature tables.
- Update the status column honestly: E = checked in the emulator, T = covered by tests, – = not
  checked. Never claim something was tested on a phone unless the user said so.

## Working rules

- The user writes German; answer in German. Code comments, commit messages and internal docs in
  English; user-visible strings in `values/` and `values-de/`.
- No model names or AI identifiers in code, docs or commits.
- Never commit signing keys or passwords. The release key lives only in the GitHub secret.
- Builds for the phone come from CI (`main` → `build-N`). Verify the APK (SHA-256 and signer, see
  ARCHITECTURE.md) before sending a link.
- Compile locally before pushing (see ARCHITECTURE.md, "Build, CI, tests").
