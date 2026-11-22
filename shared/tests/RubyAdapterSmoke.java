import @CORE_PACKAGE@.core.Core;
import @CORE_PACKAGE@.core.MethodWrapper;
import @CORE_PACKAGE@.core.config.BaseProfile;
import @CORE_PACKAGE@.core.config.ScriptTrigger;
import @CORE_PACKAGE@.core.event.BaseEvent;
import @CORE_PACKAGE@.core.event.BaseEventRegistry;
import @CORE_PACKAGE@.core.language.EventContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

public class RubyAdapterSmoke {
    private static final List<Throwable> errors = new CopyOnWriteArrayList<>();

    public static void run(Path directory) throws Exception {
        Path macros = directory.resolve("macros").toAbsolutePath();
        Files.createDirectories(macros);
        Path config = directory.resolve("config");
        Files.createDirectories(config);
        if (!Files.exists(config.resolve("options.json"))) Files.writeString(config.resolve("options.json"), "{}");
        var core = @CORE_CREATE@;
        var provider = new @RUBY_PROVIDER@();
        @INIT@;
        for (var library : provider.getLibraries()) core.libraryRegistry.addLibrary(library);
        var language = provider.getLanguage(core);
        Probe event = new Probe(core);
        String script = """
            raise 'wrong engine' unless JRUBY_VERSION == '@JRUBY_VERSION@'
            raise 'missing bindings' unless event && context && file
            def add(a, b); a + b; end
            def answer; 42; end
            def truthy(value); 42; end
            event.record(JavaWrapper.methodToJava(method(:add)).apply(20, 22))
            event.record(JavaWrapper.methodToJava(method(:answer)).apply(999))
            raise 'wrong Ruby truthiness' unless JavaWrapper.methodToJava(method(:truthy)).test(0)
            event.setCallback(JavaWrapper.methodToJava(method(:add)))
            define_singleton_method(:record_async) { event.record(42) }
            JavaWrapper.methodToJavaAsync(method(:record_async)).run
            """;
        Path file = macros.resolve("callback.rb");
        Files.writeString(file, script);
        EventContainer<?> running = language.trigger("rb", script, file.toFile(), event, null, errors::add);
        try {
            Thread main = running.getCtx().getMainThread();
            main.join(30_000);
            require(!main.isAlive(), "Ruby script did not finish");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (event.records.size() < 3 && errors.isEmpty() && System.nanoTime() < deadline) Thread.sleep(10);
            require(errors.isEmpty() && event.records.equals(List.of(42, 42, 42)),
                "Ruby sync/async callbacks failed: " + event.records + ", " + errors);
            require(((Number) event.callback.apply(20, 22)).intValue() == 42,
                "Ruby callback after script completion failed");
        } finally {
            running.getCtx().closeContext();
        }
        require(running.getCtx().isContextClosed(), "Ruby context did not close");
        require(errors.isEmpty(), "Ruby context shutdown failed: " + errors);
        System.out.println("Ruby adapter: bindings, sync/async callbacks, arity, truthiness, and context shutdown passed");
    }

    public static final class Probe @PROBE_PARENT@ {
        private MethodWrapper<Object, Object, Object, ?> callback;
        public final List<Integer> records = new CopyOnWriteArrayList<>();
        public Probe(Core<?, ?> core) { @PROBE_SUPER@ }
        public void record(int value) { records.add(value); }
        public void setCallback(MethodWrapper<Object, Object, Object, ?> callback) { this.callback = callback; }
        @Override public String getEventName() { return "Smoke"; }
    }

    public static final class Profile extends BaseProfile {
        private java.util.function.Consumer<Throwable> errorHandler = errors::add;
        public Profile(Core<?, ?> runner, Logger logger) { super(runner, logger); }
        public void setErrorHandler(java.util.function.Consumer<Throwable> handler) { errorHandler = handler; }
        @Override public void logError(Throwable error) { errorHandler.accept(error); }
        @Override public boolean checkJoinedThreadStack() { return joinedThreadStack.contains(Thread.currentThread()); }
        public void triggerEventJoin(BaseEvent event) {}
        public void triggerEventJoinNoAnything(BaseEvent event) {}
    }

    public static final class Registry extends BaseEventRegistry {
        public Registry(Core<?, ?> runner) { super(runner); }
        @Override public void addScriptTrigger(ScriptTrigger trigger) {}
        @Override public boolean removeScriptTrigger(ScriptTrigger trigger) { return false; }
        @Override public List<ScriptTrigger> getScriptTriggers() { return List.of(); }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
