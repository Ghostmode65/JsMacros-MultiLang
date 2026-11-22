package @CORE_PACKAGE@.jython.library.impl;

import @CORE_PACKAGE@.core.MethodWrapper;
import @CORE_PACKAGE@.core.language.BaseLanguage;
import @CORE_PACKAGE@.core.library.IFWrapper;
import @CORE_PACKAGE@.core.library.Library;
import @CORE_PACKAGE@.core.library.PerExecLanguageLibrary;
import @CORE_PACKAGE@.jython.language.impl.JythonLanguageDefinition;
import @CORE_PACKAGE@.jython.language.impl.JythonScriptContext;
import org.python.core.PyObject;
import org.python.util.PythonInterpreter;

@Library(value = "JavaWrapper", languages = JythonLanguageDefinition.class)
public final class FWrapper extends PerExecLanguageLibrary<PythonInterpreter, JythonScriptContext> implements IFWrapper<PyObject> {
    public FWrapper(JythonScriptContext context, Class<? extends BaseLanguage<PythonInterpreter, JythonScriptContext>> language) {
        super(context, language);
    }

    @Override
    public <A, B, R> MethodWrapper<A, B, R, ?> methodToJava(PyObject function) {
        return new JythonMethodWrapper<>(function, true, ctx);
    }

    @Override
    public <A, B, R> MethodWrapper<A, B, R, ?> methodToJavaAsync(PyObject function) {
        return new JythonMethodWrapper<>(function, false, ctx);
    }

    @Override public void stop() { ctx.closeContext(); }
    @Override public void deferCurrentTask() throws InterruptedException { ctx.wrapSleep(() -> {}); }

    private static final class JythonMethodWrapper<T, U, R> extends MethodWrapper<T, U, R, JythonScriptContext> {
        private final PyObject function;
        private final boolean await;

        private JythonMethodWrapper(PyObject function, boolean await, JythonScriptContext context) {
            super(context);
            if (!function.isCallable()) throw new IllegalArgumentException("Expected a Python callable");
            this.function = function;
            this.await = await;
        }

        private PyObject invoke(Object... arguments) {
            Thread thread = Thread.currentThread();
            boolean bound = ctx.getBoundThreads().contains(thread);
            boolean newlyBound = false;
            boolean joined = false;
            try {
                if (!bound) {
                    @PYTHON_BIND_THREAD@;
                    newlyBound = true;
                    if (await && ctx.runner.profile.checkJoinedThreadStack()) {
                        joined = ctx.runner.profile.joinedThreadStack.add(thread);
                    }
                }
                return ctx.call(() -> function._jcall(arguments));
            } catch (RuntimeException error) {
                throw error;
            } catch (Exception error) {
                if (error instanceof InterruptedException) thread.interrupt();
                throw new RuntimeException(error);
            } finally {
                if (newlyBound) {
                    ctx.releaseBoundEventIfPresent(thread);
                    ctx.unbindThread(thread);
                    if (joined) ctx.runner.profile.joinedThreadStack.remove(thread);
                }
            }
        }

        private void acceptArguments(Object... arguments) {
            if (await) invoke(arguments);
            else {
                Thread thread = new Thread(() -> {
                    try { invoke(arguments); }
                    catch (Throwable error) { ctx.runner.profile.logError(error); }
                }, "Jython-JavaWrapper");
                thread.setDaemon(true);
                thread.start();
            }
        }

        @SuppressWarnings("unchecked")
        private R applyArguments(Object... arguments) { return (R) invoke(arguments).__tojava__(Object.class); }

        @Override public void accept(T value) { acceptArguments(value); }
        @Override public void accept(T first, U second) { acceptArguments(first, second); }
        @Override public R apply(T value) { return applyArguments(value); }
        @Override public R apply(T first, U second) { return applyArguments(first, second); }
        @Override public boolean test(T value) { return invoke(value).__nonzero__(); }
        @Override public boolean test(T first, U second) { return invoke(first, second).__nonzero__(); }
        @Override public void run() { acceptArguments(); }
        @Override public R get() { return applyArguments(); }
        @Override public int compare(T first, T second) { return ((Number) invoke(first, second).__tojava__(Object.class)).intValue(); }
    }
}
