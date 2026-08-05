#ifndef KTNATIVE_LINUX_DRAG_H
#define KTNATIVE_LINUX_DRAG_H

#ifdef __cplusplus
extern "C" {
#endif

void *kdrag_create(void *sdl_window, char **error_message);
void kdrag_destroy(void *drag);
int kdrag_start(
    void *drag,
    const char *text,
    const char *uri_list,
    const unsigned char *icon_pixels,
    int icon_width,
    int icon_height,
    int icon_stride,
    char **error_message);
void kdrag_pointer_motion(void *drag);
void kdrag_pointer_release(void *drag);
void kdrag_handle_syswm(void *drag, const void *sdl_syswm_message);
int kdrag_active(void *drag);

/* Configures per-pixel top-level transparency for the active SDL backend. */
int kplatform_window_set_transparent(void *sdl_window, int transparent);

#ifdef __cplusplus
}
#endif

#endif
