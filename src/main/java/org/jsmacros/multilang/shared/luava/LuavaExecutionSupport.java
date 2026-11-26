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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

public final class LuavaExecutionSupport {
    private LuavaExecutionSupport() {}
    private static final ScheduledExecutorService CANCELLATION_MONITOR = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "Luava-Cancellation-Monitor");
        thread.setDaemon(true);
        return thread;
    });

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
        private final Object guardLock = new Object();
        private Thread activeThread;

        private void checkCancellation() {
            synchronized (guardLock) {
                if (active != null && (active.cancelled.getAsBoolean() || activeThread.isInterrupted())) state.cancel();
            }
        }

        private final boolean pooled;

        public Session() {
            this(false);
        }

        Session(boolean pooled) {
            this.pooled = pooled;
            LuaTable metatable = new LuaTable();
            metatable.rawset(LuaValue.valueOf("__index"), LuaFunction.of(args -> {
                Bindings bindings = active;
                return bindings == null ? LuaNil.NIL
                    : bindings.values.getOrDefault(args[1].toLuaString(), LuaNil.NIL);
            }));
            state.getGlobals().setMetatable(metatable);
            state.cancellable();
            state.jitEnabled(false);
        }

        public void closeIfPrivate() {
            if (!pooled) state.close();
        }

        public <T> T call(Bindings bindings, Callable<T> action) throws Exception {
            boolean nested = depth.get() > 0 || (LuaValue.activeBasicState() == state && active != null);
            if (!nested) execution.acquire();
            depth.set(depth.get() + 1);
            Bindings previous = active;
            LuaState.LoopGuard previousGuard = state.loopGuard;
            boolean previousCancelled = state.cancelled;
            boolean previousJit = state.isJitEnabled();
            LuaCoroutine previousCoroutine = LuaCoroutine.running();
            LuaState previousState = LuaValue.pushBasicState(state);
            JavaAccessPolicy previousPolicy = JavaAccessPolicy.setActive(JavaAccessPolicy.UNRESTRICTED);
            synchronized (guardLock) {
                active = bindings;
                if (!nested) {
                    activeThread = Thread.currentThread();
                    state.loopGuard = null;
                    state.cancelled = false;
                    state.cancellable();
                }
            }
            ScheduledFuture<?> monitor = !nested ? CANCELLATION_MONITOR.scheduleWithFixedDelay(
                this::checkCancellation, 0, 10, TimeUnit.MILLISECONDS) : null;
            if (!nested) LuaCoroutine.setCurrent(LuaCoroutine.createMainThread());
            try {
                if (!nested) {
                    boolean jit = bindings.jitEnabled.getAsBoolean();
                    state.jitEnabled(jit);
                }
                if (bindings.cancelled.getAsBoolean()) throw new LuaException("Context execution was cancelled.");
                return action.call();
            } catch (LuaException error) {
                throw new LuavaScriptException(error, bindings.file);
            } finally {
                synchronized (guardLock) {
                    active = previous;
                    if (!nested) {
                        monitor.cancel(false);
                        activeThread = null;
                        state.loopGuard = previousGuard;
                        state.cancelled = previousCancelled;
                        state.jitEnabled(previousJit);
                    }
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
            LuaState.LoopGuard suspendedGuard;
            boolean suspendedCancelled;
            Thread suspendedThread;
            boolean suspendedJit = state.isJitEnabled();
            int suspendedDepth = depth.get();
            depth.remove();
            synchronized (guardLock) {
                suspendedGuard = state.loopGuard;
                suspendedCancelled = state.cancelled;
                suspendedThread = activeThread;
                active = null;
                activeThread = null;
            }
            execution.release();
            try {
                action.run();
            } finally {
                execution.acquireUninterruptibly();
                synchronized (guardLock) {
                    active = suspended;
                    activeThread = suspendedThread;
                    state.loopGuard = suspendedGuard;
                    state.cancelled = suspendedCancelled;
                    state.jitEnabled(suspendedJit);
                }
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
