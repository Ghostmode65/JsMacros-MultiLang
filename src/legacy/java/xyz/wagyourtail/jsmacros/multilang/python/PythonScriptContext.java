package xyz.wagyourtail.jsmacros.multilang.python;

import org.graalvm.polyglot.Context;
import xyz.wagyourtail.jsmacros.core.event.BaseEvent;
import xyz.wagyourtail.jsmacros.core.language.BaseScriptContext;

import java.io.File;
import java.util.concurrent.Callable;
import java.util.concurrent.locks.ReentrantLock;

public final class PythonScriptContext extends BaseScriptContext<Context> {
    private final ReentrantLock execution = new ReentrantLock(true);
    private final ThreadLocal<Boolean> entered = ThreadLocal.withInitial(() -> false);

    public PythonScriptContext(BaseEvent event, File file) {
        super(event, file);
    }

    public <T> T call(Callable<T> action) throws Exception {
        execution.lockInterruptibly();
        try {
            if (isContextClosed()) throw new ScriptAssertionError("Context closed");
            boolean outermost = execution.getHoldCount() == 1;
            if (outermost) {
                getContext().enter();
                entered.set(true);
            }
            try {
                return action.call();
            } finally {
                if (outermost && entered.get()) {
                    entered.remove();
                    getContext().leave();
                }
            }
        } finally {
            execution.unlock();
        }
    }

    @Override
    public void wrapSleep(SleepRunnable sleep) throws InterruptedException {
        int holds = execution.getHoldCount();
        if (holds == 0) {
            sleep.run();
            return;
        }
        getContext().leave();
        entered.remove();
        for (int i = 0; i < holds; i++) execution.unlock();
        try {
            sleep.run();
        } finally {
            for (int i = 0; i < holds; i++) execution.lock();
            if (isContextClosed()) throw new ScriptAssertionError("Context closed");
            getContext().enter();
            entered.set(true);
        }
    }

    @Override
    public void setContext(Context python) {
        synchronized (this) {
            if (!closed) {
                super.setContext(python);
                return;
            }
        }
        python.close(true);
        throw new ScriptAssertionError("Context closed");
    }

    @Override
    public void closeContext() {
        synchronized (this) {
            if (closed) return;
            super.closeContext();
        }
        Context python = getContext();
        if (python != null) python.close(true);
    }

    @Override public boolean isMultiThreaded() { return false; }
}
