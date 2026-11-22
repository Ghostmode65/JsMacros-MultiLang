package org.jsmacros.lua.shared;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.jse.JsePlatform;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class LuaSharedGlobals {

    public interface ContextHandle {
        File getFile();

        boolean isContextClosed();

        void closeContext();
    }

    public interface GlobalsFactory {
        Globals create();
    }

    public static final class Claim {
        private final Globals globals;
        private final String poolName;

        private Claim(Globals globals, String poolName) {
            this.globals = globals;
            this.poolName = poolName;
        }

        public Globals globals() {
            return globals;
        }

        public String poolName() {
            return poolName;
        }
    }

    private static final String FORCE_PREFIX = "force:";

    private static final Map<String, Globals> namedGlobals = new ConcurrentHashMap<>();
    private static final Map<String, ContextHandle> globalOwners = new ConcurrentHashMap<>();
    private static final Map<String, Object> poolLocks = new ConcurrentHashMap<>();
    private static final ThreadLocal<String> activeLoadscriptPool = new ThreadLocal<>();

    private LuaSharedGlobals() {
    }

    public static Claim claim(ContextHandle ctx, boolean useGlobalContext, boolean splitGlobalContext, GlobalsFactory factory) {
        if (!useGlobalContext) {
            return new Claim(factory.create(), null);
        }

        if (!splitGlobalContext) {
            return new Claim(namedGlobals.computeIfAbsent("default", k -> factory.create()), null);
        }

        String rawPoolName = readPoolMarker(ctx.getFile());
        if (rawPoolName == null) {
            return new Claim(factory.create(), null);
        }

        boolean forceMode = rawPoolName.startsWith(FORCE_PREFIX);
        String poolName = forceMode ? rawPoolName.substring(FORCE_PREFIX.length()) : rawPoolName;
        synchronized (lockFor(poolName)) {
            ContextHandle currentOwner = globalOwners.get(poolName);
            boolean slotFree = currentOwner == null || currentOwner.isContextClosed();
            if (slotFree || forceMode) {
                if (!slotFree) {
                    currentOwner.closeContext();
                }
                Globals globals = namedGlobals.computeIfAbsent(poolName, k -> factory.create());
                globalOwners.put(poolName, ctx);
                return new Claim(globals, poolName);
            }
            return new Claim(factory.create(), null);
        }
    }

    public static void releaseClaim(ContextHandle ctx, String poolName) {
        if (poolName == null) {
            return;
        }
        synchronized (lockFor(poolName)) {
            globalOwners.remove(poolName, ctx);
        }
    }

    public static void releaseGlobal(ContextHandle ctx) {
        for (Map.Entry<String, ContextHandle> entry : globalOwners.entrySet()) {
            if (entry.getValue() == ctx) {
                String poolName = entry.getKey();
                synchronized (lockFor(poolName)) {
                    if (globalOwners.get(poolName) == ctx) {
                        Globals current = namedGlobals.get(poolName);
                        if (current != null) {
                            namedGlobals.put(poolName, snapshotGlobals(current));
                        }
                        globalOwners.remove(poolName);
                    }
                }
                break;
            }
        }
    }

    public static String getOwnedPool(ContextHandle ctx) {
        String active = activeLoadscriptPool.get();
        if (active != null) {
            return active;
        }
        return globalOwners.entrySet().stream()
            .filter(e -> e.getValue() == ctx)
            .map(Map.Entry::getKey)
            .findFirst()
            .orElse(null);
    }

    public static boolean destroyPool(String poolName) {
        synchronized (lockFor(poolName)) {
            ContextHandle owner = globalOwners.get(poolName);
            if (owner != null && !owner.isContextClosed()) {
                return false;
            }
            globalOwners.remove(poolName);
            namedGlobals.remove(poolName);
            poolLocks.remove(poolName);
            return true;
        }
    }

    public static Globals getOrCreatePool(String poolName, Globals fallback) {
        return namedGlobals.computeIfAbsent(poolName, k -> fallback);
    }

    public static LuaValue loadscript(String script, String poolName, Globals fallback) {
        Globals poolGlobals = getOrCreatePool(poolName, fallback);
        LuaValue chunk = poolGlobals.load(checkScript(script));
        return new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                enterLoadscriptPool(poolName);
                try {
                    return chunk.invoke(args);
                } finally {
                    exitLoadscriptPool();
                }
            }
        };
    }

    public static void enterLoadscriptPool(String poolName) {
        activeLoadscriptPool.set(poolName);
    }

    public static void exitLoadscriptPool() {
        activeLoadscriptPool.remove();
    }

    public static String checkScript(String script) {
        if (script == null) {
            throw new LuaError("bad argument #1 to 'load' (string expected, got nil)");
        }
        return script;
    }

    private static Object lockFor(String poolName) {
        return poolLocks.computeIfAbsent(poolName, k -> new Object());
    }

    public static String readPoolMarker(File file) {
        if (file == null) {
            return null;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line = reader.readLine();
            if (line == null) {
                return null;
            }
            line = line.trim();
            if (line.equals("--@global")) {
                return "default";
            }
            if (line.startsWith("--@global ")) {
                return line.substring("--@global ".length()).trim();
            }
            if (line.equals("--@global-force")) {
                return FORCE_PREFIX + "default";
            }
            if (line.startsWith("--@global-force ")) {
                return FORCE_PREFIX + line.substring("--@global-force ".length()).trim();
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    private static Globals snapshotGlobals(Globals source) {
        Globals fresh = JsePlatform.debugGlobals();
        LuaValue key = LuaValue.NIL;
        while (true) {
            Varargs entry = source.next(key);
            key = entry.arg1();
            if (key.isnil()) {
                break;
            }
            fresh.rawset(key, entry.arg(2));
        }
        return fresh;
    }
}
