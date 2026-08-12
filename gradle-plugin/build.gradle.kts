plugins {
    kotlin("jvm") version "2.3.20"
    `java-gradle-plugin`
    id("com.vanniktech.maven.publish") version "0.36.0"
}

group = "dev.brahmkshatriya.compose"
version = "1.12.10-alpha04"

kotlin {
    jvmToolchain(21)
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation(gradleTestKit())
}

gradlePlugin {
    plugins {
        create("composeNative") {
            id = "dev.brahmkshatriya.compose"
            implementationClass = "dev.brahmkshatriya.compose.ComposeNativePlugin"
            displayName = "Compose Native"
            description = "Adds grouped desktop Kotlin/Native targets to Compose Multiplatform"
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
        description = "Adds grouped desktop Kotlin/Native targets to Compose Multiplatform"
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
