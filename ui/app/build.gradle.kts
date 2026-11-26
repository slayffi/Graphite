plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)

    android {
        namespace = "com.graphite.torrent.ui.app"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        androidResources { enable = true }
    }

    jvm("desktop")

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
        optIn.add("androidx.compose.material3.ExperimentalMaterial3Api")
        optIn.add("androidx.compose.foundation.ExperimentalFoundationApi")
        optIn.add("androidx.compose.ui.ExperimentalComposeUiApi")
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":ui:designsystem"))
            api(project(":data"))
            api(libs.jetbrains.lifecycle.viewmodel.compose)
            api(libs.jetbrains.lifecycle.runtime.compose)
            implementation(libs.zxing.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        val desktopTest by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.compose.ui.test)
                implementation(libs.sqldelight.sqlite.driver)
                runtimeOnly(libs.libtorrent4j.linux)
                runtimeOnly(libs.libtorrent4j.windows)
            }
        }
    }
}

compose.resources {
    publicResClass = true
    packageOfResClass = "com.graphite.torrent.ui.app.resources"
    generateResClass = always
}

tasks.withType<Test>().configureEach {
    maxHeapSize = "1g"
    if (System.getProperty("os.name").lowercase().contains("linux")) {
        doFirst {
            val jsig = javaLauncher.get().metadata.installationPath.file("lib/libjsig.so").asFile
            if (jsig.isFile) environment("LD_PRELOAD", jsig.absolutePath)
        }
    }
}
