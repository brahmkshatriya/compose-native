package dev.brahmkshatriya.compose

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import org.gradle.testkit.runner.GradleRunner

class ComposeNativeSkikoFunctionalTest {
    @Test
    fun alignsStaleTransitiveSkikoOnEveryDesktopNativeTarget() {
        verifySkikoSelection(explicitVersion = null)
    }

    @Test
    fun respectsExplicitDesktopNativeVersionWithoutChangingUpstreamPlatforms() {
        verifySkikoSelection(explicitVersion = "0.153.1")
    }

    private fun verifySkikoSelection(explicitVersion: String?) {
        val projectDir = createTempDirectory("compose-native-skiko-resolution-test").toFile()
        projectDir.deleteOnExit()
        projectDir
            .resolve("gradle.properties")
            .writeText("kotlin.stdlib.default.dependency=false\n")
        val repository = projectDir.resolve("repository")
        val nativeVariants =
            DESKTOP_NATIVE_TARGETS.associate { target ->
                target.sourceSetPrefix to
                    mapOf(
                        "org.gradle.usage" to "kotlin-api",
                        "org.jetbrains.kotlin.platform.type" to "native",
                        "org.jetbrains.kotlin.native.target" to target.konanName,
                    )
            }
        val upstreamVariants =
            nativeVariants +
                mapOf(
                    "jvm" to
                        mapOf(
                            "org.gradle.usage" to "java-api",
                            "org.jetbrains.kotlin.platform.type" to "jvm",
                        ),
                    "iosArm64" to
                        mapOf(
                            "org.gradle.usage" to "kotlin-api",
                            "org.jetbrains.kotlin.platform.type" to "native",
                            "org.jetbrains.kotlin.native.target" to "ios_arm64",
                        ),
                    "wasmJs" to
                        mapOf(
                            "org.gradle.usage" to "kotlin-api",
                            "org.jetbrains.kotlin.platform.type" to "wasm",
                            "org.jetbrains.kotlin.wasm.target" to "js",
                        ),
                )
        // These dependency-only fixtures exercise Gradle's actual variant and version resolver.
        // They do not stand in for compiled Skiko artifacts or enter any production repository.
        publishModule(repository, OFFICIAL_SKIKO_GROUP, "skiko", "0.156.0", upstreamVariants)
        for (version in listOf("0.153.1", "0.156.1")) {
            publishModule(repository, FORK_SKIKO_GROUP, "skiko", version, nativeVariants) { target
                ->
                """{"group":"$FORK_SKIKO_GROUP","module":"skiko-${target.lowercase()}","version":{"requires":"$version"}}"""
            }
            for ((target, attributes) in nativeVariants) {
                publishModule(
                    repository,
                    FORK_SKIKO_GROUP,
                    "skiko-${target.lowercase()}",
                    version,
                    mapOf(target to attributes),
                )
            }
        }
        publishModule(repository, "example", "legacy-wrapper", "1.0", nativeVariants) {
            """{"group":"$FORK_SKIKO_GROUP","module":"skiko","version":{"requires":"0.153.1"}}"""
        }
        projectDir
            .resolve("settings.gradle.kts")
            .writeText(
                """
                pluginManagement { repositories { mavenCentral(); gradlePluginPortal() } }
                dependencyResolutionManagement { repositories { maven { url = uri("repository") } } }
                rootProject.name = "skiko-resolution-test"
                """
                    .trimIndent()
            )
        val explicitDependency =
            explicitVersion?.let { "implementation(\"$FORK_SKIKO_GROUP:skiko:$it\")" }.orEmpty()
        projectDir
            .resolve("build.gradle.kts")
            .writeText(
                """
            plugins {
                kotlin("multiplatform") version "$functionalTestKotlinVersion"
                id("dev.brahmkshatriya.compose")
            }
            kotlin {
                desktopNative()
                jvm()
                iosArm64()
                @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
                wasmJs()
                sourceSets.commonMain.dependencies {
                    implementation("$OFFICIAL_SKIKO_GROUP:skiko:0.156.0")
                }
                sourceSets.desktopNativeMain.dependencies {
                    implementation("example:legacy-wrapper:1.0")
                    $explicitDependency
                }
            }
            tasks.register("verifySkiko") {
                doLast {
                    listOf("linuxX64", "linuxArm64", "mingwX64", "macosX64", "macosArm64", "jvm", "iosArm64", "wasmJs").forEach { target ->
                        val configuration = configurations.findByName(target + "CompileKlibraries")
                            ?: configurations.getByName(target + "CompileClasspath")
                        val result = configuration.incoming.resolutionResult
                        val failures = result.allDependencies.filterIsInstance<org.gradle.api.artifacts.result.UnresolvedDependencyResult>()
                        check(failures.isEmpty()) { failures.map { it.failure.message }.joinToString("\n") }
                        val skiko = result.allComponents.mapNotNull { it.moduleVersion }
                            .filter { it.group.contains("skiko") }.map { it.toString() }.sorted()
                        println("SELECTED " + target + "=" + skiko.joinToString(","))
                    }
                }
            }
            """
                    .trimIndent()
            )
        val result =
            GradleRunner.create()
                .withProjectDir(projectDir)
                .withPluginClasspath()
                .withArguments("verifySkiko", "--no-configuration-cache", "--max-workers=2")
                .build()
        val expectedVersion = explicitVersion ?: "0.156.1"
        for (target in DESKTOP_NATIVE_TARGETS) {
            val expectedModules =
                listOf(
                        "$FORK_SKIKO_GROUP:skiko:$expectedVersion",
                        "$FORK_SKIKO_GROUP:skiko-${target.sourceSetPrefix.lowercase()}:$expectedVersion",
                    )
                    .sorted()
                    .joinToString(",")
            assertContains(result.output, "SELECTED ${target.sourceSetPrefix}=$expectedModules")
        }
        for (target in listOf("jvm", "iosArm64", "wasmJs")) {
            assertContains(result.output, "SELECTED $target=$OFFICIAL_SKIKO_GROUP:skiko:0.156.0")
        }
    }

    private fun publishModule(
        repository: File,
        group: String,
        module: String,
        version: String,
        variants: Map<String, Map<String, String>>,
        dependency: (String) -> String = { "" },
    ) {
        val directory =
            repository.resolve("${group.replace('.', '/')}/$module/$version").apply { mkdirs() }
        directory
            .resolve("$module-$version.pom")
            .writeText(
                """<project><modelVersion>4.0.0</modelVersion><groupId>$group</groupId><artifactId>$module</artifactId><version>$version</version><!-- do_not_remove: published-with-gradle-metadata --></project>"""
            )
        val encodedVariants =
            variants.entries.joinToString(",") { (name, attributes) ->
                val encodedAttributes =
                    attributes.entries.joinToString(",") { (key, value) -> "\"$key\":\"$value\"" }
                """{"name":"${name}ApiElements","attributes":{$encodedAttributes},"dependencies":[${dependency(name)}]}"""
            }
        directory
            .resolve("$module-$version.module")
            .writeText(
                """{"formatVersion":"1.1","component":{"group":"$group","module":"$module","version":"$version"},"variants":[$encodedVariants]}"""
            )
    }
}
