#!/usr/bin/env python3
"""Host-only strict, fail-closed production-vs-E3 experimental APK audit."""

import hashlib
import gzip
import io
import os
from pathlib import Path
import re
import subprocess
import sys
from zipfile import ZipFile

REPO = Path(__file__).resolve().parent.parent
BUILD = REPO / "app/build"
SDK = Path(os.environ.get("ANDROID_HOME", str(Path.home() / "Android/Sdk")))
AAPT = SDK / "build-tools/35.0.0/aapt"
READELF = SDK / "ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
PROD_CORE = "83e7b9389083576d70c05466911f4796a8099bc1b43f5d56381c493c0d950c30"
ALPHA_ROM = "1dbbf7e6dea0068a4495b5d3fff5d20f61b099ba48bf9fc0dc0512b136c40588"
BASELINE_SHA = "14788027f3601eafd227b15fd1ce50563f134d82ba47d3746b5f72d84539bd8e"


def sha(data):
    return hashlib.sha256(data).hexdigest()


def run(*args):
    result = subprocess.run(args, capture_output=True, text=True, check=True)
    return result.stdout


def inspect(path, expect_id, expect_label, expect_core, experimental):
    if not path.is_file():
        raise AssertionError("Missing APK: " + str(path))
    badging = run(str(AAPT), "dump", "badging", str(path))
    application = re.search(r"^package: name='([^']+)'", badging, re.M)
    if application is None or application.group(1) != expect_id:
        raise AssertionError("Unacceptable application ID: " + str(path))
    label = re.search(r"^application-label:'([^']+)'", badging, re.M)
    if label is None or not label.group(1).startswith(expect_label):
        raise AssertionError("APK test/production label mismatch")
    launch = re.search(r"^launchable-activity: name='([^']+)'", badging, re.M)
    if launch is None:
        raise AssertionError("APK has no launchable activity")
    expected_launch = "dev.plump.emulator.E3TestActivity" if experimental else "dev.plump.emulator.MainActivity"
    if launch.group(1) != expected_launch:
        raise AssertionError("APK exposes the wrong launcher: " + launch.group(1))
    permissions = run(str(AAPT), "dump", "permissions", str(path))
    if "android.permission.INTERNET" in permissions:
        raise AssertionError("Offline Plump APK requests Internet permission")
    xmltree = run(str(AAPT), "dump", "xmltree", str(path), "AndroidManifest.xml")
    if "android:allowBackup(0x01010280)=(type 0x12)0x0" not in xmltree:
        raise AssertionError("APK does not explicitly disable backup")
    with ZipFile(path) as archive:
        names = archive.namelist()
        libs = sorted(n for n in names if n.startswith("lib/"))
        if not libs or any(not n.startswith("lib/arm64-v8a/") for n in libs):
            raise AssertionError("Unexpected APK native architecture")
        lib = "lib/arm64-v8a/libmelondsds_libretro.so"
        if lib not in names or sha(archive.read(lib)) != expect_core:
            raise AssertionError("Packaged melonDS core failed exact ELF checksum")
        if "lib/arm64-v8a/libplump_ds_host.so" not in names:
            raise AssertionError("Missing Android JNI host library")
        game = "assets/e3-dldi-alpha.nds"
        seed = "assets/e3-fat16-baseline.e3seed"
        if experimental:
            if game not in names or sha(archive.read(game)) != ALPHA_ROM:
                raise AssertionError("E3 APK lacks exact licensed diagnostic fixture")
            if seed not in names:
                raise AssertionError("E3 test-only baseline asset unavailable")
            hasher = hashlib.sha256()
            total = 0
            with gzip.GzipFile(fileobj=io.BytesIO(archive.read(seed)), mode="rb") as uncompressed:
                for block in iter(lambda: uncompressed.read(1024 * 1024), b""):
                    total += len(block)
                    if total > 128 * 1024 * 1024:
                        raise AssertionError("E3 FAT baseline expands past 128 MiB")
                    hasher.update(block)
            if total != 128 * 1024 * 1024 or hasher.hexdigest() != BASELINE_SHA:
                raise AssertionError("APK packaged FAT baseline bytes do not match immutable source")
        elif game in names or seed in names:
            raise AssertionError("Production APK accidentally packages test-only data")
        jni = archive.read("lib/arm64-v8a/libplump_ds_host.so")
        # Inspect actual ELF symbols, not textual source or unchecked manifest.
        elfname = BUILD / "p4-e3a" / ("audit-e3-jni.so" if experimental else "audit-prod-jni.so")
        elfname.parent.mkdir(parents=True, exist_ok=True)
        elfname.write_bytes(jni)
    elf = run(str(READELF), "-Ws", str(elfname))
    for symbol in (
        "Java_dev_plump_emulator_NativeDs_initializeE3",
        "Java_dev_plump_emulator_NativeDs_closeE3Dldi",
    ):
        exists = bool(re.search(r"\b" + re.escape(symbol) + r"\s*$", elf, re.M))
        if exists != experimental:
            raise AssertionError("Wrong experimental JNI ABI exposure: " + symbol)
    print("PASS_E3_PACKAGE", expect_id, "launcher", expected_launch,
          "core_sha256", expect_core, "apk_sha256", sha(path.read_bytes()),
          "size", path.stat().st_size, "jni_test_exports", experimental)


def main():
    core_file = BUILD / "p4-e3a/libmelondsds_libretro.so"
    receipt_file = BUILD / "p4-e3a/android-core.sha256"
    core_sha = sha(core_file.read_bytes())
    if core_sha != receipt_file.read_text().split()[0] or core_sha == PROD_CORE:
        raise AssertionError("E3 ARM64 core hash missing, stale or equal to production")
    inspect(BUILD / "outputs/apk/production/debug/app-production-debug.apk",
            "dev.plump.emulator", "Plump", PROD_CORE, False)
    inspect(BUILD / "outputs/apk/production/release/app-production-release-unsigned.apk",
            "dev.plump.emulator", "Plump", PROD_CORE, False)
    inspect(BUILD / "outputs/apk/e3test/debug/app-e3test-debug.apk",
            "dev.plump.emulator.e3test", "Plump E3A", core_sha, True)
    if len(sys.argv) == 2 and sys.argv[1] == "--negative-test":
        test_apk = BUILD / "outputs/apk/e3test/debug/app-e3test-debug.apk"
        prod_apk = BUILD / "outputs/apk/production/debug/app-production-debug.apk"
        bad_comparisons = [
            (test_apk, "dev.plump.emulator", "Plump E3A", core_sha, True),
            (test_apk, "dev.plump.emulator.e3test", "Plump E3A", PROD_CORE, True),
            (prod_apk, "dev.plump.emulator", "Plump", core_sha, False),
        ]
        for apk, app_id, label, expected_hash, mode in bad_comparisons:
            try:
                inspect(apk, app_id, label, expected_hash, mode)
            except AssertionError:
                continue
            raise AssertionError("Negative package ID/core substitution was accepted")
        print("PASS_E3_REJECTED_THREE_PRODUCTION_EXPERIMENTAL_IDENTITY_SUBSTITUTIONS")
    print("PASS_E3_APK_IDENTITY_AND_NATIVE_ELF_AND_LAUNCHER_ISOLATION")


if __name__ == "__main__":
    try:
        main()
    except (AssertionError, OSError, subprocess.CalledProcessError) as e:
        print("FAIL_E3_APK_IDENTITY:", e, file=sys.stderr)
        raise SystemExit(1)
