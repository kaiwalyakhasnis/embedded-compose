pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "embedded-compose"
include(":core:ui")   // the toolkit (board-independent)
include(":apps:demo") // an app: a static library exporting ec_app_main
