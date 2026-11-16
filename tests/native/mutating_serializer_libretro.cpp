#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <cstring>

namespace {
struct GameInfo { const char* path; const void* data; size_t size; const char* meta; };
struct Geometry { unsigned width, height, maxWidth, maxHeight; float aspect; };
struct Timing { double fps, sampleRate; };
struct AvInfo { Geometry geometry; Timing timing; };
using Env = bool (*)(unsigned, void*);
using Video = void (*)(const void*, unsigned, unsigned, size_t);
using Audio = void (*)(int16_t, int16_t);
using AudioBatch = size_t (*)(const int16_t*, size_t);
using Poll = void (*)();
using Input = int16_t (*)(unsigned, unsigned, unsigned, unsigned);
uint8_t saveRam[16]{};
}

extern "C" void retro_set_environment(Env) { }
extern "C" void retro_set_video_refresh(Video) { }
extern "C" void retro_set_audio_sample(Audio) { }
extern "C" void retro_set_audio_sample_batch(AudioBatch) { }
extern "C" void retro_set_input_poll(Poll) { }
extern "C" void retro_set_input_state(Input) { }
extern "C" void retro_init() { }
extern "C" void retro_deinit() { }
extern "C" bool retro_load_game(const GameInfo*) { return true; }
extern "C" void retro_unload_game() { }
extern "C" void retro_run() { }
extern "C" void retro_reset() { }
extern "C" unsigned retro_api_version() { return 1; }
extern "C" void retro_get_system_av_info(AvInfo* info) {
    info->geometry = {256, 384, 256, 384, 2.0f / 3.0f};
    info->timing = {60.0, 32768.0};
}
extern "C" void* retro_get_memory_data(unsigned type) { return type == 0 ? saveRam : nullptr; }
extern "C" size_t retro_get_memory_size(unsigned type) { return type == 0 ? sizeof(saveRam) : 0; }
extern "C" size_t retro_serialize_size() {
    return std::getenv("PLUMP_TEST_OVERSIZED_STATE") ? 64 * 1024 * 1024 + 1 : 32;
}
extern "C" bool retro_serialize(void* output, size_t size) {
    if (!output || size != 32) return false;
    saveRam[0] ^= 0x7f;
    std::memset(output, 0x5a, size);
    return true;
}
extern "C" bool retro_unserialize(const void*, size_t) { return true; }
