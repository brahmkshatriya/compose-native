package dev.brahmkshatriya.compose

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopNativeRunTaskTest {
    @Test
    fun selectsHostNativeTargetForDesktopAlias() {
        assertEquals(
            HostDesktopNativeTarget("linuxX64", "linux_x64", "LinuxX64"),
            hostDesktopNativeTarget("Linux", "amd64"),
        )
        assertEquals(
            HostDesktopNativeTarget("linuxArm64", "linux_arm64", "LinuxArm64"),
            hostDesktopNativeTarget("Linux", "aarch64"),
        )
        assertEquals(
            HostDesktopNativeTarget("mingwX64", "mingw_x64", "MingwX64"),
            hostDesktopNativeTarget("Windows 11", "x86_64"),
        )
        assertEquals(
            HostDesktopNativeTarget("macosX64", "macos_x64", "MacosX64"),
            hostDesktopNativeTarget("Mac OS X", "x86_64"),
        )
        assertEquals(
            HostDesktopNativeTarget("macosArm64", "macos_arm64", "MacosArm64"),
            hostDesktopNativeTarget("Mac OS X", "aarch64"),
        )
        assertNull(hostDesktopNativeTarget("FreeBSD", "amd64"))
    }
}
