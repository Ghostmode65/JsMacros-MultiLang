import org.gradle.jvm.tasks.Jar
import org.gradle.language.jvm.tasks.ProcessResources
import java.io.File

@Suppress("UNCHECKED_CAST")
val settings = extra["fabricSettings"] as Map<String, String>
val corePackage = settings.getValue("corePackage")
val descriptorPrefix = if (settings.getValue("coreId") == "jsmacrosce") "jsmacrosce" else "jsmacros"
val useEntrypoints = settings.getValue("registration") == "entrypoints"
val providersFile = file("resources/META-INF/services/$corePackage.core.extensions.Extension")
val metadataDirectory = layout.buildDirectory.dir("adapter-metadata")
val targetName = project.name
val modVersion = project.version.toString()

dependencies {
    add("compileOnly", "net.fabricmc:sponge-mixin:0.15.5+mixin.0.8.7") { isTransitive = false }
}

tasks.named<ProcessResources>("processResources") {
    exclude("jsmacros*.ext.*.json")
    if (useEntrypoints) exclude("META-INF/services/$corePackage.core.extensions.Extension")
}

val prepareAdapterMetadata = tasks.register("prepareAdapterMetadata") {
    val template = rootProject.file("src/templates/fabric/fabric.mod.json")
    inputs.files(template, providersFile)
    inputs.properties(settings)
    inputs.property("version", modVersion)
    outputs.dir(metadataDirectory)
    doLast {
        val output = metadataDirectory.get().asFile
        output.deleteRecursively()
        output.mkdirs()
        val providers = providersFile.readLines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }
        val entrypoints = if (useEntrypoints) {
            """{"jsmacros":[${providers.joinToString(",") { "\"$it\"" }}]}"""
        } else "{}"
        val values = settings + mapOf(
            "target" to targetName, "version" to modVersion, "entrypoints" to entrypoints,
            "otherCoreId" to if (settings.getValue("coreId") == "jsmacrosce") "jsmacros" else "jsmacrosce"
        )
        output.resolve("fabric.mod.json").writeText(values.entries.fold(template.readText()) { text, (key, value) ->
            text.replace("@$key@", value)
        })
        output.resolve("jsmacros-multilang-$targetName.mixins.json").writeText("""
            {"required":true,"minVersion":"0.8","package":"$corePackage.multilang.mixin",
             "compatibilityLevel":"JAVA_17","client":["JsMacrosMixin","IntFieldMixin"]}
        """.trimIndent())
        settings.getValue("languages").split(',').forEach { language ->
            output.resolve("$descriptorPrefix.ext.$language.json").writeText("{}")
        }
    }
}

tasks.named<Jar>("jar") {
    archiveClassifier.set("unshaded")
    destinationDirectory.set(layout.buildDirectory.dir("unshaded"))
}

tasks.named<Jar>("shadowJar") {
    archiveClassifier.set("")
    from(prepareAdapterMetadata)
    if (targetName != "community") {
        val compileClasspath = configurations.getByName("compileClasspath")
        from(provider { zipTree(compileClasspath.files.single { it.name.startsWith("luaj-jse-") }) }) {
            include("org/luaj/vm2/lib/jse/CoerceJavaToLua.class")
        }
    }
}

tasks.register("fabricModJar") {
    group = "distribution"
    description = "Builds the universal Fabric mod, including this adapter."
    dependsOn(rootProject.tasks.named("shadowJar"))
}

tasks.register<Exec>("verifyFabricMod") {
    group = "verification"
    description = "Checks the universal mod using the $targetName core and selected adapter."
    val universalJar = rootProject.tasks.named<Jar>("shadowJar")
    val compileClasspath = configurations.getByName("compileClasspath")
    val javaHome = providers.gradleProperty("${targetName}_java_home").getOrElse(System.getProperty("java.home"))
    val javaExecutable = File(javaHome, "bin/java")
    dependsOn(universalJar, rootProject.tasks.named("compileLanguageMixinTests"))
    onlyIf("Fabric verification test files are available") {
        listOf(
            "tests/FabricModSmoke.java",
            "tests/RubyAdapterSmoke.java",
            "tests/mixin/org/jsmacros/multilang/test/LanguageMixinTransform.java",
            "tests/mixin/org/jsmacros/multilang/test/TestMixinService.java",
            "tests/mixin/org/jsmacros/multilang/test/TestProperties.java",
            "tests/mixin-resources/META-INF/services/org.spongepowered.asm.service.IGlobalPropertyService",
            "tests/mixin-resources/META-INF/services/org.spongepowered.asm.service.IMixinService"
        ).all { rootProject.file(it).isFile }
    }
    doFirst {
        commandLine(javaExecutable, "-cp", rootProject.configurations.getByName("fabricVerification").asPath,
            rootProject.file("tests/FabricModSmoke.java"), universalJar.get().archiveFile.get().asFile,
            corePackage, settings.getValue("registration"), compileClasspath.asPath, temporaryDir,
            targetName, rootProject.file("tests/RubyAdapterSmoke.java"),
            rootProject.files(rootProject.layout.buildDirectory.dir("language-mixin-tests"),
                rootProject.file("tests/mixin-resources"),
                rootProject.configurations.getByName("languageMixinTestRuntime")).asPath)
    }
}

tasks.named("check") { dependsOn("verifyFabricMod") }
