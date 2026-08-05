#ifndef KTNATIVE_LINUX_ATSPI_H
#define KTNATIVE_LINUX_ATSPI_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef int (*KldAtspiActionCallback)(
    void *context,
    int32_t node_id,
    int32_t action_id,
    double numeric_value,
    const char *text_value,
    int32_t selection_start,
    int32_t selection_end
);

void *kld_atspi_window_create(
    const char *title,
    void *context,
    KldAtspiActionCallback callback
);

void kld_atspi_window_begin_update(
    void *window,
    const char *title,
    int visible,
    int focused,
    int screen_x,
    int screen_y,
    int width,
    int height
);

int kld_atspi_window_add_node(
    void *window,
    int32_t node_id,
    int32_t parent_id,
    uint32_t role,
    const char *name,
    const char *description,
    const char *accessible_id,
    const char *text,
    uint64_t states,
    int32_t x,
    int32_t y,
    int32_t width,
    int32_t height,
    int32_t selection_start,
    int32_t selection_end
);

int kld_atspi_window_add_action(
    void *window,
    int32_t node_id,
    int32_t action_id,
    const char *name,
    const char *description,
    const char *key_binding
);

int kld_atspi_window_set_value(
    void *window,
    int32_t node_id,
    double minimum,
    double maximum,
    double current,
    double increment,
    int32_t action_id
);

int kld_atspi_window_set_collection(
    void *window,
    int32_t node_id,
    int32_t row_count,
    int32_t column_count,
    int32_t row_index,
    int32_t row_span,
    int32_t column_index,
    int32_t column_span
);

int kld_atspi_window_set_editable_actions(
    void *window,
    int32_t node_id,
    int32_t set_text_action_id,
    int32_t insert_text_action_id,
    int32_t set_selection_action_id,
    int32_t copy_action_id,
    int32_t cut_action_id,
    int32_t paste_action_id
);

void kld_atspi_window_commit_update(void *window);
void kld_atspi_window_destroy(void *window);

/* Dispatches pending accessibility-bus calls without blocking. Returns 1 after a new connection. */
int kld_atspi_poll(void);
int kld_atspi_is_connected(void);
void kld_atspi_shutdown(void);

#ifdef __cplusplus
}
#endif

#endif
