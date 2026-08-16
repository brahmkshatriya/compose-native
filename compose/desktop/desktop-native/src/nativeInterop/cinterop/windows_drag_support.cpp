#include "include/native_drag.h"

#include <SDL3/SDL.h>
#include <windows.h>
#include <dwmapi.h>
#include <objidl.h>
#include <ole2.h>
#include <shellapi.h>
#include <shlobj.h>

#include <atomic>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

namespace {

constexpr const wchar_t *extended_title_bar_property = L"ComposeNativeExtendedTitleBar";
constexpr int caption_button_count = 3;
constexpr DWORD state_system_invisible = 0x00008000;
constexpr DWORD state_system_focusable = 0x00100000;

enum CaptionButtonType {
    CaptionButtonMinimize = 0,
    CaptionButtonMaximize = 1,
    CaptionButtonClose = 2,
};

struct ExtendedTitleBarState {
    WNDPROC original_window_proc = nullptr;
    SDL_Window *sdl_window = nullptr;
    bool tracking_non_client_mouse = false;
    bool has_non_client_pointer_position = false;
    POINT non_client_pointer_position = {};
    bool has_caption_button_bounds[caption_button_count] = {};
    RECT caption_button_bounds[caption_button_count] = {};
};

LRESULT caption_button_hit_test(
    const ExtendedTitleBarState *state,
    const POINT &point
) {
    if (state->has_caption_button_bounds[CaptionButtonMinimize] &&
        PtInRect(&state->caption_button_bounds[CaptionButtonMinimize], point)) {
        return HTMINBUTTON;
    }
    if (state->has_caption_button_bounds[CaptionButtonMaximize] &&
        PtInRect(&state->caption_button_bounds[CaptionButtonMaximize], point)) {
        return HTMAXBUTTON;
    }
    if (state->has_caption_button_bounds[CaptionButtonClose] &&
        PtInRect(&state->caption_button_bounds[CaptionButtonClose], point)) {
        return HTCLOSE;
    }
    return HTNOWHERE;
}

bool has_measured_caption_buttons(const ExtendedTitleBarState *state) {
    for (int index = 0; index < caption_button_count; ++index) {
        if (state->has_caption_button_bounds[index]) return true;
    }
    return false;
}

bool is_fullscreen(const ExtendedTitleBarState *state) {
    return state->sdl_window &&
        (SDL_GetWindowFlags(state->sdl_window) & SDL_WINDOW_FULLSCREEN) != 0;
}

RECT client_rect_to_screen(HWND window, const RECT &client_rect) {
    POINT corners[2] = {
        {client_rect.left, client_rect.top},
        {client_rect.right, client_rect.bottom},
    };
    MapWindowPoints(window, nullptr, corners, 2);
    return {corners[0].x, corners[0].y, corners[1].x, corners[1].y};
}

void set_title_bar_button_info(
    HWND window,
    const ExtendedTitleBarState *state,
    TITLEBARINFOEX *info,
    int info_index,
    int button_type
) {
    if (!state->has_caption_button_bounds[button_type]) return;
    info->rgrect[info_index] =
        client_rect_to_screen(window, state->caption_button_bounds[button_type]);
    info->rgstate[info_index] = state_system_focusable;
}

void populate_title_bar_info(
    HWND window,
    const ExtendedTitleBarState *state,
    TITLEBARINFOEX *info
) {
    for (int index = 0; index <= CCHILDREN_TITLEBAR; ++index) {
        info->rgstate[index] = state_system_invisible;
        info->rgrect[index] = {0, 0, 0, 0};
    }

    RECT client_bounds = {};
    GetClientRect(window, &client_bounds);
    LONG title_bar_bottom = 0;
    for (int index = 0; index < caption_button_count; ++index) {
        if (state->has_caption_button_bounds[index] &&
            state->caption_button_bounds[index].bottom > title_bar_bottom) {
            title_bar_bottom = state->caption_button_bounds[index].bottom;
        }
    }
    const RECT title_bar_bounds = {0, 0, client_bounds.right, title_bar_bottom};
    info->rcTitleBar = client_rect_to_screen(window, title_bar_bounds);
    info->rgrect[0] = info->rcTitleBar;
    info->rgstate[0] = 0;
    set_title_bar_button_info(window, state, info, 2, CaptionButtonMinimize);
    set_title_bar_button_info(window, state, info, 3, CaptionButtonMaximize);
    set_title_bar_button_info(window, state, info, 5, CaptionButtonClose);
}

UINT window_dpi(HWND window) {
    using GetDpiForWindowFunction = UINT(WINAPI *)(HWND);
    const HMODULE user32 = GetModuleHandleW(L"user32.dll");
    const auto get_dpi_for_window =
        user32
            ? reinterpret_cast<GetDpiForWindowFunction>(
                  GetProcAddress(user32, "GetDpiForWindow"))
            : nullptr;
    return get_dpi_for_window ? get_dpi_for_window(window) : 96;
}

int system_metric_for_window(HWND window, int metric) {
    using GetSystemMetricsForDpiFunction = int(WINAPI *)(int, UINT);
    const HMODULE user32 = GetModuleHandleW(L"user32.dll");
    const auto get_system_metrics_for_dpi =
        user32
            ? reinterpret_cast<GetSystemMetricsForDpiFunction>(
                  GetProcAddress(user32, "GetSystemMetricsForDpi"))
            : nullptr;
    return get_system_metrics_for_dpi
        ? get_system_metrics_for_dpi(metric, window_dpi(window))
        : GetSystemMetrics(metric);
}

bool query_title_bar_metrics(HWND window, int *caption_button_width, int *title_bar_height) {
    RECT bounds = {};
    const bool has_caption_bounds =
        SUCCEEDED(DwmGetWindowAttribute(
            window,
            DWMWA_CAPTION_BUTTON_BOUNDS,
            &bounds,
            sizeof(bounds))) &&
        bounds.right > bounds.left && bounds.bottom > bounds.top;
    const int button_width = system_metric_for_window(window, SM_CXSIZE);
    const int frame_height = system_metric_for_window(window, SM_CYFRAME);
    const int padded_border = system_metric_for_window(window, SM_CXPADDEDBORDER);
    const int system_title_bar_height =
        system_metric_for_window(window, SM_CYCAPTION) + frame_height + padded_border;
    if (has_caption_bounds) {
        if (caption_button_width) *caption_button_width = button_width;
        if (title_bar_height) {
            *title_bar_height = bounds.bottom > system_title_bar_height
                ? bounds.bottom
                : system_title_bar_height;
        }
        return true;
    }

    if (caption_button_width) *caption_button_width = button_width;
    if (title_bar_height) *title_bar_height = system_title_bar_height;
    return true;
}

void extend_frame_into_title_bar(HWND window) {
    int title_bar_height = 0;
    query_title_bar_metrics(window, nullptr, &title_bar_height);
    const MARGINS margins = {0, 0, title_bar_height, 0};
    DwmExtendFrameIntoClientArea(window, &margins);
}

void push_non_client_mouse_motion(HWND window, ExtendedTitleBarState *state, LPARAM l_param) {
    if (!state->sdl_window) return;
    POINT point = {
        static_cast<short>(LOWORD(l_param)),
        static_cast<short>(HIWORD(l_param)),
    };
    if (!ScreenToClient(window, &point)) return;

    const SDL_WindowID window_id = SDL_GetWindowID(state->sdl_window);
    if (!state->tracking_non_client_mouse) {
        TRACKMOUSEEVENT tracking = {};
        tracking.cbSize = sizeof(tracking);
        tracking.dwFlags = TME_LEAVE | TME_NONCLIENT;
        tracking.hwndTrack = window;
        if (TrackMouseEvent(&tracking)) state->tracking_non_client_mouse = true;

        SDL_Event enter = {};
        enter.type = SDL_EVENT_WINDOW_MOUSE_ENTER;
        enter.window.windowID = window_id;
        SDL_PushEvent(&enter);
    }

    SDL_Event motion = {};
    motion.type = SDL_EVENT_MOUSE_MOTION;
    motion.motion.windowID = window_id;
    motion.motion.state = SDL_GetMouseState(nullptr, nullptr);
    motion.motion.x = static_cast<float>(point.x);
    motion.motion.y = static_cast<float>(point.y);
    if (state->has_non_client_pointer_position) {
        motion.motion.xrel = static_cast<float>(point.x - state->non_client_pointer_position.x);
        motion.motion.yrel = static_cast<float>(point.y - state->non_client_pointer_position.y);
    }
    state->non_client_pointer_position = point;
    state->has_non_client_pointer_position = true;
    SDL_PushEvent(&motion);
}

void push_non_client_mouse_leave(ExtendedTitleBarState *state) {
    state->tracking_non_client_mouse = false;
    state->has_non_client_pointer_position = false;
    if (!state->sdl_window) return;
    SDL_Event leave = {};
    leave.type = SDL_EVENT_WINDOW_MOUSE_LEAVE;
    leave.window.windowID = SDL_GetWindowID(state->sdl_window);
    SDL_PushEvent(&leave);
}

LRESULT CALLBACK extended_title_bar_window_proc(
    HWND window,
    UINT message,
    WPARAM w_param,
    LPARAM l_param
) {
    auto *state = static_cast<ExtendedTitleBarState *>(
        GetPropW(window, extended_title_bar_property));
    if (!state || !state->original_window_proc) {
        return DefWindowProcW(window, message, w_param, l_param);
    }

    LRESULT dwm_result = 0;
    const bool dwm_handled =
        DwmDefWindowProc(window, message, w_param, l_param, &dwm_result) != FALSE;

    if (message == WM_NCHITTEST) {
        POINT point = {
            static_cast<short>(LOWORD(l_param)),
            static_cast<short>(HIWORD(l_param)),
        };
        if (ScreenToClient(window, &point)) {
            const LRESULT measured_result = caption_button_hit_test(state, point);
            if (measured_result != HTNOWHERE) {
                if (is_fullscreen(state)) return HTCLIENT;
                // Native non-client results retain Windows tooltips and Snap Layouts while the
                // rectangles themselves follow the buttons that Compose actually laid out.
                return dwm_handled && dwm_result == measured_result
                    ? dwm_result
                    : measured_result;
            }
        }

        if (dwm_handled) {
            if (has_measured_caption_buttons(state) &&
                (dwm_result == HTMINBUTTON ||
                 dwm_result == HTMAXBUTTON ||
                 dwm_result == HTCLOSE)) {
                return HTCLIENT;
            }
            return dwm_result;
        }
    }

    if (message == WM_NCCALCSIZE && w_param != FALSE) {
        auto *params = reinterpret_cast<NCCALCSIZE_PARAMS *>(l_param);
        const LONG window_top = params->rgrc[0].top;
        CallWindowProcW(state->original_window_proc, window, message, w_param, l_param);
        // Preserve the system-calculated side and bottom borders, but make the title bar part of
        // the client area. A maximized resizable window extends its outer frame beyond the monitor;
        // start the client at the visible work-area edge so that frame thickness does not clip the
        // top of the Compose title bar.
        LONG client_top = window_top;
        if (IsZoomed(window)) {
            MONITORINFO monitor_info = {};
            monitor_info.cbSize = sizeof(monitor_info);
            const HMONITOR monitor = MonitorFromWindow(window, MONITOR_DEFAULTTONEAREST);
            if (monitor && GetMonitorInfoW(monitor, &monitor_info) &&
                monitor_info.rcWork.top > client_top) {
                client_top = monitor_info.rcWork.top;
            }
        }
        params->rgrc[0].top = client_top;
        return 0;
    }

    if (message == WM_GETTITLEBARINFOEX && l_param != 0) {
        auto *info = reinterpret_cast<TITLEBARINFOEX *>(l_param);
        if (info->cbSize >= sizeof(TITLEBARINFOEX)) {
            populate_title_bar_info(window, state, info);
            return 0;
        }
    }

    if (message == WM_NCMOUSEMOVE) {
        // DWM owns the maximize-button hit box so Windows can show Snap Layouts. Mirror its
        // non-client pointer movement into SDL so Compose still receives hover transitions.
        push_non_client_mouse_motion(window, state, l_param);
    } else if (message == WM_NCMOUSELEAVE) {
        push_non_client_mouse_leave(state);
    }

    if (message == WM_ACTIVATE || message == WM_DWMCOMPOSITIONCHANGED) {
        extend_frame_into_title_bar(window);
    }

    if (dwm_handled) return dwm_result;
    return CallWindowProcW(state->original_window_proc, window, message, w_param, l_param);
}

HWND window_handle(SDL_Window *window) {
    if (!window) return nullptr;
    const SDL_PropertiesID properties = SDL_GetWindowProperties(window);
    if (properties == 0) return nullptr;
    return static_cast<HWND>(SDL_GetPointerProperty(
        properties,
        SDL_PROP_WINDOW_WIN32_HWND_POINTER,
        nullptr
    ));
}

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

std::wstring utf8_to_wide(const std::string &value) {
    if (value.empty()) return {};
    const int count = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, value.data(),
        static_cast<int>(value.size()), nullptr, 0);
    if (count <= 0) return {};
    std::wstring result(static_cast<size_t>(count), L'\0');
    MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, value.data(),
        static_cast<int>(value.size()), result.data(), count);
    return result;
}

int hex_value(char value) {
    if (value >= '0' && value <= '9') return value - '0';
    if (value >= 'a' && value <= 'f') return value - 'a' + 10;
    if (value >= 'A' && value <= 'F') return value - 'A' + 10;
    return -1;
}

std::string decode_file_uri(std::string value) {
    if (value.rfind("file://", 0) == 0) value.erase(0, 7);
    if (value.size() >= 3 && value[0] == '/' && value[2] == ':') value.erase(0, 1);
    std::string decoded;
    decoded.reserve(value.size());
    for (size_t index = 0; index < value.size(); ++index) {
        if (value[index] == '%' && index + 2 < value.size()) {
            const int high = hex_value(value[index + 1]);
            const int low = hex_value(value[index + 2]);
            if (high >= 0 && low >= 0) {
                decoded.push_back(static_cast<char>((high << 4) | low));
                index += 2;
                continue;
            }
        }
        decoded.push_back(value[index] == '/' ? '\\' : value[index]);
    }
    return decoded;
}

std::vector<std::wstring> parse_files(const char *uri_list) {
    std::vector<std::wstring> files;
    if (!uri_list) return files;
    std::string input(uri_list);
    size_t start = 0;
    while (start < input.size()) {
        size_t end = input.find_first_of("\r\n", start);
        if (end == std::string::npos) end = input.size();
        if (end > start && input[start] != '#') {
            std::wstring path = utf8_to_wide(decode_file_uri(input.substr(start, end - start)));
            if (!path.empty()) files.push_back(std::move(path));
        }
        start = end + 1;
        while (start < input.size() && (input[start] == '\r' || input[start] == '\n')) ++start;
    }
    return files;
}

class DataObject final : public IDataObject {
public:
    DataObject(std::wstring text, std::vector<std::wstring> files)
        : text_(std::move(text)), files_(std::move(files)) {}

    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID iid, void **output) override {
        if (!output) return E_POINTER;
        if (iid == IID_IUnknown || iid == IID_IDataObject) {
            *output = static_cast<IDataObject *>(this);
            AddRef();
            return S_OK;
        }
        *output = nullptr;
        return E_NOINTERFACE;
    }

    ULONG STDMETHODCALLTYPE AddRef() override { return ++references_; }
    ULONG STDMETHODCALLTYPE Release() override {
        const ULONG result = --references_;
        if (result == 0) delete this;
        return result;
    }

    HRESULT STDMETHODCALLTYPE GetData(FORMATETC *format, STGMEDIUM *medium) override {
        if (!format || !medium || !(format->tymed & TYMED_HGLOBAL)) return DV_E_FORMATETC;
        if (format->cfFormat == CF_UNICODETEXT && !text_.empty()) return copy_text(medium);
        if (format->cfFormat == CF_HDROP && !files_.empty()) return copy_files(medium);
        return DV_E_FORMATETC;
    }

    HRESULT STDMETHODCALLTYPE GetDataHere(FORMATETC *, STGMEDIUM *) override { return E_NOTIMPL; }
    HRESULT STDMETHODCALLTYPE QueryGetData(FORMATETC *format) override {
        if (!format || !(format->tymed & TYMED_HGLOBAL)) return DV_E_FORMATETC;
        if (format->cfFormat == CF_UNICODETEXT && !text_.empty()) return S_OK;
        if (format->cfFormat == CF_HDROP && !files_.empty()) return S_OK;
        return DV_E_FORMATETC;
    }
    HRESULT STDMETHODCALLTYPE GetCanonicalFormatEtc(FORMATETC *, FORMATETC *output) override {
        if (output) output->ptd = nullptr;
        return E_NOTIMPL;
    }
    HRESULT STDMETHODCALLTYPE SetData(FORMATETC *, STGMEDIUM *, BOOL) override { return E_NOTIMPL; }
    HRESULT STDMETHODCALLTYPE EnumFormatEtc(DWORD direction, IEnumFORMATETC **output) override {
        if (direction != DATADIR_GET || !output) return E_NOTIMPL;
        std::vector<FORMATETC> formats;
        if (!text_.empty()) formats.push_back({CF_UNICODETEXT, nullptr, DVASPECT_CONTENT, -1, TYMED_HGLOBAL});
        if (!files_.empty()) formats.push_back({CF_HDROP, nullptr, DVASPECT_CONTENT, -1, TYMED_HGLOBAL});
        return SHCreateStdEnumFmtEtc(static_cast<UINT>(formats.size()), formats.data(), output);
    }
    HRESULT STDMETHODCALLTYPE DAdvise(FORMATETC *, DWORD, IAdviseSink *, DWORD *) override { return OLE_E_ADVISENOTSUPPORTED; }
    HRESULT STDMETHODCALLTYPE DUnadvise(DWORD) override { return OLE_E_ADVISENOTSUPPORTED; }
    HRESULT STDMETHODCALLTYPE EnumDAdvise(IEnumSTATDATA **) override { return OLE_E_ADVISENOTSUPPORTED; }

private:
    HRESULT copy_text(STGMEDIUM *medium) {
        const SIZE_T bytes = (text_.size() + 1) * sizeof(wchar_t);
        HGLOBAL memory = GlobalAlloc(GMEM_MOVEABLE, bytes);
        if (!memory) return E_OUTOFMEMORY;
        void *target = GlobalLock(memory);
        std::memcpy(target, text_.c_str(), bytes);
        GlobalUnlock(memory);
        medium->tymed = TYMED_HGLOBAL;
        medium->hGlobal = memory;
        medium->pUnkForRelease = nullptr;
        return S_OK;
    }

    HRESULT copy_files(STGMEDIUM *medium) {
        size_t characters = 1;
        for (const auto &file : files_) characters += file.size() + 1;
        const SIZE_T bytes = sizeof(DROPFILES) + characters * sizeof(wchar_t);
        HGLOBAL memory = GlobalAlloc(GMEM_MOVEABLE | GMEM_ZEROINIT, bytes);
        if (!memory) return E_OUTOFMEMORY;
        auto *drop = static_cast<DROPFILES *>(GlobalLock(memory));
        drop->pFiles = sizeof(DROPFILES);
        drop->fWide = TRUE;
        auto *target = reinterpret_cast<wchar_t *>(reinterpret_cast<unsigned char *>(drop) + sizeof(DROPFILES));
        for (const auto &file : files_) {
            std::memcpy(target, file.c_str(), file.size() * sizeof(wchar_t));
            target += file.size() + 1;
        }
        GlobalUnlock(memory);
        medium->tymed = TYMED_HGLOBAL;
        medium->hGlobal = memory;
        medium->pUnkForRelease = nullptr;
        return S_OK;
    }

    std::atomic<ULONG> references_{1};
    std::wstring text_;
    std::vector<std::wstring> files_;
};

class DropSource final : public IDropSource {
public:
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID iid, void **output) override {
        if (!output) return E_POINTER;
        if (iid == IID_IUnknown || iid == IID_IDropSource) {
            *output = static_cast<IDropSource *>(this);
            AddRef();
            return S_OK;
        }
        *output = nullptr;
        return E_NOINTERFACE;
    }
    ULONG STDMETHODCALLTYPE AddRef() override { return ++references_; }
    ULONG STDMETHODCALLTYPE Release() override {
        const ULONG result = --references_;
        if (result == 0) delete this;
        return result;
    }
    HRESULT STDMETHODCALLTYPE QueryContinueDrag(BOOL escape, DWORD key_state) override {
        if (escape) return DRAGDROP_S_CANCEL;
        if ((key_state & MK_LBUTTON) == 0) return DRAGDROP_S_DROP;
        return S_OK;
    }
    HRESULT STDMETHODCALLTYPE GiveFeedback(DWORD) override { return DRAGDROP_S_USEDEFAULTCURSORS; }

private:
    std::atomic<ULONG> references_{1};
};

struct Drag {
    HWND window = nullptr;
    bool active = false;
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
    SDL_PropertiesID properties = SDL_GetWindowProperties(window);
    HWND hwnd = static_cast<HWND>(SDL_GetPointerProperty(
        properties,
        SDL_PROP_WINDOW_WIN32_HWND_POINTER,
        nullptr
    ));
    if (!hwnd) {
        set_error(error_message, "SDL did not expose a Win32 window handle");
        return nullptr;
    }
    const HRESULT initialized = OleInitialize(nullptr);
    if (FAILED(initialized) && initialized != RPC_E_CHANGED_MODE) {
        set_error(error_message, "Could not initialize OLE drag-and-drop");
        return nullptr;
    }
    auto *drag = new Drag();
    drag->window = hwnd;
    return drag;
}

void kdrag_destroy(void *raw) { delete static_cast<Drag *>(raw); }

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
    Drag *drag = static_cast<Drag *>(raw);
    const std::wstring wide_text = utf8_to_wide(text ? text : "");
    std::vector<std::wstring> files = parse_files(uri_list);
    if (!drag || (wide_text.empty() && files.empty())) {
        set_error(error_message, "Drag transfer data is empty");
        return 0;
    }
    auto *data = new DataObject(wide_text, std::move(files));
    auto *source = new DropSource();
    drag->active = true;
    DWORD effect = DROPEFFECT_NONE;
    const HRESULT result = DoDragDrop(data, source, DROPEFFECT_COPY | DROPEFFECT_MOVE, &effect);
    drag->active = false;
    source->Release();
    data->Release();
    if (result == DRAGDROP_S_DROP || result == DRAGDROP_S_CANCEL) return 1;
    set_error(error_message, "The Win32 OLE drag operation failed");
    return 0;
}

void kdrag_pointer_motion(void *) {}
void kdrag_pointer_release(void *) {}
void kdrag_handle_syswm(void *, const void *) {}
int kdrag_active(void *raw) {
    Drag *drag = static_cast<Drag *>(raw);
    return drag && drag->active ? 1 : 0;
}

int kplatform_window_set_transparent(void *raw_window, int transparent) {
    SDL_Window *window = static_cast<SDL_Window *>(raw_window);
    if (!window) return 0;
    const bool supported = (SDL_GetWindowFlags(window) & SDL_WINDOW_TRANSPARENT) != 0;
    return transparent ? (supported ? 1 : 0) : 1;
}

int kplatform_window_allow_drawing_inside_title_bar(void *raw_window, int allow) {
    SDL_Window *sdl_window = static_cast<SDL_Window *>(raw_window);
    HWND window = window_handle(sdl_window);
    if (!window) return 0;

    auto *state = static_cast<ExtendedTitleBarState *>(
        GetPropW(window, extended_title_bar_property));
    if (allow) {
        if (state) return 1;
        state = new ExtendedTitleBarState();
        state->original_window_proc = reinterpret_cast<WNDPROC>(
            GetWindowLongPtrW(window, GWLP_WNDPROC));
        state->sdl_window = sdl_window;
        if (!state->original_window_proc ||
            !SetPropW(window, extended_title_bar_property, state)) {
            delete state;
            return 0;
        }
        SetLastError(ERROR_SUCCESS);
        const LONG_PTR previous = SetWindowLongPtrW(
            window,
            GWLP_WNDPROC,
            reinterpret_cast<LONG_PTR>(extended_title_bar_window_proc));
        if (previous == 0 && GetLastError() != ERROR_SUCCESS) {
            RemovePropW(window, extended_title_bar_property);
            delete state;
            return 0;
        }
        extend_frame_into_title_bar(window);
    } else if (state) {
        SetWindowLongPtrW(
            window,
            GWLP_WNDPROC,
            reinterpret_cast<LONG_PTR>(state->original_window_proc));
        RemovePropW(window, extended_title_bar_property);
        const MARGINS margins = {0, 0, 0, 0};
        DwmExtendFrameIntoClientArea(window, &margins);
        delete state;
    }

    SetWindowPos(
        window,
        nullptr,
        0,
        0,
        0,
        0,
        SWP_FRAMECHANGED | SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE | SWP_NOZORDER
    );
    return 1;
}

int kplatform_window_get_title_bar_metrics(
    void *raw_window,
    int *caption_button_width,
    int *title_bar_height
) {
    SDL_Window *sdl_window = static_cast<SDL_Window *>(raw_window);
    HWND window = window_handle(sdl_window);
    if (!window || !caption_button_width || !title_bar_height) return 0;
    return query_title_bar_metrics(window, caption_button_width, title_bar_height) ? 1 : 0;
}

int kplatform_window_set_caption_button_bounds(
    void *raw_window,
    int button_type,
    int enabled,
    int x,
    int y,
    int width,
    int height
) {
    SDL_Window *sdl_window = static_cast<SDL_Window *>(raw_window);
    HWND window = window_handle(sdl_window);
    if (!window) return 0;
    auto *state = static_cast<ExtendedTitleBarState *>(
        GetPropW(window, extended_title_bar_property));
    if (!state || button_type < 0 || button_type >= caption_button_count) return 0;
    state->has_caption_button_bounds[button_type] = enabled != 0 && width > 0 && height > 0;
    state->caption_button_bounds[button_type] = {x, y, x + width, y + height};
    return 1;
}

int kplatform_window_set_shadow(void *, int enabled) {
    return enabled ? 0 : 1;
}

int kplatform_window_refresh_shadow(void *) {
    return 0;
}

namespace {

constexpr DWORD DWM_ATTRIBUTE_IMMERSIVE_DARK_MODE = 20;
constexpr DWORD DWM_ATTRIBUTE_CAPTION_COLOR = 35;
constexpr DWORD DWM_ATTRIBUTE_BORDER_COLOR = 34;
constexpr DWORD DWM_ATTRIBUTE_TEXT_COLOR = 36;

DWORD to_color_ref(int red, int green, int blue) {
    // COLORREF is 0x00BBGGRR.
    return static_cast<DWORD>(blue & 0xff) << 16 |
        static_cast<DWORD>(green & 0xff) << 8 |
        static_cast<DWORD>(red & 0xff);
}

bool set_window_attribute(HWND window, DWORD attribute, DWORD value) {
    return SUCCEEDED(DwmSetWindowAttribute(
        window, attribute, &value, static_cast<DWORD>(sizeof(value))));
}

} // namespace

int kplatform_window_set_title_bar_color(
    void *raw_window,
    int background_r,
    int background_g,
    int background_b,
    int foreground_r,
    int foreground_g,
    int foreground_b
) {
    SDL_Window *sdl_window = static_cast<SDL_Window *>(raw_window);
    HWND window = window_handle(sdl_window);
    if (!window) return 0;

    auto has_component = [](int value) { return value >= 0 && value <= 255; };
    const bool has_background =
        has_component(background_r) && has_component(background_g) && has_component(background_b);
    const bool has_foreground =
        has_component(foreground_r) && has_component(foreground_g) && has_component(foreground_b);

    if (!has_background && !has_foreground) return 1;

    bool success = true;
    if (has_background) {
        const int luminance = (2126 * background_r + 7152 * background_g + 722 * background_b) / 10000;
        const DWORD dark = luminance < 128 ? 1u : 0u;
        // Immersive dark mode drives the system caption buttons. It must be applied
        // before the caption color, because toggling it resets the caption color.
        success =
            set_window_attribute(window, DWM_ATTRIBUTE_IMMERSIVE_DARK_MODE, dark) && success;
        // Caption, border, and text colors are best-effort: they are rejected on
        // older builds and ignored while the frame is extended into the title bar.
        set_window_attribute(
            window,
            DWM_ATTRIBUTE_CAPTION_COLOR,
            to_color_ref(background_r, background_g, background_b));
        set_window_attribute(
            window,
            DWM_ATTRIBUTE_BORDER_COLOR,
            to_color_ref(background_r, background_g, background_b));
    }
    if (has_foreground) {
        set_window_attribute(
            window,
            DWM_ATTRIBUTE_TEXT_COLOR,
            to_color_ref(foreground_r, foreground_g, foreground_b));
    }
    return success ? 1 : 0;
}

} // extern "C"
