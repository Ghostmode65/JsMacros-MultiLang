package org.jsmacros.multilang.shared.luava;

import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.jse.CoerceJavaToLua;
import org.luava.runtime.LuaString;
import org.luava.runtime.LuaTable;
import org.luava.runtime.LuaUserdata;

import java.util.IdentityHashMap;

public final class LuavaToLuaJ {
    private LuavaToLuaJ() {}

    public static LuaValue convert(org.luava.runtime.LuaValue value) {
        return convert(LuavaExecutionSupport.first(value), new IdentityHashMap<>());
    }

    public static void requireData(org.luava.runtime.LuaValue value) {
        requireData(value, new IdentityHashMap<>());
    }

    private static void requireData(org.luava.runtime.LuaValue value,
                                    IdentityHashMap<org.luava.runtime.LuaValue, Boolean> seen) {
        if (value instanceof LuaTable table) {
            if (seen.put(table, Boolean.TRUE) != null) return;
            table.forEach((key, entry) -> { requireData(key, seen); requireData(entry, seen); });
        } else if (!value.isNil() && !value.isBoolean() && !value.isNumber()
                && !value.isString() && !value.isUserdata()) {
            throw new IllegalArgumentException("JavaWrapper:luava must return data, not functions or threads");
        }
    }

    private static LuaValue convert(org.luava.runtime.LuaValue value,
                                    IdentityHashMap<org.luava.runtime.LuaValue, LuaValue> seen) {
        if (value.isNil()) return LuaValue.NIL;
        if (value.isBoolean()) return LuaValue.valueOf(value.toBoolean());
        if (value.isNumber()) return LuaValue.valueOf(value.toDouble());
        if (value instanceof LuaString string) return LuaValue.valueOf(string.latin1Bytes());
        if (value instanceof LuaTable table) {
            LuaValue previous = seen.get(table);
            if (previous != null) return previous;
            var result = LuaValue.tableOf();
            seen.put(table, result);
            table.forEach((key, entry) -> result.set(convert(key, seen), convert(entry, seen)));
            return result;
        }
        if (value instanceof LuaUserdata userdata) return CoerceJavaToLua.coerce(userdata.getJavaInstance());
        throw new IllegalArgumentException("JavaWrapper:luava cannot return functions or threads to LuaJ; return data instead");
    }
}
