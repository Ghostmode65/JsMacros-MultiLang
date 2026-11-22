import org.gradle.api.tasks.compile.JavaCompile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    java
    kotlin("jvm")
    id("com.gradleup.shadow")
}

val workspace = rootProject.projectDir.parentFile
val coreJar = workspace.resolve("compile-jars/jsmacrosce-1.21.8-fabric-2.0.0-dev-6ad1f0a.jar")
val luajVersion = rootProject.property("luaj_version") as String
val jrubyVersion = rootProject.property("jruby_version") as String
val graalVersion = rootProject.property("graal_version") as String
val generatedJava = layout.buildDirectory.dir("generated/shared-sources/java")
val generatedKotlin = layout.buildDirectory.dir("generated/shared-sources/kotlin")

base { archivesName.set("jsmacros-multilang-adapter-community") }

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}
kotlin {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
}

sourceSets.named("main") {
    java.srcDir(generatedJava)
    kotlin.srcDirs("src/main/kotlin", generatedKotlin)
}
tasks.named("compileJava") { dependsOn("generateSharedSources") }
tasks.named("compileKotlin") { dependsOn("generateSharedSources") }

dependencies {
    compileOnly(project(":"))
    compileOnly(files(coreJar))
    compileOnly("com.github.wagyourtail.luaj:luaj-jse:$luajVersion")
    compileOnly("io.github.kienpc1234:luava:${rootProject.property("luava_version")}")
    compileOnly("org.jruby:jruby-complete:$jrubyVersion")
    compileOnly("org.graalvm.polyglot:polyglot:$graalVersion")
    compileOnly("org.python:jython-standalone:${rootProject.property("jython_version")}")
    compileOnly("com.google.guava:guava:31.1-jre")
    compileOnly("com.google.code.gson:gson:2.10")
    compileOnly("org.slf4j:slf4j-api:2.0.16")
    compileOnly("it.unimi.dsi:fastutil:8.5.15")
}

tasks.processResources {
    from(workspace.resolve("Languges/Lua/community/src/main/resources")) {
        exclude("META-INF/services/**")
        exclude("jsmacrosce.ext.luaj.json")
        exclude("assets/jsmacrosce/luaj/lang/en_us.json")
    }
}

