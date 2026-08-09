@file:OptIn(
    org.jetbrains.compose.resources.ExperimentalResourceApi::class,
    org.jetbrains.compose.resources.InternalResourceApi::class,
)

package org.jetbrains.compose.resources

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal

internal actual val ProvidableCompositionLocal<ResourceReader>.currentOrPreview: ResourceReader
    @Composable get() = current
