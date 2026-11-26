package @CORE_PACKAGE@.luava;

import @CORE_PACKAGE@.core.Core;
import @CORE_PACKAGE@.core.config.ScriptTrigger;
import @CORE_PACKAGE@.core.event.BaseEvent;
import @CORE_PACKAGE@.core.extensions.Extension;
import @CORE_PACKAGE@.core.language.BaseLanguage;
import @CORE_PACKAGE@.core.language.EventContainer;
import @CORE_PACKAGE@.luaj.config.LuajConfig;
import org.jsmacros.multilang.shared.luava.LuavaExecutionSupport;
import org.jsmacros.multilang.shared.luava.LuavaSharedGlobals;

import java.io.File;
import java.nio.file.Files;

public final class LuavaLanguageDefinition extends BaseLanguage<LuavaExecutionSupport.Session, LuavaScriptContext> {
    public LuavaLanguageDefinition(Extension extension, Core<?, ?> runner) {
        super(extension, runner);
    }

    private void execute(EventContainer<LuavaScriptContext> eventContext, String script, BaseEvent event) throws Exception {
        LuavaScriptContext ctx = eventContext.getCtx();
        LuajConfig config = runner.config.getOptions(LuajConfig.class);
        LuavaSharedGlobals.Claim claim = LuavaSharedGlobals.claim(ctx, config.useGlobalContext, config.splitGlobalContext);
        ctx.setContext(claim.session());
        ctx.bindings.pool = claim.pool();
        try {
            ctx.call(() -> {
                retrieveLibs(ctx).forEach(ctx.bindings::put);
                ctx.bindings.put("event", event);
                ctx.bindings.put("file", ctx.getFile());
                ctx.bindings.put("context", eventContext);
                return ctx.getContext().eval(ctx.bindings, script);
            });
        } finally {
            LuavaSharedGlobals.release(ctx, claim.pool());
        }
    }

    @Override
    protected void exec(EventContainer<LuavaScriptContext> ctx, ScriptTrigger macro, BaseEvent event) throws Exception {
        execute(ctx, Files.readString(ctx.getCtx().getFile().toPath()), event);
    }

    @Override
    protected void exec(EventContainer<LuavaScriptContext> ctx, String lang, String script, BaseEvent event) throws Exception {
        execute(ctx, script, event);
    }

    @Override
    public LuavaScriptContext createContext(BaseEvent event, File file) {
        return new LuavaScriptContext(runner, event, file);
    }
}
