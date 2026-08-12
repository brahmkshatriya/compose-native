#include "native_tray.h"

#include <dbus/dbus.h>
#include <unistd.h>

#include <algorithm>
#include <cstdlib>
#include <cstring>
#include <deque>
#include <string>
#include <utility>
#include <vector>

namespace {

constexpr const char *watcher_service = "org.kde.StatusNotifierWatcher";
constexpr const char *watcher_path = "/StatusNotifierWatcher";
constexpr const char *watcher_interface = "org.kde.StatusNotifierWatcher";
constexpr const char *item_path = "/StatusNotifierItem";
constexpr const char *item_interface = "org.kde.StatusNotifierItem";
constexpr const char *menu_path = "/Menu";
constexpr const char *menu_interface = "com.canonical.dbusmenu";
constexpr const char *properties_interface = "org.freedesktop.DBus.Properties";
constexpr const char *introspectable_interface = "org.freedesktop.DBus.Introspectable";

struct MenuItem {
    int parent_id = 0;
    int id = 0;
    int type = 0; // 0 item, 1 submenu, 2 separator, 3 checkbox, 4 radio
    std::string label;
    bool enabled = true;
    bool checked = false;
};

struct TrayEvent {
    int type = 0;
    int item_id = 0;
};

struct Tray {
    DBusConnection *connection = nullptr;
    std::string service;
    std::string title;
    std::string tooltip;
    std::vector<unsigned char> icon_argb;
    int icon_width = 0;
    int icon_height = 0;
    std::vector<MenuItem> menu;
    uint32_t revision = 1;
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

bool watcher_available() {
    DBusError error;
    dbus_error_init(&error);
    DBusConnection *bus = dbus_bus_get(DBUS_BUS_SESSION, &error);
    if (!bus) {
        dbus_error_free(&error);
        return false;
    }
    const dbus_bool_t owned = dbus_bus_name_has_owner(bus, watcher_service, &error);
    const bool ok = !dbus_error_is_set(&error) && owned;
    dbus_error_free(&error);
    return ok;
}

void set_icon(Tray *tray, const unsigned char *pixels, int width, int height, int stride) {
    tray->icon_argb.clear();
    tray->icon_width = 0;
    tray->icon_height = 0;
    if (!pixels || width <= 0 || height <= 0 || stride < width * 4) return;
    tray->icon_argb.resize(static_cast<size_t>(width) * static_cast<size_t>(height) * 4u);
    for (int y = 0; y < height; ++y) {
        const unsigned char *source = pixels + static_cast<size_t>(y) * static_cast<size_t>(stride);
        unsigned char *target = tray->icon_argb.data() +
            static_cast<size_t>(y) * static_cast<size_t>(width) * 4u;
        for (int x = 0; x < width; ++x) {
            const unsigned char b = source[x * 4 + 0];
            const unsigned char g = source[x * 4 + 1];
            const unsigned char r = source[x * 4 + 2];
            const unsigned char a = source[x * 4 + 3];
            target[x * 4 + 0] = a;
            target[x * 4 + 1] = r;
            target[x * 4 + 2] = g;
            target[x * 4 + 3] = b;
        }
    }
    tray->icon_width = width;
    tray->icon_height = height;
}

DBusMessage *empty_reply(DBusMessage *message) {
    return dbus_message_new_method_return(message);
}

DBusMessage *error_reply(DBusMessage *message, const char *name, const char *text) {
    return dbus_message_new_error(message, name, text);
}

bool append_variant_string(DBusMessageIter *dictionary, const char *name, const std::string &value) {
    DBusMessageIter entry;
    DBusMessageIter variant;
    const char *key = name;
    const char *raw = value.c_str();
    return dbus_message_iter_open_container(dictionary, DBUS_TYPE_DICT_ENTRY, nullptr, &entry) &&
        dbus_message_iter_append_basic(&entry, DBUS_TYPE_STRING, &key) &&
        dbus_message_iter_open_container(&entry, DBUS_TYPE_VARIANT, "s", &variant) &&
        dbus_message_iter_append_basic(&variant, DBUS_TYPE_STRING, &raw) &&
        dbus_message_iter_close_container(&entry, &variant) &&
        dbus_message_iter_close_container(dictionary, &entry);
}

bool append_variant_bool(DBusMessageIter *dictionary, const char *name, bool value) {
    DBusMessageIter entry;
    DBusMessageIter variant;
    const char *key = name;
    dbus_bool_t raw = value;
    return dbus_message_iter_open_container(dictionary, DBUS_TYPE_DICT_ENTRY, nullptr, &entry) &&
        dbus_message_iter_append_basic(&entry, DBUS_TYPE_STRING, &key) &&
        dbus_message_iter_open_container(&entry, DBUS_TYPE_VARIANT, "b", &variant) &&
        dbus_message_iter_append_basic(&variant, DBUS_TYPE_BOOLEAN, &raw) &&
        dbus_message_iter_close_container(&entry, &variant) &&
        dbus_message_iter_close_container(dictionary, &entry);
}

bool append_variant_int(DBusMessageIter *dictionary, const char *name, int value) {
    DBusMessageIter entry;
    DBusMessageIter variant;
    const char *key = name;
    dbus_int32_t raw = value;
    return dbus_message_iter_open_container(dictionary, DBUS_TYPE_DICT_ENTRY, nullptr, &entry) &&
        dbus_message_iter_append_basic(&entry, DBUS_TYPE_STRING, &key) &&
        dbus_message_iter_open_container(&entry, DBUS_TYPE_VARIANT, "i", &variant) &&
        dbus_message_iter_append_basic(&variant, DBUS_TYPE_INT32, &raw) &&
        dbus_message_iter_close_container(&entry, &variant) &&
        dbus_message_iter_close_container(dictionary, &entry);
}

bool append_icon_pixmap_value(DBusMessageIter *target, const Tray *tray) {
    DBusMessageIter array;
    if (!dbus_message_iter_open_container(target, DBUS_TYPE_ARRAY, "(iiay)", &array)) return false;
    if (!tray->icon_argb.empty()) {
        DBusMessageIter structure;
        DBusMessageIter bytes;
        dbus_int32_t width = tray->icon_width;
        dbus_int32_t height = tray->icon_height;
        const unsigned char *data = tray->icon_argb.data();
        const int length = static_cast<int>(tray->icon_argb.size());
        if (!dbus_message_iter_open_container(&array, DBUS_TYPE_STRUCT, nullptr, &structure) ||
            !dbus_message_iter_append_basic(&structure, DBUS_TYPE_INT32, &width) ||
            !dbus_message_iter_append_basic(&structure, DBUS_TYPE_INT32, &height) ||
            !dbus_message_iter_open_container(&structure, DBUS_TYPE_ARRAY, "y", &bytes) ||
            !dbus_message_iter_append_fixed_array(&bytes, DBUS_TYPE_BYTE, &data, length) ||
            !dbus_message_iter_close_container(&structure, &bytes) ||
            !dbus_message_iter_close_container(&array, &structure)) {
            return false;
        }
    }
    return dbus_message_iter_close_container(target, &array);
}

bool append_variant_icon_pixmap(DBusMessageIter *dictionary, const char *name, const Tray *tray) {
    DBusMessageIter entry;
    DBusMessageIter variant;
    const char *key = name;
    return dbus_message_iter_open_container(dictionary, DBUS_TYPE_DICT_ENTRY, nullptr, &entry) &&
        dbus_message_iter_append_basic(&entry, DBUS_TYPE_STRING, &key) &&
        dbus_message_iter_open_container(&entry, DBUS_TYPE_VARIANT, "a(iiay)", &variant) &&
        append_icon_pixmap_value(&variant, tray) &&
        dbus_message_iter_close_container(&entry, &variant) &&
        dbus_message_iter_close_container(dictionary, &entry);
}

bool append_variant_path(DBusMessageIter *dictionary, const char *name, const char *path) {
    DBusMessageIter entry;
    DBusMessageIter variant;
    const char *key = name;
    const char *raw = path;
    return dbus_message_iter_open_container(dictionary, DBUS_TYPE_DICT_ENTRY, nullptr, &entry) &&
        dbus_message_iter_append_basic(&entry, DBUS_TYPE_STRING, &key) &&
        dbus_message_iter_open_container(&entry, DBUS_TYPE_VARIANT, "o", &variant) &&
        dbus_message_iter_append_basic(&variant, DBUS_TYPE_OBJECT_PATH, &raw) &&
        dbus_message_iter_close_container(&entry, &variant) &&
        dbus_message_iter_close_container(dictionary, &entry);
}

bool append_tooltip_variant(DBusMessageIter *dictionary, const Tray *tray) {
    DBusMessageIter entry;
    DBusMessageIter variant;
    DBusMessageIter structure;
    const char *key = "ToolTip";
    const char *empty = "";
    const char *title = tray->title.c_str();
    const char *tooltip = tray->tooltip.c_str();
    return dbus_message_iter_open_container(dictionary, DBUS_TYPE_DICT_ENTRY, nullptr, &entry) &&
        dbus_message_iter_append_basic(&entry, DBUS_TYPE_STRING, &key) &&
        dbus_message_iter_open_container(&entry, DBUS_TYPE_VARIANT, "(sa(iiay)ss)", &variant) &&
        dbus_message_iter_open_container(&variant, DBUS_TYPE_STRUCT, nullptr, &structure) &&
        dbus_message_iter_append_basic(&structure, DBUS_TYPE_STRING, &empty) &&
        append_icon_pixmap_value(&structure, tray) &&
        dbus_message_iter_append_basic(&structure, DBUS_TYPE_STRING, &title) &&
        dbus_message_iter_append_basic(&structure, DBUS_TYPE_STRING, &tooltip) &&
        dbus_message_iter_close_container(&variant, &structure) &&
        dbus_message_iter_close_container(&entry, &variant) &&
        dbus_message_iter_close_container(dictionary, &entry);
}

bool append_item_properties(DBusMessageIter *dictionary, const MenuItem &item) {
    if (item.type == 2) return append_variant_string(dictionary, "type", "separator");
    bool ok = append_variant_string(dictionary, "label", item.label);
    ok = ok && append_variant_bool(dictionary, "enabled", item.enabled);
    ok = ok && append_variant_bool(dictionary, "visible", true);
    if (item.type == 1) ok = ok && append_variant_string(dictionary, "children-display", "submenu");
    if (item.type == 3 || item.type == 4) {
        ok = ok && append_variant_string(
            dictionary, "toggle-type", item.type == 4 ? "radio" : "checkmark");
        ok = ok && append_variant_int(dictionary, "toggle-state", item.checked ? 1 : 0);
    }
    return ok;
}

const MenuItem *find_item(const Tray *tray, int id) {
    for (const MenuItem &item : tray->menu) if (item.id == id) return &item;
    return nullptr;
}

bool append_layout_node(DBusMessageIter *target, const Tray *tray, int id, int depth) {
    DBusMessageIter structure;
    DBusMessageIter properties;
    DBusMessageIter children;
    dbus_int32_t raw_id = id;
    if (!dbus_message_iter_open_container(target, DBUS_TYPE_STRUCT, nullptr, &structure) ||
        !dbus_message_iter_append_basic(&structure, DBUS_TYPE_INT32, &raw_id) ||
        !dbus_message_iter_open_container(&structure, DBUS_TYPE_ARRAY, "{sv}", &properties)) {
        return false;
    }
    const MenuItem *item = id == 0 ? nullptr : find_item(tray, id);
    if (item && !append_item_properties(&properties, *item)) return false;
    if (!dbus_message_iter_close_container(&structure, &properties) ||
        !dbus_message_iter_open_container(&structure, DBUS_TYPE_ARRAY, "v", &children)) {
        return false;
    }
    if (depth != 0) {
        for (const MenuItem &child : tray->menu) {
            if (child.parent_id != id) continue;
            DBusMessageIter variant;
            if (!dbus_message_iter_open_container(&children, DBUS_TYPE_VARIANT, "(ia{sv}av)", &variant) ||
                !append_layout_node(&variant, tray, child.id, depth < 0 ? -1 : depth - 1) ||
                !dbus_message_iter_close_container(&children, &variant)) {
                return false;
            }
        }
    }
    return dbus_message_iter_close_container(&structure, &children) &&
        dbus_message_iter_close_container(target, &structure);
}

DBusMessage *item_get_all(DBusMessage *message, Tray *tray) {
    DBusMessage *reply = dbus_message_new_method_return(message);
    DBusMessageIter arguments;
    DBusMessageIter dictionary;
    dbus_message_iter_init_append(reply, &arguments);
    if (!dbus_message_iter_open_container(&arguments, DBUS_TYPE_ARRAY, "{sv}", &dictionary)) {
        dbus_message_unref(reply);
        return nullptr;
    }
    bool ok = true;
    ok = ok && append_variant_string(&dictionary, "Category", "ApplicationStatus");
    ok = ok && append_variant_string(&dictionary, "Id", "compose-native");
    ok = ok && append_variant_string(&dictionary, "Title", tray->title);
    ok = ok && append_variant_string(&dictionary, "Status", "Active");
    ok = ok && append_variant_string(&dictionary, "IconName", "");
    ok = ok && append_variant_icon_pixmap(&dictionary, "IconPixmap", tray);
    ok = ok && append_variant_string(&dictionary, "AttentionIconName", "");
    ok = ok && append_variant_string(&dictionary, "OverlayIconName", "");
    ok = ok && append_variant_path(&dictionary, "Menu", menu_path);
    ok = ok && append_variant_bool(&dictionary, "ItemIsMenu", false);
    ok = ok && append_tooltip_variant(&dictionary, tray);
    ok = ok && dbus_message_iter_close_container(&arguments, &dictionary);
    if (!ok) {
        dbus_message_unref(reply);
        return nullptr;
    }
    return reply;
}

DBusMessage *item_get(DBusMessage *message, Tray *tray, const char *property) {
    DBusMessage *reply = dbus_message_new_method_return(message);
    DBusMessageIter arguments;
    DBusMessageIter variant;
    dbus_message_iter_init_append(reply, &arguments);
    if (std::strcmp(property, "Category") == 0 || std::strcmp(property, "Id") == 0 ||
        std::strcmp(property, "Title") == 0 || std::strcmp(property, "Status") == 0 ||
        std::strcmp(property, "IconName") == 0 || std::strcmp(property, "AttentionIconName") == 0 ||
        std::strcmp(property, "OverlayIconName") == 0) {
        const std::string value =
            std::strcmp(property, "Category") == 0 ? "ApplicationStatus" :
            std::strcmp(property, "Id") == 0 ? "compose-native" :
            std::strcmp(property, "Title") == 0 ? tray->title :
            std::strcmp(property, "Status") == 0 ? "Active" : "";
        const char *raw = value.c_str();
        if (!dbus_message_iter_open_container(&arguments, DBUS_TYPE_VARIANT, "s", &variant) ||
            !dbus_message_iter_append_basic(&variant, DBUS_TYPE_STRING, &raw) ||
            !dbus_message_iter_close_container(&arguments, &variant)) {
            dbus_message_unref(reply);
            return nullptr;
        }
        return reply;
    }
    if (std::strcmp(property, "IconPixmap") == 0) {
        if (!dbus_message_iter_open_container(&arguments, DBUS_TYPE_VARIANT, "a(iiay)", &variant) ||
            !append_icon_pixmap_value(&variant, tray) ||
            !dbus_message_iter_close_container(&arguments, &variant)) {
            dbus_message_unref(reply);
            return nullptr;
        }
        return reply;
    }
    if (std::strcmp(property, "Menu") == 0) {
        const char *raw = menu_path;
        if (!dbus_message_iter_open_container(&arguments, DBUS_TYPE_VARIANT, "o", &variant) ||
            !dbus_message_iter_append_basic(&variant, DBUS_TYPE_OBJECT_PATH, &raw) ||
            !dbus_message_iter_close_container(&arguments, &variant)) {
            dbus_message_unref(reply);
            return nullptr;
        }
        return reply;
    }
    if (std::strcmp(property, "ItemIsMenu") == 0) {
        dbus_bool_t raw = false;
        if (!dbus_message_iter_open_container(&arguments, DBUS_TYPE_VARIANT, "b", &variant) ||
            !dbus_message_iter_append_basic(&variant, DBUS_TYPE_BOOLEAN, &raw) ||
            !dbus_message_iter_close_container(&arguments, &variant)) {
            dbus_message_unref(reply);
            return nullptr;
        }
        return reply;
    }
    dbus_message_unref(reply);
    return error_reply(message, DBUS_ERROR_UNKNOWN_PROPERTY, "Unknown tray property");
}

const char *item_xml =
    "<node>"
    "<interface name='org.freedesktop.DBus.Introspectable'><method name='Introspect'><arg direction='out' type='s'/></method></interface>"
    "<interface name='org.freedesktop.DBus.Properties'><method name='Get'><arg direction='in' type='s'/><arg direction='in' type='s'/><arg direction='out' type='v'/></method><method name='GetAll'><arg direction='in' type='s'/><arg direction='out' type='a{sv}'/></method></interface>"
    "<interface name='org.kde.StatusNotifierItem'>"
    "<method name='ContextMenu'><arg direction='in' type='i'/><arg direction='in' type='i'/></method>"
    "<method name='Activate'><arg direction='in' type='i'/><arg direction='in' type='i'/></method>"
    "<method name='SecondaryActivate'><arg direction='in' type='i'/><arg direction='in' type='i'/></method>"
    "<method name='Scroll'><arg direction='in' type='i'/><arg direction='in' type='s'/></method>"
    "<property name='Category' type='s' access='read'/><property name='Id' type='s' access='read'/><property name='Title' type='s' access='read'/><property name='Status' type='s' access='read'/><property name='IconName' type='s' access='read'/><property name='IconPixmap' type='a(iiay)' access='read'/><property name='Menu' type='o' access='read'/><property name='ItemIsMenu' type='b' access='read'/><property name='ToolTip' type='(sa(iiay)ss)' access='read'/>"
    "</interface></node>";

const char *menu_xml =
    "<node>"
    "<interface name='org.freedesktop.DBus.Introspectable'><method name='Introspect'><arg direction='out' type='s'/></method></interface>"
    "<interface name='com.canonical.dbusmenu'>"
    "<method name='GetLayout'><arg direction='in' type='i'/><arg direction='in' type='i'/><arg direction='in' type='as'/><arg direction='out' type='u'/><arg direction='out' type='(ia{sv}av)'/></method>"
    "<method name='GetGroupProperties'><arg direction='in' type='ai'/><arg direction='in' type='as'/><arg direction='out' type='a(ia{sv})'/></method>"
    "<method name='GetProperty'><arg direction='in' type='i'/><arg direction='in' type='s'/><arg direction='out' type='v'/></method>"
    "<method name='Event'><arg direction='in' type='i'/><arg direction='in' type='s'/><arg direction='in' type='v'/><arg direction='in' type='u'/></method>"
    "<method name='AboutToShow'><arg direction='in' type='i'/><arg direction='out' type='b'/></method>"
    "<signal name='LayoutUpdated'><arg type='u'/><arg type='i'/></signal>"
    "</interface></node>";

DBusHandlerResult handle_item(DBusConnection *connection, DBusMessage *message, void *data) {
    Tray *tray = static_cast<Tray *>(data);
    DBusMessage *reply = nullptr;
    const char *interface = dbus_message_get_interface(message);
    const char *member = dbus_message_get_member(message);
    if (!interface || !member) return DBUS_HANDLER_RESULT_NOT_YET_HANDLED;
    if (std::strcmp(interface, introspectable_interface) == 0 && std::strcmp(member, "Introspect") == 0) {
        reply = dbus_message_new_method_return(message);
        const char *xml = item_xml;
        dbus_message_append_args(reply, DBUS_TYPE_STRING, &xml, DBUS_TYPE_INVALID);
    } else if (std::strcmp(interface, properties_interface) == 0 && std::strcmp(member, "GetAll") == 0) {
        reply = item_get_all(message, tray);
    } else if (std::strcmp(interface, properties_interface) == 0 && std::strcmp(member, "Get") == 0) {
        DBusError error;
        dbus_error_init(&error);
        const char *requested_interface = nullptr;
        const char *property = nullptr;
        if (dbus_message_get_args(message, &error, DBUS_TYPE_STRING, &requested_interface,
                DBUS_TYPE_STRING, &property, DBUS_TYPE_INVALID) &&
            requested_interface && std::strcmp(requested_interface, item_interface) == 0) {
            reply = item_get(message, tray, property);
        } else {
            reply = error_reply(message, DBUS_ERROR_INVALID_ARGS, "Invalid property request");
        }
        dbus_error_free(&error);
    } else if (std::strcmp(interface, item_interface) == 0) {
        if (std::strcmp(member, "Activate") == 0) tray->events.push_back({1, 0});
        else if (std::strcmp(member, "SecondaryActivate") == 0) tray->events.push_back({2, 0});
        else if (std::strcmp(member, "ContextMenu") == 0) tray->events.push_back({3, 0});
        reply = empty_reply(message);
    }
    if (!reply) return DBUS_HANDLER_RESULT_NOT_YET_HANDLED;
    dbus_connection_send(connection, reply, nullptr);
    dbus_message_unref(reply);
    return DBUS_HANDLER_RESULT_HANDLED;
}

DBusMessage *menu_get_layout(DBusMessage *message, Tray *tray) {
    DBusMessageIter input;
    dbus_int32_t parent = 0;
    dbus_int32_t depth = -1;
    if (!dbus_message_iter_init(message, &input) || dbus_message_iter_get_arg_type(&input) != DBUS_TYPE_INT32) {
        return error_reply(message, DBUS_ERROR_INVALID_ARGS, "Invalid GetLayout request");
    }
    dbus_message_iter_get_basic(&input, &parent);
    dbus_message_iter_next(&input);
    if (dbus_message_iter_get_arg_type(&input) == DBUS_TYPE_INT32) dbus_message_iter_get_basic(&input, &depth);
    if (parent != 0 && !find_item(tray, parent)) {
        return error_reply(message, DBUS_ERROR_INVALID_ARGS, "Unknown menu parent");
    }
    DBusMessage *reply = dbus_message_new_method_return(message);
    DBusMessageIter output;
    dbus_message_iter_init_append(reply, &output);
    dbus_uint32_t revision = tray->revision;
    if (!dbus_message_iter_append_basic(&output, DBUS_TYPE_UINT32, &revision) ||
        !append_layout_node(&output, tray, parent, depth)) {
        dbus_message_unref(reply);
        return nullptr;
    }
    return reply;
}

DBusMessage *menu_get_property(DBusMessage *message, Tray *tray) {
    DBusError error;
    dbus_error_init(&error);
    dbus_int32_t id = 0;
    const char *name = nullptr;
    if (!dbus_message_get_args(message, &error, DBUS_TYPE_INT32, &id,
            DBUS_TYPE_STRING, &name, DBUS_TYPE_INVALID)) {
        dbus_error_free(&error);
        return error_reply(message, DBUS_ERROR_INVALID_ARGS, "Invalid GetProperty request");
    }
    dbus_error_free(&error);
    const MenuItem *item = find_item(tray, id);
    if (!item) return error_reply(message, DBUS_ERROR_INVALID_ARGS, "Unknown menu item");
    DBusMessage *reply = dbus_message_new_method_return(message);
    DBusMessageIter output;
    DBusMessageIter variant;
    dbus_message_iter_init_append(reply, &output);
    if (std::strcmp(name, "label") == 0 || std::strcmp(name, "type") == 0 ||
        std::strcmp(name, "children-display") == 0 || std::strcmp(name, "toggle-type") == 0) {
        const std::string value =
            std::strcmp(name, "label") == 0 ? item->label :
            std::strcmp(name, "type") == 0 ? (item->type == 2 ? "separator" : "") :
            std::strcmp(name, "children-display") == 0 ? (item->type == 1 ? "submenu" : "") :
            item->type == 4 ? "radio" : item->type == 3 ? "checkmark" : "";
        const char *raw = value.c_str();
        dbus_message_iter_open_container(&output, DBUS_TYPE_VARIANT, "s", &variant);
        dbus_message_iter_append_basic(&variant, DBUS_TYPE_STRING, &raw);
        dbus_message_iter_close_container(&output, &variant);
    } else if (std::strcmp(name, "enabled") == 0 || std::strcmp(name, "visible") == 0) {
        dbus_bool_t raw = std::strcmp(name, "visible") == 0 || item->enabled;
        dbus_message_iter_open_container(&output, DBUS_TYPE_VARIANT, "b", &variant);
        dbus_message_iter_append_basic(&variant, DBUS_TYPE_BOOLEAN, &raw);
        dbus_message_iter_close_container(&output, &variant);
    } else if (std::strcmp(name, "toggle-state") == 0) {
        dbus_int32_t raw = item->checked ? 1 : 0;
        dbus_message_iter_open_container(&output, DBUS_TYPE_VARIANT, "i", &variant);
        dbus_message_iter_append_basic(&variant, DBUS_TYPE_INT32, &raw);
        dbus_message_iter_close_container(&output, &variant);
    } else {
        dbus_message_unref(reply);
        return error_reply(message, DBUS_ERROR_UNKNOWN_PROPERTY, "Unknown menu property");
    }
    return reply;
}

DBusMessage *menu_get_group_properties(DBusMessage *message, Tray *tray) {
    DBusMessageIter input;
    if (!dbus_message_iter_init(message, &input) || dbus_message_iter_get_arg_type(&input) != DBUS_TYPE_ARRAY) {
        return error_reply(message, DBUS_ERROR_INVALID_ARGS, "Invalid GetGroupProperties request");
    }
    DBusMessage *reply = dbus_message_new_method_return(message);
    DBusMessageIter output;
    DBusMessageIter array;
    dbus_message_iter_init_append(reply, &output);
    dbus_message_iter_open_container(&output, DBUS_TYPE_ARRAY, "(ia{sv})", &array);
    DBusMessageIter ids;
    dbus_message_iter_recurse(&input, &ids);
    while (dbus_message_iter_get_arg_type(&ids) == DBUS_TYPE_INT32) {
        dbus_int32_t id = 0;
        dbus_message_iter_get_basic(&ids, &id);
        const MenuItem *item = find_item(tray, id);
        if (item) {
            DBusMessageIter structure;
            DBusMessageIter properties;
            dbus_message_iter_open_container(&array, DBUS_TYPE_STRUCT, nullptr, &structure);
            dbus_message_iter_append_basic(&structure, DBUS_TYPE_INT32, &id);
            dbus_message_iter_open_container(&structure, DBUS_TYPE_ARRAY, "{sv}", &properties);
            append_item_properties(&properties, *item);
            dbus_message_iter_close_container(&structure, &properties);
            dbus_message_iter_close_container(&array, &structure);
        }
        dbus_message_iter_next(&ids);
    }
    dbus_message_iter_close_container(&output, &array);
    return reply;
}

DBusHandlerResult handle_menu(DBusConnection *connection, DBusMessage *message, void *data) {
    Tray *tray = static_cast<Tray *>(data);
    DBusMessage *reply = nullptr;
    const char *interface = dbus_message_get_interface(message);
    const char *member = dbus_message_get_member(message);
    if (!interface || !member) return DBUS_HANDLER_RESULT_NOT_YET_HANDLED;
    if (std::strcmp(interface, introspectable_interface) == 0 && std::strcmp(member, "Introspect") == 0) {
        reply = dbus_message_new_method_return(message);
        const char *xml = menu_xml;
        dbus_message_append_args(reply, DBUS_TYPE_STRING, &xml, DBUS_TYPE_INVALID);
    } else if (std::strcmp(interface, menu_interface) == 0 && std::strcmp(member, "GetLayout") == 0) {
        reply = menu_get_layout(message, tray);
    } else if (std::strcmp(interface, menu_interface) == 0 && std::strcmp(member, "GetGroupProperties") == 0) {
        reply = menu_get_group_properties(message, tray);
    } else if (std::strcmp(interface, menu_interface) == 0 && std::strcmp(member, "GetProperty") == 0) {
        reply = menu_get_property(message, tray);
    } else if (std::strcmp(interface, menu_interface) == 0 && std::strcmp(member, "AboutToShow") == 0) {
        reply = dbus_message_new_method_return(message);
        dbus_bool_t update_needed = false;
        dbus_message_append_args(reply, DBUS_TYPE_BOOLEAN, &update_needed, DBUS_TYPE_INVALID);
    } else if (std::strcmp(interface, menu_interface) == 0 && std::strcmp(member, "Event") == 0) {
        DBusMessageIter input;
        dbus_int32_t id = 0;
        const char *event = nullptr;
        if (dbus_message_iter_init(message, &input) && dbus_message_iter_get_arg_type(&input) == DBUS_TYPE_INT32) {
            dbus_message_iter_get_basic(&input, &id);
            dbus_message_iter_next(&input);
            if (dbus_message_iter_get_arg_type(&input) == DBUS_TYPE_STRING) dbus_message_iter_get_basic(&input, &event);
        }
        const MenuItem *item = find_item(tray, id);
        if (event && std::strcmp(event, "clicked") == 0 && item && item->enabled &&
            item->type != 1 && item->type != 2) {
            tray->events.push_back({4, id});
        }
        reply = empty_reply(message);
    }
    if (!reply) return DBUS_HANDLER_RESULT_NOT_YET_HANDLED;
    dbus_connection_send(connection, reply, nullptr);
    dbus_message_unref(reply);
    return DBUS_HANDLER_RESULT_HANDLED;
}

DBusObjectPathVTable item_vtable = {nullptr, handle_item, nullptr, nullptr, nullptr, nullptr};
DBusObjectPathVTable menu_vtable = {nullptr, handle_menu, nullptr, nullptr, nullptr, nullptr};

bool send_signal(Tray *tray, const char *member) {
    DBusMessage *message = dbus_message_new_signal(item_path, item_interface, member);
    if (!message) return false;
    const bool sent = dbus_connection_send(tray->connection, message, nullptr);
    dbus_message_unref(message);
    if (sent) dbus_connection_flush(tray->connection);
    return sent;
}

bool register_with_watcher(Tray *tray, char **error_message) {
    DBusMessage *message = dbus_message_new_method_call(
        watcher_service, watcher_path, watcher_interface, "RegisterStatusNotifierItem");
    if (!message) {
        set_error(error_message, "Could not allocate tray registration request");
        return false;
    }
    const char *service = tray->service.c_str();
    if (!dbus_message_append_args(message, DBUS_TYPE_STRING, &service, DBUS_TYPE_INVALID)) {
        dbus_message_unref(message);
        set_error(error_message, "Could not build tray registration request");
        return false;
    }
    DBusError error;
    dbus_error_init(&error);
    DBusMessage *reply =
        dbus_connection_send_with_reply_and_block(tray->connection, message, 5000, &error);
    dbus_message_unref(message);
    if (!reply) {
        set_error(error_message, error.message ? error.message : "Tray watcher did not reply");
        dbus_error_free(&error);
        return false;
    }
    dbus_error_free(&error);
    dbus_message_unref(reply);
    return true;
}

} // namespace

extern "C" {

int kld_tray_supported(void) { return watcher_available() ? 1 : 0; }

void *kld_tray_create(
    const char *title,
    const char *tooltip,
    const unsigned char *pixels,
    int width,
    int height,
    int stride,
    char **error_message
) {
    if (error_message) *error_message = nullptr;
    if (!watcher_available()) {
        set_error(error_message, "No StatusNotifierWatcher is available");
        return nullptr;
    }
    Tray *tray = new Tray();
    tray->title = title ? title : "Compose";
    tray->tooltip = tooltip ? tooltip : "";
    set_icon(tray, pixels, width, height, stride);
    DBusError error;
    dbus_error_init(&error);
    tray->connection = dbus_bus_get_private(DBUS_BUS_SESSION, &error);
    if (!tray->connection) {
        set_error(error_message, error.message ? error.message : "No D-Bus session bus");
        dbus_error_free(&error);
        delete tray;
        return nullptr;
    }
    dbus_connection_set_exit_on_disconnect(tray->connection, FALSE);
    static uint32_t next_id = 1;
    tray->service = "org.kde.StatusNotifierItem-" + std::to_string(getpid()) + "-" +
        std::to_string(next_id++);
    const int request = dbus_bus_request_name(
        tray->connection, tray->service.c_str(), DBUS_NAME_FLAG_DO_NOT_QUEUE, &error);
    if (dbus_error_is_set(&error) || request != DBUS_REQUEST_NAME_REPLY_PRIMARY_OWNER ||
        !dbus_connection_register_object_path(tray->connection, item_path, &item_vtable, tray) ||
        !dbus_connection_register_object_path(tray->connection, menu_path, &menu_vtable, tray) ||
        !register_with_watcher(tray, error_message)) {
        if (!error_message || !*error_message) {
            set_error(error_message, dbus_error_is_set(&error) ? error.message : "Could not register tray object");
        }
        dbus_error_free(&error);
        dbus_connection_close(tray->connection);
        dbus_connection_unref(tray->connection);
        delete tray;
        return nullptr;
    }
    dbus_error_free(&error);
    dbus_connection_flush(tray->connection);
    return tray;
}

int kld_tray_update(
    void *raw,
    const char *title,
    const char *tooltip,
    const unsigned char *pixels,
    int width,
    int height,
    int stride,
    char **error_message
) {
    if (error_message) *error_message = nullptr;
    Tray *tray = static_cast<Tray *>(raw);
    if (!tray) {
        set_error(error_message, "Tray handle is null");
        return 0;
    }
    const std::string next_title = title ? title : "Compose";
    const std::string next_tooltip = tooltip ? tooltip : "";
    const bool title_changed = tray->title != next_title;
    const bool tooltip_changed = tray->tooltip != next_tooltip;
    tray->title = next_title;
    tray->tooltip = next_tooltip;
    set_icon(tray, pixels, width, height, stride);
    bool ok = send_signal(tray, "NewIcon");
    if (title_changed) ok = send_signal(tray, "NewTitle") && ok;
    if (tooltip_changed) ok = send_signal(tray, "NewToolTip") && ok;
    if (!ok) set_error(error_message, "Could not publish tray update");
    return ok ? 1 : 0;
}

void kld_tray_menu_clear(void *raw) {
    Tray *tray = static_cast<Tray *>(raw);
    if (tray) tray->menu.clear();
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
    if (!tray || item_id <= 0 || item_type < 0 || item_type > 4) return 0;
    tray->menu.push_back({parent_id, item_id, item_type, label ? label : "", enabled != 0, checked != 0});
    return 1;
}

int kld_tray_menu_commit(void *raw) {
    Tray *tray = static_cast<Tray *>(raw);
    if (!tray) return 0;
    tray->revision++;
    DBusMessage *message = dbus_message_new_signal(menu_path, menu_interface, "LayoutUpdated");
    if (!message) return 0;
    dbus_uint32_t revision = tray->revision;
    dbus_int32_t parent = 0;
    const bool built = dbus_message_append_args(
        message, DBUS_TYPE_UINT32, &revision, DBUS_TYPE_INT32, &parent, DBUS_TYPE_INVALID);
    const bool sent = built && dbus_connection_send(tray->connection, message, nullptr);
    dbus_message_unref(message);
    if (sent) dbus_connection_flush(tray->connection);
    return sent ? 1 : 0;
}

int kld_tray_poll(void *raw, int *event_type, int *item_id) {
    if (event_type) *event_type = 0;
    if (item_id) *item_id = 0;
    Tray *tray = static_cast<Tray *>(raw);
    if (!tray || !tray->connection) return 0;
    dbus_connection_read_write_dispatch(tray->connection, 0);
    if (tray->events.empty()) return 0;
    const TrayEvent event = tray->events.front();
    tray->events.pop_front();
    if (event_type) *event_type = event.type;
    if (item_id) *item_id = event.item_id;
    return 1;
}

void kld_tray_destroy(void *raw) {
    Tray *tray = static_cast<Tray *>(raw);
    if (!tray) return;
    if (tray->connection) {
        dbus_connection_unregister_object_path(tray->connection, item_path);
        dbus_connection_unregister_object_path(tray->connection, menu_path);
        DBusError error;
        dbus_error_init(&error);
        dbus_bus_release_name(tray->connection, tray->service.c_str(), &error);
        dbus_error_free(&error);
        dbus_connection_flush(tray->connection);
        dbus_connection_close(tray->connection);
        dbus_connection_unref(tray->connection);
    }
    delete tray;
}

} // extern "C"
