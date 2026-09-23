#define GL_SILENCE_DEPRECATION
#include <OpenGL/gl3.h>
#include <SDL3/SDL.h>

#include "include/native_gl.h"

namespace {

struct KglEventWatch {
    kgl_event_watch_callback callback = nullptr;
    void *userdata = nullptr;
};

struct KglLayer {
    GLuint framebuffer = 0;
    GLuint texture = 0;
    int width = 0;
    int height = 0;
    GLint previous_framebuffer = 0;
    GLint previous_viewport[4] = {0, 0, 1, 1};
    GLint previous_active_texture = GL_TEXTURE0;
    GLint previous_texture = 0;
    bool prepared = false;
};

bool SDLCALL dispatch_event_watch(void *raw_watch, SDL_Event *event) {
    auto *watch = static_cast<KglEventWatch *>(raw_watch);
    if (!watch || !watch->callback || !event) return true;
    return watch->callback(watch->userdata, event) != 0;
}

void restore_gl_state(KglLayer *layer) {
    if (!layer || !layer->prepared) return;
    glBindFramebuffer(GL_FRAMEBUFFER, static_cast<GLuint>(layer->previous_framebuffer));
    glViewport(
        layer->previous_viewport[0],
        layer->previous_viewport[1],
        layer->previous_viewport[2],
        layer->previous_viewport[3]
    );
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, static_cast<GLuint>(layer->previous_texture));
    glActiveTexture(static_cast<GLenum>(layer->previous_active_texture));
    layer->prepared = false;
}

kgl_gl_function skia_gl_get_proc(void *, const char *name) {
    return reinterpret_cast<kgl_gl_function>(SDL_GL_GetProcAddress(name));
}

}  // namespace

extern "C" {

void *kgl_event_watch_add(kgl_event_watch_callback callback, void *userdata) {
    if (!callback) return nullptr;
    auto *watch = new KglEventWatch{callback, userdata};
    SDL_AddEventWatch(dispatch_event_watch, watch);
    return watch;
}

void kgl_event_watch_remove(void *raw_watch) {
    auto *watch = static_cast<KglEventWatch *>(raw_watch);
    if (!watch) return;
    SDL_RemoveEventWatch(dispatch_event_watch, watch);
    delete watch;
}

void kgl_get_window_size(void *raw_window, int *width, int *height) {
    auto *window = static_cast<SDL_Window *>(raw_window);
    if (!window) {
        if (width) *width = 0;
        if (height) *height = 0;
        return;
    }
    SDL_GetWindowSize(window, width, height);
}

void *kgl_layer_create(void) {
    return new KglLayer();
}

void kgl_layer_destroy(void *raw_layer) {
    auto *layer = static_cast<KglLayer *>(raw_layer);
    if (!layer) return;
    if (glGetString(GL_VERSION)) {
        restore_gl_state(layer);
        if (layer->framebuffer) glDeleteFramebuffers(1, &layer->framebuffer);
        if (layer->texture) glDeleteTextures(1, &layer->texture);
    }
    delete layer;
}

int kgl_layer_prepare(void *raw_layer, int width, int height) {
    auto *layer = static_cast<KglLayer *>(raw_layer);
    if (!layer || width <= 0 || height <= 0 || layer->prepared) return 0;

    glGetIntegerv(GL_FRAMEBUFFER_BINDING, &layer->previous_framebuffer);
    glGetIntegerv(GL_VIEWPORT, layer->previous_viewport);
    glGetIntegerv(GL_ACTIVE_TEXTURE, &layer->previous_active_texture);
    glActiveTexture(GL_TEXTURE0);
    glGetIntegerv(GL_TEXTURE_BINDING_2D, &layer->previous_texture);
    layer->prepared = true;

    if (layer->width != width || layer->height != height) {
        if (!layer->texture) glGenTextures(1, &layer->texture);
        if (!layer->framebuffer) glGenFramebuffers(1, &layer->framebuffer);

        glBindTexture(GL_TEXTURE_2D, layer->texture);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexImage2D(
            GL_TEXTURE_2D,
            0,
            GL_RGBA8,
            width,
            height,
            0,
            GL_RGBA,
            GL_UNSIGNED_BYTE,
            nullptr
        );

        glBindFramebuffer(GL_FRAMEBUFFER, layer->framebuffer);
        glFramebufferTexture2D(
            GL_FRAMEBUFFER,
            GL_COLOR_ATTACHMENT0,
            GL_TEXTURE_2D,
            layer->texture,
            0
        );
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
            restore_gl_state(layer);
            return 0;
        }
        layer->width = width;
        layer->height = height;
    } else {
        glBindFramebuffer(GL_FRAMEBUFFER, layer->framebuffer);
    }

    glViewport(0, 0, width, height);
    glDisable(GL_SCISSOR_TEST);
    glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
    glClear(GL_COLOR_BUFFER_BIT);
    glBindTexture(GL_TEXTURE_2D, 0);
    return 1;
}

void kgl_layer_finish(void *raw_layer) {
    restore_gl_state(static_cast<KglLayer *>(raw_layer));
}

unsigned int kgl_layer_framebuffer(void *raw_layer) {
    auto *layer = static_cast<KglLayer *>(raw_layer);
    return layer ? layer->framebuffer : 0;
}

unsigned int kgl_layer_texture(void *raw_layer) {
    auto *layer = static_cast<KglLayer *>(raw_layer);
    return layer ? layer->texture : 0;
}

const char *kgl_renderer(void) {
    const GLubyte *value = glGetString(GL_RENDERER);
    return value ? reinterpret_cast<const char *>(value) : nullptr;
}

int kgl_context_is_lost(void) { return 0; }
kgl_gl_get_proc kgl_skia_gl_get_proc_resolver(void) { return skia_gl_get_proc; }

}  // extern "C"
