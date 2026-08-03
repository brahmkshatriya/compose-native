/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package androidx.compose.ui.platform

private class LinuxUriHandler : UriHandler {
    override fun openUri(uri: String) {
        val services =
            checkNotNull(LinuxPlatformServicesRegistry.current()) {
                "Opening URIs requires an active Linux window host"
            }
        services.openUri(uri)
    }
}

internal actual fun createPlatformUriHandler(): UriHandler = LinuxUriHandler()
