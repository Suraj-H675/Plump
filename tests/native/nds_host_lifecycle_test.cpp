// Real pinned Linux melonDS, called through the *production Android JNI host*.
// Minimal Window/Log stubs provide two pixel buffers; no Android phone needed.
// The core remains real: Libretro frames, sound, pointer/buttons, and SRAM.
#include <jni.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <android/log.h>

#include <algorithm>
#include <array>
#include <cassert>
#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iterator>
#include <string>
#include <utility>
#include <vector>

struct ANativeWindow {
    std::vector<uint8_t> pixels;
    int width = 0, height = 0, format = 0;
    unsigned posts = 0;
};

static unsigned topPosts = 0, bottomPosts = 0;
static std::array<uint8_t, 256 * 192 * 4> lastTop{}, lastBottom{};
static int windowIndex = 0;

extern "C" ANativeWindow* ANativeWindow_fromSurface(JNIEnv*, jobject surface) {
    if (!surface) return nullptr;
    return new ANativeWindow();
}
extern "C" void ANativeWindow_release(ANativeWindow* window) { delete window; }
extern "C" int ANativeWindow_setBuffersGeometry(ANativeWindow* w, int width, int height, int fmt) {
    if (!w || width != 256 || height != 192 || fmt != WINDOW_FORMAT_RGBA_8888) return -1;
    w->width = width; w->height = height; w->format = fmt;
    w->pixels.resize(static_cast<size_t>(width) * height * 4);
    return 0;
}
extern "C" int ANativeWindow_lock(ANativeWindow* w, ANativeWindow_Buffer* out, void*) {
    if (!w || !out || w->pixels.empty()) return -1;
    *out = {};
    out->bits = w->pixels.data();
    out->width = w->width; out->height = w->height;
    out->stride = w->width; out->format = w->format;
    return 0;
}
extern "C" int ANativeWindow_unlockAndPost(ANativeWindow* w) {
    assert(w && w->pixels.size() == 256 * 192 * 4);
    if (windowIndex++ % 2 == 0) {
        std::copy(w->pixels.begin(), w->pixels.end(), lastTop.begin());
        ++topPosts;
    } else {
        std::copy(w->pixels.begin(), w->pixels.end(), lastBottom.begin());
        ++bottomPosts;
    }
    return 0;
}
extern "C" int __android_log_print(int, const char*, const char*, ...) { return 0; }

#ifdef PLUMP_DS_HOST_SOURCE
#include PLUMP_DS_HOST_SOURCE
#else
#include "../../app/src/main/cpp/nds_host.cpp"
#endif

#ifndef PLUMP_TEST_OLD_ADAPTER
extern "C" JNIEXPORT jobjectArray JNICALL
Java_dev_plump_emulator_HostCheckpointProbe_captureDs(JNIEnv* env, jclass) {
    return Java_dev_plump_emulator_NativeDs_captureCheckpoint(env, nullptr);
}
extern "C" JNIEXPORT jobjectArray JNICALL
Java_dev_plump_emulator_HostCheckpointProbe_captureGba(JNIEnv*, jclass) { return nullptr; }
#endif

static std::vector<uint8_t> readFile(const char* path) {
    std::ifstream stream(path, std::ios::binary);
    assert(stream);
    return {std::istreambuf_iterator<char>(stream), std::istreambuf_iterator<char>()};
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
static jbyteArray bytes(JNIEnv* env, const std::vector<uint8_t>& value) {
    jbyteArray out = env->NewByteArray(static_cast<jsize>(value.size()));
    assert(out);
    env->SetByteArrayRegion(out, 0, static_cast<jsize>(value.size()),
                            reinterpret_cast<const jbyte*>(value.data()));
    assert(!env->ExceptionCheck());
    return out;
}
static std::string consume(JNIEnv* env, jstring value) {
    if (!value) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    assert(chars);
    std::string out(chars);
    env->ReleaseStringUTFChars(value, chars);
    env->DeleteLocalRef(value);
    return out;
}
static std::vector<uint8_t> snapshot(JNIEnv* env) {
    jbyteArray out = Java_dev_plump_emulator_NativeDs_snapshot(env, nullptr);
    if (!out) return {};
    size_t size = env->GetArrayLength(out);
    std::vector<uint8_t> data(size);
    env->GetByteArrayRegion(out, 0, static_cast<jsize>(size),
                            reinterpret_cast<jbyte*>(data.data()));
    env->DeleteLocalRef(out);
    assert(!env->ExceptionCheck());
    return data;
}
#ifndef PLUMP_TEST_OLD_ADAPTER
static std::pair<std::vector<uint8_t>, std::vector<uint8_t>> checkpointCapture(JNIEnv* env) {
    jobjectArray result = Java_dev_plump_emulator_NativeDs_captureCheckpoint(env, nullptr);
    assert(result && env->GetArrayLength(result) == 2);
    std::vector<uint8_t> arrays[2];
    for (jsize index = 0; index < 2; ++index) {
        auto value = static_cast<jbyteArray>(env->GetObjectArrayElement(result, index));
        assert(value);
        const jsize length = env->GetArrayLength(value);
        arrays[index].resize(static_cast<size_t>(length));
        env->GetByteArrayRegion(value, 0, length, reinterpret_cast<jbyte*>(arrays[index].data()));
        env->DeleteLocalRef(value);
    }
    env->DeleteLocalRef(result);
    assert(!env->ExceptionCheck());
    return {std::move(arrays[0]), std::move(arrays[1])};
}
#endif
static void load(JNIEnv* env, const std::vector<uint8_t>& rom) {
    jbyteArray data = bytes(env, rom);
    std::string error = consume(env, Java_dev_plump_emulator_NativeDs_load(env, nullptr, data));
    env->DeleteLocalRef(data);
    if (!error.empty()) std::fprintf(stderr, "DS load failed: %s\n", error.c_str());
    assert(error.empty());
}
static void reject(JNIEnv* env, const std::vector<uint8_t>& rom) {
    jbyteArray data = bytes(env, rom);
    std::string error = consume(env, Java_dev_plump_emulator_NativeDs_load(env, nullptr, data));
    env->DeleteLocalRef(data);
    assert(!error.empty());
    assert(!Java_dev_plump_emulator_NativeDs_snapshot(env, nullptr));
    std::printf("Expected invalid load rejected: %s\n", error.c_str());
}
static void frames(JNIEnv* env, int count, bool sound) {
    const unsigned startTop = topPosts, startBottom = bottomPosts;
    if (sound) {
        Java_dev_plump_emulator_NativeDs_buttons(env, nullptr, (1 << 8) | 1);
        Java_dev_plump_emulator_NativeDs_touch(env, nullptr, true, 128, 96);
    }
    for (int n = 0; n < count; ++n) Java_dev_plump_emulator_NativeDs_frame(env, nullptr);
    Java_dev_plump_emulator_NativeDs_buttons(env, nullptr, 0);
    Java_dev_plump_emulator_NativeDs_touch(env, nullptr, false, 128, 96);
    assert(topPosts - startTop >= static_cast<unsigned>(count - 1));
    assert(bottomPosts - startBottom >= static_cast<unsigned>(count - 1));
    if (sound) {
        jshortArray output = env->NewShortArray(131072);
        assert(output);
        const jint count = Java_dev_plump_emulator_NativeDs_drain(env, nullptr, output);
        assert(count > 0);
        std::vector<jshort> pcm(count);
        env->GetShortArrayRegion(output, 0, count, pcm.data());
        env->DeleteLocalRef(output);
        assert(std::any_of(pcm.begin(), pcm.end(), [](jshort v) { return v != 0; }));
        std::printf("Real callback audio: %d samples, nonzero PCM\n", count);
    }
    std::printf("Real frame advancement: %d frames, both surfaces posted\n", count);
}

int main(int argc, char** argv) {
    JavaVM* vm = nullptr;
    JNIEnv* env = nullptr;
    // Keep the intentional baseline SIGSEGV report inside the ignored host
    // test directory, never in the public Git checkout root.
    std::string errorFile = std::string("-XX:ErrorFile=") +
                            (std::getenv("PLUMP_JVM_ERROR_FILE") ?
                             std::getenv("PLUMP_JVM_ERROR_FILE") : "/tmp/plump-host-hs_err_pid%p.log");
    const char* classPath = argc == 8 ? argv[5] : nullptr;
    std::string classPathOption = classPath ? std::string("-Djava.class.path=") + classPath : std::string();
    JavaVMOption vmOptions[] = {{errorFile.data(), nullptr}, {classPathOption.data(), nullptr}};
    JavaVMInitArgs vmArgs{JNI_VERSION_1_8, classPath ? 2 : 1, vmOptions, JNI_FALSE};
    assert(JNI_CreateJavaVM(&vm, reinterpret_cast<void**>(&env), &vmArgs) == JNI_OK);
    if (argc == 2 && std::strcmp(argv[1], "--missing-symbols") == 0) {
        jstring sys = env->NewStringUTF("/tmp/test-only-system");
        jstring saves = env->NewStringUTF("/tmp/test-only-saves");
        assert(consume(env, Java_dev_plump_emulator_NativeDs_initialize(env, nullptr, nullptr, saves)) ==
               "Private DS storage unavailable");
        assert(consume(env, Java_dev_plump_emulator_NativeDs_initialize(env, nullptr, sys, saves)) ==
               "Missing melonDS Libretro symbol");
        Java_dev_plump_emulator_NativeDs_shutdown(env, nullptr);
        assert(consume(env, Java_dev_plump_emulator_NativeDs_initialize(env, nullptr, sys, saves)) ==
               "Missing melonDS Libretro symbol");
        Java_dev_plump_emulator_NativeDs_shutdown(env, nullptr);
        env->DeleteLocalRef(sys); env->DeleteLocalRef(saves);
        assert(vm->DestroyJavaVM() == JNI_OK);
        std::puts("PASS: partial initialization rejects missing symbols, remains retryable");
        return 0;
    }
#ifndef PLUMP_TEST_OLD_ADAPTER
    if (argc == 2 && std::strcmp(argv[1], "--unsupported-serializer") == 0) {
        jstring sys = env->NewStringUTF("/tmp/test-only-system");
        jstring saves = env->NewStringUTF("/tmp/test-only-saves");
        assert(!consume(env, Java_dev_plump_emulator_NativeDs_initialize(env, nullptr, sys, saves)).size());
        assert(Java_dev_plump_emulator_NativeDs_serializerCapabilities(env, nullptr) == 0);
        assert(Java_dev_plump_emulator_NativeDs_serializerApiVersion(env, nullptr) == -1);
        load(env, std::vector<uint8_t>(512, 0));
        assert(Java_dev_plump_emulator_NativeDs_saveSize(env, nullptr) == 16);
        assert(!Java_dev_plump_emulator_NativeDs_captureCheckpoint(env, nullptr));
        Java_dev_plump_emulator_NativeDs_unload(env, nullptr);
        Java_dev_plump_emulator_NativeDs_shutdown(env, nullptr);
        env->DeleteLocalRef(sys);
        env->DeleteLocalRef(saves);
        assert(vm->DestroyJavaVM() == JNI_OK);
        std::puts("PASS melonDS unsupported serializer: frontend remains usable and capture is rejected");
        return 0;
    }
    if (argc == 2 && (std::strcmp(argv[1], "--mutating-serializer") == 0 ||
                      std::strcmp(argv[1], "--oversized-serializer") == 0)) {
        const bool oversized = std::strcmp(argv[1], "--oversized-serializer") == 0;
        jstring sys = env->NewStringUTF("/tmp/test-only-system");
        jstring saves = env->NewStringUTF("/tmp/test-only-saves");
        assert(!consume(env, Java_dev_plump_emulator_NativeDs_initialize(env, nullptr, sys, saves)).size());
        assert(Java_dev_plump_emulator_NativeDs_serializerCapabilities(env, nullptr) == 15);
        assert(Java_dev_plump_emulator_NativeDs_serializerApiVersion(env, nullptr) == 1);
        load(env, std::vector<uint8_t>(512, 0));
        const auto before = snapshot(env);
        assert(before.size() == 16);
        assert(!Java_dev_plump_emulator_NativeDs_captureCheckpoint(env, nullptr));
        assert(snapshot(env) == before);
        Java_dev_plump_emulator_NativeDs_unload(env, nullptr);
        Java_dev_plump_emulator_NativeDs_shutdown(env, nullptr);
        env->DeleteLocalRef(sys);
        env->DeleteLocalRef(saves);
        assert(vm->DestroyJavaVM() == JNI_OK);
        std::printf("PASS melonDS %s capture: rejected without changing native save RAM\n",
                    oversized ? "oversized" : "mutating-serializer");
        return 0;
    }
#endif
#ifdef PLUMP_TEST_OLD_ADAPTER
    assert(argc == 5 && "usage: HOST_TEST DS_INPUT_NDS DS_EEPROM_NDS EEPROM_BASELINE_8192 WORKDIR");
#else
    assert(argc == 8 && "usage: HOST_TEST DS_INPUT_NDS DS_EEPROM_NDS EEPROM_BASELINE_8192 WORKDIR PROBE_CLASSES ROM_SHA HOST_CORE_SHA");
#endif
    std::string root(argv[4]);
    const auto input = readFile(argv[1]);
    const auto eeprom = readFile(argv[2]);
    const auto saved = readFile(argv[3]);
    assert(input.size() > 512 && eeprom.size() > 512 && saved.size() == 8192);
    jstring sys = env->NewStringUTF((root + "/system").c_str());
    jstring saves = env->NewStringUTF((root + "/save").c_str());
    assert(!consume(env, Java_dev_plump_emulator_NativeDs_initialize(env, nullptr, sys, saves)).size());
    assert(consume(env, Java_dev_plump_emulator_NativeDs_initialize(env, nullptr, sys, saves)) ==
           "DS core is already initialized");
    jobject top = env->NewStringUTF("fake host top Surface");
    jobject bottom = env->NewStringUTF("fake host lower Surface");
    Java_dev_plump_emulator_NativeDs_topSurface(env, nullptr, top);
    Java_dev_plump_emulator_NativeDs_bottomSurface(env, nullptr, bottom);

#ifndef PLUMP_TEST_OLD_ADAPTER
    assert(Java_dev_plump_emulator_NativeDs_serializerCapabilities(env, nullptr) == 15);
    assert(Java_dev_plump_emulator_NativeDs_serializerApiVersion(env, nullptr) == 1);
    assert(!Java_dev_plump_emulator_NativeDs_captureCheckpoint(env, nullptr));
#endif
    load(env, input);                        // first game, callbacks and surfaces
    frames(env, 90, true);
    Java_dev_plump_emulator_NativeDs_unload(env, nullptr);
    Java_dev_plump_emulator_NativeDs_unload(env, nullptr); // idempotent
    assert(snapshot(env).empty());
    assert(buttonMask.load() == 0 && touchState.load() == 0);
    assert(lastTop != lastBottom && "DS upper/lower surfaces must show distinct real frames");
    jshortArray emptyPcm = env->NewShortArray(8192);
    assert(Java_dev_plump_emulator_NativeDs_drain(env, nullptr, emptyPcm) == 0);
    env->DeleteLocalRef(emptyPcm);

    load(env, eeprom);                       // same core, different ROM
    assert(Java_dev_plump_emulator_NativeDs_saveSize(env, nullptr) == 8192);
    jbyteArray saveArray = bytes(env, saved);
    assert(consume(env, Java_dev_plump_emulator_NativeDs_restore(env, nullptr, saveArray)).empty());
    env->DeleteLocalRef(saveArray);
    assert(snapshot(env) == saved);          // native restore before a single frame
#ifndef PLUMP_TEST_OLD_ADAPTER
    const auto nativeBeforeCapture = snapshot(env);
    const auto checkpointA = checkpointCapture(env);
    const auto checkpointB = checkpointCapture(env);
    assert(checkpointA.first.size() == 19228045);
    assert(checkpointA.first == checkpointB.first);
    assert(checkpointA.second == saved && checkpointA.second == checkpointB.second);
    assert(snapshot(env) == nativeBeforeCapture);
    std::printf("PASS JNI CHECKPOINT: melonDS API v1 state=%zu bytes native=%zu bytes stable twice; EEPROM unchanged\n",
                checkpointA.first.size(), checkpointA.second.size());
    const std::string checkpointRoot = root + "/real-owner-checkpoints";
    runOwnerCheckpointProbe(env, "runDs", checkpointRoot.c_str(), argv[6], argv[7]);
    assert(snapshot(env) == nativeBeforeCapture);
#endif
    frames(env, 90, false);                  // EEPROM diagnostic without pressing A
    assert(snapshot(env) == saved);
    Java_dev_plump_emulator_NativeDs_unload(env, nullptr);

    reject(env, std::vector<uint8_t>(511, 0)); // invalid length after unload
    load(env, input);                        // must survive invalid input
    frames(env, 55, true);                   // callbacks re-registered
    Java_dev_plump_emulator_NativeDs_unload(env, nullptr);

    reject(env, std::vector<uint8_t>(4096, 0)); // actual core content rejection
    load(env, eeprom);                       // must recover even after failed retro_load_game
    assert(Java_dev_plump_emulator_NativeDs_saveSize(env, nullptr) == 8192);
    saveArray = bytes(env, saved);
    assert(consume(env, Java_dev_plump_emulator_NativeDs_restore(env, nullptr, saveArray)).empty());
    env->DeleteLocalRef(saveArray);
    frames(env, 55, false);
    assert(snapshot(env) == saved);

    Java_dev_plump_emulator_NativeDs_unload(env, nullptr);
    Java_dev_plump_emulator_NativeDs_shutdown(env, nullptr);
    Java_dev_plump_emulator_NativeDs_shutdown(env, nullptr);
    assert(consume(env, Java_dev_plump_emulator_NativeDs_load(env, nullptr, nullptr)) == "DS core unavailable");
    assert(!consume(env, Java_dev_plump_emulator_NativeDs_initialize(env, nullptr, sys, saves)).size());
    Java_dev_plump_emulator_NativeDs_topSurface(env, nullptr, top);
    Java_dev_plump_emulator_NativeDs_bottomSurface(env, nullptr, bottom);
    load(env, input);
    frames(env, 55, true);
    Java_dev_plump_emulator_NativeDs_unload(env, nullptr);
    Java_dev_plump_emulator_NativeDs_shutdown(env, nullptr);
    env->DeleteLocalRef(sys); env->DeleteLocalRef(saves);
    env->DeleteLocalRef(top); env->DeleteLocalRef(bottom);
    assert(vm->DestroyJavaVM() == JNI_OK);
    std::puts("PASS: JNI adapter genuine DS multiple games, frames, input, audio, EEPROM, failures, reinit");
}
