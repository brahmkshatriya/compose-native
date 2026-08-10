#define GL_GLEXT_PROTOTYPES

#include "app_webview.h"

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GL/gl.h>
#include <GL/glext.h>
#include <SDL3/SDL.h>
#include <glib-object.h>
#include <glib.h>
#include <wpe/headless/wpe-headless.h>
#include <wpe/WPEBufferDMABuf.h>
#include <wpe/WPEBufferSHM.h>
#include <wpe/WPEEvent.h>
#include <wpe/webkit.h>

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <string>

struct AppWebView {
    WPEDisplay *display = nullptr;
    WPEView *wpe_view = nullptr;
    WebKitWebView *web_view = nullptr;
    WPEBuffer *buffer = nullptr;
    GLuint texture = 0;
    GLuint framebuffer = 0;
    int texture_width = 0;
    int texture_height = 0;
    SDL_Cursor *custom_cursor = nullptr;
    bool texture_ready = false;
    bool reported_buffer = false;
    int width = 1;
    int height = 1;
    int logical_width = 1;
    int logical_height = 1;
    float scale = 1.0f;
    bool debug = false;
    int debug_width = 0;
    int debug_height = 0;
    std::string pending_uri;
    char error[512] = {};
};

static SDL_Cursor *app_webview_system_cursor(SDL_SystemCursor type) {
    static SDL_Cursor *cursors[SDL_SYSTEM_CURSOR_COUNT] = {};
    if (!cursors[type]) cursors[type] = SDL_CreateSystemCursor(type);
    return cursors[type];
}

static SDL_SystemCursor app_webview_cursor_type(const char *name) {
    if (!std::strcmp(name, "pointer")) return SDL_SYSTEM_CURSOR_POINTER;
    if (!std::strcmp(name, "text") || !std::strcmp(name, "vertical-text")) {
        return SDL_SYSTEM_CURSOR_TEXT;
    }
    if (!std::strcmp(name, "crosshair") || !std::strcmp(name, "cell")) {
        return SDL_SYSTEM_CURSOR_CROSSHAIR;
    }
    if (!std::strcmp(name, "wait")) return SDL_SYSTEM_CURSOR_WAIT;
    if (!std::strcmp(name, "progress")) return SDL_SYSTEM_CURSOR_PROGRESS;
    if (!std::strcmp(name, "not-allowed") || !std::strcmp(name, "no-drop")) {
        return SDL_SYSTEM_CURSOR_NOT_ALLOWED;
    }
    if (
        !std::strcmp(name, "e-resize") || !std::strcmp(name, "w-resize") ||
        !std::strcmp(name, "ew-resize") || !std::strcmp(name, "col-resize")
    ) return SDL_SYSTEM_CURSOR_EW_RESIZE;
    if (
        !std::strcmp(name, "n-resize") || !std::strcmp(name, "s-resize") ||
        !std::strcmp(name, "ns-resize") || !std::strcmp(name, "row-resize")
    ) return SDL_SYSTEM_CURSOR_NS_RESIZE;
    if (
        !std::strcmp(name, "ne-resize") || !std::strcmp(name, "sw-resize") ||
        !std::strcmp(name, "nesw-resize")
    ) return SDL_SYSTEM_CURSOR_NESW_RESIZE;
    if (
        !std::strcmp(name, "nw-resize") || !std::strcmp(name, "se-resize") ||
        !std::strcmp(name, "nwse-resize")
    ) return SDL_SYSTEM_CURSOR_NWSE_RESIZE;
    if (
        !std::strcmp(name, "move") || !std::strcmp(name, "all-scroll") ||
        !std::strcmp(name, "grab") || !std::strcmp(name, "grabbing")
    ) return SDL_SYSTEM_CURSOR_MOVE;
    return SDL_SYSTEM_CURSOR_DEFAULT;
}

static void app_webview_set_cursor_from_name(WPEView *wpe_view, const char *name) {
    auto *view = static_cast<AppWebView *>(
        g_object_get_data(G_OBJECT(wpe_view), "ktnative-app-webview")
    );
    if (!view || !name) return;
    if (view->custom_cursor) {
        SDL_DestroyCursor(view->custom_cursor);
        view->custom_cursor = nullptr;
    }
    if (!std::strcmp(name, "none")) {
        SDL_HideCursor();
        return;
    }
    SDL_ShowCursor();
    if (SDL_Cursor *cursor = app_webview_system_cursor(app_webview_cursor_type(name))) {
        SDL_SetCursor(cursor);
    }
}

static void app_webview_set_cursor_from_bytes(
    WPEView *wpe_view,
    GBytes *bytes,
    unsigned int width,
    unsigned int height,
    unsigned int stride,
    unsigned int hotspot_x,
    unsigned int hotspot_y
) {
    auto *view = static_cast<AppWebView *>(
        g_object_get_data(G_OBJECT(wpe_view), "ktnative-app-webview")
    );
    if (!view || !bytes || !width || !height) return;
    std::size_t byte_count = 0;
    const void *pixels = g_bytes_get_data(bytes, &byte_count);
    if (!pixels || byte_count < static_cast<std::size_t>(height) * stride) return;
    SDL_Surface *surface = SDL_CreateSurfaceFrom(
        static_cast<int>(width),
        static_cast<int>(height),
        SDL_PIXELFORMAT_ARGB8888,
        const_cast<void *>(pixels),
        static_cast<int>(stride)
    );
    if (!surface) return;
    SDL_Cursor *cursor = SDL_CreateColorCursor(
        surface,
        static_cast<int>(hotspot_x),
        static_cast<int>(hotspot_y)
    );
    SDL_DestroySurface(surface);
    if (!cursor) return;
    if (view->custom_cursor) SDL_DestroyCursor(view->custom_cursor);
    view->custom_cursor = cursor;
    SDL_ShowCursor();
    SDL_SetCursor(cursor);
}

static void app_webview_set_error(AppWebView *view, const char *message) {
    if (!view || view->error[0]) return;
    std::snprintf(view->error, sizeof(view->error), "%s", message);
    std::fprintf(stderr, "Native WebView: %s\n", message);
}

static void app_webview_release_buffer(AppWebView *view) {
    if (!view) return;
    if (view->buffer) {
        g_object_unref(view->buffer);
        view->buffer = nullptr;
    }
}

static void app_webview_buffer_rendered(WPEView *, WPEBuffer *buffer, void *data) {
    AppWebView *view = static_cast<AppWebView *>(data);
    if (!view || !buffer) return;
    const int image_width = wpe_buffer_get_width(buffer);
    const int image_height = wpe_buffer_get_height(buffer);
    if (!view->reported_buffer) {
        std::fprintf(
            stderr,
            "Native WebView: received %s buffer %dx%d\n",
            WPE_IS_BUFFER_DMA_BUF(buffer) ? "DMA-BUF" :
                (WPE_IS_BUFFER_SHM(buffer) ? "shared-memory" : "unknown"),
            image_width,
            image_height
        );
        view->reported_buffer = true;
    }
    if (
        view->debug &&
        (view->debug_width != view->width || view->debug_height != view->height)
    ) {
        std::fprintf(
            stderr,
            "Native WebView: exported image %dx%d for %dx%d\n",
            image_width,
            image_height,
            view->width,
            view->height
        );
        view->debug_width = view->width;
        view->debug_height = view->height;
    }
    app_webview_release_buffer(view);
    view->buffer = WPE_BUFFER(g_object_ref(buffer));
    view->texture_ready = false;
}

static void app_webview_buffer_released(WPEView *, WPEBuffer *buffer, void *data) {
    AppWebView *view = static_cast<AppWebView *>(data);
    if (view && view->buffer == buffer) app_webview_release_buffer(view);
}

static bool app_webview_import_buffer(AppWebView *view) {
    if (!view) return false;
    if (view->texture_ready) return true;
    if (!view->buffer) return false;
    GError *error = nullptr;
    GBytes *bytes = wpe_buffer_import_to_pixels(view->buffer, &error);
    if (!bytes) {
        app_webview_set_error(
            view,
            error ? error->message : "Could not read pixels from the WPE buffer"
        );
        g_clear_error(&error);
        return false;
    }

    std::size_t byte_count = 0;
    const void *pixels = g_bytes_get_data(bytes, &byte_count);
    const int width = wpe_buffer_get_width(view->buffer);
    const int height = wpe_buffer_get_height(view->buffer);
    const std::size_t stride = height > 0 ? byte_count / static_cast<std::size_t>(height) : 0;
    if (!pixels || width <= 0 || height <= 0 || stride < static_cast<std::size_t>(width) * 4) {
        app_webview_set_error(view, "WPE returned an invalid pixel buffer");
        return false;
    }

    glBindTexture(GL_TEXTURE_2D, view->texture);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    glPixelStorei(GL_UNPACK_ROW_LENGTH, static_cast<GLint>(stride / 4));
    glTexImage2D(
        GL_TEXTURE_2D,
        0,
        GL_RGB8,
        width,
        height,
        0,
        GL_BGRA,
        GL_UNSIGNED_BYTE,
        pixels
    );
    glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
    glBindTexture(GL_TEXTURE_2D, 0);
    glBindFramebuffer(GL_READ_FRAMEBUFFER, view->framebuffer);
    glFramebufferTexture2D(
        GL_READ_FRAMEBUFFER,
        GL_COLOR_ATTACHMENT0,
        GL_TEXTURE_2D,
        view->texture,
        0
    );
    if (glCheckFramebufferStatus(GL_READ_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
        app_webview_set_error(view, "Could not create the WPE source framebuffer");
        return false;
    }
    const GLenum gl_error = glGetError();
    if (gl_error != GL_NO_ERROR) {
        char message[128];
        std::snprintf(message, sizeof(message), "Could not upload WPE pixels (OpenGL error %#x)", gl_error);
        app_webview_set_error(view, message);
        return false;
    }
    view->texture_width = width;
    view->texture_height = height;
    view->texture_ready = true;
    return true;
}

static void app_webview_load_changed(
    WebKitWebView *web_view,
    WebKitLoadEvent event,
    void *data
) {
    AppWebView *view = static_cast<AppWebView *>(data);
    if (!view) return;
    const char *state = "unknown";
    switch (event) {
        case WEBKIT_LOAD_STARTED: state = "started"; break;
        case WEBKIT_LOAD_REDIRECTED: state = "redirected"; break;
        case WEBKIT_LOAD_COMMITTED: state = "committed"; break;
        case WEBKIT_LOAD_FINISHED: state = "finished"; break;
    }
    std::fprintf(
        stderr,
        "Native WebView: load %s: %s\n",
        state,
        webkit_web_view_get_uri(web_view)
    );
    if (event == WEBKIT_LOAD_FINISHED && view->debug) {
        static const char probe[] =
            "console.log('KTNATIVE_WEBVIEW_DIAG ' + JSON.stringify({"
            "readyState:document.readyState,title:document.title,"
            "bodyText:document.body?document.body.innerText.slice(0,240):'',"
            "items:document.querySelectorAll('ytd-rich-item-renderer').length,"
            "videos:document.querySelectorAll('video').length,"
            "scrollHeight:document.documentElement.scrollHeight,"
            "initialData:!!window.ytInitialData"
            "}));";
        webkit_web_view_evaluate_javascript(
            web_view,
            probe,
            -1,
            nullptr,
            nullptr,
            nullptr,
            nullptr,
            nullptr
        );
    }
}

static gboolean app_webview_load_failed(
    WebKitWebView *,
    WebKitLoadEvent,
    const char *uri,
    GError *error,
    void *data
) {
    AppWebView *view = static_cast<AppWebView *>(data);
    if (view && view->debug) {
        std::fprintf(
            stderr,
            "Native WebView: load failed: %s: %s\n",
            uri ? uri : "(unknown URI)",
            error ? error->message : "unknown error"
        );
    }
    return FALSE;
}

static bool app_webview_is_media_permission(const char *name) {
    return name && (!std::strcmp(name, "microphone") || !std::strcmp(name, "camera"));
}

static gboolean app_webview_permission_request(
    WebKitWebView *,
    WebKitPermissionRequest *request,
    void *data
) {
    if (!WEBKIT_IS_USER_MEDIA_PERMISSION_REQUEST(request)) return FALSE;

    auto *media_request = WEBKIT_USER_MEDIA_PERMISSION_REQUEST(request);
    const bool requests_audio =
        webkit_user_media_permission_is_for_audio_device(media_request);
    const bool requests_video =
        webkit_user_media_permission_is_for_video_device(media_request);
    const bool requests_display =
        webkit_user_media_permission_is_for_display_device(media_request);
    if ((!requests_audio && !requests_video) || requests_display) return FALSE;

    AppWebView *view = static_cast<AppWebView *>(data);
    if (view && view->debug) {
        std::fprintf(
            stderr,
            "Native WebView: allowing media permission (microphone=%s, camera=%s)\n",
            requests_audio ? "yes" : "no",
            requests_video ? "yes" : "no"
        );
    }
    webkit_permission_request_allow(request);
    return TRUE;
}

static gboolean app_webview_query_permission_state(
    WebKitWebView *,
    WebKitPermissionStateQuery *query,
    void *data
) {
    const char *name = webkit_permission_state_query_get_name(query);
    if (!app_webview_is_media_permission(name)) return FALSE;

    AppWebView *view = static_cast<AppWebView *>(data);
    if (view && view->debug) {
        std::fprintf(stderr, "Native WebView: reporting %s permission as granted\n", name);
    }
    webkit_permission_state_query_finish(query, WEBKIT_PERMISSION_STATE_GRANTED);
    return TRUE;
}

static bool app_webview_is_printable_key(long long key) {
    switch (key) {
        case 0: case 11: case 8: case 2: case 14: case 3: case 5: case 4:
        case 34: case 38: case 40: case 37: case 46: case 45: case 31: case 35:
        case 12: case 15: case 1: case 17: case 32: case 9: case 13: case 7:
        case 16: case 6:
        case 29: case 18: case 19: case 20: case 21: case 23: case 22: case 26:
        case 28: case 25:
        case 49: // Space
            return true;
        default:
            return false;
    }
}

static unsigned int app_webview_keysym(long long key, unsigned int code_point) {
    if (code_point) {
        return code_point <= 0xff ? code_point : 0x01000000u | code_point;
    }
    switch (key) {
        case 0: return 'a'; case 11: return 'b'; case 8: return 'c'; case 2: return 'd';
        case 14: return 'e'; case 3: return 'f'; case 5: return 'g'; case 4: return 'h';
        case 34: return 'i'; case 38: return 'j'; case 40: return 'k'; case 37: return 'l';
        case 46: return 'm'; case 45: return 'n'; case 31: return 'o'; case 35: return 'p';
        case 12: return 'q'; case 15: return 'r'; case 1: return 's'; case 17: return 't';
        case 32: return 'u'; case 9: return 'v'; case 13: return 'w'; case 7: return 'x';
        case 16: return 'y'; case 6: return 'z';
        case 29: return '0'; case 18: return '1'; case 19: return '2'; case 20: return '3';
        case 21: return '4'; case 23: return '5'; case 22: return '6'; case 26: return '7';
        case 28: return '8'; case 25: return '9';
        case 36: return 0xff0d; // Return
        case 48: return 0xff09; // Tab
        case 49: return 0x20;   // Space
        case 51: return 0xff08; // BackSpace
        case 53: return 0xff1b; // Escape
        case 117: return 0xffff; // Delete
        case 123: return 0xff51; // Left
        case 124: return 0xff53; // Right
        case 125: return 0xff54; // Down
        case 126: return 0xff52; // Up
        case 115: return 0xff50; // Home
        case 119: return 0xff57; // End
        case 116: return 0xff55; // Page Up
        case 121: return 0xff56; // Page Down
        case 59: case 62: return 0xffe3; // Control
        case 56: case 60: return 0xffe1; // Shift
        case 58: case 61: return 0xffe9; // Alt
        case 54: case 55: return 0xffeb; // Meta
        default: return 0;
    }
}

static const char *app_webview_current_drm_device(EGLDisplay display) {
    auto query_display = reinterpret_cast<PFNEGLQUERYDISPLAYATTRIBEXTPROC>(
        eglGetProcAddress("eglQueryDisplayAttribEXT")
    );
    auto query_device = reinterpret_cast<PFNEGLQUERYDEVICESTRINGEXTPROC>(
        eglGetProcAddress("eglQueryDeviceStringEXT")
    );
    if (!query_display || !query_device) return nullptr;

    EGLAttrib device_attribute = 0;
    if (!query_display(display, EGL_DEVICE_EXT, &device_attribute) || !device_attribute) {
        return nullptr;
    }
    auto device = reinterpret_cast<EGLDeviceEXT>(device_attribute);
    const char *render_node = query_device(device, EGL_DRM_RENDER_NODE_FILE_EXT);
    if (render_node && render_node[0]) return render_node;
    const char *primary_node = query_device(device, EGL_DRM_DEVICE_FILE_EXT);
    return primary_node && primary_node[0] ? primary_node : nullptr;
}

extern "C" {

AppWebView *app_webview_create(const char *uri) {
    AppWebView *view = new AppWebView();
    view->debug = g_getenv("KTNATIVE_WEBVIEW_DEBUG") != nullptr;
    const EGLDisplay egl_display = eglGetCurrentDisplay();
    if (egl_display == EGL_NO_DISPLAY) {
        app_webview_set_error(view, "WPE WebKit requires an EGL-backed SDL OpenGL context");
        return view;
    }
    GError *display_error = nullptr;
    const char *drm_device = app_webview_current_drm_device(egl_display);
    view->display = drm_device
        ? wpe_display_headless_new_for_device(drm_device, &display_error)
        : wpe_display_headless_new();
    if (drm_device) {
        std::fprintf(stderr, "Native WebView: using EGL DRM device %s\n", drm_device);
    }
    if (!view->display || !wpe_display_connect(view->display, &display_error)) {
        app_webview_set_error(
            view,
            display_error ? display_error->message : "Could not create the headless WPE display"
        );
        g_clear_error(&display_error);
        return view;
    }
    view->web_view = WEBKIT_WEB_VIEW(g_object_new(
        WEBKIT_TYPE_WEB_VIEW,
        "display", view->display,
        nullptr
    ));
    if (!view->web_view) {
        app_webview_set_error(view, "Could not create the WPE WebKit web view");
        return view;
    }
    view->wpe_view = webkit_web_view_get_wpe_view(view->web_view);
    if (!view->wpe_view) {
        app_webview_set_error(view, "WPE WebKit did not create a platform view");
        return view;
    }
    g_object_set_data(G_OBJECT(view->wpe_view), "ktnative-app-webview", view);
    WPEViewClass *view_class = WPE_VIEW_GET_CLASS(view->wpe_view);
    view_class->set_cursor_from_name = app_webview_set_cursor_from_name;
    view_class->set_cursor_from_bytes = app_webview_set_cursor_from_bytes;
    g_signal_connect(
        view->wpe_view,
        "buffer-rendered",
        G_CALLBACK(app_webview_buffer_rendered),
        view
    );
    g_signal_connect(
        view->wpe_view,
        "buffer-released",
        G_CALLBACK(app_webview_buffer_released),
        view
    );

    WebKitSettings *settings = webkit_web_view_get_settings(view->web_view);
    webkit_settings_set_enable_javascript(settings, TRUE);
    webkit_settings_set_enable_media(settings, TRUE);
    webkit_settings_set_enable_mediasource(settings, TRUE);
    webkit_settings_set_enable_media_capabilities(settings, TRUE);
    webkit_settings_set_enable_webgl(settings, TRUE);
    webkit_settings_set_media_playback_allows_inline(settings, TRUE);
    webkit_settings_set_media_playback_requires_user_gesture(settings, FALSE);
    webkit_settings_set_enable_site_specific_quirks(settings, TRUE);
    const char *user_agent = g_getenv("KTNATIVE_WEBVIEW_USER_AGENT");
    if (!user_agent || !user_agent[0]) {
        user_agent =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
            "(KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36";
    }
    webkit_settings_set_user_agent(settings, user_agent);
    webkit_settings_set_enable_write_console_messages_to_stdout(settings, view->debug);
    g_signal_connect(view->web_view, "load-changed", G_CALLBACK(app_webview_load_changed), view);
    g_signal_connect(view->web_view, "load-failed", G_CALLBACK(app_webview_load_failed), view);
    g_signal_connect(
        view->web_view,
        "permission-request",
        G_CALLBACK(app_webview_permission_request),
        view
    );
    g_signal_connect(
        view->web_view,
        "query-permission-state",
        G_CALLBACK(app_webview_query_permission_state),
        view
    );
    if (view->debug) {
        std::fprintf(stderr, "Native WebView: user agent: %s\n", webkit_settings_get_user_agent(settings));
    }

    wpe_view_set_visible(view->wpe_view, TRUE);
    wpe_view_map(view->wpe_view);
    glGenTextures(1, &view->texture);
    glGenFramebuffers(1, &view->framebuffer);
    if (uri && uri[0]) view->pending_uri = uri;
    return view;
}

void app_webview_destroy(AppWebView *view) {
    if (!view) return;
    if (view->wpe_view) {
        g_signal_handlers_disconnect_by_data(view->wpe_view, view);
        g_object_set_data(G_OBJECT(view->wpe_view), "ktnative-app-webview", nullptr);
    }
    if (view->custom_cursor) {
        SDL_SetCursor(app_webview_system_cursor(SDL_SYSTEM_CURSOR_DEFAULT));
        SDL_DestroyCursor(view->custom_cursor);
    }
    app_webview_release_buffer(view);
    if (view->framebuffer) glDeleteFramebuffers(1, &view->framebuffer);
    if (view->texture) glDeleteTextures(1, &view->texture);
    if (view->web_view) g_object_unref(view->web_view);
    if (view->display) g_object_unref(view->display);
    delete view;
}

const char *app_webview_error(AppWebView *view) {
    return view && view->error[0] ? view->error : nullptr;
}

int app_webview_render(
    AppWebView *view,
    int framebuffer,
    int width,
    int height,
    float device_scale
) {
    if (!view || !view->web_view || width <= 0 || height <= 0) return 0;
    const float safe_scale = std::clamp(device_scale, 0.05f, 5.0f);
    const int logical_width = std::max(1, static_cast<int>(std::lround(width / safe_scale)));
    const int logical_height = std::max(1, static_cast<int>(std::lround(height / safe_scale)));
    if (
        view->width != width ||
        view->height != height ||
        view->logical_width != logical_width ||
        view->logical_height != logical_height ||
        std::fabs(view->scale - safe_scale) > 0.001f
    ) {
        view->width = width;
        view->height = height;
        view->logical_width = logical_width;
        view->logical_height = logical_height;
        view->scale = safe_scale;
        WPEToplevel *toplevel = wpe_view_get_toplevel(view->wpe_view);
        if (toplevel) {
            wpe_toplevel_scale_changed(toplevel, safe_scale);
            wpe_toplevel_resize(toplevel, logical_width, logical_height);
        } else {
            wpe_view_resized(view->wpe_view, logical_width, logical_height);
        }
    }
    if (!view->pending_uri.empty()) {
        webkit_web_view_load_uri(view->web_view, view->pending_uri.c_str());
        view->pending_uri.clear();
    }

    for (int iteration = 0; iteration < 64; ++iteration) {
        if (!g_main_context_iteration(nullptr, FALSE)) break;
    }
    if (!app_webview_import_buffer(view)) {
        glBindFramebuffer(GL_FRAMEBUFFER, static_cast<GLuint>(framebuffer));
        glViewport(0, 0, width, height);
        glDisable(GL_SCISSOR_TEST);
        glClearColor(1, 1, 1, 1);
        glClear(GL_COLOR_BUFFER_BIT);
        return 1;
    }

    // The SDL host uses an OpenGL 3.3 core-profile context, so legacy fixed-function drawing
    // (glBegin/glEnd) is invalid. Copy the uploaded WPE texture through framebuffer blitting,
    // which is core-profile safe. The source texture uses RGB8 so XRGB buffers acquire alpha 1.
    glBindFramebuffer(GL_READ_FRAMEBUFFER, view->framebuffer);
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, static_cast<GLuint>(framebuffer));
    glDisable(GL_SCISSOR_TEST);
    glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
    glBlitFramebuffer(
        0,
        0,
        view->texture_width,
        view->texture_height,
        0,
        height,
        width,
        0,
        GL_COLOR_BUFFER_BIT,
        GL_LINEAR
    );
    const GLenum draw_error = glGetError();
    if (draw_error != GL_NO_ERROR) {
        char message[128];
        std::snprintf(message, sizeof(message), "Could not draw WPE pixels (OpenGL error %#x)", draw_error);
        app_webview_set_error(view, message);
        return 0;
    }
    return 1;
}

int app_webview_render_pixels(AppWebView *, void *, int, int, int, float) {
    return 0;
}

void app_webview_load_uri(AppWebView *view, const char *uri) {
    if (!view || !view->web_view || !uri || !uri[0]) return;
    if (view->width <= 1 || view->height <= 1) {
        view->pending_uri = uri;
    } else {
        webkit_web_view_load_uri(view->web_view, uri);
    }
}

void app_webview_go_back(AppWebView *view) {
    if (view && view->web_view && webkit_web_view_can_go_back(view->web_view)) {
        webkit_web_view_go_back(view->web_view);
    }
}

void app_webview_go_forward(AppWebView *view) {
    if (view && view->web_view && webkit_web_view_can_go_forward(view->web_view)) {
        webkit_web_view_go_forward(view->web_view);
    }
}

void app_webview_reload(AppWebView *view) {
    if (view && view->web_view) webkit_web_view_reload(view->web_view);
}

int app_webview_can_go_back(AppWebView *view) {
    return view && view->web_view && webkit_web_view_can_go_back(view->web_view);
}

int app_webview_can_go_forward(AppWebView *view) {
    return view && view->web_view && webkit_web_view_can_go_forward(view->web_view);
}

static void app_webview_run_script(AppWebView *view, const char *script) {
    if (!view || !view->web_view) return;
    webkit_web_view_evaluate_javascript(
        view->web_view, script, -1, nullptr, nullptr, nullptr, nullptr, nullptr
    );
}

void app_webview_media_set_playing(AppWebView *view, int playing) {
    app_webview_run_script(
        view,
        playing ? "document.querySelector('video')?.play()" :
                  "document.querySelector('video')?.pause()"
    );
}

void app_webview_media_seek(AppWebView *view, double seconds) {
    char script[160];
    std::snprintf(
        script, sizeof(script),
        "(()=>{const v=document.querySelector('video');if(v)v.currentTime=%f})()", seconds
    );
    app_webview_run_script(view, script);
}

void app_webview_media_set_volume(AppWebView *view, double volume) {
    char script[160];
    std::snprintf(
        script, sizeof(script),
        "(()=>{const v=document.querySelector('video');if(v)v.volume=%f})()", volume
    );
    app_webview_run_script(view, script);
}

void app_demo_render_gl(int framebuffer, int width, int height, float phase) {
    if (width <= 0 || height <= 0) return;
    glBindFramebuffer(GL_FRAMEBUFFER, static_cast<GLuint>(framebuffer));
    glViewport(0, 0, width, height);
    const float wave = (std::sin(phase) + 1.0f) * 0.5f;
    glClearColor(0.08f + wave * 0.18f, 0.12f, 0.30f + wave * 0.35f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);
}

void app_webview_set_focused(AppWebView *view, int focused) {
    if (!view || !view->wpe_view) return;
    if (focused) wpe_view_focus_in(view->wpe_view);
    else wpe_view_focus_out(view->wpe_view);
}

void app_webview_pointer_motion(
    AppWebView *view,
    int x,
    int y,
    unsigned int time,
    unsigned int modifiers
) {
    if (!view || !view->wpe_view) return;
    WPEEvent *event = wpe_event_pointer_move_new(
        WPE_EVENT_POINTER_MOVE,
        view->wpe_view,
        WPE_INPUT_SOURCE_MOUSE,
        time,
        static_cast<WPEModifiers>(modifiers),
        x / view->scale,
        y / view->scale,
        0,
        0
    );
    if (!event) return;
    wpe_view_event(view->wpe_view, event);
    wpe_event_unref(event);
}

void app_webview_pointer_button(
    AppWebView *view,
    int x,
    int y,
    unsigned int time,
    unsigned int button,
    int pressed,
    unsigned int modifiers
) {
    if (!view || !view->wpe_view) return;
    const unsigned int button_modifier = button >= 1 && button <= 5 ? 1u << (7 + button) : 0;
    WPEEvent *event = wpe_event_pointer_button_new(
        pressed ? WPE_EVENT_POINTER_DOWN : WPE_EVENT_POINTER_UP,
        view->wpe_view,
        WPE_INPUT_SOURCE_MOUSE,
        time,
        static_cast<WPEModifiers>(modifiers | button_modifier),
        button,
        x / view->scale,
        y / view->scale,
        pressed ? 1 : 0
    );
    if (!event) return;
    wpe_view_event(view->wpe_view, event);
    wpe_event_unref(event);
}

void app_webview_scroll(
    AppWebView *view,
    int x,
    int y,
    unsigned int time,
    double delta_x,
    double delta_y,
    unsigned int modifiers
) {
    if (!view || !view->wpe_view) return;
    WPEEvent *event = wpe_event_scroll_new(
        view->wpe_view,
        WPE_INPUT_SOURCE_TOUCHPAD,
        time,
        static_cast<WPEModifiers>(modifiers),
        -delta_x,
        -delta_y,
        TRUE,
        FALSE,
        x / view->scale,
        y / view->scale
    );
    if (!event) return;
    if (view->debug) {
        std::fprintf(stderr, "Native WebView: scroll %.1f, %.1f at %d, %d\n", -delta_x, -delta_y, x, y);
    }
    wpe_view_event(view->wpe_view, event);
    wpe_event_unref(event);
}

void app_webview_key(
    AppWebView *view,
    long long compose_key,
    unsigned int code_point,
    int pressed,
    unsigned int modifiers
) {
    if (!view || !view->wpe_view) return;

    // SDL emits a physical key event followed by SDL_TEXTINPUT for printable input. The latter
    // is already keyboard-layout and IME translated, so forwarding both inserts each character
    // twice. Keep physical printable events only for Ctrl/Meta shortcuts; committed text arrives
    // separately with a non-zero code point. Ctrl+Alt is treated as AltGr text input.
    const bool control_shortcut =
        (modifiers & WPE_MODIFIER_KEYBOARD_CONTROL) &&
        !(modifiers & WPE_MODIFIER_KEYBOARD_ALT);
    const bool meta_shortcut = modifiers & WPE_MODIFIER_KEYBOARD_META;
    if (
        !code_point &&
        app_webview_is_printable_key(compose_key) &&
        !control_shortcut &&
        !meta_shortcut
    ) {
        return;
    }

    const unsigned int keysym = app_webview_keysym(compose_key, code_point);
    if (!keysym) return;
    WPEEvent *event = wpe_event_keyboard_new(
        pressed ? WPE_EVENT_KEYBOARD_KEY_DOWN : WPE_EVENT_KEYBOARD_KEY_UP,
        view->wpe_view,
        WPE_INPUT_SOURCE_KEYBOARD,
        0,
        static_cast<WPEModifiers>(modifiers),
        0,
        keysym
    );
    if (!event) return;
    wpe_view_event(view->wpe_view, event);
    wpe_event_unref(event);
}

} // extern "C"
