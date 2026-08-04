#include "linux_atspi.h"

#include <dbus/dbus.h>

#include <algorithm>
#include <chrono>
#include <clocale>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <functional>
#include <map>
#include <memory>
#include <set>
#include <string>
#include <utility>
#include <vector>

namespace {

constexpr const char *a11y_bus_service = "org.a11y.Bus";
constexpr const char *a11y_bus_path = "/org/a11y/bus";
constexpr const char *a11y_bus_interface = "org.a11y.Bus";
constexpr const char *registry_service = "org.a11y.atspi.Registry";
constexpr const char *registry_path = "/org/a11y/atspi/accessible/root";
constexpr const char *socket_interface = "org.a11y.atspi.Socket";
constexpr const char *accessible_interface = "org.a11y.atspi.Accessible";
constexpr const char *application_interface = "org.a11y.atspi.Application";
constexpr const char *component_interface = "org.a11y.atspi.Component";
constexpr const char *action_interface = "org.a11y.atspi.Action";
constexpr const char *text_interface = "org.a11y.atspi.Text";
constexpr const char *value_interface = "org.a11y.atspi.Value";
constexpr const char *cache_interface = "org.a11y.atspi.Cache";
constexpr const char *object_event_interface = "org.a11y.atspi.Event.Object";
constexpr const char *properties_interface = "org.freedesktop.DBus.Properties";
constexpr const char *introspectable_interface = "org.freedesktop.DBus.Introspectable";
constexpr const char *peer_interface = "org.freedesktop.DBus.Peer";
constexpr const char *root_path = "/org/a11y/atspi/accessible/root";
constexpr const char *accessible_prefix = "/org/a11y/atspi/accessible";
constexpr const char *cache_path = "/org/a11y/atspi/cache";
constexpr const char *null_path = "/org/a11y/atspi/null";
constexpr uint32_t interface_version = 1;

/* AT-SPI public ABI role values used by the Compose mapping. */
constexpr uint32_t role_application = 75;
constexpr uint32_t role_frame = 23;
constexpr uint32_t role_unknown = 67;

/* AT-SPI state values are encoded as bits in an array of uint32 values. */
constexpr uint32_t state_active = 1;
constexpr uint32_t state_enabled = 8;
constexpr uint32_t state_focusable = 11;
constexpr uint32_t state_focused = 12;
constexpr uint32_t state_resizable = 21;
constexpr uint32_t state_sensitive = 24;
constexpr uint32_t state_showing = 25;
constexpr uint32_t state_visible = 30;

struct Action {
    int32_t callback_id = -1;
    std::string name;
    std::string description;
    std::string key_binding;
};

struct Node {
    int32_t id = 0;
    int32_t parent_id = -1;
    uint32_t role = role_unknown;
    std::string path;
    std::string name;
    std::string description;
    std::string accessible_id;
    std::string text;
    uint64_t states = 0;
    int32_t x = 0;
    int32_t y = 0;
    int32_t width = 0;
    int32_t height = 0;
    int32_t selection_start = 0;
    int32_t selection_end = 0;
    std::vector<int32_t> children;
    std::vector<Action> actions;
    bool has_value = false;
    double minimum = 0.0;
    double maximum = 0.0;
    double current = 0.0;
    double increment = 0.0;
    int32_t value_action_id = -1;
};

struct Window {
    uint64_t serial = 0;
    std::string path;
    std::string title;
    bool visible = true;
    bool focused = false;
    int32_t screen_x = 0;
    int32_t screen_y = 0;
    int32_t width = 1;
    int32_t height = 1;
    void *callback_context = nullptr;
    KldAtspiActionCallback callback = nullptr;
    std::map<int32_t, Node> nodes;
    std::map<int32_t, Node> pending_nodes;
    std::vector<int32_t> root_nodes;
};

enum class ObjectKind { Missing, Application, Window, Node };

struct Object {
    ObjectKind kind = ObjectKind::Missing;
    Window *window = nullptr;
    Node *node = nullptr;
};

DBusConnection *accessibility_bus = nullptr;
std::chrono::steady_clock::time_point next_connection_attempt{};
std::string unique_name;
std::string accessibility_bus_address;
int32_t application_id = 0;
uint64_t next_window_serial = 1;
std::vector<std::unique_ptr<Window>> windows;

std::string safe(const char *value) { return value ? value : ""; }

uint64_t state_bit(uint32_t state) {
    return state < 64 ? (uint64_t{1} << state) : 0;
}

uint64_t application_states() {
    return state_bit(state_enabled) | state_bit(state_sensitive) | state_bit(state_showing) |
           state_bit(state_visible);
}

uint64_t window_states(const Window &window) {
    uint64_t states = state_bit(state_enabled) | state_bit(state_sensitive) |
                      state_bit(state_focusable) | state_bit(state_resizable);
    if (window.visible) states |= state_bit(state_showing) | state_bit(state_visible);
    if (window.focused) states |= state_bit(state_active) | state_bit(state_focused);
    return states;
}

const char *role_name(uint32_t role) {
    switch (role) {
        case 7: return "check box";
        case 11: return "combo box";
        case 16: return "dialog";
        case 23: return "frame";
        case 27: return "image";
        case 29: return "label";
        case 31: return "list";
        case 32: return "list item";
        case 37: return "page tab";
        case 39: return "panel";
        case 40: return "password text";
        case 42: return "progress bar";
        case 43: return "push button";
        case 44: return "radio button";
        case 49: return "scroll pane";
        case 51: return "slider";
        case 61: return "text";
        case 67: return "unknown";
        case 69: return "window";
        case 75: return "application";
        case 79: return "entry";
        case 83: return "heading";
        case 99: return "grouping";
        case 130: return "switch";
        default: return "unknown";
    }
}

Window *window_from_raw(void *raw) { return static_cast<Window *>(raw); }

Window *find_window_by_path(const std::string &path) {
    for (auto &window : windows) {
        if (window->path == path || path.rfind(window->path + "/", 0) == 0) return window.get();
    }
    return nullptr;
}

Object find_object(const char *raw_path) {
    const std::string path = safe(raw_path);
    if (path == root_path) return {ObjectKind::Application, nullptr, nullptr};
    Window *window = find_window_by_path(path);
    if (!window) return {};
    if (path == window->path) return {ObjectKind::Window, window, nullptr};
    for (auto &[id, node] : window->nodes) {
        if (node.path == path) return {ObjectKind::Node, window, &node};
    }
    return {};
}

std::string object_path(const Object &object) {
    switch (object.kind) {
        case ObjectKind::Application: return root_path;
        case ObjectKind::Window: return object.window ? object.window->path : null_path;
        case ObjectKind::Node: return object.node ? object.node->path : null_path;
        default: return null_path;
    }
}

std::string object_name(const Object &object) {
    switch (object.kind) {
        case ObjectKind::Application:
            return windows.empty() ? "Compose" : windows.front()->title;
        case ObjectKind::Window: return object.window->title;
        case ObjectKind::Node: return object.node->name;
        default: return "";
    }
}

std::string object_description(const Object &object) {
    return object.kind == ObjectKind::Node && object.node ? object.node->description : "";
}

std::string object_accessible_id(const Object &object) {
    if (object.kind == ObjectKind::Application) return "compose-application";
    if (object.kind == ObjectKind::Window && object.window) {
        return "compose-window-" + std::to_string(object.window->serial);
    }
    return object.node ? object.node->accessible_id : "";
}

uint32_t object_role(const Object &object) {
    switch (object.kind) {
        case ObjectKind::Application: return role_application;
        case ObjectKind::Window: return role_frame;
        case ObjectKind::Node: return object.node ? object.node->role : role_unknown;
        default: return role_unknown;
    }
}

uint64_t object_states(const Object &object) {
    switch (object.kind) {
        case ObjectKind::Application: return application_states();
        case ObjectKind::Window: return object.window ? window_states(*object.window) : 0;
        case ObjectKind::Node: return object.node ? object.node->states : 0;
        default: return 0;
    }
}

std::vector<Object> object_children(const Object &object) {
    std::vector<Object> result;
    if (object.kind == ObjectKind::Application) {
        for (auto &window : windows) result.push_back({ObjectKind::Window, window.get(), nullptr});
    } else if (object.kind == ObjectKind::Window && object.window) {
        for (int32_t id : object.window->root_nodes) {
            auto found = object.window->nodes.find(id);
            if (found != object.window->nodes.end()) {
                result.push_back({ObjectKind::Node, object.window, &found->second});
            }
        }
    } else if (object.kind == ObjectKind::Node && object.window && object.node) {
        for (int32_t id : object.node->children) {
            auto found = object.window->nodes.find(id);
            if (found != object.window->nodes.end()) {
                result.push_back({ObjectKind::Node, object.window, &found->second});
            }
        }
    }
    return result;
}

Object object_parent(const Object &object) {
    if (object.kind == ObjectKind::Application) return {};
    if (object.kind == ObjectKind::Window) return {ObjectKind::Application, nullptr, nullptr};
    if (object.kind == ObjectKind::Node && object.window && object.node) {
        auto found = object.window->nodes.find(object.node->parent_id);
        if (found != object.window->nodes.end()) {
            return {ObjectKind::Node, object.window, &found->second};
        }
        return {ObjectKind::Window, object.window, nullptr};
    }
    return {};
}

int32_t object_index_in_parent(const Object &object) {
    if (object.kind == ObjectKind::Application) return -1;
    const Object parent = object_parent(object);
    const auto siblings = object_children(parent);
    const std::string path = object_path(object);
    for (size_t index = 0; index < siblings.size(); ++index) {
        if (object_path(siblings[index]) == path) return static_cast<int32_t>(index);
    }
    return -1;
}

std::vector<std::string> object_interfaces(const Object &object) {
    std::vector<std::string> result{accessible_interface};
    if (object.kind == ObjectKind::Application) result.push_back(application_interface);
    if (object.kind == ObjectKind::Window || object.kind == ObjectKind::Node) {
        result.push_back(component_interface);
    }
    if (object.kind == ObjectKind::Node && object.node) {
        if (!object.node->actions.empty()) result.push_back(action_interface);
        if (!object.node->text.empty()) result.push_back(text_interface);
        if (object.node->has_value) result.push_back(value_interface);
    }
    return result;
}

std::string parent_path_for_node(
    const Window &window,
    const Node &node,
    const std::map<int32_t, Node> &nodes
) {
    const auto parent = nodes.find(node.parent_id);
    return parent == nodes.end() ? window.path : parent->second.path;
}

int32_t index_for_node(
    int32_t node_id,
    const Node &node,
    const std::map<int32_t, Node> &nodes,
    const std::vector<int32_t> &root_nodes
) {
    const auto parent = nodes.find(node.parent_id);
    const std::vector<int32_t> &siblings = parent == nodes.end() ? root_nodes : parent->second.children;
    const auto found = std::find(siblings.begin(), siblings.end(), node_id);
    return found == siblings.end() ? -1 : static_cast<int32_t>(found - siblings.begin());
}

void append_reference(DBusMessageIter *iter, const std::string &name, const std::string &path) {
    DBusMessageIter structure;
    dbus_message_iter_open_container(iter, DBUS_TYPE_STRUCT, nullptr, &structure);
    const char *bus_name = name.c_str();
    const char *object_path_value = path.c_str();
    dbus_message_iter_append_basic(&structure, DBUS_TYPE_STRING, &bus_name);
    dbus_message_iter_append_basic(&structure, DBUS_TYPE_OBJECT_PATH, &object_path_value);
    dbus_message_iter_close_container(iter, &structure);
}

void append_object_reference(DBusMessageIter *iter, const Object &object) {
    if (object.kind == ObjectKind::Missing) {
        append_reference(iter, "", null_path);
    } else {
        append_reference(iter, unique_name, object_path(object));
    }
}

void append_string_array(DBusMessageIter *iter, const std::vector<std::string> &values) {
    DBusMessageIter array;
    dbus_message_iter_open_container(iter, DBUS_TYPE_ARRAY, "s", &array);
    for (const std::string &value : values) {
        const char *text = value.c_str();
        dbus_message_iter_append_basic(&array, DBUS_TYPE_STRING, &text);
    }
    dbus_message_iter_close_container(iter, &array);
}

void append_state_array(DBusMessageIter *iter, uint64_t states) {
    DBusMessageIter array;
    dbus_message_iter_open_container(iter, DBUS_TYPE_ARRAY, "u", &array);
    uint32_t lower = static_cast<uint32_t>(states & 0xffffffffu);
    uint32_t upper = static_cast<uint32_t>(states >> 32u);
    dbus_message_iter_append_basic(&array, DBUS_TYPE_UINT32, &lower);
    dbus_message_iter_append_basic(&array, DBUS_TYPE_UINT32, &upper);
    dbus_message_iter_close_container(iter, &array);
}

void append_empty_relations(DBusMessageIter *iter) {
    DBusMessageIter array;
    dbus_message_iter_open_container(iter, DBUS_TYPE_ARRAY, "(ua(so))", &array);
    dbus_message_iter_close_container(iter, &array);
}

void append_attributes(DBusMessageIter *iter, const Object &object) {
    DBusMessageIter array;
    dbus_message_iter_open_container(iter, DBUS_TYPE_ARRAY, "{ss}", &array);
    const std::vector<std::pair<std::string, std::string>> attributes = {
        {"toolkit", "Compose"},
        {"accessible-id", object_accessible_id(object)},
    };
    for (const auto &[key_value, value_value] : attributes) {
        if (value_value.empty()) continue;
        DBusMessageIter entry;
        dbus_message_iter_open_container(&array, DBUS_TYPE_DICT_ENTRY, nullptr, &entry);
        const char *key = key_value.c_str();
        const char *value = value_value.c_str();
        dbus_message_iter_append_basic(&entry, DBUS_TYPE_STRING, &key);
        dbus_message_iter_append_basic(&entry, DBUS_TYPE_STRING, &value);
        dbus_message_iter_close_container(&array, &entry);
    }
    dbus_message_iter_close_container(iter, &array);
}

DBusMessage *new_reply(DBusMessage *message) { return dbus_message_new_method_return(message); }

DBusMessage *new_error(DBusMessage *message, const char *name, const char *detail) {
    return dbus_message_new_error(message, name, detail);
}

void send_reply(DBusMessage *reply) {
    if (!reply || !accessibility_bus) return;
    dbus_uint32_t serial = 0;
    dbus_connection_send(accessibility_bus, reply, &serial);
    dbus_message_unref(reply);
}

template <typename T>
DBusMessage *basic_reply(DBusMessage *message, int type, T value) {
    DBusMessage *reply = new_reply(message);
    if (!reply || !dbus_message_append_args(reply, type, &value, DBUS_TYPE_INVALID)) {
        if (reply) dbus_message_unref(reply);
        return new_error(message, DBUS_ERROR_NO_MEMORY, "Could not build AT-SPI reply");
    }
    return reply;
}

DBusMessage *string_reply(DBusMessage *message, const std::string &value) {
    const char *text = value.c_str();
    return basic_reply(message, DBUS_TYPE_STRING, text);
}

DBusMessage *reference_reply(DBusMessage *message, const Object &object) {
    DBusMessage *reply = new_reply(message);
    DBusMessageIter iter;
    dbus_message_iter_init_append(reply, &iter);
    append_object_reference(&iter, object);
    return reply;
}

void append_property_entry(
    DBusMessageIter *dictionary,
    const char *name,
    const char *signature,
    const std::function<void(DBusMessageIter *)> &append
) {
    DBusMessageIter entry;
    DBusMessageIter variant;
    dbus_message_iter_open_container(dictionary, DBUS_TYPE_DICT_ENTRY, nullptr, &entry);
    dbus_message_iter_append_basic(&entry, DBUS_TYPE_STRING, &name);
    dbus_message_iter_open_container(&entry, DBUS_TYPE_VARIANT, signature, &variant);
    append(&variant);
    dbus_message_iter_close_container(&entry, &variant);
    dbus_message_iter_close_container(dictionary, &entry);
}

bool append_property(DBusMessageIter *variant, const Object &object, const std::string &interface_name, const std::string &property) {
    if (interface_name == accessible_interface) {
        if (property == "version") {
            uint32_t value = interface_version;
            return dbus_message_iter_append_basic(variant, DBUS_TYPE_UINT32, &value);
        }
        if (property == "Name" || property == "Description" || property == "Locale" ||
            property == "AccessibleId" || property == "HelpText") {
            std::string value;
            if (property == "Name") value = object_name(object);
            else if (property == "Description") value = object_description(object);
            else if (property == "Locale") value = (std::setlocale(LC_MESSAGES, nullptr) ? std::setlocale(LC_MESSAGES, nullptr) : "C");
            else if (property == "AccessibleId") value = object_accessible_id(object);
            const char *text = value.c_str();
            return dbus_message_iter_append_basic(variant, DBUS_TYPE_STRING, &text);
        }
        if (property == "Parent") {
            if (object.kind == ObjectKind::Application) {
                append_reference(variant, registry_service, root_path);
            } else {
                append_object_reference(variant, object_parent(object));
            }
            return true;
        }
        if (property == "ChildCount") {
            int32_t value = static_cast<int32_t>(object_children(object).size());
            return dbus_message_iter_append_basic(variant, DBUS_TYPE_INT32, &value);
        }
    } else if (interface_name == application_interface && object.kind == ObjectKind::Application) {
        if (property == "version" || property == "InterfaceVersion") {
            uint32_t value = interface_version;
            return dbus_message_iter_append_basic(variant, DBUS_TYPE_UINT32, &value);
        }
        if (property == "ToolkitName" || property == "ToolkitVersion" || property == "AtspiVersion" || property == "Version") {
            const char *value = property == "ToolkitName" ? "Compose" : property == "AtspiVersion" ? "2.1" : "1";
            return dbus_message_iter_append_basic(variant, DBUS_TYPE_STRING, &value);
        }
        if (property == "Id") {
            return dbus_message_iter_append_basic(variant, DBUS_TYPE_INT32, &application_id);
        }
    } else if (interface_name == component_interface &&
               (object.kind == ObjectKind::Window || object.kind == ObjectKind::Node)) {
        if (property == "version") {
            uint32_t value = interface_version;
            return dbus_message_iter_append_basic(variant, DBUS_TYPE_UINT32, &value);
        }
    } else if (interface_name == action_interface && object.node) {
        if (property == "version") {
            uint32_t value = interface_version;
            return dbus_message_iter_append_basic(variant, DBUS_TYPE_UINT32, &value);
        }
        if (property == "NActions") {
            int32_t value = static_cast<int32_t>(object.node->actions.size());
            return dbus_message_iter_append_basic(variant, DBUS_TYPE_INT32, &value);
        }
    } else if (interface_name == text_interface && object.node && !object.node->text.empty()) {
        if (property == "version") {
            uint32_t value = interface_version;
            return dbus_message_iter_append_basic(variant, DBUS_TYPE_UINT32, &value);
        }
        if (property == "CharacterCount" || property == "CaretOffset") {
            int32_t value = property == "CaretOffset" ? object.node->selection_end : 0;
            if (property == "CharacterCount") {
                for (unsigned char byte : object.node->text) if ((byte & 0xc0u) != 0x80u) ++value;
            }
            return dbus_message_iter_append_basic(variant, DBUS_TYPE_INT32, &value);
        }
    } else if (interface_name == value_interface && object.node && object.node->has_value) {
        if (property == "version") {
            uint32_t value = interface_version;
            return dbus_message_iter_append_basic(variant, DBUS_TYPE_UINT32, &value);
        }
        if (property == "MinimumValue" || property == "MaximumValue" || property == "MinimumIncrement" || property == "CurrentValue") {
            double value = property == "MinimumValue" ? object.node->minimum :
                           property == "MaximumValue" ? object.node->maximum :
                           property == "MinimumIncrement" ? object.node->increment : object.node->current;
            return dbus_message_iter_append_basic(variant, DBUS_TYPE_DOUBLE, &value);
        }
        if (property == "Text") {
            const std::string value = object.node->text.empty() ? std::to_string(object.node->current) : object.node->text;
            const char *text = value.c_str();
            return dbus_message_iter_append_basic(variant, DBUS_TYPE_STRING, &text);
        }
    } else if (interface_name == cache_interface && object.kind == ObjectKind::Missing) {
        if (property == "version") {
            uint32_t value = interface_version;
            return dbus_message_iter_append_basic(variant, DBUS_TYPE_UINT32, &value);
        }
    }
    return false;
}

const char *property_signature(const Object &object, const std::string &interface_name, const std::string &property) {
    if (interface_name == accessible_interface) {
        if (property == "version") return "u";
        if (property == "Parent") return "(so)";
        if (property == "ChildCount") return "i";
        if (property == "Name" || property == "Description" || property == "Locale" ||
            property == "AccessibleId" || property == "HelpText") return "s";
    }
    if (interface_name == application_interface && object.kind == ObjectKind::Application) {
        if (property == "version" || property == "InterfaceVersion") return "u";
        if (property == "Id") return "i";
        if (property == "ToolkitName" || property == "ToolkitVersion" || property == "AtspiVersion" || property == "Version") return "s";
    }
    if (interface_name == component_interface && property == "version") return "u";
    if (interface_name == action_interface) {
        if (property == "version") return "u";
        if (property == "NActions") return "i";
    }
    if (interface_name == text_interface) {
        if (property == "version") return "u";
        if (property == "CharacterCount" || property == "CaretOffset") return "i";
    }
    if (interface_name == value_interface) {
        if (property == "version") return "u";
        if (property == "Text") return "s";
        if (property == "MinimumValue" || property == "MaximumValue" || property == "MinimumIncrement" || property == "CurrentValue") return "d";
    }
    if (interface_name == cache_interface && property == "version") return "u";
    return nullptr;
}

DBusMessage *handle_properties(DBusMessage *message, const Object &object) {
    const char *member = dbus_message_get_member(message);
    if (!member) return nullptr;
    if (std::strcmp(member, "Get") == 0) {
        DBusError error;
        dbus_error_init(&error);
        const char *interface_name = nullptr;
        const char *property_name = nullptr;
        if (!dbus_message_get_args(message, &error, DBUS_TYPE_STRING, &interface_name,
                                   DBUS_TYPE_STRING, &property_name, DBUS_TYPE_INVALID)) {
            DBusMessage *reply = new_error(message, DBUS_ERROR_INVALID_ARGS, error.message ? error.message : "Invalid property request");
            dbus_error_free(&error);
            return reply;
        }
        const std::string iface = safe(interface_name);
        const std::string property = safe(property_name);
        const char *signature = property_signature(object, iface, property);
        if (!signature) {
            dbus_error_free(&error);
            return new_error(message, DBUS_ERROR_UNKNOWN_PROPERTY, "Unknown AT-SPI property");
        }
        DBusMessage *reply = new_reply(message);
        DBusMessageIter iter;
        DBusMessageIter variant;
        dbus_message_iter_init_append(reply, &iter);
        dbus_message_iter_open_container(&iter, DBUS_TYPE_VARIANT, signature, &variant);
        if (!append_property(&variant, object, iface, property)) {
            dbus_message_unref(reply);
            dbus_error_free(&error);
            return new_error(message, DBUS_ERROR_UNKNOWN_PROPERTY, "Unavailable AT-SPI property");
        }
        dbus_message_iter_close_container(&iter, &variant);
        dbus_error_free(&error);
        return reply;
    }
    if (std::strcmp(member, "Set") == 0) {
        DBusMessageIter iter;
        if (!dbus_message_iter_init(message, &iter) || dbus_message_iter_get_arg_type(&iter) != DBUS_TYPE_STRING) {
            return new_error(message, DBUS_ERROR_INVALID_ARGS, "Invalid property assignment");
        }
        const char *iface_value = nullptr;
        dbus_message_iter_get_basic(&iter, &iface_value);
        dbus_message_iter_next(&iter);
        const char *property_value = nullptr;
        dbus_message_iter_get_basic(&iter, &property_value);
        dbus_message_iter_next(&iter);
        if (dbus_message_iter_get_arg_type(&iter) != DBUS_TYPE_VARIANT) {
            return new_error(message, DBUS_ERROR_INVALID_ARGS, "Property value must be a variant");
        }
        DBusMessageIter variant;
        dbus_message_iter_recurse(&iter, &variant);
        const std::string iface = safe(iface_value);
        const std::string property = safe(property_value);
        if (iface == application_interface && property == "Id" && object.kind == ObjectKind::Application &&
            dbus_message_iter_get_arg_type(&variant) == DBUS_TYPE_INT32) {
            dbus_message_iter_get_basic(&variant, &application_id);
            return new_reply(message);
        }
        if (iface == value_interface && property == "CurrentValue" && object.node && object.node->has_value &&
            dbus_message_iter_get_arg_type(&variant) == DBUS_TYPE_DOUBLE) {
            double value = 0.0;
            dbus_message_iter_get_basic(&variant, &value);
            const int result = object.window->callback && object.node->value_action_id >= 0
                ? object.window->callback(object.window->callback_context, object.node->id,
                    object.node->value_action_id, value, nullptr, 0, 0)
                : 0;
            if (result) object.node->current = value;
            return new_reply(message);
        }
        return new_error(message, DBUS_ERROR_PROPERTY_READ_ONLY, "AT-SPI property is read-only");
    }
    if (std::strcmp(member, "GetAll") == 0) {
        const char *interface_name = nullptr;
        DBusError error;
        dbus_error_init(&error);
        if (!dbus_message_get_args(message, &error, DBUS_TYPE_STRING, &interface_name, DBUS_TYPE_INVALID)) {
            DBusMessage *reply = new_error(message, DBUS_ERROR_INVALID_ARGS, error.message ? error.message : "Invalid GetAll request");
            dbus_error_free(&error);
            return reply;
        }
        const std::string iface = safe(interface_name);
        DBusMessage *reply = new_reply(message);
        DBusMessageIter iter;
        DBusMessageIter dictionary;
        dbus_message_iter_init_append(reply, &iter);
        dbus_message_iter_open_container(&iter, DBUS_TYPE_ARRAY, "{sv}", &dictionary);
        const std::vector<std::string> candidates = {
            "version", "Name", "Description", "Parent", "ChildCount", "Locale", "AccessibleId", "HelpText",
            "ToolkitName", "ToolkitVersion", "AtspiVersion", "InterfaceVersion", "Id", "NActions",
            "CharacterCount", "CaretOffset", "MinimumValue", "MaximumValue", "MinimumIncrement", "CurrentValue", "Text"
        };
        for (const std::string &property : candidates) {
            const char *signature = property_signature(object, iface, property);
            if (!signature) continue;
            append_property_entry(&dictionary, property.c_str(), signature, [&](DBusMessageIter *variant) {
                append_property(variant, object, iface, property);
            });
        }
        dbus_message_iter_close_container(&iter, &dictionary);
        dbus_error_free(&error);
        return reply;
    }
    return nullptr;
}

DBusMessage *handle_accessible(DBusMessage *message, const Object &object) {
    const char *member = dbus_message_get_member(message);
    if (!member) return nullptr;
    if (std::strcmp(member, "GetChildAtIndex") == 0) {
        int32_t index = -1;
        DBusError error;
        dbus_error_init(&error);
        if (!dbus_message_get_args(message, &error, DBUS_TYPE_INT32, &index, DBUS_TYPE_INVALID)) {
            DBusMessage *reply = new_error(message, DBUS_ERROR_INVALID_ARGS, error.message ? error.message : "Invalid child index");
            dbus_error_free(&error);
            return reply;
        }
        const auto children = object_children(object);
        dbus_error_free(&error);
        if (index < 0 || static_cast<size_t>(index) >= children.size()) return reference_reply(message, {});
        return reference_reply(message, children[index]);
    }
    if (std::strcmp(member, "GetChildren") == 0) {
        DBusMessage *reply = new_reply(message);
        DBusMessageIter iter;
        DBusMessageIter array;
        dbus_message_iter_init_append(reply, &iter);
        dbus_message_iter_open_container(&iter, DBUS_TYPE_ARRAY, "(so)", &array);
        for (const Object &child : object_children(object)) append_object_reference(&array, child);
        dbus_message_iter_close_container(&iter, &array);
        return reply;
    }
    if (std::strcmp(member, "GetIndexInParent") == 0) {
        return basic_reply(message, DBUS_TYPE_INT32, object_index_in_parent(object));
    }
    if (std::strcmp(member, "GetRelationSet") == 0) {
        DBusMessage *reply = new_reply(message);
        DBusMessageIter iter;
        dbus_message_iter_init_append(reply, &iter);
        append_empty_relations(&iter);
        return reply;
    }
    if (std::strcmp(member, "GetRole") == 0) {
        return basic_reply(message, DBUS_TYPE_UINT32, object_role(object));
    }
    if (std::strcmp(member, "GetRoleName") == 0 || std::strcmp(member, "GetLocalizedRoleName") == 0) {
        return string_reply(message, role_name(object_role(object)));
    }
    if (std::strcmp(member, "GetState") == 0) {
        DBusMessage *reply = new_reply(message);
        DBusMessageIter iter;
        dbus_message_iter_init_append(reply, &iter);
        append_state_array(&iter, object_states(object));
        return reply;
    }
    if (std::strcmp(member, "GetAttributes") == 0) {
        DBusMessage *reply = new_reply(message);
        DBusMessageIter iter;
        dbus_message_iter_init_append(reply, &iter);
        append_attributes(&iter, object);
        return reply;
    }
    if (std::strcmp(member, "GetApplication") == 0) {
        return reference_reply(message, {ObjectKind::Application, nullptr, nullptr});
    }
    if (std::strcmp(member, "GetInterfaces") == 0) {
        DBusMessage *reply = new_reply(message);
        DBusMessageIter iter;
        dbus_message_iter_init_append(reply, &iter);
        append_string_array(&iter, object_interfaces(object));
        return reply;
    }
    return nullptr;
}

void object_rect(const Object &object, int coord_type, int32_t &x, int32_t &y, int32_t &width, int32_t &height) {
    x = y = 0;
    width = height = 0;
    if (object.kind == ObjectKind::Window && object.window) {
        width = object.window->width;
        height = object.window->height;
        if (coord_type == 0) { x = object.window->screen_x; y = object.window->screen_y; }
    } else if (object.kind == ObjectKind::Node && object.window && object.node) {
        x = object.node->x;
        y = object.node->y;
        width = object.node->width;
        height = object.node->height;
        if (coord_type == 0) { x += object.window->screen_x; y += object.window->screen_y; }
        if (coord_type == 2) {
            const Object parent = object_parent(object);
            int32_t px = 0, py = 0, pw = 0, ph = 0;
            object_rect(parent, 1, px, py, pw, ph);
            x -= px;
            y -= py;
        }
    }
}

Object accessible_at_point(Window *window, int32_t x, int32_t y) {
    if (!window) return {};
    Object best{ObjectKind::Window, window, nullptr};
    for (auto &[id, node] : window->nodes) {
        if (x >= node.x && y >= node.y && x < node.x + node.width && y < node.y + node.height) {
            best = {ObjectKind::Node, window, &node};
        }
    }
    return best;
}

DBusMessage *handle_component(DBusMessage *message, const Object &object) {
    const char *member = dbus_message_get_member(message);
    if (!member) return nullptr;
    if (std::strcmp(member, "Contains") == 0 || std::strcmp(member, "GetAccessibleAtPoint") == 0) {
        int32_t x = 0, y = 0;
        uint32_t coord_type = 1;
        DBusError error;
        dbus_error_init(&error);
        if (!dbus_message_get_args(message, &error, DBUS_TYPE_INT32, &x, DBUS_TYPE_INT32, &y,
                                   DBUS_TYPE_UINT32, &coord_type, DBUS_TYPE_INVALID)) {
            DBusMessage *reply = new_error(message, DBUS_ERROR_INVALID_ARGS, error.message ? error.message : "Invalid point");
            dbus_error_free(&error);
            return reply;
        }
        int32_t rx, ry, width, height;
        object_rect(object, coord_type, rx, ry, width, height);
        const dbus_bool_t contains = x >= rx && y >= ry && x < rx + width && y < ry + height;
        dbus_error_free(&error);
        if (std::strcmp(member, "Contains") == 0) return basic_reply(message, DBUS_TYPE_BOOLEAN, contains);
        if (!contains) return reference_reply(message, {});
        int32_t window_x = x;
        int32_t window_y = y;
        if (coord_type == 0 && object.window) { window_x -= object.window->screen_x; window_y -= object.window->screen_y; }
        return reference_reply(message, accessible_at_point(object.window, window_x, window_y));
    }
    if (std::strcmp(member, "GetExtents") == 0 || std::strcmp(member, "GetPosition") == 0) {
        uint32_t coord_type = 1;
        DBusError error;
        dbus_error_init(&error);
        if (!dbus_message_get_args(message, &error, DBUS_TYPE_UINT32, &coord_type, DBUS_TYPE_INVALID)) {
            DBusMessage *reply = new_error(message, DBUS_ERROR_INVALID_ARGS, error.message ? error.message : "Invalid coordinate type");
            dbus_error_free(&error);
            return reply;
        }
        int32_t x, y, width, height;
        object_rect(object, coord_type, x, y, width, height);
        dbus_error_free(&error);
        DBusMessage *reply = new_reply(message);
        if (std::strcmp(member, "GetExtents") == 0) {
            DBusMessageIter iter;
            DBusMessageIter rectangle;
            dbus_message_iter_init_append(reply, &iter);
            dbus_message_iter_open_container(&iter, DBUS_TYPE_STRUCT, nullptr, &rectangle);
            dbus_message_iter_append_basic(&rectangle, DBUS_TYPE_INT32, &x);
            dbus_message_iter_append_basic(&rectangle, DBUS_TYPE_INT32, &y);
            dbus_message_iter_append_basic(&rectangle, DBUS_TYPE_INT32, &width);
            dbus_message_iter_append_basic(&rectangle, DBUS_TYPE_INT32, &height);
            dbus_message_iter_close_container(&iter, &rectangle);
        } else {
            dbus_message_append_args(reply, DBUS_TYPE_INT32, &x, DBUS_TYPE_INT32, &y, DBUS_TYPE_INVALID);
        }
        return reply;
    }
    if (std::strcmp(member, "GetSize") == 0) {
        int32_t x, y, width, height;
        object_rect(object, 1, x, y, width, height);
        DBusMessage *reply = new_reply(message);
        dbus_message_append_args(reply, DBUS_TYPE_INT32, &width, DBUS_TYPE_INT32, &height, DBUS_TYPE_INVALID);
        return reply;
    }
    if (std::strcmp(member, "GetLayer") == 0) {
        uint32_t layer = object.kind == ObjectKind::Window ? 7u : 3u;
        return basic_reply(message, DBUS_TYPE_UINT32, layer);
    }
    if (std::strcmp(member, "GetMDIZOrder") == 0) {
        int16_t order = 0;
        return basic_reply(message, DBUS_TYPE_INT16, order);
    }
    if (std::strcmp(member, "GrabFocus") == 0) {
        int result = 0;
        if (object.node && object.window->callback) {
            for (const Action &action : object.node->actions) {
                if (action.name == "focus") {
                    result = object.window->callback(object.window->callback_context, object.node->id,
                        action.callback_id, 0.0, nullptr, 0, 0);
                    break;
                }
            }
        }
        dbus_bool_t value = result != 0;
        return basic_reply(message, DBUS_TYPE_BOOLEAN, value);
    }
    if (std::strcmp(member, "GetAlpha") == 0) {
        double alpha = 1.0;
        return basic_reply(message, DBUS_TYPE_DOUBLE, alpha);
    }
    if (std::strcmp(member, "SetExtents") == 0 || std::strcmp(member, "SetPosition") == 0 ||
        std::strcmp(member, "SetSize") == 0 || std::strcmp(member, "ScrollTo") == 0 ||
        std::strcmp(member, "ScrollToPoint") == 0) {
        dbus_bool_t value = FALSE;
        return basic_reply(message, DBUS_TYPE_BOOLEAN, value);
    }
    return nullptr;
}

const Action *action_at(const Object &object, int32_t index) {
    if (!object.node || index < 0 || static_cast<size_t>(index) >= object.node->actions.size()) return nullptr;
    return &object.node->actions[index];
}

DBusMessage *handle_action(DBusMessage *message, const Object &object) {
    if (!object.node) return nullptr;
    const char *member = dbus_message_get_member(message);
    if (!member) return nullptr;
    int32_t index = -1;
    if (std::strcmp(member, "GetDescription") == 0 || std::strcmp(member, "GetName") == 0 ||
        std::strcmp(member, "GetLocalizedName") == 0 || std::strcmp(member, "GetKeyBinding") == 0 ||
        std::strcmp(member, "DoAction") == 0) {
        DBusError error;
        dbus_error_init(&error);
        if (!dbus_message_get_args(message, &error, DBUS_TYPE_INT32, &index, DBUS_TYPE_INVALID)) {
            DBusMessage *reply = new_error(message, DBUS_ERROR_INVALID_ARGS, error.message ? error.message : "Invalid action index");
            dbus_error_free(&error);
            return reply;
        }
        dbus_error_free(&error);
    }
    const Action *action = action_at(object, index);
    if (!action) return new_error(message, DBUS_ERROR_INVALID_ARGS, "Action index is out of range");
    if (std::strcmp(member, "GetDescription") == 0) return string_reply(message, action->description);
    if (std::strcmp(member, "GetName") == 0 || std::strcmp(member, "GetLocalizedName") == 0) return string_reply(message, action->name);
    if (std::strcmp(member, "GetKeyBinding") == 0) return string_reply(message, action->key_binding);
    if (std::strcmp(member, "DoAction") == 0) {
        const int result = object.window->callback
            ? object.window->callback(object.window->callback_context, object.node->id,
                action->callback_id, 0.0, nullptr, 0, 0)
            : 0;
        dbus_bool_t value = result != 0;
        return basic_reply(message, DBUS_TYPE_BOOLEAN, value);
    }
    return nullptr;
}

std::vector<size_t> utf8_offsets(const std::string &text) {
    std::vector<size_t> offsets;
    for (size_t index = 0; index < text.size(); ++index) {
        if ((static_cast<unsigned char>(text[index]) & 0xc0u) != 0x80u) offsets.push_back(index);
    }
    offsets.push_back(text.size());
    return offsets;
}

std::string utf8_slice(const std::string &text, int32_t start, int32_t end) {
    const auto offsets = utf8_offsets(text);
    const int32_t count = static_cast<int32_t>(offsets.size()) - 1;
    start = std::max(0, std::min(start, count));
    if (end < 0) end = count;
    end = std::max(start, std::min(end, count));
    return text.substr(offsets[start], offsets[end] - offsets[start]);
}

uint32_t utf8_codepoint_at(const std::string &text, int32_t offset) {
    const auto offsets = utf8_offsets(text);
    const int32_t count = static_cast<int32_t>(offsets.size()) - 1;
    if (offset < 0 || offset >= count) return 0;
    const unsigned char *bytes = reinterpret_cast<const unsigned char *>(text.data() + offsets[offset]);
    if (bytes[0] < 0x80) return bytes[0];
    if ((bytes[0] & 0xe0) == 0xc0) return ((bytes[0] & 0x1f) << 6) | (bytes[1] & 0x3f);
    if ((bytes[0] & 0xf0) == 0xe0) return ((bytes[0] & 0x0f) << 12) | ((bytes[1] & 0x3f) << 6) | (bytes[2] & 0x3f);
    if ((bytes[0] & 0xf8) == 0xf0) return ((bytes[0] & 0x07) << 18) | ((bytes[1] & 0x3f) << 12) | ((bytes[2] & 0x3f) << 6) | (bytes[3] & 0x3f);
    return 0xfffd;
}

DBusMessage *text_range_reply(DBusMessage *message, const std::string &text, int32_t start, int32_t end) {
    DBusMessage *reply = new_reply(message);
    const std::string slice = utf8_slice(text, start, end);
    const char *value = slice.c_str();
    dbus_message_append_args(reply, DBUS_TYPE_STRING, &value, DBUS_TYPE_INT32, &start,
        DBUS_TYPE_INT32, &end, DBUS_TYPE_INVALID);
    return reply;
}

DBusMessage *handle_text(DBusMessage *message, const Object &object) {
    if (!object.node || object.node->text.empty()) return nullptr;
    const char *member = dbus_message_get_member(message);
    if (!member) return nullptr;
    const std::string &text = object.node->text;
    if (std::strcmp(member, "GetText") == 0) {
        int32_t start = 0, end = -1;
        DBusError error;
        dbus_error_init(&error);
        if (!dbus_message_get_args(message, &error, DBUS_TYPE_INT32, &start, DBUS_TYPE_INT32, &end, DBUS_TYPE_INVALID)) {
            DBusMessage *reply = new_error(message, DBUS_ERROR_INVALID_ARGS, error.message ? error.message : "Invalid text range");
            dbus_error_free(&error);
            return reply;
        }
        dbus_error_free(&error);
        return string_reply(message, utf8_slice(text, start, end));
    }
    if (std::strcmp(member, "GetCharacterAtOffset") == 0) {
        int32_t offset = 0;
        DBusError error;
        dbus_error_init(&error);
        if (!dbus_message_get_args(message, &error, DBUS_TYPE_INT32, &offset, DBUS_TYPE_INVALID)) {
            DBusMessage *reply = new_error(message, DBUS_ERROR_INVALID_ARGS, error.message ? error.message : "Invalid text offset");
            dbus_error_free(&error);
            return reply;
        }
        dbus_error_free(&error);
        const int32_t codepoint = static_cast<int32_t>(utf8_codepoint_at(text, offset));
        return basic_reply(message, DBUS_TYPE_INT32, codepoint);
    }
    if (std::strcmp(member, "GetStringAtOffset") == 0 || std::strcmp(member, "GetTextAtOffset") == 0 ||
        std::strcmp(member, "GetTextBeforeOffset") == 0 || std::strcmp(member, "GetTextAfterOffset") == 0) {
        int32_t offset = 0;
        uint32_t granularity = 0;
        DBusError error;
        dbus_error_init(&error);
        if (!dbus_message_get_args(message, &error, DBUS_TYPE_INT32, &offset, DBUS_TYPE_UINT32, &granularity, DBUS_TYPE_INVALID)) {
            DBusMessage *reply = new_error(message, DBUS_ERROR_INVALID_ARGS, error.message ? error.message : "Invalid text granularity request");
            dbus_error_free(&error);
            return reply;
        }
        dbus_error_free(&error);
        const int32_t count = static_cast<int32_t>(utf8_offsets(text).size()) - 1;
        int32_t start = 0;
        int32_t end = count;
        if (granularity == 0) { start = std::max(0, std::min(offset, count)); end = std::min(count, start + 1); }
        if (std::strcmp(member, "GetTextBeforeOffset") == 0) { end = std::max(0, std::min(offset, count)); start = std::max(0, end - 1); }
        if (std::strcmp(member, "GetTextAfterOffset") == 0) { start = std::min(count, std::max(0, offset + 1)); end = std::min(count, start + 1); }
        return text_range_reply(message, text, start, end);
    }
    if (std::strcmp(member, "GetNSelections") == 0) {
        const int32_t count = object.node->selection_end > object.node->selection_start ? 1 : 0;
        return basic_reply(message, DBUS_TYPE_INT32, count);
    }
    if (std::strcmp(member, "GetSelection") == 0) {
        int32_t index = 0;
        DBusError error;
        dbus_error_init(&error);
        if (!dbus_message_get_args(message, &error, DBUS_TYPE_INT32, &index, DBUS_TYPE_INVALID) || index != 0) {
            DBusMessage *reply = new_error(message, DBUS_ERROR_INVALID_ARGS, "Selection index is out of range");
            dbus_error_free(&error);
            return reply;
        }
        dbus_error_free(&error);
        DBusMessage *reply = new_reply(message);
        dbus_message_append_args(reply, DBUS_TYPE_INT32, &object.node->selection_start,
            DBUS_TYPE_INT32, &object.node->selection_end, DBUS_TYPE_INVALID);
        return reply;
    }
    if (std::strcmp(member, "SetCaretOffset") == 0 || std::strcmp(member, "AddSelection") == 0 ||
        std::strcmp(member, "RemoveSelection") == 0 || std::strcmp(member, "SetSelection") == 0 ||
        std::strcmp(member, "ScrollSubstringTo") == 0 || std::strcmp(member, "ScrollSubstringToPoint") == 0) {
        dbus_bool_t value = FALSE;
        return basic_reply(message, DBUS_TYPE_BOOLEAN, value);
    }
    return nullptr;
}

DBusMessage *handle_value(DBusMessage *message, const Object &object) {
    if (!object.node || !object.node->has_value) return nullptr;
    const char *member = dbus_message_get_member(message);
    if (!member) return nullptr;
    if (std::strcmp(member, "SetCurrentValue") == 0) {
        double value = 0.0;
        DBusError error;
        dbus_error_init(&error);
        if (!dbus_message_get_args(message, &error, DBUS_TYPE_DOUBLE, &value, DBUS_TYPE_INVALID)) {
            DBusMessage *reply = new_error(
                message,
                DBUS_ERROR_INVALID_ARGS,
                error.message ? error.message : "Invalid value"
            );
            dbus_error_free(&error);
            return reply;
        }
        dbus_error_free(&error);
        const int result = object.window->callback && object.node->value_action_id >= 0
            ? object.window->callback(
                object.window->callback_context,
                object.node->id,
                object.node->value_action_id,
                value,
                nullptr,
                0,
                0
            )
            : 0;
        if (result) object.node->current = value;
        dbus_bool_t accepted = result != 0;
        return basic_reply(message, DBUS_TYPE_BOOLEAN, accepted);
    }
    return nullptr;
}

DBusMessage *handle_application(DBusMessage *message, const Object &object) {
    if (object.kind != ObjectKind::Application) return nullptr;
    const char *member = dbus_message_get_member(message);
    if (!member) return nullptr;
    if (std::strcmp(member, "GetLocale") == 0) {
        uint32_t type = 0;
        DBusError error;
        dbus_error_init(&error);
        dbus_message_get_args(message, &error, DBUS_TYPE_UINT32, &type, DBUS_TYPE_INVALID);
        dbus_error_free(&error);
        return string_reply(message, (std::setlocale(LC_ALL, nullptr) ? std::setlocale(LC_ALL, nullptr) : "C"));
    }
    if (std::strcmp(member, "GetApplicationBusAddress") == 0) {
        return string_reply(message, accessibility_bus_address);
    }
    return nullptr;
}

void append_cache_item(DBusMessageIter *array, const Object &object) {
    DBusMessageIter item;
    dbus_message_iter_open_container(array, DBUS_TYPE_STRUCT, nullptr, &item);
    append_object_reference(&item, object);
    append_object_reference(&item, {ObjectKind::Application, nullptr, nullptr});
    if (object.kind == ObjectKind::Application) {
        append_reference(&item, registry_service, root_path);
    } else {
        append_object_reference(&item, object_parent(object));
    }
    int32_t index = object_index_in_parent(object);
    int32_t child_count = static_cast<int32_t>(object_children(object).size());
    dbus_message_iter_append_basic(&item, DBUS_TYPE_INT32, &index);
    dbus_message_iter_append_basic(&item, DBUS_TYPE_INT32, &child_count);
    append_string_array(&item, object_interfaces(object));
    const std::string name_value = object_name(object);
    const char *name = name_value.c_str();
    dbus_message_iter_append_basic(&item, DBUS_TYPE_STRING, &name);
    uint32_t role = object_role(object);
    dbus_message_iter_append_basic(&item, DBUS_TYPE_UINT32, &role);
    const std::string description_value = object_description(object);
    const char *description = description_value.c_str();
    dbus_message_iter_append_basic(&item, DBUS_TYPE_STRING, &description);
    append_state_array(&item, object_states(object));
    dbus_message_iter_close_container(array, &item);
}

DBusMessage *handle_cache(DBusMessage *message) {
    const char *member = dbus_message_get_member(message);
    if (!member || std::strcmp(member, "GetItems") != 0) return nullptr;
    DBusMessage *reply = new_reply(message);
    DBusMessageIter iter;
    DBusMessageIter array;
    dbus_message_iter_init_append(reply, &iter);
    dbus_message_iter_open_container(&iter, DBUS_TYPE_ARRAY, "((so)(so)(so)iiassusau)", &array);
    append_cache_item(&array, {ObjectKind::Application, nullptr, nullptr});
    for (auto &window : windows) {
        append_cache_item(&array, {ObjectKind::Window, window.get(), nullptr});
        for (auto &[id, node] : window->nodes) append_cache_item(&array, {ObjectKind::Node, window.get(), &node});
    }
    dbus_message_iter_close_container(&iter, &array);
    return reply;
}

const char *introspection_xml = R"XML(
<node>
 <interface name="org.freedesktop.DBus.Introspectable">
  <method name="Introspect"><arg direction="out" type="s" name="xml_data"/></method>
 </interface>
 <interface name="org.freedesktop.DBus.Properties">
  <method name="Get"><arg direction="in" type="s" name="interface_name"/><arg direction="in" type="s" name="property_name"/><arg direction="out" type="v" name="value"/></method>
  <method name="GetAll"><arg direction="in" type="s" name="interface_name"/><arg direction="out" type="a{sv}" name="properties"/></method>
  <method name="Set"><arg direction="in" type="s" name="interface_name"/><arg direction="in" type="s" name="property_name"/><arg direction="in" type="v" name="value"/></method>
 </interface>
 <interface name="org.freedesktop.DBus.Peer">
  <method name="Ping"/>
 </interface>
 <interface name="org.a11y.atspi.Accessible">
  <method name="GetChildAtIndex"><arg direction="in" type="i" name="index"/><arg direction="out" type="(so)" name="child"/></method>
  <method name="GetChildren"><arg direction="out" type="a(so)" name="children"/></method>
  <method name="GetIndexInParent"><arg direction="out" type="i" name="index"/></method>
  <method name="GetRelationSet"><arg direction="out" type="a(ua(so))" name="relations"/></method>
  <method name="GetRole"><arg direction="out" type="u" name="role"/></method>
  <method name="GetRoleName"><arg direction="out" type="s" name="role_name"/></method>
  <method name="GetLocalizedRoleName"><arg direction="out" type="s" name="role_name"/></method>
  <method name="GetState"><arg direction="out" type="au" name="states"/></method>
  <method name="GetAttributes"><arg direction="out" type="a{ss}" name="attributes"/></method>
  <method name="GetApplication"><arg direction="out" type="(so)" name="application"/></method>
  <method name="GetInterfaces"><arg direction="out" type="as" name="interfaces"/></method>
  <property name="version" type="u" access="read"/>
  <property name="Name" type="s" access="read"/>
  <property name="Description" type="s" access="read"/>
  <property name="Parent" type="(so)" access="read"/>
  <property name="ChildCount" type="i" access="read"/>
  <property name="Locale" type="s" access="read"/>
  <property name="AccessibleId" type="s" access="read"/>
  <property name="HelpText" type="s" access="read"/>
 </interface>
 <interface name="org.a11y.atspi.Application">
  <method name="GetLocale"><arg direction="in" type="u" name="locale_type"/><arg direction="out" type="s" name="locale"/></method>
  <method name="GetApplicationBusAddress"><arg direction="out" type="s" name="address"/></method>
  <property name="version" type="u" access="read"/>
  <property name="ToolkitName" type="s" access="read"/>
  <property name="ToolkitVersion" type="s" access="read"/>
  <property name="AtspiVersion" type="s" access="read"/>
  <property name="Version" type="s" access="read"/>
  <property name="InterfaceVersion" type="u" access="read"/>
  <property name="Id" type="i" access="readwrite"/>
 </interface>
 <interface name="org.a11y.atspi.Component">
  <method name="Contains"><arg direction="in" type="i" name="x"/><arg direction="in" type="i" name="y"/><arg direction="in" type="u" name="coord_type"/><arg direction="out" type="b" name="contains"/></method>
  <method name="GetAccessibleAtPoint"><arg direction="in" type="i" name="x"/><arg direction="in" type="i" name="y"/><arg direction="in" type="u" name="coord_type"/><arg direction="out" type="(so)" name="accessible"/></method>
  <method name="GetExtents"><arg direction="in" type="u" name="coord_type"/><arg direction="out" type="(iiii)" name="extents"/></method>
  <method name="GetPosition"><arg direction="in" type="u" name="coord_type"/><arg direction="out" type="i" name="x"/><arg direction="out" type="i" name="y"/></method>
  <method name="GetSize"><arg direction="out" type="i" name="width"/><arg direction="out" type="i" name="height"/></method>
  <method name="GetLayer"><arg direction="out" type="u" name="layer"/></method>
  <method name="GetMDIZOrder"><arg direction="out" type="n" name="order"/></method>
  <method name="GrabFocus"><arg direction="out" type="b" name="accepted"/></method>
  <method name="GetAlpha"><arg direction="out" type="d" name="alpha"/></method>
  <method name="SetExtents"><arg direction="in" type="i" name="x"/><arg direction="in" type="i" name="y"/><arg direction="in" type="i" name="width"/><arg direction="in" type="i" name="height"/><arg direction="in" type="u" name="coord_type"/><arg direction="out" type="b" name="accepted"/></method>
  <method name="SetPosition"><arg direction="in" type="i" name="x"/><arg direction="in" type="i" name="y"/><arg direction="in" type="u" name="coord_type"/><arg direction="out" type="b" name="accepted"/></method>
  <method name="SetSize"><arg direction="in" type="i" name="width"/><arg direction="in" type="i" name="height"/><arg direction="out" type="b" name="accepted"/></method>
  <method name="ScrollTo"><arg direction="in" type="u" name="type"/><arg direction="out" type="b" name="accepted"/></method>
  <method name="ScrollToPoint"><arg direction="in" type="u" name="coord_type"/><arg direction="in" type="i" name="x"/><arg direction="in" type="i" name="y"/><arg direction="out" type="b" name="accepted"/></method>
  <property name="version" type="u" access="read"/>
 </interface>
 <interface name="org.a11y.atspi.Action">
  <method name="GetDescription"><arg direction="in" type="i" name="index"/><arg direction="out" type="s" name="description"/></method>
  <method name="GetName"><arg direction="in" type="i" name="index"/><arg direction="out" type="s" name="name"/></method>
  <method name="GetLocalizedName"><arg direction="in" type="i" name="index"/><arg direction="out" type="s" name="name"/></method>
  <method name="GetKeyBinding"><arg direction="in" type="i" name="index"/><arg direction="out" type="s" name="key_binding"/></method>
  <method name="DoAction"><arg direction="in" type="i" name="index"/><arg direction="out" type="b" name="accepted"/></method>
  <property name="version" type="u" access="read"/>
  <property name="NActions" type="i" access="read"/>
 </interface>
 <interface name="org.a11y.atspi.Text">
  <method name="GetText"><arg direction="in" type="i" name="start_offset"/><arg direction="in" type="i" name="end_offset"/><arg direction="out" type="s" name="text"/></method>
  <method name="GetCharacterAtOffset"><arg direction="in" type="i" name="offset"/><arg direction="out" type="i" name="character"/></method>
  <method name="GetStringAtOffset"><arg direction="in" type="i" name="offset"/><arg direction="in" type="u" name="granularity"/><arg direction="out" type="s" name="text"/><arg direction="out" type="i" name="start_offset"/><arg direction="out" type="i" name="end_offset"/></method>
  <method name="GetTextAtOffset"><arg direction="in" type="i" name="offset"/><arg direction="in" type="u" name="boundary_type"/><arg direction="out" type="s" name="text"/><arg direction="out" type="i" name="start_offset"/><arg direction="out" type="i" name="end_offset"/></method>
  <method name="GetTextBeforeOffset"><arg direction="in" type="i" name="offset"/><arg direction="in" type="u" name="boundary_type"/><arg direction="out" type="s" name="text"/><arg direction="out" type="i" name="start_offset"/><arg direction="out" type="i" name="end_offset"/></method>
  <method name="GetTextAfterOffset"><arg direction="in" type="i" name="offset"/><arg direction="in" type="u" name="boundary_type"/><arg direction="out" type="s" name="text"/><arg direction="out" type="i" name="start_offset"/><arg direction="out" type="i" name="end_offset"/></method>
  <method name="GetNSelections"><arg direction="out" type="i" name="count"/></method>
  <method name="GetSelection"><arg direction="in" type="i" name="selection_index"/><arg direction="out" type="i" name="start_offset"/><arg direction="out" type="i" name="end_offset"/></method>
  <method name="SetCaretOffset"><arg direction="in" type="i" name="offset"/><arg direction="out" type="b" name="accepted"/></method>
  <method name="AddSelection"><arg direction="in" type="i" name="start_offset"/><arg direction="in" type="i" name="end_offset"/><arg direction="out" type="b" name="accepted"/></method>
  <method name="RemoveSelection"><arg direction="in" type="i" name="selection_index"/><arg direction="out" type="b" name="accepted"/></method>
  <method name="SetSelection"><arg direction="in" type="i" name="selection_index"/><arg direction="in" type="i" name="start_offset"/><arg direction="in" type="i" name="end_offset"/><arg direction="out" type="b" name="accepted"/></method>
  <method name="ScrollSubstringTo"><arg direction="in" type="i" name="start_offset"/><arg direction="in" type="i" name="end_offset"/><arg direction="in" type="u" name="type"/><arg direction="out" type="b" name="accepted"/></method>
  <method name="ScrollSubstringToPoint"><arg direction="in" type="i" name="start_offset"/><arg direction="in" type="i" name="end_offset"/><arg direction="in" type="u" name="coord_type"/><arg direction="in" type="i" name="x"/><arg direction="in" type="i" name="y"/><arg direction="out" type="b" name="accepted"/></method>
  <property name="version" type="u" access="read"/>
  <property name="CharacterCount" type="i" access="read"/>
  <property name="CaretOffset" type="i" access="read"/>
 </interface>
 <interface name="org.a11y.atspi.Value">
  <method name="SetCurrentValue"><arg direction="in" type="d" name="value"/><arg direction="out" type="b" name="accepted"/></method>
  <property name="version" type="u" access="read"/>
  <property name="MinimumValue" type="d" access="read"/>
  <property name="MaximumValue" type="d" access="read"/>
  <property name="MinimumIncrement" type="d" access="read"/>
  <property name="CurrentValue" type="d" access="readwrite"/>
  <property name="Text" type="s" access="read"/>
 </interface>
 <interface name="org.a11y.atspi.Cache">
  <method name="GetItems"><arg direction="out" type="a((so)(so)(so)iiassusau)" name="items"/></method>
  <signal name="AddAccessible"><arg type="((so)(so)(so)iiassusau)" name="item"/></signal>
  <signal name="RemoveAccessible"><arg type="(so)" name="accessible"/></signal>
  <property name="version" type="u" access="read"/>
 </interface>
</node>)XML";

DBusHandlerResult message_handler(DBusConnection *, DBusMessage *message, void *) {
    const char *path = dbus_message_get_path(message);
    const char *interface_name = dbus_message_get_interface(message);
    const char *member = dbus_message_get_member(message);
    if (!path || !member) return DBUS_HANDLER_RESULT_NOT_YET_HANDLED;

    if (interface_name && std::strcmp(interface_name, peer_interface) == 0 && std::strcmp(member, "Ping") == 0) {
        send_reply(new_reply(message));
        return DBUS_HANDLER_RESULT_HANDLED;
    }
    if (interface_name && std::strcmp(interface_name, introspectable_interface) == 0 && std::strcmp(member, "Introspect") == 0) {
        send_reply(string_reply(message, introspection_xml));
        return DBUS_HANDLER_RESULT_HANDLED;
    }
    if (std::strcmp(path, cache_path) == 0) {
        Object cache_object{};
        DBusMessage *reply = nullptr;
        if (interface_name && std::strcmp(interface_name, properties_interface) == 0) reply = handle_properties(message, cache_object);
        else if (interface_name && std::strcmp(interface_name, cache_interface) == 0) reply = handle_cache(message);
        if (reply) { send_reply(reply); return DBUS_HANDLER_RESULT_HANDLED; }
    }

    Object object = find_object(path);
    if (object.kind == ObjectKind::Missing) {
        send_reply(new_error(message, DBUS_ERROR_UNKNOWN_OBJECT, "Unknown Compose accessibility object"));
        return DBUS_HANDLER_RESULT_HANDLED;
    }

    DBusMessage *reply = nullptr;
    if (interface_name && std::strcmp(interface_name, properties_interface) == 0) reply = handle_properties(message, object);
    else if (interface_name && std::strcmp(interface_name, accessible_interface) == 0) reply = handle_accessible(message, object);
    else if (interface_name && std::strcmp(interface_name, application_interface) == 0) reply = handle_application(message, object);
    else if (interface_name && std::strcmp(interface_name, component_interface) == 0) reply = handle_component(message, object);
    else if (interface_name && std::strcmp(interface_name, action_interface) == 0) reply = handle_action(message, object);
    else if (interface_name && std::strcmp(interface_name, text_interface) == 0) reply = handle_text(message, object);
    else if (interface_name && std::strcmp(interface_name, value_interface) == 0) reply = handle_value(message, object);

    if (!reply) reply = new_error(message, DBUS_ERROR_UNKNOWN_METHOD, "Unsupported AT-SPI method");
    send_reply(reply);
    return DBUS_HANDLER_RESULT_HANDLED;
}

DBusObjectPathVTable object_vtable = {nullptr, message_handler, nullptr, nullptr, nullptr, nullptr};

std::string get_accessibility_bus_address() {
    DBusError error;
    dbus_error_init(&error);
    DBusConnection *session = dbus_bus_get_private(DBUS_BUS_SESSION, &error);
    if (!session) { dbus_error_free(&error); return {}; }
    dbus_connection_set_exit_on_disconnect(session, FALSE);
    DBusMessage *request = dbus_message_new_method_call(a11y_bus_service, a11y_bus_path, a11y_bus_interface, "GetAddress");
    DBusMessage *reply = request ? dbus_connection_send_with_reply_and_block(session, request, 3000, &error) : nullptr;
    if (request) dbus_message_unref(request);
    std::string address;
    const char *raw_address = nullptr;
    if (reply && dbus_message_get_args(reply, &error, DBUS_TYPE_STRING, &raw_address, DBUS_TYPE_INVALID) && raw_address) {
        address = raw_address;
    }
    if (reply) dbus_message_unref(reply);
    dbus_connection_close(session);
    dbus_connection_unref(session);
    dbus_error_free(&error);
    return address;
}

bool register_application() {
    DBusMessage *request = dbus_message_new_method_call(
        registry_service,
        registry_path,
        socket_interface,
        "Embed"
    );
    if (!request) return false;
    DBusMessageIter iter;
    dbus_message_iter_init_append(request, &iter);
    append_reference(&iter, unique_name, root_path);
    dbus_message_set_no_reply(request, TRUE);
    dbus_uint32_t serial = 0;
    const bool sent = dbus_connection_send(accessibility_bus, request, &serial) != FALSE;
    dbus_message_unref(request);
    if (sent) dbus_connection_flush(accessibility_bus);
    return sent;
}

bool ensure_accessibility_bus() {
    if (accessibility_bus) return true;
    const auto now = std::chrono::steady_clock::now();
    if (now < next_connection_attempt) return false;
    next_connection_attempt = now + std::chrono::seconds(5);

    const std::string address = get_accessibility_bus_address();
    if (address.empty()) return false;
    DBusError error;
    dbus_error_init(&error);
    accessibility_bus = dbus_connection_open_private(address.c_str(), &error);
    if (!accessibility_bus || !dbus_bus_register(accessibility_bus, &error)) {
        if (accessibility_bus) {
            dbus_connection_close(accessibility_bus);
            dbus_connection_unref(accessibility_bus);
        }
        accessibility_bus = nullptr;
        dbus_error_free(&error);
        return false;
    }
    dbus_connection_set_exit_on_disconnect(accessibility_bus, FALSE);
    unique_name = safe(dbus_bus_get_unique_name(accessibility_bus));
    accessibility_bus_address = address;
    const bool paths_registered =
        dbus_connection_register_fallback(accessibility_bus, accessible_prefix, &object_vtable, nullptr) &&
        dbus_connection_register_object_path(accessibility_bus, cache_path, &object_vtable, nullptr);
    if (!paths_registered || unique_name.empty() || !register_application()) {
        dbus_connection_close(accessibility_bus);
        dbus_connection_unref(accessibility_bus);
        accessibility_bus = nullptr;
        unique_name.clear();
        accessibility_bus_address.clear();
        dbus_error_free(&error);
        return false;
    }
    dbus_error_free(&error);
    return true;
}

void emit_cache_signal(const char *member, const Object &object) {
    if (!accessibility_bus) return;
    DBusMessage *signal = dbus_message_new_signal(cache_path, cache_interface, member);
    if (!signal) return;
    DBusMessageIter iter;
    dbus_message_iter_init_append(signal, &iter);
    if (std::strcmp(member, "AddAccessible") == 0) {
        append_cache_item(&iter, object);
    } else {
        append_object_reference(&iter, object);
    }
    dbus_uint32_t serial = 0;
    dbus_connection_send(accessibility_bus, signal, &serial);
    dbus_message_unref(signal);
}

void append_empty_event_properties(DBusMessageIter *iter) {
    DBusMessageIter attributes;
    dbus_message_iter_open_container(iter, DBUS_TYPE_ARRAY, "{sv}", &attributes);
    dbus_message_iter_close_container(iter, &attributes);
}

void emit_object_event(
    const std::string &path,
    const char *member,
    const char *detail,
    int32_t detail1,
    int32_t detail2,
    const char *variant_signature,
    const std::function<void(DBusMessageIter *)> &append_variant
) {
    if (!accessibility_bus) return;
    DBusMessage *signal = dbus_message_new_signal(path.c_str(), object_event_interface, member);
    if (!signal) return;
    DBusMessageIter iter;
    DBusMessageIter variant;
    dbus_message_iter_init_append(signal, &iter);
    dbus_message_iter_append_basic(&iter, DBUS_TYPE_STRING, &detail);
    dbus_message_iter_append_basic(&iter, DBUS_TYPE_INT32, &detail1);
    dbus_message_iter_append_basic(&iter, DBUS_TYPE_INT32, &detail2);
    dbus_message_iter_open_container(&iter, DBUS_TYPE_VARIANT, variant_signature, &variant);
    append_variant(&variant);
    dbus_message_iter_close_container(&iter, &variant);
    append_empty_event_properties(&iter);
    dbus_uint32_t serial = 0;
    dbus_connection_send(accessibility_bus, signal, &serial);
    dbus_message_unref(signal);
}

void emit_children_changed_path(
    const std::string &parent_path,
    const std::string &child_path,
    int32_t index,
    bool added
) {
    emit_object_event(
        parent_path,
        "ChildrenChanged",
        added ? "add" : "remove",
        index,
        0,
        "(so)",
        [&](DBusMessageIter *variant) { append_reference(variant, unique_name, child_path); }
    );
}

void emit_children_changed(const Object &parent, const Object &child, bool added) {
    if (parent.kind == ObjectKind::Missing || child.kind == ObjectKind::Missing) return;
    emit_children_changed_path(
        object_path(parent),
        object_path(child),
        object_index_in_parent(child),
        added
    );
}

void emit_property_string(const std::string &path, const char *property, const std::string &value) {
    emit_object_event(path, "PropertyChange", property, 0, 0, "s", [&](DBusMessageIter *variant) {
        const char *text = value.c_str();
        dbus_message_iter_append_basic(variant, DBUS_TYPE_STRING, &text);
    });
}

void emit_property_uint32(const std::string &path, const char *property, uint32_t value) {
    emit_object_event(path, "PropertyChange", property, 0, 0, "u", [&](DBusMessageIter *variant) {
        dbus_message_iter_append_basic(variant, DBUS_TYPE_UINT32, &value);
    });
}

void emit_property_double(const std::string &path, const char *property, double value) {
    emit_object_event(path, "PropertyChange", property, 0, 0, "d", [&](DBusMessageIter *variant) {
        dbus_message_iter_append_basic(variant, DBUS_TYPE_DOUBLE, &value);
    });
}

const char *state_name(uint32_t state) {
    static const char *names[] = {
        "invalid", "active", "armed", "busy", "checked", "collapsed", "defunct",
        "editable", "enabled", "expandable", "expanded", "focusable", "focused",
        "has-tooltip", "horizontal", "iconified", "modal", "multi-line",
        "multiselectable", "opaque", "pressed", "resizable", "selectable", "selected",
        "sensitive", "showing", "single-line", "stale", "transient", "vertical", "visible",
        "manages-descendants", "indeterminate", "required", "truncated", "animated",
        "invalid-entry", "supports-autocompletion", "selectable-text", "is-default", "visited",
        "checkable", "has-popup", "read-only"
    };
    return state < sizeof(names) / sizeof(names[0]) ? names[state] : "unknown";
}

void emit_state_changes(const std::string &path, uint64_t before, uint64_t after) {
    const uint64_t changed = before ^ after;
    for (uint32_t state = 0; state < 64; ++state) {
        if (!(changed & state_bit(state))) continue;
        const int32_t enabled = (after & state_bit(state)) ? 1 : 0;
        emit_object_event(path, "StateChanged", state_name(state), enabled, 0, "i", [&](DBusMessageIter *variant) {
            int32_t value = 0;
            dbus_message_iter_append_basic(variant, DBUS_TYPE_INT32, &value);
        });
    }
}

void emit_bounds_changed(const std::string &path, const Node &node) {
    emit_object_event(path, "BoundsChanged", "", 0, 0, "(iiii)", [&](DBusMessageIter *variant) {
        DBusMessageIter bounds;
        dbus_message_iter_open_container(variant, DBUS_TYPE_STRUCT, nullptr, &bounds);
        dbus_message_iter_append_basic(&bounds, DBUS_TYPE_INT32, &node.x);
        dbus_message_iter_append_basic(&bounds, DBUS_TYPE_INT32, &node.y);
        dbus_message_iter_append_basic(&bounds, DBUS_TYPE_INT32, &node.width);
        dbus_message_iter_append_basic(&bounds, DBUS_TYPE_INT32, &node.height);
        dbus_message_iter_close_container(variant, &bounds);
    });
}

int32_t utf8_character_count(const std::string &text) {
    int32_t count = 0;
    for (unsigned char byte : text) if ((byte & 0xc0u) != 0x80u) ++count;
    return count;
}

void emit_text_replaced(const std::string &path, const std::string &before, const std::string &after) {
    if (!before.empty()) {
        emit_object_event(path, "TextChanged", "delete", 0, utf8_character_count(before), "s", [&](DBusMessageIter *variant) {
            const char *text = before.c_str();
            dbus_message_iter_append_basic(variant, DBUS_TYPE_STRING, &text);
        });
    }
    if (!after.empty()) {
        emit_object_event(path, "TextChanged", "insert", 0, utf8_character_count(after), "s", [&](DBusMessageIter *variant) {
            const char *text = after.c_str();
            dbus_message_iter_append_basic(variant, DBUS_TYPE_STRING, &text);
        });
    }
}

void emit_caret_moved(const std::string &path, int32_t position) {
    emit_object_event(path, "TextCaretMoved", "", position, 0, "i", [&](DBusMessageIter *variant) {
        int32_t value = 0;
        dbus_message_iter_append_basic(variant, DBUS_TYPE_INT32, &value);
    });
}


} // namespace

extern "C" {

void *kld_atspi_window_create(const char *title, void *context, KldAtspiActionCallback callback) {
    ensure_accessibility_bus();
    auto window = std::make_unique<Window>();
    window->serial = next_window_serial++;
    window->path = std::string(accessible_prefix) + "/window_" + std::to_string(window->serial);
    window->title = safe(title);
    window->callback_context = context;
    window->callback = callback;
    Window *raw = window.get();
    windows.push_back(std::move(window));
    if (accessibility_bus) {
        Object object{ObjectKind::Window, raw, nullptr};
        emit_cache_signal("AddAccessible", object);
        emit_children_changed({ObjectKind::Application, nullptr, nullptr}, object, true);
        dbus_connection_flush(accessibility_bus);
    }
    return raw;
}

void kld_atspi_window_begin_update(
    void *raw, const char *title, int visible, int focused, int screen_x, int screen_y, int width, int height
) {
    Window *window = window_from_raw(raw);
    if (!window) return;
    window->title = safe(title);
    window->visible = visible != 0;
    window->focused = focused != 0;
    window->screen_x = screen_x;
    window->screen_y = screen_y;
    window->width = std::max(1, width);
    window->height = std::max(1, height);
    window->pending_nodes.clear();
}

int kld_atspi_window_add_node(
    void *raw, int32_t node_id, int32_t parent_id, uint32_t role, const char *name,
    const char *description, const char *accessible_id, const char *text, uint64_t states,
    int32_t x, int32_t y, int32_t width, int32_t height, int32_t selection_start, int32_t selection_end
) {
    Window *window = window_from_raw(raw);
    if (!window) return 0;
    Node node;
    node.id = node_id;
    node.parent_id = parent_id;
    node.role = role;
    node.path = window->path + (node_id < 0 ? "/node_n" : "/node_") + std::to_string(node_id < 0 ? -static_cast<int64_t>(node_id) : node_id);
    node.name = safe(name);
    node.description = safe(description);
    node.accessible_id = safe(accessible_id);
    node.text = safe(text);
    node.states = states;
    node.x = x;
    node.y = y;
    node.width = std::max(0, width);
    node.height = std::max(0, height);
    node.selection_start = std::max(0, selection_start);
    node.selection_end = std::max(node.selection_start, selection_end);
    window->pending_nodes[node_id] = std::move(node);
    return 1;
}

int kld_atspi_window_add_action(
    void *raw, int32_t node_id, int32_t action_id, const char *name,
    const char *description, const char *key_binding
) {
    Window *window = window_from_raw(raw);
    if (!window) return 0;
    auto found = window->pending_nodes.find(node_id);
    if (found == window->pending_nodes.end()) return 0;
    found->second.actions.push_back({action_id, safe(name), safe(description), safe(key_binding)});
    return 1;
}

int kld_atspi_window_set_value(
    void *raw, int32_t node_id, double minimum, double maximum, double current,
    double increment, int32_t action_id
) {
    Window *window = window_from_raw(raw);
    if (!window) return 0;
    auto found = window->pending_nodes.find(node_id);
    if (found == window->pending_nodes.end()) return 0;
    found->second.has_value = true;
    found->second.minimum = minimum;
    found->second.maximum = maximum;
    found->second.current = current;
    found->second.increment = increment;
    found->second.value_action_id = action_id;
    return 1;
}

void kld_atspi_window_commit_update(void *raw) {
    Window *window = window_from_raw(raw);
    if (!window) return;
    ensure_accessibility_bus();

    std::map<int32_t, Node> previous_nodes = std::move(window->nodes);
    std::vector<int32_t> previous_root_nodes = std::move(window->root_nodes);
    window->nodes = std::move(window->pending_nodes);
    window->pending_nodes.clear();
    window->root_nodes.clear();
    for (auto &[id, node] : window->nodes) node.children.clear();
    for (auto &[id, node] : window->nodes) {
        auto parent = window->nodes.find(node.parent_id);
        if (parent == window->nodes.end()) window->root_nodes.push_back(id);
        else parent->second.children.push_back(id);
    }

    if (!accessibility_bus) return;

    for (const auto &[id, previous] : previous_nodes) {
        const auto current = window->nodes.find(id);
        const int32_t previous_index =
            index_for_node(id, previous, previous_nodes, previous_root_nodes);
        const std::string previous_parent = parent_path_for_node(*window, previous, previous_nodes);
        if (current == window->nodes.end()) {
            emit_children_changed_path(previous_parent, previous.path, previous_index, false);
            emit_cache_signal("RemoveAccessible", {ObjectKind::Node, window, const_cast<Node *>(&previous)});
            continue;
        }

        Node &next = current->second;
        const int32_t next_index = index_for_node(id, next, window->nodes, window->root_nodes);
        const std::string next_parent = parent_path_for_node(*window, next, window->nodes);
        if (previous_parent != next_parent || previous_index != next_index) {
            emit_children_changed_path(previous_parent, previous.path, previous_index, false);
            emit_children_changed_path(next_parent, next.path, next_index, true);
        }
        if (previous.name != next.name) emit_property_string(next.path, "accessible-name", next.name);
        if (previous.description != next.description) {
            emit_property_string(next.path, "accessible-description", next.description);
        }
        if (previous.accessible_id != next.accessible_id) {
            emit_property_string(next.path, "accessible-id", next.accessible_id);
        }
        if (previous.role != next.role) emit_property_uint32(next.path, "accessible-role", next.role);
        emit_state_changes(next.path, previous.states, next.states);
        if (previous.x != next.x || previous.y != next.y || previous.width != next.width ||
            previous.height != next.height) {
            emit_bounds_changed(next.path, next);
        }
        if (previous.text != next.text) emit_text_replaced(next.path, previous.text, next.text);
        if (previous.selection_start != next.selection_start || previous.selection_end != next.selection_end) {
            emit_object_event(next.path, "TextSelectionChanged", "", 0, 0, "i", [&](DBusMessageIter *variant) {
                int32_t value = 0;
                dbus_message_iter_append_basic(variant, DBUS_TYPE_INT32, &value);
            });
            emit_caret_moved(next.path, next.selection_end);
        }
        if (previous.has_value && next.has_value && previous.current != next.current) {
            emit_property_double(next.path, "accessible-value", next.current);
        }
    }

    for (auto &[id, node] : window->nodes) {
        if (previous_nodes.find(id) != previous_nodes.end()) continue;
        emit_cache_signal("AddAccessible", {ObjectKind::Node, window, &node});
        emit_children_changed_path(
            parent_path_for_node(*window, node, window->nodes),
            node.path,
            index_for_node(id, node, window->nodes, window->root_nodes),
            true
        );
    }
    dbus_connection_flush(accessibility_bus);
}

void kld_atspi_window_destroy(void *raw) {
    Window *window = window_from_raw(raw);
    if (!window) return;
    if (accessibility_bus) {
        for (auto &[id, node] : window->nodes) emit_cache_signal("RemoveAccessible", {ObjectKind::Node, window, &node});
        Object object{ObjectKind::Window, window, nullptr};
        emit_children_changed({ObjectKind::Application, nullptr, nullptr}, object, false);
        emit_cache_signal("RemoveAccessible", object);
        dbus_connection_flush(accessibility_bus);
    }
    windows.erase(std::remove_if(windows.begin(), windows.end(), [window](const std::unique_ptr<Window> &candidate) {
        return candidate.get() == window;
    }), windows.end());
}

int kld_atspi_poll(void) {
    const bool was_connected = accessibility_bus != nullptr;
    if (!ensure_accessibility_bus()) return 0;
    if (!was_connected) {
        for (const auto &window : windows) {
            Object object{ObjectKind::Window, window.get(), nullptr};
            emit_cache_signal("AddAccessible", object);
            emit_children_changed({ObjectKind::Application, nullptr, nullptr}, object, true);
        }
        dbus_connection_flush(accessibility_bus);
    }
    dbus_connection_read_write(accessibility_bus, 0);
    while (dbus_connection_dispatch(accessibility_bus) == DBUS_DISPATCH_DATA_REMAINS) {}
    return was_connected ? 0 : 1;
}

int kld_atspi_is_connected(void) {
    return accessibility_bus ? 1 : 0;
}

void kld_atspi_shutdown(void) {
    windows.clear();
    application_id = 0;
    if (!accessibility_bus) return;
    dbus_connection_close(accessibility_bus);
    dbus_connection_unref(accessibility_bus);
    accessibility_bus = nullptr;
    unique_name.clear();
    accessibility_bus_address.clear();
    next_connection_attempt = {};
}

} // extern "C"
