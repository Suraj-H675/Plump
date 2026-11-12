# Plump — repository engineering instructions

Plump is a **personal, entirely offline, Android-only** Nintendo handheld emulator. It aims for a premium phone-native experience: GB/GBC/GBA, DS/DSi and 3DS/New 3DS emulation; one-screen and two-screen portrait controls; stable game saves, recovery, and a polished library. Reuse proven emulator technology wherever feasible rather than implementing another emulator from scratch.

This public file is a concise pointer, not the personal master workflow. Before substantive work, read the LOCAL authoritative source pack at:
  .plump-private/project-sources/00_READ_FIRST.md
If the private source pack is unavailable, **do not infer missing decisions** from this README; ask for the pack or report which decisions cannot be verified. The executing ChatGPT project may also have uploaded snapshots; the actual local canonical documents control when available.

Engineering rules:
- Verify pwd, Git root, branch, HEAD, status, untracked changes and remote before any mutation. Work in this existing checkout, default branch main. No routine worktree creation; use isolated worktrees only for demonstrated need such as truly independent concurrent writers or hazardous experiments.
- Never modify Ash-Harness, Ash-IDE, OpenDrop, Nintendo-Emulator-Design or other sibling projects as a side effect. Their files are read-only references.
- Never add, force-add, commit, push or package .plump-private/ or local workflow/account/credential/user-content files. Review explicit staging paths and diff before commits and pushes.
- Public repo visibility is currently intentional, but **this is not authorization to release the app or expose private artifacts**. No forced history changes, surprise releases, repo administration or destructive cleanup.
- Favor clear modules, small coherent diffs, explicit invariants and high-value behavioral tests. Prove native frame rendering, audio, touchscreen coordinates, Android lifecycle, state persistence and actual APK behavior on device; a mocked or visually attractive prototype does not prove an emulator works.
- Keep player save integrity paramount. Never allow experimental core, renderer, state load or migration to destroy the last usable in-game save. Disable unsupported features truthfully.
- Follow upstream license/notice/provenance requirements for imported source and do not put third-party game assets or test content in the public tree.
- Prefer a few coherent tested commits over commit/push spam. Don't busy-poll CI, agents or devices; record exact blockers and stop at a recoverable checkpoint.
- The main ChatGPT planning conversation decides product scope and independently accepts milestones; this development workspace executes approved tasks and returns evidence. No worker silently changes locked requirements.

Current public project state is recorded in docs/STATUS.md. See docs/OVERVIEW.md for the non-private product outline.
