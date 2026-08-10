#include "app_webview.h"

#include <WebView2.h>
#include <windows.h>
#include <wincodec.h>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdio>
#include <cwchar>
#include <cstring>
#include <string>
#include <vector>

struct AppWebView {
    std::atomic<unsigned long> references{1};
    HWND host = nullptr;
    ICoreWebView2Environment *environment = nullptr;
    ICoreWebView2Controller *controller = nullptr;
    ICoreWebView2 *webview = nullptr;
    std::wstring pending_uri;
    std::string error;
    std::vector<unsigned char> frame;
    int frame_width = 0;
    int frame_height = 0;
    int frame_stride = 0;
    int width = 1;
    int height = 1;
    bool frame_ready = false;
    bool capture_in_flight = false;
    bool destroyed = false;
    ULONGLONG last_capture = 0;
};

template <typename Interface>
static REFIID callback_iid();

template <>
REFIID callback_iid<ICoreWebView2CreateCoreWebView2ControllerCompletedHandler>() {
    return IID_ICoreWebView2CreateCoreWebView2ControllerCompletedHandler;
}

template <>
REFIID callback_iid<ICoreWebView2CreateCoreWebView2EnvironmentCompletedHandler>() {
    return IID_ICoreWebView2CreateCoreWebView2EnvironmentCompletedHandler;
}

template <>
REFIID callback_iid<ICoreWebView2CapturePreviewCompletedHandler>() {
    return IID_ICoreWebView2CapturePreviewCompletedHandler;
}

template <typename Derived, typename Interface>
class CallbackBase : public Interface {
public:
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID id, void **object) override {
        if (!object) return E_POINTER;
        if (IsEqualIID(id, IID_IUnknown) || IsEqualIID(id, callback_iid<Interface>())) {
            *object = static_cast<Interface *>(this);
            AddRef();
            return S_OK;
        }
        *object = nullptr;
        return E_NOINTERFACE;
    }

    ULONG STDMETHODCALLTYPE AddRef() override {
        return references_.fetch_add(1, std::memory_order_relaxed) + 1;
    }

    ULONG STDMETHODCALLTYPE Release() override {
        ULONG remaining = references_.fetch_sub(1, std::memory_order_acq_rel) - 1;
        if (!remaining) delete static_cast<Derived *>(this);
        return remaining;
    }

protected:
    ~CallbackBase() = default;

private:
    std::atomic<ULONG> references_{1};
};

class ControllerReady final : public CallbackBase<
    ControllerReady, ICoreWebView2CreateCoreWebView2ControllerCompletedHandler> {
public:
    explicit ControllerReady(AppWebView *view);
    ~ControllerReady();
    HRESULT STDMETHODCALLTYPE Invoke(HRESULT result, ICoreWebView2Controller *controller) override;
private:
    AppWebView *view_;
};

class EnvironmentReady final : public CallbackBase<
    EnvironmentReady, ICoreWebView2CreateCoreWebView2EnvironmentCompletedHandler> {
public:
    explicit EnvironmentReady(AppWebView *view);
    ~EnvironmentReady();
    HRESULT STDMETHODCALLTYPE Invoke(HRESULT result, ICoreWebView2Environment *environment) override;
private:
    AppWebView *view_;
};

class CaptureReady final
    : public CallbackBase<CaptureReady, ICoreWebView2CapturePreviewCompletedHandler> {
public:
    CaptureReady(AppWebView *view, IStream *stream);
    ~CaptureReady();
    HRESULT STDMETHODCALLTYPE Invoke(HRESULT result) override;
private:
    AppWebView *view_;
    IStream *stream_;
};

static void retain(AppWebView *view) {
    view->references.fetch_add(1, std::memory_order_relaxed);
}

static void release(AppWebView *view) {
    if (view->references.fetch_sub(1, std::memory_order_acq_rel) == 1) delete view;
}

static void set_error(AppWebView *view, const char *message, HRESULT result = S_OK) {
    if (!view || view->destroyed) return;
    char buffer[384];
    if (FAILED(result)) {
        std::snprintf(buffer, sizeof(buffer), "%s (HRESULT %#lx)", message,
                      static_cast<unsigned long>(result));
        view->error = buffer;
    } else {
        view->error = message;
    }
}

static std::wstring widen(const char *text) {
    if (!text || !text[0]) return {};
    int length = MultiByteToWideChar(CP_UTF8, 0, text, -1, nullptr, 0);
    if (length <= 1) return {};
    std::wstring result(static_cast<size_t>(length), L'\0');
    MultiByteToWideChar(CP_UTF8, 0, text, -1, result.data(), length);
    result.pop_back();
    return result;
}

static std::wstring user_data_folder() {
    wchar_t local_app_data[MAX_PATH];
    DWORD size = GetEnvironmentVariableW(L"LOCALAPPDATA", local_app_data, MAX_PATH);
    if (size == 0 || size >= MAX_PATH) return L"ComposeNativeWebView2";
    std::wstring parent(local_app_data);
    parent += L"\\ComposeNative";
    CreateDirectoryW(parent.c_str(), nullptr);
    std::wstring folder = parent + L"\\WebView2";
    CreateDirectoryW(folder.c_str(), nullptr);
    return folder;
}

static void resize_controller(AppWebView *view, int width, int height) {
    if (!view || width <= 0 || height <= 0) return;
    view->width = width;
    view->height = height;
    SetWindowPos(view->host, nullptr, -32000, -32000, width, height,
                 SWP_NOACTIVATE | SWP_NOZORDER);
    if (view->controller) {
        RECT bounds{0, 0, width, height};
        view->controller->put_Bounds(bounds);
        view->controller->put_IsVisible(TRUE);
    }
}

ControllerReady::ControllerReady(AppWebView *view) : view_(view) { retain(view_); }
ControllerReady::~ControllerReady() { release(view_); }

HRESULT ControllerReady::Invoke(HRESULT result, ICoreWebView2Controller *controller) {
    if (FAILED(result) || !controller || view_->destroyed) {
        if (!view_->destroyed) set_error(view_, "Could not create the WebView2 controller", result);
        return S_OK;
    }
    view_->controller = controller;
    controller->AddRef();
    HRESULT core_result = controller->get_CoreWebView2(&view_->webview);
    if (FAILED(core_result) || !view_->webview) {
        set_error(view_, "Could not access the WebView2 browser", core_result);
    } else {
        resize_controller(view_, view_->width, view_->height);
        if (!view_->pending_uri.empty()) view_->webview->Navigate(view_->pending_uri.c_str());
        view_->pending_uri.clear();
        view_->error.clear();
    }
    return S_OK;
}

EnvironmentReady::EnvironmentReady(AppWebView *view) : view_(view) { retain(view_); }
EnvironmentReady::~EnvironmentReady() { release(view_); }

HRESULT EnvironmentReady::Invoke(HRESULT result, ICoreWebView2Environment *environment) {
    if (FAILED(result) || !environment || view_->destroyed) {
        if (!view_->destroyed) set_error(view_, "Could not create the WebView2 environment", result);
        return S_OK;
    }
    view_->environment = environment;
    environment->AddRef();
    auto *ready = new ControllerReady(view_);
    HRESULT controller_result = environment->CreateCoreWebView2Controller(view_->host, ready);
    ready->Release();
    if (FAILED(controller_result))
        set_error(view_, "Could not start the WebView2 controller", controller_result);
    return S_OK;
}

using CreateEnvironment = HRESULT(STDAPICALLTYPE *)(
    PCWSTR, PCWSTR, ICoreWebView2EnvironmentOptions *,
    ICoreWebView2CreateCoreWebView2EnvironmentCompletedHandler *);

static CreateEnvironment load_environment_factory() {
    HMODULE loader = LoadLibraryW(L"WebView2Loader.dll");
    if (!loader) return nullptr;
    return reinterpret_cast<CreateEnvironment>(
        GetProcAddress(loader, "CreateCoreWebView2EnvironmentWithOptions"));
}

static bool decode_png(AppWebView *view, IStream *stream) {
    LARGE_INTEGER start{};
    if (FAILED(stream->Seek(start, STREAM_SEEK_SET, nullptr))) return false;

    IWICImagingFactory *factory = nullptr;
    IWICBitmapDecoder *decoder = nullptr;
    IWICBitmapFrameDecode *source = nullptr;
    IWICFormatConverter *converter = nullptr;
    HRESULT result = CoCreateInstance(CLSID_WICImagingFactory, nullptr, CLSCTX_INPROC_SERVER,
                                      IID_PPV_ARGS(&factory));
    if (SUCCEEDED(result)) result = factory->CreateDecoderFromStream(
        stream, nullptr, WICDecodeMetadataCacheOnLoad, &decoder);
    if (SUCCEEDED(result)) result = decoder->GetFrame(0, &source);
    if (SUCCEEDED(result)) result = factory->CreateFormatConverter(&converter);
    if (SUCCEEDED(result)) result = converter->Initialize(
        source, GUID_WICPixelFormat32bppPBGRA, WICBitmapDitherTypeNone, nullptr, 0.0,
        WICBitmapPaletteTypeCustom);

    UINT width = 0;
    UINT height = 0;
    if (SUCCEEDED(result)) result = converter->GetSize(&width, &height);
    const UINT stride = width * 4;
    std::vector<unsigned char> pixels;
    if (SUCCEEDED(result) && width && height) {
        pixels.resize(static_cast<size_t>(stride) * height);
        result = converter->CopyPixels(nullptr, stride, static_cast<UINT>(pixels.size()),
                                       pixels.data());
    }
    if (SUCCEEDED(result) && !view->destroyed) {
        view->frame = std::move(pixels);
        view->frame_width = static_cast<int>(width);
        view->frame_height = static_cast<int>(height);
        view->frame_stride = static_cast<int>(stride);
        view->frame_ready = true;
    }

    if (converter) converter->Release();
    if (source) source->Release();
    if (decoder) decoder->Release();
    if (factory) factory->Release();
    return SUCCEEDED(result);
}

CaptureReady::CaptureReady(AppWebView *view, IStream *stream)
    : view_(view), stream_(stream) {
    retain(view_);
    stream_->AddRef();
}

CaptureReady::~CaptureReady() {
    stream_->Release();
    release(view_);
}

HRESULT CaptureReady::Invoke(HRESULT result) {
    if (!view_->destroyed) {
        if (SUCCEEDED(result)) {
            if (!decode_png(view_, stream_)) set_error(view_, "Could not decode the WebView2 preview");
        } else {
            set_error(view_, "WebView2 preview capture failed", result);
        }
        view_->capture_in_flight = false;
    }
    return S_OK;
}

static void request_capture(AppWebView *view) {
    if (!view || !view->webview || view->capture_in_flight || view->destroyed) return;
    ULONGLONG now = GetTickCount64();
    if (now - view->last_capture < 32) return;
    view->last_capture = now;

    IStream *stream = nullptr;
    HRESULT result = CreateStreamOnHGlobal(nullptr, TRUE, &stream);
    if (FAILED(result)) {
        set_error(view, "Could not allocate the WebView2 preview stream", result);
        return;
    }
    view->capture_in_flight = true;
    auto *completed = new CaptureReady(view, stream);
    result = view->webview->CapturePreview(
        COREWEBVIEW2_CAPTURE_PREVIEW_IMAGE_FORMAT_PNG, stream, completed);
    completed->Release();
    stream->Release();
    if (FAILED(result)) {
        view->capture_in_flight = false;
        set_error(view, "Could not start WebView2 preview capture", result);
    }
}

static HWND find_input_window(HWND parent) {
    HWND child = GetWindow(parent, GW_CHILD);
    if (!child) return parent;
    while (HWND nested = GetWindow(child, GW_CHILD)) child = nested;
    return child;
}

static WPARAM mouse_modifiers(unsigned int modifiers) {
    WPARAM result = 0;
    if (modifiers & 1u) result |= MK_SHIFT;
    if (modifiers & 2u) result |= MK_CONTROL;
    return result;
}

extern "C" AppWebView *app_webview_create(const char *uri) {
    auto *view = new AppWebView();
    view->pending_uri = widen(uri);
    HRESULT apartment = CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);
    if (FAILED(apartment) && apartment != RPC_E_CHANGED_MODE) {
        set_error(view, "Could not initialize COM for WebView2", apartment);
        return view;
    }

    view->host = CreateWindowExW(
        WS_EX_TOOLWINDOW | WS_EX_NOACTIVATE, L"STATIC", L"Compose Native WebView2", WS_POPUP,
        -32000, -32000, 1, 1, nullptr, nullptr, GetModuleHandleW(nullptr), nullptr);
    if (!view->host) {
        set_error(view, "Could not create the WebView2 host window",
                  HRESULT_FROM_WIN32(GetLastError()));
        return view;
    }
    ShowWindow(view->host, SW_SHOWNOACTIVATE);

    CreateEnvironment create_environment = load_environment_factory();
    if (!create_environment) {
        set_error(view, "WebView2Loader.dll is missing or invalid");
        return view;
    }

    auto *environment_ready = new EnvironmentReady(view);
    std::wstring data_folder = user_data_folder();
    HRESULT result = create_environment(nullptr, data_folder.c_str(), nullptr,
                                        environment_ready);
    environment_ready->Release();
    if (FAILED(result)) {
        set_error(view, "Could not start WebView2", result);
    }
    return view;
}

extern "C" void app_webview_destroy(AppWebView *view) {
    if (!view || view->destroyed) return;
    view->destroyed = true;
    if (view->controller) {
        view->controller->Close();
        view->controller->Release();
        view->controller = nullptr;
    }
    if (view->webview) {
        view->webview->Release();
        view->webview = nullptr;
    }
    if (view->environment) {
        view->environment->Release();
        view->environment = nullptr;
    }
    if (view->host) DestroyWindow(view->host);
    release(view);
}

extern "C" const char *app_webview_error(AppWebView *view) {
    return view && !view->error.empty() ? view->error.c_str() : nullptr;
}

extern "C" int app_webview_render(AppWebView *, int, int, int, float) { return 0; }

extern "C" int app_webview_render_pixels(
    AppWebView *view, void *pixels, int width, int height, int stride, float) {
    if (!view || !pixels || width <= 0 || height <= 0 || stride < width * 4) return 0;
    if (view->width != width || view->height != height) resize_controller(view, width, height);
    request_capture(view);
    if (!view->frame_ready || view->frame_width <= 0 || view->frame_height <= 0) return 0;
    auto *destination = static_cast<unsigned char *>(pixels);
    if (view->frame_width == width && view->frame_height == height) {
        for (int y = 0; y < height; ++y) {
            std::memcpy(destination + static_cast<size_t>(y) * stride,
                        view->frame.data() + static_cast<size_t>(y) * view->frame_stride,
                        static_cast<size_t>(width) * 4);
        }
    } else {
        for (int y = 0; y < height; ++y) {
            int source_y = std::min(view->frame_height - 1, y * view->frame_height / height);
            const auto *source =
                view->frame.data() + static_cast<size_t>(source_y) * view->frame_stride;
            auto *row = destination + static_cast<size_t>(y) * stride;
            for (int x = 0; x < width; ++x) {
                int source_x = std::min(view->frame_width - 1, x * view->frame_width / width);
                std::memcpy(row + static_cast<size_t>(x) * 4,
                            source + static_cast<size_t>(source_x) * 4, 4);
            }
        }
    }
    view->frame_ready = false;
    return 1;
}

extern "C" void app_webview_load_uri(AppWebView *view, const char *uri) {
    if (!view || !uri || !uri[0]) return;
    std::wstring value = widen(uri);
    if (view->webview) view->webview->Navigate(value.c_str());
    else view->pending_uri = std::move(value);
}

extern "C" void app_webview_go_back(AppWebView *view) {
    if (view && view->webview) view->webview->GoBack();
}

extern "C" void app_webview_go_forward(AppWebView *view) {
    if (view && view->webview) view->webview->GoForward();
}

extern "C" void app_webview_reload(AppWebView *view) {
    if (view && view->webview) view->webview->Reload();
}

extern "C" int app_webview_can_go_back(AppWebView *view) {
    BOOL result = FALSE;
    return view && view->webview && SUCCEEDED(view->webview->get_CanGoBack(&result)) && result;
}

extern "C" int app_webview_can_go_forward(AppWebView *view) {
    BOOL result = FALSE;
    return view && view->webview && SUCCEEDED(view->webview->get_CanGoForward(&result)) && result;
}

static void execute_script(AppWebView *view, const wchar_t *script) {
    if (view && view->webview) view->webview->ExecuteScript(script, nullptr);
}

extern "C" void app_webview_media_set_playing(AppWebView *view, int playing) {
    execute_script(view, playing
        ? L"document.querySelectorAll('video,audio').forEach(x=>x.play())"
        : L"document.querySelectorAll('video,audio').forEach(x=>x.pause())");
}

extern "C" void app_webview_media_seek(AppWebView *view, double seconds) {
    wchar_t script[192];
    std::swprintf(script, 192,
                  L"document.querySelectorAll('video,audio').forEach(x=>x.currentTime=%g)", seconds);
    execute_script(view, script);
}

extern "C" void app_webview_media_set_volume(AppWebView *view, double volume) {
    wchar_t script[192];
    std::swprintf(script, 192,
                  L"document.querySelectorAll('video,audio').forEach(x=>x.volume=%g)",
                  std::clamp(volume, 0.0, 1.0));
    execute_script(view, script);
}

extern "C" void app_webview_set_focused(AppWebView *view, int focused) {
    if (view && view->controller && focused)
        view->controller->MoveFocus(COREWEBVIEW2_MOVE_FOCUS_REASON_PROGRAMMATIC);
}

extern "C" void app_webview_pointer_motion(
    AppWebView *view, int x, int y, unsigned int, unsigned int modifiers) {
    if (!view || !view->host) return;
    PostMessageW(find_input_window(view->host), WM_MOUSEMOVE, mouse_modifiers(modifiers),
                 MAKELPARAM(x, y));
}

extern "C" void app_webview_pointer_button(
    AppWebView *view, int x, int y, unsigned int, unsigned int button, int pressed,
    unsigned int modifiers) {
    if (!view || !view->host) return;
    UINT down = button == 3 ? WM_RBUTTONDOWN : button == 2 ? WM_MBUTTONDOWN : WM_LBUTTONDOWN;
    UINT up = button == 3 ? WM_RBUTTONUP : button == 2 ? WM_MBUTTONUP : WM_LBUTTONUP;
    PostMessageW(find_input_window(view->host), pressed ? down : up, mouse_modifiers(modifiers),
                 MAKELPARAM(x, y));
}

extern "C" void app_webview_scroll(
    AppWebView *view, int x, int y, unsigned int, double, double delta_y,
    unsigned int modifiers) {
    if (!view || !view->host) return;
    HWND input = find_input_window(view->host);
    POINT point{x, y};
    ClientToScreen(input, &point);
    int wheel = static_cast<int>(std::lround(-delta_y * WHEEL_DELTA));
    WPARAM value = MAKEWPARAM(mouse_modifiers(modifiers), static_cast<WORD>(wheel));
    PostMessageW(input, WM_MOUSEWHEEL, value, MAKELPARAM(point.x, point.y));
}

extern "C" void app_webview_key(
    AppWebView *view, long long compose_key, unsigned int code_point, int pressed,
    unsigned int) {
    if (!view || !view->host) return;
    HWND input = find_input_window(view->host);
    if (pressed && code_point) {
        PostMessageW(input, WM_CHAR, code_point, 1);
        return;
    }
    UINT virtual_key = static_cast<UINT>(compose_key & 0xff);
    PostMessageW(input, pressed ? WM_KEYDOWN : WM_KEYUP, virtual_key, 1);
}
