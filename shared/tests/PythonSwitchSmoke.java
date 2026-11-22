import @CORE_PACKAGE@.core.Core;
import @CORE_PACKAGE@.core.MethodWrapper;
import @CORE_PACKAGE@.core.config.BaseProfile;
import @CORE_PACKAGE@.core.config.Option;
import @CORE_PACKAGE@.core.config.ScriptTrigger;
import @CORE_PACKAGE@.core.event.BaseEvent;
import @CORE_PACKAGE@.core.event.BaseEventRegistry;
import @CORE_PACKAGE@.core.language.BaseLanguage;
import @CORE_PACKAGE@.core.language.BaseScriptContext;
import @CORE_PACKAGE@.core.language.BaseWrappedException;
import @CORE_PACKAGE@.core.language.EventContainer;
import @CORE_PACKAGE@.graal.python.PythonExtension;
import @CORE_PACKAGE@.graal.python.PythonScriptContext;
import @CORE_PACKAGE@.graal.python.config.PythonConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

public class PythonSwitchSmoke {
    private static final List<Throwable> errors = new CopyOnWriteArrayList<>();
    private static Core<?, ?> core;
    private static Path macros;
    private static PythonExtension provider;

    public static void run(Path directory) throws Exception {
        macros = directory.resolve("macros").toAbsolutePath();
        Files.createDirectories(macros);
        Path configDirectory = directory.resolve("config");
        Files.createDirectories(configDirectory);
        Files.writeString(configDirectory.resolve("options.json"), "{}");
        System.setProperty("python.cachedir", directory.resolve("jython-cache").toString());
        core = new Core<>(Registry::new, Profile::new, configDirectory.toFile(), macros.toFile(), LoggerFactory.getLogger("PythonSwitchSmoke"));
        provider = new PythonExtension();
        provider.init(core);
        for (var library : provider.getLibraries()) core.libraryRegistry.addLibrary(library);
        core.libraryRegistry.addLibrary(@CORE_PACKAGE@.core.library.impl.FJsMacros.class);
        require(!core.config.getOptions(PythonConfig.class).useJython, "GraalPy must remain the default");
        Option option = PythonConfig.class.getField("useJython").getAnnotation(Option.class);
        require(List.of(option.group()).equals(List.of("@CORE_SETTINGS_LANGUAGES@", "jsmacrospython.settings.languages.python")),
            "Jython toggle is outside Python settings");
        require(provider.getTranslations("en_us").get(option.translationKey()).equals("Use Jython (Python 2.7)"), "missing setting translation");
        require(provider.defaultFileExtension().equals("py") && provider.extensionMatch(new File("smoke.py")).isMatch(), "Python file discovery failed");
        require(!provider.extensionMatch(new File("smoke.lua")).isMatch(), "Python claimed another language's file");
        Files.writeString(macros.resolve("local_module.py"), "answer = 42\n");
        BaseLanguage<?, ?> graalpy = provider.getLanguage(core);
        require(graalpy.getClass().getSimpleName().equals("PythonLanguageDefinition"), "wrong default engine");
        Probe python3 = run(graalpy, """
            import sys
            assert sys.version_info.major == 3
            assert f'{6 * 7}' == '42'
            import local_module
            assert file.getName() == 'graalpy.py' and event is not None and context is not None
            def answer(value):
                return local_module.answer + value
            event.setCallback(JavaWrapper.methodToJava(answer))
            event.record(event.getCallback().apply(0))
            JavaWrapper.methodToJavaAsync(lambda: event.record(42)).run()
            """, "graalpy.py");
        awaitRecords(python3, 2);

        saveSetting(true);
        BaseLanguage<?, ?> jython = provider.getLanguage(core);
        require(jython.getClass().getSimpleName().equals("JythonLanguageDefinition"), "toggle did not select Jython");
        require(((Number) python3.callback.apply(0)).intValue() == 42, "toggle invalidated a GraalPy callback");
        String body = """
            import sys
            assert sys.version_info[0] == 2
            assert 5 / 2 == 2
            import os, socket
            assert os.stat('.').st_mode > 0
            assert os.getcwd()
            assert socket.gethostname()
            import local_module
            from java.util import ArrayList
            values = ArrayList()
            values.add(42)
            assert values.get(0) == 42
            assert file.getName() == 'jython.py' and event is not None and context is not None
            def answer(value):
                import local_module
                return local_module.answer + value
            event.setCallback(JavaWrapper.methodToJava(answer))
            event.record(event.getCallback().apply(0))
            assert JavaWrapper.methodToJava(lambda n: [n]).test(0)
            assert not JavaWrapper.methodToJava(lambda n: []).test(0)
            JavaWrapper.methodToJavaAsync(lambda: event.record(42)).run()
            """;
        Files.writeString(macros.resolve("jython.py"), body);
        Probe python2 = run(jython, body, "jython.py");
        awaitRecords(python2, 2);
        require(((Number) python2.callback.apply(0)).intValue() == 42, "Jython callback lost interpreter state");

        Probe fromFile = new Probe(core);
        fromFile.running = jython.trigger(new ScriptTrigger(ScriptTrigger.TriggerType.EVENT, "Smoke", "jython.py", true, false),
            fromFile, null, errors::add);
        await(fromFile.running);
        awaitRecords(fromFile, 2);
        require(((Number) fromFile.callback.apply(0)).intValue() == 42, "Jython file callback failed");

        saveSetting(false);
        require(provider.getLanguage(core) == graalpy, "toggle did not restore GraalPy");
        require(((Number) python2.callback.apply(0)).intValue() == 42, "toggle invalidated a Jython callback");
        Probe restored = run(graalpy, "import sys\nassert sys.version_info.major == 3\nevent.record(42)", "restored.py");
        require(restored.records.equals(List.of(42)), "restored GraalPy failed");
        restored.running.getCtx().closeContext();

        PythonScriptContext python3Context = (PythonScriptContext) python3.running.getCtx();
        python3Context.call(() -> {
            python3Context.wrapSleep(() -> {
                FutureTask<Object> task = new FutureTask<>(() -> python3.callback.apply(0));
                Thread worker = new Thread(task, "Python-Sleep-Smoke");
                worker.setDaemon(true);
                worker.start();
                try { require(((Number) task.get(20, TimeUnit.SECONDS)).intValue() == 42, "callback during sleep failed"); }
                catch (InterruptedException failure) { throw failure; }
                catch (Exception failure) { throw new AssertionError("sleep did not release execution", failure); }
            });
            return null;
        });

        require(provider.isGuestObject(python2.running.getCtx().getContext().getClass()
            .getMethod("eval", String.class).invoke(python2.running.getCtx().getContext(), "42")), "Jython guest detection failed");
        for (BaseLanguage<?, ?> language : List.of(jython, graalpy)) {
            for (String source : List.of("value = 1\nraise RuntimeError('expected failure')\n", "value = 1\ndef broken(:\n")) {
                File file = macros.resolve("error.py").toFile();
                EventContainer<?> failed = language.trigger("py", source, file, null, null, errors::add);
                await(failed);
                require(errors.size() == 1, "missing Python error: " + errors);
                Throwable failure = errors.remove(0);
                BaseWrappedException<?> wrapped = provider.wrapException(failure);
                require(wrapped != null && wrapped.location instanceof BaseWrappedException.GuestLocation, "missing Python editor location");
                var location = (BaseWrappedException.GuestLocation) wrapped.location;
                require(location.file.equals(file) && location.line == 2, "wrong Python editor location for "
                    + language.getClass().getSimpleName() + ": " + location.file + ":" + location.line + "; " + failure);
                failed.getCtx().closeContext();
            }
        }

        for (Probe probe : List.of(python2, fromFile, python3)) {
            probe.running.getCtx().closeContext();
            require(probe.running.getCtx().isContextClosed(), "Python context did not close");
            try {
                probe.callback.apply(0);
                throw new AssertionError("closed Python callback executed");
            } catch (BaseScriptContext.ScriptAssertionError expected) {}
        }
        saveSetting(true);
        require(provider.getLanguage(core) == jython, "re-enabling Jython replaced its language instance");
        saveSetting(false);
        require(errors.isEmpty(), "unexpected Python errors: " + errors);
        System.out.println("Python: default GraalPy, saved opt-in Jython, Python 2/3, Java access, imports, files, sync/async callbacks across toggles, shutdown and editor errors passed on Java " + System.getProperty("java.version"));
    }

    private static void saveSetting(boolean enabled) throws Exception {
        Probe change = run(provider.getLanguage(core), "settings = JsMacros.getLanguages()\nassert settings.jython("
            + (enabled ? "True" : "False") + ") == settings", "change-engine.py");
        change.running.getCtx().closeContext();
        core.config.loadConfig();
        require(core.config.getOptions(PythonConfig.class).useJython == enabled, "Python setting did not persist");
    }

    private static Probe run(BaseLanguage<?, ?> language, String source, String name) throws Exception {
        Probe probe = new Probe(core);
        probe.running = language.trigger("py", source, macros.resolve(name).toFile(), probe, null, errors::add);
        await(probe.running);
        require(errors.isEmpty(), "Python script failed: " + errors);
        return probe;
    }

    private static void await(EventContainer<?> event) throws InterruptedException {
        Thread thread = event.getCtx().getMainThread();
        thread.join(30_000);
        if (thread.isAlive()) {
            event.getCtx().closeContext();
            throw new AssertionError("Python script did not finish");
        }
    }

    private static void awaitRecords(Probe probe, int count) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (probe.records.size() < count && errors.isEmpty() && System.nanoTime() < deadline) Thread.sleep(10);
        require(errors.isEmpty() && probe.records.equals(java.util.Collections.nCopies(count, 42)), "Python callbacks failed: " + probe.records + ", " + errors);
    }

    public static final class Probe extends BaseEvent {
        public final List<Integer> records = new CopyOnWriteArrayList<>();
        public MethodWrapper<Object, Object, Object, ?> callback;
        public EventContainer<?> running;
        public Probe(Core<?, ?> runner) { super(runner); }
        public void record(int value) { records.add(value); }
        public void setCallback(MethodWrapper<Object, Object, Object, ?> callback) { this.callback = callback; }
        public MethodWrapper<Object, Object, Object, ?> getCallback() { return callback; }
        @Override public String getEventName() { return "Smoke"; }
    }

    public static final class Profile extends BaseProfile {
        public Profile(Core<?, ?> runner, Logger logger) { super(runner, logger); }
        @Override public void logError(Throwable error) { errors.add(error); }
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
