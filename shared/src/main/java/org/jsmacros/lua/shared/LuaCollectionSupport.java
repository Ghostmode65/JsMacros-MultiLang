package org.jsmacros.lua.shared;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.jse.CoerceJavaToLua;

import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.WeakHashMap;

public final class LuaCollectionSupport {
    private static final Map<Globals, Boolean> INSTALLED = new WeakHashMap<>();

    private LuaCollectionSupport() {}

    public static synchronized void install(Globals globals) {
        if (INSTALLED.putIfAbsent(globals, Boolean.TRUE) != null) {
            return;
        }

        LuaValue originalPairs = globals.get("pairs");
        LuaValue originalIpairs = globals.get("ipairs");
        LuaValue originalNext = globals.get("next");
        globals.set("pairs", forwardingIterator(originalPairs, false));
        globals.set("ipairs", forwardingIterator(originalIpairs, false));
        globals.set("next", forwardingIterator(originalNext, true));
    }

    private static VarArgFunction forwardingIterator(LuaValue original, boolean hasKey) {
        return new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                LuaValue collection = asTable(args.arg1());
                if (hasKey) {
                    return original.invoke(LuaValue.varargsOf(collection, args.arg(2)));
                }
                return original.invoke(collection);
            }
        };
    }

    private static LuaValue asTable(LuaValue value) {
        if (value.istable() || !value.isuserdata()) {
            return value;
        }
        Object javaValue = value.touserdata();
        if (javaValue instanceof Map<?, ?> || javaValue instanceof Collection<?>) {
            return toLuaValue(javaValue, new IdentityHashMap<>());
        }
        return value;
    }

    private static LuaValue toLuaValue(Object value, IdentityHashMap<Object, LuaValue> seen) {
        if (value == null) {
            return LuaValue.NIL;
        }
        if (value instanceof LuaValue luaValue) {
            return luaValue;
        }
        if (value instanceof Map<?, ?> map) {
            LuaValue previous = seen.get(map);
            if (previous != null) {
                return previous;
            }
            LuaTable table = LuaValue.tableOf();
            seen.put(map, table);
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                LuaValue key = toLuaValue(entry.getKey(), seen);
                if (!key.isnil()) {
                    table.set(key, toLuaValue(entry.getValue(), seen));
                }
            }
            return table;
        }
        if (value instanceof Collection<?> collection) {
            LuaValue previous = seen.get(collection);
            if (previous != null) {
                return previous;
            }
            LuaTable table = LuaValue.tableOf();
            seen.put(collection, table);
            int index = 1;
            for (Object element : collection) {
                table.set(index++, toLuaValue(element, seen));
            }
            return table;
        }
        return CoerceJavaToLua.coerce(value);
    }
}
