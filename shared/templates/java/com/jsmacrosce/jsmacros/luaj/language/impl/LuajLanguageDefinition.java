package @CORE_PACKAGE@.luaj.language.impl;

import @CORE_PACKAGE@.core.Core;
import @CORE_PACKAGE@.core.config.ScriptTrigger;
import @CORE_PACKAGE@.core.event.BaseEvent;
import @CORE_PACKAGE@.core.language.BaseLanguage;
import @CORE_PACKAGE@.core.language.BaseScriptContext;
import @CORE_PACKAGE@.core.language.EventContainer;
import @CORE_PACKAGE@.luaj.LuajExtension;
import @CORE_PACKAGE@.luaj.config.LuajConfig;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaClosure;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Prototype;
import org.luaj.vm2.lib.jse.CoerceJavaToLua;
import org.luaj.vm2.lib.jse.JsePlatform;
import org.jsmacros.lua.shared.LuaCollectionSupport;
import org.jsmacros.lua.shared.LuaSharedGlobals;
import org.jsmacros.multilang.shared.lua.LuaScriptBindings;

import java.io.File;
import java.io.FileInputStream;
import java.util.Map;

public class LuajLanguageDefinition extends BaseLanguage<Globals, LuajScriptContext> {

    public static final Map<String, Globals> namedGlobals = new java.util.concurrent.ConcurrentHashMap<>();

    public static final Map<String, LuajScriptContext> globalOwners = new java.util.concurrent.ConcurrentHashMap<>();

    public LuajLanguageDefinition(LuajExtension extension, Core<?, ?> runner) {
        super(extension, runner);
    }

    protected void execContext(EventContainer<LuajScriptContext> ctx, Executor e) throws Exception {
        LuajConfig cfg = runner.config.getOptions(LuajConfig.class);
        LuaSharedGlobals.Claim claim = LuaSharedGlobals.claim(ctx.getCtx(), cfg.useGlobalContext, cfg.splitGlobalContext, JsePlatform::debugGlobals);
        Globals globals = claim.globals();
        LuaCollectionSupport.install(globals);

        ctx.getCtx().setContext(globals);
        retrieveLibs(ctx.getCtx()).forEach((name, lib) -> globals.set(name, CoerceJavaToLua.coerce(lib)));

        try {
            e.accept(globals);
        } finally {
            LuaSharedGlobals.releaseClaim(ctx.getCtx(), claim.poolName());
        }
    }

    public static void releaseGlobal(LuajScriptContext ctx) {
        LuaSharedGlobals.releaseGlobal(ctx);
    }

    public static boolean destroyPool(String poolName) {
        return LuaSharedGlobals.destroyPool(poolName);
    }

    public static String getOwnedPool(LuajScriptContext ctx) {
        return LuaSharedGlobals.getOwnedPool(ctx);
    }

    @Override
    protected void exec(EventContainer<LuajScriptContext> ctx, ScriptTrigger macro, BaseEvent event) throws Exception {
        execContext(ctx, (globals) -> {
            LuaScriptBindings.bindScriptValues(globals, ctx.getCtx(), event,
                ctx.getCtx().getFile(), ctx, PerContextLuaValue::new);

            retrieveOnceLibs().forEach((name, lib) -> globals.set(name, CoerceJavaToLua.coerce(lib)));

            retrievePerExecLibs(ctx.getCtx()).forEach((name, lib) -> LuaScriptBindings.bind(globals, ctx.getCtx(), name, CoerceJavaToLua.coerce(lib), PerContextLuaValue::new));

            File file = ctx.getCtx().getFile();
            String chunkName = "@" + file.getCanonicalPath();
            LuaClosure current;
            try (FileInputStream stream = new FileInputStream(file)) {
                Prototype prototype = globals.loadPrototype(stream, chunkName, "bt");
                current = (LuaClosure) globals.loader.load(prototype, chunkName, globals);
            }
            current.call();
        });
    }

    @Override
    protected void exec(EventContainer<LuajScriptContext> ctx, String lang, String script, BaseEvent event) throws Exception {
        execContext(ctx, (globals) -> {
            LuaScriptBindings.bindScriptValues(globals, ctx.getCtx(), event,
                ctx.getCtx().getFile(), ctx, PerContextLuaValue::new);

            LuaValue current = globals.load(checkScript(script));
            current.call();
        });
    }

    @Override
    public LuajScriptContext createContext(BaseEvent event, File file) {
        return new LuajScriptContext(runner, event, file);
    }

    private interface Executor {
        void accept(Globals globals) throws Exception;
    }

    public static String checkScript(String script) {
        return LuaSharedGlobals.checkScript(script);
    }
}
