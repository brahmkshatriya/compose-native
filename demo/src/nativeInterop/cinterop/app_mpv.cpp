#include "app_mpv.h"

#include <SDL3/SDL.h>
#include <mpv/client.h>
#include <mpv/render_gl.h>

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <mutex>
#include <string>
#include <thread>

struct AppMpv {
    std::string uri;
    std::string error;
    mpv_handle *handle = nullptr;
    mpv_render_context *render_context = nullptr;
    bool initialized = false;
    bool playing = false;
    double volume = 80.0;
    std::atomic<bool> render_update_pending{false};
    std::mutex render_callback_mutex;
    AppMpvRenderUpdateCallback render_update_callback = nullptr;
    void *render_update_context = nullptr;
    std::atomic<bool> event_thread_stopping{false};
    std::thread event_thread;
    std::mutex position_callback_mutex;
    AppMpvPositionUpdateCallback position_update_callback = nullptr;
    void *position_update_context = nullptr;
    int last_framebuffer = -1;
    int last_width = 0;
    int last_height = 0;
};

static void app_mpv_on_render_update(void *context) {
    auto *player = static_cast<AppMpv *>(context);
    const bool update_already_pending =
        player->render_update_pending.exchange(true, std::memory_order_acq_rel);
    if (update_already_pending) return;

    std::lock_guard<std::mutex> lock(player->render_callback_mutex);
    if (player->render_update_callback) {
        player->render_update_callback(player->render_update_context);
    }
}

static constexpr std::uint64_t APP_MPV_POSITION_EVENT = 1;

static void app_mpv_emit_position(AppMpv *player, double percent) {
    std::lock_guard<std::mutex> lock(player->position_callback_mutex);
    if (player->position_update_callback) {
        player->position_update_callback(
            player->position_update_context,
            std::clamp(percent / 100.0, 0.0, 1.0)
        );
    }
}

static void app_mpv_event_loop(AppMpv *player) {
    while (!player->event_thread_stopping.load(std::memory_order_acquire)) {
        mpv_event *event = mpv_wait_event(player->handle, -1.0);
        if (player->event_thread_stopping.load(std::memory_order_acquire)) break;
        if (!event || event->event_id == MPV_EVENT_NONE) continue;
        if (event->event_id == MPV_EVENT_SHUTDOWN) break;
        if (event->event_id != MPV_EVENT_PROPERTY_CHANGE) continue;
        if (event->reply_userdata != APP_MPV_POSITION_EVENT) continue;

        auto *property = static_cast<mpv_event_property *>(event->data);
        if (!property || property->format != MPV_FORMAT_DOUBLE || !property->data) continue;
        app_mpv_emit_position(player, *static_cast<double *>(property->data));
    }
}

static void *app_mpv_get_proc_address(void *, const char *name) {
    return reinterpret_cast<void *>(SDL_GL_GetProcAddress(name));
}

static bool app_mpv_initialize(AppMpv *player) {
    if (player->initialized) return player->render_context != nullptr;
    player->initialized = true;

    player->handle = mpv_create();
    if (!player->handle) {
        player->error = "Could not create MPV";
        return false;
    }

    mpv_set_option_string(player->handle, "vo", "libmpv");
    mpv_set_option_string(player->handle, "hwdec", "auto-safe");
    mpv_set_option_string(player->handle, "keep-open", "yes");
    mpv_set_option_string(player->handle, "audio-client-name", "Compose native catalogue");
    if (mpv_initialize(player->handle) < 0) {
        player->error = "Could not initialize MPV";
        return false;
    }

    mpv_opengl_init_params gl_init = {app_mpv_get_proc_address, nullptr};
    mpv_render_param params[] = {
        {MPV_RENDER_PARAM_API_TYPE, const_cast<char *>(MPV_RENDER_API_TYPE_OPENGL)},
        {MPV_RENDER_PARAM_OPENGL_INIT_PARAMS, &gl_init},
        {MPV_RENDER_PARAM_INVALID, nullptr},
    };
    const int render_result = mpv_render_context_create(&player->render_context, player->handle, params);
    if (render_result < 0) {
        player->error = std::string("Could not create MPV OpenGL renderer: ") + mpv_error_string(render_result);
        return false;
    }

    mpv_render_context_set_update_callback(
        player->render_context,
        app_mpv_on_render_update,
        player
    );

    const int observe_result = mpv_observe_property(
        player->handle,
        APP_MPV_POSITION_EVENT,
        "percent-pos",
        MPV_FORMAT_DOUBLE
    );
    if (observe_result < 0) {
        player->error = std::string("Could not observe MPV position: ") +
            mpv_error_string(observe_result);
        return false;
    }
    player->event_thread = std::thread(app_mpv_event_loop, player);

    const char *load[] = {"loadfile", player->uri.c_str(), "replace", nullptr};
    mpv_command_async(player->handle, 0, load);
    mpv_set_property_string(player->handle, "pause", player->playing ? "no" : "yes");
    const std::string volume = std::to_string(player->volume);
    mpv_set_property_string(player->handle, "volume", volume.c_str());
    return true;
}

extern "C" AppMpv *app_mpv_create(const char *uri) {
    AppMpv *player = new AppMpv;
    player->uri = uri ? uri : "";
    return player;
}

extern "C" void app_mpv_destroy(AppMpv *player) {
    if (!player) return;
    if (player->render_context) {
        mpv_render_context_set_update_callback(player->render_context, nullptr, nullptr);
    }
    {
        std::lock_guard<std::mutex> lock(player->render_callback_mutex);
        player->render_update_callback = nullptr;
        player->render_update_context = nullptr;
    }
    {
        std::lock_guard<std::mutex> lock(player->position_callback_mutex);
        player->position_update_callback = nullptr;
        player->position_update_context = nullptr;
    }
    if (player->event_thread.joinable()) {
        player->event_thread_stopping.store(true, std::memory_order_release);
        mpv_wakeup(player->handle);
        player->event_thread.join();
    }
    if (player->render_context) mpv_render_context_free(player->render_context);
    if (player->handle) mpv_terminate_destroy(player->handle);
    delete player;
}

extern "C" const char *app_mpv_error(AppMpv *player) {
    return player && !player->error.empty() ? player->error.c_str() : nullptr;
}

extern "C" int app_mpv_render(AppMpv *player, int framebuffer, int width, int height) {
    if (!player || width <= 0 || height <= 0 || !app_mpv_initialize(player)) return 0;

    const bool target_changed =
        framebuffer != player->last_framebuffer ||
        width != player->last_width ||
        height != player->last_height;
    const bool update_pending =
        player->render_update_pending.exchange(false, std::memory_order_acq_rel);

    if (!target_changed && !update_pending) return 0;

    const std::uint64_t update_flags =
        update_pending ? mpv_render_context_update(player->render_context) : 0;
    if (!target_changed && !(update_flags & MPV_RENDER_UPDATE_FRAME)) return 0;

    mpv_opengl_fbo fbo = {framebuffer, width, height, 0};
    int flip_y = 1;
    mpv_render_param params[] = {
        {MPV_RENDER_PARAM_OPENGL_FBO, &fbo},
        {MPV_RENDER_PARAM_FLIP_Y, &flip_y},
        {MPV_RENDER_PARAM_INVALID, nullptr},
    };
    const int render_result = mpv_render_context_render(player->render_context, params);
    if (render_result < 0) {
        player->error = std::string("Could not render MPV frame: ") + mpv_error_string(render_result);
        return 0;
    }

    player->last_framebuffer = framebuffer;
    player->last_width = width;
    player->last_height = height;
    return 1;
}

extern "C" void app_mpv_set_render_update_callback(
    AppMpv *player,
    AppMpvRenderUpdateCallback callback,
    void *context
) {
    if (!player) return;

    if (!callback && player->render_context) {
        mpv_render_context_set_update_callback(player->render_context, nullptr, nullptr);
    }

    {
        std::lock_guard<std::mutex> lock(player->render_callback_mutex);
        player->render_update_context = context;
        player->render_update_callback = callback;
    }

    if (callback && player->render_context) {
        mpv_render_context_set_update_callback(
            player->render_context,
            app_mpv_on_render_update,
            player
        );
    }
}

extern "C" void app_mpv_set_playing(AppMpv *player, int playing) {
    if (!player) return;
    player->playing = playing != 0;
    if (player->handle) {
        mpv_set_property_string(player->handle, "pause", player->playing ? "no" : "yes");
    }
}

extern "C" void app_mpv_seek_percent(AppMpv *player, double percent) {
    if (!player || !player->handle) return;
    const std::string position = std::to_string(std::clamp(percent, 0.0, 100.0));
    const char *seek[] = {"seek", position.c_str(), "absolute-percent", "exact", nullptr};
    mpv_command_async(player->handle, 0, seek);
}

extern "C" void app_mpv_set_position_update_callback(
    AppMpv *player,
    AppMpvPositionUpdateCallback callback,
    void *context
) {
    if (!player) return;
    std::lock_guard<std::mutex> lock(player->position_callback_mutex);
    player->position_update_context = context;
    player->position_update_callback = callback;
}

extern "C" void app_mpv_set_volume(AppMpv *player, double volume) {
    if (!player) return;
    player->volume = std::clamp(volume, 0.0, 100.0);
    if (player->handle) {
        const std::string value = std::to_string(player->volume);
        mpv_set_property_string(player->handle, "volume", value.c_str());
    }
}
