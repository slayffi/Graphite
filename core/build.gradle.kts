plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)

    android {
        namespace = "com.graphite.torrent.core"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    jvm("desktop")

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
        optIn.add("kotlin.time.ExperimentalTime")
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.libtorrent4j)
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
        }
        val desktopTest by getting {
            dependencies {
                runtimeOnly(libs.libtorrent4j.linux)
                runtimeOnly(libs.libtorrent4j.windows)
                runtimeOnly(libs.libtorrent4j.macos)
            }
        }
    }
}

tasks.withType<Test>().configureEach {
    maxHeapSize = "1g"
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
tasks.withType<Test>().configureEach {
    // libtorrent4j's native library installs its own SIGSEGV handler on Linux/macOS, replacing the one
    // HotSpot relies on for implicit null checks. libjsig chains signals so the JVM keeps working.
    val os = System.getProperty("os.name").lowercase()
    if (os.contains("linux")) {
        doFirst {
            val jsig = javaLauncher.get().metadata.installationPath.file("lib/libjsig.so").asFile
            if (jsig.isFile) environment("LD_PRELOAD", jsig.absolutePath)
        }
    }
}
