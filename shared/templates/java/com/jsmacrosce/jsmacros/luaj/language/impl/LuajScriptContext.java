package @CORE_PACKAGE@.luaj.language.impl;

import @CORE_PACKAGE@.core.Core;
import @CORE_PACKAGE@.core.event.BaseEvent;
import @CORE_PACKAGE@.core.language.BaseScriptContext;
import org.luaj.vm2.Globals;
import org.jsmacros.lua.shared.LuaSharedGlobals;

import java.io.File;

public class LuajScriptContext extends BaseScriptContext<Globals> implements LuaSharedGlobals.ContextHandle {
    public LuajScriptContext(Core<?, ?> runner, BaseEvent event, File file) {
        super(runner, event, file);
    }

    @Override
    public boolean isMultiThreaded() {
        return true;
    }
}
