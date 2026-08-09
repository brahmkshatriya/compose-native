#define GL_GLEXT_PROTOTYPES 1
#include <GL/gl.h>
#include <GL/glext.h>
#include <SDL3/SDL.h>
#include <cstdint>

#include "include/linux_gl.h"

using ActiveTextureFunction = void (*)(GLenum texture);
using BindFramebufferFunction = void (*)(GLenum target, GLuint framebuffer);
using CheckFramebufferStatusFunction = GLenum (*)(GLenum target);
using DeleteFramebuffersFunction = void (*)(GLsizei count, const GLuint *framebuffers);
using FramebufferTexture2DFunction =
    void (*)(GLenum target, GLenum attachment, GLenum texture_target, GLuint texture, GLint level);
using GenFramebuffersFunction = void (*)(GLsizei count, GLuint *framebuffers);

static ActiveTextureFunction kglActiveTexture = nullptr;
static BindFramebufferFunction kglBindFramebuffer = nullptr;
static CheckFramebufferStatusFunction kglCheckFramebufferStatus = nullptr;
static DeleteFramebuffersFunction kglDeleteFramebuffers = nullptr;
static FramebufferTexture2DFunction kglFramebufferTexture2D = nullptr;
static GenFramebuffersFunction kglGenFramebuffers = nullptr;

template <typename T>
static T resolveGlFunction(const char *name) {
    return reinterpret_cast<T>(SDL_GL_GetProcAddress(name));
}

static bool ensureFramebufferFunctions() {
    if (kglBindFramebuffer) return true;
    kglActiveTexture = resolveGlFunction<ActiveTextureFunction>("glActiveTexture");
    kglBindFramebuffer = resolveGlFunction<BindFramebufferFunction>("glBindFramebuffer");
    kglCheckFramebufferStatus =
        resolveGlFunction<CheckFramebufferStatusFunction>("glCheckFramebufferStatus");
    kglDeleteFramebuffers =
        resolveGlFunction<DeleteFramebuffersFunction>("glDeleteFramebuffers");
    kglFramebufferTexture2D =
        resolveGlFunction<FramebufferTexture2DFunction>("glFramebufferTexture2D");
    kglGenFramebuffers = resolveGlFunction<GenFramebuffersFunction>("glGenFramebuffers");
    return kglActiveTexture && kglBindFramebuffer && kglCheckFramebufferStatus &&
        kglDeleteFramebuffers && kglFramebufferTexture2D && kglGenFramebuffers;
}


struct KglEventWatch {
    kgl_event_watch_callback callback = nullptr;
    void *userdata = nullptr;
};

static bool SDLCALL dispatchEventWatch(void *rawWatch, SDL_Event *event) {
    KglEventWatch *watch = static_cast<KglEventWatch *>(rawWatch);
    if (!watch || !watch->callback || !event) return true;
    return watch->callback(watch->userdata, event) != 0;
}

struct KglLayer {
    GLuint framebuffer = 0;
    GLuint texture = 0;
    int width = 0;
    int height = 0;
    GLint previousFramebuffer = 0;
    GLint previousViewport[4] = {0, 0, 1, 1};
    GLint previousActiveTexture = GL_TEXTURE0;
    GLint previousTexture = 0;
    bool prepared = false;
};

static void restoreState(KglLayer *layer) {
    if (!layer || !layer->prepared) return;
    kglBindFramebuffer(GL_FRAMEBUFFER, static_cast<GLuint>(layer->previousFramebuffer));
    glViewport(
        layer->previousViewport[0],
        layer->previousViewport[1],
        layer->previousViewport[2],
        layer->previousViewport[3]
    );
    kglActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, static_cast<GLuint>(layer->previousTexture));
    kglActiveTexture(static_cast<GLenum>(layer->previousActiveTexture));
    layer->prepared = false;
}

extern "C" {


void *kgl_event_watch_add(kgl_event_watch_callback callback, void *userdata) {
    if (!callback) return nullptr;
    KglEventWatch *watch = new KglEventWatch();
    watch->callback = callback;
    watch->userdata = userdata;
    SDL_AddEventWatch(dispatchEventWatch, watch);
    return watch;
}

void kgl_event_watch_remove(void *rawWatch) {
    KglEventWatch *watch = static_cast<KglEventWatch *>(rawWatch);
    if (!watch) return;
    SDL_RemoveEventWatch(dispatchEventWatch, watch);
    delete watch;
}

void kgl_get_window_size(void *rawWindow, int *width, int *height) {
    SDL_Window *window = static_cast<SDL_Window *>(rawWindow);
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

void kgl_layer_destroy(void *rawLayer) {
    KglLayer *layer = static_cast<KglLayer *>(rawLayer);
    if (!layer) return;
    // Skiko owns the WGL context on Windows, so SDL does not necessarily know
    // about it even though OpenGL is current on this thread.
    if (glGetString(GL_VERSION)) {
        restoreState(layer);
        if (layer->framebuffer && ensureFramebufferFunctions()) {
            kglDeleteFramebuffers(1, &layer->framebuffer);
        }
        if (layer->texture) glDeleteTextures(1, &layer->texture);
    }
    delete layer;
}

int kgl_layer_prepare(void *rawLayer, int width, int height) {
    KglLayer *layer = static_cast<KglLayer *>(rawLayer);
    if (!layer || width <= 0 || height <= 0 || layer->prepared ||
        !ensureFramebufferFunctions()) return 0;

    glGetIntegerv(GL_FRAMEBUFFER_BINDING, &layer->previousFramebuffer);
    glGetIntegerv(GL_VIEWPORT, layer->previousViewport);
    glGetIntegerv(GL_ACTIVE_TEXTURE, &layer->previousActiveTexture);
    kglActiveTexture(GL_TEXTURE0);
    glGetIntegerv(GL_TEXTURE_BINDING_2D, &layer->previousTexture);
    layer->prepared = true;

    if (layer->width != width || layer->height != height) {
        if (!layer->texture) glGenTextures(1, &layer->texture);
        if (!layer->framebuffer) kglGenFramebuffers(1, &layer->framebuffer);

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

        kglBindFramebuffer(GL_FRAMEBUFFER, layer->framebuffer);
        kglFramebufferTexture2D(
            GL_FRAMEBUFFER,
            GL_COLOR_ATTACHMENT0,
            GL_TEXTURE_2D,
            layer->texture,
            0
        );
        if (kglCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
            restoreState(layer);
            return 0;
        }
        layer->width = width;
        layer->height = height;
    } else {
        kglBindFramebuffer(GL_FRAMEBUFFER, layer->framebuffer);
    }

    glViewport(0, 0, width, height);
    glDisable(GL_SCISSOR_TEST);
    glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
    glClear(GL_COLOR_BUFFER_BIT);
    glBindTexture(GL_TEXTURE_2D, 0);
    return 1;
}

void kgl_layer_finish(void *rawLayer) {
    restoreState(static_cast<KglLayer *>(rawLayer));
}

unsigned int kgl_layer_framebuffer(void *rawLayer) {
    KglLayer *layer = static_cast<KglLayer *>(rawLayer);
    return layer ? layer->framebuffer : 0;
}

unsigned int kgl_layer_texture(void *rawLayer) {
    KglLayer *layer = static_cast<KglLayer *>(rawLayer);
    return layer ? layer->texture : 0;
}

const char *kgl_renderer(void) {
    const GLubyte *value = glGetString(GL_RENDERER);
    return value ? reinterpret_cast<const char *>(value) : nullptr;
}

int kgl_context_is_lost(void) {
    using GetGraphicsResetStatus = GLenum (*)(void);
    auto getResetStatus = reinterpret_cast<GetGraphicsResetStatus>(
        SDL_GL_GetProcAddress("glGetGraphicsResetStatus")
    );
    if (!getResetStatus) {
        getResetStatus = reinterpret_cast<GetGraphicsResetStatus>(
            SDL_GL_GetProcAddress("glGetGraphicsResetStatusARB")
        );
    }
    return getResetStatus && getResetStatus() != GL_NO_ERROR;
}

}
