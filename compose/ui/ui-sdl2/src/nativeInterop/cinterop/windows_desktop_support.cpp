#include "include/linux_desktop.h"

#include <SDL3/SDL.h>
#include <dwmapi.h>
#include <shellapi.h>
#include <windows.h>

#include <algorithm>
#include <cstdlib>
#include <cstring>
#include <deque>
#include <string>
#include <unordered_set>

namespace {

struct ThemeObserver {
    uint32_t color_scheme_event_type = 0;
    uint32_t accent_color_event_type = 0;
};

struct NotificationBuilder {
    std::string application_name;
    std::string title;
    std::string message;
    std::string icon_name;
    uint32_t replaces_id = 0;
    int timeout_millis = -1;
    int urgency = 0;
    int progress = -1;
};

struct DesktopEvent {
    int type = 0;
    uint32_t id = 0;
    uint32_t reason = 0;
    std::string value;
};

uint32_t next_notification_id = 1;
std::unordered_set<uint32_t> active_notification_ids;
std::deque<DesktopEvent> desktop_events;
HWND notification_window = nullptr;

char *copy_string(const char *value) {
    if (!value) return nullptr;
    const size_t length = std::strlen(value) + 1;
    char *result = static_cast<char *>(std::malloc(length));
    if (result) std::memcpy(result, value, length);
    return result;
}

std::wstring utf8_to_wide(const char *value) {
    if (!value || !*value) return {};
    const int length = MultiByteToWideChar(CP_UTF8, 0, value, -1, nullptr, 0);
    if (length <= 1) return {};
    std::wstring result(static_cast<size_t>(length), L'\0');
    MultiByteToWideChar(CP_UTF8, 0, value, -1, result.data(), length);
    result.resize(static_cast<size_t>(length - 1));
    return result;
}

template <size_t Size>
void copy_wide(wchar_t (&destination)[Size], const std::wstring &value) {
    const size_t count = std::min(value.size(), Size - 1);
    std::copy_n(value.data(), count, destination);
    destination[count] = L'\0';
}

HWND ensure_notification_window() {
    if (notification_window && IsWindow(notification_window)) return notification_window;
    notification_window = CreateWindowExW(
        0,
        L"STATIC",
        L"Compose Native notifications",
        0,
        0,
        0,
        0,
        0,
        HWND_MESSAGE,
        nullptr,
        GetModuleHandleW(nullptr),
        nullptr
    );
    return notification_window;
}

HICON notification_icon(int urgency) {
    const wchar_t *resource = urgency >= 2 ? IDI_ERROR : urgency == 1 ? IDI_WARNING : IDI_INFORMATION;
    return LoadIconW(nullptr, resource);
}

bool publish_notification(uint32_t id, const NotificationBuilder &builder, bool replacing) {
    HWND window = ensure_notification_window();
    if (!window) return false;

    NOTIFYICONDATAW data{};
    data.cbSize = sizeof(data);
    data.hWnd = window;
    data.uID = id;
    data.uFlags = NIF_ICON | NIF_TIP | NIF_INFO;
    data.hIcon = notification_icon(builder.urgency);
    data.dwInfoFlags =
        builder.urgency >= 2 ? NIIF_ERROR : builder.urgency == 1 ? NIIF_WARNING : NIIF_INFO;
    if (builder.timeout_millis > 0) data.uTimeout = static_cast<UINT>(builder.timeout_millis);

    copy_wide(data.szTip, utf8_to_wide(builder.application_name.c_str()));
    copy_wide(data.szInfoTitle, utf8_to_wide(builder.title.c_str()));
    std::string message = builder.message;
    if (builder.progress >= 0) {
        if (!message.empty()) message += "\n";
        message += std::to_string(builder.progress) + "%";
    }
    copy_wide(data.szInfo, utf8_to_wide(message.c_str()));

    return Shell_NotifyIconW(replacing ? NIM_MODIFY : NIM_ADD, &data) != FALSE;
}

bool remove_notification(uint32_t id) {
    if (active_notification_ids.erase(id) == 0) return true;
    NOTIFYICONDATAW data{};
    data.cbSize = sizeof(data);
    data.hWnd = ensure_notification_window();
    data.uID = id;
    return data.hWnd && Shell_NotifyIconW(NIM_DELETE, &data) != FALSE;
}

int current_theme() {
    switch (SDL_GetSystemTheme()) {
        case SDL_SYSTEM_THEME_DARK:
            return 1;
        case SDL_SYSTEM_THEME_LIGHT:
            return 2;
        default:
            return 0;
    }
}

uint32_t current_accent() {
    DWORD color = 0;
    BOOL opaque = FALSE;
    if (FAILED(DwmGetColorizationColor(&color, &opaque))) return 0;
    // DwmGetColorizationColor returns AARRGGBB. Bit 24 is reserved by the Kotlin ABI as the
    // presence flag, so only carry RGB across the boundary.
    return 0x01000000u | (static_cast<uint32_t>(color) & 0x00ffffffu);
}

bool SDLCALL watch_system_theme(void *userdata, SDL_Event *event) {
    ThemeObserver *observer = static_cast<ThemeObserver *>(userdata);
    if (!observer || !event || event->type != SDL_EVENT_SYSTEM_THEME_CHANGED) return true;

    SDL_Event color_event{};
    color_event.type = observer->color_scheme_event_type;
    color_event.user.code = current_theme();
    SDL_PushEvent(&color_event);

    SDL_Event accent_event{};
    accent_event.type = observer->accent_color_event_type;
    accent_event.user.code = static_cast<Sint32>(current_accent());
    SDL_PushEvent(&accent_event);
    return true;
}

} // namespace

extern "C" {

void *kld_system_theme_observer_create(
    uint32_t color_scheme_event_type,
    uint32_t accent_color_event_type
) {
    ThemeObserver *observer = new ThemeObserver();
    observer->color_scheme_event_type = color_scheme_event_type;
    observer->accent_color_event_type = accent_color_event_type;
    SDL_AddEventWatch(watch_system_theme, observer);
    return observer;
}

int kld_system_theme_observer_current(void *) { return current_theme(); }

uint32_t kld_system_theme_observer_accent(void *) { return current_accent(); }

void kld_system_theme_observer_destroy(void *raw) {
    ThemeObserver *observer = static_cast<ThemeObserver *>(raw);
    if (!observer) return;
    SDL_RemoveEventWatch(watch_system_theme, observer);
    delete observer;
}

int kld_notifications_supported(void) { return ensure_notification_window() ? 1 : 0; }

char *kld_notification_capabilities(char **error_message) {
    if (error_message) *error_message = nullptr;
    if (!ensure_notification_window()) {
        if (error_message) *error_message = copy_string("Could not create the notification host window");
        return nullptr;
    }
    return copy_string("body\nicon-static\npersistence\n");
}

void *kld_notification_create(
    const char *application_name,
    const char *title,
    const char *message,
    const char *icon_name,
    uint32_t replaces_id,
    int timeout_millis
) {
    auto *builder = new NotificationBuilder();
    builder->application_name = application_name ? application_name : "Compose";
    builder->title = title ? title : "";
    builder->message = message ? message : "";
    builder->icon_name = icon_name ? icon_name : "";
    builder->replaces_id = replaces_id;
    builder->timeout_millis = timeout_millis;
    return builder;
}

int kld_notification_add_action(void *, const char *, const char *) { return 1; }

int kld_notification_add_hint_byte(void *raw, const char *name, uint8_t value) {
    auto *builder = static_cast<NotificationBuilder *>(raw);
    if (!builder) return 0;
    if (name && std::strcmp(name, "urgency") == 0) builder->urgency = value;
    return 1;
}

int kld_notification_add_hint_int32(void *raw, const char *name, int32_t value) {
    auto *builder = static_cast<NotificationBuilder *>(raw);
    if (!builder) return 0;
    if (name && std::strcmp(name, "value") == 0) {
        builder->progress = std::clamp(static_cast<int>(value), 0, 100);
    }
    return 1;
}

int kld_notification_add_hint_uint32(void *, const char *, uint32_t) { return 1; }
int kld_notification_add_hint_int64(void *, const char *, int64_t) { return 1; }
int kld_notification_add_hint_uint64(void *, const char *, uint64_t) { return 1; }
int kld_notification_add_hint_double(void *, const char *, double) { return 1; }
int kld_notification_add_hint_bool(void *, const char *, int) { return 1; }
int kld_notification_add_hint_string(void *, const char *, const char *) { return 1; }

uint32_t kld_notification_send(void *raw, char **error_message) {
    if (error_message) *error_message = nullptr;
    auto *builder = static_cast<NotificationBuilder *>(raw);
    if (!builder) {
        if (error_message) *error_message = copy_string("Notification builder is null");
        return 0;
    }
    uint32_t id = builder->replaces_id;
    const bool replacing = id != 0 && active_notification_ids.count(id) != 0;
    if (id == 0) {
        do {
            id = next_notification_id++;
        } while (id == 0 || active_notification_ids.count(id) != 0);
    }
    if (!publish_notification(id, *builder, replacing)) {
        if (error_message) *error_message = copy_string("Shell_NotifyIconW rejected the notification");
        return 0;
    }
    active_notification_ids.insert(id);
    return id;
}

void kld_notification_destroy(void *raw) {
    delete static_cast<NotificationBuilder *>(raw);
}

int kld_notification_close(uint32_t id, char **error_message) {
    if (error_message) *error_message = nullptr;
    if (!remove_notification(id)) {
        if (error_message) *error_message = copy_string("Could not remove the Windows notification");
        return 0;
    }
    desktop_events.push_back({2, id, 3, {}});
    return 1;
}
int kld_progress_supported(void) { return 0; }
char *kld_progress_start(const char *, const char *, int, char **) { return nullptr; }
int kld_progress_update(const char *, uint64_t, uint64_t, uint64_t, uint64_t, uint32_t, const char *, char **) {
    return 0;
}
int kld_progress_terminate(const char *, const char *, char **) { return 0; }
int kld_poll_event(uint32_t *id, uint32_t *reason, char **value) {
    if (value) *value = nullptr;
    if (desktop_events.empty()) return 0;
    DesktopEvent event = desktop_events.front();
    desktop_events.pop_front();
    if (id) *id = event.id;
    if (reason) *reason = event.reason;
    if (value && !event.value.empty()) *value = copy_string(event.value.c_str());
    return event.type;
}

void kld_free_string(char *value) { std::free(value); }

void kld_shutdown(void) {
    const auto ids = active_notification_ids;
    for (uint32_t id : ids) remove_notification(id);
    desktop_events.clear();
    if (notification_window) DestroyWindow(notification_window);
    notification_window = nullptr;
}

} // extern "C"
