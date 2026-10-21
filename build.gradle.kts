plugins {
    kotlin("multiplatform") version "2.2.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21" apply false
}

// All build output under out/, next to the boards' images.
allprojects {
    layout.buildDirectory.set(rootDir.resolve("out/gradle" + path.replace(':', '/')))
}
