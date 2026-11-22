package @CORE_PACKAGE@.luava;

import @CORE_PACKAGE@.core.MethodWrapper;
import @CORE_PACKAGE@.core.Core;
import @CORE_PACKAGE@.luaj.LuajExtension;
import org.jsmacros.multilang.shared.luava.LuavaExecutionSupport;
import org.luava.runtime.LuaValue;

final class LuavaMethodWrapper<T, U, R> extends MethodWrapper<T, U, R, LuavaScriptContext> {
    private final LuaValue function;
    private final boolean await;

    LuavaMethodWrapper(LuaValue function, boolean await, LuavaScriptContext context) {
        super(context);
        if (function == null || !function.isFunction()) throw new IllegalArgumentException("Expected a Lua function");
        this.function = function;
        this.await = await;
    }

    private LuaValue invoke(Object... arguments) {
        Thread thread = Thread.currentThread();
        boolean bound = ctx.isInlineCaller(thread) || ctx.getBoundThreads().contains(thread);
        boolean newlyBound = false;
        boolean joined = false;
        try {
            if (!bound) {
                @LUAVA_BIND_THREAD@;
                newlyBound = true;
                if (await && @LUA_RUNNER@.profile.checkJoinedThreadStack()) {
                    joined = @LUA_RUNNER@.profile.joinedThreadStack.add(thread);
                }
            }
            return ctx.call(() -> ctx.getContext().invoke(ctx.bindings, function, arguments));
        } catch (RuntimeException error) {
            throw error;
        } catch (Exception error) {
            if (error instanceof InterruptedException) thread.interrupt();
            throw new RuntimeException(error);
        } finally {
            if (newlyBound) {
                ctx.releaseBoundEventIfPresent(thread);
                ctx.unbindThread(thread);
                if (joined) @LUA_RUNNER@.profile.joinedThreadStack.remove(thread);
            }
        }
    }

    private void acceptArguments(Object... arguments) {
        if (await) invoke(arguments);
        else {
            Thread thread = new Thread(() -> {
                try { invoke(arguments); }
                catch (Throwable error) { @LUA_RUNNER@.profile.logError(error); }
            }, "Luava-JavaWrapper");
            thread.setDaemon(true);
            thread.start();
        }
    }

    @SuppressWarnings("unchecked")
    private R applyArguments(Object... arguments) { return (R) LuavaExecutionSupport.toJava(invoke(arguments)); }

    @Override public void accept(T value) { acceptArguments(value); }
    @Override public void accept(T first, U second) { acceptArguments(first, second); }
    @Override public R apply(T value) { return applyArguments(value); }
    @Override public R apply(T first, U second) { return applyArguments(first, second); }
    @Override public boolean test(T value) { return LuavaExecutionSupport.first(invoke(value)).toBoolean(); }
    @Override public boolean test(T first, U second) { return LuavaExecutionSupport.first(invoke(first, second)).toBoolean(); }
    @Override public void run() { acceptArguments(); }
    @Override public R get() { return applyArguments(); }
    @Override public int compare(T first, T second) { return ((Number) LuavaExecutionSupport.toJava(invoke(first, second))).intValue(); }
}
