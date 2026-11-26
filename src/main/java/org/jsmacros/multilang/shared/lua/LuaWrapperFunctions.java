package org.jsmacros.multilang.shared.lua;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;

public final class LuaWrapperFunctions {
    private LuaWrapperFunctions() {
    }

    public static LuaValue toDouble(Object value) {
        return LuaValue.userdataOf(Double.valueOf(toNumber(value, "toDouble").doubleValue()));
    }

    public static LuaValue toFloat(Object value) {
        return LuaValue.userdataOf(Float.valueOf(toNumber(value, "toFloat").floatValue()));
    }

    public static LuaValue toInteger(Object value) {
        return LuaValue.userdataOf(Integer.valueOf(toNumber(value, "toInteger").intValue()));
    }

    public static LuaValue toLong(Object value) {
        double number = toNumber(value, "toLong").doubleValue();
        if (number != (long) number) {
            throw new LuaError("number has no exact long value");
        }
        return LuaValue.userdataOf(Long.valueOf((long) number));
    }

    public static String unicode(Object value) {
        if (value instanceof Number) {
            return new String(Character.toChars(((Number) value).intValue()));
        }
        String text = String.valueOf(value);
        if (text.startsWith("U+") || text.startsWith("u+")) {
            return new String(Character.toChars(Integer.parseInt(text.substring(2), 16)));
        }
        return text;
    }

    public static LuaTable wrapCallResult(boolean ok, Varargs values) {
        LuaTable table = LuaValue.tableOf();
        LuaTable result = LuaValue.tableOf();
        table.set("ok", LuaValue.valueOf(ok));
        table.set(1, LuaValue.valueOf(ok));
        for (int i = 1; i <= values.narg(); ++i) {
            result.set(i, values.arg(i));
        }
        table.set("result", result);
        table.set(2, result);
        return table;
    }

    private static Number toNumber(Object value, String method) {
        if (value instanceof Number) {
            return (Number) value;
        }
        if (value instanceof LuaValue) {
            LuaValue luaValue = (LuaValue) value;
            if (luaValue.isnumber()) {
                return Double.valueOf(luaValue.todouble());
            }
        }
        throw new LuaError("bad argument #1 to '" + method + "' (number expected, got "
            + (value == null ? "nil" : value.getClass().getName()) + ")");
    }
}
