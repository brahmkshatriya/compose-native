#pragma once

#ifdef __cplusplus
extern "C" {
#endif

typedef struct AppMpv AppMpv;
typedef void (*AppMpvRenderUpdateCallback)(void *context);
typedef void (*AppMpvPositionUpdateCallback)(void *context, double position);

AppMpv *app_mpv_create(const char *uri);
void app_mpv_destroy(AppMpv *player);
const char *app_mpv_error(AppMpv *player);
int app_mpv_render(AppMpv *player, int framebuffer, int width, int height);
void app_mpv_set_render_update_callback(
    AppMpv *player,
    AppMpvRenderUpdateCallback callback,
    void *context
);
void app_mpv_set_playing(AppMpv *player, int playing);
void app_mpv_seek_percent(AppMpv *player, double percent);
void app_mpv_set_position_update_callback(
    AppMpv *player,
    AppMpvPositionUpdateCallback callback,
    void *context
);
void app_mpv_set_volume(AppMpv *player, double volume);

#ifdef __cplusplus
}
#endif
