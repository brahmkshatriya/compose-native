/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package androidx.compose.ui.window

import kotlin.test.Test
import kotlin.test.assertEquals

class LinuxDragAndDropTest {
    @Test
    fun convertsAbsolutePathToFileUri() {
        assertEquals("file:///tmp/report.txt", fileUri("/tmp/report.txt"))
    }

    @Test
    fun percentEncodesSpacesUnicodeAndReservedCharacters() {
        assertEquals(
            "file:///tmp/a%20b/%E2%9C%93%23.txt",
            fileUri("/tmp/a b/✓#.txt"),
        )
    }

    @Test
    fun preservesExistingFileUri() {
        assertEquals("file:///tmp/already%20encoded", fileUri("file:///tmp/already%20encoded"))
    }
}
