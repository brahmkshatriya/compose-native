#ifndef KTNATIVE_APP_WEBVIEW_H
#define KTNATIVE_APP_WEBVIEW_H

#ifdef __cplusplus
extern "C" {
#endif

typedef struct AppWebView AppWebView;

AppWebView *app_webview_create(const char *uri);
void app_webview_destroy(AppWebView *view);
const char *app_webview_error(AppWebView *view);
int app_webview_render(
    AppWebView *view,
    int framebuffer,
    int width,
    int height,
    float device_scale
);
void app_webview_load_uri(AppWebView *view, const char *uri);
void app_webview_go_back(AppWebView *view);
void app_webview_go_forward(AppWebView *view);
void app_webview_reload(AppWebView *view);
int app_webview_can_go_back(AppWebView *view);
int app_webview_can_go_forward(AppWebView *view);
void app_webview_media_set_playing(AppWebView *view, int playing);
void app_webview_media_seek(AppWebView *view, double seconds);
void app_webview_media_set_volume(AppWebView *view, double volume);
void app_webview_set_focused(AppWebView *view, int focused);
void app_webview_pointer_motion(
    AppWebView *view,
    int x,
    int y,
    unsigned int time,
    unsigned int modifiers
);
void app_webview_pointer_button(
    AppWebView *view,
    int x,
    int y,
    unsigned int time,
    unsigned int button,
    int pressed,
    unsigned int modifiers
);
void app_webview_scroll(
    AppWebView *view,
    int x,
    int y,
    unsigned int time,
    double delta_x,
    double delta_y,
    unsigned int modifiers
);
void app_webview_key(
    AppWebView *view,
    long long compose_key,
    unsigned int code_point,
    int pressed,
    unsigned int modifiers
);
void app_demo_render_gl(int framebuffer, int width, int height, float phase);

#ifdef __cplusplus
}
#endif

#endif
