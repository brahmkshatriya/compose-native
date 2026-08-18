#ifndef KTNATIVE_LINUX_GL_H
#define KTNATIVE_LINUX_GL_H

#ifdef __cplusplus
extern "C" {
#endif

void *kgl_layer_create(void);
void kgl_layer_destroy(void *layer);
int kgl_layer_prepare(void *layer, int width, int height);
void kgl_layer_finish(void *layer);
unsigned int kgl_layer_framebuffer(void *layer);
unsigned int kgl_layer_texture(void *layer);
const char *kgl_renderer(void);
int kgl_context_is_lost(void);

typedef void (*kgl_gl_function)(void);
typedef kgl_gl_function (*kgl_gl_get_proc)(void *context, const char *name);
kgl_gl_get_proc kgl_skia_gl_get_proc_resolver(void);

typedef int (*kgl_event_watch_callback)(void *userdata, void *event);
void *kgl_event_watch_add(kgl_event_watch_callback callback, void *userdata);
void kgl_event_watch_remove(void *watch);
void kgl_get_window_size(void *window, int *width, int *height);

#ifdef __cplusplus
}
#endif

#endif
