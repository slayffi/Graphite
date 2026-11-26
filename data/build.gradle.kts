plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sqldelight)
}

kotlin {
    jvmToolchain(21)

    android {
        namespace = "com.graphite.torrent.data"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    jvm("desktop")

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
            api(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines)
            api(libs.datastore.core.okio)
            api(libs.okio)
        }
        androidMain.dependencies {
            implementation(libs.sqldelight.android.driver)
        }
        val desktopMain by getting {
            dependencies {
                implementation(libs.sqldelight.sqlite.driver)
            }
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
        }
        val desktopTest by getting {
            dependencies {
                implementation(project(":testing"))
                implementation(libs.sqldelight.sqlite.driver)
                runtimeOnly(libs.libtorrent4j.linux)
                runtimeOnly(libs.libtorrent4j.windows)
                runtimeOnly(libs.libtorrent4j.macos)
            }
        }
    }
}

sqldelight {
    databases {
        create("GraphiteDatabase") {
            packageName.set("com.graphite.torrent.data.db")
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
    // See core/build.gradle.kts: libtorrent4j replaces the SIGSEGV handler on Linux; chain it through libjsig.
    if (System.getProperty("os.name").lowercase().contains("linux")) {
        doFirst {
            val jsig = javaLauncher.get().metadata.installationPath.file("lib/libjsig.so").asFile
            if (jsig.isFile) environment("LD_PRELOAD", jsig.absolutePath)
        }
    }
}
