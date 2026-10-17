package org.jsmacros.multilang.shared.luava;

import org.jsmacros.lua.shared.LuaSharedGlobals;
import org.luava.runtime.LuaFunction;
import org.luava.runtime.LuaValue;

import java.util.HashMap;
import java.util.Map;

public final class LuavaSharedGlobals {
    private static final Map<String, LuavaExecutionSupport.Session> sessions = new HashMap<>();
    private static final Map<String, LuaSharedGlobals.ContextHandle> owners = new java.util.concurrent.ConcurrentHashMap<>();

    public record Claim(LuavaExecutionSupport.Session session, String pool) {}

    private LuavaSharedGlobals() {}

    public static synchronized Claim claim(LuaSharedGlobals.ContextHandle ctx, boolean shared, boolean split) {
        if (!shared) return new Claim(new LuavaExecutionSupport.Session(), null);
        if (!split) return new Claim(pool("default"), null);
        String marker = LuaSharedGlobals.readPoolMarker(ctx.getFile());
        if (marker == null) return new Claim(new LuavaExecutionSupport.Session(), null);
        boolean force = marker.startsWith("force:");
        String name = force ? marker.substring("force:".length()) : marker;
        LuaSharedGlobals.ContextHandle owner = owners.get(name);
        if (owner != null && !owner.isContextClosed()) {
            if (!force) return new Claim(new LuavaExecutionSupport.Session(), null);
            owner.closeContext();
        }
        owners.put(name, ctx);
        return new Claim(pool(name), name);
    }

    public static synchronized void release(LuaSharedGlobals.ContextHandle ctx, String name) {
        if (name != null) owners.remove(name, ctx);
    }

    public static void release(LuaSharedGlobals.ContextHandle ctx) {
        owners.values().removeIf(owner -> owner == ctx);
    }

    public static synchronized String ownedPool(LuaSharedGlobals.ContextHandle ctx) {
        return owners.entrySet().stream().filter(entry -> entry.getValue() == ctx)
            .map(Map.Entry::getKey).findFirst().orElse(null);
    }

    public static synchronized boolean destroy(String name) {
        LuaSharedGlobals.ContextHandle owner = owners.get(name);
        if (owner != null && !owner.isContextClosed()) return false;
        owners.remove(name);
        sessions.remove(name);
        return true;
    }

    private static synchronized LuavaExecutionSupport.Session pool(String name) {
        return sessions.computeIfAbsent(name, ignored -> new LuavaExecutionSupport.Session());
    }

    public static LuaFunction loadscript(String script, String name, LuavaExecutionSupport.Bindings bindings) throws Exception {
        if (name == null || script == null) throw new IllegalArgumentException("script and pool name are required");
        LuavaExecutionSupport.Session session = pool(name);
        LuaFunction chunk = session.call(bindings, () -> session.state.compile(LuavaExecutionSupport.sourceBytes(script)));
        return LuaFunction.of(args -> {
            String previous = bindings.pool;
            bindings.pool = name;
            try {
                return session.invoke(bindings, chunk, (Object[]) args);
            } catch (RuntimeException error) {
                throw error;
            } catch (Exception error) {
                if (error instanceof InterruptedException) Thread.currentThread().interrupt();
                throw new RuntimeException(error);
            } finally {
                bindings.pool = previous;
            }
        });
    }
}
