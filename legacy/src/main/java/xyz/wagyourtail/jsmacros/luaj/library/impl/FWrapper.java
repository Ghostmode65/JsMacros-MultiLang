package xyz.wagyourtail.jsmacros.luaj.library.impl;

import xyz.wagyourtail.jsmacros.core.Core;
import xyz.wagyourtail.jsmacros.luaj.LuajExtension;
import xyz.wagyourtail.jsmacros.luava.LuavaChunkRunner;
import xyz.wagyourtail.jsmacros.luava.LuavaHelper;
import java.io.File;
import java.util.Objects;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaClosure;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.jse.CoerceLuaToJava;
import org.jsmacros.lua.shared.JavaIntrospection;
import org.jsmacros.multilang.shared.lua.LuaWrapperFunctions;
import xyz.wagyourtail.jsmacros.core.MethodWrapper;
import xyz.wagyourtail.jsmacros.core.library.IFWrapper;
import xyz.wagyourtail.jsmacros.core.library.Library;
import xyz.wagyourtail.jsmacros.core.library.PerExecLanguageLibrary;
import xyz.wagyourtail.jsmacros.luaj.language.impl.LuajLanguageDefinition;
import xyz.wagyourtail.jsmacros.luaj.language.impl.LuajScriptContext;

@Library(value = "JavaWrapper", languages = LuajLanguageDefinition.class)
public class FWrapper extends PerExecLanguageLibrary<Globals, LuajScriptContext> implements IFWrapper<LuaClosure> {

    public FWrapper(LuajScriptContext context, Class language) {
        super(context, language);
    }

    @Override
    public <A, B, R> MethodWrapper<A, B, R, LuajScriptContext> methodToJava(LuaClosure luaClosure) {
        return new LuaMethodWrapper<>(luaClosure, true, ctx);
    }

    @Override
    public <A, B, R> MethodWrapper<A, B, R, LuajScriptContext> methodToJavaAsync(LuaClosure luaClosure) {
        return new LuaMethodWrapper<>(luaClosure, false, ctx);
    }
    
    @Override
    public void stop() {
        ctx.closeContext();
    }


    public LuavaHelper luava() throws Exception { return luava(true, false); }

    public LuavaHelper luava(boolean jit) throws Exception { return luava(jit, false); }

    public LuavaHelper luava(boolean jit, boolean async) throws Exception {
        requireLuava();
        return new LuavaHelper(Core.getInstance(), ctx, jit, async, true);
    }

    public LuaValue luava(String source) throws Exception { return luava(true, source); }

    public LuaValue luava(File file) throws Exception { return luava(true, file); }

    public LuaValue luava(boolean jit, String source) throws Exception { return luava(jit, false, source); }

    public LuaValue luava(boolean jit, File file) throws Exception { return luava(jit, false, file); }

    public LuaValue luava(boolean jit, boolean async, String source) throws Exception {
        requireLuava();
        Objects.requireNonNull(source, "source");
        if (async) {
            LuavaChunkRunner.startAsync(Core.getInstance(), ctx, jit, source, null);
            return LuaValue.NIL;
        }
        return LuavaChunkRunner.fromLuaJ(Core.getInstance(), ctx, jit, source, null);
    }

    public LuaValue luava(boolean jit, boolean async, File file) throws Exception {
        requireLuava();
        Objects.requireNonNull(file, "file");
        if (async) {
            LuavaChunkRunner.startAsync(Core.getInstance(), ctx, jit, null, file);
            return LuaValue.NIL;
        }
        return LuavaChunkRunner.fromLuaJ(Core.getInstance(), ctx, jit, null, file);
    }

    private static void requireLuava() {
        if (Runtime.version().feature() < 21) throw new LuaError("JavaWrapper:luava requires Java 21 or newer");
    }

    public LuaValue toDouble(Object value) {
        return LuaWrapperFunctions.toDouble(value);
    }

    public LuaValue toFloat(Object value) {
        return LuaWrapperFunctions.toFloat(value);
    }

    public LuaValue toInteger(Object value) {
        return LuaWrapperFunctions.toInteger(value);
    }

    public LuaValue toLong(Object value) {
        return LuaWrapperFunctions.toLong(value);
    }

    public String unicode(Object value) {
        return LuaWrapperFunctions.unicode(value);
    }

    public JavaIntrospection.Target list(Object target) {
        return JavaIntrospection.list(target);
    }

    public LuaTable pcall(LuaValue fn) {
        return pcall(fn, LuaValue.NONE);
    }

    public LuaTable pcall(LuaValue fn, Varargs args) {
        try {
            return LuaWrapperFunctions.wrapCallResult(true, fn.invoke(args));
        } catch (LuaError ex) {
            return wrapError(ex);
        } catch (Exception ex) {
            return wrapError(new LuaError(ex));
        }
    }

    public LuaTable xcall(LuaValue fn, LuaValue err) {
        return xcall(fn, err, LuaValue.NONE);
    }

    public LuaTable xcall(LuaValue fn, LuaValue err, Varargs args) {
        try {
            return LuaWrapperFunctions.wrapCallResult(true, fn.invoke(args));
        } catch (LuaError ex) {
            return handleError(err, ex);
        } catch (Exception ex) {
            return handleError(err, new LuaError(ex));
        }
    }

    private LuaTable handleError(LuaValue handler, LuaError error) {
        LuaErrorHelper helper = new LuaErrorHelper(error);
        try {
            return wrapError(helper, handler.call(createErrorTable(helper)));
        } catch (LuaError handlerError) {
            return wrapError(handlerError);
        } catch (Exception handlerError) {
            return wrapError(new LuaError(handlerError));
        }
    }

    public LuaTable xpcall(LuaValue fn, LuaValue err) {
        return xcall(fn, err);
    }

    public LuaTable xpcall(LuaValue fn, LuaValue err, Varargs args) {
        return xcall(fn, err, args);
    }

    private LuaTable wrapError(LuaError ex) {
        return wrapError(new LuaErrorHelper(ex), null);
    }

    private LuaTable wrapError(LuaErrorHelper helper, LuaValue handled) {
        LuaTable table = LuaValue.tableOf();
        LuaTable error = createErrorTable(helper);
        table.set("ok", LuaValue.FALSE);
        table.set("error", error);
        table.set("getString", helper.getString() == null ? LuaValue.NIL : LuaValue.valueOf(helper.getString()));
        table.set("handled", handled == null ? LuaValue.NIL : handled);
        table.set(1, LuaValue.FALSE);
        table.set(2, error);
        if (handled != null) {
            table.set(3, handled);
        }
        return table;
    }

    private LuaTable createErrorTable(LuaErrorHelper helper) {
        LuaTable table = LuaValue.tableOf();
        table.set("getString", helper.getString() == null ? LuaValue.NIL : LuaValue.valueOf(helper.getString()));
        table.set("cause", helper.getCause() == null ? LuaValue.NIL : LuaValue.userdataOf(helper.getCause()));
        table.set("class", helper.getClassName() == null ? LuaValue.NIL : LuaValue.valueOf(helper.getClassName()));
        table.set("causeMessage", helper.getCauseMessage() == null ? LuaValue.NIL : LuaValue.valueOf(helper.getCauseMessage()));
        table.set("file", helper.getFile() == null ? LuaValue.NIL : LuaValue.valueOf(helper.getFile()));
        table.set("line", helper.getLine());
        return table;
    }

    public static class LuaErrorHelper {
        private final LuaError error;

        public LuaErrorHelper(LuaError error) {
            this.error = error;
        }

        public String getString() {
            return error.getMessage();
        }

        public Throwable getCause() {
            return error.getCause();
        }

        public String getClassName() {
            Throwable cause = error.getCause();
            if (cause == null) return null;
            return cause.getClass().getName();
        }

        public String getCauseMessage() {
            Throwable cause = error.getCause();
            if (cause == null) return null;
            return cause.getMessage();
        }

        public String getFile() {
            if (error.getFile() == null) return null;
            return error.getFile().getAbsolutePath();
        }

        public int getLine() {
            return error.getLine();
        }

        @Override
        public String toString() {
            return error.getMessage();
        }
    }
}
