package dev.brahmkshatriya.compose

import java.io.File
import org.gradle.api.Action
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType

/**
 * Boundary for KGP model access whose JVM signatures vary between supported Kotlin versions. Keep
 * reflective target/binary access here so conventions do not depend on KGP implementation classes.
 */
@Suppress("UNCHECKED_CAST")
internal fun Project.kotlinNativeTargets(): NamedDomainObjectContainer<Any> =
    extensions.getByName("kotlin").let { kotlin ->
        kotlin.javaClass.methods
            .first { it.name == "getTargets" && it.parameterCount == 0 }
            .invoke(kotlin) as NamedDomainObjectContainer<Any>
    }

@Suppress("UNCHECKED_CAST")
internal fun Project.kotlinSourceSets(): NamedDomainObjectContainer<Any> =
    extensions.getByName("kotlin").let { kotlin ->
        kotlin.javaClass.methods
            .first { it.name == "getSourceSets" && it.parameterCount == 0 }
            .invoke(kotlin) as NamedDomainObjectContainer<Any>
    }

internal fun Any.nativeBinaries(): Any =
    javaClass.methods.first { it.name == "getBinaries" && it.parameterCount == 0 }.invoke(this)

internal fun Any.isNativeExecutable(): Boolean =
    javaClass.methods.any {
        it.name == "setEntryPoint" &&
            it.parameterCount == 1 &&
            it.parameterTypes.single() == String::class.java
    }

internal fun Any.setNativeEntryPoint(entryPoint: String) {
    javaClass.methods
        .first {
            it.name == "setEntryPoint" &&
                it.parameterCount == 1 &&
                it.parameterTypes.single() == String::class.java
        }
        .invoke(this, entryPoint)
}

internal fun Any.addNativeLinkerOptions(options: List<String>) {
    if (options.isEmpty()) return
    javaClass.methods
        .firstOrNull {
            it.name == "linkerOpts" &&
                it.parameterCount == 1 &&
                it.parameterTypes.single() == Iterable::class.java
        }
        ?.invoke(this, options)
}

internal fun Any.dependsOnSourceSet(sourceSet: Any) {
    javaClass.methods
        .single { it.name == "dependsOn" && it.parameterCount == 1 }
        .invoke(this, sourceSet)
}

internal fun Any.addKotlinSourceDirectory(directory: File) {
    val sources =
        javaClass.methods.single { it.name == "getKotlin" && it.parameterCount == 0 }.invoke(this)
    sources.javaClass.methods
        .first { it.name == "srcDir" && it.parameterCount == 1 }
        .invoke(sources, directory)
}

internal fun Project.createKotlinNativeTarget(name: String) {
    val kotlin = extensions.getByName("kotlin")
    kotlin.javaClass.methods.single { it.name == name && it.parameterCount == 0 }.invoke(kotlin)
}

internal fun Any.nativeBuildType(): NativeBuildType? =
    javaClass.methods
        .firstOrNull { it.name == "getBuildType" && it.parameterCount == 0 }
        ?.invoke(this) as? NativeBuildType

internal fun Any.createNativeExecutables(buildTypes: Set<NativeBuildType>, action: Action<Any>) {
    javaClass.methods
        .single {
            it.name == "executable" &&
                it.parameterCount == 2 &&
                Collection::class.java.isAssignableFrom(it.parameterTypes[0]) &&
                it.parameterTypes[1] == Action::class.java
        }
        .invoke(this, buildTypes, action)
}
