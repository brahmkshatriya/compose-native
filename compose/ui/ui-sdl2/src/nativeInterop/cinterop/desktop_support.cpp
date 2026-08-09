#include "linux_desktop.h"

#include <dbus/dbus.h>
#include <SDL3/SDL.h>

#include <atomic>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <poll.h>
#include <string>
#include <thread>
#include <unistd.h>
#include <utility>
#include <vector>

namespace {

constexpr const char *portal_service = "org.freedesktop.portal.Desktop";
constexpr const char *portal_path = "/org/freedesktop/portal/desktop";
constexpr const char *portal_settings_interface = "org.freedesktop.portal.Settings";
constexpr const char *appearance_namespace = "org.freedesktop.appearance";
constexpr const char *color_scheme_key = "color-scheme";
constexpr const char *accent_color_key = "accent-color";
constexpr const char *notification_service = "org.freedesktop.Notifications";
constexpr const char *notification_path = "/org/freedesktop/Notifications";
constexpr const char *notification_interface = "org.freedesktop.Notifications";
constexpr const char *progress_service = "org.kde.JobViewServer";
constexpr const char *progress_server_path = "/JobViewServer";
constexpr const char *progress_server_interface = "org.kde.JobViewServer";
constexpr const char *progress_view_interface = "org.kde.JobViewV2";

DBusConnection *connection = nullptr;
bool event_matches_installed = false;

char *copy_string(const char *value) {
    if (!value) return nullptr;
    const size_t length = std::strlen(value) + 1;
    char *copy = static_cast<char *>(std::malloc(length));
    if (copy) std::memcpy(copy, value, length);
    return copy;
}

void set_error(char **output, const char *message) {
    if (output) *output = copy_string(message ? message : "Unknown D-Bus error");
}

DBusConnection *session_bus(char **error_message = nullptr) {
    if (error_message) *error_message = nullptr;
    if (connection) return connection;
    dbus_threads_init_default();
    DBusError error;
    dbus_error_init(&error);
    connection = dbus_bus_get_private(DBUS_BUS_SESSION, &error);
    if (!connection) {
        set_error(error_message, error.message ? error.message : "No D-Bus session bus");
        dbus_error_free(&error);
        return nullptr;
    }
    dbus_connection_set_exit_on_disconnect(connection, FALSE);
    dbus_error_free(&error);
    return connection;
}

bool name_has_owner(const char *name) {
    DBusError error;
    dbus_error_init(&error);
    DBusConnection *bus = session_bus();
    if (!bus) return false;
    const dbus_bool_t result = dbus_bus_name_has_owner(bus, name, &error);
    const bool failed = dbus_error_is_set(&error);
    dbus_error_free(&error);
    return !failed && result;
}

struct Hint {
    enum Type { Byte, Int32, UInt32, Int64, UInt64, Double, Boolean, String } type;
    std::string name;
    uint64_t unsigned_value = 0;
    int64_t signed_value = 0;
    double double_value = 0;
    std::string string_value;

    Hint(Type type, const char *name) : type(type), name(name ? name : "") {}
};

struct NotificationBuilder {
    std::string application_name;
    std::string title;
    std::string body;
    std::string icon_name;
    uint32_t replaces_id;
    int timeout_millis;
    std::vector<std::pair<std::string, std::string>> actions;
    std::vector<Hint> hints;
};

bool append_hint(DBusMessageIter *dictionary, const Hint &hint) {
    const char *signature = nullptr;
    int dbus_type = DBUS_TYPE_INVALID;
    switch (hint.type) {
        case Hint::Byte: signature = "y"; dbus_type = DBUS_TYPE_BYTE; break;
        case Hint::Int32: signature = "i"; dbus_type = DBUS_TYPE_INT32; break;
        case Hint::UInt32: signature = "u"; dbus_type = DBUS_TYPE_UINT32; break;
        case Hint::Int64: signature = "x"; dbus_type = DBUS_TYPE_INT64; break;
        case Hint::UInt64: signature = "t"; dbus_type = DBUS_TYPE_UINT64; break;
        case Hint::Double: signature = "d"; dbus_type = DBUS_TYPE_DOUBLE; break;
        case Hint::Boolean: signature = "b"; dbus_type = DBUS_TYPE_BOOLEAN; break;
        case Hint::String: signature = "s"; dbus_type = DBUS_TYPE_STRING; break;
    }
    DBusMessageIter entry;
    DBusMessageIter variant;
    const char *name = hint.name.c_str();
    if (!dbus_message_iter_open_container(dictionary, DBUS_TYPE_DICT_ENTRY, nullptr, &entry)) return false;
    if (!dbus_message_iter_append_basic(&entry, DBUS_TYPE_STRING, &name)) return false;
    if (!dbus_message_iter_open_container(&entry, DBUS_TYPE_VARIANT, signature, &variant)) return false;
    dbus_bool_t boolean_value = hint.unsigned_value != 0;
    uint8_t byte_value = static_cast<uint8_t>(hint.unsigned_value);
    int32_t int32_value = static_cast<int32_t>(hint.signed_value);
    uint32_t uint32_value = static_cast<uint32_t>(hint.unsigned_value);
    int64_t int64_value = hint.signed_value;
    uint64_t uint64_value = hint.unsigned_value;
    double double_value = hint.double_value;
    const char *string_value = hint.string_value.c_str();
    const void *value =
        hint.type == Hint::Byte ? static_cast<const void *>(&byte_value) :
        hint.type == Hint::Int32 ? static_cast<const void *>(&int32_value) :
        hint.type == Hint::UInt32 ? static_cast<const void *>(&uint32_value) :
        hint.type == Hint::Int64 ? static_cast<const void *>(&int64_value) :
        hint.type == Hint::UInt64 ? static_cast<const void *>(&uint64_value) :
        hint.type == Hint::Double ? static_cast<const void *>(&double_value) :
        hint.type == Hint::Boolean ? static_cast<const void *>(&boolean_value) :
        static_cast<const void *>(&string_value);
    if (!dbus_message_iter_append_basic(&variant, dbus_type, value)) return false;
    if (!dbus_message_iter_close_container(&entry, &variant)) return false;
    return dbus_message_iter_close_container(dictionary, &entry);
}

DBusMessage *call_with_reply(DBusMessage *message, char **error_message) {
    DBusConnection *bus = session_bus(error_message);
    if (!bus) {
        dbus_message_unref(message);
        return nullptr;
    }
    DBusError error;
    dbus_error_init(&error);
    DBusMessage *reply = dbus_connection_send_with_reply_and_block(bus, message, 5000, &error);
    dbus_message_unref(message);
    if (!reply) set_error(error_message, error.message ? error.message : "D-Bus service did not reply");
    dbus_error_free(&error);
    return reply;
}

bool send_no_reply(DBusMessage *message, char **error_message) {
    DBusConnection *bus = session_bus(error_message);
    if (!bus) {
        dbus_message_unref(message);
        return false;
    }
    dbus_message_set_no_reply(message, TRUE);
    dbus_uint32_t serial = 0;
    const bool sent = dbus_connection_send(bus, message, &serial);
    dbus_message_unref(message);
    if (!sent) set_error(error_message, "Could not queue D-Bus message");
    return sent;
}

DBusMessage *progress_message(const char *path, const char *method) {
    return dbus_message_new_method_call(progress_service, path, progress_view_interface, method);
}

bool unwrap_variant(DBusMessageIter *value) {
    while (dbus_message_iter_get_arg_type(value) == DBUS_TYPE_VARIANT) {
        DBusMessageIter nested;
        dbus_message_iter_recurse(value, &nested);
        *value = nested;
    }
    return dbus_message_iter_get_arg_type(value) != DBUS_TYPE_INVALID;
}

bool read_uint32_variant(DBusMessageIter value, uint32_t *result) {
    if (!unwrap_variant(&value) ||
        dbus_message_iter_get_arg_type(&value) != DBUS_TYPE_UINT32) return false;
    dbus_uint32_t raw = 0;
    dbus_message_iter_get_basic(&value, &raw);
    if (result) *result = raw <= 2u ? raw : 0u;
    return true;
}

bool read_accent_color_variant(DBusMessageIter value, uint32_t *result) {
    if (!unwrap_variant(&value) ||
        dbus_message_iter_get_arg_type(&value) != DBUS_TYPE_STRUCT) return false;
    DBusMessageIter components;
    dbus_message_iter_recurse(&value, &components);
    double channels[3] = {};
    for (int index = 0; index < 3; ++index) {
        if (dbus_message_iter_get_arg_type(&components) != DBUS_TYPE_DOUBLE) return false;
        dbus_message_iter_get_basic(&components, &channels[index]);
        if (!std::isfinite(channels[index]) || channels[index] < 0.0 || channels[index] > 1.0) {
            return false;
        }
        if (index < 2 && !dbus_message_iter_next(&components)) return false;
    }
    const uint32_t red = static_cast<uint32_t>(std::lround(channels[0] * 255.0));
    const uint32_t green = static_cast<uint32_t>(std::lround(channels[1] * 255.0));
    const uint32_t blue = static_cast<uint32_t>(std::lround(channels[2] * 255.0));
    if (result) *result = 0x01000000u | (red << 16u) | (green << 8u) | blue;
    return true;
}

using PortalValueReader = bool (*)(DBusMessageIter, uint32_t *);

uint32_t read_portal_setting(
    DBusConnection *bus,
    const char *key,
    PortalValueReader reader
) {
    if (!bus) return 0;
    const char *methods[] = {"ReadOne", "Read"};
    for (const char *method : methods) {
        DBusMessage *message = dbus_message_new_method_call(
            portal_service, portal_path, portal_settings_interface, method);
        if (!message) continue;
        const char *name_space = appearance_namespace;
        if (!dbus_message_append_args(
                message,
                DBUS_TYPE_STRING,
                &name_space,
                DBUS_TYPE_STRING,
                &key,
                DBUS_TYPE_INVALID)) {
            dbus_message_unref(message);
            continue;
        }
        DBusError error;
        dbus_error_init(&error);
        DBusMessage *reply =
            dbus_connection_send_with_reply_and_block(bus, message, 3000, &error);
        dbus_message_unref(message);
        dbus_error_free(&error);
        if (!reply) continue;
        DBusMessageIter value;
        uint32_t result = 0;
        const bool parsed = dbus_message_iter_init(reply, &value) && reader(value, &result);
        dbus_message_unref(reply);
        if (parsed) return result;
    }
    return 0;
}

uint32_t read_portal_color_scheme(DBusConnection *bus) {
    return read_portal_setting(bus, color_scheme_key, read_uint32_variant);
}

uint32_t read_portal_accent_color(DBusConnection *bus) {
    return read_portal_setting(bus, accent_color_key, read_accent_color_variant);
}

struct SystemThemeObserver {
    DBusConnection *bus = nullptr;
    int bus_fd = -1;
    int wake_pipe[2] = {-1, -1};
    uint32_t color_scheme_event_type = 0;
    uint32_t accent_color_event_type = 0;
    std::atomic<uint32_t> current{0};
    std::atomic<uint32_t> accent{0};
    std::atomic<bool> running{true};
    std::thread worker;

    void push_event(uint32_t event_type, uint32_t value) {
        SDL_Event event{};
        event.type = event_type;
        event.user.type = event_type;
        event.user.code = static_cast<Sint32>(value);
        (void)SDL_PushEvent(&event);
    }

    void notify_color_scheme(uint32_t value) {
        value = value <= 2u ? value : 0u;
        if (current.exchange(value) == value) return;
        push_event(color_scheme_event_type, value);
    }

    void notify_accent_color(uint32_t value) {
        if ((value & 0x01000000u) == 0u) value = 0u;
        if (accent.exchange(value) == value) return;
        push_event(accent_color_event_type, value);
    }

    void refresh() {
        notify_color_scheme(read_portal_color_scheme(bus));
        notify_accent_color(read_portal_accent_color(bus));
    }

    bool drain_messages() {
        if (!dbus_connection_read_write(bus, 0)) {
            notify_color_scheme(0);
            notify_accent_color(0);
            return false;
        }
        while (DBusMessage *message = dbus_connection_pop_message(bus)) {
            if (dbus_message_is_signal(message, portal_settings_interface, "SettingChanged")) {
                DBusMessageIter arguments;
                if (dbus_message_iter_init(message, &arguments) &&
                    dbus_message_iter_get_arg_type(&arguments) == DBUS_TYPE_STRING) {
                    const char *name_space = nullptr;
                    dbus_message_iter_get_basic(&arguments, &name_space);
                    if (dbus_message_iter_next(&arguments) &&
                        dbus_message_iter_get_arg_type(&arguments) == DBUS_TYPE_STRING) {
                        const char *key = nullptr;
                        dbus_message_iter_get_basic(&arguments, &key);
                        if (name_space && key &&
                            std::strcmp(name_space, appearance_namespace) == 0 &&
                            dbus_message_iter_next(&arguments)) {
                            uint32_t value = 0;
                            if (std::strcmp(key, color_scheme_key) == 0 &&
                                read_uint32_variant(arguments, &value)) {
                                notify_color_scheme(value);
                            } else if (std::strcmp(key, accent_color_key) == 0 &&
                                       read_accent_color_variant(arguments, &value)) {
                                notify_accent_color(value);
                            }
                        }
                    }
                }
            } else if (dbus_message_is_signal(
                           message, DBUS_INTERFACE_DBUS, "NameOwnerChanged")) {
                DBusError error;
                dbus_error_init(&error);
                const char *name = nullptr;
                const char *old_owner = nullptr;
                const char *new_owner = nullptr;
                if (dbus_message_get_args(
                        message,
                        &error,
                        DBUS_TYPE_STRING,
                        &name,
                        DBUS_TYPE_STRING,
                        &old_owner,
                        DBUS_TYPE_STRING,
                        &new_owner,
                        DBUS_TYPE_INVALID) &&
                    name && std::strcmp(name, portal_service) == 0) {
                    if (new_owner && new_owner[0] != '\0') refresh();
                    else {
                        notify_color_scheme(0);
                        notify_accent_color(0);
                    }
                }
                dbus_error_free(&error);
            }
            dbus_message_unref(message);
        }
        return true;
    }

    void run() {
        if (!drain_messages()) return;
        pollfd descriptors[2] = {
            {bus_fd, POLLIN | POLLERR | POLLHUP, 0},
            {wake_pipe[0], POLLIN | POLLERR | POLLHUP, 0},
        };
        while (running.load()) {
            descriptors[0].revents = 0;
            descriptors[1].revents = 0;
            const int result = poll(descriptors, 2, -1);
            if (result < 0) continue;
            if (descriptors[1].revents != 0) break;
            if (descriptors[0].revents != 0 && !drain_messages()) break;
        }
    }
};

bool add_event_matches() {
    if (event_matches_installed) return true;
    DBusConnection *bus = session_bus();
    if (!bus) return false;
    DBusError error;
    dbus_error_init(&error);
    dbus_bus_add_match(bus, "type='signal',interface='org.freedesktop.Notifications'", &error);
    if (!dbus_error_is_set(&error)) {
        dbus_bus_add_match(bus, "type='signal',interface='org.kde.JobViewV2'", &error);
    }
    const bool success = !dbus_error_is_set(&error);
    dbus_error_free(&error);
    if (success) event_matches_installed = true;
    return success;
}

} // namespace

extern "C" {

void *kld_system_theme_observer_create(
    uint32_t color_scheme_event_type,
    uint32_t accent_color_event_type
) {
    dbus_threads_init_default();
    DBusError error;
    dbus_error_init(&error);
    DBusConnection *bus = dbus_bus_get_private(DBUS_BUS_SESSION, &error);
    dbus_error_free(&error);
    if (!bus) return nullptr;
    dbus_connection_set_exit_on_disconnect(bus, FALSE);

    auto *observer = new SystemThemeObserver();
    observer->bus = bus;
    observer->color_scheme_event_type = color_scheme_event_type;
    observer->accent_color_event_type = accent_color_event_type;
    if (!dbus_connection_get_unix_fd(bus, &observer->bus_fd) ||
        pipe(observer->wake_pipe) != 0) {
        dbus_connection_close(bus);
        dbus_connection_unref(bus);
        delete observer;
        return nullptr;
    }

    DBusError match_error;
    dbus_error_init(&match_error);
    dbus_bus_add_match(
        bus,
        "type='signal',sender='org.freedesktop.portal.Desktop',"
        "path='/org/freedesktop/portal/desktop',"
        "interface='org.freedesktop.portal.Settings',member='SettingChanged',"
        "arg0='org.freedesktop.appearance'",
        &match_error);
    if (!dbus_error_is_set(&match_error)) {
        dbus_bus_add_match(
            bus,
            "type='signal',sender='org.freedesktop.DBus',"
            "interface='org.freedesktop.DBus',member='NameOwnerChanged',"
            "arg0='org.freedesktop.portal.Desktop'",
            &match_error);
    }
    const bool matched = !dbus_error_is_set(&match_error);
    dbus_error_free(&match_error);
    if (!matched) {
        close(observer->wake_pipe[0]);
        close(observer->wake_pipe[1]);
        dbus_connection_close(bus);
        dbus_connection_unref(bus);
        delete observer;
        return nullptr;
    }

    dbus_connection_flush(bus);
    observer->current.store(read_portal_color_scheme(bus));
    observer->accent.store(read_portal_accent_color(bus));
    observer->worker = std::thread([observer] { observer->run(); });
    return observer;
}

int kld_system_theme_observer_current(void *raw) {
    auto *observer = static_cast<SystemThemeObserver *>(raw);
    return observer ? static_cast<int>(observer->current.load()) : 0;
}

uint32_t kld_system_theme_observer_accent(void *raw) {
    auto *observer = static_cast<SystemThemeObserver *>(raw);
    return observer ? observer->accent.load() : 0u;
}

void kld_system_theme_observer_destroy(void *raw) {
    auto *observer = static_cast<SystemThemeObserver *>(raw);
    if (!observer) return;
    observer->running.store(false);
    if (observer->wake_pipe[1] >= 0) {
        const char wake = 1;
        (void)write(observer->wake_pipe[1], &wake, 1);
    }
    if (observer->worker.joinable()) observer->worker.join();
    if (observer->wake_pipe[0] >= 0) close(observer->wake_pipe[0]);
    if (observer->wake_pipe[1] >= 0) close(observer->wake_pipe[1]);
    if (observer->bus) {
        dbus_connection_close(observer->bus);
        dbus_connection_unref(observer->bus);
    }
    delete observer;
}

int kld_notifications_supported(void) { return name_has_owner(notification_service); }

char *kld_notification_capabilities(char **error_message) {
    if (error_message) *error_message = nullptr;
    DBusMessage *message = dbus_message_new_method_call(
        notification_service, notification_path, notification_interface, "GetCapabilities");
    if (!message) {
        set_error(error_message, "Could not allocate a D-Bus capabilities message");
        return nullptr;
    }
    DBusMessage *reply = call_with_reply(message, error_message);
    if (!reply) return nullptr;
    DBusMessageIter arguments;
    DBusMessageIter values;
    std::string joined;
    if (dbus_message_iter_init(reply, &arguments) && dbus_message_iter_get_arg_type(&arguments) == DBUS_TYPE_ARRAY) {
        dbus_message_iter_recurse(&arguments, &values);
        while (dbus_message_iter_get_arg_type(&values) == DBUS_TYPE_STRING) {
            const char *value = nullptr;
            dbus_message_iter_get_basic(&values, &value);
            if (!joined.empty()) joined += '\n';
            if (value) joined += value;
            dbus_message_iter_next(&values);
        }
    }
    dbus_message_unref(reply);
    return copy_string(joined.c_str());
}

void *kld_notification_create(
    const char *application_name, const char *title, const char *body, const char *icon_name,
    uint32_t replaces_id, int timeout_millis
) {
    return new NotificationBuilder{
        application_name ? application_name : "Compose",
        title ? title : "",
        body ? body : "",
        icon_name ? icon_name : "",
        replaces_id,
        timeout_millis,
        {},
        {},
    };
}

int kld_notification_add_action(void *raw, const char *id, const char *label) {
    if (!raw || !id || !label) return 0;
    static_cast<NotificationBuilder *>(raw)->actions.emplace_back(id, label);
    return 1;
}

static int add_hint(void *raw, Hint hint) {
    if (!raw || hint.name.empty()) return 0;
    static_cast<NotificationBuilder *>(raw)->hints.push_back(std::move(hint));
    return 1;
}

int kld_notification_add_hint_byte(void *raw, const char *name, uint8_t value) {
    Hint hint(Hint::Byte, name); hint.unsigned_value = value; return add_hint(raw, std::move(hint));
}
int kld_notification_add_hint_int32(void *raw, const char *name, int32_t value) {
    Hint hint(Hint::Int32, name); hint.signed_value = value; return add_hint(raw, std::move(hint));
}
int kld_notification_add_hint_uint32(void *raw, const char *name, uint32_t value) {
    Hint hint(Hint::UInt32, name); hint.unsigned_value = value; return add_hint(raw, std::move(hint));
}
int kld_notification_add_hint_int64(void *raw, const char *name, int64_t value) {
    Hint hint(Hint::Int64, name); hint.signed_value = value; return add_hint(raw, std::move(hint));
}
int kld_notification_add_hint_uint64(void *raw, const char *name, uint64_t value) {
    Hint hint(Hint::UInt64, name); hint.unsigned_value = value; return add_hint(raw, std::move(hint));
}
int kld_notification_add_hint_double(void *raw, const char *name, double value) {
    Hint hint(Hint::Double, name); hint.double_value = value; return add_hint(raw, std::move(hint));
}
int kld_notification_add_hint_bool(void *raw, const char *name, int value) {
    Hint hint(Hint::Boolean, name); hint.unsigned_value = value != 0; return add_hint(raw, std::move(hint));
}
int kld_notification_add_hint_string(void *raw, const char *name, const char *value) {
    Hint hint(Hint::String, name); hint.string_value = value ? value : ""; return add_hint(raw, std::move(hint));
}

uint32_t kld_notification_send(void *raw, char **error_message) {
    if (error_message) *error_message = nullptr;
    if (!raw) {
        set_error(error_message, "Notification builder is null");
        return 0;
    }
    NotificationBuilder &builder = *static_cast<NotificationBuilder *>(raw);
    DBusMessage *message = dbus_message_new_method_call(
        notification_service, notification_path, notification_interface, "Notify");
    if (!message) {
        set_error(error_message, "Could not allocate a D-Bus notification message");
        return 0;
    }
    const char *app = builder.application_name.c_str();
    const char *icon = builder.icon_name.c_str();
    const char *title = builder.title.c_str();
    const char *body = builder.body.c_str();
    dbus_uint32_t replaces_id = builder.replaces_id;
    dbus_int32_t timeout = builder.timeout_millis;
    DBusMessageIter arguments;
    DBusMessageIter actions;
    DBusMessageIter hints;
    dbus_message_iter_init_append(message, &arguments);
    bool ok =
        dbus_message_iter_append_basic(&arguments, DBUS_TYPE_STRING, &app) &&
        dbus_message_iter_append_basic(&arguments, DBUS_TYPE_UINT32, &replaces_id) &&
        dbus_message_iter_append_basic(&arguments, DBUS_TYPE_STRING, &icon) &&
        dbus_message_iter_append_basic(&arguments, DBUS_TYPE_STRING, &title) &&
        dbus_message_iter_append_basic(&arguments, DBUS_TYPE_STRING, &body) &&
        dbus_message_iter_open_container(&arguments, DBUS_TYPE_ARRAY, "s", &actions);
    for (const auto &action : builder.actions) {
        const char *id = action.first.c_str();
        const char *label = action.second.c_str();
        ok = ok && dbus_message_iter_append_basic(&actions, DBUS_TYPE_STRING, &id);
        ok = ok && dbus_message_iter_append_basic(&actions, DBUS_TYPE_STRING, &label);
    }
    ok = ok && dbus_message_iter_close_container(&arguments, &actions);
    ok = ok && dbus_message_iter_open_container(&arguments, DBUS_TYPE_ARRAY, "{sv}", &hints);
    for (const Hint &hint : builder.hints) ok = ok && append_hint(&hints, hint);
    ok = ok && dbus_message_iter_close_container(&arguments, &hints);
    ok = ok && dbus_message_iter_append_basic(&arguments, DBUS_TYPE_INT32, &timeout);
    if (!ok) {
        dbus_message_unref(message);
        set_error(error_message, "Could not build a D-Bus notification message");
        return 0;
    }
    DBusMessage *reply = call_with_reply(message, error_message);
    if (!reply) return 0;
    DBusError error;
    dbus_error_init(&error);
    dbus_uint32_t id = 0;
    if (!dbus_message_get_args(reply, &error, DBUS_TYPE_UINT32, &id, DBUS_TYPE_INVALID)) {
        set_error(error_message, error.message ? error.message : "Invalid notification service reply");
        id = 0;
    }
    dbus_error_free(&error);
    dbus_message_unref(reply);
    add_event_matches();
    return id;
}

void kld_notification_destroy(void *builder) { delete static_cast<NotificationBuilder *>(builder); }

int kld_notification_close(uint32_t id, char **error_message) {
    DBusMessage *message = dbus_message_new_method_call(
        notification_service, notification_path, notification_interface, "CloseNotification");
    if (!message) { set_error(error_message, "Could not allocate CloseNotification message"); return 0; }
    dbus_uint32_t value = id;
    if (!dbus_message_append_args(message, DBUS_TYPE_UINT32, &value, DBUS_TYPE_INVALID)) {
        dbus_message_unref(message); set_error(error_message, "Could not build CloseNotification message"); return 0;
    }
    DBusMessage *reply = call_with_reply(message, error_message);
    if (!reply) return 0;
    dbus_message_unref(reply);
    return 1;
}

int kld_progress_supported(void) { return name_has_owner(progress_service); }

char *kld_progress_start(
    const char *application_name, const char *icon_name, int capabilities, char **error_message
) {
    DBusMessage *message = dbus_message_new_method_call(
        progress_service, progress_server_path, progress_server_interface, "requestView");
    if (!message) { set_error(error_message, "Could not allocate JobView request"); return nullptr; }
    const char *app = application_name ? application_name : "Compose";
    const char *icon = icon_name ? icon_name : "";
    dbus_int32_t flags = capabilities;
    if (!dbus_message_append_args(message,
        DBUS_TYPE_STRING, &app, DBUS_TYPE_STRING, &icon, DBUS_TYPE_INT32, &flags, DBUS_TYPE_INVALID)) {
        dbus_message_unref(message); set_error(error_message, "Could not build JobView request"); return nullptr;
    }
    DBusMessage *reply = call_with_reply(message, error_message);
    if (!reply) return nullptr;
    DBusError error;
    dbus_error_init(&error);
    const char *path = nullptr;
    char *result = nullptr;
    if (dbus_message_get_args(reply, &error, DBUS_TYPE_OBJECT_PATH, &path, DBUS_TYPE_INVALID)) {
        result = copy_string(path);
        add_event_matches();
    } else {
        set_error(error_message, error.message ? error.message : "Invalid JobView reply");
    }
    dbus_error_free(&error);
    dbus_message_unref(reply);
    return result;
}

int kld_progress_update(
    const char *path, uint64_t total_bytes, uint64_t processed_bytes, uint64_t bytes_per_second,
    uint64_t elapsed_millis, uint32_t percent, const char *info, char **error_message
) {
    struct Call { const char *method; int type1; const void *value1; int type2; const void *value2; };
    const char *bytes = "bytes";
    const char *message_text = info ? info : "";
    Call calls[] = {
        {"setTotalAmount", DBUS_TYPE_UINT64, &total_bytes, DBUS_TYPE_STRING, &bytes},
        {"setProcessedAmount", DBUS_TYPE_UINT64, &processed_bytes, DBUS_TYPE_STRING, &bytes},
        {"setPercent", DBUS_TYPE_UINT32, &percent, DBUS_TYPE_INVALID, nullptr},
        {"setSpeed", DBUS_TYPE_UINT64, &bytes_per_second, DBUS_TYPE_INVALID, nullptr},
        {"setElapsedTime", DBUS_TYPE_UINT64, &elapsed_millis, DBUS_TYPE_INVALID, nullptr},
        {"setInfoMessage", DBUS_TYPE_STRING, &message_text, DBUS_TYPE_INVALID, nullptr},
    };
    for (const Call &call : calls) {
        DBusMessage *message = progress_message(path, call.method);
        if (!message) { set_error(error_message, "Could not allocate JobView update"); return 0; }
        const bool appended = call.type2 == DBUS_TYPE_INVALID
            ? dbus_message_append_args(message, call.type1, call.value1, DBUS_TYPE_INVALID)
            : dbus_message_append_args(message, call.type1, call.value1, call.type2, call.value2, DBUS_TYPE_INVALID);
        if (!appended) { dbus_message_unref(message); set_error(error_message, "Could not build JobView update"); return 0; }
        if (!send_no_reply(message, error_message)) return 0;
    }
    dbus_connection_flush(connection);
    return 1;
}

int kld_progress_terminate(const char *path, const char *error, char **error_message) {
    DBusMessage *message = progress_message(path, "terminate");
    if (!message) { set_error(error_message, "Could not allocate JobView termination"); return 0; }
    const char *value = error ? error : "";
    if (!dbus_message_append_args(message, DBUS_TYPE_STRING, &value, DBUS_TYPE_INVALID)) {
        dbus_message_unref(message); set_error(error_message, "Could not build JobView termination"); return 0;
    }
    const int sent = send_no_reply(message, error_message);
    if (sent) dbus_connection_flush(connection);
    return sent;
}

int kld_poll_event(uint32_t *id, uint32_t *reason, char **value) {
    if (id) *id = 0;
    if (reason) *reason = 0;
    if (value) *value = nullptr;
    DBusConnection *bus = session_bus();
    if (!bus || !add_event_matches()) return 0;
    dbus_connection_read_write(bus, 0);
    while (DBusMessage *message = dbus_connection_pop_message(bus)) {
        int result = 0;
        DBusError error;
        dbus_error_init(&error);
        if (dbus_message_is_signal(message, notification_interface, "ActionInvoked")) {
            dbus_uint32_t notification_id = 0;
            const char *action = nullptr;
            if (dbus_message_get_args(message, &error,
                DBUS_TYPE_UINT32, &notification_id, DBUS_TYPE_STRING, &action, DBUS_TYPE_INVALID)) {
                if (id) *id = notification_id;
                if (value) *value = copy_string(action);
                result = 1;
            }
        } else if (dbus_message_is_signal(message, notification_interface, "NotificationClosed")) {
            dbus_uint32_t notification_id = 0, close_reason = 0;
            if (dbus_message_get_args(message, &error,
                DBUS_TYPE_UINT32, &notification_id, DBUS_TYPE_UINT32, &close_reason, DBUS_TYPE_INVALID)) {
                if (id) *id = notification_id;
                if (reason) *reason = close_reason;
                result = 2;
            }
        } else if (dbus_message_has_interface(message, progress_view_interface)) {
            const char *member = dbus_message_get_member(message);
            if (member && value) *value = copy_string(dbus_message_get_path(message));
            if (member && std::strcmp(member, "cancelRequested") == 0) result = 3;
            else if (member && std::strcmp(member, "suspendRequested") == 0) result = 4;
            else if (member && std::strcmp(member, "resumeRequested") == 0) result = 5;
        }
        dbus_error_free(&error);
        dbus_message_unref(message);
        if (result != 0) return result;
        if (value && *value) { std::free(*value); *value = nullptr; }
    }
    return 0;
}

void kld_free_string(char *value) { std::free(value); }

void kld_shutdown(void) {
    if (!connection) return;
    dbus_connection_close(connection);
    dbus_connection_unref(connection);
    connection = nullptr;
    event_matches_installed = false;
}

} // extern "C"
