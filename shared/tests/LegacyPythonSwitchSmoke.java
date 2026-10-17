import xyz.wagyourtail.jsmacros.core.Core;
import xyz.wagyourtail.jsmacros.core.MethodWrapper;
import xyz.wagyourtail.jsmacros.core.config.ScriptTrigger;
import xyz.wagyourtail.jsmacros.core.event.BaseEvent;
import xyz.wagyourtail.jsmacros.core.language.BaseLanguage;
import xyz.wagyourtail.jsmacros.core.language.EventContainer;
import xyz.wagyourtail.jsmacros.jython.JythonExtension;
import xyz.wagyourtail.jsmacros.jython.config.PythonConfig;
import xyz.wagyourtail.jsmacros.multilang.python.PythonScriptContext;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

public class LegacyPythonSwitchSmoke {
    private static final List<Throwable> errors = new CopyOnWriteArrayList<>();

    public static void run(Path directory) throws Exception {
        var core = Core.getInstance();
        Path macros = core.config.macroFolder.toPath().toAbsolutePath();
        Files.createDirectories(macros);
        com.google.gson.JsonObject defaults = new com.google.gson.JsonObject();
        defaults.addProperty("useGraalPy", false);
        core.config.rawOptions.add("python", defaults);
        JythonExtension provider = new JythonExtension();
        provider.init();
        for (var library : provider.getLibraries()) core.libraryRegistry.addLibrary(library);
        core.libraryRegistry.addLibrary(xyz.wagyourtail.jsmacros.core.library.impl.FJsMacros.class);
        PythonConfig config = core.config.getOptions(PythonConfig.class);
        require(!config.useGraalPy, "Jython must remain the default");
        require(provider.getTranslations("en_us").containsKey("jsmacrospython.usegraalpy"), "missing setting translation");
        require(provider.getLanguageImplName().equals("jython"), "Legacy Python registration changed");
        require(provider.defaultFileExtension().equals("py"), "missing Python file extension");
        require(provider.extensionMatch(new File("smoke.jython.py")).isMatch(), "Python file discovery failed");
        BaseLanguage<?, ?> jython = provider.getLanguage(core);
        require(jython.getClass().getSimpleName().equals("JythonLanguageDescription"), "default Python engine changed");
        Probe old = new Probe();
        EventContainer<?> oldRun = jython.trigger("py", "import sys, os, socket\nassert sys.version_info[0] == 2\n"
            + "assert os.stat('.').st_mode > 0\nassert os.getcwd()\nassert socket.gethostname()\nevent.record(42)\n"
            + "settings = JsMacros.getLanguages()\nassert settings.graal(True) == settings\nassert sys.version_info[0] == 2",
            macros.resolve("default.py").toFile(), old, null, errors::add);
        await(oldRun);
        require(old.records.equals(List.of(42)) && errors.isEmpty(), "default Jython execution failed: " + errors);
        oldRun.getCtx().closeContext();

        core.config.loadConfig();
        require(core.config.getOptions(PythonConfig.class).useGraalPy, "GraalPy setting was not saved");
        BaseLanguage<?, ?> language = provider.getLanguage(core);
        require(language.getClass().getSimpleName().equals("PythonLanguageDefinition"), "toggle did not select GraalPy");
        Files.writeString(macros.resolve("local_module.py"), "answer = 42\n");
        Path script = macros.resolve("smoke.py");
        String body = """
            import os
            import sys
            assert sys.version_info.major == 3
            assert f"{6 * 7}" == "42"
            import local_module
            assert event is not None and context is not None
            assert file.getName() == 'smoke.py'
            assert os.getcwd() == str(file.getParent())
            def answer(value):
                return local_module.answer + value
            event.setCallback(JavaWrapper.methodToJava(answer))
            event.record(event.getCallback().apply(0))
            JavaWrapper.methodToJavaAsync(lambda: event.record(42)).run()
            settings = JsMacros.getLanguages()
            assert settings.graal(False) == settings
            assert sys.version_info.major == 3
            """;
        Files.writeString(script, body);
        Probe event = new Probe();
        EventContainer<?> running = language.trigger("py", body, script.toFile(), event, null, errors::add);
        await(running);
        require(event.records.size() >= 1 && event.records.get(0) == 42, "Python bindings or inline execution failed");
        awaitRecords(event);
        require(((Number) event.callback.apply(0)).intValue() == 42, "callback did not survive script completion");

        require(provider.getLanguage(core) == jython, "toggle did not restore Jython");
        require(((Number) event.callback.apply(0)).intValue() == 42, "toggle broke an existing GraalPy callback");
        Probe restored = new Probe();
        EventContainer<?> restoredRun = provider.getLanguage(core).trigger("py",
            "import sys\nassert sys.version_info[0] == 2\nevent.record(42)", macros.resolve("restored.py").toFile(), restored, null, errors::add);
        await(restoredRun);
        require(restored.records.equals(List.of(42)) && errors.isEmpty(), "restored Jython execution failed: " + errors);
        restoredRun.getCtx().closeContext();

        PythonScriptContext context = (PythonScriptContext) running.getCtx();
        context.call(() -> {
            context.wrapSleep(() -> {
                FutureTask<Object> callback = new FutureTask<>(() -> event.callback.apply(0));
                Thread thread = new Thread(callback, "Legacy-GraalPy-Sleep-Smoke");
                thread.setDaemon(true);
                thread.start();
                try {
                    require(((Number) callback.get(20, TimeUnit.SECONDS)).intValue() == 42, "Python callback during sleep failed");
                } catch (InterruptedException error) {
                    throw error;
                } catch (Exception error) {
                    throw new AssertionError("Python sleep did not release execution", error);
                }
            });
            return null;
        });
        context.closeContext();
        require(context.isContextClosed(), "Python context did not close");
        try {
            event.callback.apply(0);
            throw new AssertionError("callback accepted a closed context");
        } catch (xyz.wagyourtail.jsmacros.core.language.BaseScriptContext.ScriptAssertionError expected) {
        }

        core.config.getOptions(PythonConfig.class).useGraalPy = true;
        require(provider.getLanguage(core) == language, "re-enabling GraalPy created another language instance");
        Probe fileEvent = new Probe();
        EventContainer<?> fileRun = language.trigger(new ScriptTrigger(ScriptTrigger.TriggerType.EVENT,
            "Smoke", "smoke.py", true), fileEvent, null, errors::add);
        await(fileRun);
        awaitRecords(fileEvent);
        require(fileEvent.records.get(0) == 42, "Python file execution failed");
        require(((Number) fileEvent.callback.apply(0)).intValue() == 42, "Python file callback failed");
        fileRun.getCtx().closeContext();
        require(errors.isEmpty(), "unexpected Python adapter errors: " + errors);

        Path errorFile = macros.resolve("error.py");
        Files.writeString(errorFile, "raise RuntimeError('expected Python failure')\n");
        EventContainer<?> errorRun = language.trigger("py", Files.readString(errorFile), errorFile.toFile(),
            null, null, errors::add);
        await(errorRun);
        require(errors.size() == 1, "unexpected Legacy script errors: " + errors);
        var wrapped = provider.wrapException(errors.get(0));
        require(wrapped != null && wrapped.location != null, "Python editor location missing");
        var location = (xyz.wagyourtail.jsmacros.core.language.BaseWrappedException.GuestLocation) wrapped.location;
        require(location.file.equals(errorFile.toFile()) && location.line == 1, "wrong Python editor location");
        errors.clear();

        core.config.getOptions(PythonConfig.class).useGraalPy = false;
        core.config.saveConfig();
        core.config.loadConfig();
        require(!core.config.getOptions(PythonConfig.class).useGraalPy && provider.getLanguage(core) == jython,
            "disabled setting was not saved");
        if (Runtime.version().feature() < 21) {
            var lua = new xyz.wagyourtail.jsmacros.luaj.LuajExtension();
            core.config.rawOptions.add("lua", new com.google.gson.JsonObject());
            lua.init();
            var luaConfig = core.config.getOptions(xyz.wagyourtail.jsmacros.luaj.config.LuajConfig.class);
            require(!luaConfig.luava && !luaConfig.luavaJit, "LuaJ must remain the default on Java 17");
            luaConfig.luava = true;
            require(lua.getLanguage(core).getClass().getSimpleName().equals("LuajLanguageDefinition"),
                "Java 17 must retain LuaJ even if Luava was enabled on a newer JVM");
            require(lua.getLibraries().stream().noneMatch(c -> c.getName().contains(".luava.")), "Java 17 exposed Luava libraries");
            require(!lua.isGuestObject(new Object()), "Java 17 guest detection loaded Luava");
            for (var library : lua.getLibraries()) core.libraryRegistry.addLibrary(library);
            Probe unsupported = new Probe();
            EventContainer<?> unsupportedRun = lua.getLanguage(core).trigger("lua", """
                local ok, failure = pcall(function() JavaWrapper:luava('return 42') end)
                assert(not ok and string.find(tostring(failure), 'requires Java 21', 1, true))
                ok, failure = pcall(function() JavaWrapper:luava() end)
                assert(not ok and string.find(tostring(failure), 'requires Java 21', 1, true))
                ok, failure = pcall(function() JavaWrapper:luava(false) end)
                assert(not ok and string.find(tostring(failure), 'requires Java 21', 1, true))
                ok, failure = pcall(function() JavaWrapper:luava(true, true) end)
                assert(not ok and string.find(tostring(failure), 'requires Java 21', 1, true))
                ok, failure = pcall(function() JavaWrapper:luava(true, false, 'return 42') end)
                assert(not ok and string.find(tostring(failure), 'requires Java 21', 1, true))
                ok, failure = pcall(function() JavaWrapper:luava(true, true, 'return 42') end)
                assert(not ok and string.find(tostring(failure), 'requires Java 21', 1, true))
                local languages = JsMacros:getLanguages()
                languages:luava(false, false)
                local ok, failure = pcall(function() languages:luava(true, true) end)
                assert(not ok and string.find(tostring(failure), 'requires Java 21', 1, true))
                ok, failure = pcall(function() languages:luava(true) end)
                assert(not ok and string.find(tostring(failure), 'requires Java 21', 1, true))
                assert(6 * 7 == 42)
                event:record(42)
                """, macros.resolve("unsupported-direct-call.lua").toFile(), unsupported, null, errors::add);
            await(unsupportedRun);
            require(errors.isEmpty() && unsupported.records.equals(List.of(42)), "Java 17 direct call did not fail cleanly: " + errors);
            require(!luaConfig.luava && !luaConfig.luavaJit, "Java 17 rejected setter changed settings");
            unsupportedRun.getCtx().closeContext();
            luaConfig.luava = false;
        }
        System.out.println("Legacy Python: default Jython, saved GraalPy toggle, Python 3, imports, callbacks across toggles, sleep, shutdown, and editor errors passed on Java " + System.getProperty("java.version"));
    }

    private static void await(EventContainer<?> event) throws Exception {
        Thread thread = event.getCtx().getMainThread();
        thread.join(30_000);
        if (thread.isAlive()) {
            event.getCtx().closeContext();
            throw new AssertionError("Legacy script did not finish");
        }
    }

    private static void awaitRecords(Probe event) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (event.records.size() < 2 && System.nanoTime() < deadline) Thread.sleep(10);
        require(event.records.size() == 2, "asynchronous Python callback failed: " + errors);
    }

    public static final class Probe implements BaseEvent {
        private MethodWrapper<Object, Object, Object, ?> callback;
        public final List<Integer> records = new CopyOnWriteArrayList<>();
        public void record(int value) { records.add(value); }
        public void setCallback(MethodWrapper<Object, Object, Object, ?> callback) { this.callback = callback; }
        public MethodWrapper<Object, Object, Object, ?> getCallback() { return callback; }
        @Override public String getEventName() { return "Smoke"; }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
