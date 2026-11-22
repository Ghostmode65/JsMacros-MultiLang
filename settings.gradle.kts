pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "jsmacros-multilang"

include("legacy", "community", "reloaded")
