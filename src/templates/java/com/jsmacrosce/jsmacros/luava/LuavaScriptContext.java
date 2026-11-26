package @CORE_PACKAGE@.luava;

import @CORE_PACKAGE@.core.Core;
import @CORE_PACKAGE@.core.event.BaseEvent;
import @CORE_PACKAGE@.core.language.BaseScriptContext;
import @CORE_PACKAGE@.luaj.config.LuajConfig;
import org.jsmacros.lua.shared.LuaSharedGlobals;
import org.jsmacros.multilang.shared.luava.LuavaExecutionSupport;
import org.jsmacros.multilang.shared.luava.LuavaSharedGlobals;

import java.io.File;
import java.util.concurrent.Callable;

public final class LuavaScriptContext extends BaseScriptContext<LuavaExecutionSupport.Session>
        implements LuaSharedGlobals.ContextHandle {
    private int activeCalls;
    private volatile boolean cancelled;
    private volatile boolean cancellationRequested;
    private final BaseScriptContext<?> parent;
    private final Thread inlineCaller;
    private volatile File executionFile;
    private volatile Thread executionThread;
    public final LuavaExecutionSupport.Bindings bindings;

    public LuavaScriptContext(Core<?, ?> runner, BaseEvent event, File file) {
        this(runner, event, file, null);
    }

    public LuavaScriptContext(Core<?, ?> runner, BaseScriptContext<?> parent, File file) {
        this(runner, parent.getTriggeringEvent(), file, parent);
    }

    private LuavaScriptContext(Core<?, ?> runner, BaseEvent event, File file, BaseScriptContext<?> parent) {
        super(@LUAVA_CONTEXT_SUPER@);
        this.parent = parent;
        executionFile = file;
        inlineCaller = parent == null ? null : Thread.currentThread();
        bindings = new LuavaExecutionSupport.Bindings(file,
            () -> cancelled || cancellationRequested || (parent != null && parent.isContextClosed()), () -> {
            LuajConfig config = runner.config.getOptions(LuajConfig.class);
            return config == null || config.luavaJit;
        });
    }

    public boolean isInlineCaller(Thread thread) { return thread == inlineCaller || thread == executionThread; }

    public void setExecutionFile(File file) {
        executionFile = file;
        bindings.file = file;
    }

    public void setExecutionThread(Thread thread) { executionThread = thread; }

    public void requestCancellation() { cancellationRequested = true; }

    @Override public File getFile() { return executionFile; }

    @Override public File getContainedFolder() {
        File file = executionFile;
        return file == null ? super.getContainedFolder() : file.getAbsoluteFile().getParentFile();
    }

    @Override
    public Thread getMainThread() {
        Thread thread = executionThread;
        return thread != null ? thread : inlineCaller == null ? super.getMainThread() : inlineCaller;
    }

    public <T> T call(Callable<T> action) throws Exception {
        if (parent != null && parent.isContextClosed()) throw new ScriptAssertionError("Context closed");
        synchronized (this) {
            if (cancelled || cancellationRequested || isContextClosed()) throw new ScriptAssertionError("Context closed");
            activeCalls++;
        }
        try {
            return getContext().call(bindings, action);
        } finally {
            synchronized (this) {
                activeCalls--;
                if (cancelled && activeCalls == 0) getContext().closeIfPrivate();
            }
        }
    }

    @Override
    public void wrapSleep(SleepRunnable sleep) throws InterruptedException {
        if (getContext() == null) sleep.run();
        else getContext().sleep(sleep::run);
    }

    @Override
    public synchronized void setContext(LuavaExecutionSupport.Session session) {
        if (cancelled) {
            session.closeIfPrivate();
            throw new ScriptAssertionError("Context closed");
        }
        super.setContext(session);
    }

    @Override
    @LUAVA_CLOSE_METHOD@ {
        synchronized (this) {
            if (cancelled) return;
            cancelled = true;
            @LUAVA_CLOSE_SUPER@
            // A suspended script or callback still owns Luava frames until its call returns.
            if (activeCalls == 0 && getContext() != null) getContext().closeIfPrivate();
        }
        LuavaSharedGlobals.release(this);
    }

    @Override public boolean isMultiThreaded() { return false; }
}
