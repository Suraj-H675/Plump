#pragma once

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct ANativeWindow ANativeWindow;
typedef struct ANativeWindow_Buffer {
    int32_t width;
    int32_t height;
    int32_t stride;
    int32_t format;
    void* bits;
    uint32_t reserved[6];
} ANativeWindow_Buffer;

#define WINDOW_FORMAT_RGBA_8888 1
void ANativeWindow_release(ANativeWindow* window);
int ANativeWindow_setBuffersGeometry(ANativeWindow* window, int width, int height, int format);
int ANativeWindow_lock(ANativeWindow* window, ANativeWindow_Buffer* out, void* dirty);
int ANativeWindow_unlockAndPost(ANativeWindow* window);

#ifdef __cplusplus
}
#endif
