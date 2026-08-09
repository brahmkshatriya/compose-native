@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package app.mpv

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer

/** Windows placeholders for the catalogue's Linux libmpv integration. */
fun app_mpv_create(uri: String): COpaquePointer? = null

fun app_mpv_destroy(player: COpaquePointer?) = Unit

fun app_mpv_error(player: COpaquePointer?): CPointer<ByteVar>? = null

fun app_mpv_render(player: COpaquePointer?, framebuffer: Int, width: Int, height: Int): Int = 0

fun app_mpv_set_render_update_callback(
    player: COpaquePointer?,
    callback: CPointer<CFunction<(COpaquePointer?) -> Unit>>?,
    context: COpaquePointer?,
) = Unit

fun app_mpv_set_playing(player: COpaquePointer?, playing: Int) = Unit

fun app_mpv_seek_percent(player: COpaquePointer?, percent: Double) = Unit

fun app_mpv_set_position_update_callback(
    player: COpaquePointer?,
    callback: CPointer<CFunction<(COpaquePointer?, Double) -> Unit>>?,
    context: COpaquePointer?,
) = Unit

fun app_mpv_set_volume(player: COpaquePointer?, volume: Double) = Unit
