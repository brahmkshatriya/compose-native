@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package androidx.compose.demo.windows

import dev.demo.main as runCatalogue
import platform.posix._putenv_s
import platform.posix.getenv

/** Starts the shared desktop catalogue with the OpenGL backend required by its interop pages. */
fun main() {
    if (getenv("SKIKO_RENDER_API") == null) {
        check(_putenv_s("SKIKO_RENDER_API", "OPENGL") == 0) {
            "Could not select OpenGL for the native-view catalogue"
        }
    }
    runCatalogue()
}
