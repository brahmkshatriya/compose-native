import java.util.Properties
import org.gradle.plugin.devel.tasks.PluginUnderTestMetadata

plugins {
    kotlin("jvm") version "2.3.20"
    `java-gradle-plugin`
    id("com.vanniktech.maven.publish") version "0.36.0"
}

group = "dev.brahmkshatriya.compose"
version = "1.13.0-alpha06"

kotlin {
    jvmToolchain(21)
}

val composeNativeUpstreamVersionsDir =
    layout.buildDirectory.dir("generated/composeNativeUpstreamVersions")
val generateComposeNativeUpstreamVersions =
    tasks.register("generateComposeNativeUpstreamVersions") {
        val upstreamPropertiesFile = layout.projectDirectory.file("../gradle.properties")
        val redirectVersionsFile = layout.projectDirectory.file("../redirectversions.toml")

        inputs.file(upstreamPropertiesFile)
        inputs.file(redirectVersionsFile)
        outputs.dir(composeNativeUpstreamVersionsDir)

        doLast {
            val upstream = Properties().apply {
                upstreamPropertiesFile.asFile.inputStream().use(::load)
            }
            fun upstreamVersion(name: String): String =
                requireNotNull(upstream.getProperty("compose.native.upstream.version.$name")) {
                    "Missing compose.native.upstream.version.$name in ${upstreamPropertiesFile.asFile}"
                }
            val redirectVersions =
                Regex("""^\"([^\"]+)\"\s*=\s*\"([^\"]+)\"\s*$""")
                    .let { pattern ->
                        redirectVersionsFile.asFile.readLines().mapNotNull { line ->
                            pattern.matchEntire(line.trim())?.destructured?.let { (group, version) ->
                                group to version
                            }
                        }
                    }
                    .toMap()
            val values =
                linkedMapOf(
                    "compose" to upstreamVersion("COMPOSE"),
                    "compose.material3" to upstreamVersion("COMPOSE_MATERIAL3"),
                    "compose.material3.adaptive" to upstreamVersion("COMPOSE_MATERIAL3_ADAPTIVE"),
                ) + redirectVersions
            val output =
                composeNativeUpstreamVersionsDir.get().file("compose-native-upstream.properties").asFile
            output.parentFile.mkdirs()
            output.writeText(
                values.entries
                    .sortedBy { it.key }
                    .joinToString(separator = "\n", postfix = "\n") { (key, value) ->
                        "$key=$value"
                    }
            )
        }
    }

sourceSets.named("main") {
    resources.srcDir(composeNativeUpstreamVersionsDir)
}
tasks.named("processResources") {
    dependsOn(generateComposeNativeUpstreamVersions)
}
tasks.configureEach {
    if (name == "sourcesJar") {
        dependsOn(generateComposeNativeUpstreamVersions)
    }
}

val kotlinGradlePluginApiForTests by configurations.creating

dependencies {
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.20")
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin-idea:2.3.20")
    kotlinGradlePluginApiForTests("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.20")
    kotlinGradlePluginApiForTests("org.jetbrains.kotlin:kotlin-gradle-plugin-idea:2.3.20")
    kotlinGradlePluginApiForTests("org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.3.20")
    kotlinGradlePluginApiForTests("org.jetbrains.compose:compose-gradle-plugin:1.13.0-alpha01")

    testImplementation(kotlin("test"))
    testImplementation(gradleTestKit())
}

tasks.named<PluginUnderTestMetadata>("pluginUnderTestMetadata") {
    pluginClasspath.from(kotlinGradlePluginApiForTests)
}

gradlePlugin {
    plugins {
        create("composeNative") {
            id = "dev.brahmkshatriya.compose"
            implementationClass = "dev.brahmkshatriya.compose.ComposeNativePlugin"
            displayName = "Compose Native"
            description = "Adds desktop Kotlin/Native targets and application conventions to Compose Multiplatform"
        }
    }
}

mavenPublishing {
    publishToMavenCentral()
    if (
        providers.gradleProperty("signingInMemoryKey").isPresent ||
            providers.gradleProperty("signing.secretKeyRingFile").isPresent
    ) {
        signAllPublications()
    }
    coordinates(group.toString(), "compose-gradle-plugin", version.toString())
    pom {
        name = "Compose Native Gradle Plugin"
        description = "Adds desktop Kotlin/Native targets and application conventions to Compose Multiplatform"
        inceptionYear = "2026"
        url = "https://github.com/brahmkshatriya/compose-native"
        licenses {
            license {
                name = "The Apache License, Version 2.0"
                url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                distribution = "repo"
            }
        }
        developers {
            developer {
                id = "brahmkshatriya"
                name = "Shivam Brahmkshatriya"
                url = "https://github.com/brahmkshatriya"
            }
        }
        scm {
            url = "https://github.com/brahmkshatriya/compose-native"
            connection = "scm:git:https://github.com/brahmkshatriya/compose-native.git"
            developerConnection = "scm:git:ssh://git@github.com/brahmkshatriya/compose-native.git"
        }
    }
}
