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
    private volatile boolean cancelled;
    private volatile boolean cancellationRequested;
    private final BaseScriptContext<?> parent;
    private final Thread inlineCaller;
    private volatile File executionFile;
    private volatile Thread executionThread;
    public final LuavaExecutionSupport.Bindings bindings;

    public LuavaScriptContext(Core<?, ?> runner, BaseEvent event, File file) {
        this(runner, event, file, null, null);
    }

    public LuavaScriptContext(Core<?, ?> runner, BaseScriptContext<?> parent, File file, boolean jit) {
        this(runner, parent.getTriggeringEvent(), file, parent, jit);
    }

    private LuavaScriptContext(Core<?, ?> runner, BaseEvent event, File file, BaseScriptContext<?> parent, Boolean jit) {
        super(@LUAVA_CONTEXT_SUPER@);
        this.parent = parent;
        executionFile = file;
        inlineCaller = parent == null ? null : Thread.currentThread();
        bindings = new LuavaExecutionSupport.Bindings(file,
            () -> cancelled || cancellationRequested || (parent != null && parent.isContextClosed()), () -> {
            if (jit != null) return jit;
            LuajConfig config = runner.config.getOptions(LuajConfig.class);
            return config != null && config.luavaJit;
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
        if (cancelled || cancellationRequested || isContextClosed() || (parent != null && parent.isContextClosed()))
            throw new ScriptAssertionError("Context closed");
        return getContext().call(bindings, action);
    }

    @Override
    public void wrapSleep(SleepRunnable sleep) throws InterruptedException {
        if (getContext() == null) sleep.run();
        else getContext().sleep(sleep::run);
    }

    @Override
    public synchronized void setContext(LuavaExecutionSupport.Session session) {
        if (cancelled) throw new ScriptAssertionError("Context closed");
        super.setContext(session);
    }

    @Override
    @LUAVA_CLOSE_METHOD@ {
        synchronized (this) {
            if (cancelled) return;
            cancelled = true;
            @LUAVA_CLOSE_SUPER@
        }
        LuavaSharedGlobals.release(this);
    }

    @Override public boolean isMultiThreaded() { return false; }
}
