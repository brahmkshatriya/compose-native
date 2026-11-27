package dev.brahmkshatriya.compose

import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Project
import org.gradle.api.plugins.ExtensionAware
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet

internal fun Project.configureDesktopNative() {
    pluginManager.withPlugin(KOTLIN_MULTIPLATFORM_PLUGIN_ID) {
        val kotlin = extensions.getByName("kotlin")
        val desktopNative = DesktopNativeTargets(this)
        (kotlin as ExtensionAware).extensions.add("desktopNative", desktopNative)
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
            desktopNative.binaries.executable?.let { executable ->
                val executableTargets = selectDesktopNativeExecutableTargets()
                // Kotlin registers artifact tasks for newly declared targets in its next callback.
                afterEvaluate { configureDesktopNativeExecutable(executable, executableTargets) }
            }
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
            val skiaTargetMain =
                sourceSets.addSharedPlatformSourceSet(
                    "skiaTargetMain",
                    SKIA_TARGET_MAIN_SOURCE_SET_NAMES,
                    listOf(sourceSets.getByName("commonMain")),
                )
            if (skiaTargetMain is KotlinSourceSet) configureSkiaTargetDependencies(skiaTargetMain)

            configureDesktopNativeApplicationConventions()
        }
    }
}

private fun Project.configureSkiaTargetDependencies(sourceSet: KotlinSourceSet) {
    pluginManager.withPlugin("org.jetbrains.compose") {
        val uiGroup = "${OFFICIAL_COMPOSE_GROUP_PREFIX}ui"
        val uiVersion =
            configurations
                .matching { it.name.startsWith(COMMON_MAIN_CONFIGURATION_PREFIX) }
                .flatMap { it.dependencies }
                .firstOrNull { it.group == uiGroup && it.name == "ui" }
                ?.version ?: requireNotNull(ComposeNativeUpstreamVersions.compose("ui"))
        val uiSkiko = "$uiGroup:ui-skiko:$uiVersion"
        sourceSet.dependencies {
            // Use the same requests in every target so Kotlin can select the shared fragments.
            implementation("$uiGroup:ui-graphics:$uiVersion")
            implementation(uiSkiko)
            implementation(upstreamSkikoDependency(uiSkiko))
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
