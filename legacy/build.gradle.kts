import org.gradle.api.tasks.compile.JavaCompile

plugins {
    java
    id("com.gradleup.shadow")
}

val workspace = rootProject.projectDir.parentFile
val luajVersion = rootProject.property("luaj_version") as String
val jrubyVersion = rootProject.property("jruby_legacy_version") as String
val jythonVersion = rootProject.property("jython_version") as String

base { archivesName.set("jsmacros-multilang-adapter-legacy") }

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(17)
}

sourceSets.named("main") {
    java.srcDir(layout.buildDirectory.dir("generated/shared-sources/java"))
}
tasks.named("compileJava") { dependsOn("generateSharedSources") }

dependencies {
    compileOnly(project(":"))
    compileOnly("com.github.jsmacros.jsmacros:jsmacros-1.19:f59d4b875d")
    compileOnly("com.google.guava:guava:31.1-jre")
    compileOnly("org.slf4j:slf4j-api:2.0.0-alpha7")
    compileOnly("com.google.code.gson:gson:2.9.0")
    compileOnly("org.jetbrains:annotations:16.0.2")
    compileOnly("javassist:javassist:3.12.1.GA")
    compileOnly("com.github.wagyourtail.luaj:luaj-jse:$luajVersion")
    compileOnly("io.github.kienpc1234:luava:${rootProject.property("luava_version")}")
    compileOnly("org.python:jython-standalone:$jythonVersion")
    compileOnly("org.graalvm.polyglot:polyglot:${rootProject.property("graal_version")}")
    compileOnly("org.jruby:jruby-complete:$jrubyVersion")
}

tasks.processResources {
    from(workspace.resolve("Languges/Jython-legacy/src/main/resources")) {
        exclude("META-INF/services/**")
    }
    from(workspace.resolve("Languges/Ruby Legacy/src/main/resources")) {
        exclude("META-INF/services/**")
    }
}
