package dev.brahmkshatriya.compose

import java.io.File
import org.gradle.api.GradleException

internal fun copyRuntimeFiles(files: Set<File>, destination: File) {
    files.forEach { source ->
        if (source.isDirectory) {
            source.listFiles().orEmpty().forEach { child ->
                if (child.isFile)
                    child.copyTo(destination.resolve(runtimeFileName(child)), overwrite = true)
            }
        } else if (source.isFile) {
            source.copyTo(destination.resolve(runtimeFileName(source)), overwrite = true)
        }
    }
}

private fun runtimeFileName(file: File): String =
    if (file.extension == "dat" && file.name.contains("icudtl", ignoreCase = true)) "icudtl.dat"
    else file.name

internal fun xmlEscape(value: String): String =
    value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

internal fun findOnPath(name: String): String? =
    System.getenv("PATH")
        ?.split(File.pathSeparatorChar)
        ?.asSequence()
        ?.map { File(it, name) }
        ?.firstOrNull { it.isFile && it.canExecute() }
        ?.absolutePath

internal fun runCommand(vararg command: String) {
    val result = ProcessBuilder(*command).inheritIO().start().waitFor()
    if (result != 0) throw GradleException("Command failed ($result): ${command.joinToString(" ")}")
}

internal fun defaultIconSvg(name: String): String {
    val label = name.trim().firstOrNull()?.uppercaseChar() ?: 'C'
    return """<svg xmlns="http://www.w3.org/2000/svg" width="256" height="256" viewBox="0 0 256 256">
<rect width="256" height="256" rx="52" fill="#4051b5"/>
<text x="128" y="164" text-anchor="middle" font-family="sans-serif" font-size="132" font-weight="600" fill="white">$label</text>
</svg>
"""
}

internal fun String.fileSafe(): String =
    trim().ifBlank { "application" }.replace(Regex("[^A-Za-z0-9._-]"), "-")
