package @CORE_PACKAGE@.luaj.library.impl;

import @CORE_PACKAGE@.core.language.BaseLanguage;
import @CORE_PACKAGE@.core.library.Library;
import @CORE_PACKAGE@.core.library.PerExecLanguageLibrary;
import @CORE_PACKAGE@.luaj.language.impl.LuajLanguageDefinition;
import @CORE_PACKAGE@.luaj.language.impl.LuajScriptContext;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaValue;
import org.jsmacros.lua.shared.LuaSharedGlobals;

@Library(value = "GlobalContext", languages = LuajLanguageDefinition.class)
public class LuajGlobalContextLib extends PerExecLanguageLibrary<Globals, LuajScriptContext> {

    public LuajGlobalContextLib(LuajScriptContext context, Class<? extends BaseLanguage<Globals, LuajScriptContext>> language) {
        super(context, language);
    }

    public String getPool() {
        return LuajLanguageDefinition.getOwnedPool(ctx);
    }

    public boolean isGlobal() {
        return LuajLanguageDefinition.getOwnedPool(ctx) != null;
    }

    public void release() {
        LuajLanguageDefinition.releaseGlobal(ctx);
    }

    public boolean destroyPool(String poolName) {
        return LuajLanguageDefinition.destroyPool(poolName);
    }

    public LuaValue loadscript(String script, String poolName) {
        return LuaSharedGlobals.loadscript(script, poolName, ctx.getContext());
    }
}
