package xyz.wagyourtail.jsmacros.jruby.language.impl;

import org.jruby.embed.ScriptingContainer;
import org.jsmacros.multilang.shared.ruby.RubyExecutionSupport;
import xyz.wagyourtail.jsmacros.core.Core;
import xyz.wagyourtail.jsmacros.core.config.ScriptTrigger;
import xyz.wagyourtail.jsmacros.core.event.BaseEvent;
import xyz.wagyourtail.jsmacros.core.language.BaseLanguage;
import xyz.wagyourtail.jsmacros.core.language.EventContainer;
import xyz.wagyourtail.jsmacros.jruby.client.JRubyExtension;

import javax.annotation.Nullable;
import java.io.File;
import java.io.FileReader;
import java.io.StringReader;
import java.nio.file.Path;

public class JRubyLanguageDefinition extends BaseLanguage<ScriptingContainer, JRubyScriptContext> {
    public JRubyLanguageDefinition(JRubyExtension extension, Core runner) {
        super(extension, runner);
    }

    protected void runInstance(EventContainer<JRubyScriptContext> ctx, Executor e, @Nullable Path cwd) throws Exception {
        ScriptingContainer instance = RubyExecutionSupport.newContainer(JRubyExtension.class.getClassLoader());
        ctx.getCtx().setContext(instance);
        RubyExecutionSupport.setWorkingDirectory(instance, cwd);

        retrieveLibs(ctx.getCtx()).forEach((name, library) ->
            RubyExecutionSupport.bindLibrary(instance, name, library));

        e.accept(instance);
    }

    @Override
    protected void exec(EventContainer<JRubyScriptContext> ctx, ScriptTrigger macro, BaseEvent event) throws Exception {
        runInstance(ctx, instance -> {
            RubyExecutionSupport.bindScriptValues(instance, event, ctx.getCtx().getFile(), ctx);

            try (FileReader reader = new FileReader(ctx.getCtx().getFile())) {
                instance.runScriptlet(reader, ctx.getCtx().getFile().getAbsolutePath());
            }
        }, ctx.getCtx().getFile().getParentFile().toPath());

    }

    @Override
    protected void exec(EventContainer<JRubyScriptContext> ctx, String lang, String script, BaseEvent event) throws Exception {
        runInstance(ctx, instance -> {
            RubyExecutionSupport.bindScriptValues(instance, event, ctx.getCtx().getFile(), ctx);

            if (ctx.getCtx().getFile() != null) {
                instance.runScriptlet(new StringReader(script), ctx.getCtx().getFile().getAbsolutePath());
            } else {
                instance.runScriptlet(script);
            }
        }, ctx.getCtx().getFile() != null ? ctx.getCtx().getFile().getParentFile().toPath() : null);
    }

    @Override
    public JRubyScriptContext createContext(BaseEvent event, File path) {
        return new JRubyScriptContext(event, path);
    }

    private interface Executor {
        void accept(ScriptingContainer instance) throws Exception;
    }
}
