package @CORE_PACKAGE@.graal.python;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import @CORE_PACKAGE@.core.Core;
import @CORE_PACKAGE@.core.MethodWrapper;
import @CORE_PACKAGE@.core.language.BaseLanguage;
import @CORE_PACKAGE@.core.library.IFWrapper;
import @CORE_PACKAGE@.core.library.Library;
import @CORE_PACKAGE@.core.library.PerExecLanguageLibrary;

@Library(value = "JavaWrapper", languages = PythonLanguageDefinition.class)
public final class FWrapper extends PerExecLanguageLibrary<Context, PythonScriptContext> implements IFWrapper<Value> {
    public FWrapper(PythonScriptContext context, Class<? extends BaseLanguage<Context, PythonScriptContext>> language) {
        super(context, language);
    }

    @Override
    public <A, B, R> MethodWrapper<A, B, R, ?> methodToJava(Value function) {
        return new PythonMethodWrapper<>(function, true, ctx);
    }

    @Override
    public <A, B, R> MethodWrapper<A, B, R, ?> methodToJavaAsync(Value function) {
        return new PythonMethodWrapper<>(function, false, ctx);
    }

    @Override public void stop() { ctx.closeContext(); }
    @Override public void deferCurrentTask() throws InterruptedException { ctx.wrapSleep(() -> {}); }

    private static final class PythonMethodWrapper<T, U, R> extends MethodWrapper<T, U, R, PythonScriptContext> {
        private final Value function;
        private final boolean await;

        PythonMethodWrapper(Value function, boolean await, PythonScriptContext context) {
            super(context);
            if (!function.canExecute()) throw new IllegalArgumentException("Expected a Python callable");
            this.function = function;
            this.await = await;
        }

        private Object invoke(Object... arguments) {
            Thread thread = Thread.currentThread();
            boolean alreadyBound = ctx.getBoundThreads().contains(thread);
            boolean joined = false;
            try {
                if (!alreadyBound) {
                    @PYTHON_BIND_THREAD@;
                    if (await && ctx.runner.profile.checkJoinedThreadStack()) {
                        joined = ctx.runner.profile.joinedThreadStack.add(thread);
                    }
                }
                return ctx.call(() -> function.execute(arguments).as(Object.class));
            } catch (RuntimeException error) {
                throw error;
            } catch (Exception error) {
                if (error instanceof InterruptedException) thread.interrupt();
                throw new RuntimeException(error);
            } finally {
                if (!alreadyBound) {
                    ctx.releaseBoundEventIfPresent(thread);
                    ctx.unbindThread(thread);
                    if (joined) ctx.runner.profile.joinedThreadStack.remove(thread);
                }
            }
        }

        private void acceptArguments(Object... arguments) {
            if (await) {
                invoke(arguments);
            } else {
                Thread thread = new Thread(() -> {
                    try {
                        invoke(arguments);
                    } catch (Throwable error) {
                        ctx.runner.profile.logError(error);
                    }
                }, "GraalPy-JavaWrapper");
                thread.setDaemon(true);
                thread.start();
            }
        }

        @SuppressWarnings("unchecked")
        private R applyArguments(Object... arguments) { return (R) invoke(arguments); }

        @Override public void accept(T value) { acceptArguments(value); }
        @Override public void accept(T first, U second) { acceptArguments(first, second); }
        @Override public R apply(T value) { return applyArguments(value); }
        @Override public R apply(T first, U second) { return applyArguments(first, second); }
        @Override public boolean test(T value) { return (boolean) invoke(value); }
        @Override public boolean test(T first, U second) { return (boolean) invoke(first, second); }
        @Override public void run() { acceptArguments(); }
        @Override public R get() { return applyArguments(); }
        @Override public int compare(T first, T second) { return ((Number) invoke(first, second)).intValue(); }
    }
}
