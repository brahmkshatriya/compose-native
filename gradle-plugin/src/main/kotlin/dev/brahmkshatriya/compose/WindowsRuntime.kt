package dev.brahmkshatriya.compose

import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Downloads and unpacks the SDL runtime for Windows")
abstract class PrepareWindowsSdlRuntimeTask : DefaultTask() {
    @get:Input abstract val version: Property<String>
    @get:Input abstract val sha256: Property<String>
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun prepare() {
        val output = outputDirectory.get().asFile
        output.deleteRecursively()
        output.mkdirs()
        val archive = temporaryDir.resolve("SDL3-devel-${version.get()}-mingw.tar.gz")
        val url =
            URI.create(
                "https://github.com/libsdl-org/SDL/releases/download/release-${version.get()}/" +
                    archive.name
            )
        downloadWithRetry(url, archive)
        val actual = sha256(archive)
        check(actual == sha256.get()) {
            "SDL ${version.get()} checksum mismatch: expected ${sha256.get()}, got $actual"
        }
        extractTarGzEntries(
            archive,
            output,
            mapOf(
                "/x86_64-w64-mingw32/bin/SDL3.dll" to "SDL3.dll",
                "/x86_64-w64-mingw32/lib/libSDL3.dll.a" to "libSDL3.dll.a",
                "/x86_64-w64-mingw32/share/licenses/SDL3/LICENSE.txt" to "SDL3-LICENSE.txt",
            ),
        )
        check(output.resolve("SDL3.dll").isFile) { "SDL3.dll was not found in ${archive.name}" }
        check(output.resolve("libSDL3.dll.a").isFile) {
            "libSDL3.dll.a was not found in ${archive.name}"
        }
    }
}

@DisableCachingByDefault(
    because = "Copies runtime DLLs from the Kotlin/Native MinGW toolchain after it is downloaded"
)
abstract class PrepareWindowsCxxRuntimeTask : DefaultTask() {
    @get:Input abstract val konanDataDirectory: Property<String>
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun prepare() {
        val output = outputDirectory.get().asFile
        output.deleteRecursively()
        output.mkdirs()

        val runtimeFiles = windowsCxxRuntimeFiles(File(konanDataDirectory.get()))
        val available = runtimeFiles.map(File::getName).toSet()
        val missing = WINDOWS_CXX_RUNTIME_NAMES.filterNot(available::contains)
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Could not locate Kotlin/Native MinGW runtime DLLs: " +
                    missing.joinToString() +
                    ". Expected them under ${konanDataDirectory.get()}."
            )
        }
        runtimeFiles.forEach { source ->
            source.copyTo(output.resolve(source.name), overwrite = true)
        }
    }
}

internal fun windowsCxxRuntimeFiles(konanRoot: File): List<File> {
    val bin = File(konanRoot, "dependencies/msys2-mingw-w64-x86_64-2/bin")
    return WINDOWS_CXX_RUNTIME_NAMES.map(bin::resolve).filter(File::isFile)
}

private val WINDOWS_CXX_RUNTIME_NAMES =
    listOf("libstdc++-6.dll", "libgcc_s_seh-1.dll", "libwinpthread-1.dll")

private fun downloadWithRetry(uri: URI, destination: File) {
    var lastFailure: Exception? = null
    repeat(3) { attempt ->
        try {
            val connection =
                uri.toURL().openConnection().apply {
                    connectTimeout = 15_000
                    readTimeout = 60_000
                }
            connection.getInputStream().use { input ->
                destination.outputStream().use(input::copyTo)
            }
            return
        } catch (failure: Exception) {
            destination.delete()
            lastFailure = failure
            if (attempt < 2) Thread.sleep((attempt + 1) * 1_000L)
        }
    }
    throw GradleException("Could not download $uri", lastFailure)
}

internal fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun extractTarGzEntries(archive: File, output: File, entries: Map<String, String>) {
    GZIPInputStream(archive.inputStream().buffered()).use { input ->
        val header = ByteArray(512)
        while (true) {
            if (!input.readFully(header)) break
            if (header.all { it == 0.toByte() }) break
            val name = header.copyOfRange(0, 100).toTarString()
            val size = header.copyOfRange(124, 136).toTarString().trim().ifBlank { "0" }.toLong(8)
            val outputName = entries.entries.firstOrNull { name.endsWith(it.key) }?.value
            if (outputName != null) {
                output.resolve(outputName).outputStream().use { sink ->
                    input.copyExactlyTo(sink, size)
                }
            } else {
                input.skipExactly(size)
            }
            input.skipExactly((512 - (size % 512)) % 512)
        }
    }
}

private fun ByteArray.toTarString(): String =
    takeWhile { it != 0.toByte() }.toByteArray().toString(Charsets.UTF_8).trim()

private fun java.io.InputStream.readFully(buffer: ByteArray): Boolean {
    var offset = 0
    while (offset < buffer.size) {
        val count = read(buffer, offset, buffer.size - offset)
        if (count < 0) return offset != 0
        offset += count
    }
    return true
}

private fun java.io.InputStream.copyExactlyTo(output: java.io.OutputStream, byteCount: Long) {
    var remaining = byteCount
    val buffer = ByteArray(64 * 1024)
    while (remaining > 0) {
        val count = read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
        if (count < 0) throw java.io.EOFException("Unexpected end of tar archive")
        output.write(buffer, 0, count)
        remaining -= count
    }
}

private fun java.io.InputStream.skipExactly(byteCount: Long) {
    var remaining = byteCount
    while (remaining > 0) {
        val skipped = skip(remaining)
        if (skipped > 0) {
            remaining -= skipped
        } else if (read() >= 0) {
            remaining--
        } else {
            throw java.io.EOFException("Unexpected end of tar archive")
        }
    }
}
