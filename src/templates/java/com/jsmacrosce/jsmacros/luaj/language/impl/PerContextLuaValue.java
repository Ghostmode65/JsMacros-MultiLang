package @CORE_PACKAGE@.luaj.language.impl;

import org.jsmacros.multilang.shared.lua.SharedPerContextLuaValue;
import @CORE_PACKAGE@.luaj.LuajExtension;

public final class PerContextLuaValue extends SharedPerContextLuaValue {
    public PerContextLuaValue() {
        super(() -> LuajExtension.RUNNER.getContexts().stream()
            .filter(context -> context.getBoundThreads().contains(Thread.currentThread()))
            .findFirst().orElseThrow(RuntimeException::new));
    }
}
