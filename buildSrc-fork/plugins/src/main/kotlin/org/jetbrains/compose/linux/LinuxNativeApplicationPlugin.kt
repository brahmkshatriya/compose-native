/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package org.jetbrains.compose.linux

import java.io.File
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.bundling.Compression
import org.gradle.api.tasks.bundling.Tar
import org.gradle.process.ExecOperations

abstract class LinuxNativeApplicationExtension @Inject constructor(objects: ObjectFactory) {
    val applicationName: Property<String> = objects.property(String::class.java)
    val packageName: Property<String> = objects.property(String::class.java)
    val executableName: Property<String> = objects.property(String::class.java)
    val packageVersion: Property<String> = objects.property(String::class.java)
    val description: Property<String> = objects.property(String::class.java)
    val vendor: Property<String> = objects.property(String::class.java)
    val categories: ListProperty<String> = objects.listProperty(String::class.java)
    val iconFile: RegularFileProperty = objects.fileProperty()
    val releaseExecutable: RegularFileProperty = objects.fileProperty()
    val resourceDirectory: DirectoryProperty = objects.directoryProperty()
    val appDir: DirectoryProperty = objects.directoryProperty()
    val distributionDirectory: DirectoryProperty = objects.directoryProperty()
}

abstract class PrepareLinuxAppDirTask : DefaultTask() {
    @get:Input abstract val applicationName: Property<String>

    @get:Input abstract val packageName: Property<String>

    @get:Input abstract val executableName: Property<String>

    @get:Input abstract val packageVersion: Property<String>

    @get:Input abstract val applicationDescription: Property<String>

    @get:Input abstract val vendor: Property<String>

    @get:Input abstract val categories: ListProperty<String>

    @get:InputFile abstract val releaseExecutable: RegularFileProperty

    @get:InputDirectory @get:Optional abstract val resourceDirectory: DirectoryProperty

    @get:InputFile @get:Optional abstract val iconFile: RegularFileProperty

    @get:OutputDirectory abstract val appDir: DirectoryProperty

    @TaskAction
    fun prepare() {
        val root = appDir.get().asFile
        root.deleteRecursively()
        root.mkdirs()

        val packageId = packageName.get()
        val binaryName = executableName.get()
        val executable = root.resolve("usr/bin/$binaryName")
        executable.parentFile.mkdirs()
        releaseExecutable.get().asFile.copyTo(executable, overwrite = true)
        executable.setExecutable(true, false)

        if (resourceDirectory.isPresent) {
            val source = resourceDirectory.get().asFile
            if (source.isDirectory) {
                source.copyRecursively(
                    target = root.resolve("usr/share/$packageId/resources"),
                    overwrite = true,
                )
            }
        }

        val desktopText = buildString {
            appendLine("[Desktop Entry]")
            appendLine("Type=Application")
            appendLine("Name=${desktopEscape(applicationName.get())}")
            appendLine("Comment=${desktopEscape(applicationDescription.get())}")
            appendLine("Exec=$binaryName")
            appendLine("Icon=$packageId")
            appendLine("Terminal=false")
            appendLine(
                "Categories=${categories.get().joinToString(separator = ";", postfix = ";")}"
            )
            appendLine("X-AppImage-Version=${desktopEscape(packageVersion.get())}")
            appendLine("X-AppImage-Vendor=${desktopEscape(vendor.get())}")
        }
        val desktopAtRoot = root.resolve("$packageId.desktop")
        desktopAtRoot.writeText(desktopText)
        val desktopInstalled = root.resolve("usr/share/applications/$packageId.desktop")
        desktopInstalled.parentFile.mkdirs()
        desktopInstalled.writeText(desktopText)

        val iconExtension: String
        val iconSource: File
        if (iconFile.isPresent) {
            iconSource = iconFile.get().asFile
            iconExtension = iconSource.extension.lowercase().ifBlank { "png" }
        } else {
            iconExtension = "svg"
            iconSource = root.resolve(".generated-$packageId.svg")
            iconSource.writeText(defaultIconSvg(applicationName.get()))
        }
        val iconAtRoot = root.resolve("$packageId.$iconExtension")
        iconSource.copyTo(iconAtRoot, overwrite = true)
        val iconInstalled =
            if (iconExtension == "svg") {
                root.resolve("usr/share/icons/hicolor/scalable/apps/$packageId.svg")
            } else {
                root.resolve("usr/share/icons/hicolor/256x256/apps/$packageId.$iconExtension")
            }
        iconInstalled.parentFile.mkdirs()
        iconSource.copyTo(iconInstalled, overwrite = true)
        if (iconSource.name.startsWith(".generated-")) iconSource.delete()

        val appRun = root.resolve("AppRun")
        appRun.writeText(
            """#!/bin/sh
APPDIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
export COMPOSE_RESOURCE_ROOT="${'$'}APPDIR/usr/share/$packageId/resources"
exec "${'$'}APPDIR/usr/bin/$binaryName" "${'$'}@"
"""
        )
        appRun.setExecutable(true, false)
    }

    private fun desktopEscape(value: String): String =
        value.replace("\\", "\\\\").replace("\n", " ").replace("\r", " ")

    private fun defaultIconSvg(name: String): String {
        val label = name.trim().firstOrNull()?.uppercaseChar() ?: 'C'
        return """<svg xmlns="http://www.w3.org/2000/svg" width="256" height="256" viewBox="0 0 256 256">
<rect width="256" height="256" rx="52" fill="#4051b5"/>
<text x="128" y="164" text-anchor="middle" font-family="sans-serif" font-size="132" font-weight="600" fill="white">$label</text>
</svg>
"""
    }
}

abstract class PackageLinuxAppImageTask
@Inject
constructor(private val execOperations: ExecOperations) : DefaultTask() {
    @get:InputDirectory abstract val appDir: DirectoryProperty

    @get:Input abstract val architecture: Property<String>

    @get:OutputFile abstract val outputFile: RegularFileProperty

    @TaskAction
    fun packageAppImage() {
        val tool =
            System.getenv("APPIMAGETOOL")?.takeIf { it.isNotBlank() }
                ?: findOnPath("appimagetool")
                ?: throw GradleException(
                    "appimagetool was not found. Install it or set APPIMAGETOOL to its executable path."
                )
        val output = outputFile.get().asFile
        output.parentFile.mkdirs()
        output.delete()
        execOperations.exec { spec ->
            spec.executable(tool)
            spec.args(appDir.get().asFile.absolutePath, output.absolutePath)
            spec.environment("ARCH", architecture.get())
        }
    }

    private fun findOnPath(name: String): String? =
        System.getenv("PATH")
            ?.split(File.pathSeparatorChar)
            ?.asSequence()
            ?.map { File(it, name) }
            ?.firstOrNull { it.isFile && it.canExecute() }
            ?.absolutePath
}

abstract class RunLinuxAppDirTask @Inject constructor(private val execOperations: ExecOperations) :
    DefaultTask() {
    @get:InputFile abstract val appRun: RegularFileProperty

    @TaskAction
    fun runApplication() {
        execOperations.exec { spec -> spec.commandLine(appRun.get().asFile.absolutePath) }
    }
}

class LinuxNativeApplicationPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        with(project) {
            val hostIsArm64 =
                System.getProperty("os.arch").equals("aarch64", ignoreCase = true) ||
                    System.getProperty("os.arch").equals("arm64", ignoreCase = true)
            val kotlinTargetName = if (hostIsArm64) "LinuxArm64" else "LinuxX64"
            val kotlinSourceSetName = if (hostIsArm64) "linuxArm64Main" else "linuxX64Main"
            val distributionArch = if (hostIsArm64) "arm64" else "x64"
            val appImageArch = if (hostIsArm64) "aarch64" else "x86_64"

            val extension =
                extensions.create(
                    "linuxNativeApplication",
                    LinuxNativeApplicationExtension::class.java,
                )
            extension.applicationName.convention(name)
            extension.packageName.convention(
                providers.provider {
                    val groupPart =
                        group.toString().takeIf { it.isNotBlank() && it != "unspecified" }
                    listOfNotNull(groupPart, name)
                        .joinToString(".")
                        .lowercase()
                        .replace(Regex("[^a-z0-9._-]"), "-")
                }
            )
            extension.executableName.convention(name)
            extension.packageVersion.convention(
                providers.provider {
                    version.toString().takeIf { it.isNotBlank() && it != "unspecified" } ?: "1.0.0"
                }
            )
            extension.description.convention(extension.applicationName)
            extension.vendor.convention("")
            extension.categories.convention(listOf("Utility"))
            extension.releaseExecutable.convention(
                layout.buildDirectory.file(
                    extension.executableName.map { executable ->
                        "bin/${kotlinTargetName.replaceFirstChar(Char::lowercaseChar)}/releaseExecutable/$executable.kexe"
                    }
                )
            )
            extension.resourceDirectory.convention(
                layout.buildDirectory.dir(
                    "generated/compose/resourceGenerator/assembledResources/$kotlinSourceSetName"
                )
            )
            extension.appDir.convention(
                layout.buildDirectory.dir(
                    extension.applicationName.map { app ->
                        "compose/binaries/main-release/app/${app.fileSafe()}.AppDir"
                    }
                )
            )
            extension.distributionDirectory.convention(layout.buildDirectory.dir("distributions"))

            val prepare =
                tasks.register("prepareLinuxReleaseAppDir", PrepareLinuxAppDirTask::class.java) {
                    task ->
                    task.group = "distribution"
                    task.description = "Assembles the Linux native release AppDir."
                    task.dependsOn("linkReleaseExecutable$kotlinTargetName")
                    task.applicationName.set(extension.applicationName)
                    task.packageName.set(extension.packageName)
                    task.executableName.set(extension.executableName)
                    task.packageVersion.set(extension.packageVersion)
                    task.applicationDescription.set(extension.description)
                    task.vendor.set(extension.vendor)
                    task.categories.set(extension.categories)
                    task.releaseExecutable.set(extension.releaseExecutable)
                    task.resourceDirectory.set(extension.resourceDirectory)
                    task.iconFile.set(extension.iconFile)
                    task.appDir.set(extension.appDir)
                    task.onlyIf {
                        val binary = task.releaseExecutable.get().asFile
                        if (!binary.isFile) {
                            throw GradleException(
                                "Linux native release executable was not produced: $binary"
                            )
                        }
                        true
                    }
                }
            pluginManager.withPlugin("org.jetbrains.compose") {
                prepare.configure { task ->
                    task.dependsOn("assemble${kotlinTargetName}MainResources")
                }
            }

            tasks.register("packageLinuxReleaseTarGz", Tar::class.java) { task ->
                task.group = "distribution"
                task.description = "Packages the Linux native AppDir as a tar.gz archive."
                task.dependsOn(prepare)
                task.compression = Compression.GZIP
                task.archiveExtension.set("tar.gz")
                task.archiveBaseName.set(extension.applicationName.map(String::fileSafe))
                task.archiveVersion.set(extension.packageVersion)
                task.archiveClassifier.set("linux-$distributionArch")
                task.destinationDirectory.set(extension.distributionDirectory)
                task.from(extension.appDir) { spec ->
                    spec.into(extension.appDir.map { directory -> directory.asFile.name })
                }
            }

            tasks.register("packageLinuxReleaseAppImage", PackageLinuxAppImageTask::class.java) {
                task ->
                task.group = "distribution"
                task.description = "Packages the Linux native AppDir as an AppImage."
                task.dependsOn(prepare)
                task.appDir.set(extension.appDir)
                task.architecture.set(appImageArch)
                task.outputFile.set(
                    extension.distributionDirectory.file(
                        extension.applicationName.zip(extension.packageVersion) { app, version ->
                            "${app.fileSafe()}-$version-$appImageArch.AppImage"
                        }
                    )
                )
            }

            tasks.register("runLinuxReleaseDistributable", RunLinuxAppDirTask::class.java) { task ->
                task.group = "application"
                task.description = "Runs the assembled Linux native release AppDir."
                task.dependsOn(prepare)
                task.appRun.set(extension.appDir.file("AppRun"))
            }
        }
    }
}

private fun String.fileSafe(): String =
    trim().ifBlank { "application" }.replace(Regex("[^A-Za-z0-9._-]"), "-")
