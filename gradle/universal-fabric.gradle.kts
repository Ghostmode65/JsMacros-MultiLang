import org.gradle.jvm.tasks.Jar
import java.io.File
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

@Suppress("UNCHECKED_CAST")
val targets = extra["fabricTargets"] as Map<String, Map<String, String>>
val runtimes = configurations.getByName("embeddedRuntimes")
val graalRuntimes = configurations.getByName("embeddedGraalRuntimes")
val legacyRubyRuntime = configurations.getByName("legacyRubyRuntime")
val adapters = targets.keys.associateWith { project(":$it").tasks.named<Jar>("shadowJar") }
val runtimeDirectory = layout.buildDirectory.dir("fabric-runtime")
val modVersion = project.version.toString()
val graalVersion = property("graal_version").toString()
val jrubyVersion = property("jruby_version").toString()
val jrubyLegacyVersion = property("jruby_legacy_version").toString()
val luavaVersion = property("luava_version").toString()
val template = file("src/templates/fabric/universal.mod.json")

fun prepareRuntime(edition: String) = tasks.register(if (edition == "all") "prepareFabricRuntime" else "prepare${edition.replaceFirstChar { it.uppercase() }}FabricRuntime") {
    val runtimeDirectory = if (edition == "all") runtimeDirectory else layout.buildDirectory.dir("fabric-runtime-$edition")
    dependsOn(adapters.values)
    if (edition != "all") dependsOn("prepareFabricRuntime")
    inputs.files(runtimes, graalRuntimes, legacyRubyRuntime, adapters.values.map { it.flatMap { jar -> jar.archiveFile } })
    inputs.file(template)
    inputs.property("edition", edition)
    inputs.properties(mapOf("version" to modVersion, "graalVersion" to graalVersion, "jrubyVersion" to jrubyVersion,
        "jrubyLegacyVersion" to jrubyLegacyVersion, "luavaVersion" to luavaVersion))
    outputs.dir(runtimeDirectory)
    doLast {
        val output = runtimeDirectory.get().asFile
        output.deleteRecursively()
        val nested = output.resolve("META-INF/jars").apply { mkdirs() }
        val libraries = linkedMapOf<String, String>()
        val archiveHashes = mutableMapOf<String, List<Byte>>()
        data class SharedEntry(val hash: List<Byte>, val owners: MutableSet<String>, var identical: Boolean = true)
        val entries = linkedMapOf<String, SharedEntry>()
        // Keep native support with the selected JRuby candidate, including identical files:
        // the other JRuby candidate is absent at runtime and must not supply half a version.
        fun nativeSupport(path: String) = path.startsWith("jnr/") || path.startsWith("com/kenai/jffi/") || path.startsWith("jni/")
        fun shareable(path: String) = path != "fabric.mod.json" && path != "module-info.class"
            && !path.startsWith("META-INF/") && !path.startsWith("org/jruby/") && !nativeSupport(path)
        fun addLibrary(name: String, input: InputStream) {
            require(name.endsWith(".jar")) { "Bundled Fabric libraries must be JARs: $name" }
            val bytes = input.readBytes()
            val archiveHash = MessageDigest.getInstance("SHA-256").digest(bytes).toList()
            val previous = archiveHashes.putIfAbsent(name, archiveHash)
            require(previous == null || previous == archiveHash) { "Conflicting bundled archives named $name" }
            if (previous != null) return
            val stem = name.removeSuffix(".jar").lowercase().map {
                if (it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-') it else '_'
            }.joinToString("")
            val ruby = name.startsWith("jruby-complete-")
            val luava = name.startsWith("luava-")
            val libraryVersion = if (ruby) name.removePrefix("jruby-complete-").removeSuffix(".jar") else modVersion
            val id = if (ruby) "jsmacros_multilang_jruby" else if (luava) "jsmacros_multilang_luava" else "jsmacros_ml_${stem.take(40)}_${Integer.toHexString(name.hashCode())}"
            val javaDependency = when {
                luava -> ",\"depends\":{\"java\":\">=21\"}"
                ruby -> ",\"depends\":{\"java\":\">=${if (libraryVersion == jrubyLegacyVersion) 17 else 21}\"}"
                else -> ""
            }
            libraries[name] = id
            val metadata = """
                {"schemaVersion":1,"id":"$id","version":"$libraryVersion","name":"Bundled $name",
                 "environment":"client"$javaDependency,"custom":{"modmenu":{"badges":["library"],"parent":"jsmacros_multilang"}}}
            """.trimIndent()
            ZipOutputStream(nested.resolve(name).outputStream().buffered()).use { destination ->
                destination.setLevel(Deflater.BEST_COMPRESSION)
                val paths = mutableSetOf<String>()
                ZipInputStream(ByteArrayInputStream(bytes)).use { source ->
                    while (true) {
                        val entry = source.nextEntry ?: break
                        val path = entry.name
                        val signed = path.startsWith("META-INF/") && listOf(".SF", ".RSA", ".DSA", ".EC").any { path.endsWith(it) }
                        if (path != "fabric.mod.json" && path != "META-INF/INDEX.LIST" && !signed) {
                            require(paths.add(path)) { "Duplicate entry $path in $name" }
                            destination.putNextEntry(ZipEntry(path).apply { time = 0L })
                            if (!entry.isDirectory && shareable(path)) {
                                val digest = MessageDigest.getInstance("SHA-256")
                                DigestInputStream(source, digest).copyTo(destination)
                                val hash = digest.digest().toList()
                                val existing = entries[path]
                                if (existing == null) entries[path] = SharedEntry(hash, mutableSetOf(name))
                                else {
                                    existing.owners.add(name)
                                    existing.identical = existing.identical && existing.hash == hash
                                }
                            } else if (!entry.isDirectory) source.copyTo(destination)
                            destination.closeEntry()
                        }
                        source.closeEntry()
                    }
                }
                destination.putNextEntry(ZipEntry("fabric.mod.json").apply { time = 0L })
                destination.write(metadata.toByteArray(Charsets.UTF_8))
                destination.closeEntry()
            }
        }
        // Aggregate Maven POMs contribute transitive dependencies, but are not
        // runtime libraries. Fabric only discovers nested archives ending in .jar.
        if (edition == "all") {
            (runtimes.files + graalRuntimes.files + legacyRubyRuntime.files)
                .filter { it.extension == "jar" && !it.name.startsWith("luaj-jse-") }
                .sortedBy { it.name }.forEach { jar ->
                    jar.inputStream().buffered().use { addLibrary(jar.name, it) }
                }
            listOf("python-language", "python-resources", "polyglot", "truffle-api").forEach { library ->
                require("$library-$graalVersion.jar" in libraries) { "Missing shared runtime $library-$graalVersion.jar" }
                require(libraries.keys.count { it.startsWith("$library-") } == 1) { "Conflicting $library versions" }
            }
            require("jruby-complete-$jrubyVersion.jar" in libraries && "jruby-complete-$jrubyLegacyVersion.jar" in libraries
                && libraries.keys.count { it.startsWith("jruby-complete-") } == 2) { "Missing JRuby runtime candidates" }
            require("luava-$luavaVersion.jar" in libraries && libraries.keys.count { it.startsWith("luava-") } == 1)
            val sharedPaths = entries.filterValues { it.identical && it.owners.size > 1 }.keys
            val commonJar = temporaryDir.resolve("shared-runtime.jar")
            val written = mutableSetOf<String>()
            var savedBytes = 0L
            ZipOutputStream(commonJar.outputStream().buffered()).use { common ->
                common.setLevel(Deflater.BEST_COMPRESSION)
                libraries.keys.filter { name -> sharedPaths.any { name in entries.getValue(it).owners } }.forEach { name ->
                    val original = nested.resolve(name)
                    val replacement = temporaryDir.resolve(name)
                    ZipFile(original).use { source ->
                        ZipOutputStream(replacement.outputStream().buffered()).use { destination ->
                            destination.setLevel(Deflater.BEST_COMPRESSION)
                            source.entries().asSequence().forEach { entry ->
                                if (entry.name in sharedPaths) {
                                    if (written.add(entry.name)) {
                                        common.putNextEntry(ZipEntry(entry.name).apply { time = 0L })
                                        source.getInputStream(entry).use { it.copyTo(common) }
                                        common.closeEntry()
                                    } else savedBytes += entry.size
                                } else {
                                    destination.putNextEntry(ZipEntry(entry.name).apply { time = 0L })
                                    if (!entry.isDirectory) source.getInputStream(entry).use { it.copyTo(destination) }
                                    destination.closeEntry()
                                }
                            }
                        }
                    }
                    replacement.copyTo(original, overwrite = true)
                    replacement.delete()
                }
            }
            if (written.isNotEmpty()) commonJar.inputStream().buffered().use { addLibrary("jsmacros-multilang-shared-runtime-$modVersion.jar", it) }
            logger.lifecycle("Shared ${written.size} identical runtime entries; removed $savedBytes uncompressed duplicate bytes")
        } else {
            val graalNames = graalRuntimes.files.filter { it.extension == "jar" }.map { it.name }.toSet()
            val source = rootProject.layout.buildDirectory.dir("fabric-runtime/META-INF/jars").get().asFile
            source.listFiles()!!.filter { file ->
                val name = file.name
                name.startsWith("jsmacros-multilang-shared-runtime-") || when (edition) {
                    "lua" -> name.startsWith("luava-") || name.startsWith("kotlin-stdlib-")
                    "ruby" -> name.startsWith("jruby-complete-")
                    "python" -> name in graalNames
                    else -> false
                }
            }.forEach { file ->
                file.copyTo(nested.resolve(file.name), overwrite = true)
                val id = ZipFile(file).use { archive ->
                    val json = JsonSlurper().parseText(archive.getInputStream(archive.getEntry("fabric.mod.json")).bufferedReader().readText()) as Map<*, *>
                    json["id"] as String
                }
                libraries[file.name] = id
            }
        }
        val adapterFiles = adapters.mapValues { (_, task) ->
            val file = task.get().archiveFile.get().asFile
            if (edition == "all") file.copyTo(nested.resolve(file.name), overwrite = true)
            else ZipFile(file).use { source ->
                ZipOutputStream(nested.resolve(file.name).outputStream().buffered()).use { destination ->
                    source.entries().asSequence().forEach { entry ->
                        destination.putNextEntry(ZipEntry(entry.name).apply { time = 0L })
                        if (!entry.isDirectory) {
                            val contents = source.getInputStream(entry).use { it.readBytes() }
                            val rewritten = when {
                                entry.name == "fabric.mod.json" -> {
                                    @Suppress("UNCHECKED_CAST")
                                    val metadata = JsonSlurper().parseText(contents.toString(Charsets.UTF_8)) as MutableMap<String, Any>
                                    metadata["id"] = "jsmacros_multilang_shared_adapter"
                                    metadata["entrypoints"] = emptyMap<String, Any>()
                                    @Suppress("UNCHECKED_CAST")
                                    (metadata["depends"] as MutableMap<String, Any>).apply {
                                        remove("jsmacros_multilang")
                                        remove("jsmacros_multilang_jruby")
                                    }
                                    @Suppress("UNCHECKED_CAST")
                                    (metadata["custom"] as MutableMap<String, Any>)["modmenu"] = mapOf("badges" to listOf("library"))
                                    JsonOutput.toJson(metadata).toByteArray(Charsets.UTF_8)
                                }
                                entry.name.startsWith("META-INF/services/") && entry.name.endsWith(".core.extensions.Extension") ->
                                    ByteArray(0)
                                else -> contents
                            }
                            destination.write(rewritten)
                        }
                        destination.closeEntry()
                    }
                }
            }
            "META-INF/jars/${file.name}"
        }
        if (edition != "all") {
            adapters.forEach { (target, task) ->
                ZipFile(task.get().archiveFile.get().asFile).use { adapter ->
                    val service = adapter.entries().asSequence().firstOrNull {
                        it.name.startsWith("META-INF/services/") && it.name.endsWith(".core.extensions.Extension")
                    }
                    if (service != null) {
                        val providers = adapter.getInputStream(service).bufferedReader().readLines().filter { providerForEdition(it, edition) }
                        output.resolve(service.name).apply { parentFile.mkdirs(); writeText(providers.joinToString("\n", postfix = "\n")) }
                    }
                }
            }
        }
        if (edition == "ruby") {
            adapters.forEach { (target, task) ->
                val adapter = task.get().archiveFile.get().asFile
                val metadata = ZipFile(adapter).use { zip ->
                    @Suppress("UNCHECKED_CAST")
                    val json = JsonSlurper().parseText(zip.getInputStream(zip.getEntry("fabric.mod.json")).bufferedReader().readText()) as MutableMap<String, Any>
                    json["id"] = "jsmacros_multilang_ruby_selector"
                    json["name"] = "JsMacros Ruby runtime selector"
                    json.remove("mixins")
                    json.remove("entrypoints")
                    @Suppress("UNCHECKED_CAST")
                    val depends = json["depends"] as MutableMap<String, Any>
                    depends.remove("jsmacros_multilang")
                    depends["jsmacros_multilang_shared_adapter"] = "=" + json.getValue("version")
                    JsonOutput.toJson(json)
                }
                val name = "jsmacros-multilang-ruby-selector-$target-$modVersion.jar"
                ZipOutputStream(nested.resolve(name).outputStream().buffered()).use { zip ->
                    zip.putNextEntry(ZipEntry("fabric.mod.json").apply { time = 0L })
                    zip.write(metadata.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
                libraries[name] = "jsmacros_multilang_ruby_selector"
            }
        }
        val values = mapOf(
            "version" to modVersion, "jrubyVersion" to jrubyVersion, "graalVersion" to graalVersion,
            "jrubyLegacyVersion" to jrubyLegacyVersion,
            "luavaVersion" to luavaVersion,
            "jars" to (libraries.keys.map { "META-INF/jars/$it" } + adapterFiles.values)
                .joinToString(",\n") { "    {\"file\":\"$it\"}" },
            "runtimeDependencies" to libraries.values.distinct().filter { it != "jsmacros_multilang_luava" }
                .joinToString("") { ",\n    \"$it\": \"*\"" },
            "adapters" to adapterFiles.entries.joinToString(",\n") { (target, path) -> "      \"$target\": \"$path\"" }
        )
        val metadata = values.entries.fold(template.readText()) { text, (key, value) ->
            text.replace("@$key@", value)
        }
        output.resolve("fabric.mod.json").writeText(if (edition == "all") metadata else {
            @Suppress("UNCHECKED_CAST")
            val json = JsonSlurper().parseText(metadata) as MutableMap<String, Any>
            json["id"] = "jsmacros_${edition}"
            @Suppress("UNCHECKED_CAST")
            val depends = json["depends"] as MutableMap<String, Any>
            depends.remove("jsmacros_multilang_adapter")
            depends["jsmacros_multilang_shared_adapter"] = "*"
            json["breaks"] = mapOf("jsmacros_multilang" to "*")
            val reloaded = ZipFile(adapters.getValue("reloaded").get().archiveFile.get().asFile).use { adapter ->
                @Suppress("UNCHECKED_CAST")
                val original = JsonSlurper().parseText(adapter.getInputStream(adapter.getEntry("fabric.mod.json")).bufferedReader().readText()) as Map<String, Any>
                @Suppress("UNCHECKED_CAST")
                val entrypoints = original["entrypoints"] as Map<String, List<String>>
                entrypoints.getValue("jsmacros").filter { providerForEdition(it, edition) }
            }
            json["entrypoints"] = mapOf("jsmacros" to reloaded)
            json["name"] = "JsMacros ${edition.replaceFirstChar { it.uppercase() }}"
            json["description"] = "${edition.replaceFirstChar { it.uppercase() }} for JsMacros Legacy, Community, and Reloaded"
            JsonOutput.toJson(json)
        })
    }
}

fun providerForEdition(provider: String, edition: String): Boolean = when (edition) {
    "lua" -> provider.contains(".luaj.")
    "ruby" -> provider.contains(".jruby.") || provider.endsWith(".RubyProvider")
    "python" -> provider.contains(".multilang.python.") || provider.contains(".graal.python.")
    else -> true
}

val prepareFabricRuntime = prepareRuntime("all")

tasks.named<Jar>("jar") {
    archiveClassifier.set("unshaded")
    destinationDirectory.set(layout.buildDirectory.dir("unshaded"))
}
tasks.named<Jar>("shadowJar") {
    archiveClassifier.set("")
    from(provider { zipTree(runtimes.files.single { it.name.startsWith("luaj-jse-") }) }) {
        exclude("org/luaj/vm2/lib/jse/CoerceJavaToLua.class")
    }
    from(prepareFabricRuntime)
}

val languageJars = listOf("lua", "ruby", "python").map { edition ->
    val runtime = prepareRuntime(edition)
    tasks.register<Jar>("${edition}Jar") {
        group = "distribution"
        description = "Builds the $edition edition for all supported JsMacros versions."
        archiveBaseName.set("jsmacros-$edition")
        archiveClassifier.set("")
        destinationDirectory.set(layout.buildDirectory.dir("language-jars"))
        val universal = tasks.named<Jar>("shadowJar")
        dependsOn(universal)
        from(universal.map { zipTree(it.archiveFile) }) {
            exclude("fabric.mod.json", "META-INF/jars/**")
        }
        from(runtime)
    }
}

tasks.register<Sync>("languageDistribution") {
    group = "distribution"
    description = "Collects the Lua, Ruby, and Python universal editions in lang_build."
    dependsOn(languageJars)
    languageJars.forEach { from(it.flatMap { jar -> jar.archiveFile }) }
    into(layout.projectDirectory.dir("lang_build"))
}

val fabricVerification = configurations.create("fabricVerification") { isTransitive = false }
dependencies.add(fabricVerification.name, "net.fabricmc:fabric-loader:0.16.14")
dependencies.add(fabricVerification.name, "com.google.code.gson:gson:2.10")
tasks.register<Exec>("verifyUniversalSelection") {
    group = "verification"
    description = "Uses Fabric's resolver to verify adapter selection and shared engine packaging."
    val modJar = tasks.named<Jar>("shadowJar")
    dependsOn(modJar)
    onlyIf("universal selection test is available") { file("tests/UniversalSelectionSmoke.java").isFile }
    doFirst {
        commandLine(File(System.getProperty("java.home"), "bin/java"), "-cp", fabricVerification.asPath,
            file("tests/UniversalSelectionSmoke.java"), modJar.get().archiveFile.get().asFile, temporaryDir)
    }
}

val verifyLanguageSelection = languageJars.zip(listOf("lua", "ruby", "python")).map { (jar, edition) ->
    tasks.register<Exec>("verify${edition.replaceFirstChar { it.uppercase() }}Selection") {
        group = "verification"
        description = "Checks $edition providers and Fabric adapter selection."
        dependsOn(jar)
        onlyIf("universal selection test is available") { file("tests/UniversalSelectionSmoke.java").isFile }
        doFirst {
            commandLine(File(System.getProperty("java.home"), "bin/java"), "-cp", fabricVerification.asPath,
                file("tests/UniversalSelectionSmoke.java"), jar.get().archiveFile.get().asFile, temporaryDir, edition)
        }
    }
}
tasks.named("check") { dependsOn(verifyLanguageSelection) }

val verifySeparateLanguages = tasks.register<Exec>("verifySeparateLanguages") {
    group = "verification"
    description = "Checks that separate language mods share identical adapters and runtimes."
    dependsOn(languageJars)
    onlyIf("separate languages test is available") { file("tests/SeparateLanguagesSmoke.java").isFile }
    doFirst {
        commandLine(File(System.getProperty("java.home"), "bin/java"), "-cp", fabricVerification.asPath,
            file("tests/SeparateLanguagesSmoke.java"), layout.buildDirectory.dir("language-jars").get().asFile, modVersion)
    }
}
tasks.named("check") { dependsOn(verifySeparateLanguages) }
