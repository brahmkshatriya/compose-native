package dev.brahmkshatriya.compose

import org.gradle.api.Plugin
import org.gradle.api.Project

class ComposeNativePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.enableRequestedCoordinateMatching()
        project.addDesktopNativeSourceSets()
        project.createComposeNativeApplicationExtension()
        project.configureDesktopNativeApplicationConventions()
        project.configureMetadataCompilation()
        project.configureIdeDependencyResolution()
        project.configureSkikoResolution()
        project.configureNonDesktopForkPlatformMetadata()
        project.configureNonDesktopForkSupportRouting()
        project.configureAndroidApplicationForkSupportRouting()
        project.configureDependencySubstitutions()
        project.configureNativeSkikoPublicationMetadata()
    }
}
