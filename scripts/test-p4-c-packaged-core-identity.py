#!/usr/bin/env python3
"""Verify exact APK-packaged emulator ELF digests against Android runtime pins.

Run after assembleDebug AND assembleRelease. Never installs an APK or reads phone data.
"""

import argparse
import hashlib
import os
import re
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path


REPO = Path(__file__).resolve().parent.parent
JAVA = REPO / "app/src/main/java/dev/plump/emulator/PinnedCheckpointIdentity.java"
CORES = (
    ("mgba", "libmgba_libretro.so", "MGBA"),
    ("melonds", "libmelondsds_libretro.so", "MELONDS"),
)


class VerificationError(Exception):
    pass


def sha256(payload):
    return hashlib.sha256(payload).hexdigest()


def pin(source, name):
    matches = re.findall(
        rf'^\s*static final String {re.escape(name)} = "([0-9a-f]{{64}})";',
        source,
        re.MULTILINE,
    )
    if len(matches) != 1:
        raise VerificationError(f"Exactly one literal production pin required: {name}")
    return matches[0]


def require_digest(label, payload, expected):
    actual = sha256(payload)
    if actual != expected:
        raise VerificationError(f"{label}: SHA-256 mismatch (expected {expected}, actual {actual})")
    print(f"PASS {label}: {len(payload)} bytes SHA-256 {actual}")
    return actual


def java_probe(classpath, file, core_id, variant, expectation):
    subprocess.run(
        ["java", "-cp", classpath, "dev.plump.emulator.PackagedCheckpointIdentityProbe",
         str(file), core_id, variant, expectation],
        check=True,
    )


def verify_apk(apk, variant, source, negative_test, scratch, classpath, aapt):
    if not apk.is_file():
        raise VerificationError(f"{variant}: assembled APK is missing: {apk}")
    print(f"APK {variant}: {apk.relative_to(REPO) if apk.is_relative_to(REPO) else apk}"
          f" SHA-256 {sha256(apk.read_bytes())}")
    manifest = subprocess.check_output([str(aapt), "dump", "xmltree", str(apk), "AndroidManifest.xml"],
                                       text=True)
    debuggable = bool(re.search(r'android:debuggable\(.*0xffffffff', manifest))
    extracts_libs = bool(re.search(r'android:extractNativeLibs\(.*0xffffffff', manifest))
    if debuggable != (variant == "debug"):
        raise VerificationError(f"{variant}: APK manifest debuggable flag disagrees with runtime pin selection")
    if not extracts_libs:
        raise VerificationError(f"{variant}: APK does not promise extracted nativeLibraryDir files")
    print(f"PASS {variant} manifest: debuggable={debuggable}, extractNativeLibs=true")
    stripped_dir = REPO / "app/build/intermediates/stripped_native_libs" / variant
    stripped_dir /= "stripDebugDebugSymbols" if variant == "debug" else "stripReleaseDebugSymbols"
    stripped_dir /= "out/lib/arm64-v8a"

    with zipfile.ZipFile(apk) as archive:
        for core, filename, key in CORES:
            entry = "lib/arm64-v8a/" + filename
            if archive.namelist().count(entry) != 1:
                raise VerificationError(f"{variant}: expected exactly one packaged {entry}")
            packed = archive.read(entry)
            runtime = pin(source, f"{key}_{variant.upper()}_PACKAGED_SHA256")
            require_digest(f"{variant} APK installed-extraction bytes {core}", packed, runtime)
            installed_copy = scratch / f"{variant}-{filename}"
            installed_copy.write_bytes(packed)
            java_probe(classpath, installed_copy,
                       "mgba-libretro" if core == "mgba" else "melonds-libretro-ds",
                       variant, "accept")
            stripped = stripped_dir / filename
            if not stripped.is_file():
                raise VerificationError(f"{variant}: missing Gradle stripped ELF: {stripped}")
            require_digest(f"{variant} Gradle stripped ELF {core}", stripped.read_bytes(), runtime)
            source_file = REPO / "app/src/main/jniLibs/arm64-v8a" / filename
            if not source_file.is_file():
                raise VerificationError(f"{variant}: missing pinned input ELF: {source_file}")
            unstripped = pin(source, f"{key}_UNSTRIPPED_INPUT_SHA256")
            require_digest(f"unstripped source/build input {core}", source_file.read_bytes(), unstripped)
            if core == "mgba":
                # The exact original defect: the source ELF must NOT pass the packaged runtime pin.
                java_probe(classpath, source_file, "mgba-libretro", variant, "reject")

            if negative_test and core == "mgba":
                modified = bytearray(packed)
                modified[len(modified) // 2] ^= 1
                changed_copy = scratch / f"altered-{variant}-{filename}"
                changed_copy.write_bytes(modified)
                try:
                    require_digest("deliberately altered mGBA APK ELF", modified, runtime)
                except VerificationError:
                    print(f"PASS {variant} NEGATIVE: unexpected packaged mGBA digest rejected")
                else:
                    raise VerificationError("An altered packaged mGBA binary was incorrectly accepted")
                java_probe(classpath, changed_copy, "mgba-libretro", variant, "reject")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--debug-apk", type=Path,
                        default=REPO / "app/build/outputs/apk/debug/app-debug.apk")
    parser.add_argument("--release-apk", type=Path,
                        default=REPO / "app/build/outputs/apk/release/app-release-unsigned.apk")
    parser.add_argument("--negative-test", action="store_true",
                        help="Check that one altered packaged mGBA byte is rejected per variant")
    args = parser.parse_args()
    try:
        source = JAVA.read_text(encoding="utf-8")
        work = REPO / "app/build/p4-c-identity-review"
        work.mkdir(parents=True, exist_ok=True)
        android_sdk = Path(os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
                           or Path.home() / "Android/Sdk")
        android_jar = android_sdk / "platforms/android-35/android.jar"
        aapt = android_sdk / "build-tools/35.0.0/aapt"
        app_classes = REPO / "app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes"
        if not android_jar.is_file() or not aapt.is_file() or not (app_classes / "dev/plump/emulator/PinnedCheckpointIdentity.class").is_file():
            raise VerificationError("Build the debug APK and configure Android SDK 35 before verifying runtime Java")
        with tempfile.TemporaryDirectory(prefix="apk-identity-", dir=work) as scratch_name:
            scratch = Path(scratch_name)
            probe_classes = scratch / "classes"
            probe_classes.mkdir()
            probe_classpath = os.pathsep.join((str(probe_classes), str(app_classes), str(android_jar)))
            subprocess.run(["javac", "--release", "17", "-cp", probe_classpath, "-d", str(probe_classes),
                            str(REPO / "tests/identity/PackagedCheckpointIdentityProbe.java")], check=True)
            verify_apk(args.debug_apk.resolve(), "debug", source, args.negative_test, scratch, probe_classpath, aapt)
            verify_apk(args.release_apk.resolve(), "release", source, args.negative_test, scratch, probe_classpath, aapt)
    except (OSError, subprocess.CalledProcessError, zipfile.BadZipFile, VerificationError) as error:
        print(f"FAIL packaged checkpoint identity: {error}", file=sys.stderr)
        return 1
    print("PASS both assembled APK variants contain exactly pinned runtime core binaries")
    return 0


if __name__ == "__main__":
    sys.exit(main())
