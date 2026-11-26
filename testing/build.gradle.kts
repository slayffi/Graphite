// Test doubles shared by the data and UI test suites (never shipped in the apps).
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":core"))
}
