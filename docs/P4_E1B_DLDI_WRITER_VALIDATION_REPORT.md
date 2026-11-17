# Plump P4-E1-B — Genuine DLDI writer validation

**Date:** 2026-11-17
**Result: BLOCKED — no DS writer ROM was compiled or run.**
**Production writable DS virtual SD remains disabled.**

## 1. Scope and repository preflight

This was a host-only toolchain recovery and fixture-build gate. The success condition is a genuine emulated ARM9 program creating and writing a FAT file through DLDI, closing and reopening it in game code, and verifying the same sequence/checksum after a fresh emulator process. A core-injected ROM file, host-side FAT edit, changed image hash, successful game boot, or diagnostic process exit is not that proof.

Canonical checkout verified as `/home/suraj/projects/Plump`, branch `main`, HEAD `fd4ea74e34b86e133bd88c11abf3a382566754ab`, origin `https://github.com/Suraj-H675/Plump.git`, one canonical worktree. No Plump build worker was running. One unrelated Codex worker was observed in an Ash-IDE worktree; it was left alone. Existing user changes were preserved: ` M docs/P3_TECHNICAL_REPORT.md` and `?? docs/P4_ENGINEERING_PLAN.md`. No production source, pinned core, PLSV/checkpoint format, existing immutable image, ROM fixture, or device was changed.

## 2. Existing source and toolchain audit

| Item | Observed state |
|---|---|
| Cached BlocksDS SDK | `app/build/p3-support-src/blocksds-sdk`, HEAD `5153fe216a218d676efdd6baed842ef6899fcfb5`, clean. Its declared submodules are **not initialized** (`git submodule status` prefixes them with `-`); `libs/libnds` and `sys/dldi_r4` are empty. Their gitlink pins are `6e263bea35833b4235ef7860b1a62ad1060da92c` and `8dff29496cecdf64b83b6e7ebb76d3f89aa7520c`. |
| Correct upstream example | `examples/filesystem/save_data/source/main.c`, SPDX `CC0-1.0`, by Antonio Niño Díaz (2023–2025); its Makefile uses the official BlocksDS ARM9 ROM makefile |
| Actual example operations | Calls `fatInitDefault()`, reads a file with `fopen`/`fread`/`fclose`, writes with `fopen`/`fwrite`/`fclose`, and exposes status on DS text consoles. It is a good source for a fixture adaptation, not itself a Plump deterministic magic/identity/sequence/checksum test. |
| Host build utilities | `gcc`, `g++`, `make`, `cmake`, `ninja`, `python3`, `tar`, `xz`, `curl`, and `git` are present |
| Required DS toolchain | `arm-none-eabi-gcc`, `wf-pacman`, `wf-env`, and `ndstool` are absent from PATH and the searched user/optional install roots. No `/opt/blocksds`, `/opt/devkitpro`, or `/opt/wonderful` exists. |
| Privilege and disk | Linux x86_64; `$HOME` filesystem has about 178 GiB free. `sudo -n true` fails because privilege elevation is not available noninteractively. |
| Container alternative | Docker CLI exists, but `/var/run/docker.sock` is absent and the daemon is stopped. No Podman or Nix route was found. Docker was not started or reconfigured. |

The existing CC0 example source and cached SDK were read directly. No `make` was run because the ARM cross-compiler, BlocksDS libraries and ROM packager are missing; trying to compile with the Android NDK or replacing libnds/libfat was deliberately rejected.

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

### Downloaded material — inspection only, not executed

The official HTTPS bootstrap was downloaded only for static inspection, into ignored `app/build/p4-e1-b/toolchain-audit/`:

| Artifact | Size / SHA-256 | Handling |
|---|---|---|
| Official `wf-installer.sh` | 9,203 bytes; `7b7381e8862409d1f31434a1c75e97eee8ab4ca5e473039c8f35ad10c3e06fd4` | Read and inspected; not run |
| Official `wf-bootstrap-x86_64.tar.gz` | 5,036,467 bytes; `923bcd6d0e6c89bc5d19d9a85d1dd0b749b2aa46dc4d470e106b3d7d547e96a3` | Extracted for static inspection only; no executable run |
| Bundled `wf-pacman` | Static PIE x86-64 ELF; `fc2831dfbaa38f54f51fe0954c5d0aa1df48bb74a787ebe91ca876fd316d2f83` | `file`, `readelf`, and `strings` only; not run |
| Wonderful package DB | `95e4d935fc488a79934babda4fb1133343f7609778bd8be6beda34de40bc9bf3` | Parsed offline |
| BlocksDS package DB | `1f3f4ea66791303118c487985b7c78f23c51ccc6027e0c83575e7aebe304bd7b` | Parsed offline |

No detached `.sig`, `.asc`, or `.sha256` sidecar was available at the tested bootstrap URLs (HTTP 404). The bundled `pacman.conf` has `SigLevel = Optional`; the bootstrap GPG directory is empty. The package database records SHA-256 digests, but no `%PGPSIG%` fields were present and the BlocksDS toolchain package `.sig` URL returned 404. This is recorded so no one mistakes transport integrity or a repository hash for a separately signed package chain. **No installer, package manager, downloaded compiler, or toolchain executable was run.**

The official BlocksDS package DB advertises:

| Package | Version | Download bytes | Installed bytes | Repository SHA-256 |
|---|---:|---:|---:|---|
| `blocksds-toolchain` | `1.25.0-1` | 3,091,416 | 22,702,022 | `d44d9db1d9d85fbf06094365f0fed5549f62183dce17e4d8e0f9d100a5fe5d31` |

Its dependencies include `toolchain-gcc-arm-none-eabi-binutils`, `toolchain-gcc-arm-none-eabi-gcc-libs`, `toolchain-gcc-arm-none-eabi-gcc`, `toolchain-gcc-arm-none-eabi-picolibc-generic`, and `toolchain-gcc-arm-none-eabi-libstdcxx-picolibc` plus runtime libraries. The Wonderful repository metadata identifies GCC `16.1.1.r228552.d6ba7128f65-2` (62,547,368 compressed bytes), binutils `2.46.1-1` (2,496,472 bytes), and the required Picolibc and C++ runtime packages. No package archives were downloaded or executed.

## 4. Why provisioning stopped

There is no supported user-local prefix for the current Wonderful package-manager installation. The only documented Linux prefix is `/opt/wonderful`; its package manager is designed to maintain that directory without requiring root **after the directory is created and owned by the developer**. The installer itself can create/chown the prefix using privilege escalation, but the task explicitly forbids invoking `sudo` or changing host-wide permissions without separate approval. Noninteractive sudo is unavailable in this environment.

The smaller documented prerequisite is exactly:

```bash
sudo mkdir -p /opt/wonderful
sudo chown -R "$USER":"$USER" /opt/wonderful
```

Neither command was run. This changes ownership only for the dedicated Wonderful directory, not system PATH, shell profiles, package databases, or the OS package manager. Once that prerequisite is separately authorized/provided, the supported route is to run the official installer at its default `/opt/wonderful` location, choose **option 4** (“Do not make changes to my `.profile`”), then install `blocksds-toolchain` with `wf-pacman`. Subsequent package installation is documented to run without sudo. The existing host C/C++/make tools satisfy the source-build host prerequisites if needed, but the prebuilt official package route is smaller.

## 5. Fixture and emulation proof status

| Required E1-B evidence | Result |
|---|---|
| Original Plump diagnostic source adapted from CC0 example | **NOT STARTED** — no buildable supported toolchain prefix |
| Compiled `.nds`, source/toolchain build receipt, ROM SHA | **NOT AVAILABLE** — no ROM built |
| `fatInitDefault()` success from emulated ARM9 | **NOT TESTED** |
| In-game first file creation, deterministic magic/ROM identity/sequence/checksum | **NOT TESTED** |
| In-game flush/close/reopen and checksum verification | **NOT TESTED** |
| Independent FAT extraction showing the exact in-game record | **NOT TESTED** |
| Fresh Libretro process and same record recovered by emulated code | **NOT TESTED** |
| Second write plus another cold restart | **NOT TESTED** |
| Two distinct ROM identities and branch working-image isolation | **NOT TESTED** |
| Missing/corrupt record and unavailable/read-only-storage reporting | **NOT TESTED** |
| Normal-unload FAT/sidecar consistency after a genuine game write | **NOT TESTED** |

The prior E1 immutable test image `app/build/p4-e1-acceptance-qdEWnNMm/immutable-preformatted/dldi_sd_card.bin` was independently rehashed and remains `0d118e5f7585feca37fc4b6cc5774691e3da588317dd9fe3b46301aaa9b9937e`. No prior E1 image was mounted or modified in this E1-B attempt. No DLDI writer fixture, FAT file, image, or sidecar was generated.

## 6. Reproduction and handoff

Static environment audit and artifact inspection were performed with `command -v`, `git rev-parse/status/worktree`, `file`, `readelf`, `strings`, `tar`, `curl`, and Python's read-only `tarfile` parsing. Exact source revisions, bootstrap script/archive hashes, package DB hashes, package metadata and the extracted bootstrap are preserved under the ignored directory `app/build/p4-e1-b/toolchain-audit/`. Nothing under that path is committed.

**No emulator experiment was rerun**: the previously accepted E1 harness does not exercise game-initiated file I/O and cannot satisfy E1-B's acceptance condition. The pinned melonDS source/core and production JNI remain unchanged. No Android/JVM regression is relevant because production sources were not touched. No APK, ADB, personal game/save/firmware, DSi NAND, 3DS, GitHub push or unrelated milestone work was used.

**Smallest required authorization:** approve the two `/opt/wonderful` directory ownership commands above (or create that exact directory and grant the current user ownership). If that system-path prerequisite is not acceptable, the alternative is a separately authorized disposable Linux VM/container with the official `/opt/wonderful` path available inside it; do not start or reconfigure the host Docker daemon as a workaround.

**Recommendation: BLOCKED.** E1-B's success criterion is unmet. No genuine game-level DLDI write or cold-restart readback has been demonstrated. After toolchain access, the next bounded step is to adapt the CC0 sample into one deterministic Plump fixture, compile it with the official pinned BlocksDS package, and only then extend the existing bounded host harness to verify actual game-created records. Writable SD stays disabled; no E2 production implementation is justified or authorized by this result.
