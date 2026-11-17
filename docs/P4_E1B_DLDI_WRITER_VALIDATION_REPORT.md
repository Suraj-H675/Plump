# Plump P4-E1-B — Genuine DLDI writer validation

**Date:** 2026-11-18 (original blocked toolchain audit: 2026-11-17)
**Result: PASS — genuine game-initiated DLDI file writes and separate-process cold recovery proven at host feasibility level.**
**Production writable DS virtual SD remains disabled. E2 is not authorized by this report.**

## 1. Scope and repository preflight

This was a host-only toolchain recovery and fixture-build gate. The success condition was a genuine emulated ARM9 program creating and writing a FAT file through DLDI, closing and reopening it in game code, and verifying the same sequence/checksum after a fresh emulator process. The test now proves this by **two mutually independent mechanisms**: (1) ARM9 in-game `fopen`/`fwrite`/`fflush`/`fclose`/`fread` with on-screen and FAT-resident signed-off reports, and (2) host-side read-only FAT extraction independently validating the record and report byte-for-byte. The core's automatic ROM injection, changing an image SHA, boot success and a diagnostic process exit never count as proof on their own.

Canonical checkout verified as `/home/suraj/projects/Plump`, branch `main`, HEAD `fd4ea74e34b86e133bd88c11abf3a382566754ab`, origin `https://github.com/Suraj-H675/Plump.git`, one canonical worktree. No Plump build worker was running. One unrelated Codex worker was observed in an Ash-IDE worktree; it was left alone. Existing user changes were preserved: ` M docs/P3_TECHNICAL_REPORT.md` and `?? docs/P4_ENGINEERING_PLAN.md`. No production source, pinned core, PLSV/checkpoint format, existing immutable image, ROM fixture, or device was changed.

## 2. Existing source and toolchain audit

| Item | Observed state |
|---|---|
| Cached BlocksDS SDK | `app/build/p3-support-src/blocksds-sdk`, HEAD `5153fe216a218d676efdd6baed842ef6899fcfb5`, clean. Its declared submodules are **not initialized** (`git submodule status` prefixes them with `-`); `libs/libnds` and `sys/dldi_r4` are empty. Their gitlink pins are `6e263bea35833b4235ef7860b1a62ad1060da92c` and `8dff29496cecdf64b83b6e7ebb76d3f89aa7520c`. |
| Correct upstream example | `examples/filesystem/save_data/source/main.c`, SPDX `CC0-1.0`, by Antonio Niño Díaz (2023–2025); its Makefile uses the official BlocksDS ARM9 ROM makefile |
| Actual example operations | Calls `fatInitDefault()`, reads a file with `fopen`/`fread`/`fclose`, writes with `fopen`/`fwrite`/`fclose`, and exposes status on DS text consoles. It is a good source for a fixture adaptation, not itself a Plump deterministic magic/identity/sequence/checksum test. |
| Host build utilities | `gcc`, `g++`, `make`, `cmake`, `ninja`, `python3`, `tar`, `xz`, `curl`, and `git` are present |
| Toolchain before user action | `arm-none-eabi-gcc` and Wonderful were not installed; `/opt/wonderful` did not exist. The cached SDK submodules were uninitialized and could not provide a working compiler by themselves. |
| Toolchain **after** user action | The owner created `/opt/wonderful` and assigned ownership. The official bootstrap and package set were installed under that dedicated directory with the existing user account, **without sudo from this agent**. BlocksDS `1.25.0-1`, Wonderful ARM GCC `16.2.0` (`1:16.2.0.r229124.be93d9d35bf-1`), binutils `2.47-1`, Picolibc `1.8.13.r26367.770d86889-1`. ARM9 ROM compilation and `ndstool` succeeded. |
| Privilege and disk | Linux x86_64; about 177–178 GiB free at installation time. The official toolchain occupies about **730 MiB** in `/opt/wonderful`. No host-wide permissions or PATH configuration were changed by this agent. |
| Container alternative | Docker CLI exists, but `/var/run/docker.sock` is absent and the daemon is stopped. No Podman or Nix route was found. Docker was not started or reconfigured. |

The existing CC0 example source and cached SDK were read directly. The **installed official BlocksDS toolchain** (not Android NDK and not a substitute libnds/libfat implementation) compiled both original Plump ROMs successfully. The standalone CC0 example was also independently compiled into an ignored `.nds` first as a toolchain smoke test.

## 3. Official installation route investigation

Official documentation consulted:

- BlocksDS Linux setup: <https://blocksds.skylyrac.net/docs/setup/linux/>
- BlocksDS build from source: <https://blocksds.skylyrac.net/docs/setup/from_source/>
- BlocksDS FAT/DLDI filesystem tutorial: <https://blocksds.skylyrac.net/tutorial/intermediate/filesystems/>
- Wonderful Linux installation: <https://wonderful.asie.pl/doc/general/getting-started/>
- Wonderful Linux advanced installation: <https://wonderful.asie.pl/wiki/doku.php?id=getting_started%3Alinux>
- Wonderful FAQ (package-manager root): <https://wonderful.asie.pl/doc/general/frequently-asked-questions/>

The BlocksDS Linux guide installs Wonderful, enables the BlocksDS repository, then installs `blocksds-toolchain` with `wf-pacman`. It places BlocksDS under `/opt/wonderful/thirdparty/blocksds/`; its environment setup derives `BLOCKSDS` and `BLOCKSDSEXT` from that root. The Wonderful Linux installation guide says other installation locations are **not supported**. Its package-manager FAQ also says `wf-pacman` is customized around `/opt/wonderful`.

The current official installer script does display an experimental user-specified path choice. Static inspection of the downloaded official installer (`VERSION=20260619`, SHA-256 `7b7381e8862409d1f31434a1c75e97eee8ab4ca5e473039c8f35ad10c3e06fd4`) and the bootstrap's `lib/wf-pacman/runtime-env-vars.sh` shows `WF_LOCAL_PATH="opt/wonderful"`, `WF_PATH="/opt/wonderful"`, and `WF_DESTDIR="/"` are fixed. The custom installer choice therefore conflicts with the official package layout and the explicit documentation that alternate paths are unsupported. I did **not** rely on the experimental home-directory option or risk a partial install that writes outside the chosen prefix.

### Official distribution provenance (bootstrap inspected before installation)

The official HTTPS bootstrap was initially downloaded only for static inspection into ignored `app/build/p4-e1-b/toolchain-audit/`. **After the owner independently provisioned and granted ownership of `/opt/wonderful`**, the previously SHA-verified archive was extracted to that supported prefix and its package manager was used to install official BlocksDS packages:

| Artifact | Size / SHA-256 | Handling |
|---|---|---|
| Official `wf-installer.sh` | 9,203 bytes; `7b7381e8862409d1f31434a1c75e97eee8ab4ca5e473039c8f35ad10c3e06fd4` | Read and inspected; not run |
| Official `wf-bootstrap-x86_64.tar.gz` | 5,036,467 bytes; `923bcd6d0e6c89bc5d19d9a85d1dd0b749b2aa46dc4d470e106b3d7d547e96a3` | SHA verified, extracted into user-owned `/opt/wonderful` after owner action |
| Bundled `wf-pacman` | Static PIE x86-64 ELF; `fc2831dfbaa38f54f51fe0954c5d0aa1df48bb74a787ebe91ca876fd316d2f83` | Static inspected first, then executed locally to install official packages |
| Wonderful package DB | `95e4d935fc488a79934babda4fb1133343f7609778bd8be6beda34de40bc9bf3` | Parsed offline |
| BlocksDS package DB | `1f3f4ea66791303118c487985b7c78f23c51ccc6027e0c83575e7aebe304bd7b` | Parsed offline |

No detached `.sig`, `.asc`, or `.sha256` sidecar was available at the tested bootstrap URLs (HTTP 404). The bundled `pacman.conf` has `SigLevel = Optional`; the bootstrap GPG directory was initially empty. The package database records SHA-256 digests, but no `%PGPSIG%` fields were present and the BlocksDS toolchain package `.sig` URL returned 404. Thus **HTTPS origin, known bootstrap SHA and package-manager digest verification are observed; detached cryptographic signature verification is not claimed**. No host system package manager was used.

The official BlocksDS package DB advertises:

| Package | Version | Download bytes | Installed bytes | Repository SHA-256 |
|---|---:|---:|---:|---|
| `blocksds-toolchain` | `1.25.0-1` | 3,091,416 | 22,702,022 | `d44d9db1d9d85fbf06094365f0fed5549f62183dce17e4d8e0f9d100a5fe5d31` |

Its dependencies include `toolchain-gcc-arm-none-eabi-binutils`, `toolchain-gcc-arm-none-eabi-gcc-libs`, `toolchain-gcc-arm-none-eabi-gcc`, `toolchain-gcc-arm-none-eabi-picolibc-generic`, and `toolchain-gcc-arm-none-eabi-libstdcxx-picolibc` plus runtime libraries. The **refreshed official repository** exposed GCC `16.2.0`, binutils `2.47` and the required Picolibc package. The installed package manager verified download integrity. The complete eight-package BlocksDS dependency transaction totalled **151.12 MiB compressed** and **548.98 MiB installed**, with the core prerequisite package-manager utilities installed separately.

## 4. Unblocking event and toolchain transaction

The previous **BLOCKED** verdict was accurate before the owner's intervention. On 2026-11-18 the owner explicitly reported running the requested directory creation and ownership commands. Independent inspection then established `/opt/wonderful` existed, was owned by `suraj:suraj`, was writeable by the unprivileged current account and initially empty. No need for this agent to invoke `sudo`.

Using that supported prefix, this agent extracted the already SHA-256-verified official bootstrap archive and ran:

```bash
export WONDERFUL_TOOLCHAIN=/opt/wonderful
export PATH=/opt/wonderful/bin:$PATH
/opt/wonderful/bin/wf-pacman -Syu --noconfirm wf-tools
/opt/wonderful/bin/wf-config repo enable blocksds
/opt/wonderful/bin/wf-pacman -Syy --noconfirm
/opt/wonderful/bin/wf-pacman -S --noconfirm blocksds-toolchain
eval "$(/opt/wonderful/bin/wf-config env generate -a)"
arm-none-eabi-gcc --version
```

The official manager verified package integrity and installed BlocksDS and GNU ARM9 tools successfully; the BlocksDS transaction was 151.12 MiB compressed/548.98 MiB installed. `wf-config env generate -a` exports `WONDERFUL_TOOLCHAIN=/opt/wonderful`, `BLOCKSDS=/opt/wonderful/thirdparty/blocksds/core`, `BLOCKSDSEXT=/opt/wonderful/thirdparty/blocksds/external` and process-local PATH. Nothing was appended to `.bashrc`, `.profile`, system PATH or OS configuration. The standalone cached upstream CC0 filesystem example first compiled and linked successfully as an ignored toolchain smoke test.

**Build prerequisites:** official BlocksDS package `1.25.0-1`, ARM GCC `16.2.0`, GNU binutils `2.47`; libnds, DLDI tooling, default DS ARM7 binary and `ndstool` provided by the installed SDK. The separate host FAT verifier runs in an ignored Python venv with `pyfatfs==1.1.0`, `fs==2.4.16`, `appdirs==1.4.4`, `six==1.17.0`, and `setuptools==81.0.0`. A newer setuptools lacked `pkg_resources` required by `fs`; restricting that one version fixed the isolated verifier without any global Python changes.

## 5. Fixture provenance, real emulated behavior and DLDI diagnosis

The two ROMs are **original diagnostic code** adapted from the CC0-1.0 `examples/filesystem/save_data/source/main.c` in the cached BlocksDS SDK, git `5153fe216a218d676efdd6baed842ef6899fcfb5`. Plump-specific source: `tests/roms/p4-e1b-dldi-writer/source/main.c` (SHA-256 `5f8e67ad08c53b6ec0a85a315fad819b8adf9b18abc6801b610089ca05d1e713`); accompanying Makefile `tests/roms/p4-e1b-dldi-writer/Makefile` (SHA-256 `eb895a2c2bd8886ff36c0a3ed9d311dfbe94ddf7a7f6f72e96b5d213be2031ca`). Both carry SPDX `CC0-1.0` and attribution. No existing ROM or game data was changed.

The ARM9 program calls real installed libnds/libfat `fatInitDefault()`, rejects a default drive other than `fat:/` (DS DLDI rather than DSi SD/NAND), creates `fat:/PLUMP`, opens a per-ROM file, reads/validates any prior record, increments a counter, writes through `fwrite()`, `fflush()`, `fclose()`, **reopens the same file and validates every byte**. It also creates `A001.TXT` / `B002.TXT` with a deterministic `PLUMP_E1B_RESULT=PASS` or explicit failure, reopens and byte-compares that report, and renders the actual result on the DS text console. The 24-byte little-endian record is `{magic=0x4D554C50, version=1, rom_id, counter, fnv1a32(first 16 bytes), ~checksum}`. Missing file is the only accepted initial condition; unknown identity, corrupt record, I/O errors and counter overflow fail closed instead of resetting progress.

**Critical source-rooted bug and fix (no melonDS revision change):** BlocksDS initially built a DLDI placeholder too small for the pinned melonDS driver. The core logged `DLDI driver ain't gonna fit`; the booted fixture wrote no game data. Running the official BlocksDS `make dldipatch` target on each **disposable ROM only** embeds its redistributable R4 DLDI driver with 16 KiB reserved space. The **unchanged pinned melonDS host core** then successfully patches its own DLDI implementation into the cartridge and genuine ARM9 FAT read/write works. A subsequent redundant `DLDI driver ain't gonna fit` log still occurs in this core's reset path; it does not negate the independently observed successful first patch and genuine record round trips. Preserve this log as an upstream quirk for later analysis, not as proof of universal compatibility.

| Fixture | ROM ID | Output / state file | Final DLDI-patched ROM SHA-256 | Size |
|---|---|---|---|---:|
| Alpha | `A1A10001` | `fat:/PLUMP/A001.BIN` | `1dbbf7e6dea0068a4495b5d3fff5d20f61b099ba48bf9fc0dc0512b136c40588` | 150,528 bytes |
| Beta | `B2B20002` | `fat:/PLUMP/B002.BIN` | `e01a1934e49732c6266bef6b51e64ae74ae5d0d5a426d802cf311f29854823e5` | 150,528 bytes |

Exact unchanged engine: Libretro frontend `JesseTG/melonds-ds` git `f394adbacb5722ee97c1b37c8064da9a25818310`; melonDS dependency git `16f127dbb587a73371827ce79689c5d237c4b59b`. The **genuine, unrecompiled pinned Linux core** `app/build/p3-melonds-host/src/libretro/melondsds_libretro.so` SHA-256 `e8c5fd156e9d1fe9fa861cd93cc0ef25ddc7eecb0ccf65a6a6a8c4695e577616` loaded each freshly built ROM and rendered 180 frames per process. No native-core source changes, patches, compilation or production JNI changes.

For independent host verification, `tests/roms/inspect-p4-e1b-fat.py` opens the FAT16 image with the separately installed **MIT-licensed PyFatFS**, parses the files, computes FNV checksum/inverse, validates binary fields against ROM identity and enforces exact report contents and counter progression. The positive tests **never use host-side writes to create or advance a record**. The host FAT library writes only when deliberately flipping a checksum in a separate, disposable, negative-test image.

## 6. Genuine cold-restart proof and storage matrix

Reproducible full acceptance invocation `PYTHONDONTWRITEBYTECODE=1 bash scripts/test-p4-e1b-real-dldi-writer.sh` **PASS (exit 0)**. Observed complete run log `app/build/p4-e1-b/e1b-third-full-run.log`; ignored, isolated evidence root `app/build/p4-e1-b/runs/acceptance-mJnYMKhR/`. Fresh emulator subprocesses are used for each row; each normal run calls real `retro_unload_game` and opens/closes a real FAT handle. Independent `fsck.fat -nv` succeeded after **every** normal unload. Each screenshot is a captured 256×384 XRGB8888 real-emulator frame with a PNG companion.

| Genuine process / separate FAT volume | In-game old → new counter | ARM9 / read-only independent FAT verification |
|---|---|---|
| Alpha canonical launch 1, file missing | 0 → **1** | PASS; record checksum `7001CAE4`, SHA-256 `36100b988b28cf6cd11ecffc47101740a54a3a1451ad1695e521b75189ac9ef0` |
| Alpha canonical **cold process 2** | 1 → **2** | PASS; `60868817`, SHA-256 `34474cee035dec60a5b043ff235e70e80bf14caa763dbe8abc010bf8d93fc8c4` |
| Alpha canonical **cold process 3** | 2 → **3** | PASS; `105A4906`, SHA-256 `82c401cb183aa27a082df3e371d42713551f252f9aa4b4064772e1d50d810463` |
| Beta different ROM, separate image | 0 → **1** | PASS; ROM ID `B2B20002`, checksum `49163805` |
| Beta **cold process 2** | 1 → **2** | PASS; `99427716`, SHA-256 `490aa18a1018cf59261f0de02b8a67c9844092b84b0eeddee5b28700bc41ad6a` |
| Copied Alpha historical working image (from canonical 3) | 3 → **4** | PASS; `7F7D0DB1`, SHA-256 `dd29bd4fcaae158192b9ba7609764d9ec989a9be102d0916a90097f03959adde` |

The Beta volume contains `B002.BIN/B002.TXT` and **no Alpha files**; the canonical Alpha volume contains `A001.BIN/A001.TXT` and **no Beta files**. After Beta progressed, bytewise canonical SHA was unchanged. The historical **working copy** started with the exact SHA of canonical Alpha counter 3, then diverged after its genuine next in-game write while canonical retained counter 3, its last verified record, and its exact complete-image SHA. This is copy isolation on the host; it is **not** a production branch-storage transaction or a machine-state snapshot.

**Complete 128 MiB FAT16 image SHA-256 after final verified unload (same run):**

| Protected source / independent working timeline | SHA-256 |
|---|---|
| Immutable preformatted source (unchanged from pretest) | `f0ea2ba6fb53293afb9853dd231b21a8577782f75774dcaf6711663b038ce30c` |
| Alpha canonical, after counter 3 (unchanged after Beta, branch, corrupt-record and readonly probes) | `0b651e607772c614f83d80a94873736f361d7a819d8bba0c38ec2dba73aa3ae4` |
| Beta independent, counter 2 | `019f31f3449ff9860c86e432561d0c4fde0544aa462bea3e0d9a1055996bc576` |
| Alpha copied historical working image, counter 4 | `400071e086fabd408710faaea10f070912535c50463dc808503d68ebbbb3643f` |
| Independent corrupt-record working copy after rejection | `66a4ac66d50735f537117ae0a91ae1df5417eb5b2403625389197ca7cec81030` |
| OS read-only copied image after core boot/refusal | `f0ea2ba6fb53293afb9853dd231b21a8577782f75774dcaf6711663b038ce30c` |

The earlier E1 protected immutable image was **also rehashed separately** and remains `0d118e5f7585feca37fc4b6cc5774691e3da588317dd9fe3b46301aaa9b9937e`. The verified records are separate from the ROM files the core automatically injects into FAT. **No `.idx` was present for these runs with folder synchronization disabled**; index absence here is an observation, not a universal guarantee. No other sidecar was identified in the writable FAT storage root.

**Space accounting:** every volume was 134,217,728 logical bytes; each new sparse FAT16 baseline occupied **282,624 host-allocated bytes**, each cleanly written virtual image occupied **548,864 host-allocated bytes** (Linux `st_blocks×512`). The complete accepted disposable evidence root occupied about **9 MiB of actual host disk** including copied images and logs; `app/build/p4-e1-b/runs` about 20 MiB after multiple deliberate diagnostics. Every image was bounded to 128 MiB logical and ≤32 MiB allocated; the test's process resource limit also prevents a runaway >160 MiB per-file allocation. These are sparse Linux-host figures, **not** Android image-copy cost projections.

## 7. Negative tests, unresolved safety and no-go boundaries

| Test | Actual result | Meaning |
|---|---|---|
| Valid record missing at first launch | Alpha emitted `PREVIOUS=0,COUNTER=1,PASS` and created 24-byte file plus report | Initial creation **PASS** |
| Correct old record after cold restart | In-game and independent checks observed Alpha 1→2→3 and Beta 1→2 | Native FAT persistence **PASS** for tested fixtures |
| Corrupt record (disposable copy of canonical 3) | Independent PyFatFS mutation changed only checksum `105A4906`→`105A4907` before launching; ARM9 wrote `PLUMP_E1B_RESULT=ERROR,ERROR=BAD_RECORD`; invalid state SHA `70bec35ab08a99089f17c6ca618e9c5580a36503f7894f1c8dfb1c26f75109e3` stayed **identical before/after** | No silent reset/overwrite of bad existing record **PASS**; this fixture-level validation is not general FAT corruption recovery |
| Host image `chmod 0444` | Pinned core logged failed R/W image open but still rendered ROM; captured DS screen displayed `RESULT: FAT_INIT_FAIL` and `DETAIL: DLDI/FAT unavailable`; no new FAT file/report, source SHA unchanged | Honest game-level unavailable-storage reporting **PASS**; **boot success by itself is misleading** |
| Normal FAT integrity and sidecars | Read-only `fsck.fat -nv` **PASS after every normal unload**; no `.idx` with host folder sync explicitly disabled | Narrow healthy normal-unload path only; does not prove durable power-loss recovery |
| Previously accepted E1 negative regressions | `bash scripts/test-p4-e1-bounded-host.sh` **PASS diagnostic exit 0**, still independently reproduces core read-only-option bypass, corrupt-volume acceptance, fake successful boot on OS read-only and orphaned FAT clusters after abrupt process termination | **Known unsafe behavior remains; do not call virtual SD production-safe** |

No process-death test was run against a user image, committed immutable generation or active unprotected working image. No in-sector crash, failed close/fsync simulation, disk-full, machine-state + current-image compatibility, historical-state restoration, Android lifecycle or storage-provider timing proof was attempted during E1-B. No host folder sync was enabled. The native `FATStorage::Save()` returns success when folder sync is disabled but does **not** expose a reliable durability/health receipt for an entire image generation. The existing `FF_WriteStorage()` path bypasses the read-only policy in the game's *different* ROM-injection route. E1's prior core-read-only bypass, silent volume faults and unmount-free inconsistent allocation still exist. **This E1-B PASS is a genuine capability proof, not a safety acceptance for writable production SD.**

## 8. Reproduction, reviewed Git boundary and minimal E2 recommendation

Run the following commands from the canonical Plump root (host-only; `app/build` remains Git-ignored):

```bash
# Once, after the official user-owned BlocksDS toolchain is already installed:
python3 -m venv app/build/p4-e1-b/fat-inspector-venv
app/build/p4-e1-b/fat-inspector-venv/bin/python -m pip install \
  'pyfatfs==1.1.0' 'fs==2.4.16' 'appdirs==1.4.4' \
  'six==1.17.0' 'setuptools==81.0.0'

# Builds both deterministic CC0-derived ROMs with the official DLDI patch,
# checks exact SHA-256, creates only new bounded sparse disposable images,
# runs real pinned melonDS in independent processes and asserts FAT/ROM state:
PYTHONDONTWRITEBYTECODE=1 bash scripts/test-p4-e1b-real-dldi-writer.sh

# Recheck earlier mount/hazard tests independently:
PYTHONDONTWRITEBYTECODE=1 bash scripts/test-p4-e1-bounded-host.sh
```

The new harness, `scripts/test-p4-e1b-real-dldi-writer.sh`, is pinned to the source SHA, generated ROM SHA values, installed BlocksDS/GCC versions and the exact melonDS host ELF. It refuses missing/mismatched core, old/toolchain mismatches, unbounded/missing volumes, symlinks, unexpected folder sync or missing native close evidence. It retains owned, ignored evidence roots rather than automatically deleting forensic data. `tests/roms/verify-p4-e1-virtual-sd.py` was **extended** to allow two strictly SHA-pinned E1-B ROMs and save final video frame bytes; original E1 cases and their negative outcomes remain intact. The independent read-only FAT reader `tests/roms/inspect-p4-e1b-fat.py` and byte-exact libretro frame PNG converter `tests/roms/render-p4-e1b-frame.py` are also tracked. No ROM, virtual image, screen/frame evidence, FAT record, APK or toolchain binary enters Git.

**Regression status:** complete genuine E1-B harness PASS; existing original E1 bounded pinned-core diagnostic PASS, including its intentionally detected hazards; Python AST parse, shell syntax, toolchain exact-version checks, ROM deterministic SHA, per-process FAT fsck and independent source-image checks PASS. Production Java/JNI/C++ source was **not modified**, so unrelated Android/JVM rebuilding is not implied by this host-only fixture milestone. The production DS JNI still forces `melonds_homebrew_sdcard=disabled` and `melonds_dsi_sdcard=disabled`. No APK installation, ADB, Android settings, personal game/save/firmware, DSi, 3DS, core upgrade, GitHub push, E2 integration or P4-F.

**Git baseline:** `abe7af21ad9c686030bba03206d7e854b0040758`, branch `main`, origin `https://github.com/Suraj-H675/Plump.git`, one canonical worktree. The pre-existing unrelated ` M docs/P3_TECHNICAL_REPORT.md` and `?? docs/P4_ENGINEERING_PLAN.md` are intentionally untouched and unstaged. This report and only the five relevant E1-B implementation/test files plus the existing E1 verifier extension should be in the one coherent E1-B local commit (inspect `git log -1 --format=%H` for its exact ID). Review actual staged file names and byte diff before committing. No push.

**Recommendation: ACCEPT E1-B as a proven host-only in-game DLDI filesystem write and persistence feasibility gate. E2 still requires separate mastermind authorization.** Minimal safe **proposal**, not implementation: single (DS, exact ROM SHA, branch identity) authoritative working image; separate immutable full-image-plus-sidecar generations; validate FAT, required index sizes, unexpected file creation, native mount/open/flush/close status, exact image/core identity; quiesce and close the real native FAT handle before staging a checksummed/fsynced complete generation; transaction-safe generation pointer with a retained previous good set; fail closed on missing/corrupt/mismatched image; no historical machine-state restoration without a paired coherent FAT generation; hard bounds on image size and copy cost. The pinned native engine currently lacks reliable error/closure status and has a read-only bypass, so a **narrow monitored frontend/native FAT health adapter or separately approved upstream fix may be required**. Scope and any engine modification must be approved before E2 work.
