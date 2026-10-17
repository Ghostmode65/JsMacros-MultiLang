package @CORE_PACKAGE@.luava;

import @CORE_PACKAGE@.core.Core;
import @CORE_PACKAGE@.luaj.LuajExtension;
import @CORE_PACKAGE@.luava.LuavaChunkRunner;
import @CORE_PACKAGE@.luava.LuavaHelper;
import java.io.File;
import java.util.Objects;

import @CORE_PACKAGE@.core.MethodWrapper;
import @CORE_PACKAGE@.core.language.BaseLanguage;
import @CORE_PACKAGE@.core.library.IFWrapper;
import @CORE_PACKAGE@.core.library.Library;
import @CORE_PACKAGE@.core.library.PerExecLanguageLibrary;
import org.jsmacros.lua.shared.JavaIntrospection;
import org.jsmacros.multilang.shared.lua.LuaWrapperFunctions;
import org.jsmacros.multilang.shared.luava.LuavaExecutionSupport;
import org.luava.binding.LuaDataConverter;
import org.luava.runtime.*;

@Library(value = "JavaWrapper", languages = LuavaLanguageDefinition.class)
public final class LuavaFWrapper extends PerExecLanguageLibrary<LuavaExecutionSupport.Session, LuavaScriptContext>
        implements IFWrapper<LuaValue> {
    public LuavaFWrapper(LuavaScriptContext context, Class<? extends BaseLanguage<LuavaExecutionSupport.Session, LuavaScriptContext>> language) {
        super(context, language);
    }

    @Override
    public <A, B, R> MethodWrapper<A, B, R, ?> methodToJava(LuaValue function) {
        return new LuavaMethodWrapper<>(function, true, ctx);
    }

    @Override
    public <A, B, R> MethodWrapper<A, B, R, ?> methodToJavaAsync(LuaValue function) {
        return new LuavaMethodWrapper<>(function, false, ctx);
    }

    @Override public void stop() { ctx.closeContext(); }
    @Override public void deferCurrentTask() throws InterruptedException { ctx.wrapSleep(() -> {}); }


    public LuavaHelper luava() throws Exception { return luava(true, false); }

    public LuavaHelper luava(boolean jit) throws Exception { return luava(jit, false); }

    public LuavaHelper luava(boolean jit, boolean async) throws Exception {
        requireLuava();
        return new LuavaHelper(@LUA_RUNNER@, ctx, jit, async, false);
    }

    public LuaValue luava(String source) throws Exception { return luava(true, source); }

    public LuaValue luava(File file) throws Exception { return luava(true, file); }

    public LuaValue luava(boolean jit, String source) throws Exception { return luava(jit, false, source); }

    public LuaValue luava(boolean jit, File file) throws Exception { return luava(jit, false, file); }

    public LuaValue luava(boolean jit, boolean async, String source) throws Exception {
        requireLuava();
        Objects.requireNonNull(source, "source");
        if (async) {
            LuavaChunkRunner.startAsync(@LUA_RUNNER@, ctx, jit, source, null);
            return LuaNil.NIL;
        }
        return LuavaChunkRunner.fromLuava(@LUA_RUNNER@, ctx, jit, source, null);
    }

    public LuaValue luava(boolean jit, boolean async, File file) throws Exception {
        requireLuava();
        Objects.requireNonNull(file, "file");
        if (async) {
            LuavaChunkRunner.startAsync(@LUA_RUNNER@, ctx, jit, null, file);
            return LuaNil.NIL;
        }
        return LuavaChunkRunner.fromLuava(@LUA_RUNNER@, ctx, jit, null, file);
    }

    private static void requireLuava() {
        if (Runtime.version().feature() < 21) throw new LuaException("JavaWrapper:luava requires Java 21 or newer");
    }

    public LuaValue toDouble(LuaValue value) { return new LuaUserdata(Double.valueOf(value.toLuaNumber().toDouble())); }
    public LuaValue toFloat(LuaValue value) { return new LuaUserdata(Float.valueOf((float) value.toLuaNumber().toDouble())); }
    public LuaValue toInteger(LuaValue value) { return new LuaUserdata(Integer.valueOf((int) value.toLuaNumber().toDouble())); }
    public LuaValue toLong(LuaValue value) { return new LuaUserdata(Long.valueOf(value.toLuaIntegerCoercingStrings().toLong())); }
    public String unicode(Object value) { return LuaWrapperFunctions.unicode(value); }

    public Inspection list(LuaValue target) {
        return new Inspection(JavaIntrospection.list(LuaDataConverter.toJava(target, Object.class)));
    }

    public LuaTable pcall(LuaValue function, LuaValue... arguments) {
        try { return LuavaExecutionSupport.result(function.call(arguments)); }
        catch (RuntimeException error) { return failure(error, null, null); }
    }

    public LuaTable xcall(LuaValue function, LuaValue handler, LuaValue... arguments) {
        try { return LuavaExecutionSupport.result(function.call(arguments)); }
        catch (RuntimeException error) {
            LuaTable info = errorInfo(error);
            try { return failure(error, info, LuavaExecutionSupport.first(handler.call(info))); }
            catch (RuntimeException handlerError) { return failure(handlerError, null, null); }
        }
    }

    public LuaTable xpcall(LuaValue function, LuaValue handler, LuaValue... arguments) {
        return xcall(function, handler, arguments);
    }

    private LuaTable failure(Throwable error, LuaTable info, LuaValue handled) {
        LuaTable result = new LuaTable();
        if (info == null) info = errorInfo(error);
        result.rawset(LuaValue.valueOf("ok"), LuaValue.valueOf(false));
        result.rawsetInt(1, LuaValue.valueOf(false));
        result.rawset(LuaValue.valueOf("error"), info);
        result.rawsetInt(2, info);
        result.rawset(LuaValue.valueOf("message"), LuaDataConverter.toLua(error.getMessage()));
        result.rawset(LuaValue.valueOf("handled"), handled == null ? LuaNil.NIL : handled);
        if (handled != null) result.rawsetInt(3, handled);
        return result;
    }

    private LuaTable errorInfo(Throwable error) {
        var located = error instanceof org.jsmacros.multilang.shared.luava.LuavaScriptException e ? e
            : error instanceof LuaException e ? new org.jsmacros.multilang.shared.luava.LuavaScriptException(e, ctx.getFile()) : null;
        LuaTable info = new LuaTable();
        info.rawset(LuaValue.valueOf("object"), LuaDataConverter.toLuaLive(error));
        info.rawset(LuaValue.valueOf("message"), LuaDataConverter.toLua(error.getMessage()));
        info.rawset(LuaValue.valueOf("errorMessage"), LuaDataConverter.toLua(error.getMessage()));
        Throwable cause = error.getCause();
        info.rawset(LuaValue.valueOf("cause"), LuaDataConverter.toLuaLive(cause));
        info.rawset(LuaValue.valueOf("causeClass"), LuaDataConverter.toLua(cause == null ? null : cause.getClass().getName()));
        info.rawset(LuaValue.valueOf("causeMessage"), LuaDataConverter.toLua(cause == null ? null : cause.getMessage()));
        info.rawset(LuaValue.valueOf("file"), LuaDataConverter.toLua(located == null || located.file == null ? null : located.file.getAbsolutePath()));
        info.rawset(LuaValue.valueOf("line"), LuaValue.valueOf(located == null ? -1 : located.line));
        return info;
    }

    public static final class Inspection {
        private final JavaIntrospection.Target target;
        public Inspection(JavaIntrospection.Target target) { this.target = target; }
        public String getName() { return target.getName(); }
        public Class<?> getType() { return target.getType(); }
        public LuaValue methods() { return methods(false); }
        public LuaValue fields() { return fields(false); }
        public LuaValue methods(boolean declared) { return convert(target.methods(declared)); }
        public LuaValue fields(boolean declared) { return convert(target.fields(declared)); }
        private static LuaValue convert(org.luaj.vm2.LuaValue value) {
            if (value.isnil()) return LuaNil.NIL;
            if (value.isboolean()) return LuaValue.valueOf(value.toboolean());
            if (value.isnumber()) return value.isinttype() ? LuaValue.valueOf(value.tolong()) : LuaValue.valueOf(value.todouble());
            if (value.istable()) {
                LuaTable table = new LuaTable();
                org.luaj.vm2.LuaValue key = org.luaj.vm2.LuaValue.NIL;
                while (true) {
                    var pair = value.next(key);
                    key = pair.arg1();
                    if (key.isnil()) return table;
                    table.rawset(convert(key), convert(pair.arg(2)));
                }
            }
            return LuaDataConverter.toLua(value.tojstring());
        }
    }
}
