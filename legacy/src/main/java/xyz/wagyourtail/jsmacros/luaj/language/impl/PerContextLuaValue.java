package xyz.wagyourtail.jsmacros.luaj.language.impl;

import org.jsmacros.multilang.shared.lua.SharedPerContextLuaValue;
import xyz.wagyourtail.jsmacros.core.Core;

public final class PerContextLuaValue extends SharedPerContextLuaValue {
    public PerContextLuaValue() {
        super(() -> Core.getInstance().getContexts().stream()
            .filter(context -> context.getBoundThreads().contains(Thread.currentThread()))
            .findFirst().orElseThrow(RuntimeException::new));
    }
}
