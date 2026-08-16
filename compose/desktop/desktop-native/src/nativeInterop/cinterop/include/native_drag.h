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

/* Extends the client area into the system title bar when the backend supports it. */
int kplatform_window_allow_drawing_inside_title_bar(void *sdl_window, int allow);

/* Returns the platform caption-button width and title-bar height in window pixels. */
int kplatform_window_get_title_bar_metrics(
    void *sdl_window,
    int *caption_button_width,
    int *title_bar_height
);

/* Sets a Compose caption button's bounds in client pixels for native hit testing. */
int kplatform_window_set_caption_button_bounds(
    void *sdl_window,
    int button_type,
    int enabled,
    int x,
    int y,
    int width,
    int height
);

/* Requests an external compositor shadow for a client-decorated window when supported. */
int kplatform_window_set_shadow(void *sdl_window, int enabled);
int kplatform_window_refresh_shadow(void *sdl_window);

/* Overrides the native title bar colors when the platform supports it.
 * Color components are sRGB values in 0..255, or -1 to leave a component unchanged. */
int kplatform_window_set_title_bar_color(
    void *sdl_window,
    int background_r,
    int background_g,
    int background_b,
    int foreground_r,
    int foreground_g,
    int foreground_b
);

#ifdef __cplusplus
}
#endif

#endif
