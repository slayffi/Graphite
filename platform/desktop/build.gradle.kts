import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        optIn.add("androidx.compose.ui.ExperimentalComposeUiApi")
    }
}

val os: String = System.getProperty("os.name").lowercase()

dependencies {
    implementation(project(":ui:app"))
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.jna)
    implementation(libs.jna.platform)
    implementation(libs.zxing.core)
    // Only the native library of the OS that builds the package is bundled.
    when {
        os.contains("win") -> runtimeOnly(libs.libtorrent4j.windows)
        os.contains("mac") -> runtimeOnly(libs.libtorrent4j.macos)
        else -> runtimeOnly(libs.libtorrent4j.linux)
    }
    testImplementation(libs.kotlin.test)
}

val appVersion = "1.0.0"

compose.desktop {
    application {
        mainClass = "com.graphite.torrent.desktop.MainKt"
        jvmArgs += listOf("-Xmx768m", "-Dfile.encoding=UTF-8", "-Dgraphite.version=$appVersion")

        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "Graphite"
            packageVersion = appVersion
            description = "Graphite — fast torrent client"
            vendor = "Graphite"
            copyright = "Graphite contributors"
            modules("java.sql", "java.naming", "java.management", "jdk.unsupported", "jdk.crypto.ec", "java.net.http")
            appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))

            windows {
                iconFile.set(project.file("icons/graphite.ico"))
                menuGroup = "Graphite"
                upgradeUuid = "8f0c2b0e-6b5f-4f3e-9a51-3c1f9d2b7e41"
                perUserInstall = true
                dirChooser = true
                shortcut = true
                menu = true
                fileAssociation(
                    mimeType = "application/x-bittorrent",
                    extension = "torrent",
                    description = "BitTorrent file",
                    iconFile = project.file("icons/graphite.ico"),
                )
            }
            linux {
                iconFile.set(project.file("icons/graphite.png"))
            }
        }

        buildTypes.release.proguard {
            isEnabled.set(false)
        }
    }
}

// libtorrent4j replaces the SIGSEGV handler on Linux; HotSpot needs signal chaining (libjsig) there.
// Windows (the shipped platform) is not affected.
tasks.withType<JavaExec>().configureEach {
    if (os.contains("linux")) {
        doFirst {
            val home = javaLauncher.orNull?.metadata?.installationPath?.asFile ?: File(System.getProperty("java.home"))
            val jsig = File(home, "lib/libjsig.so")
            if (jsig.isFile) environment("LD_PRELOAD", jsig.absolutePath)
        }
    }
}
