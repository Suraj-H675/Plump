# P4-E3C Lifecycle Cancellation and Commit Outcomes

## Result

Implemented P4-E3C on `main` at starting HEAD `ed6196c0fa3f0cd77e810812fb2acb33e576fade`. The change scopes the E3 diagnostic worker across Activity recreation, makes cancellation nonblocking on the UI thread, keeps native and storage cleanup behind the existing owner, and reports publication separately from receipt or native cleanup.

The reported E3B device run reached `0 → 1 → 2 → 3` and retained four generations, as supplied by the user. This work did not access the device or independently verify that report.

## Lifecycle and Ownership

`E3DiagnosticRunCoordinator` provides one process-wide admission slot and a distinct run UUID. The worker owns preflight, seed creation, baseline publication, working-image copy, core initialization, game load, frame completion, publication, and cleanup. A recreated Activity attaches a new observer to the existing run; it does not start another worker. `onResume()` only observes an active run or displays its last result. `onPause()` requests cancellation and detaches the observer without I/O, owner transitions, joins, or locks.

The run control serializes cancellation with publication authorization under one monitor. The store first makes the pending marker, candidate generation, and staged HEAD file durable. Authorization occurs immediately before the atomic HEAD slot rename:

- If cancellation wins first, HEAD publication is denied. The store proves the exact prior HEAD and target-slot contents before reporting `NOT_COMMITTED` and clearing the candidate.
- If authorization wins first, later cancellation is deferred while the store finishes publication and verifies its outcome.

The worker joins the frame thread before calling the owner to close DLDI, unload, or shut down. The Activity never shuts down native state directly. If the frame worker remains live, owner cleanup fails, a receipt cannot be retired, or storage cleanup remains unresolved, the coordinator retains its slot in `BLOCKED`; it does not release a live working image or admit another run in that process.

## Commit Outcome and Recovery

`VirtualSdGenerationStore` keeps the existing E2 HEAD, manifest, and receipt formats. It adds a separate checksummed `PENDING_TRANSACTION.vsd` record for E3 publication identity. That identity contains the preselected candidate UUID, expected parent generation and HEAD digests, expected sequence and target-slot state, candidate sequence, image digest, and manifest digest.

The store reports:

- `COMMITTED` only after the exact candidate HEAD, manifest, parent, and image verify and the containing directory sync succeeds. A later PAUSED/CLOSED receipt or owner cleanup failure does not erase this durable result; cleanup status remains separate and may keep the process blocked.
- `NOT_COMMITTED` only when the exact old authority and target slot are still proven and the unpublished candidate can be quarantined.
- `RECOVERY_REQUIRED` when the pending transaction cannot prove either exact authority, including ambiguous HEAD publication, failed directory sync, corrupt candidate data, or unresolved cleanup.
- `NO_PENDING` when strict reconciliation finds no pending operation and has no retained identity. This is a status for “nothing to classify,” not a claim that a particular transaction failed to commit.

Strict reconciliation runs under the writer lease, verifies only the identity in the pending marker or retained operation, and does not use ordinary HEAD fallback or rewrite HEAD, manifests, or receipts. An uncertain operation retains the lease and blocks further writes until an explicit strict recovery resolves it or the process is restarted.

`EmulatorSessionOwner.StopResult` now separates durable outcome from quiescence. Legacy boolean callbacks produce a null durable outcome while preserving their historical quiescence and checkpoint behavior. E3 supplies its typed store outcome and terminal callback result; `closeHost()` reuses that terminal result instead of invoking publication again. A quiesced legacy host still gets the existing close-time AUTO checkpoint, including a retry after a transient checkpoint failure.

## Independent verification after the Poteto implementation

The Poteto implementation ran Java 17 direct compilation, **50 focused tests**, **154 production JVM tests** and **154 experimental JVM tests** successfully. Its environment could not initialize Gradle due to a sandbox socket restriction. The subsequent independent engineering review reran **actual Gradle, APK, signing, real-core and crash-recovery checks in the canonical checkout**, resolving that verification gap without any Android device access.

### Automated Java/Android artifacts

Executed successfully, offline and without changing production binaries:

```sh
./gradlew --offline --no-daemon \
  :app:testProductionDebugUnitTest :app:testE3testDebugUnitTest \
  :app:assembleProductionDebug :app:assembleProductionRelease \
  :app:assembleE3testDebug :app:lintProductionDebug :app:lintE3testDebug
python3 scripts/verify-p4-e3a-apk-isolation.py --negative-test
```

| Independent verification | Result |
|---|---|
| Full production JVM test suite via Gradle | **154/154**, 18 suites, no failures/errors/skips |
| Full E3 experimental JVM test suite via Gradle | **154/154**, 18 suites, no failures/errors/skips |
| Production debug and unsigned release builds | **PASS** |
| Isolated E3 debug APK build | **PASS** |
| Android lint | Production **0 errors / 20 warnings**; E3 **0 errors / 29 warnings**, existing warnings retained |
| Strict APK package ID, manifest/launcher, exact packaged ELF, JNI export isolation, ROM and compressed baseline checks | **PASS**, three incorrect identity substitutions rejected |
| Frozen E3 APK Android v2 debug signature and signer | **PASS** |

Canonical Gradle output: `app/build/p4-e3c/gradle-independent-first.log` (**BUILD SUCCESSFUL**, 158 actionable tasks). Strict package evidence: `app/build/p4-e3c/verification/apk-isolation.log`; frozen signer evidence: `app/build/p4-e3c/verification/frozen-apk-signature.log`. The existing production `libmelondsds_libretro.so` SHA-256 remains `83e7b9389083576d70c05466911f4796a8099bc1b43f5d56381c493c0d950c30` and the E2-instrumented experimental ARM64 core SHA-256 remains `9e7a84190385b8aa526f17ffcbbbd2f709d8b1ec3dfc608aa34cb8d6bbeba5f9`.

### Deterministic fault and end-to-end host regression evidence

- The coordinator tests use latches/barriers for cancellation in PREFLIGHT, SEED, BASELINE, WORKING_COPY, CORE_INITIALIZATION, GAME_LOAD, FRAMES and COMMIT; verify cancellation before/after publication authorization, single-run admission, and stale observer replacement. No sleep-based race oracle.
- Store tests cover generation-renamed-but-not-authoritative, before/after HEAD rename, before/during/after directory sync, corrupted candidate, strict cold-process exact-identity reconciliation, and normal baseline plus three immutable generation commits (`1 → 2 → 3 → 4`) with unchanged prior SHA. The JVM crash probe uses a separate process and an abrupt exit, not a simulated normal shutdown.
- Owner tests independently inject PAUSED and CLOSED receipt publication faults, preserve an already verified COMMITTED store outcome, and prove `closeHost()` cannot repeat the terminal native/store callback. Unquiesced workers block unload/shutdown. Legacy quiesced host AUTO checkpoint/retry behavior remains tested.
- P4-C genuine mGBA/melonDS native JNI and checkpoint regression: **PASS**, `verification/jni-checkpoints.log`.
- E1-B genuine licensed ARM9 DLDI writer, 3 cold launches, two ROMs, branch copy, record corruption and OS readonly handling: **PASS**, `verification/e1b-real-dldi.log`; fresh isolated image root `app/build/p4-e1-b/runs/acceptance-Rhkgzb1Z/`.
- Android-compatible Java FAT checker on a genuine game-written image: **PASS**, independent record counter 3 / prior 2 and corrupt alternate FAT rejection, `verification/e3-java-fat.log`.
- E2 native DLDI write/flush/close failure checks: **PASS** all three approved real fault switches, each rejecting publication and preserving the exact canonical baseline image, `verification/e2-native-faults.log`.
- E2 real pinned Linux core and immutable generation store: **PASS**, actual generation `1 → 2 → 3`, `verification/e2-real-generation.log`.
- E2 separate-process abrupt exit (**expected code 87**) and cold owner recovery: **PASS**, `verification/e2-crash-owner.log` and `verification/e2-crash-recover.log`.
- P4-D real GBA/DS canonical/branch PLSV isolation: **PASS**, `verification/d-real-branch-isolation.log`; five branch-publication plus ten selector process-hard-kill classifications: **PASS 15/15**, `verification/d-branch-publication.log`; eight genuine mGBA/melonDS restoration interruption deaths: **PASS 8/8**, `verification/d-real-restore-deaths.log`.
- P4-D4 real pinned GBA SRAM/DS EEPROM promotion, original three-file rollback and next-counter execution: **PASS**, **46/46 fresh-JVM hard-kill recovery boundaries** plus **10/10 explicitly chosen post-kill recoveries** with independently rerun real-core cold loads. Evidence: `verification/d4-promotion-full.log`, ignored root `app/build/p4-d4-acceptance-eqWHgyxh/`.

The E3C owner now references `VirtualSdGenerationStore.Outcome`, so the standalone P4-C/D host compilation scripts previously omitted two transitive Java source files. The first independent P4-C run correctly **failed to compile** (`VirtualSdGenerationStore does not exist`). Five host scripts were corrected to add **only** the explicit `StorageCapacity.java` and `VirtualSdGenerationStore.java` compilation dependencies. Those genuine native regressions subsequently passed; neither emulator assertions nor fault classifications were weakened. The corrected files are `scripts/test-p4-c-checkpoints.sh`, `scripts/test-p4-br-ds-reload.sh`, `scripts/test-p4-d-branch-publication.sh`, `scripts/test-p4-d-real-branch-isolation.sh`, `scripts/test-p4-d-real-restore-deaths.sh`.

The same required Java dependencies were added to the standalone P4-D4 promotion script, and an optional `PLUMP_P4_LEGACY_DS_CORE` test-core override was added to its real-core Python verifier. The override still **asserts the exact accepted original melonDS ELF SHA-256** and retains the original default path; it only avoids overwriting the E2-instrumented Linux host binary at the historical path. The full D4 matrix passed using the accepted ignored D1 fixtures. Changes: `scripts/test-p4-d4-promotion.sh` and `tests/roms/verify-p4-d4-real-core-promotion.py`.

### Frozen replacement experimental APK — DO NOT INSTALL

- **Exact candidate:** `app/build/p4-e3c/plump-e3test-lifecycle-corrected.apk`
- **SHA-256:** `8bbf09550db1c211cf26ce35cb5f14666d807d52ff6ff7d16edbc99f244aeac1`
- **Bytes:** 3,638,404
- **Application ID:** `dev.plump.emulator.e3test`; launcher `dev.plump.emulator.E3TestActivity`; visibly isolated label `Plump E3A • ISOLATED TEST`.
- **Experimental packaged ARM64 core:** `9e7a84190385b8aa526f17ffcbbbd2f709d8b1ec3dfc608aa34cb8d6bbeba5f9`; production core packaged unchanged.
- **Signature:** Android debug signer certificate SHA-256 `c48b3d3b6e5913013476b3bf8f9de667dac6121a6c8e713da3ce986d7b09e016`, APK Signature Scheme **v2 verified**.

The frozen APK is byte-identical to the freshly built `app/build/outputs/apk/e3test/debug/app-e3test-debug.apk`. It **supersedes** the previous E3A capacity correction candidate for future mastermind review, but no update or installation is authorized here. All binaries/images and evidence remain Git-ignored; no user private backup was inspected or touched.

## Limitations and mastermind handoff

**Host engineering candidate: ready for mastermind review.** The confirmed physical E3B counter progression and four existing E3 generations are user-supplied accepted context, not E3C test inputs. E3C has **not** been exercised on the physical Android device; actual lifecycle interruption during `StatFs`/`StorageManager`, native load, frame execution, in-flight close, `Os.fsync`, per-device atomic rename timing or screen recreation remains the subject of a **separately authorized device test**. Linux fault injection and JVM tests cannot establish physical Android power-cut durability.

**Important E3B evidence protection / next test limitation:** the existing E3 test app already holds the accepted baseline plus three writes, ending at **sequence 4**. E3C deliberately retains the original three-write cap and exact canonical namespace; therefore pressing Run against that existing verified store will **fail closed at the cap** rather than overwrite or advance the accepted E3B data. To perform actual E3C foreground/lifecycle interruption tests, the mastermind must separately approve a **different disposable test-only data namespace or package identity** that cannot alter the four accepted E3B generations. Do not clear, reset, migrate, overwrite, or use those accepted generations as failure-injection input. This authorization does not include a new test namespace or physical installation.

No ADB/device/install, personal ROM/save data or backup, E3B generation inspection/modification, upstream source/core revision change, production writable-DLDI enablement, PLSV format change, GitHub push or unrelated milestone work. The pre-existing modified `docs/P3_TECHNICAL_REPORT.md` and untracked `docs/P4_ENGINEERING_PLAN.md` were preserved. The single reviewed E3C local commit ID and final Git status are supplied in the engineering closeout; do not include generated APKs or data in Git. **Stop at the E3C mastermind review gate.**
