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
import nativedesktop.kld_free_string
import nativedesktop.kld_tray_create
import nativedesktop.kld_tray_destroy
import nativedesktop.kld_tray_menu_add
import nativedesktop.kld_tray_menu_clear
import nativedesktop.kld_tray_menu_commit
import nativedesktop.kld_tray_poll
import nativedesktop.kld_tray_supported
import nativedesktop.kld_tray_update

/**
 * `true` when the current desktop session supports tray icons.
 *
 * Check this value before composing [Tray] when tray support is optional for the application.
 */
val isTraySupported: Boolean
    get() = kld_tray_supported() != 0

/**
 * State object associated with a [Tray].
 *
 * In most cases this should be created with [rememberTrayState].
 */
class TrayState {}

/** Creates a [TrayState] that is remembered across compositions. */
@Composable
fun rememberTrayState(): TrayState = remember { TrayState() }

/**
 * Adds a tray icon to the desktop session.
 *
 * The tray icon and its menu remain registered while this composable is in the composition.
 * Use [isTraySupported] to determine whether the current desktop session supports tray icons.
 *
 * @param icon icon shown in the system tray
 * @param state state associated with this tray
 * @param tooltip text shown when the desktop presents a tooltip for the tray icon
 * @param onAction invoked for the tray icon's primary activation action
 * @param menu context-menu content associated with the tray icon
 */
@Composable
fun ApplicationScope.Tray(
    icon: Painter,
    state: TrayState = rememberTrayState(),
    tooltip: String? = null,
    onAction: () -> Unit = {},
    menu: @Composable @MenuComposable MenuScope.() -> Unit = {},
) {
    @Suppress("UNUSED_VARIABLE") val retainedState = state
    val builder = NativeMenuBuilder()
    MenuScope(builder).menu()
    val model = builder.build()
    val registration = remember { NativeTrayRegistration() }
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
        NativeTrayRegistry.add(registration)
        onDispose {
            NativeTrayRegistry.remove(registration)
            registration.close()
        }
    }
}

internal object NativeTrayRegistry {
    private val registrations = mutableSetOf<NativeTrayRegistration>()

    val hasRegistrations: Boolean
        get() = registrations.isNotEmpty()

    fun add(registration: NativeTrayRegistration) {
        registrations += registration
    }

    fun remove(registration: NativeTrayRegistration) {
        registrations -= registration
    }

    fun poll() {
        registrations.toList().forEach(NativeTrayRegistration::poll)
    }

    fun closeAll() {
        registrations.toList().forEach(NativeTrayRegistration::close)
        registrations.clear()
    }
}

internal class NativeTrayRegistration : AutoCloseable {
    private var handle: COpaquePointer? = null
    private var icon: Painter? = null
    private var title = ""
    private var tooltip = ""
    private var menuSignature = Int.MIN_VALUE
    private var model = NativeMenuModel.Empty
    private var onAction: () -> Unit = {}
    private var closed = false

    fun update(
        icon: Painter,
        title: String,
        tooltip: String,
        onAction: () -> Unit,
        model: NativeMenuModel,
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

    private fun publishMenu(handle: COpaquePointer, model: NativeMenuModel) {
        kld_tray_menu_clear(handle)
        fun add(entries: List<NativeMenuEntry>, parentId: Int) {
            entries.forEach { entry ->
                val type =
                    when (entry) {
                        is NativeMenuEntry.Item ->
                            when {
                                entry.radio -> 4
                                entry.checked != null -> 3
                                else -> 0
                            }
                        is NativeMenuEntry.Menu -> 1
                        is NativeMenuEntry.Separator -> 2
                    }
                val label =
                    when (entry) {
                        is NativeMenuEntry.Item -> entry.text
                        is NativeMenuEntry.Menu -> entry.text
                        is NativeMenuEntry.Separator -> ""
                    }
                val checked = (entry as? NativeMenuEntry.Item)?.checked == true
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
                if (entry is NativeMenuEntry.Menu) add(entry.children, entry.id)
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

private fun findMenuItem(entries: List<NativeMenuEntry>, id: Int): NativeMenuEntry.Item? {
    entries.forEach { entry ->
        when (entry) {
            is NativeMenuEntry.Item -> if (entry.id == id) return entry
            is NativeMenuEntry.Menu ->
                findMenuItem(entry.children, id)?.let {
                    return it
                }
            is NativeMenuEntry.Separator -> Unit
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
