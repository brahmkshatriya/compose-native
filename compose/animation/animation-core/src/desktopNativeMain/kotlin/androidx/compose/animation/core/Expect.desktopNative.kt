/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package androidx.compose.animation.core

import platform.posix.pthread_self

internal actual fun getCurrentThread(): Any = pthread_self()
