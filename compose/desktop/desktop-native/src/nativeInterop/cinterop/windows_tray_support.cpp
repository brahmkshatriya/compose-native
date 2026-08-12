#include "include/native_tray.h"

#include <SDL3/SDL.h>

#include <cstdlib>
#include <cstring>
#include <deque>
#include <string>
#include <unordered_map>
#include <vector>

namespace {

struct MenuItem {
    int parent_id = 0;
    int id = 0;
    int type = 0;
    std::string label;
    bool enabled = true;
    bool checked = false;
};

struct TrayEvent {
    int type = 0;
    int item_id = 0;
};

struct Tray {
    SDL_Tray *handle = nullptr;
    SDL_TrayMenu *root = nullptr;
    std::vector<MenuItem> model;
    std::unordered_map<int, SDL_TrayEntry *> entries;
    std::unordered_map<int, SDL_TrayMenu *> submenus;
    std::vector<std::pair<Tray *, int> *> callback_data;
    std::deque<TrayEvent> events;
};

char *copy_string(const char *value) {
    if (!value) return nullptr;
    const size_t length = std::strlen(value) + 1;
    char *result = static_cast<char *>(std::malloc(length));
    if (result) std::memcpy(result, value, length);
    return result;
}

void set_error(char **output, const char *message) {
    if (output) *output = copy_string(message ? message : "Unknown tray error");
}

SDL_Surface *make_icon(const unsigned char *pixels, int width, int height, int stride) {
    if (!pixels || width <= 0 || height <= 0 || stride < width * 4) return nullptr;
    return SDL_CreateSurfaceFrom(
        width,
        height,
        SDL_PIXELFORMAT_ARGB8888,
        const_cast<unsigned char *>(pixels),
        stride
    );
}

void SDLCALL on_entry(void *userdata, SDL_TrayEntry *) {
    auto *pair = static_cast<std::pair<Tray *, int> *>(userdata);
    if (pair && pair->first) pair->first->events.push_back({4, pair->second});
}

void clear_menu(Tray *tray) {
    if (!tray || !tray->root) return;
    int count = 0;
    const SDL_TrayEntry **entries = SDL_GetTrayEntries(tray->root, &count);
    while (entries && count > 0) {
        SDL_RemoveTrayEntry(const_cast<SDL_TrayEntry *>(entries[0]));
        entries = SDL_GetTrayEntries(tray->root, &count);
    }
    tray->entries.clear();
    tray->submenus.clear();
    for (auto *data : tray->callback_data) delete data;
    tray->callback_data.clear();
}

SDL_TrayEntryFlags flags_for(const MenuItem &item) {
    SDL_TrayEntryFlags flags =
        item.type == 1 ? SDL_TRAYENTRY_SUBMENU :
        (item.type == 3 || item.type == 4) ? SDL_TRAYENTRY_CHECKBOX :
        SDL_TRAYENTRY_BUTTON;
    if (!item.enabled) flags |= SDL_TRAYENTRY_DISABLED;
    if ((item.type == 3 || item.type == 4) && item.checked) flags |= SDL_TRAYENTRY_CHECKED;
    return flags;
}

} // namespace

extern "C" {

int kld_tray_supported(void) { return 1; }

void *kld_tray_create(
    const char *,
    const char *tooltip,
    const unsigned char *pixels,
    int width,
    int height,
    int stride,
    char **error_message
) {
    if (error_message) *error_message = nullptr;
    SDL_Surface *icon = make_icon(pixels, width, height, stride);
    Tray *tray = new Tray();
    tray->handle = SDL_CreateTray(icon, tooltip);
    if (icon) SDL_DestroySurface(icon);
    if (!tray->handle) {
        set_error(error_message, SDL_GetError());
        delete tray;
        return nullptr;
    }
    tray->root = SDL_CreateTrayMenu(tray->handle);
    if (!tray->root) {
        set_error(error_message, SDL_GetError());
        SDL_DestroyTray(tray->handle);
        delete tray;
        return nullptr;
    }
    return tray;
}

int kld_tray_update(
    void *raw,
    const char *,
    const char *tooltip,
    const unsigned char *pixels,
    int width,
    int height,
    int stride,
    char **error_message
) {
    if (error_message) *error_message = nullptr;
    Tray *tray = static_cast<Tray *>(raw);
    if (!tray || !tray->handle) {
        set_error(error_message, "Tray is not initialized");
        return 0;
    }
    SDL_Surface *icon = make_icon(pixels, width, height, stride);
    SDL_SetTrayIcon(tray->handle, icon);
    SDL_SetTrayTooltip(tray->handle, tooltip);
    if (icon) SDL_DestroySurface(icon);
    return 1;
}

void kld_tray_menu_clear(void *raw) {
    Tray *tray = static_cast<Tray *>(raw);
    if (!tray) return;
    tray->model.clear();
}

int kld_tray_menu_add(
    void *raw,
    int parent_id,
    int item_id,
    int item_type,
    const char *label,
    int enabled,
    int checked
) {
    Tray *tray = static_cast<Tray *>(raw);
    if (!tray) return 0;
    tray->model.push_back(
        {parent_id, item_id, item_type, label ? label : "", enabled != 0, checked != 0}
    );
    return 1;
}

int kld_tray_menu_commit(void *raw) {
    Tray *tray = static_cast<Tray *>(raw);
    if (!tray || !tray->root) return 0;
    clear_menu(tray);
    // The Kotlin model is emitted parent-before-child. Rebuild it atomically so stale callbacks
    // cannot address a newly composed menu.
    for (const MenuItem &item : tray->model) {
        SDL_TrayMenu *parent = item.parent_id == 0 ? tray->root : tray->submenus[item.parent_id];
        if (!parent) return 0;
        SDL_TrayEntry *entry = SDL_InsertTrayEntryAt(
            parent,
            -1,
            item.type == 2 ? nullptr : item.label.c_str(),
            flags_for(item)
        );
        if (!entry) return 0;
        tray->entries[item.id] = entry;
        if (item.type == 1) {
            SDL_TrayMenu *submenu = SDL_CreateTraySubmenu(entry);
            if (!submenu) return 0;
            tray->submenus[item.id] = submenu;
        } else if (item.type != 2) {
            auto *callback_data = new std::pair<Tray *, int>(tray, item.id);
            tray->callback_data.push_back(callback_data);
            SDL_SetTrayEntryCallback(entry, on_entry, callback_data);
        }
    }
    return 1;
}

int kld_tray_poll(void *raw, int *event_type, int *item_id) {
    Tray *tray = static_cast<Tray *>(raw);
    if (!tray || tray->events.empty()) return 0;
    TrayEvent event = tray->events.front();
    tray->events.pop_front();
    if (event_type) *event_type = event.type;
    if (item_id) *item_id = event.item_id;
    return 1;
}

void kld_tray_destroy(void *raw) {
    Tray *tray = static_cast<Tray *>(raw);
    if (!tray) return;
    if (tray->handle) SDL_DestroyTray(tray->handle);
    for (auto *data : tray->callback_data) delete data;
    delete tray;
}

} // extern "C"
