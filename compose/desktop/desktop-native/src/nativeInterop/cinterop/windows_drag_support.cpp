#include "include/native_drag.h"

#include <SDL3/SDL.h>
#include <dwmapi.h>
#include <objidl.h>
#include <ole2.h>
#include <shellapi.h>
#include <shlobj.h>
#include <windows.h>

#include <atomic>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

namespace {

constexpr const wchar_t *extended_title_bar_property = L"ComposeNativeExtendedTitleBar";

struct ExtendedTitleBarState {
    WNDPROC original_window_proc = nullptr;
};

void extend_frame_into_title_bar(HWND window) {
    const int title_bar_height =
        GetSystemMetrics(SM_CYCAPTION) +
        GetSystemMetrics(SM_CYFRAME) +
        GetSystemMetrics(SM_CXPADDEDBORDER);
    const MARGINS margins = {0, 0, title_bar_height, 0};
    DwmExtendFrameIntoClientArea(window, &margins);
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

    if (message == WM_NCHITTEST) {
        LRESULT result = 0;
        if (DwmDefWindowProc(window, message, w_param, l_param, &result)) return result;
    }

    if (message == WM_NCCALCSIZE && w_param != FALSE) {
        auto *params = reinterpret_cast<NCCALCSIZE_PARAMS *>(l_param);
        const LONG window_top = params->rgrc[0].top;
        CallWindowProcW(state->original_window_proc, window, message, w_param, l_param);
        // Preserve the system-calculated side and bottom borders, but make the title bar part of
        // the client area. SDL hit testing supplies the top resize border and draggable regions.
        params->rgrc[0].top = window_top;
        return 0;
    }

    if (message == WM_ACTIVATE || message == WM_DWMCOMPOSITIONCHANGED) {
        extend_frame_into_title_bar(window);
    }

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
