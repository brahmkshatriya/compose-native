#include "linux_drag.h"

#include <SDL.h>
#include <SDL_syswm.h>
#include <X11/Xatom.h>
#include <X11/Xlib.h>
#include <wayland-client.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>

#include <algorithm>
#include <cerrno>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

namespace {

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

struct WaylandHandle {
    WaylandContext *context = nullptr;
    wl_surface *surface = nullptr;
};

std::vector<WaylandContext *> wayland_contexts;

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

} // namespace

extern "C" {

void *kdrag_create(void *raw_window, char **error_message) {
    if (error_message) *error_message = nullptr;
    SDL_Window *window = static_cast<SDL_Window *>(raw_window);
    if (!window) {
        set_error(error_message, "SDL window is null");
        return nullptr;
    }
    SDL_SysWMinfo info;
    SDL_VERSION(&info.version);
    if (!SDL_GetWindowWMInfo(window, &info)) {
        set_error(error_message, SDL_GetError());
        return nullptr;
    }
    Drag *drag = new Drag();
    if (info.subsystem == SDL_SYSWM_WAYLAND) {
        drag->backend = Backend::Wayland;
        drag->wayland.context = wayland_acquire(info.info.wl.display, error_message);
        drag->wayland.surface = info.info.wl.surface;
        if (!drag->wayland.context || !drag->wayland.surface) {
            wayland_release(&drag->wayland);
            delete drag;
            return nullptr;
        }
    } else if (info.subsystem == SDL_SYSWM_X11) {
        drag->backend = Backend::X11;
        x11_init(&drag->x11, info.info.x11.display, info.info.x11.window);
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
    else if (drag->backend == Backend::X11) x11_finish(&drag->x11);
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
    Drag *drag = static_cast<Drag *>(raw);
    const SDL_SysWMmsg *message = static_cast<const SDL_SysWMmsg *>(raw_message);
    if (!drag || !message || drag->backend != Backend::X11 || message->subsystem != SDL_SYSWM_X11) return;
    x11_handle(&drag->x11, message->msg.x11.event);
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
    SDL_SysWMinfo info;
    SDL_VERSION(&info.version);
    if (!SDL_GetWindowWMInfo(window, &info)) return 0;
    if (info.subsystem == SDL_SYSWM_WAYLAND) {
        if (!info.info.wl.surface) return 0;
        // A null opaque region tells the compositor to honor the alpha channel of every buffer.
        wl_surface_set_opaque_region(info.info.wl.surface, nullptr);
        wl_display_flush(info.info.wl.display);
        return 1;
    }
    if (info.subsystem == SDL_SYSWM_X11) {
        if (!transparent) return 1;
        XWindowAttributes attributes{};
        if (!XGetWindowAttributes(info.info.x11.display, info.info.x11.window, &attributes) ||
            attributes.depth != 32) {
            return 0;
        }
        XSetWindowBackgroundPixmap(info.info.x11.display, info.info.x11.window, None);
        XFlush(info.info.x11.display);
        return 1;
    }
    return transparent ? 0 : 1;
}

} // extern "C"
