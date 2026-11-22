package org.jsmacros.multilang.shared.luava;

import org.luava.binding.JavaAccessPolicy;
import org.luava.binding.LuaDataConverter;
import org.luava.runtime.*;
import org.luava.runtime.concurrency.LuaCoroutine;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Semaphore;
import java.util.function.BooleanSupplier;

public final class LuavaExecutionSupport {
    private LuavaExecutionSupport() {}

    public static final class Bindings {
        public volatile File file;
        public final BooleanSupplier cancelled;
        public final BooleanSupplier jitEnabled;
        public final Map<String, LuaValue> values = new LinkedHashMap<>();
        public String pool;

        public Bindings(File file, BooleanSupplier cancelled, BooleanSupplier jitEnabled) {
            this.file = file;
            this.cancelled = cancelled;
            this.jitEnabled = jitEnabled;
        }

        public void put(String name, Object value) {
            values.put(name, LuavaJavaObjects.wrap(value));
        }
    }

    @FunctionalInterface
    public interface Sleep { void run() throws InterruptedException; }

    public static final class Session {
        public final LuaState state = new LuaState().javaPolicy(JavaAccessPolicy.UNRESTRICTED);
        private final Semaphore execution = new Semaphore(1, true);
        private final ThreadLocal<Integer> depth = ThreadLocal.withInitial(() -> 0);
        private volatile Bindings active;
        private final LuaState.LoopGuard cancellationGuard = () -> {
            Bindings bindings = active;
            if (bindings == null || bindings.cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                throw new LuaException("Context execution was cancelled.");
            }
        };

        public Session() {
            LuaTable metatable = new LuaTable();
            metatable.rawset(LuaValue.valueOf("__index"), LuaFunction.of(args -> {
                Bindings bindings = active;
                return bindings == null ? LuaNil.NIL
                    : bindings.values.getOrDefault(args[1].toLuaString(), LuaNil.NIL);
            }));
            state.getGlobals().setMetatable(metatable);
            state.loopGuard = cancellationGuard;
            state.jitEnabled(false);
        }

        public <T> T call(Bindings bindings, Callable<T> action) throws Exception {
            boolean nested = depth.get() > 0 || (LuaValue.activeBasicState() == state && active != null);
            if (!nested) execution.acquire();
            depth.set(depth.get() + 1);
            Bindings previous = active;
            LuaState.LoopGuard previousGuard = state.loopGuard;
            boolean previousJit = state.isJitEnabled();
            LuaCoroutine previousCoroutine = LuaCoroutine.running();
            LuaState previousState = LuaValue.pushBasicState(state);
            JavaAccessPolicy previousPolicy = JavaAccessPolicy.setActive(JavaAccessPolicy.UNRESTRICTED);
            active = bindings;
            if (!nested) LuaCoroutine.setCurrent(LuaCoroutine.createMainThread());
            try {
                if (!nested) {
                    boolean jit = bindings.jitEnabled.getAsBoolean();
                    state.loopGuard = jit ? null : cancellationGuard;
                    state.jitEnabled(jit);
                }
                if (bindings.cancelled.getAsBoolean()) throw new LuaException("Context execution was cancelled.");
                return action.call();
            } catch (LuaException error) {
                throw new LuavaScriptException(error, bindings.file);
            } finally {
                active = previous;
                if (!nested) {
                    state.loopGuard = previousGuard;
                    state.jitEnabled(previousJit);
                }
                JavaAccessPolicy.restoreActive(previousPolicy);
                LuaValue.popBasicState(previousState);
                LuaCoroutine.setCurrent(previousCoroutine);
                int remaining = depth.get() - 1;
                if (remaining == 0) depth.remove();
                else depth.set(remaining);
                if (!nested) execution.release();
            }
        }

        public void sleep(Sleep action) throws InterruptedException {
            if (LuaValue.activeBasicState() != state || active == null) {
                action.run();
                return;
            }
            Bindings suspended = active;
            LuaState.LoopGuard suspendedGuard = state.loopGuard;
            boolean suspendedJit = state.isJitEnabled();
            int suspendedDepth = depth.get();
            depth.remove();
            active = null;
            execution.release();
            try {
                action.run();
            } finally {
                execution.acquireUninterruptibly();
                active = suspended;
                state.loopGuard = suspendedGuard;
                state.jitEnabled(suspendedJit);
                if (suspendedDepth > 0) depth.set(suspendedDepth);
            }
            if (suspended.cancelled.getAsBoolean()) throw new LuaException("Context execution was cancelled.");
        }

        public LuaValue eval(Bindings bindings, String script) throws Exception {
            return call(bindings, () -> {
                String path = bindings.file == null ? "=(script)" : "@" + bindings.file.getCanonicalPath();
                if (bindings.file != null && state.get("package") instanceof LuaTable packages) {
                    String directory = bindings.file.getCanonicalFile().getParent().replace('\\', '/');
                    String prefix = directory + "/?.lua;" + directory + "/?/init.lua;";
                    LuaValue key = LuaValue.valueOf("path");
                    String current = packages.get(key).toLuaString();
                    if (!current.startsWith(prefix)) packages.set(key, LuaValue.valueOf(prefix + current));
                }
                return state.eval(sourceBytes(script), path);
            });
        }

        public LuaValue invoke(Bindings bindings, LuaValue function, Object... arguments) throws Exception {
            return call(bindings, () -> {
                LuaValue[] values = new LuaValue[arguments.length];
                for (int i = 0; i < values.length; i++) values[i] = LuavaJavaObjects.wrap(arguments[i]);
                return function.call(values);
            });
        }
    }

    public static LuaValue first(LuaValue value) {
        return value instanceof Varargs values ? values.first() : value;
    }

    public static String sourceBytes(String source) {
        if (source == null) throw new LuaException("script must not be nil");
        if (source.startsWith("\uFEFF")) source = source.substring(1);
        return new String(source.getBytes(java.nio.charset.StandardCharsets.UTF_8), java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    public static Object toJava(LuaValue value) {
        return LuaDataConverter.toJava(first(value), Object.class);
    }

    public static LuaTable result(LuaValue value) {
        LuaTable result = new LuaTable();
        LuaValue[] values = value instanceof Varargs args ? args.toArray() : new LuaValue[]{value};
        LuaTable entries = new LuaTable();
        for (int i = 0; i < values.length; i++) entries.rawsetInt(i + 1, values[i]);
        result.rawset(LuaValue.valueOf("ok"), LuaValue.valueOf(true));
        result.rawsetInt(1, LuaValue.valueOf(true));
        result.rawset(LuaValue.valueOf("result"), entries);
        result.rawsetInt(2, entries);
        return result;
    }
}
