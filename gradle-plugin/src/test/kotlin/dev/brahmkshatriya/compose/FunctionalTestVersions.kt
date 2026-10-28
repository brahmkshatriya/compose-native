package dev.brahmkshatriya.compose

/** Matches the KGP implementation injected by TestKit, rather than only its requested plugin ID. */
internal val functionalTestKotlinVersion: String =
    System.getProperty("composeNative.test.kotlinVersion", "2.4.21")
