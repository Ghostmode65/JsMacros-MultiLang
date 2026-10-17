package org.jsmacros.multilang.shared.ruby;

import org.jruby.embed.LocalContextScope;
import org.jruby.embed.ScriptingContainer;
import org.jruby.RubyMethod;
import org.jruby.javasupport.JavaUtil;
import org.jruby.runtime.ThreadContext;
import org.jruby.runtime.builtin.IRubyObject;

import java.nio.file.Path;
import java.util.Arrays;

public final class RubyExecutionSupport {
    private RubyExecutionSupport() {
    }

    public static ScriptingContainer newContainer(ClassLoader loader) {
        ScriptingContainer container = new ScriptingContainer(LocalContextScope.SINGLETHREAD);
        if (loader != null) {
            container.setClassLoader(loader);
        }
        return container;
    }

    public static void setWorkingDirectory(ScriptingContainer container, Path workingDirectory) {
        if (workingDirectory != null) {
            container.setCurrentDirectory(workingDirectory.toString());
        }
    }

    public static void bindLibrary(ScriptingContainer container, String name, Object library) {
        container.put("Time".equals(name) ? "FTime" : name, library);
    }

    public static void bindScriptValues(ScriptingContainer container, Object event,
                                        Object file, Object context) {
        container.put("event", event);
        container.put("file", file);
        container.put("context", context);
    }

    public static IRubyObject call(ScriptingContainer container, RubyMethod method, Object... arguments) {
        ThreadContext thread = container.getProvider().getRuntime().getCurrentContext();
        thread.pushNewScope(thread.getCurrentStaticScope());
        try {
            IRubyObject[] converted = JavaUtil.convertJavaArrayToRuby(thread.runtime, arguments);
            int arity = @RUBY_ARITY@;
            if (arity >= 0 && converted.length > arity) converted = Arrays.copyOf(converted, arity);
            return method.call(thread, converted, thread.getFrameBlock());
        } finally {
            thread.popScope();
        }
    }
}
