package @CORE_PACKAGE@.jython.language.impl;

import @CORE_PACKAGE@.core.Core;
import @CORE_PACKAGE@.core.event.BaseEvent;
import @CORE_PACKAGE@.core.language.BaseScriptContext;
import org.python.core.Py;
import org.python.core.PySystemState;
import org.python.util.PythonInterpreter;

import java.io.File;
import java.util.concurrent.Callable;

public final class JythonScriptContext extends BaseScriptContext<PythonInterpreter> {
    public JythonScriptContext(Core<?, ?> runner, BaseEvent event, File file) {
        super(runner, event, file);
    }

    public <T> T call(Callable<T> action) throws Exception {
        if (isContextClosed()) throw new ScriptAssertionError("Context closed");
        Thread thread = Thread.currentThread();
        ClassLoader loader = thread.getContextClassLoader();
        PySystemState previous = Py.setSystemState(getContext().getSystemState());
        thread.setContextClassLoader(getClass().getClassLoader());
        try {
            return action.call();
        } finally {
            Py.setSystemState(previous);
            thread.setContextClassLoader(loader);
        }
    }

    @Override
    public synchronized void setContext(PythonInterpreter python) {
        if (closed) {
            python.close();
            throw new ScriptAssertionError("Context closed");
        }
        super.setContext(python);
    }

    @Override
    @PYTHON_CLOSE_METHOD@ {
        @PYTHON_CLOSE_SUPER@
        PythonInterpreter python = getContext();
        if (python != null) python.close();
    }

    @Override public boolean isMultiThreaded() { return true; }
}
