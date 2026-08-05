#ifndef KTNATIVE_LINUX_TRAY_H
#define KTNATIVE_LINUX_TRAY_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

int kld_tray_supported(void);
void *kld_tray_create(
    const char *title,
    const char *tooltip,
    const unsigned char *pixels,
    int width,
    int height,
    int stride,
    char **error_message);
int kld_tray_update(
    void *tray,
    const char *title,
    const char *tooltip,
    const unsigned char *pixels,
    int width,
    int height,
    int stride,
    char **error_message);
void kld_tray_menu_clear(void *tray);
int kld_tray_menu_add(
    void *tray,
    int parent_id,
    int item_id,
    int item_type,
    const char *label,
    int enabled,
    int checked);
int kld_tray_menu_commit(void *tray);
/* Event types: 1 activate, 2 secondary activate, 3 context request, 4 menu item. */
int kld_tray_poll(void *tray, int *event_type, int *item_id);
void kld_tray_destroy(void *tray);

#ifdef __cplusplus
}
#endif

#endif
