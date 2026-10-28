package dev.brahmkshatriya.compose

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.gradle.testfixtures.ProjectBuilder

class ComposeNativePluginTest {
    @Test
    fun enablesRequestedCoordinateMatchingOnlyWhenKotlinNeedsTheFlag() {
        assertEquals(true, null.requiresRequestedCoordinateMatchingFlag())
        assertEquals(true, "2.3.20-release-208".requiresRequestedCoordinateMatchingFlag())
        assertEquals(false, "2.4.0-release-281".requiresRequestedCoordinateMatchingFlag())
        assertEquals(false, "3.0.0".requiresRequestedCoordinateMatchingFlag())
    }

    @Test
    fun doesNotRegisterAProjectExtension() {
        val project = ProjectBuilder.builder().build()

        ComposeNativePlugin().apply(project)

        assertNull(project.extensions.findByName("composeNative"))
        assertNull(project.extensions.findByName("brahmkshatriyaCompose"))
    }

    @Test
    fun identifiesAProjectThatDirectlyConsumesTheComposeNativeProject() {
        val root = ProjectBuilder.builder().withName("root").build()
        val producer = ProjectBuilder.builder().withName("app").withParent(root).build()
        val consumer = ProjectBuilder.builder().withName("android").withParent(root).build()
        val unrelated = ProjectBuilder.builder().withName("unrelated").withParent(root).build()
        consumer.configurations
            .create("implementation")
            .dependencies
            .add(consumer.dependencies.project(mapOf("path" to producer.path)))

        assertEquals(true, consumer.directlyDependsOn(producer))
        assertEquals(false, unrelated.directlyDependsOn(producer))
    }
}
