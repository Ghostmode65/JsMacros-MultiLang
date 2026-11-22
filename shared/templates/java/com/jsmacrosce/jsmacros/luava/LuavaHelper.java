package @CORE_PACKAGE@.luava;

import @CORE_PACKAGE@.core.Core;
import @CORE_PACKAGE@.core.language.BaseScriptContext;
import @CORE_PACKAGE@.core.language.EventContainer;
import @CORE_PACKAGE@.luaj.LuajExtension;
import org.jsmacros.multilang.shared.luava.LuavaExecutionSupport;
import org.jsmacros.multilang.shared.luava.LuavaScriptException;
import org.jsmacros.multilang.shared.luava.LuavaToLuaJ;
import org.luava.runtime.LuaValue;

import java.io.File;
import java.lang.ref.Reference;
import java.nio.file.Files;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.locks.ReentrantLock;

/** A preinitialized Lua state retained across execute calls. Close it when finished. */
public final class LuavaHelper implements AutoCloseable {
    private final Core<?, ?> runner;
    private final BaseScriptContext<?> parent;
    private final LuavaScriptContext child;
    private final boolean luaJ;
    private final ReentrantLock execution = new ReentrantLock(true);
    private final BlockingQueue<Request> pending = new LinkedBlockingQueue<>();
    private final Thread worker;
    private volatile Object parentKeepAlive;
    private volatile boolean closed;

    public LuavaHelper(Core<?, ?> runner, BaseScriptContext<?> parent,
                       boolean jit, boolean async, boolean luaJ) throws Exception {
        if (parent.isContextClosed()) throw new BaseScriptContext.ScriptAssertionError("Context closed");
        this.runner = runner;
        this.parent = parent;
        this.luaJ = luaJ;
        parentKeepAlive = parent.getSyncObject();
        File file = parent.getFile();
        child = new LuavaScriptContext(runner, parent, file == null ? null : file.getCanonicalFile(), jit);
        worker = async ? new Thread(this::work, "Luava-Helper") : null;
        try {
            child.setContext(new LuavaExecutionSupport.Session());
            child.call(() -> {
                new LuavaLanguageDefinition(new LuajExtension(), runner).retrieveLibs(child)
                    .forEach(child.bindings::put);
                child.bindings.put("event", parent.getTriggeringEvent());
                child.bindings.put("file", child.getFile());
                return null;
            });
            parent.hasMethodWrapperBeenInvoked = true;
            if (worker != null) {
                worker.setDaemon(true);
                parent.bindThread(worker);
                try { worker.start(); }
                catch (Throwable error) {
                    parent.unbindThread(worker);
                    throw error;
                }
            }
        } catch (Throwable error) {
            close();
            throw error;
        }
    }

    public Object execute(String source) throws Exception {
        return submit(new Request(Objects.requireNonNull(source, "source"), null));
    }

    public Object execute(File file) throws Exception {
        return submit(new Request(null, Objects.requireNonNull(file, "file")));
    }

    private Object submit(Request request) throws Exception {
        if (worker != null) {
            synchronized (this) {
                requireOpen();
                pending.add(request);
            }
            return null;
        }
        return evaluate(request, true);
    }

    private Object evaluate(Request request, boolean result) throws Exception {
        execution.lockInterruptibly();
        Thread caller = Thread.currentThread();
        boolean joined = runner.profile.joinedThreadStack.contains(caller);
        Thread previousThread = child.getMainThread();
        EventContainer<LuavaScriptContext> event = new EventContainer<>(child);
        boolean locked = false;
        try {
            requireOpen();
            File target = request.file == null ? parent.getFile() : request.file.isAbsolute() ? request.file
                : new File(parent.getContainedFolder(), request.file.getPath());
            if (target != null) target = target.getCanonicalFile();
            String script = request.file == null ? request.source : Files.readString(target.toPath());
            child.setExecutionThread(caller);
            event.setLockThread(caller);
            locked = true;
            File file = target;
            return child.call(() -> {
                child.setExecutionFile(file);
                child.bindings.put("file", file);
                child.bindings.put("context", event);
                LuaValue value = child.getContext().eval(child.bindings, script);
                if (!result) return null;
                if (luaJ) return LuavaToLuaJ.convert(value);
                LuaValue first = LuavaExecutionSupport.first(value);
                LuavaToLuaJ.requireData(first);
                return first;
            });
        } catch (LuavaScriptException error) {
            if (result && !luaJ) throw (org.luava.runtime.LuaException) error.getCause();
            throw error;
        } finally {
            try {
                if (locked) event.releaseLock();
                child.setExecutionThread(previousThread);
                if (joined) runner.profile.joinedThreadStack.add(caller);
                if (closed) child.closeContext();
            } finally {
                execution.unlock();
                Reference.reachabilityFence(parentKeepAlive);
            }
        }
    }

    private void work() {
        Object keepAlive = parentKeepAlive;
        try {
            while (!isClosed()) {
                Request request = pending.take();
                try { evaluate(request, false); }
                catch (Throwable error) { runner.profile.logError(error); }
            }
        } catch (InterruptedException error) {
            if (!isClosed()) runner.profile.logError(error);
        } finally {
            try {
                close();
                parent.releaseBoundEventIfPresent(Thread.currentThread());
                parent.unbindThread(Thread.currentThread());
                runner.profile.joinedThreadStack.remove(Thread.currentThread());
            } finally {
                Reference.reachabilityFence(keepAlive);
            }
        }
    }

    public boolean isClosed() {
        if (!closed && (parent.isContextClosed() || child.isContextClosed())) close();
        return closed;
    }

    private void requireOpen() {
        if (isClosed()) throw new BaseScriptContext.ScriptAssertionError("Luava helper is closed");
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
            pending.clear();
        }
        child.requestCancellation();
        if (worker != null && worker != Thread.currentThread()) worker.interrupt();
        // An active execution releases its event before closing the context.
        // Avoid closing it concurrently with EventContainer.releaseLock().
        if (execution.tryLock()) {
            try { child.closeContext(); }
            finally { execution.unlock(); }
        }
        parentKeepAlive = null;
    }

    private record Request(String source, File file) {}
}
