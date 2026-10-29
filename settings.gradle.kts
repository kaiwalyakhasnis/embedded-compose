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
include(":core:led")  // ui.led: parts for small LED panels, for the apps that want them
include(":core:material") // ui.material: Material 3 text, colors and components, for the apps that want them
include(":apps:demo") // an app: a static library exporting ec_app_main
include(":apps:charger") // the off-mode charging screen (APP=charger)
include(":apps:matrix")  // for LED matrices (APP=matrix; rpi-hub75, rpi-max7219, qemu-rpi3)
// An app outside this repo (APP_DIR=<dir> ./ec build ...), named after its directory.
System.getenv("APP_DIR")?.let { dir ->
    val name = file(dir).name
    include(":apps:$name")
    project(":apps:$name").projectDir = file(dir)
}
// Libraries outside this repo that such an app uses (its app.env: APP_LIBS="<name>=<dir> ..."),
// as :libs:<name>. They build against the modules here like the in-repo ones.
System.getenv("APP_LIBS")?.split(' ')?.filter { it.isNotBlank() }?.forEach { lib ->
    val name = lib.substringBefore('=')
    include(":libs:$name")
    project(":libs:$name").projectDir = file(lib.substringAfter('='))
}
