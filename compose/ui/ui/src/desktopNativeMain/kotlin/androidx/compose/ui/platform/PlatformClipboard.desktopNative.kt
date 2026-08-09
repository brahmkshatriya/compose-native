/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package androidx.compose.ui.platform

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.text.AnnotatedString

actual typealias NativeClipboard = Any

private var clipboardText: String? = null

private fun readClipboardText(): String? =
    NativeDesktopPlatformServicesRegistry.current()?.getClipboardText() ?: clipboardText

private fun writeClipboardText(text: String?) {
    clipboardText = text
    NativeDesktopPlatformServicesRegistry.current()?.setClipboardText(text.orEmpty())
}

@Suppress("DEPRECATION")
private class NativeDesktopClipboardManager : ClipboardManager {
    override fun getText(): AnnotatedString? = readClipboardText()?.let(::AnnotatedString)

    override fun setText(annotatedString: AnnotatedString) {
        writeClipboardText(annotatedString.text)
    }

    override fun getClip(): ClipEntry? = readClipboardText()?.let(ClipEntry::withPlainText)

    override fun setClip(clipEntry: ClipEntry?) {
        writeClipboardText(clipEntry?.plainText)
    }
}

private class NativeDesktopClipboard : Clipboard {
    override suspend fun getClipEntry(): ClipEntry? =
        readClipboardText()?.let(ClipEntry::withPlainText)

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        writeClipboardText(clipEntry?.plainText)
    }
}

actual class ClipEntry internal constructor() {
    actual val clipMetadata: ClipMetadata
        get() = ClipMetadata.Empty

    internal var plainText: String? = null

    @ExperimentalComposeUiApi fun getPlainText(): String? = plainText

    companion object {
        @ExperimentalComposeUiApi
        fun withPlainText(text: String): ClipEntry = ClipEntry().apply { plainText = text }
    }
}

@Suppress("DEPRECATION")
internal actual fun createPlatformClipboardManager(): ClipboardManager =
    NativeDesktopClipboardManager()

internal actual fun createPlatformClipboard(): Clipboard = NativeDesktopClipboard()
