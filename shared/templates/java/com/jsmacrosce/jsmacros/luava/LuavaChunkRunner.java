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
import java.util.function.Function;

public final class LuavaChunkRunner {
    private LuavaChunkRunner() {}

    public static void startAsync(Core<?, ?> runner, BaseScriptContext<?> parent,
                                  boolean jit, String source, File file) {
        // Keep the parent alive after its main script returns, as method wrappers do.
        Object keepAlive = parent.getSyncObject();
        Thread worker = new Thread(() -> {
            try {
                evaluate(runner, parent, jit, source, file, value -> null);
            } catch (Throwable error) {
                runner.profile.logError(error);
            } finally {
                try {
                    parent.releaseBoundEventIfPresent(Thread.currentThread());
                    parent.unbindThread(Thread.currentThread());
                    runner.profile.joinedThreadStack.remove(Thread.currentThread());
                } finally {
                    Reference.reachabilityFence(keepAlive);
                }
            }
        }, "Luava-Chunk");
        worker.setDaemon(true);
        parent.bindThread(worker);
        parent.hasMethodWrapperBeenInvoked = true;
        try {
            worker.start();
        } catch (Throwable error) {
            parent.unbindThread(worker);
            throw error;
        }
    }

    public static org.luaj.vm2.LuaValue fromLuaJ(Core<?, ?> runner, BaseScriptContext<?> parent,
                                               boolean jit, String source, File file) throws Exception {
        return evaluate(runner, parent, jit, source, file, LuavaToLuaJ::convert);
    }

    public static LuaValue fromLuava(Core<?, ?> runner, BaseScriptContext<?> parent,
                                     boolean jit, String source, File file) throws Exception {
        try {
            return evaluate(runner, parent, jit, source, file, value -> {
                LuaValue first = LuavaExecutionSupport.first(value);
                LuavaToLuaJ.requireData(first);
                return first;
            });
        } catch (LuavaScriptException error) {
            throw (org.luava.runtime.LuaException) error.getCause();
        }
    }

    private static <T> T evaluate(Core<?, ?> runner, BaseScriptContext<?> parent,
                                   boolean jit, String source, File file, Function<LuaValue, T> convert) throws Exception {
        if (parent.isContextClosed()) throw new BaseScriptContext.ScriptAssertionError("Context closed");
        File target = file == null ? parent.getFile()
            : (file.isAbsolute() ? file : new File(parent.getContainedFolder(), file.getPath()));
        if (target != null) target = target.getCanonicalFile();
        String script = file == null ? source : Files.readString(target.toPath());
        LuavaScriptContext child = new LuavaScriptContext(runner, parent, target, jit);
        child.setContext(new LuavaExecutionSupport.Session());
        EventContainer<LuavaScriptContext> event = new EventContainer<>(child);
        Thread caller = Thread.currentThread();
        boolean joined = runner.profile.joinedThreadStack.contains(caller);
        event.setLockThread(caller);
        try {
            return child.call(() -> {
                var language = new LuavaLanguageDefinition(new LuajExtension(), runner);
                language.retrieveLibs(child).forEach(child.bindings::put);
                child.bindings.put("event", parent.getTriggeringEvent());
                child.bindings.put("file", child.getFile());
                child.bindings.put("context", event);
                return convert.apply(child.getContext().eval(child.bindings, script));
            });
        } finally {
            try {
                event.releaseLock();
                child.closeContext();
            } finally {
                if (joined) runner.profile.joinedThreadStack.add(caller);
            }
        }
    }
}
