@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.jetbrains.compose.resources

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.toKString
import platform.Foundation.NSBundle
import platform.posix.F_OK
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.access
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.getcwd
import platform.posix.getenv

@ExperimentalResourceApi
internal actual fun getPlatformResourceReader(): ResourceReader = MacosResourceReader

@ExperimentalResourceApi
private object MacosResourceReader : ResourceReader {
    private val root: String by lazy(::findResourceRoot)

    override suspend fun read(path: String): ByteArray = readFile(resolve(path), 0, null)

    override suspend fun readPart(path: String, offset: Long, size: Long): ByteArray =
        readFile(resolve(path), offset, size)

    override fun getUri(path: String): String = "file://${resolve(path)}"

    private fun resolve(path: String): String = "${root.trimEnd('/')}/${path.trimStart('/')}"
}

private fun findResourceRoot(): String {
    val explicit =
        sequenceOf("COMPOSE_RESOURCE_ROOT", "KTNATIVE_RESOURCE_ROOT")
            .mapNotNull { key -> getenv(key)?.toKString()?.takeIf(String::isNotBlank) }
            .firstOrNull()
    if (explicit != null) return explicit

    val executableDirectory = NSBundle.mainBundle.executablePath?.substringBeforeLast('/', "")
    val bundleResourcePath = NSBundle.mainBundle.resourcePath
    val workingDirectory = currentWorkingDirectory()
    val candidates = buildList {
        if (!bundleResourcePath.isNullOrBlank()) {
            add("$bundleResourcePath/compose-resources")
            add(bundleResourcePath)
        }
        if (!executableDirectory.isNullOrBlank()) {
            add("$executableDirectory/resources")
            add("$executableDirectory/compose-resources")
            add("$executableDirectory/../Resources/compose-resources")
        }
        if (workingDirectory.isNotBlank()) {
            add("$workingDirectory/resources")
            add("$workingDirectory/compose-resources")
        }
    }
    return candidates.firstOrNull(::exists) ?: candidates.firstOrNull() ?: "compose-resources"
}

private fun currentWorkingDirectory(): String = memScoped {
    val buffer = allocArray<ByteVar>(4096)
    getcwd(buffer, 4096.convert())?.toKString().orEmpty()
}

private fun exists(path: String): Boolean = access(path, F_OK) == 0

private fun readFile(path: String, offset: Long, requestedSize: Long?): ByteArray {
    val file = fopen(path, "rb") ?: throw MissingResourceException(path)
    try {
        if (fseek(file, 0, SEEK_END) != 0) throw MissingResourceException(path, "Could not seek")
        val total = ftell(file)
        if (total < 0) throw MissingResourceException(path, "Could not determine size")
        val safeOffset = offset.coerceIn(0, total)
        val available = total - safeOffset
        val size = (requestedSize ?: available).coerceIn(0, available)
        if (size > Int.MAX_VALUE) throw MissingResourceException(path, "Resource is too large")
        if (fseek(file, safeOffset, SEEK_SET) != 0) throw MissingResourceException(path, "Could not seek")
        if (size == 0L) return ByteArray(0)
        return memScoped {
            val buffer = allocArray<ByteVar>(size.toInt())
            val read = fread(buffer, 1.convert(), size.convert(), file).toLong()
            if (read != size) throw MissingResourceException(path, "Expected $size bytes, read $read")
            buffer.readBytes(size.toInt())
        }
    } finally {
        fclose(file)
    }
}
