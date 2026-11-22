package xyz.wagyourtail.jsmacros.jruby.language.impl;

import org.jruby.embed.ScriptingContainer;
import xyz.wagyourtail.jsmacros.core.event.BaseEvent;
import xyz.wagyourtail.jsmacros.core.language.BaseScriptContext;

import java.io.File;

public class JRubyScriptContext extends BaseScriptContext<ScriptingContainer> {
    public JRubyScriptContext(BaseEvent event, File file) {
        super(event, file);
    }

    @Override
    public synchronized void closeContext() {
        if (closed) return;
        ScriptingContainer ruby = getContext();
        super.closeContext();
        if (ruby != null) ruby.terminate();
    }

    @Override
    public boolean isMultiThreaded() {
        return true;
    }

}
