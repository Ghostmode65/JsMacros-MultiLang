package org.jsmacros.multilang.shared.lua;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.jse.CoerceJavaToLua;

import java.util.function.Supplier;

public final class LuaScriptBindings {
    private LuaScriptBindings() {
    }

    public static void bindScriptValues(Globals globals, Object contextKey, Object event,
                                        Object file, Object container,
                                        Supplier<? extends SharedPerContextLuaValue> newValue) {
        bind(globals, contextKey, "event", CoerceJavaToLua.coerce(event), newValue);
        bind(globals, contextKey, "file", CoerceJavaToLua.coerce(file), newValue);
        bind(globals, contextKey, "context", CoerceJavaToLua.coerce(container), newValue);
    }

    public static void bind(Globals globals, Object contextKey, String name, LuaValue value,
                            Supplier<? extends SharedPerContextLuaValue> newValue) {
        LuaValue current = globals.rawget(name);
        SharedPerContextLuaValue perContext = current instanceof SharedPerContextLuaValue
            ? (SharedPerContextLuaValue) current : newValue.get();
        perContext.addContext(contextKey, value);
        if (!(current instanceof SharedPerContextLuaValue)) {
            globals.rawset(name, perContext);
        }
    }
}
