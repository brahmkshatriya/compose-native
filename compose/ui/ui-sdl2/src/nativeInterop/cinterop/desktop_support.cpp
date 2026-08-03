#include "linux_desktop.h"

#include <dbus/dbus.h>

#include <cstdlib>
#include <cstring>
#include <string>
#include <utility>
#include <vector>

namespace {

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
