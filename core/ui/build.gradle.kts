// The UI toolkit: Compose runtime + LayoutNode tree + modifiers + shapes.
// Board- and backend-independent: it only calls core/hal/ec_hal.h.
plugins {
    kotlin("multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    linuxArm64 {
        compilations.getByName("main") {
            cinterops.create("hal") {
                definitionFile.set(project.file("src/nativeInterop/cinterop/hal.def"))
                includeDirs(rootProject.file("core/hal"))
            }
        }
    }
    sourceSets {
        val linuxArm64Main by getting {
            dependencies {
                api("androidx.compose.runtime:runtime:1.10.5")
                api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            }
        }
        all {
            languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi")
        }
    }
}
