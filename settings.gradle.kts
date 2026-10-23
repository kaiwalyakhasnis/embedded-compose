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
include(":apps:charger") // the off-mode charging screen (APP=charger)
include(":apps:matrix")  // for LED matrices (APP=matrix; rpi-hub75, rpi-max7219, qemu-rpi3)
// An app outside this repo (APP_DIR=<dir> ./ec build ...), named after its directory.
System.getenv("APP_DIR")?.let { dir ->
    val name = file(dir).name
    include(":apps:$name")
    project(":apps:$name").projectDir = file(dir)
}
