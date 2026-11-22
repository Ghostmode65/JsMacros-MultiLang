package @CORE_PACKAGE@.luava;

import @CORE_PACKAGE@.core.language.BaseLanguage;
import @CORE_PACKAGE@.core.library.Library;
import @CORE_PACKAGE@.core.library.PerExecLanguageLibrary;
import org.jsmacros.multilang.shared.luava.LuavaExecutionSupport;
import org.jsmacros.multilang.shared.luava.LuavaSharedGlobals;
import org.luava.runtime.LuaValue;

@Library(value = "GlobalContext", languages = LuavaLanguageDefinition.class)
public final class LuavaGlobalContextLib extends PerExecLanguageLibrary<LuavaExecutionSupport.Session, LuavaScriptContext> {
    public LuavaGlobalContextLib(LuavaScriptContext context, Class<? extends BaseLanguage<LuavaExecutionSupport.Session, LuavaScriptContext>> language) {
        super(context, language);
    }

    public String getPool() { return ctx.bindings.pool; }
    public boolean isGlobal() { return getPool() != null; }
    public void release() {
        LuavaSharedGlobals.release(ctx);
        ctx.bindings.pool = null;
    }
    public boolean destroyPool(String name) { return LuavaSharedGlobals.destroy(name); }
    public LuaValue loadscript(String script, String name) throws Exception {
        return LuavaSharedGlobals.loadscript(script, name, ctx.bindings);
    }
}
