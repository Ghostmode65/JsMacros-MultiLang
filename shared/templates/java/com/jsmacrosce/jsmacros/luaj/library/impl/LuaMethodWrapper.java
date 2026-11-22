package @CORE_PACKAGE@.luaj.library.impl;

import @CORE_PACKAGE@.core.Core;
import @CORE_PACKAGE@.core.MethodWrapper;
import @CORE_PACKAGE@.luaj.LuajExtension;
import @CORE_PACKAGE@.luaj.language.impl.LuajScriptContext;
import org.luaj.vm2.LuaClosure;
import org.luaj.vm2.lib.jse.CoerceJavaToLua;
import org.luaj.vm2.lib.jse.CoerceLuaToJava;

import java.util.function.Supplier;

final class LuaMethodWrapper<T, U, R> extends MethodWrapper<T, U, R, LuajScriptContext> {
    private final LuaClosure fn;
    private final boolean await;

    LuaMethodWrapper(LuaClosure fn, boolean await, LuajScriptContext ctx) {
        super(ctx);
        this.fn = fn;
        this.await = await;
    }

    private void internal_accept(Runnable accepted, boolean await) {
        if (await) {
            internal_apply(() -> {
                accepted.run();
                return null;
            });
            return;
        }

        Thread th = new Thread(() -> {
            ctx.bindThread(Thread.currentThread());
            try {
                accepted.run();
            } catch (Throwable ex) {
                @LUA_RUNNER@.profile.logError(ex);
            } finally {
                ctx.releaseBoundEventIfPresent(Thread.currentThread());
                ctx.unbindThread(Thread.currentThread());
                @LUA_RUNNER@.profile.joinedThreadStack.remove(Thread.currentThread());
            }
        });
        th.start();
    }

    private <A> A internal_apply(Supplier<A> supplier) {
        if (ctx.getBoundThreads().contains(Thread.currentThread())) {
            return supplier.get();
        }
        try {
            ctx.bindThread(Thread.currentThread());
            if (@LUA_RUNNER@.profile.checkJoinedThreadStack()) {
                @LUA_RUNNER@.profile.joinedThreadStack.add(Thread.currentThread());
            }
            return supplier.get();
        } catch (Throwable ex) {
            throw new RuntimeException(ex);
        } finally {
            ctx.releaseBoundEventIfPresent(Thread.currentThread());
            ctx.unbindThread(Thread.currentThread());
            @LUA_RUNNER@.profile.joinedThreadStack.remove(Thread.currentThread());
        }
    }

    @Override
    public void accept(T t) {
        internal_accept(() -> fn.call(CoerceJavaToLua.coerce(t)), await);
    }

    @Override
    public void accept(T t, U u) {
        internal_accept(() -> fn.call(CoerceJavaToLua.coerce(t), CoerceJavaToLua.coerce(u)), await);
    }

    @Override
    public R apply(T t) {
        return internal_apply(() -> (R) CoerceLuaToJava.coerce(fn.call(CoerceJavaToLua.coerce(t)), Object.class));
    }

    @Override
    public R apply(T t, U u) {
        return internal_apply(() -> (R) CoerceLuaToJava.coerce(fn.call(CoerceJavaToLua.coerce(t), CoerceJavaToLua.coerce(u)), Object.class));
    }

    @Override
    public boolean test(T t) {
        return internal_apply(() -> fn.call(CoerceJavaToLua.coerce(t)).toboolean());
    }

    @Override
    public boolean test(T t, U u) {
        return internal_apply(() -> fn.call(CoerceJavaToLua.coerce(t), CoerceJavaToLua.coerce(u)).toboolean());
    }

    @Override
    public void run() {
        internal_accept(fn::call, await);
    }

    @Override
    public int compare(T o1, T o2) {
        return internal_apply(() -> fn.call(CoerceJavaToLua.coerce(o1), CoerceJavaToLua.coerce(o2)).toint());
    }

    @Override
    public R get() {
        return internal_apply(() -> (R) CoerceLuaToJava.coerce(fn.call(), Object.class));
    }
}
