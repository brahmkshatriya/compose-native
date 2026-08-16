#include "native_drag.h"

#include <SDL3/SDL.h>
#include <X11/Xatom.h>
#include <X11/Xlib.h>
#include <wayland-client.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>

#include <algorithm>
#include <cerrno>
#include <cmath>
#include <csignal>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

namespace {

struct org_kde_kwin_shadow;
struct org_kde_kwin_shadow_manager;

extern const wl_interface org_kde_kwin_shadow_interface;

static const wl_interface *kwin_shadow_types[] = {
    nullptr,
    &org_kde_kwin_shadow_interface,
    &wl_surface_interface,
    &wl_surface_interface,
    &wl_buffer_interface,
    &wl_buffer_interface,
    &wl_buffer_interface,
    &wl_buffer_interface,
    &wl_buffer_interface,
    &wl_buffer_interface,
    &wl_buffer_interface,
    &wl_buffer_interface,
};

static const wl_message kwin_shadow_manager_requests[] = {
    {"create", "no", kwin_shadow_types + 1},
    {"unset", "o", kwin_shadow_types + 3},
    {"destroy", "2", kwin_shadow_types},
};

extern const wl_interface org_kde_kwin_shadow_manager_interface = {
    "org_kde_kwin_shadow_manager", 2, 3, kwin_shadow_manager_requests, 0, nullptr,
};

static const wl_message kwin_shadow_requests[] = {
    {"commit", "", kwin_shadow_types},
    {"attach_left", "o", kwin_shadow_types + 4},
    {"attach_top_left", "o", kwin_shadow_types + 5},
    {"attach_top", "o", kwin_shadow_types + 6},
    {"attach_top_right", "o", kwin_shadow_types + 7},
    {"attach_right", "o", kwin_shadow_types + 8},
    {"attach_bottom_right", "o", kwin_shadow_types + 9},
    {"attach_bottom", "o", kwin_shadow_types + 10},
    {"attach_bottom_left", "o", kwin_shadow_types + 11},
    {"set_left_offset", "f", kwin_shadow_types},
    {"set_top_offset", "f", kwin_shadow_types},
    {"set_right_offset", "f", kwin_shadow_types},
    {"set_bottom_offset", "f", kwin_shadow_types},
    {"destroy", "2", kwin_shadow_types},
};

extern const wl_interface org_kde_kwin_shadow_interface = {
    "org_kde_kwin_shadow", 2, 14, kwin_shadow_requests, 0, nullptr,
};

org_kde_kwin_shadow *kwin_shadow_create(
    org_kde_kwin_shadow_manager *manager,
    wl_surface *surface
) {
    return reinterpret_cast<org_kde_kwin_shadow *>(wl_proxy_marshal_flags(
        reinterpret_cast<wl_proxy *>(manager), 0, &org_kde_kwin_shadow_interface,
        wl_proxy_get_version(reinterpret_cast<wl_proxy *>(manager)), 0, nullptr, surface));
}

void kwin_shadow_request(org_kde_kwin_shadow *shadow, uint32_t opcode, wl_buffer *buffer = nullptr) {
    wl_proxy_marshal_flags(
        reinterpret_cast<wl_proxy *>(shadow), opcode, nullptr,
        wl_proxy_get_version(reinterpret_cast<wl_proxy *>(shadow)), 0, buffer);
}

void kwin_shadow_set_offset(org_kde_kwin_shadow *shadow, uint32_t opcode, int offset) {
    wl_proxy_marshal_flags(
        reinterpret_cast<wl_proxy *>(shadow), opcode, nullptr,
        wl_proxy_get_version(reinterpret_cast<wl_proxy *>(shadow)), 0,
        wl_fixed_from_int(offset));
}

void kwin_shadow_destroy(org_kde_kwin_shadow *shadow) {
    wl_proxy_marshal_flags(
        reinterpret_cast<wl_proxy *>(shadow), 13, nullptr,
        wl_proxy_get_version(reinterpret_cast<wl_proxy *>(shadow)),
        WL_MARSHAL_FLAG_DESTROY);
}

void kwin_shadow_unset(org_kde_kwin_shadow_manager *manager, wl_surface *surface) {
    wl_proxy_marshal_flags(
        reinterpret_cast<wl_proxy *>(manager), 1, nullptr,
        wl_proxy_get_version(reinterpret_cast<wl_proxy *>(manager)), 0, surface);
}

char *copy_string(const char *value) {
    if (!value) return nullptr;
    const size_t length = std::strlen(value) + 1;
    char *result = static_cast<char *>(std::malloc(length));
    if (result) std::memcpy(result, value, length);
    return result;
}

void set_error(char **output, const char *message) {
    if (output) *output = copy_string(message ? message : "Unknown drag-and-drop error");
}

bool write_all(int fd, const std::string &value) {
    const char *data = value.data();
    size_t remaining = value.size();
    while (remaining > 0) {
        const ssize_t written = ::write(fd, data, remaining);
        if (written < 0) {
            if (errno == EINTR) continue;
            return false;
        }
        data += written;
        remaining -= static_cast<size_t>(written);
    }
    return true;
}

struct WaylandContext {
    wl_display *display = nullptr;
    wl_registry *registry = nullptr;
    wl_compositor *compositor = nullptr;
    wl_shm *shm = nullptr;
    wl_seat *seat = nullptr;
    wl_pointer *pointer = nullptr;
    wl_data_device_manager *manager = nullptr;
    wl_data_device *device = nullptr;
    wl_data_source *source = nullptr;
    org_kde_kwin_shadow_manager *shadow_manager = nullptr;
    wl_surface *icon_surface = nullptr;
    wl_buffer *icon_buffer = nullptr;
    void *icon_data = nullptr;
    size_t icon_data_size = 0;
    wl_surface *entered_surface = nullptr;
    wl_surface *source_surface = nullptr;
    uint32_t serial = 0;
    std::string text;
    std::string uri_list;
    bool active = false;
    int references = 0;
};

struct ShadowBuffer {
    wl_buffer *buffer = nullptr;
    void *data = nullptr;
    size_t size = 0;
};

struct WindowShadow {
    WaylandContext *context = nullptr;
    wl_surface *surface = nullptr;
    org_kde_kwin_shadow *shadow = nullptr;
    ShadowBuffer buffers[8];
};

struct WaylandHandle {
    WaylandContext *context = nullptr;
    wl_surface *surface = nullptr;
};

std::vector<WaylandContext *> wayland_contexts;
std::vector<std::pair<SDL_Window *, WindowShadow *>> window_shadows;

void destroy_shadow_buffer(ShadowBuffer *buffer) {
    if (buffer->buffer) wl_buffer_destroy(buffer->buffer);
    if (buffer->data && buffer->size) munmap(buffer->data, buffer->size);
    *buffer = {};
}

bool create_shadow_buffer(
    WaylandContext *context,
    ShadowBuffer *result,
    int width,
    int height,
    int horizontal,
    int vertical
) {
    const int stride = width * 4;
    const size_t bytes = static_cast<size_t>(stride) * static_cast<size_t>(height);
    static uint32_t next_shadow_id = 1;
    int fd = -1;
    std::string name;
    for (int attempt = 0; attempt < 32 && fd < 0; ++attempt) {
        name = "/ktnative-shadow-" + std::to_string(getpid()) + "-" +
            std::to_string(next_shadow_id++);
        fd = shm_open(name.c_str(), O_CREAT | O_EXCL | O_RDWR, 0600);
    }
    if (fd < 0) return false;
    shm_unlink(name.c_str());
    if (ftruncate(fd, static_cast<off_t>(bytes)) != 0) {
        close(fd);
        return false;
    }
    void *mapping = mmap(nullptr, bytes, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    if (mapping == MAP_FAILED) {
        close(fd);
        return false;
    }

    constexpr int padding = 24;
    auto *pixels = static_cast<uint32_t *>(mapping);
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            const float dx = horizontal < 0 ? std::max(0, padding - x) :
                horizontal > 0 ? std::max(0, x - (width - padding - 1)) : 0;
            const float dy = vertical < 0 ? std::max(0, padding - y) :
                vertical > 0 ? std::max(0, y - (height - padding - 1)) : 0;
            const float distance = std::sqrt(dx * dx + dy * dy);
            const float strength = std::max(0.0f, 1.0f - distance / padding);
            const uint32_t alpha = static_cast<uint32_t>(72.0f * strength * strength);
            pixels[y * width + x] = alpha << 24;
        }
    }

    wl_shm_pool *pool = wl_shm_create_pool(context->shm, fd, static_cast<int>(bytes));
    close(fd);
    if (!pool) {
        munmap(mapping, bytes);
        return false;
    }
    wl_buffer *buffer = wl_shm_pool_create_buffer(
        pool, 0, width, height, stride, WL_SHM_FORMAT_ARGB8888);
    wl_shm_pool_destroy(pool);
    if (!buffer) {
        munmap(mapping, bytes);
        return false;
    }
    result->buffer = buffer;
    result->data = mapping;
    result->size = bytes;
    return true;
}

void destroy_window_shadow(WindowShadow *window_shadow, bool unset) {
    if (unset && window_shadow->context && window_shadow->context->shadow_manager &&
        window_shadow->surface) {
        kwin_shadow_unset(window_shadow->context->shadow_manager, window_shadow->surface);
        wl_surface_commit(window_shadow->surface);
    }
    if (window_shadow->shadow) kwin_shadow_destroy(window_shadow->shadow);
    for (auto &buffer : window_shadow->buffers) destroy_shadow_buffer(&buffer);
    delete window_shadow;
}

void remove_window_shadow(SDL_Window *window) {
    auto iterator = std::find_if(
        window_shadows.begin(), window_shadows.end(),
        [window](const auto &entry) { return entry.first == window; });
    if (iterator == window_shadows.end()) return;
    destroy_window_shadow(iterator->second, true);
    window_shadows.erase(iterator);
}

bool install_window_shadow(SDL_Window *window) {
    std::fprintf(stderr, "[shadow] install_window_shadow(window=%p)\n", static_cast<void *>(window));
    remove_window_shadow(window);
    const SDL_PropertiesID properties = SDL_GetWindowProperties(window);
    wl_display *display = static_cast<wl_display *>(SDL_GetPointerProperty(
        properties, SDL_PROP_WINDOW_WAYLAND_DISPLAY_POINTER, nullptr));
    wl_surface *surface = static_cast<wl_surface *>(SDL_GetPointerProperty(
        properties, SDL_PROP_WINDOW_WAYLAND_SURFACE_POINTER, nullptr));
    if (!display || !surface) {
        std::fprintf(stderr, "[shadow] FAIL: no display(%p)/surface(%p)\n",
            static_cast<void *>(display), static_cast<void *>(surface));
        return false;
    }
    auto context_iterator = std::find_if(
        wayland_contexts.begin(), wayland_contexts.end(),
        [display](WaylandContext *context) { return context->display == display; });
    if (context_iterator == wayland_contexts.end()) {
        std::fprintf(stderr, "[shadow] FAIL: no wayland context for display=%p (contexts=%zu)\n",
            static_cast<void *>(display), wayland_contexts.size());
        return false;
    }
    WaylandContext *context = *context_iterator;
    if (!context->shadow_manager || !context->shm) {
        std::fprintf(stderr, "[shadow] FAIL: shadow_manager=%p shm=%p\n",
            static_cast<void *>(context->shadow_manager), static_cast<void *>(context->shm));
        return false;
    }

    auto *window_shadow = new WindowShadow();
    window_shadow->context = context;
    window_shadow->surface = surface;
    window_shadow->shadow = kwin_shadow_create(context->shadow_manager, surface);
    constexpr int tile = 32;
    const int widths[8] = {tile, tile, 1, tile, tile, tile, 1, tile};
    const int heights[8] = {1, tile, tile, tile, 1, tile, tile, tile};
    const int horizontal[8] = {-1, -1, 0, 1, 1, 1, 0, -1};
    const int vertical[8] = {0, -1, -1, -1, 0, 1, 1, 1};
    for (int index = 0; index < 8; ++index) {
        if (!window_shadow->shadow || !create_shadow_buffer(
                context, &window_shadow->buffers[index], widths[index], heights[index],
                horizontal[index], vertical[index])) {
            std::fprintf(stderr, "[shadow] FAIL: shadow=%p or buffer[%d] failed\n",
                static_cast<void *>(window_shadow->shadow), index);
            destroy_window_shadow(window_shadow, false);
            return false;
        }
    }
    for (uint32_t index = 0; index < 8; ++index) {
        kwin_shadow_request(window_shadow->shadow, index + 1, window_shadow->buffers[index].buffer);
    }
    constexpr int padding = 24;
    for (uint32_t opcode = 9; opcode <= 12; ++opcode) {
        kwin_shadow_set_offset(window_shadow->shadow, opcode, padding);
    }
    kwin_shadow_request(window_shadow->shadow, 0);
    wl_surface_commit(surface);
    wl_display_flush(display);
    window_shadows.emplace_back(window, window_shadow);
    std::fprintf(stderr, "[shadow] install_window_shadow OK (surface=%p)\n",
        static_cast<void *>(surface));
    return true;
}

void wayland_clear_icon(WaylandContext *context) {
    if (context->icon_surface) wl_surface_destroy(context->icon_surface);
    if (context->icon_buffer) wl_buffer_destroy(context->icon_buffer);
    if (context->icon_data && context->icon_data_size > 0) {
        munmap(context->icon_data, context->icon_data_size);
    }
    context->icon_surface = nullptr;
    context->icon_buffer = nullptr;
    context->icon_data = nullptr;
    context->icon_data_size = 0;
}

bool wayland_set_icon(
    WaylandContext *context,
    const unsigned char *pixels,
    int width,
    int height,
    int stride,
    char **error
) {
    wayland_clear_icon(context);
    if (!pixels || width <= 0 || height <= 0 || stride < width * 4) return true;
    if (!context->compositor || !context->shm) {
        set_error(error, "Wayland drag-icon support is unavailable");
        return false;
    }
    const int target_stride = width * 4;
    const size_t bytes = static_cast<size_t>(target_stride) * static_cast<size_t>(height);
    static uint32_t next_shm_id = 1;
    std::string name;
    int fd = -1;
    for (int attempt = 0; attempt < 32 && fd < 0; ++attempt) {
        name = "/ktnative-drag-" + std::to_string(getpid()) + "-" +
            std::to_string(next_shm_id++);
        fd = shm_open(name.c_str(), O_CREAT | O_EXCL | O_RDWR, 0600);
    }
    if (fd < 0) {
        set_error(error, "Could not allocate the Wayland drag-icon shared memory");
        return false;
    }
    shm_unlink(name.c_str());
    if (ftruncate(fd, static_cast<off_t>(bytes)) != 0) {
        close(fd);
        set_error(error, "Could not size the Wayland drag-icon shared memory");
        return false;
    }
    void *mapping = mmap(nullptr, bytes, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    if (mapping == MAP_FAILED) {
        close(fd);
        set_error(error, "Could not map the Wayland drag-icon shared memory");
        return false;
    }
    for (int y = 0; y < height; ++y) {
        std::memcpy(
            static_cast<unsigned char *>(mapping) + static_cast<size_t>(y) * target_stride,
            pixels + static_cast<size_t>(y) * stride,
            static_cast<size_t>(target_stride)
        );
    }
    wl_shm_pool *pool = wl_shm_create_pool(context->shm, fd, static_cast<int>(bytes));
    close(fd);
    if (!pool) {
        munmap(mapping, bytes);
        set_error(error, "Could not create the Wayland drag-icon pool");
        return false;
    }
    wl_buffer *buffer = wl_shm_pool_create_buffer(
        pool, 0, width, height, target_stride, WL_SHM_FORMAT_ARGB8888);
    wl_shm_pool_destroy(pool);
    wl_surface *surface = wl_compositor_create_surface(context->compositor);
    if (!buffer || !surface) {
        if (surface) wl_surface_destroy(surface);
        if (buffer) wl_buffer_destroy(buffer);
        munmap(mapping, bytes);
        set_error(error, "Could not create the Wayland drag-icon surface");
        return false;
    }
    wl_surface_attach(surface, buffer, 0, 0);
    wl_surface_damage(surface, 0, 0, width, height);
    wl_surface_commit(surface);
    context->icon_surface = surface;
    context->icon_buffer = buffer;
    context->icon_data = mapping;
    context->icon_data_size = bytes;
    return true;
}

void wayland_finish(WaylandContext *context) {
    if (context->source) wl_data_source_destroy(context->source);
    context->source = nullptr;
    wayland_clear_icon(context);
    context->source_surface = nullptr;
    context->active = false;
    context->text.clear();
    context->uri_list.clear();
}

void source_target(void *, wl_data_source *, const char *) {}

void source_send(void *data, wl_data_source *, const char *mime, int32_t fd) {
    WaylandContext *context = static_cast<WaylandContext *>(data);
    const std::string *payload = nullptr;
    if (mime && std::strcmp(mime, "text/uri-list") == 0 && !context->uri_list.empty()) {
        payload = &context->uri_list;
    } else if (!context->text.empty()) {
        payload = &context->text;
    }
    if (payload) write_all(fd, *payload);
    close(fd);
}

void source_cancelled(void *data, wl_data_source *) {
    wayland_finish(static_cast<WaylandContext *>(data));
}
void source_drop_performed(void *, wl_data_source *) {}
void source_finished(void *data, wl_data_source *) {
    wayland_finish(static_cast<WaylandContext *>(data));
}
void source_action(void *, wl_data_source *, uint32_t) {}

const wl_data_source_listener source_listener = {
    source_target,
    source_send,
    source_cancelled,
    source_drop_performed,
    source_finished,
    source_action,
};

void pointer_enter(
    void *data,
    wl_pointer *,
    uint32_t serial,
    wl_surface *surface,
    wl_fixed_t,
    wl_fixed_t
) {
    WaylandContext *context = static_cast<WaylandContext *>(data);
    context->entered_surface = surface;
    context->serial = serial;
}
void pointer_leave(void *data, wl_pointer *, uint32_t, wl_surface *surface) {
    WaylandContext *context = static_cast<WaylandContext *>(data);
    if (context->entered_surface == surface) context->entered_surface = nullptr;
}
void pointer_motion(void *, wl_pointer *, uint32_t, wl_fixed_t, wl_fixed_t) {}
void pointer_button(void *data, wl_pointer *, uint32_t serial, uint32_t, uint32_t, uint32_t state) {
    WaylandContext *context = static_cast<WaylandContext *>(data);
    if (state == WL_POINTER_BUTTON_STATE_PRESSED) context->serial = serial;
}
void pointer_axis(void *, wl_pointer *, uint32_t, uint32_t, wl_fixed_t) {}
void pointer_frame(void *, wl_pointer *) {}
void pointer_axis_source(void *, wl_pointer *, uint32_t) {}
void pointer_axis_stop(void *, wl_pointer *, uint32_t, uint32_t) {}
void pointer_axis_discrete(void *, wl_pointer *, uint32_t, int32_t) {}

const wl_pointer_listener pointer_listener = {
    pointer_enter,
    pointer_leave,
    pointer_motion,
    pointer_button,
    pointer_axis,
    pointer_frame,
    pointer_axis_source,
    pointer_axis_stop,
    pointer_axis_discrete,
};

void device_offer(void *, wl_data_device *, wl_data_offer *) {}
void device_enter(void *, wl_data_device *, uint32_t, wl_surface *, wl_fixed_t, wl_fixed_t, wl_data_offer *) {}
void device_leave(void *, wl_data_device *) {}
void device_motion(void *, wl_data_device *, uint32_t, wl_fixed_t, wl_fixed_t) {}
void device_drop(void *, wl_data_device *) {}
void device_selection(void *, wl_data_device *, wl_data_offer *) {}

const wl_data_device_listener device_listener = {
    device_offer,
    device_enter,
    device_leave,
    device_motion,
    device_drop,
    device_selection,
};

void registry_global(void *data, wl_registry *registry, uint32_t name, const char *interface, uint32_t version) {
    WaylandContext *context = static_cast<WaylandContext *>(data);
    if (std::strcmp(interface, wl_compositor_interface.name) == 0 && !context->compositor) {
        context->compositor = static_cast<wl_compositor *>(
            wl_registry_bind(registry, name, &wl_compositor_interface, std::min(version, 4u)));
    } else if (std::strcmp(interface, wl_shm_interface.name) == 0 && !context->shm) {
        context->shm = static_cast<wl_shm *>(
            wl_registry_bind(registry, name, &wl_shm_interface, 1));
    } else if (std::strcmp(interface, wl_data_device_manager_interface.name) == 0 && !context->manager) {
        context->manager = static_cast<wl_data_device_manager *>(
            wl_registry_bind(registry, name, &wl_data_device_manager_interface, std::min(version, 3u)));
    } else if (std::strcmp(interface, wl_seat_interface.name) == 0 && !context->seat) {
        context->seat = static_cast<wl_seat *>(
            wl_registry_bind(registry, name, &wl_seat_interface, std::min(version, 5u)));
    } else if (
        std::strcmp(interface, org_kde_kwin_shadow_manager_interface.name) == 0 &&
        !context->shadow_manager
    ) {
        context->shadow_manager = static_cast<org_kde_kwin_shadow_manager *>(
            wl_registry_bind(
                registry,
                name,
                &org_kde_kwin_shadow_manager_interface,
                std::min(version, 2u)));
        std::fprintf(stderr, "[shadow] registry_global bound shadow_manager name=%u version=%u\n",
            name, version);
    }
}
void registry_remove(void *, wl_registry *, uint32_t) {}
const wl_registry_listener registry_listener = {registry_global, registry_remove};

bool wayland_init(WaylandContext *context, wl_display *display, char **error) {
    context->display = display;
    context->registry = wl_display_get_registry(display);
    if (!context->registry) {
        set_error(error, "Could not access the Wayland registry");
        return false;
    }
    wl_registry_add_listener(context->registry, &registry_listener, context);
    if (wl_display_roundtrip(display) < 0 || !context->manager || !context->seat) {
        set_error(error, "Wayland data-device support is unavailable");
        return false;
    }
    context->device = wl_data_device_manager_get_data_device(context->manager, context->seat);
    context->pointer = wl_seat_get_pointer(context->seat);
    if (!context->device || !context->pointer) {
        set_error(error, "Wayland pointer or data device is unavailable");
        return false;
    }
    wl_data_device_add_listener(context->device, &device_listener, context);
    wl_pointer_add_listener(context->pointer, &pointer_listener, context);
    wl_display_flush(display);
    return true;
}

void wayland_destroy_context(WaylandContext *context) {
    wayland_finish(context);
    if (context->pointer) wl_pointer_destroy(context->pointer);
    if (context->device) wl_data_device_destroy(context->device);
    if (context->manager) wl_data_device_manager_destroy(context->manager);
    if (context->seat) wl_seat_destroy(context->seat);
    if (context->shm) wl_shm_destroy(context->shm);
    if (context->compositor) wl_compositor_destroy(context->compositor);
    if (context->shadow_manager) {
        auto *proxy = reinterpret_cast<wl_proxy *>(context->shadow_manager);
        if (wl_proxy_get_version(proxy) >= 2) {
            wl_proxy_marshal_flags(
                proxy, 2, nullptr, wl_proxy_get_version(proxy), WL_MARSHAL_FLAG_DESTROY);
        } else {
            wl_proxy_destroy(proxy);
        }
    }
    if (context->registry) wl_registry_destroy(context->registry);
}

WaylandContext *wayland_acquire(wl_display *display, char **error) {
    for (WaylandContext *context : wayland_contexts) {
        if (context->display == display) {
            context->references += 1;
            return context;
        }
    }
    WaylandContext *context = new WaylandContext();
    if (!wayland_init(context, display, error)) {
        wayland_destroy_context(context);
        delete context;
        return nullptr;
    }
    context->references = 1;
    wayland_contexts.push_back(context);
    return context;
}

void wayland_release(WaylandHandle *handle) {
    WaylandContext *context = handle->context;
    if (!context) return;
    if (context->source_surface == handle->surface) wayland_finish(context);
    handle->context = nullptr;
    handle->surface = nullptr;
    context->references -= 1;
    if (context->references > 0) return;
    wayland_contexts.erase(
        std::remove(wayland_contexts.begin(), wayland_contexts.end(), context),
        wayland_contexts.end()
    );
    wayland_destroy_context(context);
    delete context;
}

bool wayland_start(
    WaylandHandle *handle,
    const char *text,
    const char *uris,
    const unsigned char *icon_pixels,
    int icon_width,
    int icon_height,
    int icon_stride,
    char **error
) {
    WaylandContext *context = handle->context;
    if (!context || !handle->surface) {
        set_error(error, "Wayland drag context is unavailable");
        return false;
    }
    if (!context->serial || context->entered_surface != handle->surface) {
        set_error(error, "No Wayland pointer serial is available for this source window");
        return false;
    }
    wayland_finish(context);
    context->text = text ? text : "";
    context->uri_list = uris ? uris : "";
    context->source_surface = handle->surface;
    context->source = wl_data_device_manager_create_data_source(context->manager);
    if (!context->source) {
        set_error(error, "Could not create a Wayland data source");
        return false;
    }
    wl_data_source_add_listener(context->source, &source_listener, context);
    if (!context->uri_list.empty()) wl_data_source_offer(context->source, "text/uri-list");
    if (!context->text.empty()) {
        wl_data_source_offer(context->source, "text/plain;charset=utf-8");
        wl_data_source_offer(context->source, "text/plain");
        wl_data_source_offer(context->source, "UTF8_STRING");
    }
    wl_data_source_set_actions(context->source, WL_DATA_DEVICE_MANAGER_DND_ACTION_COPY);
    if (!wayland_set_icon(
            context, icon_pixels, icon_width, icon_height, icon_stride, error)) {
        wayland_finish(context);
        return false;
    }
    wl_data_device_start_drag(
        context->device,
        context->source,
        handle->surface,
        context->icon_surface,
        context->serial
    );
    wl_display_flush(context->display);
    context->active = true;
    return true;
}

struct X11Drag {
    Display *display = nullptr;
    Window source = 0;
    Window root = 0;
    Window target = 0;
    bool active = false;
    bool accepted = false;
    std::string text;
    std::string uri_list;
    Atom xdnd_aware = None;
    Atom xdnd_enter = None;
    Atom xdnd_leave = None;
    Atom xdnd_position = None;
    Atom xdnd_status = None;
    Atom xdnd_drop = None;
    Atom xdnd_finished = None;
    Atom xdnd_selection = None;
    Atom xdnd_type_list = None;
    Atom xdnd_action_copy = None;
    Atom text_uri_list = None;
    Atom utf8_string = None;
    Atom text_plain = None;
};

bool has_xdnd_aware(X11Drag *drag, Window window) {
    Atom actual = None;
    int format = 0;
    unsigned long count = 0;
    unsigned long remaining = 0;
    unsigned char *data = nullptr;
    const int status = XGetWindowProperty(
        drag->display, window, drag->xdnd_aware, 0, 1, False, AnyPropertyType,
        &actual, &format, &count, &remaining, &data);
    if (data) XFree(data);
    return status == Success && actual != None && count > 0;
}

Window deepest_window_at_pointer(X11Drag *drag, int *root_x, int *root_y) {
    Window current = drag->root;
    Window root_return = 0;
    Window child = 0;
    int root_px = 0;
    int root_py = 0;
    int local_x = 0;
    int local_y = 0;
    unsigned int mask = 0;
    if (!XQueryPointer(drag->display, drag->root, &root_return, &child,
            &root_px, &root_py, &local_x, &local_y, &mask)) return 0;
    if (root_x) *root_x = root_px;
    if (root_y) *root_y = root_py;
    current = child ? child : drag->root;
    while (current) {
        Window next_root = 0;
        Window next_child = 0;
        if (!XQueryPointer(drag->display, current, &next_root, &next_child,
                &root_px, &root_py, &local_x, &local_y, &mask) || !next_child) break;
        current = next_child;
    }
    return current;
}

Window find_aware_target(X11Drag *drag, int *root_x, int *root_y) {
    Window current = deepest_window_at_pointer(drag, root_x, root_y);
    while (current && current != drag->root) {
        if (current != drag->source && has_xdnd_aware(drag, current)) return current;
        Window root = 0;
        Window parent = 0;
        Window *children = nullptr;
        unsigned int count = 0;
        if (!XQueryTree(drag->display, current, &root, &parent, &children, &count)) break;
        if (children) XFree(children);
        current = parent;
    }
    return 0;
}

void send_client(Display *display, Window target, Atom message, long a, long b, long c, long d, long e) {
    XEvent event{};
    event.xclient.type = ClientMessage;
    event.xclient.display = display;
    event.xclient.window = target;
    event.xclient.message_type = message;
    event.xclient.format = 32;
    event.xclient.data.l[0] = a;
    event.xclient.data.l[1] = b;
    event.xclient.data.l[2] = c;
    event.xclient.data.l[3] = d;
    event.xclient.data.l[4] = e;
    XSendEvent(display, target, False, NoEventMask, &event);
}

std::vector<Atom> offered_types(X11Drag *drag) {
    std::vector<Atom> result;
    if (!drag->uri_list.empty()) result.push_back(drag->text_uri_list);
    if (!drag->text.empty()) {
        result.push_back(drag->utf8_string);
        result.push_back(drag->text_plain);
    }
    return result;
}

void x11_leave(X11Drag *drag) {
    if (drag->target) send_client(drag->display, drag->target, drag->xdnd_leave, drag->source, 0, 0, 0, 0);
    drag->target = 0;
    drag->accepted = false;
}

void x11_update_target(X11Drag *drag) {
    if (!drag->active) return;
    int root_x = 0;
    int root_y = 0;
    const Window next = find_aware_target(drag, &root_x, &root_y);
    if (next != drag->target) {
        x11_leave(drag);
        drag->target = next;
        if (next) {
            const std::vector<Atom> types = offered_types(drag);
            if (types.size() > 3) {
                XChangeProperty(drag->display, drag->source, drag->xdnd_type_list, XA_ATOM, 32,
                    PropModeReplace, reinterpret_cast<const unsigned char *>(types.data()),
                    static_cast<int>(types.size()));
            }
            send_client(
                drag->display, next, drag->xdnd_enter, drag->source,
                (5L << 24) | (types.size() > 3 ? 1L : 0L),
                types.size() > 0 ? types[0] : None,
                types.size() > 1 ? types[1] : None,
                types.size() > 2 ? types[2] : None);
        }
    }
    if (drag->target) {
        const long packed = ((root_x & 0xffff) << 16) | (root_y & 0xffff);
        send_client(drag->display, drag->target, drag->xdnd_position, drag->source, 0,
            packed, CurrentTime, drag->xdnd_action_copy);
        XFlush(drag->display);
    }
}

void x11_finish(X11Drag *drag) {
    x11_leave(drag);
    XSetSelectionOwner(drag->display, drag->xdnd_selection, None, CurrentTime);
    XDeleteProperty(drag->display, drag->source, drag->xdnd_type_list);
    XFlush(drag->display);
    drag->active = false;
    drag->text.clear();
    drag->uri_list.clear();
}

bool x11_init(X11Drag *drag, Display *display, Window source) {
    drag->display = display;
    drag->source = source;
    drag->root = DefaultRootWindow(display);
    auto atom = [display](const char *name) { return XInternAtom(display, name, False); };
    drag->xdnd_aware = atom("XdndAware");
    drag->xdnd_enter = atom("XdndEnter");
    drag->xdnd_leave = atom("XdndLeave");
    drag->xdnd_position = atom("XdndPosition");
    drag->xdnd_status = atom("XdndStatus");
    drag->xdnd_drop = atom("XdndDrop");
    drag->xdnd_finished = atom("XdndFinished");
    drag->xdnd_selection = atom("XdndSelection");
    drag->xdnd_type_list = atom("XdndTypeList");
    drag->xdnd_action_copy = atom("XdndActionCopy");
    drag->text_uri_list = atom("text/uri-list");
    drag->utf8_string = atom("UTF8_STRING");
    drag->text_plain = atom("text/plain;charset=utf-8");
    return true;
}

bool x11_start(X11Drag *drag, const char *text, const char *uris) {
    x11_finish(drag);
    drag->text = text ? text : "";
    drag->uri_list = uris ? uris : "";
    drag->active = true;
    XSetSelectionOwner(drag->display, drag->xdnd_selection, drag->source, CurrentTime);
    x11_update_target(drag);
    return true;
}

void x11_release(X11Drag *drag) {
    if (!drag->active) return;
    x11_update_target(drag);
    if (drag->target && drag->accepted) {
        send_client(drag->display, drag->target, drag->xdnd_drop, drag->source, 0, CurrentTime, 0, 0);
        XFlush(drag->display);
    } else {
        x11_finish(drag);
    }
}

void x11_selection_request(X11Drag *drag, const XSelectionRequestEvent &request) {
    XEvent reply{};
    reply.xselection.type = SelectionNotify;
    reply.xselection.display = request.display;
    reply.xselection.requestor = request.requestor;
    reply.xselection.selection = request.selection;
    reply.xselection.target = request.target;
    reply.xselection.time = request.time;
    reply.xselection.property = None;
    const std::string *payload = nullptr;
    if (request.target == drag->text_uri_list && !drag->uri_list.empty()) payload = &drag->uri_list;
    else if ((request.target == drag->utf8_string || request.target == drag->text_plain) && !drag->text.empty()) payload = &drag->text;
    if (payload) {
        const Atom property = request.property == None ? request.target : request.property;
        XChangeProperty(drag->display, request.requestor, property, request.target, 8,
            PropModeReplace, reinterpret_cast<const unsigned char *>(payload->data()),
            static_cast<int>(payload->size()));
        reply.xselection.property = property;
    }
    XSendEvent(drag->display, request.requestor, False, 0, &reply);
    XFlush(drag->display);
}

void x11_handle(X11Drag *drag, const XEvent &event) {
    if (event.type == SelectionRequest && event.xselectionrequest.selection == drag->xdnd_selection) {
        x11_selection_request(drag, event.xselectionrequest);
    } else if (event.type == ClientMessage && event.xclient.message_type == drag->xdnd_status) {
        drag->accepted = (event.xclient.data.l[1] & 1L) != 0;
    } else if (event.type == ClientMessage && event.xclient.message_type == drag->xdnd_finished) {
        x11_finish(drag);
    } else if (event.type == SelectionClear && event.xselectionclear.selection == drag->xdnd_selection) {
        x11_finish(drag);
    }
}

enum class Backend { Unsupported, Wayland, X11 };
struct Drag {
    Backend backend = Backend::Unsupported;
    WaylandHandle wayland;
    X11Drag x11;
};

std::vector<Drag *> x11_drags;

bool SDLCALL handle_x11_event(void *, XEvent *event) {
    if (!event) return true;
    for (Drag *drag : x11_drags) {
        if (drag && drag->backend == Backend::X11) x11_handle(&drag->x11, *event);
    }
    return true;
}

void register_x11_drag(Drag *drag) {
    if (x11_drags.empty()) SDL_SetX11EventHook(handle_x11_event, nullptr);
    x11_drags.push_back(drag);
}

void unregister_x11_drag(Drag *drag) {
    x11_drags.erase(std::remove(x11_drags.begin(), x11_drags.end(), drag), x11_drags.end());
    if (x11_drags.empty()) SDL_SetX11EventHook(nullptr, nullptr);
}

} // namespace

extern "C" {

void *kdrag_create(void *raw_window, char **error_message) {
    if (error_message) *error_message = nullptr;
    // A drop target may close an offered MIME pipe before the source finishes writing. Treat that
    // as an ordinary EPIPE result instead of letting SIGPIPE terminate the entire application.
    std::signal(SIGPIPE, SIG_IGN);
    SDL_Window *window = static_cast<SDL_Window *>(raw_window);
    if (!window) {
        set_error(error_message, "SDL window is null");
        return nullptr;
    }
    SDL_PropertiesID properties = SDL_GetWindowProperties(window);
    if (!properties) {
        set_error(error_message, SDL_GetError());
        return nullptr;
    }
    Drag *drag = new Drag();
    wl_display *wayland_display = static_cast<wl_display *>(SDL_GetPointerProperty(
        properties,
        SDL_PROP_WINDOW_WAYLAND_DISPLAY_POINTER,
        nullptr
    ));
    wl_surface *wayland_surface = static_cast<wl_surface *>(SDL_GetPointerProperty(
        properties,
        SDL_PROP_WINDOW_WAYLAND_SURFACE_POINTER,
        nullptr
    ));
    Display *x11_display = static_cast<Display *>(SDL_GetPointerProperty(
        properties,
        SDL_PROP_WINDOW_X11_DISPLAY_POINTER,
        nullptr
    ));
    Window x11_window = static_cast<Window>(SDL_GetNumberProperty(
        properties,
        SDL_PROP_WINDOW_X11_WINDOW_NUMBER,
        0
    ));
    if (wayland_display && wayland_surface) {
        drag->backend = Backend::Wayland;
        drag->wayland.context = wayland_acquire(wayland_display, error_message);
        drag->wayland.surface = wayland_surface;
        if (!drag->wayland.context || !drag->wayland.surface) {
            wayland_release(&drag->wayland);
            delete drag;
            return nullptr;
        }
    } else if (x11_display && x11_window != None) {
        drag->backend = Backend::X11;
        x11_init(&drag->x11, x11_display, x11_window);
        register_x11_drag(drag);
    } else {
        set_error(error_message, "Outgoing drag is supported only by SDL Wayland and X11 backends");
        delete drag;
        return nullptr;
    }
    return drag;
}

void kdrag_destroy(void *raw) {
    Drag *drag = static_cast<Drag *>(raw);
    if (!drag) return;
    if (drag->backend == Backend::Wayland) wayland_release(&drag->wayland);
    else if (drag->backend == Backend::X11) {
        unregister_x11_drag(drag);
        x11_finish(&drag->x11);
    }
    delete drag;
}

int kdrag_start(
    void *raw,
    const char *text,
    const char *uri_list,
    const unsigned char *icon_pixels,
    int icon_width,
    int icon_height,
    int icon_stride,
    char **error_message
) {
    if (error_message) *error_message = nullptr;
    Drag *drag = static_cast<Drag *>(raw);
    if (!drag || ((!text || !*text) && (!uri_list || !*uri_list))) {
        set_error(error_message, "Drag transfer data is empty");
        return 0;
    }
    if (drag->backend == Backend::Wayland) {
        return wayland_start(
            &drag->wayland,
            text,
            uri_list,
            icon_pixels,
            icon_width,
            icon_height,
            icon_stride,
            error_message
        ) ? 1 : 0;
    }
    if (drag->backend == Backend::X11) return x11_start(&drag->x11, text, uri_list) ? 1 : 0;
    set_error(error_message, "Outgoing drag backend is unavailable");
    return 0;
}

void kdrag_pointer_motion(void *raw) {
    Drag *drag = static_cast<Drag *>(raw);
    if (drag && drag->backend == Backend::X11) x11_update_target(&drag->x11);
}

void kdrag_pointer_release(void *raw) {
    Drag *drag = static_cast<Drag *>(raw);
    if (drag && drag->backend == Backend::X11) x11_release(&drag->x11);
}

void kdrag_handle_syswm(void *raw, const void *raw_message) {
    (void)raw;
    (void)raw_message;
}

int kdrag_active(void *raw) {
    Drag *drag = static_cast<Drag *>(raw);
    if (!drag) return 0;
    if (drag->backend == Backend::Wayland) {
        return drag->wayland.context && drag->wayland.context->active ? 1 : 0;
    }
    if (drag->backend == Backend::X11) return drag->x11.active ? 1 : 0;
    return 0;
}

int kplatform_window_set_transparent(void *raw_window, int transparent) {
    SDL_Window *window = static_cast<SDL_Window *>(raw_window);
    if (!window) return 0;
    const bool has_transparent_buffer =
        (SDL_GetWindowFlags(window) & SDL_WINDOW_TRANSPARENT) != 0;
    return transparent ? (has_transparent_buffer ? 1 : 0) : 1;
}

int kplatform_window_allow_drawing_inside_title_bar(void *raw_window, int allow) {
    SDL_Window *window = static_cast<SDL_Window *>(raw_window);
    if (!window) return 0;

    // Linux compositors own decorated title bars, so there is no portable way to retain the
    // system title bar while extending client content into it. Use a client-decorated window
    // instead; Compose content and WindowDraggableArea provide the custom title bar.
    if (!SDL_SetWindowBordered(window, allow == 0)) return 0;
    return allow ? 1 : 0;
}

int kplatform_window_set_shadow(void *raw_window, int enabled) {
    SDL_Window *window = static_cast<SDL_Window *>(raw_window);
    std::fprintf(stderr, "[shadow] kplatform_window_set_shadow(window=%p, enabled=%d)\n",
        static_cast<void *>(window), enabled);
    if (!window) return 0;
    if (!enabled) {
        remove_window_shadow(window);
        return 1;
    }
    // Reinstalling an existing shadow tears the compositor's custom shadow down and creates a new
    // one. Keep the installed shadow unless the caller explicitly requests a refresh.
    auto iterator = std::find_if(
        window_shadows.begin(), window_shadows.end(),
        [window](const auto &entry) { return entry.first == window; });
    if (iterator != window_shadows.end()) return 1;
    return install_window_shadow(window) ? 1 : 0;
}

int kplatform_window_refresh_shadow(void *raw_window) {
    SDL_Window *window = static_cast<SDL_Window *>(raw_window);
    if (!window) return 0;
    auto iterator = std::find_if(
        window_shadows.begin(), window_shadows.end(),
        [window](const auto &entry) { return entry.first == window; });
    if (iterator == window_shadows.end()) return install_window_shadow(window) ? 1 : 0;

    // KWin applies the surface shadow state before handling the surface commit that removes the
    // server-side decoration. Wait until that commit has been processed, then recreate the shadow
    // so KWin evaluates it while the window is already client-decorated.
    WaylandContext *context = iterator->second->context;
    if (!context || wl_display_roundtrip(context->display) < 0) return 0;
    remove_window_shadow(window);
    return install_window_shadow(window) ? 1 : 0;
}

int kplatform_window_set_title_bar_color(
    void *raw_window,
    int background_r,
    int background_g,
    int background_b,
    int foreground_r,
    int foreground_g,
    int foreground_b
) {
    SDL_Window *window = static_cast<SDL_Window *>(raw_window);
    if (!window) return 0;

    auto has_component = [](int value) { return value >= 0 && value <= 255; };
    const bool has_background =
        has_component(background_r) && has_component(background_g) && has_component(background_b);
    const bool has_foreground =
        has_component(foreground_r) && has_component(foreground_g) && has_component(foreground_b);

    // "dark" tells GTK-themed window managers whether to render the server-side
    // title bar with a dark or light variant.
    int luminance = 128;
    if (has_background) {
        luminance = (2126 * background_r + 7152 * background_g + 722 * background_b) / 10000;
    }
    std::string value = has_background || has_foreground ? (luminance < 128 ? "dark" : "light") : "";

    const SDL_PropertiesID properties = SDL_GetWindowProperties(window);
    Display *x11_display = static_cast<Display *>(SDL_GetPointerProperty(
        properties, SDL_PROP_WINDOW_X11_DISPLAY_POINTER, nullptr));
    if (x11_display) {
        const Window x11_window = static_cast<Window>(SDL_GetNumberProperty(
            properties, SDL_PROP_WINDOW_X11_WINDOW_NUMBER, 0));
        if (x11_window != None) {
            const Atom target = XInternAtom(x11_display, "_GTK_THEME_VARIANT", False);
            const Atom type = XInternAtom(x11_display, "UTF8_STRING", False);
            XChangeProperty(
                x11_display, x11_window, target, type, 8, PropModeReplace,
                reinterpret_cast<const unsigned char *>(value.c_str()),
                static_cast<int>(value.size()));
            XFlush(x11_display);
            return 1;
        }
    }

    // The Wayland client-side title bar is drawn by Compose, so there is no
    // compositor-side color to override here.
    return 0;
}

} // extern "C"
