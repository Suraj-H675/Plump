#include <jni.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <android/log.h>

#include <algorithm>
#include <cassert>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <iterator>
#include <string>
#include <utility>
#include <vector>

struct ANativeWindow {};
extern "C" ANativeWindow* ANativeWindow_fromSurface(JNIEnv*, jobject) { return nullptr; }
extern "C" void ANativeWindow_release(ANativeWindow*) { }
extern "C" int ANativeWindow_setBuffersGeometry(ANativeWindow*, int, int, int) { return -1; }
extern "C" int ANativeWindow_lock(ANativeWindow*, ANativeWindow_Buffer*, void*) { return -1; }
extern "C" int ANativeWindow_unlockAndPost(ANativeWindow*) { return -1; }
extern "C" int __android_log_print(int, const char*, const char*, ...) { return 0; }

#include "../../app/src/main/cpp/plump_host.cpp"

extern "C" JNIEXPORT jobjectArray JNICALL
Java_dev_plump_emulator_HostCheckpointProbe_captureGba(JNIEnv* env, jclass) {
    return Java_dev_plump_emulator_NativeCore_captureCheckpoint(env, nullptr);
}
extern "C" JNIEXPORT jobjectArray JNICALL
Java_dev_plump_emulator_HostCheckpointProbe_captureDs(JNIEnv*, jclass) { return nullptr; }

static std::vector<uint8_t> readFile(const char* path) {
    std::ifstream stream(path, std::ios::binary);
    assert(stream);
    return {std::istreambuf_iterator<char>(stream), std::istreambuf_iterator<char>()};
}

static jbyteArray bytes(JNIEnv* env, const std::vector<uint8_t>& value) {
    jbyteArray out = env->NewByteArray(static_cast<jsize>(value.size()));
    assert(out);
    env->SetByteArrayRegion(out, 0, static_cast<jsize>(value.size()),
                            reinterpret_cast<const jbyte*>(value.data()));
    assert(!env->ExceptionCheck());
    return out;
}

static std::vector<uint8_t> copy(JNIEnv* env, jbyteArray value) {
    assert(value);
    const jsize length = env->GetArrayLength(value);
    std::vector<uint8_t> result(static_cast<size_t>(length));
    env->GetByteArrayRegion(value, 0, length, reinterpret_cast<jbyte*>(result.data()));
    assert(!env->ExceptionCheck());
    return result;
}

static std::pair<std::vector<uint8_t>, std::vector<uint8_t>> capture(JNIEnv* env) {
    jobjectArray result = Java_dev_plump_emulator_NativeCore_captureCheckpoint(env, nullptr);
    assert(result && env->GetArrayLength(result) == 2);
    auto machine = static_cast<jbyteArray>(env->GetObjectArrayElement(result, 0));
    auto nativeSave = static_cast<jbyteArray>(env->GetObjectArrayElement(result, 1));
    assert(machine && nativeSave);
    auto copiedMachine = copy(env, machine);
    auto copiedNative = copy(env, nativeSave);
    env->DeleteLocalRef(machine);
    env->DeleteLocalRef(nativeSave);
    env->DeleteLocalRef(result);
    return {std::move(copiedMachine), std::move(copiedNative)};
}

static JavaVM* startVm(JNIEnv** env, const char* classPath) {
    JavaVM* vm = nullptr;
    std::string errorFile = std::string("-XX:ErrorFile=") +
            (std::getenv("PLUMP_JVM_ERROR_FILE") ? std::getenv("PLUMP_JVM_ERROR_FILE") :
             "/tmp/plump-mgba-hs_err_pid%p.log");
    std::string classPathOption = classPath ? std::string("-Djava.class.path=") + classPath : std::string();
    JavaVMOption options[] = {{errorFile.data(), nullptr}, {classPathOption.data(), nullptr}};
    JavaVMInitArgs args{JNI_VERSION_1_8, classPath ? 2 : 1, options, JNI_FALSE};
    assert(JNI_CreateJavaVM(&vm, reinterpret_cast<void**>(env), &args) == JNI_OK);
    return vm;
}

static void runOwnerCheckpointProbe(JNIEnv* env, const char* method, const char* root,
                                    const char* romSha, const char* hostSha) {
    jclass probe = env->FindClass("dev/plump/emulator/HostCheckpointProbe");
    assert(probe && !env->ExceptionCheck());
    JNINativeMethod captureMethods[] = {
        {const_cast<char*>("captureGba"), const_cast<char*>("()[[B"),
         reinterpret_cast<void*>(Java_dev_plump_emulator_HostCheckpointProbe_captureGba)},
        {const_cast<char*>("captureDs"), const_cast<char*>("()[[B"),
         reinterpret_cast<void*>(Java_dev_plump_emulator_HostCheckpointProbe_captureDs)},
    };
    assert(env->RegisterNatives(probe, captureMethods, 2) == JNI_OK);
    jmethodID entry = env->GetStaticMethodID(probe, method,
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
    assert(entry && !env->ExceptionCheck());
    jstring rootArg = env->NewStringUTF(root);
    jstring romArg = env->NewStringUTF(romSha);
    jstring coreArg = env->NewStringUTF(hostSha);
    env->CallStaticVoidMethod(probe, entry, rootArg, romArg, coreArg);
    if (env->ExceptionCheck()) { env->ExceptionDescribe(); std::abort(); }
    env->DeleteLocalRef(rootArg);
    env->DeleteLocalRef(romArg);
    env->DeleteLocalRef(coreArg);
    env->DeleteLocalRef(probe);
}

static void unsupportedSerializerTest(JNIEnv* env, JavaVM* vm, const char* romPath) {
    assert(!Java_dev_plump_emulator_NativeCore_initialize(env, nullptr));
    assert(Java_dev_plump_emulator_NativeCore_serializerCapabilities(env, nullptr) == 0);
    assert(Java_dev_plump_emulator_NativeCore_serializerApiVersion(env, nullptr) == -1);
    auto rom = readFile(romPath);
    assert(!Java_dev_plump_emulator_NativeCore_loadGame(env, nullptr, bytes(env, rom)));
    assert(!Java_dev_plump_emulator_NativeCore_captureCheckpoint(env, nullptr));
    Java_dev_plump_emulator_NativeCore_unloadGame(env, nullptr);
    Java_dev_plump_emulator_NativeCore_shutdown(env, nullptr);
    assert(vm->DestroyJavaVM() == JNI_OK);
    std::puts("PASS mGBA unsupported serializer: frontend remains usable and capture is rejected");
}

static void unsupportedCapabilityOnlyTest(JNIEnv* env, JavaVM* vm) {
    assert(!Java_dev_plump_emulator_NativeCore_initialize(env, nullptr));
    assert(Java_dev_plump_emulator_NativeCore_serializerCapabilities(env, nullptr) == 0);
    assert(Java_dev_plump_emulator_NativeCore_serializerApiVersion(env, nullptr) == -1);
    auto fakeRom = bytes(env, std::vector<uint8_t>(512, 0));
    assert(!Java_dev_plump_emulator_NativeCore_loadGame(env, nullptr, fakeRom));
    env->DeleteLocalRef(fakeRom);
    assert(Java_dev_plump_emulator_NativeCore_saveRamSize(env, nullptr) == 16);
    assert(!Java_dev_plump_emulator_NativeCore_captureCheckpoint(env, nullptr));
    Java_dev_plump_emulator_NativeCore_unloadGame(env, nullptr);
    Java_dev_plump_emulator_NativeCore_shutdown(env, nullptr);
    assert(vm->DestroyJavaVM() == JNI_OK);
    std::puts("PASS mGBA unsupported serializer: frontend remains usable and capture is rejected");
}

static void rejectedCaptureTest(JNIEnv* env, JavaVM* vm, const char* label) {
    assert(!Java_dev_plump_emulator_NativeCore_initialize(env, nullptr));
    assert(Java_dev_plump_emulator_NativeCore_serializerCapabilities(env, nullptr) == 15);
    assert(Java_dev_plump_emulator_NativeCore_serializerApiVersion(env, nullptr) == 1);
    auto fakeRom = bytes(env, std::vector<uint8_t>(512, 0));
    assert(!Java_dev_plump_emulator_NativeCore_loadGame(env, nullptr, fakeRom));
    env->DeleteLocalRef(fakeRom);
    jbyteArray beforeArray = Java_dev_plump_emulator_NativeCore_snapshotSaveRam(env, nullptr);
    assert(beforeArray);
    const auto before = copy(env, beforeArray);
    env->DeleteLocalRef(beforeArray);
    assert(before.size() == 16);
    assert(!Java_dev_plump_emulator_NativeCore_captureCheckpoint(env, nullptr));
    jbyteArray afterArray = Java_dev_plump_emulator_NativeCore_snapshotSaveRam(env, nullptr);
    assert(afterArray && copy(env, afterArray) == before);
    env->DeleteLocalRef(afterArray);
    Java_dev_plump_emulator_NativeCore_unloadGame(env, nullptr);
    Java_dev_plump_emulator_NativeCore_shutdown(env, nullptr);
    assert(vm->DestroyJavaVM() == JNI_OK);
    std::printf("PASS mGBA %s capture: rejected without changing native save RAM\n", label);
}

int main(int argc, char** argv) {
    JNIEnv* env = nullptr;
    const char* classPath = argc == 6 ? argv[3] : nullptr;
    JavaVM* vm = startVm(&env, classPath);
    if (argc == 3 && std::string(argv[1]) == "--unsupported-serializer") {
        unsupportedSerializerTest(env, vm, argv[2]);
        return 0;
    }
    if (argc == 2 && std::string(argv[1]) == "--unsupported-serializer") {
        unsupportedCapabilityOnlyTest(env, vm);
        return 0;
    }
    if (argc == 2 && std::string(argv[1]) == "--mutating-serializer") {
        rejectedCaptureTest(env, vm, "mutating-serializer");
        return 0;
    }
    if (argc == 2 && std::string(argv[1]) == "--oversized-serializer") {
        rejectedCaptureTest(env, vm, "oversized-serializer");
        return 0;
    }
    assert(argc == 6 && "usage: MGBA_TEST LICENSED_GBA_ROM WORKDIR PROBE_CLASSES ROM_SHA HOST_CORE_SHA");
    const auto rom = readFile(argv[1]);
    assert(rom.size() > 192);
    assert(!Java_dev_plump_emulator_NativeCore_initialize(env, nullptr));
    assert(Java_dev_plump_emulator_NativeCore_serializerCapabilities(env, nullptr) == 15);
    assert(Java_dev_plump_emulator_NativeCore_serializerApiVersion(env, nullptr) == 1);
    jbyteArray romArray = bytes(env, rom);
    assert(!Java_dev_plump_emulator_NativeCore_loadGame(env, nullptr, romArray));
    env->DeleteLocalRef(romArray);
    for (int frame = 0; frame < 200; ++frame) Java_dev_plump_emulator_NativeCore_runFrame(env, nullptr);

    jbyteArray beforeArray = Java_dev_plump_emulator_NativeCore_snapshotSaveRam(env, nullptr);
    assert(beforeArray);
    auto before = copy(env, beforeArray);
    env->DeleteLocalRef(beforeArray);
    assert(before.size() == 32768);
    const auto first = capture(env);
    const auto second = capture(env);
    assert(first.first.size() == 430144);
    assert(first.first == second.first);
    assert(first.second == before && first.second == second.second);
    jbyteArray afterArray = Java_dev_plump_emulator_NativeCore_snapshotSaveRam(env, nullptr);
    assert(afterArray && copy(env, afterArray) == before);
    env->DeleteLocalRef(afterArray);
    const std::string checkpointRoot = std::string(argv[2]) + "/real-owner-checkpoints";
    runOwnerCheckpointProbe(env, "runGba", checkpointRoot.c_str(), argv[4], argv[5]);
    jbyteArray afterOwnerArray = Java_dev_plump_emulator_NativeCore_snapshotSaveRam(env, nullptr);
    assert(afterOwnerArray && copy(env, afterOwnerArray) == before);
    env->DeleteLocalRef(afterOwnerArray);
    for (int frame = 0; frame < 20; ++frame) Java_dev_plump_emulator_NativeCore_runFrame(env, nullptr);
    jbyteArray statePair = bytes(env, first.first);
    jbyteArray nativePair = bytes(env, first.second);
    assert(!Java_dev_plump_emulator_NativeCore_restoreCheckpoint(env, nullptr, statePair, nativePair));
    jbyteArray restoredArray = Java_dev_plump_emulator_NativeCore_snapshotSaveRam(env, nullptr);
    assert(restoredArray && copy(env, restoredArray) == first.second);
    env->DeleteLocalRef(restoredArray);
    std::vector<uint8_t> shortState(first.first.begin(), first.first.end() - 1);
    jbyteArray shortStateArray = bytes(env, shortState);
    jstring rejected = Java_dev_plump_emulator_NativeCore_restoreCheckpoint(
            env, nullptr, shortStateArray, nativePair);
    assert(rejected);
    env->DeleteLocalRef(rejected);
    restoredArray = Java_dev_plump_emulator_NativeCore_snapshotSaveRam(env, nullptr);
    assert(restoredArray && copy(env, restoredArray) == first.second);
    env->DeleteLocalRef(restoredArray);
    std::vector<uint8_t> shortNative(first.second.begin(), first.second.end() - 1);
    jbyteArray shortNativeArray = bytes(env, shortNative);
    rejected = Java_dev_plump_emulator_NativeCore_restoreCheckpoint(
            env, nullptr, statePair, shortNativeArray);
    assert(rejected);
    env->DeleteLocalRef(rejected);
    restoredArray = Java_dev_plump_emulator_NativeCore_snapshotSaveRam(env, nullptr);
    assert(restoredArray && copy(env, restoredArray) == first.second);
    env->DeleteLocalRef(restoredArray);

    // Production D3 uses a fresh, branch-owned mGBA instance. A newly loaded
    // cartridge initially reports provisional 128-KiB SRAM and the wrong
    // serialization size; priming one branch-only frame resolves its geometry.
    Java_dev_plump_emulator_NativeCore_unloadGame(env, nullptr);
    Java_dev_plump_emulator_NativeCore_shutdown(env, nullptr);
    assert(!Java_dev_plump_emulator_NativeCore_initialize(env, nullptr));
    romArray = bytes(env, rom);
    assert(!Java_dev_plump_emulator_NativeCore_loadGame(env, nullptr, romArray));
    env->DeleteLocalRef(romArray);
    assert(Java_dev_plump_emulator_NativeCore_saveRamSize(env, nullptr) == 131072);
    rejected = Java_dev_plump_emulator_NativeCore_restoreCheckpoint(env, nullptr, statePair, nativePair);
    assert(rejected && "Unprimed GBA must reject mismatched SRAM/state geometry");
    env->DeleteLocalRef(rejected);
    Java_dev_plump_emulator_NativeCore_setButtons(env, nullptr, 0);
    Java_dev_plump_emulator_NativeCore_runFrame(env, nullptr);
    Java_dev_plump_emulator_NativeCore_discardAudio(env, nullptr);
    assert(Java_dev_plump_emulator_NativeCore_saveRamSize(env, nullptr) == 32768);
    assert(!Java_dev_plump_emulator_NativeCore_restoreCheckpoint(env, nullptr, statePair, nativePair));
    restoredArray = Java_dev_plump_emulator_NativeCore_snapshotSaveRam(env, nullptr);
    assert(restoredArray && copy(env, restoredArray) == first.second);
    env->DeleteLocalRef(restoredArray);
    for (int frame = 0; frame < 5; ++frame) Java_dev_plump_emulator_NativeCore_runFrame(env, nullptr);
    restoredArray = Java_dev_plump_emulator_NativeCore_snapshotSaveRam(env, nullptr);
    assert(restoredArray && copy(env, restoredArray) == first.second);
    env->DeleteLocalRef(restoredArray);
    std::puts("PASS JNI FRESH BRANCH RESTORE: mGBA 128-KiB provisional geometry rejects, 1-frame 32-KiB geometry restores pinned historical pair");

    env->DeleteLocalRef(shortNativeArray);
    env->DeleteLocalRef(shortStateArray);
    env->DeleteLocalRef(statePair);
    env->DeleteLocalRef(nativePair);
    std::puts("PASS JNI HISTORICAL RESTORE: mGBA exact state/native pair and SRAM readback; wrong state length rejected");
    std::printf("PASS JNI CHECKPOINT: mGBA API v1 state=%zu bytes native=%zu bytes stable twice; SRAM unchanged\n",
                first.first.size(), first.second.size());
    Java_dev_plump_emulator_NativeCore_unloadGame(env, nullptr);
    Java_dev_plump_emulator_NativeCore_shutdown(env, nullptr);
    assert(vm->DestroyJavaVM() == JNI_OK);
    return 0;
}
