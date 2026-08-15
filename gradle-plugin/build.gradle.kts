import org.gradle.plugin.devel.tasks.PluginUnderTestMetadata

plugins {
    kotlin("jvm") version "2.3.20"
    `java-gradle-plugin`
    id("com.vanniktech.maven.publish") version "0.36.0"
}

group = "dev.brahmkshatriya.compose"
version = "1.12.10-alpha07"

kotlin {
    jvmToolchain(21)
}

val kotlinGradlePluginApiForTests by configurations.creating

dependencies {
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.20")
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin-idea:2.3.20")
    kotlinGradlePluginApiForTests("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.20")
    kotlinGradlePluginApiForTests("org.jetbrains.kotlin:kotlin-gradle-plugin-idea:2.3.20")

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
