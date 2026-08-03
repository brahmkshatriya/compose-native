#ifndef KTNATIVE_CAIRO_COMPOSE_H
#define KTNATIVE_CAIRO_COMPOSE_H

#include <cairo.h>
#include <pango/pangocairo.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

static inline void *kc_surface_create(int width, int height) {
    return cairo_image_surface_create(CAIRO_FORMAT_ARGB32, width, height);
}
static inline void kc_surface_destroy(void *surface) { cairo_surface_destroy(surface); }
static inline void kc_surface_flush(void *surface) { cairo_surface_flush(surface); }
static inline void kc_surface_dirty(void *surface) { cairo_surface_mark_dirty(surface); }
static inline unsigned char *kc_surface_data(void *surface) {
    cairo_surface_flush(surface);
    return cairo_image_surface_get_data(surface);
}
static inline int kc_surface_stride(void *surface) { return cairo_image_surface_get_stride(surface); }
static inline int kc_surface_width(void *surface) { return cairo_image_surface_get_width(surface); }
static inline int kc_surface_height(void *surface) { return cairo_image_surface_get_height(surface); }
static inline int kc_surface_status(void *surface) { return cairo_surface_status(surface); }

/* Implemented in native_support.cpp. */
void *kc_surface_decode(const unsigned char *bytes, int length);
void kc_surface_blur(void *surface, double radius_x, double radius_y, int edge_mode);
void kc_surface_offset(void *surface, int dx, int dy);
void kc_pop_group_color_matrix_source(void *cr, const float *matrix);
void kc_pop_group_tint_source(
    void *cr, double r, double g, double b, double a, int blend_operator);
void kc_pop_group_blur_source(void *cr, double radius_x, double radius_y, int edge_mode);
char *kp_font_register(const unsigned char *bytes, int length);
void kp_string_free(char *value);
unsigned char *kp_test_font_data(int *length);
void kp_bytes_free(unsigned char *value);

/* SDL/OpenGL compositor used by GPU-backed native interop views. */
void *kgpu_context_create(void *sdl_window);
void kgpu_context_make_current(void *context);
void kgpu_context_destroy(void *context);
void kgpu_context_begin(void *context, int width, int height);
void kgpu_context_draw_compose(
    void *context, const unsigned char *pixels, int width, int height, int stride);
void kgpu_context_present(void *context);
const char *kgpu_context_renderer(void *context);
void *kgpu_layer_create(void);
void kgpu_layer_destroy(void *context, void *layer);
int kgpu_layer_prepare(void *context, void *layer, int width, int height);
void kgpu_layer_finish(void *context);
int kgpu_layer_framebuffer(void *layer);
void kgpu_layer_draw(void *context, void *layer, int x, int y, int width, int height);
void kgpu_layer_draw_mesh(
    void *context,
    void *layer,
    const float *positions,
    int columns,
    int rows,
    float alpha);

void *kg_path_create(void);
void kg_path_destroy(void *path);
void kg_path_move_to(void *path, double x, double y);
void kg_path_line_to(void *path, double x, double y);
void kg_path_curve_to(void *path, double x1, double y1, double x2, double y2, double x3, double y3);
void kg_path_close(void *path);
void *kg_path_op(void *a, void *b, int operation, int even_odd);
int kg_path_command_count(void *path);
int kg_path_command_type(void *path, int index);
double kg_path_command_value(void *path, int index, int value);

static inline void *kc_create(void *surface) { return cairo_create(surface); }
static inline void kc_destroy(void *cr) { cairo_destroy(cr); }
static inline int kc_status(void *cr) { return cairo_status(cr); }
static inline void kc_save(void *cr) { cairo_save(cr); }
static inline void kc_restore(void *cr) { cairo_restore(cr); }
static inline void kc_push_group(void *cr) { cairo_push_group(cr); }
static inline void kc_pop_group_source(void *cr) { cairo_pop_group_to_source(cr); }
static inline void *kc_pop_group(void *cr) { return cairo_pop_group(cr); }
static inline void kc_mask(void *cr, void *pattern) { cairo_mask(cr, pattern); }
static inline void kc_pop_group_blur(void *cr, double radius_x, double radius_y, int edge_mode) {
    cairo_pattern_t *pattern = cairo_pop_group(cr);
    cairo_surface_t *surface = NULL;
    if (cairo_pattern_get_surface(pattern, &surface) == CAIRO_STATUS_SUCCESS && surface) {
        kc_surface_blur(surface, radius_x, radius_y, edge_mode);
    }
    cairo_set_source(cr, pattern);
    cairo_pattern_destroy(pattern);
    cairo_paint(cr);
}
static inline void kc_paint(void *cr) { cairo_paint(cr); }
static inline void kc_paint_alpha(void *cr, double alpha) { cairo_paint_with_alpha(cr, alpha); }
static inline void kc_translate(void *cr, double x, double y) { cairo_translate(cr, x, y); }
static inline void kc_scale(void *cr, double x, double y) { cairo_scale(cr, x, y); }
static inline void kc_rotate(void *cr, double radians) { cairo_rotate(cr, radians); }
static inline void kc_identity_matrix(void *cr) { cairo_identity_matrix(cr); }
static inline void kc_transform(void *cr, double xx, double yx, double xy, double yy, double x0, double y0) {
    cairo_matrix_t m = {xx, yx, xy, yy, x0, y0};
    cairo_transform(cr, &m);
}
static inline void kc_new_path(void *cr) { cairo_new_path(cr); }
static inline void kc_new_sub_path(void *cr) { cairo_new_sub_path(cr); }
static inline void kc_move_to(void *cr, double x, double y) { cairo_move_to(cr, x, y); }
static inline void kc_rel_move_to(void *cr, double x, double y) { cairo_rel_move_to(cr, x, y); }
static inline void kc_line_to(void *cr, double x, double y) { cairo_line_to(cr, x, y); }
static inline void kc_rel_line_to(void *cr, double x, double y) { cairo_rel_line_to(cr, x, y); }
static inline void kc_curve_to(void *cr, double x1, double y1, double x2, double y2, double x3, double y3) {
    cairo_curve_to(cr, x1, y1, x2, y2, x3, y3);
}
static inline void kc_rel_curve_to(void *cr, double x1, double y1, double x2, double y2, double x3, double y3) {
    cairo_rel_curve_to(cr, x1, y1, x2, y2, x3, y3);
}
static inline void kc_close_path(void *cr) { cairo_close_path(cr); }
static inline void kc_rectangle(void *cr, double x, double y, double w, double h) { cairo_rectangle(cr, x, y, w, h); }
static inline void kc_arc(void *cr, double x, double y, double radius, double a1, double a2) { cairo_arc(cr, x, y, radius, a1, a2); }
static inline void kc_arc_negative(void *cr, double x, double y, double radius, double a1, double a2) { cairo_arc_negative(cr, x, y, radius, a1, a2); }
static inline void kc_clip(void *cr) { cairo_clip(cr); }
static inline void kc_clip_preserve(void *cr) { cairo_clip_preserve(cr); }
static inline void kc_set_antialias_enabled(void *cr, int enabled) {
    cairo_set_antialias(cr, enabled ? CAIRO_ANTIALIAS_DEFAULT : CAIRO_ANTIALIAS_NONE);
}
static inline int kc_clip_difference_begin(void *cr) {
    double x1, y1, x2, y2;
    int was_even_odd = cairo_get_fill_rule(cr) == CAIRO_FILL_RULE_EVEN_ODD;
    cairo_clip_extents(cr, &x1, &y1, &x2, &y2);
    cairo_new_path(cr);
    cairo_rectangle(cr, x1, y1, x2 - x1, y2 - y1);
    cairo_set_fill_rule(cr, CAIRO_FILL_RULE_EVEN_ODD);
    return was_even_odd;
}
static inline void kc_clip_difference_end(void *cr, int was_even_odd) {
    cairo_clip(cr);
    cairo_set_fill_rule(
        cr, was_even_odd ? CAIRO_FILL_RULE_EVEN_ODD : CAIRO_FILL_RULE_WINDING);
}
static inline void kc_clip_difference_rect(
    void *cr, double x, double y, double width, double height) {
    int was_even_odd = kc_clip_difference_begin(cr);
    cairo_rectangle(cr, x, y, width, height);
    kc_clip_difference_end(cr, was_even_odd);
}
static inline void kc_set_fill_rule(void *cr, int even_odd) {
    cairo_set_fill_rule(cr, even_odd ? CAIRO_FILL_RULE_EVEN_ODD : CAIRO_FILL_RULE_WINDING);
}
static inline void kc_set_source_rgba(void *cr, double r, double g, double b, double a) { cairo_set_source_rgba(cr, r, g, b, a); }
static inline void kc_set_source(void *cr, void *pattern) { cairo_set_source(cr, pattern); }
static inline void kc_set_source_surface(void *cr, void *surface, double x, double y) { cairo_set_source_surface(cr, surface, x, y); }
static inline void kc_set_line_width(void *cr, double width) { cairo_set_line_width(cr, width); }
static inline void kc_set_line_cap(void *cr, int cap) { cairo_set_line_cap(cr, (cairo_line_cap_t)cap); }
static inline void kc_set_line_join(void *cr, int join) { cairo_set_line_join(cr, (cairo_line_join_t)join); }
static inline void kc_set_miter_limit(void *cr, double limit) { cairo_set_miter_limit(cr, limit); }
static inline void kc_set_dash(void *cr, const double *values, int count, double phase) { cairo_set_dash(cr, values, count, phase); }
static inline void kc_set_operator(void *cr, int op) { cairo_set_operator(cr, (cairo_operator_t)op); }
static inline void kc_fill(void *cr) { cairo_fill(cr); }
static inline void kc_fill_preserve(void *cr) { cairo_fill_preserve(cr); }
static inline void kc_stroke(void *cr) { cairo_stroke(cr); }
static inline void kc_stroke_preserve(void *cr) { cairo_stroke_preserve(cr); }

static inline void *kc_pattern_linear(double x0, double y0, double x1, double y1) {
    return cairo_pattern_create_linear(x0, y0, x1, y1);
}
static inline void *kc_pattern_radial(double x0, double y0, double r0, double x1, double y1, double r1) {
    return cairo_pattern_create_radial(x0, y0, r0, x1, y1, r1);
}
static inline void *kc_pattern_surface(void *surface) { return cairo_pattern_create_for_surface(surface); }
static inline void *kc_pattern_reference(void *pattern) { return cairo_pattern_reference(pattern); }
static inline void kc_pattern_destroy(void *pattern) { cairo_pattern_destroy(pattern); }
static inline void *kc_pattern_mesh(void) { return cairo_pattern_create_mesh(); }
static inline void kc_mesh_begin(void *pattern) { cairo_mesh_pattern_begin_patch(pattern); }
static inline void kc_mesh_move_to(void *pattern, double x, double y) { cairo_mesh_pattern_move_to(pattern, x, y); }
static inline void kc_mesh_line_to(void *pattern, double x, double y) { cairo_mesh_pattern_line_to(pattern, x, y); }
static inline void kc_mesh_end(void *pattern) { cairo_mesh_pattern_end_patch(pattern); }
static inline void kc_mesh_color(
    void *pattern, unsigned int corner, double r, double g, double b, double a) {
    cairo_mesh_pattern_set_corner_color_rgba(pattern, corner, r, g, b, a);
}
static inline void kc_pattern_color_stop(void *pattern, double offset, double r, double g, double b, double a) {
    cairo_pattern_add_color_stop_rgba(pattern, offset, r, g, b, a);
}
static inline void kc_pattern_extend(void *pattern, int extend) { cairo_pattern_set_extend(pattern, (cairo_extend_t)extend); }
static inline void kc_pattern_matrix(void *pattern, double xx, double yx, double xy, double yy, double x0, double y0) {
    cairo_matrix_t m = {xx, yx, xy, yy, x0, y0};
    cairo_matrix_invert(&m);
    cairo_pattern_set_matrix(pattern, &m);
}

static inline void *kp_layout_create(void) {
    PangoFontMap *map = pango_cairo_font_map_get_default();
    PangoContext *context = pango_font_map_create_context(map);
    PangoLayout *layout = pango_layout_new(context);
    g_object_unref(context);
    return layout;
}
static inline void kp_layout_destroy(void *layout) { g_object_unref(layout); }
static inline void kp_layout_text(void *layout, const char *text) { pango_layout_set_text(layout, text, -1); }
static inline void kp_layout_font(void *layout, const char *family, double pixels, int weight, int italic) {
    PangoFontDescription *font = pango_font_description_new();
    pango_font_description_set_family(font, family);
    pango_font_description_set_absolute_size(font, pixels * PANGO_SCALE);
    pango_font_description_set_weight(font, (PangoWeight)weight);
    pango_font_description_set_style(font, italic ? PANGO_STYLE_ITALIC : PANGO_STYLE_NORMAL);
    pango_layout_set_font_description(layout, font);
    pango_font_description_free(font);
}
static inline void kp_layout_width(void *layout, int pixels) {
    pango_layout_set_width(layout, pixels < 0 ? -1 : pixels * PANGO_SCALE);
}
static inline void kp_layout_wrap(void *layout) { pango_layout_set_wrap(layout, PANGO_WRAP_WORD_CHAR); }
static inline void kp_layout_wrap_words(void *layout) { pango_layout_set_wrap(layout, PANGO_WRAP_WORD); }
static inline void kp_layout_ellipsize(void *layout, int enabled) {
    pango_layout_set_ellipsize(layout, enabled ? PANGO_ELLIPSIZE_END : PANGO_ELLIPSIZE_NONE);
}
static inline int kp_layout_is_ellipsized(void *layout) { return pango_layout_is_ellipsized(layout); }
static inline void kp_layout_lines(void *layout, int lines) { pango_layout_set_height(layout, lines > 0 ? -lines : -1); }
static inline void kp_layout_alignment(void *layout, int alignment) { pango_layout_set_alignment(layout, (PangoAlignment)alignment); }
static inline void kp_layout_letter_spacing(void *layout, double pixels) {
    PangoAttrList *attrs = pango_attr_list_new();
    PangoAttribute *spacing = pango_attr_letter_spacing_new((int)(pixels * PANGO_SCALE));
    pango_attr_list_insert(attrs, spacing);
    pango_layout_set_attributes(layout, attrs);
    pango_attr_list_unref(attrs);
}
static inline void *kp_attrs_create(void) { return pango_attr_list_new(); }
static inline void kp_attrs_destroy(void *attrs) { pango_attr_list_unref((PangoAttrList *)attrs); }
static inline void kp_attrs_set(void *layout, void *attrs) {
    pango_layout_set_attributes((PangoLayout *)layout, (PangoAttrList *)attrs);
}
static inline void kp_attr_insert(void *attrs, PangoAttribute *attr, int start, int end) {
    attr->start_index = start;
    attr->end_index = end;
    pango_attr_list_change((PangoAttrList *)attrs, attr);
}
static inline void kp_attrs_foreground(void *attrs, int start, int end, double r, double g, double b, double a) {
    kp_attr_insert(attrs, pango_attr_foreground_new((guint16)(r * 65535.0), (guint16)(g * 65535.0), (guint16)(b * 65535.0)), start, end);
    kp_attr_insert(attrs, pango_attr_foreground_alpha_new((guint16)(a * 65535.0)), start, end);
}
static inline void kp_attrs_foreground_alpha(void *attrs, int start, int end, double a) {
    kp_attr_insert(attrs, pango_attr_foreground_alpha_new((guint16)(a * 65535.0)), start, end);
}
static inline void kp_attrs_background(void *attrs, int start, int end, double r, double g, double b, double a) {
    kp_attr_insert(attrs, pango_attr_background_new((guint16)(r * 65535.0), (guint16)(g * 65535.0), (guint16)(b * 65535.0)), start, end);
    kp_attr_insert(attrs, pango_attr_background_alpha_new((guint16)(a * 65535.0)), start, end);
}
static inline void kp_attrs_size(void *attrs, int start, int end, double pixels) {
    kp_attr_insert(attrs, pango_attr_size_new_absolute((int)(pixels * PANGO_SCALE)), start, end);
}
static inline void kp_attrs_weight(void *attrs, int start, int end, int weight) {
    kp_attr_insert(attrs, pango_attr_weight_new((PangoWeight)weight), start, end);
}
static inline void kp_attrs_style(void *attrs, int start, int end, int italic) {
    kp_attr_insert(attrs, pango_attr_style_new(italic ? PANGO_STYLE_ITALIC : PANGO_STYLE_NORMAL), start, end);
}
static inline void kp_attrs_family(void *attrs, int start, int end, const char *family) {
    kp_attr_insert(attrs, pango_attr_family_new(family), start, end);
}
static inline void kp_attrs_features(void *attrs, int start, int end, const char *features) {
    kp_attr_insert(attrs, pango_attr_font_features_new(features), start, end);
}
static inline void kp_attrs_language(void *attrs, int start, int end, const char *language) {
    kp_attr_insert(attrs, pango_attr_language_new(pango_language_from_string(language)), start, end);
}
static inline void kp_attrs_letter_spacing(void *attrs, int start, int end, double pixels) {
    kp_attr_insert(attrs, pango_attr_letter_spacing_new((int)(pixels * PANGO_SCALE)), start, end);
}
static inline void kp_attrs_rise(void *attrs, int start, int end, double pixels) {
    kp_attr_insert(attrs, pango_attr_rise_new((int)(pixels * PANGO_SCALE)), start, end);
}
static inline void kp_attrs_scale(void *attrs, int start, int end, double scale) {
    kp_attr_insert(attrs, pango_attr_scale_new(scale), start, end);
}
static inline void kp_attrs_decoration(void *attrs, int start, int end, int underline, int strike) {
    if (underline) kp_attr_insert(attrs, pango_attr_underline_new(PANGO_UNDERLINE_SINGLE), start, end);
    if (strike) kp_attr_insert(attrs, pango_attr_strikethrough_new(TRUE), start, end);
}
static inline void kp_attrs_shape(void *attrs, int start, int end, double width, double height, double y) {
    PangoRectangle ink = {0, 0, 0, 0};
    PangoRectangle logical = {0, (int)(y * PANGO_SCALE), (int)(width * PANGO_SCALE), (int)(height * PANGO_SCALE)};
    kp_attr_insert(attrs, pango_attr_shape_new(&ink, &logical), start, end);
}
static inline int kp_layout_width_px(void *layout) { int w, h; pango_layout_get_pixel_size(layout, &w, &h); return w; }
static inline double kp_layout_width_exact(void *layout) {
    int w, h;
    pango_layout_get_size(layout, &w, &h);
    return (double)w / PANGO_SCALE;
}
static inline int kp_layout_height_px(void *layout) { int w, h; pango_layout_get_pixel_size(layout, &w, &h); return h; }
static inline int kp_layout_baseline_px(void *layout) { return pango_layout_get_baseline(layout) / PANGO_SCALE; }
static inline int kp_layout_line_count(void *layout) { return pango_layout_get_line_count(layout); }
static inline int kp_layout_line_start(void *layout, int line) {
    PangoLayoutLine *value = pango_layout_get_line_readonly(layout, line); return value ? value->start_index : 0;
}
static inline int kp_layout_line_end(void *layout, int line) {
    PangoLayoutLine *value = pango_layout_get_line_readonly(layout, line); return value ? value->start_index + value->length : 0;
}
static inline int kp_layout_line_x(void *layout, int line) {
    PangoRectangle logical = {0}; PangoLayoutLine *value = pango_layout_get_line_readonly(layout, line);
    if (value) pango_layout_line_get_pixel_extents(value, NULL, &logical); return logical.x;
}
static inline int kp_layout_line_y(void *layout, int line) {
    PangoRectangle logical = {0};
    PangoLayoutIter *iter = pango_layout_get_iter((PangoLayout *)layout);
    int current = 0;
    do {
        if (current == line) {
            pango_layout_iter_get_line_extents(iter, NULL, &logical);
            break;
        }
        current++;
    } while (pango_layout_iter_next_line(iter));
    pango_layout_iter_free(iter);
    return logical.y / PANGO_SCALE;
}
static inline int kp_layout_line_width(void *layout, int line) {
    PangoRectangle logical = {0}; PangoLayoutLine *value = pango_layout_get_line_readonly(layout, line);
    if (value) pango_layout_line_get_pixel_extents(value, NULL, &logical); return logical.width;
}
static inline int kp_layout_line_height(void *layout, int line) {
    PangoRectangle logical = {0}; PangoLayoutLine *value = pango_layout_get_line_readonly(layout, line);
    if (value) pango_layout_line_get_pixel_extents(value, NULL, &logical); return logical.height;
}
static inline int kp_layout_index_x(void *layout, int index) {
    PangoRectangle rect; pango_layout_index_to_pos(layout, index, &rect); return rect.x / PANGO_SCALE;
}
static inline int kp_layout_index_y(void *layout, int index) {
    PangoRectangle rect; pango_layout_index_to_pos(layout, index, &rect); return rect.y / PANGO_SCALE;
}
static inline int kp_layout_index_height(void *layout, int index) {
    PangoRectangle rect; pango_layout_index_to_pos(layout, index, &rect); return rect.height / PANGO_SCALE;
}
static inline int kp_layout_index_width(void *layout, int index) {
    PangoRectangle rect; pango_layout_index_to_pos(layout, index, &rect); return rect.width / PANGO_SCALE;
}
static inline int kp_layout_direction(void *layout, int index) {
    PangoDirection direction = pango_layout_get_direction((PangoLayout *)layout, index);
    return direction == PANGO_DIRECTION_RTL || direction == PANGO_DIRECTION_WEAK_RTL;
}
static inline int kp_layout_line_direction(void *layout, int line) {
    PangoLayoutLine *value = pango_layout_get_line_readonly((PangoLayout *)layout, line);
    return value && pango_layout_line_get_resolved_direction(value) == PANGO_DIRECTION_RTL;
}
static inline int kp_layout_xy_index(void *layout, int x, int y) {
    int index = 0, trailing = 0;
    pango_layout_xy_to_index(layout, x * PANGO_SCALE, y * PANGO_SCALE, &index, &trailing);
    return index + trailing;
}
static inline void kp_layout_draw(void *cr, void *layout) { pango_cairo_show_layout(cr, layout); }
static inline gboolean kp_mask_remove_paint_attr(PangoAttribute *attr, gpointer unused) {
    (void)unused;
    PangoAttrType type = attr->klass->type;
    return type == PANGO_ATTR_FOREGROUND ||
           type == PANGO_ATTR_BACKGROUND ||
           type == PANGO_ATTR_UNDERLINE ||
           type == PANGO_ATTR_STRIKETHROUGH ||
           type == PANGO_ATTR_FOREGROUND_ALPHA ||
           type == PANGO_ATTR_BACKGROUND_ALPHA;
}
static inline void kp_layout_mask_range(void *cr_raw, void *layout_raw, int start, int end) {
    cairo_t *cr = (cairo_t *)cr_raw;
    PangoLayout *layout = (PangoLayout *)layout_raw;
    PangoLayout *copy = pango_layout_copy(layout);
    PangoAttrList *original = pango_layout_get_attributes(copy);
    PangoAttrList *attrs = original ? pango_attr_list_copy(original) : pango_attr_list_new();
    /* A text mask contains glyphs only, while retaining font and shape attrs. */
    PangoAttrList *removed = pango_attr_list_filter(attrs, kp_mask_remove_paint_attr, NULL);
    if (removed) pango_attr_list_unref(removed);
    PangoAttribute *foreground = pango_attr_foreground_new(65535, 65535, 65535);
    foreground->start_index = 0; foreground->end_index = G_MAXUINT;
    pango_attr_list_change(attrs, foreground);
    PangoAttribute *hidden = pango_attr_foreground_alpha_new(0);
    hidden->start_index = 0; hidden->end_index = G_MAXUINT;
    pango_attr_list_change(attrs, hidden);
    PangoAttribute *visible = pango_attr_foreground_alpha_new(65535);
    visible->start_index = start; visible->end_index = end;
    pango_attr_list_change(attrs, visible);
    pango_layout_set_attributes(copy, attrs);
    pango_attr_list_unref(attrs);
    cairo_save(cr);
    cairo_push_group(cr);
    cairo_set_source_rgba(cr, 1, 1, 1, 1);
    pango_cairo_show_layout(cr, copy);
    cairo_pattern_t *mask = cairo_pop_group(cr);
    cairo_restore(cr);
    cairo_mask(cr, mask);
    cairo_pattern_destroy(mask);
    g_object_unref(copy);
}

#ifdef __cplusplus
}
#endif

#endif
