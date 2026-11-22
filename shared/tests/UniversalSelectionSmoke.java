import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.impl.discovery.ModCandidateImpl;
import net.fabricmc.loader.impl.discovery.ModResolutionException;
import net.fabricmc.loader.impl.discovery.ModResolver;
import net.fabricmc.loader.impl.metadata.DependencyOverrides;
import net.fabricmc.loader.impl.metadata.LoaderModMetadata;
import net.fabricmc.loader.impl.metadata.ModMetadataParser;
import net.fabricmc.loader.impl.metadata.VersionOverrides;

import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

public class UniversalSelectionSmoke {
    private record Nested(String path, long hash, byte[] metadata) {}
    private static final List<Nested> nested = new ArrayList<>();
    private static Path mod;
    private static Path temporary;
    private static byte[] rootMetadata;
    private static Method createPlain;
    private static Method createNested;
    private static Method addParent;

    public static void main(String[] args) throws Exception {
        System.setProperty("fabric.log.level", "ERROR");
        mod = Path.of(args[0]);
        temporary = Path.of(args[1]);
        Files.createDirectories(temporary);
        createPlain = ModCandidateImpl.class.getDeclaredMethod("createPlain", List.class, LoaderModMetadata.class,
            boolean.class, Collection.class);
        createNested = ModCandidateImpl.class.getDeclaredMethod("createNested", String.class, long.class,
            LoaderModMetadata.class, boolean.class, Collection.class);
        addParent = ModCandidateImpl.class.getDeclaredMethod("addParent", ModCandidateImpl.class);
        createPlain.setAccessible(true);
        createNested.setAccessible(true);
        addParent.setAccessible(true);
        inspectArchive();
        check("legacy", Map.of("jsmacros", "1.9.2"), "1.21.1", "21", "0.16.14");
        check("legacy", Map.of("jsmacros", "1.7.0"), "1.19", "17", "0.16.14");
        check("legacy", Map.of("jsmacros", "1.9.2"), "1.20.4", "17", "0.16.14");
        check("community", Map.of("jsmacrosce", "2.0.0"), "1.21.8", "21", "0.16.14");
        check("community", Map.of("jsmacrosce", "2.0.0-dev-test"), "1.21.8", "21", "0.16.14");
        check("reloaded", Map.of("jsmacros", "2.1.0"), "26.2", "25", "0.19.0");
        reject(Map.of("jsmacros", "2.1.0"), "26.3", "26", "0.19.0");
        reject(Map.of(), "1.21.8", "21", "0.16.14");
        reject(Map.of("jsmacros", "2.0.0"), "1.21.8", "21", "0.16.14");
        reject(Map.of("jsmacrosce", "2.0.0"), "1.21.5", "21", "0.16.14");
        reject(Map.of("jsmacros", "1.9.2"), "1.20.4", "16", "0.16.14");
        reject(Map.of("jsmacrosce", "2.0.0"), "1.21.8", "17", "0.16.14");
        reject(Map.of("jsmacros", "2.1.0"), "26.2", "21", "0.19.0");
        reject(Map.of("jsmacros", "2.1.0"), "26.2", "25", "0.16.14");
        reject(Map.of("jsmacros", "3.0.0-dev-test"), "26.2", "25", "0.19.0");
        reject(Map.of("jsmacros", "1.9.2", "jsmacrosce", "2.0.0"), "1.21.8", "21", "0.16.14");
        System.out.println("Universal: Java 17 Legacy support, exactly one compatible adapter and JRuby runtime, and unsupported configurations rejected by Fabric");
    }

    private static void inspectArchive() throws Exception {
        try (ZipFile zip = new ZipFile(mod.toFile())) {
            rootMetadata = zip.getInputStream(zip.getEntry("fabric.mod.json")).readAllBytes();
            JsonObject root = JsonParser.parseString(new String(rootMetadata, StandardCharsets.UTF_8)).getAsJsonObject();
            require(root.get("id").getAsString().equals("jsmacros_multilang"), "wrong universal ID");
            require(!root.has("entrypoints"), "universal shell must not eagerly load an adapter");
            require(root.getAsJsonObject("custom").getAsJsonObject("jsmacros_multilang:adapters").size() == 3, "missing adapters");
            var paths = new HashSet<String>();
            var runtimeIds = new HashSet<String>();
            var rubyVersions = new HashSet<String>();
            var runtimeEntries = new LinkedHashMap<String, List<String>>();
            int adapterCount = 0;
            for (var value : root.getAsJsonArray("jars")) {
                String path = value.getAsJsonObject().get("file").getAsString();
                require(path.startsWith("META-INF/jars/") && path.endsWith(".jar"),
                    "Fabric cannot discover this nested runtime archive: " + path);
                require(paths.add(path), "duplicate nested path");
                var outerEntry = zip.getEntry(path);
                require(outerEntry != null && !outerEntry.isDirectory(), "missing nested archive: " + path);
                byte[] metadata = null;
                int classes = 0;
                var entryHashes = new LinkedHashMap<String, String>();
                var entryNames = new HashSet<String>();
                try (ZipInputStream inner = new ZipInputStream(zip.getInputStream(outerEntry))) {
                    while (true) {
                        var entry = inner.getNextEntry();
                        if (entry == null) break;
                        require(entryNames.add(entry.getName()), "duplicate ZIP entry in " + path + ": " + entry.getName());
                        if (entry.getName().equals("fabric.mod.json")) metadata = inner.readAllBytes();
                        if (entry.getName().endsWith(".class")) classes++;
                        String name = entry.getName();
                        if (!entry.isDirectory() && !name.equals("fabric.mod.json") && !name.equals("module-info.class")
                            && !name.startsWith("META-INF/") && !name.startsWith("org/jruby/")
                            && !name.startsWith("jnr/") && !name.startsWith("com/kenai/jffi/") && !name.startsWith("jni/")) {
                            var digest = MessageDigest.getInstance("SHA-256");
                            new DigestInputStream(inner, digest).transferTo(OutputStream.nullOutputStream());
                            entryHashes.put(name, HexFormat.of().formatHex(digest.digest()));
                        }
                    }
                }
                require(metadata != null, "nested archive is not a Fabric candidate: " + path);
                JsonObject json = JsonParser.parseString(new String(metadata, StandardCharsets.UTF_8)).getAsJsonObject();
                String id = json.get("id").getAsString();
                if (!id.equals("jsmacros_multilang_adapter")) {
                    entryHashes.forEach((name, hash) -> runtimeEntries.computeIfAbsent(name, key -> new ArrayList<>()).add(hash));
                }
                if (id.equals("jsmacros_multilang_adapter")) {
                    adapterCount++;
                    require(!json.has("jars") && classes < 150, "adapter carries private engines");
                } else if (id.equals("jsmacros_multilang_jruby")) {
                    require(rubyVersions.add(json.get("version").getAsString()), "duplicate JRuby candidate version");
                    require(root.getAsJsonObject("depends").has(id), "missing Ruby runtime dependency");
                } else if (id.equals("jsmacros_multilang_luava")) {
                    require(!root.getAsJsonObject("depends").has(id), "Luava must be optional on Java 17");
                    require(json.getAsJsonObject("depends").get("java").getAsString().equals(">=21"),
                        "Luava must require Java 21");
                } else {
                    require(runtimeIds.add(id), "duplicate shared library ID");
                    require(root.getAsJsonObject("depends").has(id), "shared engine is not required by the universal mod");
                }
                nested.add(new Nested(path, outerEntry.getCrc() << 32 | outerEntry.getSize(), metadata));
            }
            require(adapterCount == 3, "expected three alternative adapter candidates");
            require(paths.stream().filter(p -> p.contains("/jsmacros-multilang-shared-runtime-")).count() == 1,
                "missing deduplicated runtime library");
            runtimeEntries.forEach((name, hashes) -> require(hashes.size() == 1 || new HashSet<>(hashes).size() > 1,
                "identical runtime entry remains duplicated: " + name));
            var runtimes = root.getAsJsonObject("custom").getAsJsonObject("jsmacros_multilang:runtimes");
            require(rubyVersions.equals(java.util.Set.of(runtimes.get("jrubyLegacy").getAsString(), runtimes.get("jruby").getAsString())),
                "expected Legacy and modern JRuby alternatives");
            require(paths.stream().filter(p -> p.contains("/jruby-complete-")).count() == 2, "expected two Ruby candidates");
            require(paths.stream().filter(p -> p.contains("/python-language-")).count() == 1, "multiple Python engines");
            require(paths.stream().filter(p -> p.contains("/python-resources-")).count() == 1, "multiple Python resource sets");
            require(paths.stream().filter(p -> p.contains("/kotlin-stdlib-")).count() == 1, "multiple Kotlin runtimes");
            require(paths.stream().filter(p -> p.contains("/jython-standalone-")).count() == 1, "missing or multiple Legacy Jython runtimes");
            require(paths.stream().filter(p -> p.contains("/luava-")).count() == 1, "missing or multiple Luava runtimes");
            require(zip.getEntry("org/jsmacros/multilang/runtime/luaj/vm2/LuaValue.class") != null, "shared LuaJ missing");
            require(zip.getEntry("org/jsmacros/multilang/runtime/luaj/vm2/lib/jse/CoerceJavaToLua.class") == null,
                "Lua coercer must be supplied by the selected adapter");
            for (var entry : java.util.Collections.list(zip.entries())) {
                String name = entry.getName();
                require(!name.startsWith("META-INF/services/xyz.wagyourtail.jsmacros")
                    && !name.startsWith("META-INF/services/com.jsmacrosce.jsmacros"), "shell exposes an unconditional service");
                require(!name.startsWith("xyz/wagyourtail/") && !name.startsWith("com/jsmacrosce/")
                    && !name.startsWith("grepsedawk/"), "adapter classes leaked into shared engine");
                if (name.endsWith(".class")) {
                    byte[] header = zip.getInputStream(entry).readNBytes(8);
                    int major = (header[6] & 255) << 8 | header[7] & 255;
                    require(major <= 61, "shared shell is not compatible with Java 17: " + name);
                }
            }
        }
    }

    private static List<ModCandidateImpl> resolve(Map<String, String> cores, String minecraft, String java, String loader) throws Exception {
        List<ModCandidateImpl> children = new ArrayList<>();
        for (Nested entry : nested) {
            children.add((ModCandidateImpl) createNested.invoke(null, entry.path, entry.hash, parse(entry.metadata), false, new ArrayList<>()));
        }
        ModCandidateImpl root = (ModCandidateImpl) createPlain.invoke(null, List.of(mod), parse(rootMetadata), false, children);
        for (ModCandidateImpl child : children) addParent.invoke(child, root);
        List<ModCandidateImpl> candidates = new ArrayList<>(children);
        candidates.add(root);
        Map<String, String> installed = new LinkedHashMap<>(cores);
        installed.put("minecraft", minecraft);
        installed.put("java", java);
        installed.put("fabricloader", loader);
        for (var entry : installed.entrySet()) {
            byte[] metadata = ("{\"schemaVersion\":1,\"id\":\"" + entry.getKey() + "\",\"version\":\"" + entry.getValue() + "\"}")
                .getBytes(StandardCharsets.UTF_8);
            candidates.add((ModCandidateImpl) createPlain.invoke(null, List.of(temporary.resolve(entry.getKey())),
                parse(metadata), false, new ArrayList<>()));
        }
        return ModResolver.resolve(candidates, EnvType.CLIENT, Map.of());
    }

    private static void check(String expected, Map<String, String> cores, String minecraft, String java, String loader) throws Exception {
        var selected = resolve(cores, minecraft, java, loader);
        var adapters = selected.stream().filter(m -> m.getId().equals("jsmacros_multilang_adapter")).toList();
        require(adapters.size() == 1, "Fabric did not select exactly one adapter");
        require(adapters.getFirst().getMetadata().getCustomValue("jsmacros_multilang:target").getAsString().equals(expected),
            "Fabric chose the wrong adapter for " + cores);
        String rubyVersion = adapters.getFirst().getMetadata().getCustomValue("jsmacros_multilang:jruby").getAsString();
        var ruby = selected.stream().filter(m -> m.getId().equals("jsmacros_multilang_jruby")).toList();
        require(ruby.size() == 1 && ruby.getFirst().getVersion().getFriendlyString().equals(rubyVersion),
            "Fabric selected the wrong JRuby runtime for " + expected + " on Java " + java);
        for (var runtime : nested) {
            var metadata = parse(runtime.metadata);
            if (!metadata.getId().equals("jsmacros_multilang_adapter")) {
                long expectedCount = metadata.getId().equals("jsmacros_multilang_luava") && Integer.parseInt(java) < 21 ? 0 : 1;
                require(selected.stream().filter(m -> m.getId().equals(metadata.getId())).count() == expectedCount,
                    "incorrect runtime selection for " + metadata.getId() + " on Java " + java);
            }
        }
    }

    private static void reject(Map<String, String> cores, String minecraft, String java, String loader) throws Exception {
        try {
            resolve(cores, minecraft, java, loader);
            throw new AssertionError("Fabric accepted an unsupported configuration: " + cores + ", Minecraft " + minecraft + ", Java " + java);
        } catch (ModResolutionException expected) {
        }
    }

    private static LoaderModMetadata parse(byte[] bytes) throws Exception {
        return ModMetadataParser.parseMetadata(new ByteArrayInputStream(bytes), mod.toString(), List.of(),
            new VersionOverrides(), new DependencyOverrides(temporary), false);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
