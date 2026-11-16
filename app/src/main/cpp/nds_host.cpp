#include <jni.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include "nds_pixels.hpp"

#include <algorithm>
#include <array>
#include <atomic>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

// Narrow Libretro ABI adapter for pinned melonDS DS; no emulation code.
namespace {
struct GameInfo { const char* path; const void* data; size_t size; const char* meta; };
struct Geometry { unsigned baseWidth, baseHeight, maxWidth, maxHeight; float aspect; };
struct Timing { double fps, sampleRate; };
struct AvInfo { Geometry geometry; Timing timing; };
struct Variable { const char* key; const char* value; };
using Environment = bool (*)(unsigned, void*);
using Video = void (*)(const void*, unsigned, unsigned, size_t);
using Audio = void (*)(int16_t, int16_t);
using AudioBatch = size_t (*)(const int16_t*, size_t);
using Poll = void (*)();
using Input = int16_t (*)(unsigned, unsigned, unsigned, unsigned);
using SerializeSize = size_t (*)();
using Serialize = bool (*)(void*, size_t);
using Unserialize = bool (*)(const void*, size_t);
using ApiVersion = unsigned (*)();

struct Api {
    void* handle = nullptr;
    void (*setEnvironment)(Environment) = nullptr;
    void (*setVideo)(Video) = nullptr;
    void (*setAudio)(Audio) = nullptr;
    void (*setAudioBatch)(AudioBatch) = nullptr;
    void (*setPoll)(Poll) = nullptr;
    void (*setInput)(Input) = nullptr;
    void (*init)() = nullptr;
    void (*deinit)() = nullptr;
    bool (*load)(const GameInfo*) = nullptr;
    void (*unload)() = nullptr;
    void (*run)() = nullptr;
    void (*avInfo)(AvInfo*) = nullptr;
    void* (*memoryData)(unsigned) = nullptr;
    size_t (*memorySize)(unsigned) = nullptr;
    SerializeSize serializeSize = nullptr;
    Serialize serialize = nullptr;
    Unserialize unserialize = nullptr;
    ApiVersion apiVersion = nullptr;
};

Api api;
std::mutex coreMutex, surfaceMutex, audioMutex;
ANativeWindow* topWindow = nullptr;
ANativeWindow* lowerWindow = nullptr;
std::string systemDir, saveDir;
std::vector<uint8_t> rom;
bool loaded = false;
// This pinned melonDS revision retains renderer configuration after unloading
// its Console. Loading another ROM into that core dereferences the null Console
// in UpdateRenderer. A load attempt (even a rejected one) requires a fresh
// retro_deinit -> callback registration -> retro_init before the next load.
bool needsCoreReset = false;
std::atomic<uint32_t> buttonMask{0};
std::atomic<uint64_t> touchState{0};
std::atomic<uint64_t> videoFrames{0}, postedFrames{0}, rejectedFrames{0};
double fps = 59.8261, audioRate = 32768;

constexpr size_t AUDIO_CAPACITY = 131072;
std::array<int16_t, AUDIO_CAPACITY> audioBuffer{};
size_t audioRead = 0, audioWrite = 0, audioCount = 0;

void emptyAudio() {
    std::lock_guard<std::mutex> lock(audioMutex);
    audioRead = audioWrite = audioCount = 0;
}
void appendAudio(const int16_t* samples, size_t count) {
    if (!samples) return;
    std::lock_guard<std::mutex> lock(audioMutex);
    for (size_t i = 0; i < count; ++i) {
        if (audioCount == AUDIO_CAPACITY) {
            audioRead = (audioRead + 1) % AUDIO_CAPACITY;
            --audioCount;
        }
        audioBuffer[audioWrite] = samples[i];
        audioWrite = (audioWrite + 1) % AUDIO_CAPACITY;
        ++audioCount;
    }
}

// All runtime core options are controlled here, never by an online provider.
// Upstream's networking code is compiled for build compatibility, but DS
// networking is explicitly disabled and the APK has no INTERNET permission.
bool environment(unsigned command, void* data) {
    if (!data) return false;
    switch (command) {
        case 3:  // GET_CAN_DUPE
            *static_cast<bool*>(data) = false;
            return true;
        case 9:  // GET_SYSTEM_DIRECTORY
        case 30: // GET_CORE_ASSETS_DIRECTORY
            *static_cast<const char**>(data) = systemDir.c_str();
            return true;
        case 10: // SET_PIXEL_FORMAT: XRGB8888 is 1
            return *static_cast<int*>(data) == 1;
        case 15: { // GET_VARIABLE
            auto* option = static_cast<Variable*>(data);
            if (!option->key) return false;
            struct Entry { const char* name; const char* value; };
            static constexpr Entry forced[] = {
                {"melonds_network_mode", "disabled"},
                {"melonds_homebrew_sdcard", "disabled"},
                {"melonds_homebrew_sync_sdcard_to_host", "disabled"},
                {"melonds_console_mode", "ds"},
                {"melonds_boot_mode", "direct"},
                {"melonds_sysfile_mode", "builtin"},
                {"melonds_screen_layout1", "top-bottom"},
                {"melonds_number_of_screen_layouts", "1"},
                {"melonds_screen_gap", "0"},
                {"melonds_touch_mode", "pointer"},
                {"melonds_render_mode", "software"},
                {"melonds_show_cursor", "disabled"},
            };
            for (const auto& pair : forced) {
                if (std::strcmp(option->key, pair.name) == 0) {
                    option->value = pair.value;
                    return true;
                }
            }
            option->value = nullptr;
            return false;
        }
        case 17: // GET_VARIABLE_UPDATE
            *static_cast<bool*>(data) = false;
            return true;
        case 31: // GET_SAVE_DIRECTORY
            *static_cast<const char**>(data) = saveDir.c_str();
            return true;
        case 52: // GET_CORE_OPTIONS_VERSION; core options v2 are supported
            *static_cast<unsigned*>(data) = 2;
            return true;
        case 67: // SET_CORE_OPTIONS_V2: definitions are registered by the core
        case 16: // SET_VARIABLES: legacy fallback
        case 11: // SET_INPUT_DESCRIPTORS
            return true;
        default:
            return false;
    }
}

void video(const void* pixels, unsigned width, unsigned height, size_t pitch) {
    ++videoFrames;
    if (!pixels || width != 256 || height != 384 || pitch < 1024) {
        ++rejectedFrames;
        return;
    }
    std::lock_guard<std::mutex> lock(surfaceMutex);
    if (!topWindow || !lowerWindow) { ++rejectedFrames; return; }
    ANativeWindow_Buffer top{}, lower{};
    if (ANativeWindow_lock(topWindow, &top, nullptr) != 0) { ++rejectedFrames; return; }
    if (ANativeWindow_lock(lowerWindow, &lower, nullptr) != 0) {
        ANativeWindow_unlockAndPost(topWindow);
        ++rejectedFrames;
        return;
    }
    const bool okay = top.format == WINDOW_FORMAT_RGBA_8888 &&
        lower.format == WINDOW_FORMAT_RGBA_8888 && top.width >= 256 && top.height >= 192 &&
        lower.width >= 256 && lower.height >= 192 && top.bits && lower.bits &&
        splitNdsFrame(pixels, width, height, pitch, static_cast<uint8_t*>(top.bits),
                      top.stride, static_cast<uint8_t*>(lower.bits), lower.stride);
    const int topResult = ANativeWindow_unlockAndPost(topWindow);
    const int lowerResult = ANativeWindow_unlockAndPost(lowerWindow);
    if (okay && topResult == 0 && lowerResult == 0) ++postedFrames;
    else ++rejectedFrames;
}
void audioSample(int16_t left, int16_t right) {
    const int16_t pair[] = {left, right};
    appendAudio(pair, 2);
}
size_t audioBatch(const int16_t* data, size_t count) {
    appendAudio(data, count * 2);
    return count;
}
void inputPoll() {}
int16_t input(unsigned port, unsigned device, unsigned index, unsigned id) {
    if (port != 0 || index != 0) return 0;
    if (device == 1) {
        if (id >= 32) return 0;
        return (buttonMask.load(std::memory_order_relaxed) >> id) & 1;
    }
    if (device == 6) {
        const uint64_t touch = touchState.load(std::memory_order_relaxed);
        if (id == 0) return static_cast<int16_t>((touch >> 1) & 0xFFFF);
        if (id == 1) return static_cast<int16_t>((touch >> 17) & 0xFFFF);
        if (id == 2) return static_cast<int16_t>(touch & 1);
    }
    return 0;
}

void registerCallbacks() {
    api.setEnvironment(environment);
    api.setVideo(video);
    api.setAudio(audioSample);
    api.setAudioBatch(audioBatch);
    api.setPoll(inputPoll);
    api.setInput(input);
}

// Called only under coreMutex with no running emulator/audio workers. Keep the
// core loaded in the same dynamic library; retro_deinit reconstructs the
// internal state, and the pinned core discards callbacks during deinit.
void resetBeforeNextLoad() {
    if (!needsCoreReset) return;
    api.deinit();
    registerCallbacks();
    api.init();
    needsCoreReset = false;
}

template <typename Function>
bool symbol(Function& output, const char* name) {
    output = reinterpret_cast<Function>(dlsym(api.handle, name));
    return output != nullptr;
}
jstring problem(JNIEnv* env, const std::string& message) { return env->NewStringUTF(message.c_str()); }
void unload() {
    if (loaded) api.unload();
    loaded = false;
    rom.clear();
    buttonMask = 0;
    touchState = 0;
    emptyAudio();
    videoFrames = postedFrames = rejectedFrames = 0;
}
void replaceWindow(JNIEnv* env, jobject surface, ANativeWindow*& target) {
    std::lock_guard<std::mutex> lock(surfaceMutex);
    if (target) { ANativeWindow_release(target); target = nullptr; }
    if (surface) {
        target = ANativeWindow_fromSurface(env, surface);
        if (target) {
            int result = ANativeWindow_setBuffersGeometry(target, 256, 192, WINDOW_FORMAT_RGBA_8888);
            if (result != 0) __android_log_print(ANDROID_LOG_ERROR, "PlumpDS",
                                                   "DS surface setup failed: %d", result);
        }
    }
}
} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_dev_plump_emulator_NativeDs_initialize(JNIEnv* env, jclass, jstring system, jstring save) {
    std::lock_guard<std::mutex> lock(coreMutex);
    if (api.handle) return problem(env, "DS core is already initialized");
    if (!system || !save) return problem(env, "Private DS storage unavailable");
    const char* sys = env->GetStringUTFChars(system, nullptr);
    if (!sys) return nullptr; // JNI has a pending allocation exception.
    const char* sav = env->GetStringUTFChars(save, nullptr);
    if (!sav) {
        env->ReleaseStringUTFChars(system, sys);
        return nullptr;
    }
    systemDir.assign(sys);
    saveDir.assign(sav);
    env->ReleaseStringUTFChars(system, sys);
    env->ReleaseStringUTFChars(save, sav);
    api.handle = dlopen("libmelondsds_libretro.so", RTLD_NOW | RTLD_LOCAL);
    if (!api.handle) {
        const char* reason = dlerror();
        return problem(env, reason ? reason : "melonDS DS core unavailable");
    }
    const bool okay = symbol(api.setEnvironment, "retro_set_environment") &&
        symbol(api.setVideo, "retro_set_video_refresh") &&
        symbol(api.setAudio, "retro_set_audio_sample") &&
        symbol(api.setAudioBatch, "retro_set_audio_sample_batch") &&
        symbol(api.setPoll, "retro_set_input_poll") &&
        symbol(api.setInput, "retro_set_input_state") &&
        symbol(api.init, "retro_init") && symbol(api.deinit, "retro_deinit") &&
        symbol(api.load, "retro_load_game") && symbol(api.unload, "retro_unload_game") &&
        symbol(api.run, "retro_run") && symbol(api.avInfo, "retro_get_system_av_info") &&
        symbol(api.memoryData, "retro_get_memory_data") &&
        symbol(api.memorySize, "retro_get_memory_size");
    if (!okay) { dlclose(api.handle); api = {}; return problem(env, "Missing melonDS Libretro symbol"); }
    symbol(api.serializeSize, "retro_serialize_size");
    symbol(api.serialize, "retro_serialize");
    symbol(api.unserialize, "retro_unserialize");
    symbol(api.apiVersion, "retro_api_version");
    registerCallbacks();
    api.init();
    needsCoreReset = false;
    return nullptr;
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_plump_emulator_NativeDs_load(JNIEnv* env, jclass, jbyteArray bytes) {
    std::lock_guard<std::mutex> lock(coreMutex);
    if (!api.handle || !bytes) return problem(env, "DS core unavailable");
    unload();
    const jsize size = env->GetArrayLength(bytes);
    if (size < 512 || size > 64 * 1024 * 1024) return problem(env, "Invalid DS cartridge size");
    rom.resize(size);
    env->GetByteArrayRegion(bytes, 0, size, reinterpret_cast<jbyte*>(rom.data()));
    if (env->ExceptionCheck()) { rom.clear(); return problem(env, "Could not read DS ROM"); }
    // Reset *after* validating/copying input so an invalid picker result does
    // not needlessly cycle the core. Do not reuse the prior renderer state.
    resetBeforeNextLoad();
    const GameInfo content{"plump-local.nds", rom.data(), rom.size(), nullptr};
    needsCoreReset = true; // A failed load may also leave partial core state.
    if (!api.load(&content)) {
        api.unload(); // Clean up any partially loaded core tasks/console.
        unload();
        return problem(env, "melonDS rejected DS ROM");
    }
    loaded = true;
    AvInfo av{};
    api.avInfo(&av);
    fps = av.timing.fps > 1 && av.timing.fps < 200 ? av.timing.fps : 59.8261;
    audioRate = av.timing.sampleRate >= 8000 && av.timing.sampleRate <= 192000 ? av.timing.sampleRate : 32768;
    __android_log_print(ANDROID_LOG_INFO, "PlumpDS", "DS loaded: geometry %ux%u, fps %.3f",
                        av.geometry.baseWidth, av.geometry.baseHeight, fps);
    return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_plump_emulator_NativeDs_frame(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lock(coreMutex);
    if (loaded) api.run();
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_dev_plump_emulator_NativeDs_snapshot(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> lock(coreMutex);
    if (!loaded) return nullptr;
    size_t size = api.memorySize(0);
    void* data = api.memoryData(0);
    if (!data || size == 0 || size > 1024 * 1024) return nullptr;
    jbyteArray result = env->NewByteArray(static_cast<jsize>(size));
    if (result) env->SetByteArrayRegion(result, 0, static_cast<jsize>(size), static_cast<jbyte*>(data));
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_plump_emulator_NativeDs_serializerCapabilities(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lock(coreMutex);
    return (api.serializeSize ? 1 : 0) | (api.serialize ? 2 : 0) |
           (api.unserialize ? 4 : 0) | (api.apiVersion ? 8 : 0);
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_plump_emulator_NativeDs_serializerApiVersion(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lock(coreMutex);
    return api.apiVersion ? static_cast<jint>(api.apiVersion()) : -1;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_dev_plump_emulator_NativeDs_captureCheckpoint(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> lock(coreMutex);
    constexpr size_t MAX_MACHINE_BYTES = 64 * 1024 * 1024;
    constexpr size_t MAX_NATIVE_BYTES = 1024 * 1024;
    if (!loaded || !api.serializeSize || !api.serialize || !api.unserialize ||
            !api.apiVersion || api.apiVersion() != 1) return nullptr;

    const size_t stateSize = api.serializeSize();
    const size_t nativeSize = api.memorySize(0);
    void* nativeData = api.memoryData(0);
    if (stateSize == 0 || stateSize > MAX_MACHINE_BYTES ||
            nativeSize == 0 || nativeSize > MAX_NATIVE_BYTES || !nativeData) return nullptr;

    try {
        std::vector<uint8_t> state(stateSize);
        std::vector<uint8_t> nativeSave(nativeSize);
        std::memcpy(nativeSave.data(), nativeData, nativeSize);
        if (!api.serialize(state.data(), state.size())) return nullptr;

        const size_t afterSize = api.memorySize(0);
        void* afterData = api.memoryData(0);
        if (!afterData || afterSize != nativeSize ||
                std::memcmp(afterData, nativeSave.data(), nativeSize) != 0) {
            if (afterData && afterSize == nativeSize)
                std::memcpy(afterData, nativeSave.data(), nativeSize);
            return nullptr;
        }

        jbyteArray stateArray = env->NewByteArray(static_cast<jsize>(stateSize));
        if (!stateArray) return nullptr;
        env->SetByteArrayRegion(stateArray, 0, static_cast<jsize>(stateSize),
                                reinterpret_cast<const jbyte*>(state.data()));
        if (env->ExceptionCheck()) { env->DeleteLocalRef(stateArray); return nullptr; }
        jbyteArray nativeArray = env->NewByteArray(static_cast<jsize>(nativeSize));
        if (!nativeArray) { env->DeleteLocalRef(stateArray); return nullptr; }
        env->SetByteArrayRegion(nativeArray, 0, static_cast<jsize>(nativeSize),
                                reinterpret_cast<const jbyte*>(nativeSave.data()));
        if (env->ExceptionCheck()) {
            env->DeleteLocalRef(stateArray);
            env->DeleteLocalRef(nativeArray);
            return nullptr;
        }
        jclass byteArrayClass = env->FindClass("[B");
        if (!byteArrayClass) {
            env->DeleteLocalRef(stateArray);
            env->DeleteLocalRef(nativeArray);
            return nullptr;
        }
        jobjectArray result = env->NewObjectArray(2, byteArrayClass, nullptr);
        env->DeleteLocalRef(byteArrayClass);
        if (result) {
            env->SetObjectArrayElement(result, 0, stateArray);
            env->SetObjectArrayElement(result, 1, nativeArray);
        }
        env->DeleteLocalRef(stateArray);
        env->DeleteLocalRef(nativeArray);
        return env->ExceptionCheck() ? nullptr : result;
    } catch (const std::bad_alloc&) {
        return nullptr;
    }
}
extern "C" JNIEXPORT jint JNICALL
Java_dev_plump_emulator_NativeDs_saveSize(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lock(coreMutex);
    if (!loaded) return 0;
    const size_t size = api.memorySize(0);
    return size > static_cast<size_t>(INT32_MAX) ? -1 : static_cast<jint>(size);
}
extern "C" JNIEXPORT jstring JNICALL
Java_dev_plump_emulator_NativeDs_restore(JNIEnv* env, jclass, jbyteArray bytes) {
    std::lock_guard<std::mutex> lock(coreMutex);
    if (!loaded || !bytes) return problem(env, "No loaded cartridge save");
    size_t capacity = api.memorySize(0);
    void* data = api.memoryData(0);
    if (!data || capacity == 0 || static_cast<size_t>(env->GetArrayLength(bytes)) != capacity)
        return problem(env, "Incompatible DS cartridge save size");
    env->GetByteArrayRegion(bytes, 0, static_cast<jsize>(capacity), static_cast<jbyte*>(data));
    return env->ExceptionCheck() ? problem(env, "Cannot restore DS cartridge data") : nullptr;
}
extern "C" JNIEXPORT void JNICALL
Java_dev_plump_emulator_NativeDs_buttons(JNIEnv*, jclass, jint mask) { buttonMask = static_cast<uint32_t>(mask); }
extern "C" JNIEXPORT void JNICALL
Java_dev_plump_emulator_NativeDs_touch(JNIEnv*, jclass, jboolean down, jint x, jint y) {
    // Libretro pointer is defined against the combined TOP+BOTTOM 256x384 frame.
    const auto px = static_cast<uint16_t>(ndsPointerCoordinate(x, 256));
    const auto py = static_cast<uint16_t>(ndsPointerCoordinate(y + 192, 384));
    touchState = (static_cast<uint64_t>(py) << 17) | (static_cast<uint64_t>(px) << 1) |
                 (down ? 1u : 0u);
}
extern "C" JNIEXPORT void JNICALL
Java_dev_plump_emulator_NativeDs_topSurface(JNIEnv* env, jclass, jobject surface) {
    replaceWindow(env, surface, topWindow);
}
extern "C" JNIEXPORT void JNICALL
Java_dev_plump_emulator_NativeDs_bottomSurface(JNIEnv* env, jclass, jobject surface) {
    replaceWindow(env, surface, lowerWindow);
}
extern "C" JNIEXPORT jint JNICALL
Java_dev_plump_emulator_NativeDs_drain(JNIEnv* env, jclass, jshortArray output) {
    if (!output) return 0;
    const size_t cap = env->GetArrayLength(output);
    std::vector<int16_t> samples;
    {
        std::lock_guard<std::mutex> lock(audioMutex);
        const size_t size = std::min(cap, audioCount) & ~static_cast<size_t>(1);
        samples.reserve(size);
        for (size_t i = 0; i < size; ++i) {
            samples.push_back(audioBuffer[audioRead]);
            audioRead = (audioRead + 1) % AUDIO_CAPACITY;
        }
        audioCount -= size;
    }
    if (!samples.empty()) env->SetShortArrayRegion(output, 0, samples.size(), samples.data());
    return samples.size();
}
extern "C" JNIEXPORT void JNICALL
Java_dev_plump_emulator_NativeDs_clearAudio(JNIEnv*, jclass) { emptyAudio(); }
extern "C" JNIEXPORT jdouble JNICALL
Java_dev_plump_emulator_NativeDs_rate(JNIEnv*, jclass) { return fps; }
extern "C" JNIEXPORT jint JNICALL
Java_dev_plump_emulator_NativeDs_audioRate(JNIEnv*, jclass) { return audioRate; }
extern "C" JNIEXPORT jstring JNICALL
Java_dev_plump_emulator_NativeDs_stats(JNIEnv* env, jclass) {
    return env->NewStringUTF(("frames=" + std::to_string(videoFrames.load()) +
        " posted=" + std::to_string(postedFrames.load()) +
        " rejected=" + std::to_string(rejectedFrames.load())).c_str());
}
extern "C" JNIEXPORT void JNICALL
Java_dev_plump_emulator_NativeDs_unload(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lock(coreMutex);
    if (api.handle) unload();
}
extern "C" JNIEXPORT void JNICALL
Java_dev_plump_emulator_NativeDs_shutdown(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lock(coreMutex);
    if (api.handle) {
        unload();
        api.deinit();
        dlclose(api.handle);
        api = {};
        needsCoreReset = false;
    }
    std::lock_guard<std::mutex> surfaces(surfaceMutex);
    if (topWindow) { ANativeWindow_release(topWindow); topWindow = nullptr; }
    if (lowerWindow) { ANativeWindow_release(lowerWindow); lowerWindow = nullptr; }
}
