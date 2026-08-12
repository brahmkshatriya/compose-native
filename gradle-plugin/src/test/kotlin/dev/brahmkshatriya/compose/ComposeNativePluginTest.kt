package dev.brahmkshatriya.compose

import kotlin.test.Test
import kotlin.test.assertNull
import org.gradle.testfixtures.ProjectBuilder

class ComposeNativePluginTest {
    @Test
    fun doesNotRegisterAProjectExtension() {
        val project = ProjectBuilder.builder().build()

        ComposeNativePlugin().apply(project)

        assertNull(project.extensions.findByName("composeNative"))
        assertNull(project.extensions.findByName("brahmkshatriyaCompose"))
    }
}
