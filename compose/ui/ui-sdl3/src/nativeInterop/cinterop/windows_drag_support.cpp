#include "include/native_drag.h"

#include <SDL3/SDL.h>
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

} // extern "C"
