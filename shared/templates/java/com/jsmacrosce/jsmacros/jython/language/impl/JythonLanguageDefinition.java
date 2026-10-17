package @CORE_PACKAGE@.jython.language.impl;

import @CORE_PACKAGE@.core.Core;
import @CORE_PACKAGE@.core.config.ScriptTrigger;
import @CORE_PACKAGE@.core.event.BaseEvent;
import @CORE_PACKAGE@.core.extensions.Extension;
import @CORE_PACKAGE@.core.language.BaseLanguage;
import @CORE_PACKAGE@.core.language.EventContainer;
import org.jsmacros.multilang.shared.ClassLoaderContext;
import org.python.core.Py;
import org.python.core.PySystemState;
import org.python.util.PythonInterpreter;

import java.io.File;

public final class JythonLanguageDefinition extends BaseLanguage<PythonInterpreter, JythonScriptContext> {
    public JythonLanguageDefinition(Extension extension, Core<?, ?> runner) {
        super(extension, runner);
    }

    private void execute(EventContainer<JythonScriptContext> eventContext, BaseEvent event, String source) throws Exception {
        JythonScriptContext script = eventContext.getCtx();
        PythonInterpreter python = ClassLoaderContext.call(getClass().getClassLoader(), () -> {
            PySystemState system = new PySystemState();
            system.setClassLoader(getClass().getClassLoader());
            system.path.insert(0, Py.newStringOrUnicode(script.getContainedFolder().getAbsolutePath()));
            return new PythonInterpreter(null, system);
        });
        script.setContext(python);
        script.call(() -> {
            retrieveLibs(script).forEach(python::set);
            python.set("event", event);
            python.set("file", script.getFile());
            python.set("context", eventContext);
            if (source == null) python.execfile(script.getFile().getCanonicalPath());
            else python.exec(python.compile(source, script.getFile() == null ? "<script>" : script.getFile().getCanonicalPath()));
            return null;
        });
    }

    @Override
    protected void exec(EventContainer<JythonScriptContext> context, ScriptTrigger trigger, BaseEvent event) throws Exception {
        execute(context, event, null);
    }

    @Override
    protected void exec(EventContainer<JythonScriptContext> context, String lang, String script, BaseEvent event) throws Exception {
        execute(context, event, script);
    }

    @Override
    public JythonScriptContext createContext(BaseEvent event, File file) {
        return new JythonScriptContext(runner, event, file);
    }
}
