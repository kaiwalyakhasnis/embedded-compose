// ui.led: parts for small LED panels (Marquee, icons, a 3x5 font, bars, ...),
// on top of the toolkit (:core:ui). Apps that want them add
//     implementation(project(":core:led"))
// next to :core:ui; the others don't compile them.
plugins {
    kotlin("multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    linuxArm64()
    sourceSets {
        val linuxArm64Main by getting {
            dependencies {
                api(project(":core:ui"))
            }
        }
    }
}
