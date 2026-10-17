import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.tasks.Sync
import org.gradle.jvm.tasks.Jar
import org.gradle.api.tasks.compile.JavaCompile

plugins {
    java
    kotlin("jvm") version "2.4.0" apply false
    id("com.gradleup.shadow") version "9.4.2"
}

group = property("maven_group") as String
version = property("mod_version") as String

allprojects {
    group = rootProject.group
    version = rootProject.version

    tasks.withType<Jar>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
        duplicatesStrategy = DuplicatesStrategy.FAIL
    }

    repositories {
        mavenCentral()
        maven("https://maven.fabricmc.net")
        maven("https://jitpack.io")
        maven("https://maven.wagyourtail.xyz/releases")
        maven("https://maven.wagyourtail.xyz/snapshots")
    }
}

base { archivesName.set("jsmacros-multilang") }
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(17)
}
sourceSets.named("main") {
    java.setSrcDirs(listOf(file("shared/src/main/java")))
}
val embeddedRuntimes = configurations.create("embeddedRuntimes") { isTransitive = false }
val embeddedGraalRuntimes = configurations.create("embeddedGraalRuntimes")
val legacyRubyRuntime = configurations.create("legacyRubyRuntime") { isTransitive = false }
val languageMixinTestRuntime = configurations.create("languageMixinTestRuntime") { isTransitive = false }
dependencies {
    languageMixinTestRuntime("net.fabricmc:sponge-mixin:0.15.5+mixin.0.8.7")
    listOf("asm", "asm-tree", "asm-commons", "asm-util", "asm-analysis").forEach {
        languageMixinTestRuntime("org.ow2.asm:$it:9.9")
    }
    languageMixinTestRuntime("com.google.code.gson:gson:2.10")
    languageMixinTestRuntime("com.google.guava:guava:31.1-jre")
}
val compileLanguageMixinTests = tasks.register<JavaCompile>("compileLanguageMixinTests") {
    source(fileTree("shared/tests/mixin") { include("**/*.java") })
    classpath = languageMixinTestRuntime
    destinationDirectory.set(layout.buildDirectory.dir("language-mixin-tests"))
    options.release.set(17)
    options.compilerArgs.add("-proc:none")
}
dependencies {
    compileOnly("com.github.wagyourtail.luaj:luaj-jse:${property("luaj_version")}")
    compileOnly("io.github.kienpc1234:luava:${property("luava_version")}")
    compileOnly("org.graalvm.polyglot:polyglot:${property("graal_version")}")
    embeddedRuntimes("com.github.wagyourtail.luaj:luaj-jse:${property("luaj_version")}")
    embeddedRuntimes("io.github.kienpc1234:luava:${property("luava_version")}")
    embeddedRuntimes("org.jruby:jruby-complete:${property("jruby_version")}")
    embeddedRuntimes("org.python:jython-standalone:${property("jython_version")}")
    embeddedRuntimes("org.jetbrains.kotlin:kotlin-stdlib:2.4.0")
    embeddedGraalRuntimes("org.graalvm.polyglot:python-community:${property("graal_version")}")
    embeddedGraalRuntimes("org.graalvm.polyglot:llvm-community:${property("graal_version")}")
    embeddedGraalRuntimes("org.graalvm.tools:chromeinspector-tool:${property("graal_version")}")
    legacyRubyRuntime("org.jruby:jruby-complete:${property("jruby_legacy_version")}")
}

listOf("legacy", "community", "reloaded").forEach { evaluationDependsOn(":$it") }

val sharedSourceValues = mapOf(
    "legacy" to mapOf(
        "CORE_PACKAGE" to "xyz.wagyourtail.jsmacros",
        "CORE_SETTINGS_LANGUAGES" to "jsmacros.settings.languages",
        "RUBY_ARITY" to "(int) method.arity().getLongValue()",
        "LUAVA_CONTEXT_SUPER" to "event, file",
        "LUA_RUNNER" to "Core.getInstance()"
    ),
    "community" to mapOf(
        "CORE_PACKAGE" to "com.jsmacrosce.jsmacros",
        "LUA_EXTENSION_NAME" to "jsmacros-LuaCE",
        "LUA_DESCRIPTOR" to "jsmacrosce.ext.luaj.json",
        "LUA_TRANSLATIONS" to "assets/jsmacrosce/luaj/lang/",
        "PYTHON_TRANSLATIONS" to "assets/jsmacrosce/python/lang/",
        "CORE_SETTINGS_LANGUAGES" to "jsmacrosce.settings.languages",
        "LUA_PREFERRED_NAME" to "luace",
        "RUBY_MAX_CORE_VERSION" to "2.1.0",
        "RUBY_ASYNC_BIND" to "ctx.bindThread(Thread.currentThread())",
        "RUBY_BIND" to "ctx.bindThread(Thread.currentThread())"
    ),
    "reloaded" to mapOf(
        "CORE_PACKAGE" to "xyz.wagyourtail.jsmacros",
        "LUA_EXTENSION_NAME" to "jsmacros-LuaReload",
        "LUA_DESCRIPTOR" to "jsmacros.ext.luaj.json",
        "LUA_TRANSLATIONS" to "assets/jsmacros/luaj/lang/",
        "PYTHON_TRANSLATIONS" to "assets/jsmacros/python/lang/",
        "CORE_SETTINGS_LANGUAGES" to "jsmacros.settings.languages",
        "LUA_PREFERRED_NAME" to "luareload",
        "RUBY_MAX_CORE_VERSION" to "3.0.0",
        "RUBY_ASYNC_BIND" to "ctx.bindThread(Thread.currentThread())",
        "RUBY_BIND" to "ctx.bindCallerThread()",
        "LUAVA_CLOSE_METHOD" to "protected void doSubclassClose()",
        "LUAVA_CLOSE_SUPER" to "",
        "LUAVA_BIND_THREAD" to "if (await) ctx.bindCallerThread(); else ctx.bindThread(thread)",
        "PYTHON_CLOSE_METHOD" to "protected void doSubclassClose()",
        "PYTHON_CLOSE_SUPER" to "",
        "PYTHON_BIND_THREAD" to "if (await) ctx.bindCallerThread(); else ctx.bindThread(thread)"
    )
)

sharedSourceValues.forEach { (targetName, values) ->
    val target = project(":$targetName")
    val replacements = mapOf(
        "LUA_RUNNER" to "LuajExtension.RUNNER",
        "LANGUAGE_RUNNER" to if (targetName == "legacy") "Core.getInstance()" else "runner",
        "PYTHON_CONFIG_PACKAGE" to if (targetName == "legacy") "jython" else "graal.python",
        "PYTHON_TOGGLE_METHOD" to if (targetName == "legacy") "graal" else "jython",
        "PYTHON_TOGGLE_FIELD" to if (targetName == "legacy") "useGraalPy" else "useJython",
        "LUAVA_CONTEXT_SUPER" to "runner, event, file",
        "LUAVA_CLOSE_METHOD" to "public void closeContext()",
        "LUAVA_CLOSE_SUPER" to "super.closeContext();",
        "LUAVA_BIND_THREAD" to "ctx.bindThread(thread)",
        "PYTHON_CLOSE_METHOD" to "public void closeContext()",
        "PYTHON_CLOSE_SUPER" to "synchronized (this) { if (closed) return; super.closeContext(); }",
        "PYTHON_BIND_THREAD" to "ctx.bindThread(thread)",
        "RUBY_ARITY" to "(int) method.arity(thread).getValue()"
    ) + values
    target.tasks.register<Sync>("generateSharedSources") {
        from(rootProject.file("shared/templates/java")) {
            into("java")
            if (targetName == "legacy") {
                include("**/LuaMethodWrapper.java", "**/LuajGlobalContextLib.java", "**/LuajConfig.java")
                include("**/RubyExecutionSupport.java")
                include("**/luava/**")
                include("**/multilang/LanguageSettings.java", "**/multilang/mixin/**")
            }
        }
        if (targetName != "legacy") {
            from(rootProject.file("shared/templates/kotlin")) { into("kotlin") }
        }
        into(target.layout.buildDirectory.dir("generated/shared-sources"))
        inputs.properties(replacements)
        filteringCharset = "UTF-8"
        if (targetName != "community") {
            eachFile { path = path.replace("com/jsmacrosce/jsmacros", "xyz/wagyourtail/jsmacros") }
        }
        filter { line: String ->
            replacements.entries
                .fold(line) { result, (key, value) -> result.replace("@$key@", value) }
        }
    }
}

val fabricTargets = mapOf(
    "legacy" to mapOf(
        "title" to "Legacy", "corePackage" to "xyz.wagyourtail.jsmacros",
        "coreId" to "jsmacros", "coreVersion" to ">=1.7.0-0 <2.0.0-0",
        "minecraft" to ">=1.19 <1.22", "java" to "17",
        "jrubyVersion" to property("jruby_legacy_version").toString(),
        "loader" to "0.15.0", "registration" to "services",
        "languages" to "luaj,jython,jruby"
    ),
    "community" to mapOf(
        "title" to "Community", "corePackage" to "com.jsmacrosce.jsmacros",
        "coreId" to "jsmacrosce", "coreVersion" to ">=2.0.0-0 <2.1.0-0",
        "minecraft" to "1.21.8", "java" to "21",
        "jrubyVersion" to property("jruby_version").toString(),
        "loader" to "0.15.0", "registration" to "services",
        "languages" to "luaj,graalpy,jruby"
    ),
    "reloaded" to mapOf(
        "title" to "Reloaded", "corePackage" to "xyz.wagyourtail.jsmacros",
        "coreId" to "jsmacros", "coreVersion" to ">=2.1.0-0 <3.0.0-0",
        "minecraft" to "26.2", "java" to property("java_version").toString(),
        "jrubyVersion" to property("jruby_version").toString(),
        "loader" to "0.19.0", "registration" to "entrypoints",
        "languages" to "luaj,graalpy,jruby"
    )
)

fabricTargets.forEach { (name, settings) ->
    val target = project(":$name")
    target.extensions.extraProperties["fabricSettings"] = settings
    target.apply(mapOf("from" to rootProject.file("shared/gradle/fabric-mod.gradle.kts")))
    target.tasks.named<ShadowJar>("shadowJar") {
        configurations = emptyList()
        relocate("org.luaj", "org.jsmacros.multilang.runtime.luaj")
    }
}

extra["fabricTargets"] = fabricTargets
apply(from = file("shared/gradle/universal-fabric.gradle.kts"))
tasks.named<ShadowJar>("shadowJar") {
    configurations = emptyList()
    relocate("org.luaj", "org.jsmacros.multilang.runtime.luaj")
    mergeServiceFiles()
}

val distribution = tasks.register<Sync>("distribution") {
    group = "distribution"
    description = "Collects the universal Fabric mod with shared engines and three alternative adapters."
    val jarTask = tasks.named<Jar>("shadowJar")
    dependsOn(jarTask)
    from(jarTask.flatMap { it.archiveFile })
    into(layout.buildDirectory.dir("dist"))
}

tasks.named("build") {
    dependsOn(distribution)
}

tasks.named("check") {
    dependsOn("verifyUniversalSelection")
    dependsOn(fabricTargets.keys.map { ":$it:verifyFabricMod" })
}
