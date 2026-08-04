#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif

#define GL_GLEXT_PROTOTYPES

#include "cairo_compose.h"

#include <SDL.h>
#include <SDL_opengl.h>
#include <clipper2/clipper.engine.h>
#include <jpeglib.h>
#include <webp/decode.h>

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <fontconfig/fontconfig.h>
#include <fontconfig/fcfreetype.h>
#include <setjmp.h>
#include <vector>

extern "C" {

struct KGpuContext {
    SDL_Window *window = nullptr;
    SDL_GLContext gl = nullptr;
    int width = 1;
    int height = 1;
    const char *renderer = "unknown";
};

struct KGpuTexture {
    GLuint texture = 0;
    int width = 0;
    int height = 0;
};

struct KGpuLayer {
    GLuint framebuffer = 0;
    GLuint texture = 0;
    int width = 0;
    int height = 0;
};

// libmpv documents that legacy state must not interfere with its core GL state. Explicitly restore
// the compatibility defaults rather than relying on each mpv/driver combination to leave them in
// exactly the same state. This also makes our fixed-function compositor independent of mpv's last
// shader, texture unit, buffer binding, and texture matrix.
static void kgpu_reset_compat_state() {
    glUseProgram(0);
    glActiveTexture(GL_TEXTURE0);
    glClientActiveTexture(GL_TEXTURE0);
    glBindBuffer(GL_ARRAY_BUFFER, 0);
    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);
    glDisable(GL_BLEND);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_STENCIL_TEST);
    glDisable(GL_CULL_FACE);
    glDisable(GL_SCISSOR_TEST);
    glDisable(GL_ALPHA_TEST);
    glDisable(GL_LIGHTING);
    glDisable(GL_FOG);
    glDisable(GL_COLOR_LOGIC_OP);
    glDisable(GL_POLYGON_OFFSET_FILL);
    glDisable(GL_TEXTURE_1D);
    glDisable(GL_TEXTURE_2D);
    glDisable(GL_TEXTURE_3D);
    glDisable(GL_TEXTURE_CUBE_MAP);
#ifdef GL_FRAMEBUFFER_SRGB
    glDisable(GL_FRAMEBUFFER_SRGB);
#endif
    glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
    glDepthMask(GL_TRUE);
    glPolygonMode(GL_FRONT_AND_BACK, GL_FILL);
    glShadeModel(GL_SMOOTH);
    glPixelStorei(GL_PACK_ALIGNMENT, 4);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
    glPixelStorei(GL_PACK_ROW_LENGTH, 0);
    glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
    glMatrixMode(GL_TEXTURE);
    glLoadIdentity();
    glMatrixMode(GL_PROJECTION);
    glLoadIdentity();
    glMatrixMode(GL_MODELVIEW);
    glLoadIdentity();
    glColor4f(1.0f, 1.0f, 1.0f, 1.0f);
    glBindTexture(GL_TEXTURE_2D, 0);
}

static void kgpu_texture_parameters() {
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
}

static void kgpu_draw_texture(
    KGpuContext *context,
    GLuint texture,
    float x,
    float y,
    float width,
    float height,
    bool cpu_top_down
) {
    if (!context || !texture || width <= 0 || height <= 0) return;
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glViewport(0, 0, context->width, context->height);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_CULL_FACE);
    glDisable(GL_SCISSOR_TEST);
    glEnable(GL_TEXTURE_2D);
    glEnable(GL_BLEND);
    glBlendEquation(GL_FUNC_ADD);
    glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
    glMatrixMode(GL_PROJECTION);
    glLoadIdentity();
    glOrtho(0.0, context->width, context->height, 0.0, -1.0, 1.0);
    glMatrixMode(GL_MODELVIEW);
    glLoadIdentity();
    glBindTexture(GL_TEXTURE_2D, texture);
    const float top_v = cpu_top_down ? 0.0f : 1.0f;
    const float bottom_v = cpu_top_down ? 1.0f : 0.0f;
    glColor4f(1.0f, 1.0f, 1.0f, 1.0f);
    glBegin(GL_QUADS);
    glTexCoord2f(0.0f, top_v); glVertex2f(x, y);
    glTexCoord2f(1.0f, top_v); glVertex2f(x + width, y);
    glTexCoord2f(1.0f, bottom_v); glVertex2f(x + width, y + height);
    glTexCoord2f(0.0f, bottom_v); glVertex2f(x, y + height);
    glEnd();
    glBindTexture(GL_TEXTURE_2D, 0);
}

void *kgpu_context_create(void *raw_window) {
    SDL_Window *window = static_cast<SDL_Window *>(raw_window);
    if (!window) return nullptr;
    KGpuContext *context = new KGpuContext();
    context->window = window;
    context->gl = SDL_GL_CreateContext(window);
    if (!context->gl) {
        delete context;
        return nullptr;
    }
    if (SDL_GL_MakeCurrent(window, context->gl) != 0) {
        SDL_GL_DeleteContext(context->gl);
        delete context;
        return nullptr;
    }
    SDL_GL_SetSwapInterval(1);
    const GLubyte *renderer = glGetString(GL_RENDERER);
    if (renderer) context->renderer = reinterpret_cast<const char *>(renderer);
    return context;
}

void kgpu_context_make_current(void *raw_context) {
    KGpuContext *context = static_cast<KGpuContext *>(raw_context);
    if (context) SDL_GL_MakeCurrent(context->window, context->gl);
}

void kgpu_context_destroy(void *raw_context) {
    KGpuContext *context = static_cast<KGpuContext *>(raw_context);
    if (!context) return;
    SDL_GL_MakeCurrent(context->window, context->gl);
    SDL_GL_DeleteContext(context->gl);
    delete context;
}

void kgpu_context_begin(void *raw_context, int width, int height) {
    KGpuContext *context = static_cast<KGpuContext *>(raw_context);
    if (!context) return;
    kgpu_context_make_current(context);
    kgpu_reset_compat_state();
    context->width = std::max(width, 1);
    context->height = std::max(height, 1);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glViewport(0, 0, context->width, context->height);
    glDisable(GL_SCISSOR_TEST);
    glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);
}

void *kgpu_texture_create(void) {
    return new KGpuTexture();
}

void kgpu_texture_destroy(void *raw_context, void *raw_texture) {
    KGpuContext *context = static_cast<KGpuContext *>(raw_context);
    KGpuTexture *texture = static_cast<KGpuTexture *>(raw_texture);
    if (!texture) return;
    if (context) kgpu_context_make_current(context);
    if (texture->texture) glDeleteTextures(1, &texture->texture);
    delete texture;
}

int kgpu_texture_upload(
    void *raw_context,
    void *raw_texture,
    const unsigned char *pixels,
    int width,
    int height,
    int stride,
    int damage_x,
    int damage_y,
    int damage_width,
    int damage_height
) {
    KGpuContext *context = static_cast<KGpuContext *>(raw_context);
    KGpuTexture *texture = static_cast<KGpuTexture *>(raw_texture);
    if (!context || !texture || !pixels || width <= 0 || height <= 0 || stride < width * 4) {
        return 0;
    }
    kgpu_context_make_current(context);
    if (!texture->texture) glGenTextures(1, &texture->texture);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, texture->texture);
    kgpu_texture_parameters();
    glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
    glPixelStorei(GL_UNPACK_ROW_LENGTH, stride / 4);

    int uploaded_bytes = 0;
    if (texture->width != width || texture->height != height) {
        glTexImage2D(
            GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_BGRA, GL_UNSIGNED_BYTE, pixels);
        texture->width = width;
        texture->height = height;
        uploaded_bytes = width * height * 4;
    } else if (damage_width > 0 && damage_height > 0) {
        const int x = std::max(0, std::min(damage_x, width));
        const int y = std::max(0, std::min(damage_y, height));
        const int region_width = std::max(0, std::min(damage_width, width - x));
        const int region_height = std::max(0, std::min(damage_height, height - y));
        if (region_width > 0 && region_height > 0) {
            const unsigned char *region = pixels + y * stride + x * 4;
            glTexSubImage2D(
                GL_TEXTURE_2D,
                0,
                x,
                y,
                region_width,
                region_height,
                GL_BGRA,
                GL_UNSIGNED_BYTE,
                region
            );
            uploaded_bytes = region_width * region_height * 4;
        }
    }

    glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
    glBindTexture(GL_TEXTURE_2D, 0);
    return uploaded_bytes;
}

int kgpu_texture_upload_region(
    void *raw_context,
    void *raw_texture,
    const unsigned char *pixels,
    int source_width,
    int source_height,
    int source_stride,
    int texture_width,
    int texture_height,
    int destination_x,
    int destination_y
) {
    KGpuContext *context = static_cast<KGpuContext *>(raw_context);
    KGpuTexture *texture = static_cast<KGpuTexture *>(raw_texture);
    if (!context || !texture || !pixels || source_width <= 0 || source_height <= 0 ||
        source_stride < source_width * 4 || texture_width <= 0 || texture_height <= 0) {
        return 0;
    }

    int source_x = 0;
    int source_y = 0;
    int upload_width = source_width;
    int upload_height = source_height;
    int target_x = destination_x;
    int target_y = destination_y;
    if (target_x < 0) {
        source_x = -target_x;
        upload_width -= source_x;
        target_x = 0;
    }
    if (target_y < 0) {
        source_y = -target_y;
        upload_height -= source_y;
        target_y = 0;
    }
    upload_width = std::min(upload_width, texture_width - target_x);
    upload_height = std::min(upload_height, texture_height - target_y);
    if (upload_width <= 0 || upload_height <= 0) return 0;

    kgpu_context_make_current(context);
    if (!texture->texture) glGenTextures(1, &texture->texture);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, texture->texture);
    kgpu_texture_parameters();
    if (texture->width != texture_width || texture->height != texture_height) {
        std::vector<unsigned char> transparent(
            static_cast<size_t>(texture_width) * static_cast<size_t>(texture_height) * 4u,
            0u
        );
        glTexImage2D(
            GL_TEXTURE_2D,
            0,
            GL_RGBA8,
            texture_width,
            texture_height,
            0,
            GL_BGRA,
            GL_UNSIGNED_BYTE,
            transparent.data()
        );
        texture->width = texture_width;
        texture->height = texture_height;
    }

    glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
    glPixelStorei(GL_UNPACK_ROW_LENGTH, source_stride / 4);
    const unsigned char *region = pixels + source_y * source_stride + source_x * 4;
    glTexSubImage2D(
        GL_TEXTURE_2D,
        0,
        target_x,
        target_y,
        upload_width,
        upload_height,
        GL_BGRA,
        GL_UNSIGNED_BYTE,
        region
    );
    glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
    glBindTexture(GL_TEXTURE_2D, 0);
    return upload_width * upload_height * 4;
}

void kgpu_texture_draw(
    void *raw_context,
    void *raw_texture,
    float x,
    float y,
    float width,
    float height,
    int cpu_top_down
) {
    KGpuContext *context = static_cast<KGpuContext *>(raw_context);
    KGpuTexture *texture = static_cast<KGpuTexture *>(raw_texture);
    if (!context || !texture) return;
    kgpu_draw_texture(
        context,
        texture->texture,
        x,
        y,
        width,
        height,
        cpu_top_down != 0
    );
}

void kgpu_context_present(void *raw_context) {
    KGpuContext *context = static_cast<KGpuContext *>(raw_context);
    if (context) SDL_GL_SwapWindow(context->window);
}

const char *kgpu_context_renderer(void *raw_context) {
    KGpuContext *context = static_cast<KGpuContext *>(raw_context);
    return context ? context->renderer : nullptr;
}

void *kgpu_layer_create(void) { return new KGpuLayer(); }

void kgpu_layer_destroy(void *raw_context, void *raw_layer) {
    KGpuContext *context = static_cast<KGpuContext *>(raw_context);
    KGpuLayer *layer = static_cast<KGpuLayer *>(raw_layer);
    if (!layer) return;
    if (context) kgpu_context_make_current(context);
    if (layer->framebuffer) glDeleteFramebuffers(1, &layer->framebuffer);
    if (layer->texture) glDeleteTextures(1, &layer->texture);
    delete layer;
}

int kgpu_layer_prepare(void *raw_context, void *raw_layer, int width, int height) {
    KGpuContext *context = static_cast<KGpuContext *>(raw_context);
    KGpuLayer *layer = static_cast<KGpuLayer *>(raw_layer);
    if (!context || !layer || width <= 0 || height <= 0) return 0;
    kgpu_context_make_current(context);
    kgpu_reset_compat_state();
    if (layer->width != width || layer->height != height) {
        if (!layer->texture) glGenTextures(1, &layer->texture);
        if (!layer->framebuffer) glGenFramebuffers(1, &layer->framebuffer);
        glBindTexture(GL_TEXTURE_2D, layer->texture);
        kgpu_texture_parameters();
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
        glBindFramebuffer(GL_FRAMEBUFFER, layer->framebuffer);
        glFramebufferTexture2D(
            GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, layer->texture, 0);
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            return 0;
        }
        layer->width = width;
        layer->height = height;
    } else {
        glBindFramebuffer(GL_FRAMEBUFFER, layer->framebuffer);
    }
    glViewport(0, 0, width, height);
    glDisable(GL_SCISSOR_TEST);
    glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);
    // mpv receives only the FBO name. Leave every other GL binding/state at compatibility defaults.
    glBindTexture(GL_TEXTURE_2D, 0);
    return 1;
}

void kgpu_layer_finish(void *raw_context) {
    KGpuContext *context = static_cast<KGpuContext *>(raw_context);
    if (!context) return;
    kgpu_reset_compat_state();
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glViewport(0, 0, context->width, context->height);
}

int kgpu_layer_framebuffer(void *raw_layer) {
    KGpuLayer *layer = static_cast<KGpuLayer *>(raw_layer);
    return layer ? static_cast<int>(layer->framebuffer) : 0;
}

void kgpu_layer_draw(
    void *raw_context,
    void *raw_layer,
    int x,
    int y,
    int width,
    int height
) {
    KGpuContext *context = static_cast<KGpuContext *>(raw_context);
    KGpuLayer *layer = static_cast<KGpuLayer *>(raw_layer);
    if (!context || !layer) return;
    kgpu_draw_texture(context, layer->texture, x, y, width, height, false);
}

void kgpu_layer_draw_mesh(
    void *raw_context,
    void *raw_layer,
    const float *positions,
    int columns,
    int rows,
    float alpha
) {
    KGpuContext *context = static_cast<KGpuContext *>(raw_context);
    KGpuLayer *layer = static_cast<KGpuLayer *>(raw_layer);
    if (!context || !layer || !layer->texture || !positions || columns <= 0 || rows <= 0) return;

    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glViewport(0, 0, context->width, context->height);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_CULL_FACE);
    glDisable(GL_SCISSOR_TEST);
    glEnable(GL_TEXTURE_2D);
    glEnable(GL_BLEND);
    glBlendEquation(GL_FUNC_ADD);
    glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
    glMatrixMode(GL_PROJECTION);
    glLoadIdentity();
    glOrtho(0.0, context->width, context->height, 0.0, -1.0, 1.0);
    glMatrixMode(GL_MODELVIEW);
    glLoadIdentity();
    glBindTexture(GL_TEXTURE_2D, layer->texture);
    glColor4f(alpha, alpha, alpha, alpha);

    const int row_stride = columns + 1;
    auto vertex = [&](int column, int row) {
        const int index = (row * row_stride + column) * 2;
        const float u = static_cast<float>(column) / columns;
        // OpenGL FBOs are bottom-up while Compose coordinates are top-down.
        const float v = 1.0f - static_cast<float>(row) / rows;
        glTexCoord2f(u, v);
        glVertex2f(positions[index], positions[index + 1]);
    };

    bool affine = true;
    const float origin_x = positions[0];
    const float origin_y = positions[1];
    const int top_right = columns * 2;
    const int bottom_left = rows * row_stride * 2;
    const float horizontal_x = positions[top_right] - origin_x;
    const float horizontal_y = positions[top_right + 1] - origin_y;
    const float vertical_x = positions[bottom_left] - origin_x;
    const float vertical_y = positions[bottom_left + 1] - origin_y;
    for (int row = 0; affine && row <= rows; ++row) {
        for (int column = 0; column <= columns; ++column) {
            const float u = static_cast<float>(column) / columns;
            const float v = static_cast<float>(row) / rows;
            const int index = (row * row_stride + column) * 2;
            const float expected_x = origin_x + horizontal_x * u + vertical_x * v;
            const float expected_y = origin_y + horizontal_y * u + vertical_y * v;
            if (std::fabs(positions[index] - expected_x) > 0.05f ||
                std::fabs(positions[index + 1] - expected_y) > 0.05f) {
                affine = false;
                break;
            }
        }
    }

    if (affine) {
        glBegin(GL_QUADS);
        vertex(0, 0);
        vertex(columns, 0);
        vertex(columns, rows);
        vertex(0, rows);
        glEnd();
        glColor4f(1.0f, 1.0f, 1.0f, 1.0f);
        glBindTexture(GL_TEXTURE_2D, 0);
        return;
    }

    // A grid follows Compose's perspective projection without requiring mpv's FBO to be copied.
    glBegin(GL_TRIANGLES);
    for (int row = 0; row < rows; ++row) {
        for (int column = 0; column < columns; ++column) {
            vertex(column, row);
            vertex(column + 1, row);
            vertex(column + 1, row + 1);
            vertex(column, row);
            vertex(column + 1, row + 1);
            vertex(column, row + 1);
        }
    }
    glEnd();
    glColor4f(1.0f, 1.0f, 1.0f, 1.0f);
    glBindTexture(GL_TEXTURE_2D, 0);
}

static cairo_surface_t *rgba_surface(const unsigned char *source, int width, int height, int source_stride, int channels) {
    cairo_surface_t *surface = cairo_image_surface_create(CAIRO_FORMAT_ARGB32, width, height);
    if (cairo_surface_status(surface) != CAIRO_STATUS_SUCCESS) {
        cairo_surface_destroy(surface);
        return nullptr;
    }
    unsigned char *destination = cairo_image_surface_get_data(surface);
    const int destination_stride = cairo_image_surface_get_stride(surface);
    for (int y = 0; y < height; ++y) {
        const unsigned char *src = source + y * source_stride;
        unsigned char *dst = destination + y * destination_stride;
        for (int x = 0; x < width; ++x) {
            const unsigned int alpha = channels == 4 ? src[x * channels + 3] : 255;
            dst[x * 4 + 0] = static_cast<unsigned char>((src[x * channels + 2] * alpha + 127) / 255);
            dst[x * 4 + 1] = static_cast<unsigned char>((src[x * channels + 1] * alpha + 127) / 255);
            dst[x * 4 + 2] = static_cast<unsigned char>((src[x * channels + 0] * alpha + 127) / 255);
            dst[x * 4 + 3] = static_cast<unsigned char>(alpha);
        }
    }
    cairo_surface_mark_dirty(surface);
    return surface;
}

struct PNGReader { const unsigned char *bytes; size_t length; size_t offset; };
static cairo_status_t read_png(void *closure, unsigned char *destination, unsigned int length) {
    PNGReader *reader = static_cast<PNGReader *>(closure);
    if (reader->offset + length > reader->length) return CAIRO_STATUS_READ_ERROR;
    std::memcpy(destination, reader->bytes + reader->offset, length);
    reader->offset += length;
    return CAIRO_STATUS_SUCCESS;
}

struct JPEGError { jpeg_error_mgr base; jmp_buf jump; };
static void jpeg_failure(j_common_ptr decoder) {
    longjmp(reinterpret_cast<JPEGError *>(decoder->err)->jump, 1);
}

static cairo_surface_t *decode_jpeg(const unsigned char *bytes, size_t length) {
    jpeg_decompress_struct decoder = {};
    JPEGError error = {};
    decoder.err = jpeg_std_error(&error.base);
    error.base.error_exit = jpeg_failure;
    if (setjmp(error.jump)) {
        jpeg_destroy_decompress(&decoder);
        return nullptr;
    }
    jpeg_create_decompress(&decoder);
    jpeg_mem_src(&decoder, bytes, length);
    jpeg_read_header(&decoder, TRUE);
    decoder.out_color_space = JCS_RGB;
    jpeg_start_decompress(&decoder);
    const int width = static_cast<int>(decoder.output_width);
    const int height = static_cast<int>(decoder.output_height);
    const int stride = width * 3;
    std::vector<unsigned char> pixels(stride * height);
    while (decoder.output_scanline < decoder.output_height) {
        unsigned char *row = pixels.data() + decoder.output_scanline * stride;
        jpeg_read_scanlines(&decoder, &row, 1);
    }
    jpeg_finish_decompress(&decoder);
    jpeg_destroy_decompress(&decoder);
    return rgba_surface(pixels.data(), width, height, stride, 3);
}

void *kc_surface_decode(const unsigned char *bytes, int length) {
    if (!bytes || length <= 0) return nullptr;
    if (length >= 8 && std::memcmp(bytes, "\x89PNG\r\n\x1a\n", 8) == 0) {
        PNGReader reader = {bytes, static_cast<size_t>(length), 0};
        cairo_surface_t *surface = cairo_image_surface_create_from_png_stream(read_png, &reader);
        if (cairo_surface_status(surface) == CAIRO_STATUS_SUCCESS) return surface;
        cairo_surface_destroy(surface);
        return nullptr;
    }
    if (length >= 2 && bytes[0] == 0xff && bytes[1] == 0xd8) return decode_jpeg(bytes, static_cast<size_t>(length));
    if (length >= 12 && std::memcmp(bytes, "RIFF", 4) == 0 && std::memcmp(bytes + 8, "WEBP", 4) == 0) {
        int width = 0, height = 0;
        if (!WebPGetInfo(bytes, static_cast<size_t>(length), &width, &height)) return nullptr;
        std::vector<unsigned char> pixels(width * height * 4);
        if (!WebPDecodeRGBAInto(bytes, static_cast<size_t>(length), pixels.data(), pixels.size(), width * 4)) return nullptr;
        return rgba_surface(pixels.data(), width, height, width * 4, 4);
    }
    return nullptr;
}

static int edge_index(int value, int size, int mode) {
    if (value >= 0 && value < size) return value;
    if (mode == 3) return -1; // Decal
    if (mode == 1) { // Repeated
        value %= size;
        return value < 0 ? value + size : value;
    }
    if (mode == 2) { // Mirror
        const int period = size * 2;
        value %= period;
        if (value < 0) value += period;
        return value < size ? value : period - value - 1;
    }
    return std::clamp(value, 0, size - 1); // Clamp
}

static void blur_pass(const std::vector<uint32_t> &source, std::vector<uint32_t> &destination,
                      int width, int height, int radius, bool horizontal, int edge_mode) {
    if (radius <= 0) {
        destination = source;
        return;
    }
    const int count = radius * 2 + 1;
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            unsigned int sums[4] = {0, 0, 0, 0};
            for (int delta = -radius; delta <= radius; ++delta) {
                const int sx = horizontal ? edge_index(x + delta, width, edge_mode) : x;
                const int sy = horizontal ? y : edge_index(y + delta, height, edge_mode);
                if (sx < 0 || sy < 0) continue;
                const uint32_t pixel = source[sy * width + sx];
                sums[0] += pixel & 255;
                sums[1] += (pixel >> 8) & 255;
                sums[2] += (pixel >> 16) & 255;
                sums[3] += pixel >> 24;
            }
            destination[y * width + x] =
                (sums[0] / count) |
                ((sums[1] / count) << 8) |
                ((sums[2] / count) << 16) |
                ((sums[3] / count) << 24);
        }
    }
}

void kc_surface_blur(void *raw_surface, double radius_x, double radius_y, int edge_mode) {
    cairo_surface_t *surface = static_cast<cairo_surface_t *>(raw_surface);
    if (!surface || cairo_surface_get_type(surface) != CAIRO_SURFACE_TYPE_IMAGE) return;
    cairo_surface_flush(surface);
    const int width = cairo_image_surface_get_width(surface);
    const int height = cairo_image_surface_get_height(surface);
    const int stride = cairo_image_surface_get_stride(surface);
    unsigned char *data = cairo_image_surface_get_data(surface);
    std::vector<uint32_t> input(width * height), temp(width * height), output(width * height);
    for (int y = 0; y < height; ++y) std::memcpy(input.data() + y * width, data + y * stride, width * 4);
    // Three box passes closely approximate a Gaussian while keeping this backend compact.
    const int rx = std::max(0, static_cast<int>(std::ceil(radius_x * 0.57735)));
    const int ry = std::max(0, static_cast<int>(std::ceil(radius_y * 0.57735)));
    for (int pass = 0; pass < 3; ++pass) {
        blur_pass(input, temp, width, height, rx, true, edge_mode);
        blur_pass(temp, output, width, height, ry, false, edge_mode);
        input.swap(output);
    }
    for (int y = 0; y < height; ++y) std::memcpy(data + y * stride, input.data() + y * width, width * 4);
    cairo_surface_mark_dirty(surface);
}

void kc_surface_offset(void *raw_surface, int dx, int dy) {
    cairo_surface_t *surface = static_cast<cairo_surface_t *>(raw_surface);
    if (!surface || cairo_surface_get_type(surface) != CAIRO_SURFACE_TYPE_IMAGE) return;
    cairo_surface_flush(surface);
    const int width = cairo_image_surface_get_width(surface);
    const int height = cairo_image_surface_get_height(surface);
    const int stride = cairo_image_surface_get_stride(surface);
    unsigned char *data = cairo_image_surface_get_data(surface);
    std::vector<unsigned char> source(stride * height);
    std::memcpy(source.data(), data, source.size());
    std::memset(data, 0, source.size());
    for (int y = 0; y < height; ++y) {
        const int source_y = y - dy;
        if (source_y < 0 || source_y >= height) continue;
        for (int x = 0; x < width; ++x) {
            const int source_x = x - dx;
            if (source_x < 0 || source_x >= width) continue;
            std::memcpy(data + y * stride + x * 4, source.data() + source_y * stride + source_x * 4, 4);
        }
    }
    cairo_surface_mark_dirty(surface);
}

static cairo_surface_t *group_surface(cairo_pattern_t *pattern) {
    cairo_surface_t *surface = nullptr;
    if (cairo_pattern_get_surface(pattern, &surface) != CAIRO_STATUS_SUCCESS) return nullptr;
    return surface && cairo_surface_get_type(surface) == CAIRO_SURFACE_TYPE_IMAGE ? surface : nullptr;
}

static uint8_t clamp_byte(float value) {
    return static_cast<uint8_t>(std::clamp(std::lround(value), 0L, 255L));
}

void kc_pop_group_color_matrix_source(void *raw_cr, const float *matrix) {
    cairo_t *cr = static_cast<cairo_t *>(raw_cr);
    cairo_pattern_t *pattern = cairo_pop_group(cr);
    cairo_surface_t *surface = group_surface(pattern);
    if (surface && matrix) {
        cairo_surface_flush(surface);
        unsigned char *data = cairo_image_surface_get_data(surface);
        const int width = cairo_image_surface_get_width(surface);
        const int height = cairo_image_surface_get_height(surface);
        const int stride = cairo_image_surface_get_stride(surface);
        for (int y = 0; y < height; ++y) {
            uint32_t *row = reinterpret_cast<uint32_t *>(data + y * stride);
            for (int x = 0; x < width; ++x) {
                const uint32_t pixel = row[x];
                const float alpha = static_cast<float>((pixel >> 24) & 0xffu);
                const float unpremultiply = alpha > 0.0f ? 255.0f / alpha : 0.0f;
                const float red = static_cast<float>((pixel >> 16) & 0xffu) * unpremultiply;
                const float green = static_cast<float>((pixel >> 8) & 0xffu) * unpremultiply;
                const float blue = static_cast<float>(pixel & 0xffu) * unpremultiply;
                const float out_red = matrix[0] * red + matrix[1] * green +
                    matrix[2] * blue + matrix[3] * alpha + matrix[4];
                const float out_green = matrix[5] * red + matrix[6] * green +
                    matrix[7] * blue + matrix[8] * alpha + matrix[9];
                const float out_blue = matrix[10] * red + matrix[11] * green +
                    matrix[12] * blue + matrix[13] * alpha + matrix[14];
                const uint8_t out_alpha = clamp_byte(
                    matrix[15] * red + matrix[16] * green + matrix[17] * blue +
                    matrix[18] * alpha + matrix[19]);
                const float premultiply = static_cast<float>(out_alpha) / 255.0f;
                row[x] = (static_cast<uint32_t>(out_alpha) << 24) |
                    (static_cast<uint32_t>(clamp_byte(out_red * premultiply)) << 16) |
                    (static_cast<uint32_t>(clamp_byte(out_green * premultiply)) << 8) |
                    static_cast<uint32_t>(clamp_byte(out_blue * premultiply));
            }
        }
        cairo_surface_mark_dirty(surface);
    }
    cairo_set_source(cr, pattern);
    cairo_pattern_destroy(pattern);
}

void kc_pop_group_tint_source(
    void *raw_cr, double red, double green, double blue, double alpha, int blend_operator) {
    cairo_t *cr = static_cast<cairo_t *>(raw_cr);
    cairo_pattern_t *original = cairo_pop_group(cr);
    cairo_push_group(cr);
    cairo_set_operator(cr, CAIRO_OPERATOR_SOURCE);
    cairo_set_source(cr, original);
    cairo_paint(cr);
    cairo_set_operator(cr, static_cast<cairo_operator_t>(blend_operator));
    cairo_set_source_rgba(cr, red, green, blue, alpha);
    cairo_mask(cr, original);
    cairo_pattern_destroy(original);
    cairo_pop_group_to_source(cr);
}

void kc_pop_group_blur_source(void *raw_cr, double radius_x, double radius_y, int edge_mode) {
    cairo_t *cr = static_cast<cairo_t *>(raw_cr);
    cairo_pattern_t *pattern = cairo_pop_group(cr);
    cairo_surface_t *surface = group_surface(pattern);
    if (surface) kc_surface_blur(surface, radius_x, radius_y, edge_mode);
    cairo_set_source(cr, pattern);
    cairo_pattern_destroy(pattern);
}

char *kp_font_register(const unsigned char *bytes, int length) {
    if (!bytes || length <= 0) return nullptr;
#if defined(__linux__)
    static std::vector<FILE *> registered_font_files;
    FILE *file = std::tmpfile();
    if (!file) return nullptr;
    if (std::fwrite(bytes, 1, static_cast<size_t>(length), file) != static_cast<size_t>(length) ||
        std::fflush(file) != 0) {
        std::fclose(file);
        return nullptr;
    }
    char path[64];
    std::snprintf(path, sizeof(path), "/proc/self/fd/%d", fileno(file));
    FcConfig *config = FcConfigGetCurrent();
    if (!config || !FcConfigAppFontAddFile(config, reinterpret_cast<const FcChar8 *>(path))) {
        std::fclose(file);
        return nullptr;
    }
    FcConfigBuildFonts(config);
    int face_count = 0;
    FcPattern *pattern = FcFreeTypeQuery(
        reinterpret_cast<const FcChar8 *>(path), 0, nullptr, &face_count);
    if (!pattern) {
        std::fclose(file);
        return nullptr;
    }
    FcChar8 *family = nullptr;
    char *result = nullptr;
    if (FcPatternGetString(pattern, FC_FAMILY, 0, &family) == FcResultMatch && family) {
        result = ::strdup(reinterpret_cast<const char *>(family));
    }
    FcPatternDestroy(pattern);
    // Fontconfig/Pango may reopen the procfs path later, so successful registrations retain it.
    if (result) registered_font_files.push_back(file); else std::fclose(file);
    return result;
#else
    return nullptr;
#endif
}

void kp_string_free(char *value) { std::free(value); }

unsigned char *kp_test_font_data(int *length) {
    if (length) *length = 0;
    FcPattern *request = FcPatternCreate();
    FcPatternAddString(request, FC_FAMILY, reinterpret_cast<const FcChar8 *>("sans-serif"));
    FcConfigSubstitute(nullptr, request, FcMatchPattern);
    FcDefaultSubstitute(request);
    FcResult match_result = FcResultNoMatch;
    FcPattern *match = FcFontMatch(nullptr, request, &match_result);
    FcPatternDestroy(request);
    if (!match) return nullptr;
    FcChar8 *path = nullptr;
    if (FcPatternGetString(match, FC_FILE, 0, &path) != FcResultMatch || !path) {
        FcPatternDestroy(match);
        return nullptr;
    }
    FILE *file = std::fopen(reinterpret_cast<const char *>(path), "rb");
    FcPatternDestroy(match);
    if (!file || std::fseek(file, 0, SEEK_END) != 0) {
        if (file) std::fclose(file);
        return nullptr;
    }
    const long size = std::ftell(file);
    if (size <= 0 || size > 32 * 1024 * 1024 || std::fseek(file, 0, SEEK_SET) != 0) {
        std::fclose(file);
        return nullptr;
    }
    unsigned char *bytes = static_cast<unsigned char *>(std::malloc(static_cast<size_t>(size)));
    if (!bytes || std::fread(bytes, 1, static_cast<size_t>(size), file) != static_cast<size_t>(size)) {
        std::free(bytes);
        std::fclose(file);
        return nullptr;
    }
    std::fclose(file);
    if (length) *length = static_cast<int>(size);
    return bytes;
}

void kp_bytes_free(unsigned char *value) { std::free(value); }

struct KGCommand { int type; double values[6]; };
struct KGPath {
    Clipper2Lib::Paths64 paths;
    Clipper2Lib::Path64 current;
    std::vector<KGCommand> commands;
    double last_x = 0;
    double last_y = 0;
    bool started = false;
};

static constexpr double path_scale = 10000.0;
static Clipper2Lib::Point64 path_point(double x, double y) {
    return Clipper2Lib::Point64(static_cast<int64_t>(std::llround(x * path_scale)), static_cast<int64_t>(std::llround(y * path_scale)));
}

static void finish_path(KGPath *path, bool close) {
    if (!path->started) return;
    if (path->current.size() >= 3) path->paths.push_back(path->current);
    path->current.clear();
    path->started = false;
}

void *kg_path_create(void) { return new KGPath(); }
void kg_path_destroy(void *raw) { delete static_cast<KGPath *>(raw); }
void kg_path_move_to(void *raw, double x, double y) {
    KGPath *path = static_cast<KGPath *>(raw);
    finish_path(path, true);
    path->current.push_back(path_point(x, y));
    path->last_x = x; path->last_y = y; path->started = true;
}
void kg_path_line_to(void *raw, double x, double y) {
    KGPath *path = static_cast<KGPath *>(raw);
    if (!path->started) kg_path_move_to(raw, x, y);
    else path->current.push_back(path_point(x, y));
    path->last_x = x; path->last_y = y;
}

static double point_line_distance(double x, double y, double ax, double ay, double bx, double by) {
    const double dx = bx - ax, dy = by - ay;
    const double length = std::hypot(dx, dy);
    return length < 1e-12 ? std::hypot(x - ax, y - ay) : std::abs(dy * x - dx * y + bx * ay - by * ax) / length;
}

static void flatten_cubic(KGPath *path,
    double x0, double y0, double x1, double y1, double x2, double y2, double x3, double y3, int depth) {
    if (depth >= 12 || std::max(point_line_distance(x1, y1, x0, y0, x3, y3), point_line_distance(x2, y2, x0, y0, x3, y3)) <= 0.05) {
        path->current.push_back(path_point(x3, y3));
        return;
    }
    const double x01 = (x0 + x1) * 0.5, y01 = (y0 + y1) * 0.5;
    const double x12 = (x1 + x2) * 0.5, y12 = (y1 + y2) * 0.5;
    const double x23 = (x2 + x3) * 0.5, y23 = (y2 + y3) * 0.5;
    const double x012 = (x01 + x12) * 0.5, y012 = (y01 + y12) * 0.5;
    const double x123 = (x12 + x23) * 0.5, y123 = (y12 + y23) * 0.5;
    const double xm = (x012 + x123) * 0.5, ym = (y012 + y123) * 0.5;
    flatten_cubic(path, x0, y0, x01, y01, x012, y012, xm, ym, depth + 1);
    flatten_cubic(path, xm, ym, x123, y123, x23, y23, x3, y3, depth + 1);
}

void kg_path_curve_to(void *raw, double x1, double y1, double x2, double y2, double x3, double y3) {
    KGPath *path = static_cast<KGPath *>(raw);
    if (!path->started) kg_path_move_to(raw, x3, y3);
    else flatten_cubic(path, path->last_x, path->last_y, x1, y1, x2, y2, x3, y3, 0);
    path->last_x = x3; path->last_y = y3;
}
void kg_path_close(void *raw) { finish_path(static_cast<KGPath *>(raw), true); }

void *kg_path_op(void *a_raw, void *b_raw, int operation, int even_odd) {
    KGPath *a = static_cast<KGPath *>(a_raw), *b = static_cast<KGPath *>(b_raw);
    finish_path(a, true); finish_path(b, true);
    try {
        Clipper2Lib::Clipper64 clipper;
        Clipper2Lib::ClipType clip_type;
        if (operation == 4) {
            clipper.AddSubject(b->paths); clipper.AddClip(a->paths);
            clip_type = Clipper2Lib::ClipType::Difference;
        } else {
            clipper.AddSubject(a->paths); clipper.AddClip(b->paths);
            switch (operation) {
                case 0: clip_type = Clipper2Lib::ClipType::Difference; break;
                case 1: clip_type = Clipper2Lib::ClipType::Intersection; break;
                case 2: clip_type = Clipper2Lib::ClipType::Union; break;
                case 3: clip_type = Clipper2Lib::ClipType::Xor; break;
                default: return nullptr;
            }
        }
        Clipper2Lib::Paths64 result;
        if (!clipper.Execute(clip_type, even_odd ? Clipper2Lib::FillRule::EvenOdd : Clipper2Lib::FillRule::NonZero, result)) return nullptr;
        KGPath *output = new KGPath();
        output->paths = result;
        for (const auto &contour : result) {
            if (contour.empty()) continue;
            KGCommand move = {0, {contour[0].x / path_scale, contour[0].y / path_scale, 0, 0, 0, 0}};
            output->commands.push_back(move);
            for (size_t index = 1; index < contour.size(); ++index) {
                KGCommand line = {1, {contour[index].x / path_scale, contour[index].y / path_scale, 0, 0, 0, 0}};
                output->commands.push_back(line);
            }
            output->commands.push_back({3, {0, 0, 0, 0, 0, 0}});
        }
        return output;
    } catch (...) {
        return nullptr;
    }
}
int kg_path_command_count(void *raw) { return static_cast<int>(static_cast<KGPath *>(raw)->commands.size()); }
int kg_path_command_type(void *raw, int index) { return static_cast<KGPath *>(raw)->commands.at(index).type; }
double kg_path_command_value(void *raw, int index, int value) { return static_cast<KGPath *>(raw)->commands.at(index).values[value]; }

} // extern "C"
