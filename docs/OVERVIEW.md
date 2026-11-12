# Plump — public product overview (not final implementation plan)

**Purpose:** a single personal Android application that feels natural to use as a
Nintendo handheld. The priority is real gameplay and stability, not a visual
wrapper around other emulator apps.

**Supported targets to validate:** Game Boy, Game Boy Color, Game Boy Advance,
Nintendo DS, DSi, Nintendo 3DS, and New Nintendo 3DS. Work is incomplete; no
current compatibility claims are made.

**Primary interaction patterns:**
- GB/GBC/GBA: one native-aspect display on top, controls below.
- DS/DSi/3DS: top display large, smaller lower touchscreen centered among
  controls, lower-screen enlargement and alternative layouts.
- Offline game library: one continuous list; games show media and title,
  optional extras under title; sort recently played or alphabetically.
- Bottom actions: add existing game files/folders, choose current system,
  and settings.
- In-game overlay: gameplay pauses; screen behind menu becomes blurred.
- Fully customizable virtual controls; no physical controllers required.

**Runtime priorities:** functional save data survives process death and
updates; native game saves take precedence over serialization-dependent
save states. Stable profile default, optional performance tuning.
No account, multiplayer or online requirement.

**Provisional design authority:** the current editable OpenPencil library
concept is user-approved as "good enough for now", not frozen pixel parity.
Actual Android UI must be compared with this local source.
