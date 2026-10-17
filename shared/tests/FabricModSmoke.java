import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.impl.metadata.DependencyOverrides;
import net.fabricmc.loader.impl.metadata.ModMetadataParser;
import net.fabricmc.loader.impl.metadata.VersionOverrides;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.ServiceLoader;
import java.util.jar.JarFile;
import java.util.zip.ZipFile;
import javax.tools.ToolProvider;

public class FabricModSmoke {
    public static void main(String[] args) throws Exception {
        Path mod = Path.of(args[0]);
        String corePackage = args[1];
        boolean entrypoints = args[2].equals("entrypoints");
        Path temporary = Path.of(args[4]);
        String target = args[5];
        Files.createDirectories(temporary);
        List<URL> urls = new ArrayList<>();
        urls.add(mod.toUri().toURL());
        for (String dependency : args[7].split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            urls.add(Path.of(dependency).toUri().toURL());
        }
        for (String dependency : args[3].split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            String name = Path.of(dependency).getFileName().toString();
            if (Files.isDirectory(Path.of(dependency)) && Files.exists(Path.of(dependency,
                "org/jsmacros/multilang/shared/ClassLoaderContext.class"))) continue;
            if (!name.startsWith("luaj-jse-") && !name.startsWith("luava-") && !name.startsWith("jruby-complete-")
                && !name.startsWith("jython-standalone-") && !name.startsWith("kotlin-")
                && !name.startsWith("polyglot-") && !name.startsWith("jsmacros-multilang-")) {
                urls.add(Path.of(dependency).toUri().toURL());
                if (name.startsWith("jsmacros") && name.endsWith(".jar")) {
                    try (ZipFile core = new ZipFile(dependency)) {
                        for (var entry : java.util.Collections.list(core.entries())) {
                            if (!entry.getName().startsWith("META-INF/jars/") || !entry.getName().endsWith(".jar")) continue;
                            Path library = temporary.resolve("core-" + Path.of(entry.getName()).getFileName());
                            try (var input = core.getInputStream(entry)) {
                                Files.copy(input, library, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            }
                            urls.add(library.toUri().toURL());
                        }
                    }
                }
            }
        }

        JsonObject metadata;
        JsonObject adapterMetadata = null;
        try (ZipFile archive = new ZipFile(mod.toFile())) {
            try (var input = archive.getInputStream(archive.getEntry("fabric.mod.json"))) {
                var fabric = ModMetadataParser.parseMetadata(input, mod.toString(), List.of(),
                    new VersionOverrides(), new DependencyOverrides(temporary), false);
                require(fabric.getId().equals("jsmacros_multilang"), "Fabric rejected mod ID");
            }
            metadata = JsonParser.parseString(new String(archive.getInputStream(archive.getEntry("fabric.mod.json")).readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            require(metadata.get("id").getAsString().equals("jsmacros_multilang"), "incorrect mod ID");
            require(metadata.getAsJsonObject("depends").has("jsmacros_multilang_adapter"), "missing required adapter");
            String adapterPath = metadata.getAsJsonObject("custom").getAsJsonObject("jsmacros_multilang:adapters")
                .get(target).getAsString();
            String javaVersion = metadata.getAsJsonObject("depends").get("java").getAsString();
            require(javaVersion.equals(">=17"), "universal mod must support Java 17");
            var versions = metadata.getAsJsonObject("custom").getAsJsonObject("jsmacros_multilang:runtimes");
            String expectedRuby = versions.get(target.equals("legacy") ? "jrubyLegacy" : "jruby").getAsString();
            var names = new HashSet<String>();
            var entries = archive.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                require(names.add(name), "duplicate archive entry: " + name);
                require(!name.startsWith("org/luaj/"), "unisolated LuaJ class: " + name);
                require(!name.startsWith("META-INF/jsmacrosdeps/") && !name.startsWith("META-INF/jsmacroscedeps/"),
                    "extension-folder runtime survived in mod: " + name);
            }
            var ids = new HashSet<String>();
            var runtimeNames = new HashSet<String>();
            int adapterCount = 0;
            for (var value : metadata.getAsJsonArray("jars")) {
                String name = value.getAsJsonObject().get("file").getAsString();
                require(name.endsWith(".jar"), "Fabric cannot discover this nested archive: " + name);
                require(runtimeNames.add(Path.of(name).getFileName().toString()), "duplicate runtime: " + name);
                require(!name.contains("luaj-jse-"), "LuaJ must stay isolated in the outer mod");
                Path runtime = temporary.resolve(Path.of(name).getFileName());
                try (var input = archive.getInputStream(archive.getEntry(name))) {
                    Files.copy(input, runtime, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                boolean selected = true;
                try (JarFile nested = new JarFile(runtime.toFile())) {
                    if (name.contains("/jython-standalone-")) {
                        require(nested.stream().noneMatch(e -> e.getName().startsWith("jnr/")
                            || e.getName().startsWith("com/kenai/jffi/") || e.getName().startsWith("jni/")),
                            "Jython still bundles a competing native support stack");
                    }
                    try (var input = nested.getInputStream(nested.getEntry("fabric.mod.json"))) {
                        ModMetadataParser.parseMetadata(input, runtime.toString(), List.of(mod.toString()),
                            new VersionOverrides(), new DependencyOverrides(temporary), false);
                    }
                    var nestedMetadata = JsonParser.parseString(new String(nested.getInputStream(nested.getEntry("fabric.mod.json")).readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                    if (nestedMetadata.get("id").getAsString().equals("jsmacros_multilang_adapter")) {
                        adapterCount++;
                        selected = name.equals(adapterPath);
                        require(!nestedMetadata.has("jars"), "adapter bundles private engines");
                        require(nested.stream().filter(e -> !e.isDirectory()).noneMatch(e -> e.getName().startsWith("org/jsmacros/lua/shared/")
                            || (e.getName().startsWith("org/jsmacros/multilang/shared/")
                                && !e.getName().equals("org/jsmacros/multilang/shared/ruby/RubyExecutionSupport.class"))
                            || e.getName().startsWith("org/jruby/")), "shared engine/helper classes leaked into adapter");
                        if (selected) {
                            adapterMetadata = nestedMetadata;
                            require(nestedMetadata.getAsJsonObject("custom").get("jsmacros_multilang:target").getAsString().equals(target),
                                "wrong adapter metadata");
                            require(nestedMetadata.getAsJsonObject("depends").has(corePackage.startsWith("com.") ? "jsmacrosce" : "jsmacros"),
                                "missing adapter core dependency");
                        }
                    } else if (nestedMetadata.get("id").getAsString().equals("jsmacros_multilang_jruby")) {
                        selected = nestedMetadata.get("version").getAsString().equals(expectedRuby);
                        if (selected) require(ids.add("jsmacros_multilang_jruby"), "multiple active JRuby runtimes");
                    } else if (nestedMetadata.get("id").getAsString().equals("jsmacros_multilang_luava")) {
                        selected = Runtime.version().feature() >= 21;
                        require(ids.add("jsmacros_multilang_luava"), "duplicate Luava runtime");
                    } else {
                        require(ids.add(nestedMetadata.get("id").getAsString()), "duplicate runtime mod ID");
                    }
                }
                if (selected) urls.add(runtime.toUri().toURL());
            }
            require(adapterCount == 3 && adapterMetadata != null, "missing adapter alternatives");
            var runtimeVersions = metadata.getAsJsonObject("custom").getAsJsonObject("jsmacros_multilang:runtimes");
            String ruby = "jruby-complete-" + expectedRuby + ".jar";
            require(runtimeNames.contains(ruby) && ids.contains("jsmacros_multilang_jruby"), "missing selected JRuby runtime");
            require(runtimeNames.stream().filter(n -> n.startsWith("jruby-complete-")).count() == 2, "missing JRuby alternatives");
            require(runtimeNames.contains("luava-" + runtimeVersions.get("luava").getAsString() + ".jar"), "missing Luava runtime");
            for (String library : List.of("python-language", "python-resources", "polyglot", "truffle-api")) {
                require(runtimeNames.contains(library + "-" + runtimeVersions.get("graalpy").getAsString() + ".jar"),
                    "missing shared GraalPy dependency: " + library);
            }
        }

        try (URLClassLoader parent = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader()) {
            @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
                if (!name.equals(corePackage + ".core.library.impl.FJsMacros")) return super.findClass(name);
                try (var input = getResourceAsStream(name.replace('.', '/') + ".class")) {
                    byte[] original = input.readAllBytes();
                    byte[] transformed = (byte[]) loadClass("org.jsmacros.multilang.test.LanguageMixinTransform")
                        .getMethod("transform", String.class, byte[].class, String.class)
                        .invoke(null, name, original, target);
                    return defineClass(name, transformed, 0, transformed.length);
                } catch (Exception error) {
                    throw new ClassNotFoundException("Could not apply JsMacros language mixin", error);
                }
            }
        }) {
            Thread.currentThread().setContextClassLoader(parent);
            require((parent.getResource("org/luava/runtime/LuaState.class") != null) == (Runtime.version().feature() >= 21),
                "Luava must only be visible on Java 21+");
            var probes = java.util.Map.of(
                "legacy", "xyz/wagyourtail/jsmacros/jython/JythonExtension.class",
                "community", "com/jsmacrosce/jsmacros/luaj/LuajExtension.class",
                "reloaded", "xyz/wagyourtail/jsmacros/multilang/RubyProvider.class");
            for (var probe : probes.entrySet()) {
                require((parent.getResource(probe.getValue()) != null) == probe.getKey().equals(target),
                    "inactive adapter is visible: " + probe.getKey());
            }
            for (String shared : List.of("org/jsmacros/multilang/shared/ruby/RubyExecutionSupport.class",
                "org/jruby/embed/ScriptingContainer.class",
                "org/jsmacros/multilang/runtime/luaj/vm2/LuaValue.class",
                "org/jsmacros/multilang/runtime/luaj/vm2/lib/jse/CoerceJavaToLua.class")) {
                require(java.util.Collections.list(parent.getResources(shared)).size() == 1, "duplicate or missing shared class: " + shared);
            }
            Class<?> extensionType = parent.loadClass(corePackage + ".core.extensions.Extension");
            List<Object> providers = new ArrayList<>();
            if (entrypoints) {
                for (var provider : adapterMetadata.getAsJsonObject("entrypoints").getAsJsonArray("jsmacros")) {
                    providers.add(parent.loadClass(provider.getAsString()).getConstructor().newInstance());
                }
                require(!ServiceLoader.load(extensionType, parent).iterator().hasNext(), "duplicate service and entrypoint registration");
            } else {
                Class<?> childType = parent.loadClass(corePackage + ".core.extensions.ExtensionClassLoader");
                try (URLClassLoader child = (URLClassLoader) childType.getConstructor(URL[].class).newInstance((Object) new URL[0])) {
                    for (Object provider : ServiceLoader.load(extensionType, child)) providers.add(provider);
                }
            }
            String pythonProvider = target.equals("legacy") ? ".JythonExtension" : ".PythonExtension";
            require(providers.stream().anyMatch(p -> p.getClass().getName().endsWith(pythonProvider)),
                "wrong Python provider for " + target);
            require(providers.size() == 3, "expected three providers, got " + providers.size());
            for (Object provider : providers) {
                require(extensionType.isInstance(provider), "incorrect extension interface");
                require(provider.getClass().getClassLoader() == parent, "provider was loaded as a folder extension");
                var dependencies = (java.util.Set<?>) extensionType.getMethod("getDependencies").invoke(provider);
                require(dependencies.isEmpty(), "provider still extracts runtimes: " + provider.getClass().getName());
            }

            testLua(parent, target, providers);
            // Initialize the real mixin environment before transforming the GUI callback.
            parent.loadClass(corePackage + ".core.library.impl.FJsMacros");
            parent.loadClass("org.jsmacros.multilang.test.LanguageMixinTransform")
                .getMethod("verifyIntegerField", String.class).invoke(null, corePackage);
            String rubyVersion = metadata.getAsJsonObject("custom").getAsJsonObject("jsmacros_multilang:runtimes")
                .get(target.equals("legacy") ? "jrubyLegacy" : "jruby").getAsString();
            testRuby(parent, rubyVersion);
            // Knot and extension threads can have different context class loaders.
            // Graal 24.0.1 leaves resource roots uninitialized after such a change.
            try (URLClassLoader contextLoader = new URLClassLoader(new URL[0], parent)) {
                Thread.currentThread().setContextClassLoader(contextLoader);
                try {
                    testGraalPython(parent);
                } finally {
                    Thread.currentThread().setContextClassLoader(parent);
                }
            }
            testPython(parent, temporary, target);
            testRubyAdapter(parent, temporary, Path.of(args[6]), corePackage, target, rubyVersion);
            if (target.equals("legacy")) testLegacyPythonSwitch(parent, temporary,
                Path.of(args[6]).resolveSibling("LegacyPythonSwitchSmoke.java"));
            else testPythonSwitch(parent, temporary, Path.of(args[6]).resolveSibling("PythonSwitchSmoke.java"), corePackage);
            if (Runtime.version().feature() >= 21) testLuaIntegration(parent, temporary,
                Path.of(args[6]).resolveSibling("LuaRuntimeSmoke.java"), corePackage, target);
            System.out.println(mod.getFileName() + " [" + target + "]: selected adapter, shared engines, discovery, Lua, Ruby, and Python passed");
        }
    }

    private static void testLua(ClassLoader loader, String target, List<Object> providers) throws Exception {
        String lua = "org.jsmacros.multilang.runtime.luaj.vm2";
        Class<?> platform = loader.loadClass(lua + ".lib.jse.JsePlatform");
        Class<?> globalsType = loader.loadClass(lua + ".Globals");
        Class<?> valueType = loader.loadClass(lua + ".LuaValue");
        Object globals = platform.getMethod("debugGlobals").invoke(null);
        loader.loadClass("org.jsmacros.lua.shared.LuaCollectionSupport").getMethod("install", globalsType).invoke(null, globals);
        Object chunk = globalsType.getMethod("load", String.class).invoke(globals, "local t = {3, 7}; return t[1] * t[2] * 2");
        Object result = valueType.getMethod("call").invoke(chunk);
        require((int) valueType.getMethod("toint").invoke(result) == 42, "Lua execution failed");
        Object map = loader.loadClass(lua + ".lib.jse.CoerceJavaToLua").getMethod("coerce", Object.class)
            .invoke(null, java.util.Map.of("answer", 42));
        require((boolean) valueType.getMethod("istable").invoke(map) == target.equals("community"),
            "adapter's Lua collection coercion changed");
        Object provider = providers.stream().filter(p -> p.getClass().getName().endsWith(".LuajExtension")).findFirst().orElseThrow();
        for (String script : List.of("local missing = nil\nmissing()", "local =")) {
            Throwable error;
            try {
                chunk = globalsType.getMethod("load", String.class, String.class).invoke(globals, script, "@/tmp/editor target.lua");
                valueType.getMethod("call").invoke(chunk);
                throw new AssertionError("expected Lua error");
            } catch (InvocationTargetException failure) {
                error = failure.getCause();
            }
            Object wrapped = provider.getClass().getMethod("wrapException", Throwable.class).invoke(provider, error);
            Object location = wrapped.getClass().getField("location").get(wrapped);
            require(location != null, "editor location missing");
            require(location.getClass().getField("file").get(location).equals(new File("/tmp/editor target.lua")), "wrong editor file");
            require(location.getClass().getField("line").getInt(location) > 0, "editor line missing");
        }
    }

    private static void testRuby(ClassLoader loader, String version) throws Exception {
        String rubyJar = "jruby-complete-" + version + ".jar";
        for (String nativeClass : List.of("jnr.ffi.Platform", "jnr.posix.POSIXFactory", "com.kenai.jffi.Foreign")) {
            Class<?> dependency = loader.loadClass(nativeClass);
            require(dependency.getProtectionDomain().getCodeSource().getLocation().toString().endsWith(rubyJar),
                "native support loaded from the wrong runtime: " + nativeClass);
            require(java.util.Collections.list(loader.getResources(nativeClass.replace('.', '/') + ".class")).size() == 1,
                "competing native support class: " + nativeClass);
        }
        Class<?> type = loader.loadClass("org.jruby.embed.ScriptingContainer");
        Class<?> support = loader.loadClass("org.jsmacros.multilang.shared.ruby.RubyExecutionSupport");
        Object container = support.getMethod("newContainer", ClassLoader.class).invoke(null, loader);
        try {
            Object value = type.getMethod("runScriptlet", String.class).invoke(container, "6 * 7");
            require(value.toString().equals("42"), "Ruby execution failed");
            require(type.getMethod("runScriptlet", String.class).invoke(container, "JRUBY_VERSION").toString().equals(version),
                "wrong JRuby version at runtime");
            Object resources = type.getMethod("runScriptlet", String.class).invoke(container,
                "require 'date'; require 'json'; require 'ffi'; "
                    + "raise 'encoding tables missing' unless 'hello'.encode('Big5').encode('UTF-8') == 'hello'; "
                    + "raise 'date support missing' unless Date.new(2026, 1, 2).year == 2026; "
                    + "raise 'native support missing' unless FFI::Platform::ADDRESS_SIZE > 0; "
                    + "JSON.parse('{\"answer\":42}')['answer']");
            require(resources.toString().equals("42"), "Ruby standard library or shared resources failed");
            Object callback = type.getMethod("runScriptlet", String.class).invoke(container,
                "def multilang_callback(a, b); a + b; end; method(:multilang_callback)");
            var call = support.getMethod("call", type, loader.loadClass("org.jruby.RubyMethod"), Object[].class);
            Object result = call.invoke(null, container, callback, new Object[]{20, 22, 999});
            Object javaResult = loader.loadClass("org.jruby.runtime.builtin.IRubyObject").getMethod("toJava", Class.class)
                .invoke(result, Object.class);
            require(javaResult.toString().equals("42"), "JRuby callback or arity conversion failed: " + version);
            Object throwing = type.getMethod("runScriptlet", String.class).invoke(container,
                "def multilang_failure; raise 'expected callback failure'; end; method(:multilang_failure)");
            try {
                call.invoke(null, container, throwing, new Object[0]);
                throw new AssertionError("Ruby callback error was swallowed");
            } catch (InvocationTargetException expected) {
                require(expected.getCause().getMessage().contains("expected callback failure"), "unexpected Ruby callback failure");
            }
            require(type.getMethod("runScriptlet", String.class).invoke(container, "6 * 7").toString().equals("42"),
                "Ruby runtime unusable after callback error");
        } finally {
            type.getMethod("terminate").invoke(container);
        }
    }

    private static void testPython(ClassLoader loader, Path temporary, String target) throws Exception {
        if (target.equals("legacy")) {
            System.setProperty("python.cachedir", temporary.resolve("jython-cache").toString());
            Class<?> type = loader.loadClass("org.python.util.PythonInterpreter");
            Object interpreter = type.getConstructor().newInstance();
            try {
                require(type.getMethod("eval", String.class).invoke(interpreter, "6 * 7").toString().equals("42"), "Jython execution failed");
            } finally {
                type.getMethod("close").invoke(interpreter);
            }
        } else {
            testGraalPython(loader);
        }
    }

    private static void testGraalPython(ClassLoader loader) throws Exception {
        Class<?> type = loader.loadClass("org.graalvm.polyglot.Context");
        Class<?> builderType = loader.loadClass("org.graalvm.polyglot.Context$Builder");
        Object builder = type.getMethod("newBuilder", String[].class).invoke(null, (Object) new String[]{"python"});
        builderType.getMethod("allowAllAccess", boolean.class).invoke(builder, true);
        builderType.getMethod("option", String.class, String.class).invoke(builder, "engine.WarnInterpreterOnly", "false");
        Object context = builderType.getMethod("build").invoke(builder);
        try {
            Object value = type.getMethod("eval", String.class, CharSequence.class).invoke(context, "python", "6 * 7");
            require((int) loader.loadClass("org.graalvm.polyglot.Value").getMethod("asInt").invoke(value) == 42, "GraalPy execution failed");
        } finally {
            type.getMethod("close").invoke(context);
        }
    }

    private static void testRubyAdapter(URLClassLoader parent, Path temporary, Path template,
                                       String corePackage, String target, String rubyVersion) throws Exception {
        Path classes = temporary.resolve("ruby-adapter-classes");
        Files.createDirectories(classes);
        boolean legacy = target.equals("legacy");
        String coreArguments = "Registry::new, Profile::new, directory.resolve(\"config\").toFile(), macros.toFile(), LoggerFactory.getLogger(\"RubyAdapterSmoke\")";
        String source = Files.readString(template)
            .replace("@CORE_PACKAGE@", corePackage)
            .replace("@JRUBY_VERSION@", rubyVersion)
            .replace("@CORE_CREATE@", legacy ? "Core.createInstance(" + coreArguments + ")" : "new Core<>(" + coreArguments + ")")
            .replace("@RUBY_PROVIDER@", legacy ? corePackage + ".jruby.client.JRubyExtension" : corePackage + ".multilang.RubyProvider")
            .replace("@INIT@", legacy ? "provider.init()" : "provider.init(core)")
            .replace("@PROBE_PARENT@", legacy ? "implements BaseEvent" : "extends BaseEvent")
            .replace("@PROBE_SUPER@", legacy ? "" : "super(core);");
        Path file = classes.resolve("RubyAdapterSmoke.java");
        Files.writeString(file, source);
        String classpath = java.util.Arrays.stream(parent.getURLs()).map(url -> {
            try { return Path.of(url.toURI()).toString(); }
            catch (Exception error) { throw new IllegalArgumentException(error); }
        }).collect(java.util.stream.Collectors.joining(File.pathSeparator));
        require(ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", legacy ? "17" : "21", "-classpath", classpath,
            "-d", classes.toString(), file.toString()) == 0, "could not compile Ruby adapter test");
        try (URLClassLoader tests = new URLClassLoader(new URL[]{classes.toUri().toURL()}, parent)) {
            tests.loadClass("RubyAdapterSmoke").getMethod("run", Path.class).invoke(null, temporary.resolve("ruby-adapter"));
        }
    }

    private static void testLegacyPythonSwitch(URLClassLoader parent, Path temporary, Path source) throws Exception {
        Path classes = temporary.resolve("legacy-python-classes");
        Files.createDirectories(classes);
        String classpath = java.util.Arrays.stream(parent.getURLs()).map(url -> {
            try { return Path.of(url.toURI()).toString(); }
            catch (Exception error) { throw new IllegalArgumentException(error); }
        }).collect(java.util.stream.Collectors.joining(File.pathSeparator));
        require(ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "17", "-classpath", classpath,
            "-d", classes.toString(), source.toString()) == 0, "could not compile Legacy Python switch test");
        try (URLClassLoader tests = new URLClassLoader(new URL[]{classes.toUri().toURL()}, parent)) {
            tests.loadClass("LegacyPythonSwitchSmoke").getMethod("run", Path.class)
                .invoke(null, temporary.resolve("legacy-python-switch"));
        }
    }

    private static void testPythonSwitch(URLClassLoader parent, Path temporary, Path template, String corePackage) throws Exception {
        Path classes = temporary.resolve("python-switch-classes");
        Files.createDirectories(classes);
        String source = Files.readString(template).replace("@CORE_PACKAGE@", corePackage)
            .replace("@CORE_SETTINGS_LANGUAGES@", corePackage.startsWith("com.") ? "jsmacrosce.settings.languages" : "jsmacros.settings.languages");
        Path file = classes.resolve("PythonSwitchSmoke.java");
        Files.writeString(file, source);
        String classpath = java.util.Arrays.stream(parent.getURLs()).map(url -> {
            try { return Path.of(url.toURI()).toString(); }
            catch (Exception error) { throw new IllegalArgumentException(error); }
        }).collect(java.util.stream.Collectors.joining(File.pathSeparator));
        require(ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "21", "-classpath", classpath,
            "-d", classes.toString(), file.toString()) == 0, "could not compile Python switch test");
        try (URLClassLoader tests = new URLClassLoader(new URL[]{classes.toUri().toURL()}, parent)) {
            tests.loadClass("PythonSwitchSmoke").getMethod("run", Path.class).invoke(null, temporary.resolve("python-switch"));
        }
    }

    private static void testLuaIntegration(URLClassLoader parent, Path temporary, Path template,
                                           String corePackage, String target) throws Exception {
        Path classes = temporary.resolve("lua-integration-classes");
        Files.createDirectories(classes);
        boolean legacy = target.equals("legacy");
        String source = Files.readString(template)
            .replace("@CORE_PACKAGE@", corePackage)
            .replace("@CORE_CREATE@", legacy ? "Core.getInstance()" : "new Core<>(Registry::new, Profile::new, directory.resolve(\"config\").toFile(), macros.toFile(), LoggerFactory.getLogger(\"LuaRuntimeSmoke\"))")
            .replace("@INIT@", legacy ? "provider.init()" : "provider.init(core)")
            .replace("@PROBE_PARENT@", legacy ? "implements BaseEvent" : "extends BaseEvent")
            .replace("@PROBE_SUPER@", legacy ? "" : "super(core);")
            .replace("@TRIGGER@", legacy ? "new ScriptTrigger(ScriptTrigger.TriggerType.EVENT, \"Smoke\", \"smoke.lua\", true)"
                : "new ScriptTrigger(ScriptTrigger.TriggerType.EVENT, \"Smoke\", \"smoke.lua\", true, false)");
        Path file = classes.resolve("LuaRuntimeSmoke.java");
        Files.writeString(file, source);
        String classpath = java.util.Arrays.stream(parent.getURLs()).map(url -> {
            try { return Path.of(url.toURI()).toString(); }
            catch (Exception error) { throw new IllegalArgumentException(error); }
        }).collect(java.util.stream.Collectors.joining(File.pathSeparator));
        require(ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "21", "-classpath", classpath,
            "-d", classes.toString(), file.toString()) == 0, "could not compile Lua integration test");
        try (URLClassLoader tests = new URLClassLoader(new URL[]{classes.toUri().toURL()}, parent)) {
            tests.loadClass("LuaRuntimeSmoke").getMethod("run", Path.class).invoke(null, temporary.resolve("lua-integration"));
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
