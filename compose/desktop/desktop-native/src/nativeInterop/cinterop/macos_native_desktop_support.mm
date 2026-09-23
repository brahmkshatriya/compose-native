#import <AppKit/AppKit.h>
#import <Foundation/Foundation.h>

#include <SDL3/SDL.h>

#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <deque>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <vector>

#include "include/native_desktop.h"
#include "include/native_drag.h"

@class ComposeNativeDraggingSource;

struct MacDrag {
    SDL_Window *sdl_window = nullptr;
    bool active = false;
    __strong ComposeNativeDraggingSource *source = nil;
};

@interface ComposeNativeNotificationDelegate : NSObject <NSUserNotificationCenterDelegate>
@end

@interface ComposeNativeDraggingSource : NSObject <NSDraggingSource>
@property(nonatomic, assign) MacDrag *owner;
@end

namespace {

struct ThemeObserver {
    uint32_t color_scheme_event_type = 0;
    uint32_t accent_color_event_type = 0;
};

struct NotificationActionSpec {
    std::string id;
    std::string label;
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
    std::vector<NotificationActionSpec> actions;
};

struct DesktopEvent {
    int type = 0;
    uint32_t id = 0;
    uint32_t reason = 0;
    std::string value;
};

uint32_t next_notification_id = 1;
std::unordered_map<uint32_t, __strong NSUserNotification *> active_notifications;
std::unordered_set<uint32_t> fallback_notifications;
std::unordered_map<uint32_t, std::vector<NotificationActionSpec>> notification_actions;
std::deque<DesktopEvent> desktop_events;
__strong ComposeNativeNotificationDelegate *notification_delegate = nil;

char *copy_string(const char *value) {
    if (!value) return nullptr;
    const size_t length = std::strlen(value) + 1;
    char *result = static_cast<char *>(std::malloc(length));
    if (result) std::memcpy(result, value, length);
    return result;
}

void set_error(char **output, const char *message) {
    if (output) *output = copy_string(message ? message : "Unknown macOS desktop error");
}

NSString *ns_string(const std::string &value) {
    return [[NSString alloc] initWithBytes:value.data()
                                    length:value.size()
                                  encoding:NSUTF8StringEncoding];
}

NSString *ns_string(const char *value) {
    if (!value) return @"";
    return [NSString stringWithUTF8String:value] ?: @"";
}

NSWindow *cocoa_window(SDL_Window *window) {
    if (!window) return nil;
    SDL_PropertiesID properties = SDL_GetWindowProperties(window);
    if (properties == 0) return nil;
    void *pointer = SDL_GetPointerProperty(
        properties,
        SDL_PROP_WINDOW_COCOA_WINDOW_POINTER,
        nullptr
    );
    return (__bridge NSWindow *)pointer;
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
    NSColor *accent = [NSColor controlAccentColor];
    NSColor *rgb = [accent colorUsingColorSpace:[NSColorSpace sRGBColorSpace]];
    if (!rgb) return 0;
    const uint32_t red = static_cast<uint32_t>(std::lround(rgb.redComponent * 255.0));
    const uint32_t green = static_cast<uint32_t>(std::lround(rgb.greenComponent * 255.0));
    const uint32_t blue = static_cast<uint32_t>(std::lround(rgb.blueComponent * 255.0));
    return 0x01000000u | (red << 16) | (green << 8) | blue;
}

void push_theme_events(ThemeObserver *observer) {
    if (!observer) return;
    if (observer->color_scheme_event_type != 0) {
        SDL_Event event{};
        event.type = observer->color_scheme_event_type;
        event.user.code = current_theme();
        SDL_PushEvent(&event);
    }
    if (observer->accent_color_event_type != 0) {
        SDL_Event event{};
        event.type = observer->accent_color_event_type;
        event.user.code = static_cast<Sint32>(current_accent());
        SDL_PushEvent(&event);
    }
}

bool SDLCALL watch_system_theme(void *userdata, SDL_Event *event) {
    auto *observer = static_cast<ThemeObserver *>(userdata);
    if (!observer || !event || event->type != SDL_EVENT_SYSTEM_THEME_CHANGED) return true;
    push_theme_events(observer);
    return true;
}

NSUserNotificationCenter *notification_center() {
    return [NSUserNotificationCenter defaultUserNotificationCenter];
}

bool osascript_notifications_supported() {
    return [[NSFileManager defaultManager] isExecutableFileAtPath:@"/usr/bin/osascript"];
}

void ensure_notification_delegate() {
    NSUserNotificationCenter *center = notification_center();
    if (!center || notification_delegate) return;
    notification_delegate = [[ComposeNativeNotificationDelegate alloc] init];
    center.delegate = notification_delegate;
}

bool deliver_osascript_notification(NSString *title, NSString *body, char **error_message) {
    NSTask *task = [[NSTask alloc] init];
    task.launchPath = @"/usr/bin/osascript";
    task.arguments = @[
        @"-e", @"on run argv",
        @"-e", @"display notification (item 1 of argv) with title (item 2 of argv)",
        @"-e", @"end run",
        @"--",
        body ?: @"",
        title ?: @"Compose",
    ];
    NSPipe *error_pipe = [NSPipe pipe];
    task.standardError = error_pipe;
    @try {
        [task launch];
        [task waitUntilExit];
    } @catch (NSException *exception) {
        set_error(error_message, exception.reason.UTF8String);
        return false;
    }
    if (task.terminationStatus == 0) return true;
    NSData *error_data = [error_pipe.fileHandleForReading readDataToEndOfFile];
    NSString *detail = [[NSString alloc] initWithData:error_data encoding:NSUTF8StringEncoding];
    set_error(
        error_message,
        detail.length > 0 ? detail.UTF8String : "osascript could not deliver the notification"
    );
    return false;
}

uint32_t notification_id(NSUserNotification *notification) {
    NSNumber *number = notification.userInfo[@"compose.native.notification.id"];
    return number ? number.unsignedIntValue : 0;
}

void push_notification_closed(uint32_t id, uint32_t reason) {
    if (id == 0) return;
    active_notifications.erase(id);
    fallback_notifications.erase(id);
    notification_actions.erase(id);
    desktop_events.push_back({2, id, reason, {}});
}

void remove_notification(uint32_t id, bool emit_closed) {
    auto it = active_notifications.find(id);
    if (it != active_notifications.end()) {
        [notification_center() removeDeliveredNotification:it->second];
    } else if (fallback_notifications.count(id) == 0) {
        return;
    }
    if (emit_closed) {
        push_notification_closed(id, 3);
    } else {
        active_notifications.erase(id);
        fallback_notifications.erase(id);
        notification_actions.erase(id);
    }
}

NSImage *application_icon() {
    NSImage *icon = NSApp.applicationIconImage;
    return icon ?: [NSImage imageNamed:NSImageNameApplicationIcon];
}

NSArray<NSString *> *parse_uri_list(const char *uri_list) {
    if (!uri_list || !*uri_list) return @[];
    NSMutableArray<NSString *> *result = [NSMutableArray array];
    NSString *input = ns_string(uri_list);
    [input enumerateLinesUsingBlock:^(NSString *line, BOOL *) {
        NSString *trimmed =
            [line stringByTrimmingCharactersInSet:[NSCharacterSet whitespaceAndNewlineCharacterSet]];
        if (trimmed.length > 0 && ![trimmed hasPrefix:@"#"]) [result addObject:trimmed];
    }];
    return result;
}

NSDraggingItem *dragging_item(id<NSPasteboardWriting> writer, NSImage *image, NSPoint point) {
    NSDraggingItem *item = [[NSDraggingItem alloc] initWithPasteboardWriter:writer];
    NSSize size = image ? image.size : NSMakeSize(48.0, 48.0);
    if (size.width <= 0 || size.height <= 0) size = NSMakeSize(48.0, 48.0);
    NSRect frame = NSMakeRect(point.x - size.width / 2.0, point.y - size.height / 2.0, size.width, size.height);
    [item setDraggingFrame:frame contents:image];
    return item;
}

}  // namespace

@implementation ComposeNativeNotificationDelegate

- (BOOL)userNotificationCenter:(NSUserNotificationCenter *)center
     shouldPresentNotification:(NSUserNotification *)notification {
    return YES;
}

- (void)userNotificationCenter:(NSUserNotificationCenter *)center
       didActivateNotification:(NSUserNotification *)notification {
    const uint32_t id = notification_id(notification);
    if (id == 0) return;
    std::string action_id;
    const auto actions = notification_actions.find(id);
    if (notification.activationType == NSUserNotificationActivationTypeActionButtonClicked &&
        actions != notification_actions.end() && !actions->second.empty()) {
        action_id = actions->second.front().id;
    } else if (notification.activationType == NSUserNotificationActivationTypeAdditionalActionClicked &&
               actions != notification_actions.end()) {
        NSUserNotificationAction *selected = notification.additionalActivationAction;
        NSString *identifier = selected.identifier;
        if (identifier) action_id = identifier.UTF8String ?: "";
    }
    if (!action_id.empty()) desktop_events.push_back({1, id, 0, action_id});
    [center removeDeliveredNotification:notification];
    push_notification_closed(id, 2);
}

@end

@implementation ComposeNativeDraggingSource

- (NSDragOperation)draggingSession:(NSDraggingSession *)session
    sourceOperationMaskForDraggingContext:(NSDraggingContext)context {
    return NSDragOperationCopy;
}

- (void)draggingSession:(NSDraggingSession *)session
             endedAtPoint:(NSPoint)screenPoint
                operation:(NSDragOperation)operation {
    if (self.owner) self.owner->active = false;
}

@end

extern "C" {

void *kld_system_theme_observer_create(
    uint32_t color_scheme_event_type,
    uint32_t accent_color_event_type
) {
    auto *observer = new ThemeObserver{color_scheme_event_type, accent_color_event_type};
    SDL_AddEventWatch(watch_system_theme, observer);
    return observer;
}

int kld_system_theme_observer_current(void *) { return current_theme(); }
uint32_t kld_system_theme_observer_accent(void *) { return current_accent(); }

void kld_system_theme_observer_destroy(void *raw) {
    auto *observer = static_cast<ThemeObserver *>(raw);
    if (!observer) return;
    SDL_RemoveEventWatch(watch_system_theme, observer);
    delete observer;
}

int kld_notifications_supported(void) {
    ensure_notification_delegate();
    return notification_center() || osascript_notifications_supported() ? 1 : 0;
}

char *kld_notification_capabilities(char **error_message) {
    if (error_message) *error_message = nullptr;
    if (!kld_notifications_supported()) {
        set_error(error_message, "macOS notification delivery is unavailable");
        return nullptr;
    }
    return copy_string(
        notification_center() ? "body\nactions\npersistence\n" : "body\n"
    );
}

void *kld_notification_create(
    const char *application_name,
    const char *title,
    const char *body,
    const char *icon_name,
    uint32_t replaces_id,
    int timeout_millis
) {
    auto *builder = new NotificationBuilder();
    builder->application_name = application_name ? application_name : "Compose";
    builder->title = title ? title : "";
    builder->message = body ? body : "";
    builder->icon_name = icon_name ? icon_name : "";
    builder->replaces_id = replaces_id;
    builder->timeout_millis = timeout_millis;
    return builder;
}

int kld_notification_add_action(void *raw, const char *id, const char *label) {
    auto *builder = static_cast<NotificationBuilder *>(raw);
    if (!builder || !id || !label) return 0;
    builder->actions.push_back({id, label});
    return 1;
}

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
        set_error(error_message, "Notification builder is null");
        return 0;
    }
    ensure_notification_delegate();
    uint32_t id = builder->replaces_id;
    const bool replaces_existing =
        id != 0 &&
        (active_notifications.count(id) != 0 || fallback_notifications.count(id) != 0);
    if (replaces_existing) {
        remove_notification(id, false);
    } else {
        do {
            id = next_notification_id++;
        } while (
            id == 0 || active_notifications.count(id) != 0 || fallback_notifications.count(id) != 0
        );
    }

    std::string body = builder->message;
    if (builder->progress >= 0) {
        if (!body.empty()) body += "\n";
        body += std::to_string(builder->progress) + "%";
    }
    NSString *title = ns_string(builder->title);
    NSString *informative_text = ns_string(body);
    NSUserNotificationCenter *center = notification_center();
    if (center) {
        NSUserNotification *notification = [[NSUserNotification alloc] init];
        notification.title = title;
        notification.informativeText = informative_text;
        notification.userInfo = @{ @"compose.native.notification.id" : @(id) };
        notification.hasActionButton = !builder->actions.empty();
        if (!builder->actions.empty()) {
            notification.actionButtonTitle = ns_string(builder->actions.front().label);
            if (builder->actions.size() > 1) {
                NSMutableArray<NSUserNotificationAction *> *additional = [NSMutableArray array];
                for (size_t index = 1; index < builder->actions.size(); ++index) {
                    const auto &action = builder->actions[index];
                    [additional addObject:[NSUserNotificationAction actionWithIdentifier:ns_string(action.id)
                                                                          title:ns_string(action.label)]];
                }
                notification.additionalActions = additional;
            }
        }
        notification.soundName =
            builder->urgency >= 2 ? NSUserNotificationDefaultSoundName : nil;
        [center deliverNotification:notification];
        active_notifications[id] = notification;
    } else {
        if (!osascript_notifications_supported()) {
            set_error(error_message, "No macOS notification delivery mechanism is available");
            return 0;
        }
        if (!deliver_osascript_notification(title, informative_text, error_message)) return 0;
        fallback_notifications.insert(id);
    }
    notification_actions[id] = builder->actions;
    return id;
}

void kld_notification_destroy(void *raw) { delete static_cast<NotificationBuilder *>(raw); }

int kld_notification_close(uint32_t id, char **error_message) {
    if (error_message) *error_message = nullptr;
    if (active_notifications.count(id) == 0 && fallback_notifications.count(id) == 0) return 1;
    remove_notification(id, true);
    return 1;
}

int kld_progress_supported(void) { return 0; }
char *kld_progress_start(const char *, const char *, int, char **) { return nullptr; }
int kld_progress_update(
    const char *,
    uint64_t,
    uint64_t,
    uint64_t,
    uint64_t,
    uint32_t,
    const char *,
    char **
) {
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
    auto ids = std::vector<uint32_t>();
    ids.reserve(active_notifications.size());
    for (const auto &entry : active_notifications) ids.push_back(entry.first);
    for (uint32_t id : ids) remove_notification(id, false);
    fallback_notifications.clear();
    desktop_events.clear();
    notification_actions.clear();
    NSUserNotificationCenter *center = notification_center();
    if (center.delegate == notification_delegate) center.delegate = nil;
    notification_delegate = nil;
}

void *kdrag_create(void *raw_window, char **error_message) {
    if (error_message) *error_message = nullptr;
    auto *window = static_cast<SDL_Window *>(raw_window);
    if (!cocoa_window(window)) {
        set_error(error_message, "SDL did not expose a Cocoa NSWindow");
        return nullptr;
    }
    auto *drag = new MacDrag();
    drag->sdl_window = window;
    drag->source = [[ComposeNativeDraggingSource alloc] init];
    drag->source.owner = drag;
    return drag;
}

void kdrag_destroy(void *raw) {
    auto *drag = static_cast<MacDrag *>(raw);
    if (!drag) return;
    drag->source.owner = nullptr;
    drag->source = nil;
    delete drag;
}

int kdrag_start(
    void *raw,
    const char *text,
    const char *uri_list,
    const unsigned char *,
    int,
    int,
    int,
    char **error_message
) {
    if (error_message) *error_message = nullptr;
    auto *drag = static_cast<MacDrag *>(raw);
    if (!drag || drag->active) return 0;
    NSWindow *window = cocoa_window(drag->sdl_window);
    NSView *view = window.contentView;
    NSEvent *event = NSApp.currentEvent;
    if (!view || !event) {
        set_error(error_message, "A Cocoa mouse event is required to start dragging");
        return 0;
    }

    NSMutableArray<NSDraggingItem *> *items = [NSMutableArray array];
    const NSPoint point = [view convertPoint:event.locationInWindow fromView:nil];
    NSImage *fallback_image = application_icon();
    if (text && *text) {
        NSPasteboardItem *item = [[NSPasteboardItem alloc] init];
        [item setString:ns_string(text) forType:NSPasteboardTypeString];
        [items addObject:dragging_item(item, fallback_image, point)];
    }
    for (NSString *uri in parse_uri_list(uri_list)) {
        NSURL *url = [NSURL URLWithString:uri];
        if (!url) continue;
        NSPasteboardItem *item = [[NSPasteboardItem alloc] init];
        [item setString:url.absoluteString forType:NSPasteboardTypeFileURL];
        [items addObject:dragging_item(item, fallback_image, point)];
    }
    if (items.count == 0) return 0;
    drag->active = true;
    NSDraggingSession *session = [view beginDraggingSessionWithItems:items event:event source:drag->source];
    session.animatesToStartingPositionsOnCancelOrFail = YES;
    return 1;
}

void kdrag_pointer_motion(void *) {}
void kdrag_pointer_release(void *) {}
void kdrag_handle_syswm(void *, const void *) {}
int kdrag_active(void *raw) {
    auto *drag = static_cast<MacDrag *>(raw);
    return drag && drag->active ? 1 : 0;
}

void *kplatform_create_window(const char *title, int width, int height, uint64_t flags, int) {
    return SDL_CreateWindow(title, width, height, static_cast<SDL_WindowFlags>(flags));
}

int kplatform_window_supports_frame_insets(void) { return 0; }
int kplatform_window_set_frame_insets(void *, int, int, int, int) { return 1; }

int kplatform_window_allow_drawing_inside_title_bar(void *raw_window, int allow) {
    NSWindow *window = cocoa_window(static_cast<SDL_Window *>(raw_window));
    if (!window) return 0;
    if (allow) {
        window.styleMask |= NSWindowStyleMaskFullSizeContentView;
        window.titleVisibility = NSWindowTitleHidden;
        window.titlebarAppearsTransparent = YES;
        window.movableByWindowBackground = NO;
    } else {
        window.styleMask &= ~NSWindowStyleMaskFullSizeContentView;
        window.titleVisibility = NSWindowTitleVisible;
        window.titlebarAppearsTransparent = NO;
    }
    for (NSWindowButton buttonType : {
             NSWindowCloseButton,
             NSWindowMiniaturizeButton,
             NSWindowZoomButton,
         }) {
        NSButton *button = [window standardWindowButton:buttonType];
        if (button) button.hidden = allow != 0;
    }
    return 1;
}

int kplatform_window_get_title_bar_metrics(
    void *raw_window,
    int *caption_button_width,
    int *title_bar_height
) {
    NSWindow *window = cocoa_window(static_cast<SDL_Window *>(raw_window));
    if (!window) return 0;
    NSButton *close = [window standardWindowButton:NSWindowCloseButton];
    const CGFloat button_width = close ? NSWidth(close.frame) : 14.0;
    CGFloat bar_height = NSHeight(window.frame) - NSHeight(window.contentLayoutRect);
    if (bar_height <= 0.0) bar_height = close ? NSMaxY(close.frame) + close.frame.origin.y : 28.0;
    if (caption_button_width) *caption_button_width = static_cast<int>(std::lround(button_width + 6.0));
    if (title_bar_height) *title_bar_height = static_cast<int>(std::lround(std::max(bar_height, 28.0)));
    return 1;
}

int kplatform_window_set_caption_button_bounds(void *, int, int, int, int, int, int) { return 1; }

int kplatform_window_set_shadow(void *raw_window, int enabled) {
    NSWindow *window = cocoa_window(static_cast<SDL_Window *>(raw_window));
    if (!window) return 0;
    window.hasShadow = enabled != 0;
    return 1;
}

int kplatform_window_refresh_shadow(void *raw_window) {
    NSWindow *window = cocoa_window(static_cast<SDL_Window *>(raw_window));
    if (!window) return 0;
    [window invalidateShadow];
    return 1;
}

int kplatform_window_set_title_bar_color(
    void *raw_window,
    int background_r,
    int background_g,
    int background_b,
    int,
    int,
    int
) {
    NSWindow *window = cocoa_window(static_cast<SDL_Window *>(raw_window));
    if (!window) return 0;
    if (background_r >= 0 && background_g >= 0 && background_b >= 0) {
        window.backgroundColor = [NSColor colorWithSRGBRed:background_r / 255.0
                                                    green:background_g / 255.0
                                                     blue:background_b / 255.0
                                                    alpha:1.0];
    }
    return 1;
}

int kplatform_window_set_transparent(void *raw_window, int transparent) {
    auto *sdl_window = static_cast<SDL_Window *>(raw_window);
    NSWindow *window = cocoa_window(sdl_window);
    if (!window) return 0;
    if (transparent) {
        window.opaque = NO;
        window.backgroundColor = NSColor.clearColor;
    } else {
        window.opaque = YES;
    }
    return 1;
}

int kplatform_window_set_fullscreen(void *raw_window, int fullscreen) {
    NSWindow *window = cocoa_window(static_cast<SDL_Window *>(raw_window));
    if (!window) return 0;
    const bool is_fullscreen =
        (window.styleMask & NSWindowStyleMaskFullScreen) == NSWindowStyleMaskFullScreen;
    if (is_fullscreen != (fullscreen != 0)) [window toggleFullScreen:nil];
    return 1;
}

int kplatform_window_set_maximized(void *raw_window, int maximized) {
    NSWindow *window = cocoa_window(static_cast<SDL_Window *>(raw_window));
    if (!window) return 0;
    const bool is_zoomed = [window isZoomed];
    if (is_zoomed != (maximized != 0)) [window zoom:nil];
    return 1;
}

}  // extern "C"
