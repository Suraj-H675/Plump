#include <jni.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include "rgb565_blit.hpp"

#include <algorithm>
#include <array>
#include <atomic>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

// The Libretro ABI is defined by libretro.h (https://github.com/libretro/libretro-common).
// Only its public ABI declarations needed by this narrow frontend are repeated here.
namespace {
struct GameInfo { const char* path; const void* data; size_t size; const char* meta; };
struct Geometry { unsigned baseWidth, baseHeight, maxWidth, maxHeight; float aspect; };
struct Timing { double fps, sampleRate; };
struct AvInfo { Geometry geometry; Timing timing; };
using Env = bool (*)(unsigned, void*);
using Video = void (*)(const void*, unsigned, unsigned, size_t);
using Audio = void (*)(int16_t, int16_t);
using AudioBatch = size_t (*)(const int16_t*, size_t);
using Poll = void (*)();
using Input = int16_t (*)(unsigned, unsigned, unsigned, unsigned);

struct CoreApi {
    void* handle = nullptr;
    void (*setEnvironment)(Env) = nullptr;
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
    void (*reset)() = nullptr;
    void (*avInfo)(AvInfo*) = nullptr;
    void* (*memoryData)(unsigned) = nullptr;
    size_t (*memorySize)(unsigned) = nullptr;
};

CoreApi core;
std::mutex coreMutex;
std::mutex windowMutex;
std::mutex audioMutex;
ANativeWindow* window = nullptr;
std::vector<uint8_t> romBytes;
std::atomic<uint32_t> heldButtons{0};
bool gameLoaded = false;
double targetFps = 59.7275;
double sampleRate = 32768.0;
std::atomic<uint64_t> receivedVideo{0};
std::atomic<uint64_t> submittedVideo{0};
std::atomic<uint64_t> failedLocks{0};
std::atomic<uint64_t> rejectedBuffers{0};
std::atomic<int32_t> lastBufferFormat{0};
std::atomic<int32_t> lastWindowError{0};
std::atomic<int32_t> lastBufferWidth{0};
std::atomic<int32_t> lastBufferHeight{0};
std::atomic<uint32_t> lastNonBlackPixels{0};

// Single-producer (core callback), single-consumer (AudioTrack thread), guarded ring.
constexpr size_t AUDIO_CAPACITY = 131072;
std::array<int16_t, AUDIO_CAPACITY> audioRing{};
size_t audioHead = 0, audioTail = 0, audioCount = 0;

void clearAudio() {
    std::lock_guard<std::mutex> lock(audioMutex);
    audioHead = audioTail = audioCount = 0;
}

void pushAudio(const int16_t* samples, size_t n) {
    std::lock_guard<std::mutex> lock(audioMutex);
    for (size_t i = 0; i < n; ++i) {
        if (audioCount == AUDIO_CAPACITY) {
            audioHead = (audioHead + 1) % AUDIO_CAPACITY;
            --audioCount;
        }
        audioRing[audioTail] = samples[i];
        audioTail = (audioTail + 1) % AUDIO_CAPACITY;
        ++audioCount;
    }
}

bool environment(unsigned command, void* data) {
    constexpr unsigned SET_PIXEL_FORMAT = 10;
    constexpr unsigned GET_CAN_DUPE = 3;
    constexpr int RGB565 = 2;
    if (command == SET_PIXEL_FORMAT && data) return *static_cast<int*>(data) == RGB565;
    if (command == GET_CAN_DUPE && data) {
        *static_cast<bool*>(data) = false;
        return true;
    }
    return false; // Unsupported optional frontend extension, not silently emulated.
}

void video(const void* pixels, unsigned w, unsigned h, size_t pitch) {
    const uint64_t frame = ++receivedVideo;
    if (!pixels || w != 240 || h != 160 || pitch < w * 2) {
        ++rejectedBuffers;
        return;
    }
    if (frame % 180 == 0) {
        // Read the real upstream pixels independently of the window path.
        // A nonzero count separates a black core output from failed posting.
        uint32_t nonBlack = 0;
        const auto* bytes = static_cast<const uint8_t*>(pixels);
        for (unsigned y = 0; y < h; ++y) {
            const auto* row = bytes + static_cast<size_t>(y) * pitch;
            for (unsigned x = 0; x < w; ++x)
                nonBlack += (row[x * 2] | row[x * 2 + 1]) != 0;
        }
        lastNonBlackPixels.store(nonBlack);
    }
    std::lock_guard<std::mutex> lock(windowMutex);
    if (!window) {
        ++rejectedBuffers;
        return;
    }
    ANativeWindow_Buffer buffer{};
    int32_t lockResult = ANativeWindow_lock(window, &buffer, nullptr);
    if (lockResult != 0) {
        lastWindowError.store(lockResult);
        ++failedLocks;
        return;
    }
    lastBufferFormat.store(buffer.format);
    lastBufferWidth.store(buffer.width);
    lastBufferHeight.store(buffer.height);
    bool populated = false;
    if (buffer.format == WINDOW_FORMAT_RGBA_8888 && buffer.width >= static_cast<int>(w) &&
        buffer.height >= static_cast<int>(h) && buffer.bits) {
        blitRgb565ToRgba8888(pixels, pitch, static_cast<uint8_t*>(buffer.bits),
                            static_cast<size_t>(buffer.stride), w, h);
        populated = true;
    } else {
        ++rejectedBuffers;
    }
    const int32_t postResult = ANativeWindow_unlockAndPost(window);
    if (postResult == 0 && populated) ++submittedVideo;
    else if (postResult != 0) {
        ++failedLocks;
        lastWindowError.store(postResult);
    }
}

void audioSample(int16_t left, int16_t right) {
    const int16_t pair[] = {left, right};
    pushAudio(pair, 2);
}
size_t audioBatch(const int16_t* data, size_t frames) {
    if (data && frames) pushAudio(data, frames * 2);
    return frames;
}
void poll() {}
int16_t input(unsigned port, unsigned device, unsigned /*index*/, unsigned id) {
    if (port || device != 1 || id >= 32) return 0;
    return static_cast<int16_t>((heldButtons.load(std::memory_order_relaxed) >> id) & 1);
}

template <typename T>
bool resolve(T& target, const char* name) {
    target = reinterpret_cast<T>(dlsym(core.handle, name));
    return target != nullptr;
}

jstring error(JNIEnv* env, const std::string& message) {
    return env->NewStringUTF(message.c_str());
}

void releaseGame() {
    if (gameLoaded) core.unload();
    gameLoaded = false;
    romBytes.clear();
    heldButtons.store(0);
    clearAudio();
    receivedVideo = 0;
    submittedVideo = 0;
    failedLocks = 0;
    rejectedBuffers = 0;
    lastWindowError = 0;
    lastBufferFormat = 0;
    lastNonBlackPixels = 0;
}
} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_dev_plump_emulator_NativeCore_initialize(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> lock(coreMutex);
    if (core.handle) return nullptr;
    core.handle = dlopen("libmgba_libretro.so", RTLD_NOW | RTLD_LOCAL);
    if (!core.handle) {
        const char* detail = dlerror();
        return error(env, detail ? detail : "mGBA library cannot be loaded");
    }
    bool ok = resolve(core.setEnvironment, "retro_set_environment") &&
              resolve(core.setVideo, "retro_set_video_refresh") &&
              resolve(core.setAudio, "retro_set_audio_sample") &&
              resolve(core.setAudioBatch, "retro_set_audio_sample_batch") &&
              resolve(core.setPoll, "retro_set_input_poll") &&
              resolve(core.setInput, "retro_set_input_state") &&
              resolve(core.init, "retro_init") && resolve(core.deinit, "retro_deinit") &&
              resolve(core.load, "retro_load_game") && resolve(core.unload, "retro_unload_game") &&
              resolve(core.run, "retro_run") && resolve(core.reset, "retro_reset") &&
              resolve(core.avInfo, "retro_get_system_av_info") &&
              resolve(core.memoryData, "retro_get_memory_data") &&
              resolve(core.memorySize, "retro_get_memory_size");
    if (!ok) {
        dlclose(core.handle);
        core = {};
        return error(env, "Missing required Libretro API symbol");
    }
    core.setEnvironment(environment);
    core.setVideo(video);
    core.setAudio(audioSample);
    core.setAudioBatch(audioBatch);
    core.setPoll(poll);
    core.setInput(input);
    core.init();
    return nullptr;
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_plump_emulator_NativeCore_loadGame(JNIEnv* env, jclass, jbyteArray bytes) {
    std::lock_guard<std::mutex> lock(coreMutex);
    if (!core.handle || !bytes) return error(env, "Core not initialized or ROM missing");
    releaseGame();
    const jsize count = env->GetArrayLength(bytes);
    if (count < 192 || count > 32 * 1024 * 1024) return error(env, "Invalid ROM size");
    romBytes.resize(static_cast<size_t>(count));
    env->GetByteArrayRegion(bytes, 0, count, reinterpret_cast<jbyte*>(romBytes.data()));
    if (env->ExceptionCheck()) { romBytes.clear(); return error(env, "Unable to read ROM"); }
    const GameInfo game{nullptr, romBytes.data(), romBytes.size(), nullptr};
    if (!core.load(&game)) { romBytes.clear(); return error(env, "mGBA rejected the ROM"); }
    AvInfo av{};
    core.avInfo(&av);
    targetFps = av.timing.fps > 1 && av.timing.fps < 200 ? av.timing.fps : 59.7275;
    sampleRate = av.timing.sampleRate > 8000 && av.timing.sampleRate < 192000 ? av.timing.sampleRate : 32768.0;
    gameLoaded = true;
    return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_plump_emulator_NativeCore_runFrame(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lock(coreMutex);
    if (gameLoaded) core.run();
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_dev_plump_emulator_NativeCore_snapshotSaveRam(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> lock(coreMutex);
    if (!gameLoaded) return nullptr;
    size_t length = core.memorySize(0);
    void* ptr = core.memoryData(0);
    if (!ptr || length == 0 || length > 1024 * 1024) return nullptr;
    auto result = env->NewByteArray(static_cast<jsize>(length));
    if (result) env->SetByteArrayRegion(result, 0, static_cast<jsize>(length), static_cast<jbyte*>(ptr));
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_plump_emulator_NativeCore_restoreSaveRam(JNIEnv* env, jclass, jbyteArray data) {
    std::lock_guard<std::mutex> lock(coreMutex);
    if (!gameLoaded || !data) return error(env, "No loaded core or save");
    const jsize size = env->GetArrayLength(data);
    const size_t capacity = core.memorySize(0);
    void* ptr = core.memoryData(0);
    if (!ptr || size <= 0 || static_cast<size_t>(size) > capacity)
        return error(env, "Save RAM size is not compatible with this core");
    env->GetByteArrayRegion(data, 0, size, static_cast<jbyte*>(ptr));
    if (env->ExceptionCheck()) return error(env, "Unable to copy save RAM");
    return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_plump_emulator_NativeCore_setButtons(JNIEnv*, jclass, jint mask) {
    heldButtons.store(static_cast<uint32_t>(mask), std::memory_order_release);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_plump_emulator_NativeCore_setSurface(JNIEnv* env, jclass, jobject surface) {
    std::lock_guard<std::mutex> lock(windowMutex);
    if (window) { ANativeWindow_release(window); window = nullptr; }
    if (surface) {
        window = ANativeWindow_fromSurface(env, surface);
        if (window) {
            int32_t result = ANativeWindow_setBuffersGeometry(window, 240, 160, WINDOW_FORMAT_RGBA_8888);
            lastWindowError.store(result);
            if (result != 0) __android_log_print(ANDROID_LOG_ERROR, "Plump",
                 "RGBA8888 native window configuration failed: %d", result);
        }
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_plump_emulator_NativeCore_videoDiagnostics(JNIEnv* env, jclass) {
    std::string report = "frames=" + std::to_string(receivedVideo.load()) +
        " posted=" + std::to_string(submittedVideo.load()) +
        " nonBlack=" + std::to_string(lastNonBlackPixels.load()) +
        " lockFail=" + std::to_string(failedLocks.load()) +
        " reject=" + std::to_string(rejectedBuffers.load()) +
        " fmt=" + std::to_string(lastBufferFormat.load()) +
        " size=" + std::to_string(lastBufferWidth.load()) + "x" +
        std::to_string(lastBufferHeight.load()) +
        " err=" + std::to_string(lastWindowError.load());
    return env->NewStringUTF(report.c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_plump_emulator_NativeCore_drainAudio(JNIEnv* env, jclass, jshortArray samples) {
    if (!samples) return 0;
    const size_t max = static_cast<size_t>(env->GetArrayLength(samples));
    std::vector<int16_t> copied;
    {
        std::lock_guard<std::mutex> lock(audioMutex);
        size_t n = std::min(audioCount, max) & ~static_cast<size_t>(1);
        copied.reserve(n);
        for (size_t i = 0; i < n; ++i) {
            copied.push_back(audioRing[audioHead]);
            audioHead = (audioHead + 1) % AUDIO_CAPACITY;
        }
        audioCount -= n;
    }
    if (!copied.empty()) env->SetShortArrayRegion(samples, 0, static_cast<jsize>(copied.size()), copied.data());
    return static_cast<jint>(copied.size());
}

extern "C" JNIEXPORT void JNICALL
Java_dev_plump_emulator_NativeCore_discardAudio(JNIEnv*, jclass) {
    clearAudio(); // Prevent stale audio from replaying after pause/background.
}

extern "C" JNIEXPORT jdouble JNICALL
Java_dev_plump_emulator_NativeCore_frameRate(JNIEnv*, jclass) { return targetFps; }
extern "C" JNIEXPORT jint JNICALL
Java_dev_plump_emulator_NativeCore_audioRate(JNIEnv*, jclass) { return static_cast<jint>(sampleRate); }

extern "C" JNIEXPORT void JNICALL
Java_dev_plump_emulator_NativeCore_unloadGame(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lock(coreMutex);
    if (core.handle) releaseGame();
}

extern "C" JNIEXPORT void JNICALL
Java_dev_plump_emulator_NativeCore_shutdown(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lock(coreMutex);
    if (core.handle) {
        releaseGame();
        core.deinit();
        dlclose(core.handle);
        core = {};
    }
    std::lock_guard<std::mutex> surfaceLock(windowMutex);
    if (window) { ANativeWindow_release(window); window = nullptr; }
}
