/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package androidx.compose.ui.window

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.Painter
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import linuxdesktop.kld_free_string
import linuxdesktop.kld_tray_create
import linuxdesktop.kld_tray_destroy
import linuxdesktop.kld_tray_menu_add
import linuxdesktop.kld_tray_menu_clear
import linuxdesktop.kld_tray_menu_commit
import linuxdesktop.kld_tray_poll
import linuxdesktop.kld_tray_supported
import linuxdesktop.kld_tray_update

/** Whether the current desktop session exposes a StatusNotifier watcher. */
val isTraySupported: Boolean
    get() = kld_tray_supported() != 0

class TrayState {
    fun sendNotification(notification: Notification) {
        androidx.compose.ui.window.sendNotification(notification)
    }
}

@Composable fun rememberTrayState(): TrayState = remember { TrayState() }

@Composable
fun ApplicationScope.Tray(
    icon: Painter,
    state: TrayState = rememberTrayState(),
    tooltip: String? = null,
    onAction: () -> Unit = {},
    menu: @Composable @MenuComposable MenuScope.() -> Unit = {},
) {
    @Suppress("UNUSED_VARIABLE") val retainedState = state
    val builder = LinuxMenuBuilder()
    MenuScope(builder).menu()
    val model = builder.build()
    val registration = remember { LinuxTrayRegistration() }
    SideEffect {
        registration.update(
            icon = icon,
            title = tooltip?.takeIf(String::isNotBlank) ?: "Compose",
            tooltip = tooltip.orEmpty(),
            onAction = onAction,
            model = model,
        )
    }
    DisposableEffect(registration) {
        LinuxTrayRegistry.add(registration)
        onDispose {
            LinuxTrayRegistry.remove(registration)
            registration.close()
        }
    }
}

internal object LinuxTrayRegistry {
    private val registrations = mutableSetOf<LinuxTrayRegistration>()

    val hasRegistrations: Boolean
        get() = registrations.isNotEmpty()

    fun add(registration: LinuxTrayRegistration) {
        registrations += registration
    }

    fun remove(registration: LinuxTrayRegistration) {
        registrations -= registration
    }

    fun poll() {
        registrations.toList().forEach(LinuxTrayRegistration::poll)
    }

    fun closeAll() {
        registrations.toList().forEach(LinuxTrayRegistration::close)
        registrations.clear()
    }
}

internal class LinuxTrayRegistration : AutoCloseable {
    private var handle: COpaquePointer? = null
    private var icon: Painter? = null
    private var title = ""
    private var tooltip = ""
    private var menuSignature = Int.MIN_VALUE
    private var model = LinuxMenuModel.Empty
    private var onAction: () -> Unit = {}
    private var closed = false

    fun update(
        icon: Painter,
        title: String,
        tooltip: String,
        onAction: () -> Unit,
        model: LinuxMenuModel,
    ) {
        check(!closed) { "Tray registration is closed" }
        this.onAction = onAction
        this.model = model
        val current = handle
        if (current == null) {
            handle = create(icon, title, tooltip)
            this.icon = icon
            this.title = title
            this.tooltip = tooltip
            publishMenu(checkNotNull(handle), model)
            menuSignature = model.presentationSignature
            return
        }
        // Stateful painters can change without changing identity; publish the current pixels on
        // every composition update. The native service coalesces unchanged presentation naturally.
        updateNative(current, icon, title, tooltip)
        this.icon = icon
        this.title = title
        this.tooltip = tooltip
        if (menuSignature != model.presentationSignature) {
            publishMenu(current, model)
            menuSignature = model.presentationSignature
        }
    }

    private fun create(icon: Painter, title: String, tooltip: String): COpaquePointer =
        rasterizeWindowIcon(icon, TrayIconSize).use { image ->
            val pixels = IntArray(image.width * image.height)
            image.readPixels(pixels)
            pixels.usePinned { pinned ->
                memScoped {
                    val error = alloc<CPointerVar<ByteVar>>()
                    error.value = null
                    val created =
                        kld_tray_create(
                            title,
                            tooltip,
                            pinned.addressOf(0).reinterpret(),
                            image.width,
                            image.height,
                            image.width * 4,
                            error.ptr,
                        )
                    checkTrayError(error.value, "create tray icon")
                    checkNotNull(created) { "The desktop rejected the tray icon" }
                }
            }
        }

    private fun updateNative(
        handle: COpaquePointer,
        icon: Painter,
        title: String,
        tooltip: String,
    ) {
        rasterizeWindowIcon(icon, TrayIconSize).use { image ->
            val pixels = IntArray(image.width * image.height)
            image.readPixels(pixels)
            pixels.usePinned { pinned ->
                memScoped {
                    val error = alloc<CPointerVar<ByteVar>>()
                    error.value = null
                    check(
                        kld_tray_update(
                            handle,
                            title,
                            tooltip,
                            pinned.addressOf(0).reinterpret(),
                            image.width,
                            image.height,
                            image.width * 4,
                            error.ptr,
                        ) != 0
                    ) {
                        checkTrayError(error.value, "update tray icon")
                        "Could not update the tray icon"
                    }
                    checkTrayError(error.value, "update tray icon")
                }
            }
        }
    }

    private fun publishMenu(handle: COpaquePointer, model: LinuxMenuModel) {
        kld_tray_menu_clear(handle)
        fun add(entries: List<LinuxMenuEntry>, parentId: Int) {
            entries.forEach { entry ->
                val type =
                    when (entry) {
                        is LinuxMenuEntry.Item ->
                            when {
                                entry.radio -> 4
                                entry.checked != null -> 3
                                else -> 0
                            }
                        is LinuxMenuEntry.Menu -> 1
                        is LinuxMenuEntry.Separator -> 2
                    }
                val label =
                    when (entry) {
                        is LinuxMenuEntry.Item -> entry.text
                        is LinuxMenuEntry.Menu -> entry.text
                        is LinuxMenuEntry.Separator -> ""
                    }
                val checked = (entry as? LinuxMenuEntry.Item)?.checked == true
                check(
                    kld_tray_menu_add(
                        handle,
                        parentId,
                        entry.id,
                        type,
                        label,
                        if (entry.enabled) 1 else 0,
                        if (checked) 1 else 0,
                    ) != 0
                ) {
                    "Could not publish tray menu item ${entry.id}"
                }
                if (entry is LinuxMenuEntry.Menu) add(entry.children, entry.id)
            }
        }
        add(model.entries, 0)
        check(kld_tray_menu_commit(handle) != 0) { "Could not publish the tray menu" }
    }

    fun poll() {
        val current = handle ?: return
        memScoped {
            val eventType = alloc<IntVar>()
            val itemId = alloc<IntVar>()
            while (kld_tray_poll(current, eventType.ptr, itemId.ptr) != 0) {
                when (eventType.value) {
                    1,
                    2 -> onAction()
                    4 -> findMenuItem(model.entries, itemId.value)?.action?.invoke()
                }
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        handle?.let(::kld_tray_destroy)
        handle = null
    }

    private companion object {
        const val TrayIconSize = 32
    }
}

private fun findMenuItem(entries: List<LinuxMenuEntry>, id: Int): LinuxMenuEntry.Item? {
    entries.forEach { entry ->
        when (entry) {
            is LinuxMenuEntry.Item -> if (entry.id == id) return entry
            is LinuxMenuEntry.Menu ->
                findMenuItem(entry.children, id)?.let {
                    return it
                }
            is LinuxMenuEntry.Separator -> Unit
        }
    }
    return null
}

private fun checkTrayError(error: kotlinx.cinterop.CPointer<ByteVar>?, operation: String) {
    if (error == null) return
    val message = error.toKString()
    kld_free_string(error)
    error("Could not $operation: $message")
}
