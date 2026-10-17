package @CORE_PACKAGE@.graal.python;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.jsmacros.multilang.shared.python.PythonExecutionSupport;
import @CORE_PACKAGE@.core.Core;
import @CORE_PACKAGE@.core.config.ScriptTrigger;
import @CORE_PACKAGE@.core.event.BaseEvent;
import @CORE_PACKAGE@.core.language.BaseLanguage;
import @CORE_PACKAGE@.core.language.EventContainer;

import java.io.File;

public final class PythonLanguageDefinition extends BaseLanguage<Context, PythonScriptContext> {
    public PythonLanguageDefinition(PythonExtension extension, Core<?, ?> runner) {
        super(extension, runner);
    }

    private void execute(EventContainer<PythonScriptContext> eventContext, BaseEvent event, Source source) throws Exception {
        PythonScriptContext script = eventContext.getCtx();
        Context context = PythonExecutionSupport.newContext(script.getContainedFolder().toPath());
        script.setContext(context);
        script.call(() -> {
            Value bindings = context.getBindings("python");
            retrieveLibs(script).forEach(bindings::putMember);
            bindings.putMember("event", event);
            bindings.putMember("file", script.getFile());
            bindings.putMember("context", eventContext);
            context.eval(source);
            return null;
        });
    }

    @Override
    protected void exec(EventContainer<PythonScriptContext> context, ScriptTrigger trigger, BaseEvent event) throws Exception {
        execute(context, event, Source.newBuilder("python", context.getCtx().getFile()).build());
    }

    @Override
    protected void exec(EventContainer<PythonScriptContext> context, String lang, String script, BaseEvent event) throws Exception {
        File file = context.getCtx().getFile();
        Source source = file == null ? Source.newBuilder("python", script, "<script>").build()
            : Source.newBuilder("python", file).content(script).build();
        execute(context, event, source);
    }

    @Override
    public PythonScriptContext createContext(BaseEvent event, File file) {
        return new PythonScriptContext(runner, event, file);
    }
}
