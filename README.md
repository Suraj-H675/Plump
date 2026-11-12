# Plump

Plump is a personal, offline-first Android Nintendo handheld emulator project.
The design direction is a native, polished phone experience with two main
gameplay layouts: a single-screen layout for Game Boy-family systems and a
dual-screen layout for DS/3DS-family systems.

## Product goals

- One on-device game library with user-selected recently played or alphabetical sorting.
- GB/GBC/GBA, DS/DSi, and 3DS/New 3DS emulation targets.
- Separate virtual controls, native console aspect ratios, direct DS/3DS touch input,
  console-appropriate layouts, and customizable touchscreen controls.
- Reliability first: in-game saves, manual states where supported, recovery
  checkpoints, independent per-game resume, and optional local-folder backups.
- No online account, cloud services, telemetry, or multiplayer.

**Status:** project charter and technical investigation stage. No emulation engine,
Android APK, compatibility claims, or runtime tests are completed by this bootstrap.

## Planning and execution

The comprehensive decision register, development workflow, engineering standard,
architecture evaluation, acceptance gates, and approved design references are kept
in a **local, Git-ignored source pack** at:

    .plump-private/project-sources/

The user's existing OpenPencil design references remain local. Public design
screenshots or source may be added later only after review.

Start at docs/OVERVIEW.md and docs/STATUS.md. Repo agents must read AGENTS.md and
the authoritative local source pack when accessible.

This project is not affiliated with Nintendo or any upstream emulator project.
No third-party emulator code has yet been imported; licensing and notices will
be preserved and recorded for any future code integrations. A public source
repository does not imply a published APK or an agreed overall project license.
