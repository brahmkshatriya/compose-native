package dev.brahmkshatriya.compose

import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Project
import org.gradle.api.plugins.ExtensionAware

internal fun Project.addDesktopNativeSourceSets() {
    pluginManager.withPlugin(KOTLIN_MULTIPLATFORM_PLUGIN_ID) {
        val kotlin = extensions.getByName("kotlin")
        (kotlin as ExtensionAware).extensions.add("desktopNative", DesktopNativeTargets(this))
        val sourceSets = kotlinSourceSets()
        val desktopNativeMain = sourceSets.maybeCreate("desktopNativeMain")
        val desktopNativeTest = sourceSets.maybeCreate("desktopNativeTest")
        val linuxWindowsMain = sourceSets.maybeCreate("linuxWindowsMain")
        val linuxWindowsTest = sourceSets.maybeCreate("linuxWindowsTest")

        desktopNativeMain.dependsOnSourceSet(sourceSets.getByName("commonMain"))
        desktopNativeTest.dependsOnSourceSet(sourceSets.getByName("commonTest"))
        linuxWindowsMain.dependsOnSourceSet(desktopNativeMain)
        linuxWindowsTest.dependsOnSourceSet(desktopNativeTest)

        afterEvaluate {
            listOf("Main", "Test").forEach { compilation ->
                val desktop = sourceSets.getByName("desktopNative$compilation")
                val linuxWindows = sourceSets.getByName("linuxWindows$compilation")
                sourceSets.findByName("mingwX64$compilation")?.dependsOnSourceSet(linuxWindows)
                sourceSets.addSharedPlatformSourceSet(
                    "linux$compilation",
                    LINUX_SOURCE_SET_NAMES.map { "$it$compilation" },
                    listOf(linuxWindows),
                )
                val apple =
                    sourceSets.addSharedPlatformSourceSet(
                        "apple$compilation",
                        APPLE_SOURCE_SET_NAMES.map { "$it$compilation" },
                        listOf(sourceSets.getByName("common$compilation")),
                    )
                if (apple != null) {
                    sourceSets.addSharedPlatformSourceSet(
                        "macos$compilation",
                        MACOS_SOURCE_SET_NAMES.map { "$it$compilation" },
                        listOf(desktop, apple),
                    )
                }
            }
            sourceSets.addSharedPlatformSourceSet(
                "skiaTargetMain",
                SKIA_TARGET_MAIN_SOURCE_SET_NAMES,
                listOf(sourceSets.getByName("commonMain")),
            )
        }
    }
}

private fun NamedDomainObjectContainer<Any>.addSharedPlatformSourceSet(
    name: String,
    targetSourceSetNames: List<String>,
    parents: List<Any>,
): Any? {
    val targetSourceSets = targetSourceSetNames.mapNotNull(::findByName)
    if (targetSourceSets.isEmpty()) return null
    val shared = maybeCreate(name)
    parents.forEach(shared::dependsOnSourceSet)
    targetSourceSets.forEach { it.dependsOnSourceSet(shared) }
    return shared
}
