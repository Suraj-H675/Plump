#!/usr/bin/env python3
"""Fail-closed audit of the distinct corrected E3F Android native-close test APK."""

import hashlib
import importlib.util
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BUILD = ROOT / "app" / "build"
EXPECTED_NATIVE = "abedb9ee7c1fcdc91a6b4451d3fba7fc9679f922e8b665c745e07a589eab27ad"
EXPECTED_E3A = "9e7a84190385b8aa526f17ffcbbbd2f709d8b1ec3dfc608aa34cb8d6bbeba5f9"
EXPECTED_PROD = "83e7b9389083576d70c05466911f4796a8099bc1b43f5d56381c493c0d950c30"
EXPECTED_APK = "97d4de76edfacd5ab69c3e03aba560dea71b954f517575b9366a3b2ac6400e40"


def digest(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def main():
    source = BUILD / "p4-e3f-close-core" / "deps" / "libretro-common-src"
    patched = source / "vfs" / "vfs_implementation.c"
    original = BUILD / "p4-e3a" / "deps" / "libretro-common-src" / "vfs" / "vfs_implementation.c"
    marker = "PLUMP_E2_FAIL_FILE_CLOSE"
    assert marker in patched.read_text(), "Corrected E3F source lacks unique close fault marker"
    assert marker not in original.read_text(), "Frozen E3A input has been altered"
    assert digest(BUILD / "p4-e3f-close-core" / "libmelondsds_libretro.so") == EXPECTED_NATIVE
    assert (BUILD / "p4-e3f-close-core" / "core.sha256").read_text().split()[0] == EXPECTED_NATIVE
    assert digest(BUILD / "p4-e3a" / "libmelondsds_libretro.so") == EXPECTED_E3A
    assert digest(ROOT / "app/src/main/jniLibs/arm64-v8a/libmelondsds_libretro.so") == EXPECTED_PROD

    spec = importlib.util.spec_from_file_location(
        "plump_apk_audit", ROOT / "scripts" / "verify-p4-e3a-apk-isolation.py")
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    apk = BUILD / "p4-e3f-native" / "plump-e3close.apk"
    assert digest(apk) == EXPECTED_APK, "Frozen E3F APK differs from device-accepted candidate"
    module.inspect(apk, "dev.plump.emulator.e3close", "Plump E3F", EXPECTED_NATIVE, True)
    for wrong in (
        "dev.plump.emulator",
        "dev.plump.emulator.e3test",
        "dev.plump.emulator.e3interrupt",
        "dev.plump.emulator.e3native",
    ):
        try:
            module.inspect(apk, wrong, "Plump E3F", EXPECTED_NATIVE, True)
        except AssertionError:
            continue
        raise AssertionError("Unsafe cross-package identity accepted: " + wrong)
    print("PASS E3F CORRECTED CORE/PATCH/APK ISOLATION; ORIGINAL CORES UNCHANGED")


if __name__ == "__main__":
    main()
