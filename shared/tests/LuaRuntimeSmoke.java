import @CORE_PACKAGE@.core.Core;
import @CORE_PACKAGE@.core.MethodWrapper;
import @CORE_PACKAGE@.core.config.BaseProfile;
import @CORE_PACKAGE@.core.config.ScriptTrigger;
import @CORE_PACKAGE@.core.event.BaseEvent;
import @CORE_PACKAGE@.core.event.BaseEventRegistry;
import @CORE_PACKAGE@.core.language.BaseLanguage;
import @CORE_PACKAGE@.core.language.EventContainer;
import @CORE_PACKAGE@.luaj.LuajExtension;
import @CORE_PACKAGE@.luaj.config.LuajConfig;
import @CORE_PACKAGE@.luava.LuavaScriptContext;
import @CORE_PACKAGE@.luava.LuavaHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

public class LuaRuntimeSmoke {
    private static final List<Throwable> errors = new CopyOnWriteArrayList<>();
    private static Core<?, ?> core;
    private static Path macros;
    private static LuajExtension provider;

    public static void run(Path directory) throws Exception {
        macros = directory.resolve("macros").toAbsolutePath();
        Files.createDirectories(macros);
        core = @CORE_CREATE@;
        if (!(core.profile instanceof Profile)) {
            // Legacy reuses the singleton core created by the Ruby integration test.
            core.profile.getClass().getMethod("setErrorHandler", java.util.function.Consumer.class)
                .invoke(core.profile, (java.util.function.Consumer<Throwable>) errors::add);
        }
        macros = core.config.macroFolder.toPath().toAbsolutePath();
        provider = new LuajExtension();
        @INIT@;
        for (var library : provider.getLibraries()) core.libraryRegistry.addLibrary(library);
        core.libraryRegistry.addLibrary(@CORE_PACKAGE@.core.library.impl.FJsMacros.class);
        LuajConfig config = core.config.getOptions(LuajConfig.class);
        require(!config.luava, "LuaJ must remain the default");
        require(!config.luavaJit, "Luava JIT must default to guarded execution");
        BaseLanguage<?, ?> luaj = provider.getLanguage(core);
        require(luaj.getClass().getSimpleName().equals("LuajLanguageDefinition"), "default engine changed");
        testDirectCalls();
        Probe old = run("assert(_VERSION ~= nil); event:setCallback(JavaWrapper:methodToJava(function(n) return n + 1 end))", "old.lua");

        Probe change = run("local settings = JsMacros:getLanguages(); assert(settings:luava(true, false) == settings)", "enable-luava.lua");
        change.running.getCtx().closeContext();
        require(provider.getLanguage(core).getClass().getSimpleName().equals("LuavaLanguageDefinition"), "toggle did not select Luava");
        testDirectCalls();
        require(((Number) old.callback.apply(41)).intValue() == 42, "toggle broke an existing LuaJ callback");
        String body = """
            assert(_VERSION == 'Lua 5.4')
            assert(event ~= nil and context ~= nil and file:getName() == 'smoke.lua')
            local count <const> = 21
            assert((count << 1) == 42 and 85 // 2 == 42)
            assert(9007199254740993 + 1 == 9007199254740994)
            local closed = false
            do local resource <close> = setmetatable({}, {__close = function() closed = true end}) end
            assert(closed)
            local packed = JavaWrapper:pcall(function() return 1, nil, 42 end)
            assert(packed.ok and packed.result[1] == 1 and packed.result[3] == 42)
            assert(JavaWrapper:pcall(setmetatable({}, {__call = function() return 42 end})).result[1] == 42)
            local failed = JavaWrapper:xpcall(function() error('expected') end, function(e) return e.message end)
            assert(not failed.ok and string.find(failed.handled, 'expected', 1, true))
            assert(JavaWrapper:unicode('U+1F642') == '🙂')
            assert(#'🙂' == 4 and utf8.len('🙂') == 1)
            assert(JavaWrapper:methodToJava(function(n) return n + 1 end):apply(41) == 42)
            assert(JavaWrapper:list(JavaWrapper:toInteger(7)):getName() == 'java.lang.Integer')
            assert(JavaWrapper:list(JavaWrapper:toLong(9007199254740993)):getName() == 'java.lang.Long')
            assert(#JavaWrapper:list(event):methods() > 0)
            local other = GlobalContext:loadscript('return JavaWrapper:methodToJava(function() return 42 end):get()', 'cross-pool')
            assert(other() == 42)
            assert(GlobalContext:destroyPool('cross-pool'))
            local co = coroutine.create(function()
                assert(event ~= nil)
                JavaWrapper:methodToJava(function() event:record(42) end):run()
                coroutine.yield(21)
                return 42
            end)
            local ok, value = coroutine.resume(co); assert(ok and value == 21)
            ok, value = coroutine.resume(co); assert(ok and value == 42)
            event:setCallback(JavaWrapper:methodToJava(function(n) return event:getValue() + n end))
            JavaWrapper:methodToJavaAsync(function() event:record(42) end):run()
            """;
        Probe inline = run(body, "smoke.lua");
        awaitRecords(inline, 2);
        require(((Number) inline.callback.apply(0)).intValue() == 42, "Luava callback after script completion failed");
        LuavaScriptContext context = (LuavaScriptContext) inline.running.getCtx();
        context.call(() -> {
            require(context.getContext().state.loopGuard != null, "default cancellation guard missing");
            context.wrapSleep(() -> {
                config.luavaJit = true;
                FutureTask<Object> callback = new FutureTask<>(() -> context.call(() -> {
                    require(context.getContext().state.loopGuard == null, "callback did not pick up the live JIT setting");
                    return inline.callback.apply(0);
                }));
                Thread thread = new Thread(callback, "Luava-Sleep-Test");
                thread.setDaemon(true);
                thread.start();
                try { require(((Number) callback.get(10, TimeUnit.SECONDS)).intValue() == 42, "callback during sleep failed"); }
                catch (InterruptedException error) { throw error; }
                catch (Exception error) { throw new AssertionError("sleep held the engine permit", error); }
            });
            require(context.getContext().state.loopGuard != null, "sleep did not restore the suspended execution's guard");
            return null;
        });
        config.luavaJit = false;

        Probe hot = run("""
            function jit_probe(n)
                local sum = 0
                for i = 1, n do sum = sum + i end
                return sum
            end
            event:setCallback(JavaWrapper:methodToJava(jit_probe))
            """, "jit.lua");
        LuavaScriptContext hotContext = (LuavaScriptContext) hot.running.getCtx();
        var closure = (org.luava.runtime.bytecode.LuaClosure) hotContext.getContext().state.get("jit_probe");
        require(((Number) hot.callback.apply(20000)).longValue() == 200010000L, "guarded calculation failed");
        require(closure.proto.jitCode == null && !closure.proto.loopCompileRequested, "guarded execution poisoned later JIT activation");
        config.luavaJit = true;
        hotContext.call(() -> {
            require(hotContext.getContext().state.loopGuard == null && hotContext.getContext().state.isJitEnabled(), "live JIT enable failed");
            config.luavaJit = false;
            hotContext.call(() -> {
                require(hotContext.getContext().state.loopGuard == null, "nested call changed an already-running execution's mode");
                return null;
            });
            return null;
        });
        config.luavaJit = true;
        require(((Number) hot.callback.apply(20000)).longValue() == 200010000L, "JIT-enabled callback failed");
        long compileDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (closure.proto.jitCode == null && !closure.proto.jitDisabled && System.nanoTime() < compileDeadline) Thread.sleep(10);
        require(closure.proto.jitCode != null, "enabling JIT did not compile the existing callback");
        require(((Number) hot.callback.apply(20000)).longValue() == 200010000L, "compiled callback failed");
        config.luavaJit = false;
        hotContext.call(() -> {
            require(hotContext.getContext().state.loopGuard != null && !hotContext.getContext().state.isJitEnabled(), "live guard restoration failed");
            return null;
        });
        require(((Number) hot.callback.apply(20000)).longValue() == 200010000L, "compiled callback could not return to guarded execution");

        String bridgeChecks = """
            local b = event.bridge
            local echo = b.echo
            assert(echo == b.echo and b:echo(42) == 42 and echo(21) == 21)
            assert(b:choose(42) == 'number' and b:choose('text') == 'object')
            assert(b:echoObject(42) == 42)
            assert(b:child():echoObject(21) == 21)
            assert(b.value == 1); b.value = 7; assert(b.value == 7)
            assert(b.collision == 3); b.collision = 9; assert(b.collision == 9)
            local handler = b.handler
            assert(handler() == 1); b:changeHandler(); assert(b.handler() == 2)
            local list, map, array = b.list, b.map, b.array
            assert(#list == 2 and #array == 2 and #map == 0)
            list[1] = 42; array[2] = 21
            assert(list[1] == 42 and array[2] == 21)
            local put = map.put
            map.put = 42; assert(map.put == 42)
            map.put = nil; assert(map.put == nil)
            b:removeMapShadow(); assert(map.put == put)
            assert(type(b:snapshot()) == 'table')
            local t = b:getType(); assert(t:staticEcho(42) == 42)
            local first, second = b:child(), b:child()
            local mt = debug.getmetatable(first)
            assert(type(mt.__index) == 'function' and type(mt.__newindex) == 'function')
            mt.marker = 42; assert(debug.getmetatable(second).marker == nil)
            """;
        Probe bridge = run(bridgeChecks, "bridge.lua");
        config.luavaJit = true;
        Probe bridgeJit = run(bridgeChecks, "bridge-jit.lua");
        config.luavaJit = false;
        context.call(() -> {
            var original = new org.luava.runtime.LuaUserdata(new BridgeProbe());
            var wrapped = org.jsmacros.multilang.shared.luava.LuavaJavaObjects.wrap(original);
            var key = org.luava.runtime.LuaValue.valueOf("echo");
            require(wrapped.get(key) == wrapped.get(key), "method binding was not retained");
            var mt = new org.luava.runtime.LuaTable();
            original.setMetatable(mt);
            mt.rawset(org.luava.runtime.LuaValue.Meta.INDEX,
                org.luava.runtime.LuaFunction.of(args -> org.luava.runtime.LuaValue.valueOf(7)));
            require(wrapped.get(key).toLong() == 7, "method cache ignored a new metatable");
            mt.rawset(org.luava.runtime.LuaValue.Meta.INDEX,
                org.luava.runtime.LuaFunction.of(args -> org.luava.runtime.LuaValue.valueOf(9)));
            require(wrapped.get(key).toLong() == 9, "dynamic __index result was cached");

            var fileObject = org.jsmacros.multilang.shared.luava.LuavaJavaObjects.wrap(new java.io.File("test"));
            var name = org.luava.runtime.LuaValue.valueOf("getName");
            require(fileObject.get(name).isFunction(), "unrestricted method lookup failed");
            var policy = org.luava.binding.JavaAccessPolicy.setActive(org.luava.binding.JavaAccessPolicy.DEFAULT);
            try { require(fileObject.get(name).isNil(), "method cache bypassed the active access policy"); }
            finally { org.luava.binding.JavaAccessPolicy.restoreActive(policy); }
            return null;
        });

        Files.writeString(macros.resolve("lua_local_module.lua"), "return {answer = 42, text = '🙂'}");
        Files.writeString(macros.resolve("smoke.lua"), "assert(require('lua_local_module').answer == 42); assert(require('lua_local_module').text == utf8.char(0x1F642)); " + body);
        Probe file = new Probe(core);
        file.running = provider.getLanguage(core).trigger(@TRIGGER@, file, null, errors::add);
        await(file.running);
        awaitRecords(file, 2);

        config.useGlobalContext = true;
        Probe first = run("shared = 21; event:setCallback(JavaWrapper:methodToJava(function() return event:getValue() + shared end))", "shared1.lua");
        Probe second = run("shared = shared * 2; event:record(shared)", "shared2.lua");
        require(second.records.equals(List.of(42)), "Luava shared globals failed");
        require(((Number) first.callback.get()).intValue() == 84, "shared globals lost per-context bindings");
        config.luavaJit = true;
        Probe sharedJit = run("assert(shared == 42)", "shared-jit.lua");
        require(sharedJit.running.getCtx().getContext() == first.running.getCtx().getContext(), "JIT toggle replaced the shared state");
        config.luavaJit = false;
        first.running.getCtx().closeContext();
        Probe retained = run("assert(shared == 42)", "shared3.lua");

        config.splitGlobalContext = true;
        Probe owner = run("--@global isolated\nshared = 7; event:setCallback(JavaWrapper:methodToJava(function() return shared end))", "owner.lua");
        Probe forced = run("--@global-force isolated\nassert(shared == 7); shared = 42", "forced.lua");
        owner.running.getCtx().closeContext();
        try { owner.callback.get(); throw new AssertionError("closed Luava context accepted a callback"); }
        catch (@CORE_PACKAGE@.core.language.BaseScriptContext.ScriptAssertionError expected) {}
        config.splitGlobalContext = false;
        config.useGlobalContext = false;

        for (String source : List.of("local a = 1\nerror('expected Luava failure')", "local a = 1\nlocal =")) {
            Path errorFile = macros.resolve("editor target.lua");
            EventContainer<?> error = provider.getLanguage(core).trigger("lua", source, errorFile.toFile(), null, null, errors::add);
            await(error);
            require(errors.size() == 1, "expected one Luava error: " + errors);
            var wrapped = provider.wrapException(errors.remove(0));
            require(wrapped != null && wrapped.location != null, "Luava editor location missing");
            var location = (@CORE_PACKAGE@.core.language.BaseWrappedException.GuestLocation) wrapped.location;
            require(location.file.equals(errorFile.toFile()) && location.line == 2, "wrong Luava editor location");
        }

        Probe restore = run("JsMacros:getLanguages():luava(false)", "disable-luava.lua");
        restore.running.getCtx().closeContext();
        require(provider.getLanguage(core) == luaj, "toggle did not restore LuaJ");
        config.useGlobalContext = true;
        run("assert(shared == nil); event:record(42)", "restored.lua");
        config.useGlobalContext = false;
        require(((Number) inline.callback.apply(0)).intValue() == 42, "toggle invalidated an existing Luava callback");
        config.luava = true;
        Probe looping = new Probe(core);
        looping.running = provider.getLanguage(core).trigger("lua", "event:record(42); while true do end", null, looping, null, errors::add);
        awaitRecords(looping, 1);
        looping.running.getCtx().closeContext();
        await(looping.running);
        require(errors.size() == 1, "cancellation was not reported");
        errors.clear();
        for (Probe probe : List.of(old, inline, hot, bridge, bridgeJit, file, second, sharedJit, retained, owner, forced)) probe.running.getCtx().closeContext();
        config.luava = false;
        Probe saved = run("local settings = JsMacros:getLanguages(); assert(settings:luava(false, true):luava(false) == settings)", "save-settings.lua");
        saved.running.getCtx().closeContext();
        core.config.loadConfig();
        require(!core.config.getOptions(LuajConfig.class).luava && core.config.getOptions(LuajConfig.class).luavaJit,
            "Lua settings did not persist or one-argument setter reset JIT");
        Probe reset = run("JsMacros:getLanguages():luava(false, false)", "reset-settings.lua");
        reset.running.getCtx().closeContext();
        core.config.loadConfig();
        require(!core.config.getOptions(LuajConfig.class).luavaJit, "setter used stale options after reload");
        require(errors.isEmpty(), "unexpected Lua integration errors: " + errors);
        System.out.println("LuaJ/Luava: engine and live JIT toggles, compiled callbacks, Lua 5.4, bindings, coroutines, sleep, files/imports, pools, shutdown and editor errors passed");
    }

    private static void testDirectCalls() throws Exception {
        LuajConfig config = core.config.getOptions(LuajConfig.class);
        boolean engine = config.luava;
        boolean jit = config.luavaJit;
        Files.writeString(macros.resolve("chunk_local_module.lua"), "return {answer=42}");
        Files.writeString(macros.resolve("direct-call.lua"), """
            assert(file:getName() == 'direct-call.lua' and context ~= nil and event ~= nil)
            local module = require('chunk_local_module')
            return {answer=module.answer, jit=context:getCtx():getContext().state:isJitEnabled()}
            """);
        Files.writeString(macros.resolve("chunk-error.lua"), "local value = 1\nerror('expected direct chunk failure')");
        Probe direct = run("""
            local outerLocal = 99
            event:markJoined()
            assert(JavaWrapper:luava('return 6 * 7') == 42)
            assert(JavaWrapper:luava(false, 'return 85 // 2') == 42)
            assert(JavaWrapper:luava(true, false, 'event:record(21); return 42') == 42)
            assert(JavaWrapper:luava(false, false, 'event:record(42); return 42') == 42)
            assert(JavaWrapper:luava('return context:getCtx():getContext().state:isJitEnabled()'))
            assert(not JavaWrapper:luava(false, 'return context:getCtx():getContext().state:isJitEnabled()'))
            local fromFile = JavaWrapper:luava(event:getChunkFile())
            assert(fromFile.answer == 42 and fromFile.jit)
            fromFile = JavaWrapper:luava(false, event:getRelativeChunkFile())
            assert(fromFile.answer == 42 and not fromFile.jit)
            fromFile = JavaWrapper:luava(true, false, event:getChunkFile())
            assert(fromFile.answer == 42 and fromFile.jit)
            fromFile = JavaWrapper:luava(false, false, event:getRelativeChunkFile())
            assert(fromFile.answer == 42 and not fromFile.jit)
            assert(JavaWrapper:luava('return outerLocal') == nil)
            assert(JavaWrapper:luava('privateChunkGlobal = 42; return privateChunkGlobal') == 42)
            assert(JavaWrapper:luava('return privateChunkGlobal') == nil)
            assert(JavaWrapper:luava('return event') == event)
            assert(JavaWrapper:luava('return') == nil)
            assert(JavaWrapper:luava('return 42, 7') == 42)
            local copied = JavaWrapper:luava([[local t={answer=42, flag=false, text='é', supplementary='🙂', bytes=string.char(0,255)}; t.self=t; return t]])
            assert(copied.answer == 42 and copied.flag == false, 'wrong returned table data')
            assert(copied.text == 'é', 'wrong returned text')
            assert(copied.supplementary == string.char(240,159,153,130), 'wrong returned UTF-8 bytes')
            assert(copied.self == copied, 'returned table lost its cycle')
            assert(string.byte(copied.bytes, 1) == 0, 'wrong first byte: '..tostring(string.byte(copied.bytes, 1)))
            assert(string.byte(copied.bytes, 2) == 255, 'wrong second byte: '..tostring(string.byte(copied.bytes, 2)))
            for _, code in ipairs({'return function() end', 'return {bad=function() end}', 'local ='}) do
                local ok = pcall(function() JavaWrapper:luava(code) end)
                assert(not ok)
            end
            local missing = pcall(function() JavaWrapper:luava(event:getMissingChunkFile()) end)
            assert(not missing)
            pcall(function() JavaWrapper:luava(false, 'JavaWrapper:stop(); return 1') end)
            assert(event:isCurrentJoined() and not event:isCurrentInterrupted())
            assert(JavaWrapper:luava('return 42') == 42)
            event:clearJoined()
            """, "direct-calls.lua");
        require(config.luava == engine && config.luavaJit == jit, "direct calls changed language settings");
        require(direct.records.equals(List.of(21, 42)), "synchronous calls did not finish before returning");
        direct.running.getCtx().closeContext();
        testAsyncDirectCalls();
        testLuavaHelpers();

        Probe failed = new Probe(core);
        failed.running = provider.getLanguage(core).trigger("lua", "JavaWrapper:luava(event:getErrorChunkFile())",
            macros.resolve("direct-error.lua").toFile(), failed, null, errors::add);
        await(failed.running);
        require(errors.size() == 1, "expected one direct chunk failure: " + errors);
        var wrapped = provider.wrapException(errors.remove(0));
        require(wrapped != null && wrapped.location instanceof @CORE_PACKAGE@.core.language.BaseWrappedException.GuestLocation,
            "direct chunk editor location missing");
        var location = (@CORE_PACKAGE@.core.language.BaseWrappedException.GuestLocation) wrapped.location;
        require(location.file.equals(macros.resolve("chunk-error.lua").toFile()) && location.line == 2,
            "wrong direct chunk editor location: " + location.file + ":" + location.line + "; " + errors);
        failed.running.getCtx().closeContext();

        Probe looping = new Probe(core);
        looping.running = provider.getLanguage(core).trigger("lua",
            "JavaWrapper:luava(false, 'event:record(42); while true do end')", null, looping, null, errors::add);
        awaitRecords(looping, 1);
        looping.running.getCtx().closeContext();
        await(looping.running);
        require(errors.size() == 1, "guarded direct chunk cancellation was not reported: " + errors);
        errors.clear();
    }

    private static void testAsyncDirectCalls() throws Exception {
        Files.writeString(macros.resolve("async-call.lua"), """
            assert(file:getName() == 'async-call.lua')
            assert(require('chunk_local_module').answer == 42)
            event:awaitAsyncRelease(context:getCtx())
            event:record(42)
            return function() end -- Async results are discarded without conversion.
            """);
        Probe async = run("""
            assert(JavaWrapper:luava(true, true, [[
                assert(context:getCtx():getContext().state:isJitEnabled())
                event:awaitAsyncRelease(context:getCtx()); event:record(42)
            ]]) == nil)
            assert(JavaWrapper:luava(false, true, [[
                assert(not context:getCtx():getContext().state:isJitEnabled())
                event:awaitAsyncRelease(context:getCtx()); event:record(42)
            ]]) == nil)
            assert(JavaWrapper:luava(true, true, event:getAsyncChunkFile()) == nil)
            assert(JavaWrapper:luava(false, true, event:getRelativeAsyncChunkFile()) == nil)
            event:record(1)
            """, "async-direct-calls.lua");
        require(async.records.equals(List.of(1)), "async calls waited for the chunk");
        awaitAsyncStarts(async, 4);
        require(!async.running.getCtx().isContextClosed(), "script completion cancelled async chunks");
        async.asyncRelease.countDown();
        awaitRecords(async, 5);
        awaitAsyncWorkers(async);
        require(async.records.equals(List.of(1, 42, 42, 42, 42)), "wrong async execution order");
        async.running.getCtx().closeContext();

        Probe failed = run("JavaWrapper:luava(false, true, 'event:awaitAsyncRelease(context:getCtx()); error(\"expected async failure\")')",
            "async-error.lua");
        awaitAsyncStarts(failed, 1);
        failed.asyncRelease.countDown();
        awaitAsyncWorkers(failed);
        require(errors.size() == 1 && errors.get(0).getMessage().contains("expected async failure"),
            "async failure was not logged: " + errors);
        errors.clear();
        failed.running.getCtx().closeContext();

        Probe looping = run("JavaWrapper:luava(false, true, 'event:awaitAsyncRelease(context:getCtx()); event:record(42); while true do end')",
            "async-cancel.lua");
        awaitAsyncStarts(looping, 1);
        looping.asyncRelease.countDown();
        awaitRecords(looping, 1);
        looping.running.getCtx().closeContext();
        awaitAsyncWorkers(looping);
        require(errors.size() == 1, "async cancellation was not reported: " + errors);
        errors.clear();
    }

    private static void testLuavaHelpers() throws Exception {
        Files.writeString(macros.resolve("helper-call.lua"), """
            assert(file:getName() == 'helper-call.lua')
            assert(require('chunk_local_module').answer == 42)
            count = count + 1
            return count
            """);
        Probe sync = run("""
            local defaults = JavaWrapper:luava()
            assert(defaults:execute('return context:getCtx():getContext().state:isJitEnabled()'))
            defaults:close()
            local guarded = JavaWrapper:luava(false)
            assert(not guarded:execute('return context:getCtx():getContext().state:isJitEnabled()'))
            guarded:close()
            guarded:close()
            assert(guarded:isClosed())
            assert(not pcall(function() guarded:execute('return 42') end))
            local h = JavaWrapper:luava(true, false)
            event:setHelper(h)
            h:execute([[
                count = 40
                function hot_sum(n)
                    local sum = 0
                    for i = 1, n do sum = sum + i end
                    return sum
                end
                event:setHelperContext(context:getCtx())
                event:setCallback(JavaWrapper:methodToJava(function() return count end))
            ]])
            assert(h:execute('return hot_sum(20000)') == 200010000)
            assert(h:execute('return hot_sum(20000)') == 200010000)
            assert(h:execute(event:getHelperChunkFile()) == 41)
            assert(h:execute(event:getRelativeHelperChunkFile()) == 42)
            assert(h:execute('return file:getName()') == 'helpers.lua')
            assert(not pcall(function() h:execute('local =') end))
            assert(h:execute('return count') == 42)
            local isolated = JavaWrapper:luava(false, false)
            assert(isolated:execute('return count') == nil)
            isolated:close()
            """, "helpers.lua");
        var closure = (org.luava.runtime.bytecode.LuaClosure) sync.helperContext.getContext().state.get("hot_sum");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (closure.proto.jitCode == null && !closure.proto.jitDisabled && System.nanoTime() < deadline) Thread.sleep(10);
        require(closure.proto.jitCode != null, "helper did not retain JIT-compiled function");
        require(((Number) sync.callback.get()).intValue() == 42, "helper callback did not survive execute");
        require(sync.helper.execute("return count") != null, "helper did not survive caller completion");
        sync.helper.close();
        require(sync.helperContext.isContextClosed(), "helper context did not close");
        sync.running.getCtx().closeContext();

        Files.writeString(macros.resolve("helper-async.lua"), """
            assert(file:getName() == 'helper-async.lua')
            assert(require('chunk_local_module').answer == 42)
            count = count + 1
            event:record(count)
            """);
        Probe async = run("""
            local h = JavaWrapper:luava(false, true)
            event:setHelper(h)
            assert(h:execute('event:awaitAsyncRelease(context:getCtx()); count = 40; event:record(count)') == nil)
            h:execute(event:getAsyncHelperChunkFile())
            h:execute("error('expected helper failure')")
            h:execute('count = count + 1; event:record(count)')
            event:record(1)
            """, "helpers-async.lua");
        require(async.records.equals(List.of(1)), "async helper waited for execution");
        awaitAsyncStarts(async, 1);
        async.asyncRelease.countDown();
        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (async.records.size() < 4 && System.nanoTime() < deadline) Thread.sleep(10);
        require(async.records.equals(List.of(1, 40, 41, 42)), "async helper did not retain state and execution order");
        require(errors.size() == 1 && errors.get(0).getMessage().contains("expected helper failure"),
            "async helper failure was not logged: " + errors);
        errors.clear();
        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (async.asyncContexts.get(0).getMainThread() == async.asyncThreads.get(0)
            && errors.isEmpty() && System.nanoTime() < deadline) Thread.sleep(10);
        require(async.asyncContexts.get(0).getMainThread() != async.asyncThreads.get(0), "async helper did not finish its last execution");
        async.running.getCtx().closeContext();
        awaitHelperWorker(async);
        require(async.helper.isClosed(), "closing parent did not close idle helper");

        Probe cancelled = run("""
            local h = JavaWrapper:luava(false, true)
            event:setHelper(h)
            h:execute('event:awaitAsyncRelease(context:getCtx())')
            h:execute('event:record(99)')
            """, "helper-cancel.lua");
        awaitAsyncStarts(cancelled, 1);
        cancelled.helper.close();
        awaitHelperWorker(cancelled);
        require(cancelled.helper.isClosed() && cancelled.records.isEmpty(), "helper did not cancel queued execution");
        require(errors.size() == 1, "helper cancellation was not logged: " + errors);
        errors.clear();
        cancelled.running.getCtx().closeContext();
    }

    private static void awaitHelperWorker(Probe event) throws InterruptedException {
        Thread worker = event.asyncThreads.get(0);
        worker.join(10_000);
        require(!worker.isAlive(), "helper worker did not stop");
        require(event.asyncContexts.get(0).isContextClosed(), "helper child context was not closed");
        require(!event.running.getCtx().getBoundThreads().contains(worker), "helper worker remained bound");
    }

    private static void awaitAsyncStarts(Probe event, int count) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (event.asyncContexts.size() < count && errors.isEmpty() && System.nanoTime() < deadline) Thread.sleep(10);
        require(event.asyncContexts.size() == count && errors.isEmpty(), "async chunks did not start: " + errors);
    }

    private static void awaitAsyncWorkers(Probe event) throws InterruptedException {
        for (LuavaScriptContext child : event.asyncContexts) {
            Thread worker = child.getMainThread();
            worker.join(10_000);
            require(!worker.isAlive(), "async worker did not stop");
            require(child.isContextClosed(), "async child context was not closed");
            require(!event.running.getCtx().getBoundThreads().contains(worker), "async worker remained bound");
        }
    }

    private static Probe run(String script, String name) throws Exception {
        Path path = macros.resolve(name);
        Files.writeString(path, script);
        Probe event = new Probe(core);
        event.running = provider.getLanguage(core).trigger("lua", script, path.toFile(), event, null, errors::add);
        await(event.running);
        require(errors.isEmpty(), "Lua execution failed: " + errors);
        return event;
    }

    private static void await(EventContainer<?> event) throws Exception {
        Thread thread = event.getCtx().getMainThread();
        thread.join(15_000);
        if (thread.isAlive()) {
            event.getCtx().closeContext();
            throw new AssertionError("Lua script did not finish");
        }
    }

    private static void awaitRecords(Probe event, int count) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (event.records.size() < count && errors.isEmpty() && System.nanoTime() < deadline) Thread.sleep(10);
        require(event.records.size() == count && errors.isEmpty(), "callback did not complete: " + errors);
    }

    public static final class Probe @PROBE_PARENT@ {
        private MethodWrapper<Object, Object, Object, ?> callback;
        private EventContainer<?> running;
        public final List<Integer> records = new CopyOnWriteArrayList<>();
        public final List<LuavaScriptContext> asyncContexts = new CopyOnWriteArrayList<>();
        public final List<Thread> asyncThreads = new CopyOnWriteArrayList<>();
        public LuavaHelper helper;
        public LuavaScriptContext helperContext;
        public final CountDownLatch asyncRelease = new CountDownLatch(1);
        public final BridgeProbe bridge = new BridgeProbe();
        public Probe(Core<?, ?> core) { @PROBE_SUPER@ }
        public int getValue() { return 42; }
        public void record(int value) { records.add(value); }
        public void setCallback(MethodWrapper<Object, Object, Object, ?> callback) { this.callback = callback; }
        public void setHelper(LuavaHelper helper) { this.helper = helper; }
        public void setHelperContext(LuavaScriptContext context) { helperContext = context; }
        public java.io.File getChunkFile() { return macros.resolve("direct-call.lua").toFile(); }
        public java.io.File getRelativeChunkFile() { return new java.io.File("direct-call.lua"); }
        public java.io.File getErrorChunkFile() { return macros.resolve("chunk-error.lua").toFile(); }
        public java.io.File getMissingChunkFile() { return new java.io.File("missing-direct-call.lua"); }
        public java.io.File getAsyncChunkFile() { return macros.resolve("async-call.lua").toFile(); }
        public java.io.File getRelativeAsyncChunkFile() { return new java.io.File("async-call.lua"); }
        public java.io.File getHelperChunkFile() { return macros.resolve("helper-call.lua").toFile(); }
        public java.io.File getRelativeHelperChunkFile() { return new java.io.File("helper-call.lua"); }
        public java.io.File getAsyncHelperChunkFile() { return macros.resolve("helper-async.lua").toFile(); }
        public void awaitAsyncRelease(LuavaScriptContext child) throws InterruptedException {
            asyncThreads.add(Thread.currentThread());
            asyncContexts.add(child);
            if (!asyncRelease.await(10, TimeUnit.SECONDS)) throw new AssertionError("async gate timed out");
        }
        public void markJoined() { core.profile.joinedThreadStack.add(Thread.currentThread()); }
        public void clearJoined() { core.profile.joinedThreadStack.remove(Thread.currentThread()); }
        public boolean isCurrentJoined() { return core.profile.joinedThreadStack.contains(Thread.currentThread()); }
        public boolean isCurrentInterrupted() { return Thread.currentThread().isInterrupted(); }
        @Override public String getEventName() { return "Smoke"; }
    }

    public static final class BridgeProbe {
        public int value = 1;
        public int collision = 3;
        public org.luava.runtime.LuaFunction handler = org.luava.runtime.LuaFunction.of(args -> org.luava.runtime.LuaValue.valueOf(1));
        public final List<Object> list = new java.util.ArrayList<>(List.of(1, 2));
        public final java.util.Map<Object, Object> map = new java.util.HashMap<>();
        public final int[] array = {1, 2};
        public int echo(int value) { return value; }
        public String choose(int value) { return "number"; }
        public String choose(Object value) { return "object"; }
        public Object echoObject(Object value) { return value; }
        public int collision() { return -1; }
        public BridgeProbe child() { return new BridgeProbe(); }
        public void changeHandler() { handler = org.luava.runtime.LuaFunction.of(args -> org.luava.runtime.LuaValue.valueOf(2)); }
        public int handler() { return -1; }
        public void removeMapShadow() { map.remove("put"); }
        public List<Integer> snapshot() { return List.of(1, 2); }
        public Class<?> getType() { return BridgeProbe.class; }
        public static int staticEcho(int value) { return value; }
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
