#ifndef KTNATIVE_LINUX_DESKTOP_H
#define KTNATIVE_LINUX_DESKTOP_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

int kld_notifications_supported(void);
char *kld_notification_capabilities(char **error_message);

void *kld_notification_create(
    const char *application_name,
    const char *title,
    const char *body,
    const char *icon_name,
    uint32_t replaces_id,
    int timeout_millis
);
int kld_notification_add_action(void *builder, const char *id, const char *label);
int kld_notification_add_hint_byte(void *builder, const char *name, uint8_t value);
int kld_notification_add_hint_int32(void *builder, const char *name, int32_t value);
int kld_notification_add_hint_uint32(void *builder, const char *name, uint32_t value);
int kld_notification_add_hint_int64(void *builder, const char *name, int64_t value);
int kld_notification_add_hint_uint64(void *builder, const char *name, uint64_t value);
int kld_notification_add_hint_double(void *builder, const char *name, double value);
int kld_notification_add_hint_bool(void *builder, const char *name, int value);
int kld_notification_add_hint_string(void *builder, const char *name, const char *value);
uint32_t kld_notification_send(void *builder, char **error_message);
void kld_notification_destroy(void *builder);
int kld_notification_close(uint32_t id, char **error_message);

int kld_progress_supported(void);
char *kld_progress_start(
    const char *application_name,
    const char *icon_name,
    int capabilities,
    char **error_message
);
int kld_progress_update(
    const char *path,
    uint64_t total_bytes,
    uint64_t processed_bytes,
    uint64_t bytes_per_second,
    uint64_t elapsed_millis,
    uint32_t percent,
    const char *message,
    char **error_message
);
int kld_progress_terminate(const char *path, const char *error, char **error_message);

/* Event types: 1 notification action, 2 notification closed,
 * 3 progress cancel, 4 progress suspend, 5 progress resume. */
int kld_poll_event(uint32_t *id, uint32_t *reason, char **value);

void kld_free_string(char *value);
void kld_shutdown(void);

#ifdef __cplusplus
}
#endif

#endif
