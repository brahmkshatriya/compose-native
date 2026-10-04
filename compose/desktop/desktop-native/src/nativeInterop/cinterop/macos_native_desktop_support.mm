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

@interface ComposeNativeInteropPassthroughView : NSView
@property(nonatomic, assign) BOOL interactionEnabled;
@end

@interface ComposeNativeInteropRootView : NSView
@property(nonatomic, strong) NSView *originalContentView;
@property(nonatomic, strong) ComposeNativeInteropPassthroughView *backgroundContainer;
@property(nonatomic, strong) ComposeNativeInteropPassthroughView *overlayContainer;
@property(nonatomic, assign) KldMacosInteropBackgroundHitTestCallback backgroundHitTest;
@property(nonatomic, assign) void *backgroundHitTestUserData;
@end

@interface ComposeNativeInteropWrapperView : NSView
@property(nonatomic, assign) BOOL interactionEnabled;
@end

namespace {

struct ThemeObserver {
    uint32_t color_scheme_event_type = 0;
    uint32_t accent_color_event_type = 0;
};

struct MacInteropHost {
    __strong NSWindow *window = nil;
    __strong NSView *original_content_view = nil;
    __strong NSResponder *original_next_responder = nil;
    __strong ComposeNativeInteropRootView *root_view = nil;
    __strong ComposeNativeInteropPassthroughView *background_container = nil;
    __strong ComposeNativeInteropPassthroughView *overlay_container = nil;
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

void fill_superview(NSView *view) {
    if (!view || !view.superview) return;
    view.frame = view.superview.bounds;
    view.autoresizingMask = NSViewWidthSizable | NSViewHeightSizable;
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

@implementation ComposeNativeInteropPassthroughView

- (BOOL)isFlipped {
    return YES;
}

- (NSView *)hitTest:(NSPoint)point {
    if (!self.interactionEnabled) return nil;
    NSView *hit = [super hitTest:point];
    return hit == self ? nil : hit;
}

@end

@implementation ComposeNativeInteropRootView

- (NSResponder *)sdlEventResponder {
    NSResponder *responder = self.originalContentView.nextResponder;
    return responder == self ? self.window.nextResponder : responder;
}

- (void)clearNativeFirstResponder {
    // Clicking Compose should relinquish focus from an AppKit control. Compose/SDL will establish
    // its own text-input focus from the forwarded mouse event when needed.
    [self.window makeFirstResponder:nil];
}

- (NSView *)hitTest:(NSPoint)point {
    // Native overlays are visually above Compose and therefore receive input first.
    if (self.overlayContainer) {
        NSPoint overlayPoint = [self.overlayContainer convertPoint:point fromView:self];
        NSView *hit = [self.overlayContainer hitTest:overlayPoint];
        if (hit) return hit;
    }

    // Background native views are visually exposed through Compose clear regions, but Compose can
    // still draw interactive content over them. Ask the Compose scene which interop target wins
    // before allowing AppKit to dispatch into the background hierarchy.
    if (self.backgroundContainer && self.backgroundHitTest) {
        NSPoint backgroundPoint = [self.backgroundContainer convertPoint:point fromView:self];
        void *nativeTarget = self.backgroundHitTest(
            self.backgroundHitTestUserData,
            backgroundPoint.x,
            backgroundPoint.y
        );
        if (nativeTarget) {
            NSView *target = (__bridge NSView *)nativeTarget;
            // backgroundContainer and the interop wrappers are both flipped and wrappers are
            // direct children of the container. Avoid crossing the unflipped root in AppKit's
            // coordinate conversion; calculate the wrapper-local point in their shared space.
            NSPoint targetPoint = NSMakePoint(
                backgroundPoint.x - target.frame.origin.x,
                backgroundPoint.y - target.frame.origin.y
            );
            NSView *content = target.subviews.firstObject;
            if (content) {
                NSPoint contentPoint = [content convertPoint:targetPoint fromView:target];
                NSView *hit = [content hitTest:contentPoint];
                return hit ?: content;
            }
            return target;
        }
    }

    // Use the root as the event target for Compose. SDL3View normally forwards mouse events to
    // SDL3Cocoa_WindowListener through its responder chain, but embedding it below native AppKit
    // controls makes that delivery unreliable once a native control becomes first responder.
    // Targeting the root lets us forward the original NSEvent directly to SDL's listener.
    return self;
}

- (BOOL)mouseDownCanMoveWindow {
    return YES;
}

- (BOOL)acceptsFirstMouse:(NSEvent *)event {
    return [self.originalContentView acceptsFirstMouse:event];
}

- (void)mouseDown:(NSEvent *)event {
    [self clearNativeFirstResponder];
    [[self sdlEventResponder] mouseDown:event];
}

- (void)rightMouseDown:(NSEvent *)event {
    [self clearNativeFirstResponder];
    [[self sdlEventResponder] rightMouseDown:event];
}

- (void)otherMouseDown:(NSEvent *)event {
    [self clearNativeFirstResponder];
    [[self sdlEventResponder] otherMouseDown:event];
}

- (void)mouseUp:(NSEvent *)event {
    [[self sdlEventResponder] mouseUp:event];
}

- (void)rightMouseUp:(NSEvent *)event {
    [[self sdlEventResponder] rightMouseUp:event];
}

- (void)otherMouseUp:(NSEvent *)event {
    [[self sdlEventResponder] otherMouseUp:event];
}

- (void)mouseMoved:(NSEvent *)event {
    [[self sdlEventResponder] mouseMoved:event];
}

- (void)mouseDragged:(NSEvent *)event {
    [[self sdlEventResponder] mouseDragged:event];
}

- (void)rightMouseDragged:(NSEvent *)event {
    [[self sdlEventResponder] rightMouseDragged:event];
}

- (void)otherMouseDragged:(NSEvent *)event {
    [[self sdlEventResponder] otherMouseDragged:event];
}

- (void)scrollWheel:(NSEvent *)event {
    [[self sdlEventResponder] scrollWheel:event];
}

- (void)touchesBeganWithEvent:(NSEvent *)event {
    [[self sdlEventResponder] touchesBeganWithEvent:event];
}

- (void)touchesMovedWithEvent:(NSEvent *)event {
    [[self sdlEventResponder] touchesMovedWithEvent:event];
}

- (void)touchesEndedWithEvent:(NSEvent *)event {
    [[self sdlEventResponder] touchesEndedWithEvent:event];
}

- (void)touchesCancelledWithEvent:(NSEvent *)event {
    [[self sdlEventResponder] touchesCancelledWithEvent:event];
}

- (void)magnifyWithEvent:(NSEvent *)event {
    [[self sdlEventResponder] magnifyWithEvent:event];
}

- (void)tabletPoint:(NSEvent *)event {
    [[self sdlEventResponder] tabletPoint:event];
}

- (void)tabletProximity:(NSEvent *)event {
    [[self sdlEventResponder] tabletProximity:event];
}

@end

@implementation ComposeNativeInteropWrapperView

- (instancetype)initWithFrame:(NSRect)frameRect {
    self = [super initWithFrame:frameRect];
    if (self) {
        _interactionEnabled = YES;
        self.wantsLayer = YES;
        self.layer.masksToBounds = YES;
    }
    return self;
}

- (BOOL)isFlipped {
    return YES;
}

- (NSView *)hitTest:(NSPoint)point {
    if (!self.interactionEnabled) return nil;
    return [super hitTest:point];
}

@end

extern "C" {

void *kld_macos_interop_host_create(void *raw_window) {
    auto *sdl_window = static_cast<SDL_Window *>(raw_window);
    NSWindow *window = cocoa_window(sdl_window);
    if (!window) return nullptr;

    NSView *original = window.contentView;
    if (!original) return nullptr;
    NSResponder *originalNextResponder = original.nextResponder;

    auto *root = [[ComposeNativeInteropRootView alloc] initWithFrame:original.frame];
    auto *background =
        [[ComposeNativeInteropPassthroughView alloc] initWithFrame:root.bounds];
    auto *overlay =
        [[ComposeNativeInteropPassthroughView alloc] initWithFrame:root.bounds];
    background.interactionEnabled = YES;
    overlay.interactionEnabled = YES;
    background.autoresizingMask = NSViewWidthSizable | NSViewHeightSizable;
    overlay.autoresizingMask = NSViewWidthSizable | NSViewHeightSizable;
    root.autoresizingMask = NSViewWidthSizable | NSViewHeightSizable;
    root.acceptsTouchEvents = YES;
    root.originalContentView = original;
    root.backgroundContainer = background;
    root.overlayContainer = overlay;

    // Replacing contentView removes the SDL view from the window but does not invalidate it.
    // Reattach it as the middle rendering/event layer between native background and overlay views.
    window.contentView = root;
    [root addSubview:background];
    [root addSubview:original];
    [root addSubview:overlay];
    // SDL installs SDL3Cocoa_WindowListener as SDL3View.nextResponder and handles mouse buttons
    // there. Reparenting SDL3View can replace that responder link with the new superview, so keep
    // SDL's responder chain intact while the native-view host is installed.
    if (originalNextResponder) original.nextResponder = originalNextResponder;
    fill_superview(original);
    fill_superview(background);
    fill_superview(overlay);

    auto *host = new MacInteropHost();
    host->window = window;
    host->original_content_view = original;
    host->original_next_responder = originalNextResponder;
    host->root_view = root;
    host->background_container = background;
    host->overlay_container = overlay;
    return host;
}

void *kld_macos_interop_host_background_view(void *raw_host) {
    auto *host = static_cast<MacInteropHost *>(raw_host);
    return host ? (__bridge void *)host->background_container : nullptr;
}

void *kld_macos_interop_host_overlay_view(void *raw_host) {
    auto *host = static_cast<MacInteropHost *>(raw_host);
    return host ? (__bridge void *)host->overlay_container : nullptr;
}

void kld_macos_interop_host_set_background_hit_test(
    void *raw_host,
    KldMacosInteropBackgroundHitTestCallback callback,
    void *user_data
) {
    auto *host = static_cast<MacInteropHost *>(raw_host);
    if (!host || !host->root_view) return;
    host->root_view.backgroundHitTest = callback;
    host->root_view.backgroundHitTestUserData = user_data;
}

void kld_macos_interop_host_prepare_render_surface(void *raw_host) {
    auto *host = static_cast<MacInteropHost *>(raw_host);
    if (!host || !host->original_content_view) return;

    // SDL3View paints its backing layer black from updateLayer() unless the SDL window itself was
    // created with SDL_WINDOW_TRANSPARENT. Native background interop only needs transparent pixels
    // in the renderer surface, not a transparent top-level window, so clear the backing layer after
    // SDL has attached its OpenGL/Metal renderer instead of changing the SDL window flags.
    NSView *view = host->original_content_view;
    view.wantsLayer = YES;
    view.layer.opaque = NO;
    view.layer.backgroundColor = CGColorGetConstantColor(kCGColorClear);
}

void kld_macos_interop_host_sync_z_order(void *raw_host) {
    auto *host = static_cast<MacInteropHost *>(raw_host);
    if (!host || !host->root_view) return;

    kld_macos_interop_host_prepare_render_surface(raw_host);

    ComposeNativeInteropRootView *root = host->root_view;
    NSView *background = host->background_container;
    NSView *original = host->original_content_view;
    NSView *overlay = host->overlay_container;
    if (background.superview == root && original.superview == root) {
        [root addSubview:background positioned:NSWindowBelow relativeTo:original];
    }
    if (overlay.superview == root) {
        // SDL_Metal_CreateView adds its render view after the host is installed. Moving the
        // overlay container to the top keeps native overlays above both Metal and OpenGL output.
        [root addSubview:overlay positioned:NSWindowAbove relativeTo:nil];
    }
}

void kld_macos_interop_host_destroy(void *raw_host) {
    auto *host = static_cast<MacInteropHost *>(raw_host);
    if (!host) return;

    NSWindow *window = host->window;
    NSView *original = host->original_content_view;
    ComposeNativeInteropRootView *root = host->root_view;
    if (window && original && window.contentView == root) {
        [host->background_container removeFromSuperview];
        [host->overlay_container removeFromSuperview];
        [original removeFromSuperview];
        window.contentView = original;
        if (host->original_next_responder) {
            original.nextResponder = host->original_next_responder;
        }
    }
    delete host;
}

void *kld_macos_interop_wrapper_create(void) {
    auto *wrapper = [[ComposeNativeInteropWrapperView alloc] initWithFrame:NSZeroRect];
    return (void *)CFBridgingRetain(wrapper);
}

void kld_macos_interop_wrapper_set_interactive(void *raw_wrapper, int interactive) {
    if (!raw_wrapper) return;
    auto *wrapper = (__bridge ComposeNativeInteropWrapperView *)raw_wrapper;
    wrapper.interactionEnabled = interactive != 0;
}

void kld_macos_interop_wrapper_fitting_size(void *raw_wrapper, double *width, double *height) {
    if (width) *width = 0.0;
    if (height) *height = 0.0;
    if (!raw_wrapper) return;

    auto *wrapper = (__bridge ComposeNativeInteropWrapperView *)raw_wrapper;
    NSView *view = wrapper.subviews.firstObject;
    NSSize size = view ? view.fittingSize : wrapper.fittingSize;
    if (width) *width = std::isfinite(size.width) && size.width > 0.0 ? size.width : 0.0;
    if (height) *height = std::isfinite(size.height) && size.height > 0.0 ? size.height : 0.0;
}

void kld_macos_interop_wrapper_destroy(void *raw_wrapper) {
    if (!raw_wrapper) return;
    CFBridgingRelease(raw_wrapper);
}

void kld_macos_interop_container_place_view(
    void *raw_container,
    void *raw_view,
    void *raw_previous_view
) {
    if (!raw_container || !raw_view) return;
    auto *container = (__bridge NSView *)raw_container;
    auto *view = (__bridge NSView *)raw_view;
    auto *previous = raw_previous_view ? (__bridge NSView *)raw_previous_view : nil;
    if (previous) {
        [container addSubview:view positioned:NSWindowAbove relativeTo:previous];
    } else {
        [container addSubview:view positioned:NSWindowBelow relativeTo:nil];
    }
}

void kld_macos_current_gl_set_surface_opaque(int opaque) {
    NSOpenGLContext *context = NSOpenGLContext.currentContext;
    if (!context) return;
    GLint value = opaque ? 1 : 0;
    [context setValues:&value forParameter:NSOpenGLCPSurfaceOpacity];
    [context update];
}

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
