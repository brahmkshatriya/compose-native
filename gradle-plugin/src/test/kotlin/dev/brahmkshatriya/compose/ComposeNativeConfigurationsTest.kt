package dev.brahmkshatriya.compose

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.gradle.api.attributes.Attribute
import org.gradle.testfixtures.ProjectBuilder

class ComposeNativeConfigurationsTest {
    @Test
    fun identifiesDesktopNativeConfigurations() {
        assertEquals(true, "linuxX64CompileKlibraries".isDesktopNativeConfiguration())
        assertEquals(true, "linuxArm64RuntimeKlibraries".isDesktopNativeConfiguration())
        assertEquals(true, "mingwX64CompileKlibraries".isDesktopNativeConfiguration())
        assertEquals(true, "macosX64CompileKlibraries".isDesktopNativeConfiguration())
        assertEquals(true, "macosArm64RuntimeKlibraries".isDesktopNativeConfiguration())
        assertEquals(
            true,
            "desktopNativeMainResolvableDependenciesMetadata".isDesktopNativeConfiguration(),
        )
        assertEquals(
            false,
            "commonMainResolvableDependenciesMetadata".isDesktopNativeConfiguration(),
        )
    }

    @Test
    fun doesNotForceSharedNativeMetadataToOneLeafTarget() {
        val project = ProjectBuilder.builder().build()
        ComposeNativePlugin().apply(project)
        val desktopMetadata =
            project.configurations.create("desktopNativeMainResolvableDependenciesMetadata")
        val linuxWindowsMetadata =
            project.configurations.create("linuxWindowsMainResolvableDependenciesMetadata")
        val commonMetadata =
            project.configurations.create("commonMainResolvableDependenciesMetadata")
        val nativeTarget = Attribute.of("org.jetbrains.kotlin.native.target", String::class.java)

        assertNull(desktopMetadata.attributes.getAttribute(nativeTarget))
        assertNull(linuxWindowsMetadata.attributes.getAttribute(nativeTarget))
        assertNull(commonMetadata.attributes.getAttribute(nativeTarget))
    }

    @Test
    fun identifiesSharedNativeMetadataConfigurationsForDisambiguation() {
        assertEquals(
            true,
            "desktopNativeMainResolvableDependenciesMetadata".isSharedNativeMetadataConfiguration(),
        )
        assertEquals(
            true,
            "linuxWindowsMainResolvableDependenciesMetadata".isSharedNativeMetadataConfiguration(),
        )
        assertEquals(
            true,
            "linuxMainResolvableDependenciesMetadata".isSharedNativeMetadataConfiguration(),
        )
        assertEquals(
            true,
            "macosMainResolvableDependenciesMetadata".isSharedNativeMetadataConfiguration(),
        )
        assertEquals(
            false,
            "appleMainResolvableDependenciesMetadata".isSharedNativeMetadataConfiguration(),
        )
        assertEquals(
            false,
            "compileDesktopNativeMainKotlinMetadata".isSharedNativeMetadataConfiguration(),
        )
        assertEquals(false, "linuxX64CompileKlibraries".isSharedNativeMetadataConfiguration())
        assertEquals(
            false,
            "commonMainResolvableDependenciesMetadata".isSharedNativeMetadataConfiguration(),
        )
    }

    @Test
    fun identifiesMetadataTransformationConfigurations() {
        assertEquals(
            true,
            "commonMainResolvableDependenciesMetadata".isMetadataTransformationConfiguration(),
        )
        assertEquals(
            true,
            "skiaMainResolvableDependenciesMetadata".isMetadataTransformationConfiguration(),
        )
        assertEquals(
            true,
            "appleMainResolvableDependenciesMetadata".isMetadataTransformationConfiguration(),
        )
        assertEquals(
            true,
            "desktopNativeMainResolvableDependenciesMetadata"
                .isMetadataTransformationConfiguration(),
        )
        assertEquals(
            false,
            "allSourceSetsCompileDependenciesMetadata".isMetadataTransformationConfiguration(),
        )
        assertEquals(
            false,
            "macosArm64CompilationDependenciesMetadata".isMetadataTransformationConfiguration(),
        )
        assertEquals(false, "commonMainImplementation".isMetadataTransformationConfiguration())
    }

    @Test
    fun identifiesKotlinMetadataCompilationTasks() {
        assertEquals(true, "compileCommonMainKotlinMetadata".isKotlinMetadataCompilationTask())
        assertEquals(true, "compileSkiaMainKotlinMetadata".isKotlinMetadataCompilationTask())
        assertEquals(true, "compileAppleMainKotlinMetadata".isKotlinMetadataCompilationTask())
        assertEquals(false, "compileKotlinLinuxX64".isKotlinMetadataCompilationTask())
        assertEquals(
            false,
            "transformSkiaMainDependenciesMetadata".isKotlinMetadataCompilationTask(),
        )
    }
}
