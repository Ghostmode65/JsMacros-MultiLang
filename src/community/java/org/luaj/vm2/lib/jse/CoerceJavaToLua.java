package org.luaj.vm2.lib.jse;

import org.luaj.vm2.LuaDouble;
import org.luaj.vm2.LuaInteger;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

import java.util.IdentityHashMap;
import java.util.Collection;
import java.util.Map;

public class CoerceJavaToLua {
    public static LuaValue coerce(Object value) {
        return coerce(value, null);
    }

    private static LuaValue coerce(Object value, IdentityHashMap<Object, LuaValue> seen) {
        if (value == null) {
            return LuaValue.NIL;
        }
        if (value instanceof Map<?, ?> map) {
            if (seen == null) seen = new IdentityHashMap<>();
            LuaValue existing = seen.get(map);
            if (existing != null) {
                return existing;
            }
            LuaTable table = LuaValue.tableOf();
            seen.put(map, table);
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                LuaValue key = coerce(entry.getKey(), seen);
                if (!key.isnil()) {
                    table.set(key, coerce(entry.getValue(), seen));
                }
            }
            return table;
        }
        if (value instanceof Collection<?> collection) {
            if (seen == null) seen = new IdentityHashMap<>();
            LuaValue existing = seen.get(collection);
            if (existing != null) {
                return existing;
            }
            LuaTable table = LuaValue.tableOf();
            seen.put(collection, table);
            int index = 1;
            for (Object element : collection) {
                table.set(index++, coerce(element, seen));
            }
            return table;
        }
        if (value instanceof Boolean bool) {
            return LuaValue.valueOf(bool);
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer) {
            return LuaInteger.valueOf(((Number) value).intValue());
        }
        if (value instanceof Character character) {
            return LuaInteger.valueOf(character);
        }
        if (value instanceof Long || value instanceof Float || value instanceof Double) {
            return LuaDouble.valueOf(((Number) value).doubleValue());
        }
        if (value instanceof String string) {
            return LuaValue.valueOf(string);
        }
        if (value instanceof byte[] bytes) {
            return LuaValue.valueOf(bytes);
        }
        if (value instanceof Class<?> type) {
            return JavaClass.forClass(type);
        }
        if (value.getClass().isArray()) {
            return new JavaArray(value);
        }
        if (value instanceof LuaValue luaValue) {
            return luaValue;
        }
        return new JavaInstance(value);
    }
}
