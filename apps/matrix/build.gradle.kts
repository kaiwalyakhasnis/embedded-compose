// LED-matrix app (64x32 and up), built as a static library (libapp.a) whose only C export is
// ec_app_main(); backends/Makefile links it with a backend into one executable.
// (Public @Composable functions can't be exported to a C header, which is why
// the toolkit lives in :core:ui and the app's own composables are internal.)
plugins {
    kotlin("multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    linuxArm64 {
        binaries.staticLib {
            baseName = "app"
        }
    }
    sourceSets {
        val linuxArm64Main by getting {
            dependencies {
                implementation(project(":core:ui"))
            }
        }
        all {
            languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi")
        }
    }
}
