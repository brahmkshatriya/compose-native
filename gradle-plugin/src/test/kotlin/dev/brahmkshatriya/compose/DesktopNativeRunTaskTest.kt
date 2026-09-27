package dev.brahmkshatriya.compose

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopNativeRunTaskTest {
    @Test
    fun selectsNativeDebugRunTaskForHost() {
        assertEquals("runDebugExecutableLinuxX64", hostDesktopDebugRunTaskName("Linux", "amd64"))
        assertEquals("runDebugExecutableLinuxArm64", hostDesktopDebugRunTaskName("Linux", "aarch64"))
        assertEquals("runDebugExecutableMingwX64", hostDesktopDebugRunTaskName("Windows 11", "x86_64"))
        assertEquals("runDebugExecutableMacosX64", hostDesktopDebugRunTaskName("Mac OS X", "x86_64"))
        assertEquals("runDebugExecutableMacosArm64", hostDesktopDebugRunTaskName("Mac OS X", "aarch64"))
        assertNull(hostDesktopDebugRunTaskName("FreeBSD", "amd64"))
    }
}
