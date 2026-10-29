// ui.material: Material 3 for the toolkit (:core:ui): Roboto drawn from its
// TrueType outlines with anti-aliasing, Material's color roles and type
// scale, and its components (buttons, cards, chips, checkbox, slider, tabs,
// top app bar, progress). Apps that want it add
//     implementation(project(":core:material"))
// next to :core:ui; the others don't compile it. The fonts go on the
// device: /res/fonts/Roboto-{Regular,Medium}.ttf (EC_FONT_DIR).
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
        all {
            languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi")
        }
    }
}
