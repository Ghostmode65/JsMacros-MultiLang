package xyz.wagyourtail.jsmacros.luaj.language.impl;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaClosure;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.jse.CoerceJavaToLua;
import org.luaj.vm2.lib.jse.JsePlatform;
import org.jsmacros.lua.shared.LuaSharedGlobals;
import org.jsmacros.multilang.shared.lua.LuaScriptBindings;
import xyz.wagyourtail.jsmacros.core.Core;
import xyz.wagyourtail.jsmacros.core.config.ScriptTrigger;
import xyz.wagyourtail.jsmacros.core.event.BaseEvent;
import xyz.wagyourtail.jsmacros.core.language.BaseLanguage;
import xyz.wagyourtail.jsmacros.core.language.BaseScriptContext;
import xyz.wagyourtail.jsmacros.core.language.EventContainer;
import xyz.wagyourtail.jsmacros.luaj.LuajExtension;
import xyz.wagyourtail.jsmacros.luaj.config.LuajConfig;

import java.io.File;
import java.util.Map;

public class LuajLanguageDefinition extends BaseLanguage<Globals, LuajScriptContext> {

    public static final Map<String, Globals> namedGlobals = new java.util.concurrent.ConcurrentHashMap<>();

    public static final ThreadLocal<String> activeLoadscriptPool = new ThreadLocal<>();

    public static final Map<String, LuajScriptContext> globalOwners = new java.util.concurrent.ConcurrentHashMap<>();

    public LuajLanguageDefinition(LuajExtension extension, Core runner) {
        super(extension, runner);
    }

    protected void execContext(EventContainer<LuajScriptContext> ctx, Executor e) throws Exception {
        LuajConfig cfg = runner.config.getOptions(LuajConfig.class);
        LuaSharedGlobals.Claim claim = LuaSharedGlobals.claim(ctx.getCtx(), cfg.useGlobalContext, cfg.splitGlobalContext, JsePlatform::debugGlobals);
        Globals globals = claim.globals();

        ctx.getCtx().setContext(globals);
        retrieveLibs(ctx.getCtx()).forEach((name, lib) -> globals.set(name, CoerceJavaToLua.coerce(lib)));

        try {
            e.accept(globals);
        } catch (LuaError le) {
            Throwable cause = le.getCause();
            if (cause instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                throw (InterruptedException) cause;
            }
            throw le;
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
            
            retrievePerExecLibs((BaseScriptContext) ctx.getCtx()).forEach((name, lib) -> LuaScriptBindings.bind(globals, ctx.getCtx(), (String) name, CoerceJavaToLua.coerce(lib), PerContextLuaValue::new));
            
            LuaClosure current = (LuaClosure) globals.loadfile(ctx.getCtx().getFile().getCanonicalPath());
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
        return new LuajScriptContext(event, file);
    }
    
    private interface Executor {
        void accept(Globals globals) throws Exception;
    }

    public static String checkScript(String script) {
        return LuaSharedGlobals.checkScript(script);
    }
}
