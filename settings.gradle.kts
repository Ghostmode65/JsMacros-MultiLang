pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "jsmacros-multilang"

listOf("legacy", "community", "reloaded").forEach { target ->
    include(target)
    project(":$target").projectDir = file("src/$target")
}
