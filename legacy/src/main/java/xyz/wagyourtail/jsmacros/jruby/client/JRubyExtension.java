package xyz.wagyourtail.jsmacros.jruby.client;

import com.google.common.collect.Sets;
import org.jruby.RubyException;
import org.jruby.embed.EvalFailedException;
import org.jruby.embed.ScriptingContainer;
import org.jruby.exceptions.RaiseException;
import org.jruby.runtime.backtrace.RubyStackTraceElement;
import org.jruby.runtime.builtin.IRubyObject;
import org.jsmacros.multilang.shared.ClassLoaderContext;
import org.jsmacros.multilang.shared.LanguageFileMatch;
import org.jsmacros.multilang.shared.ruby.RubyExecutionSupport;
import xyz.wagyourtail.jsmacros.core.Core;
import xyz.wagyourtail.jsmacros.core.extensions.Extension;
import xyz.wagyourtail.jsmacros.core.language.BaseLanguage;
import xyz.wagyourtail.jsmacros.core.language.BaseWrappedException;
import xyz.wagyourtail.jsmacros.core.library.BaseLibrary;
import xyz.wagyourtail.jsmacros.jruby.language.impl.JRubyLanguageDefinition;
import xyz.wagyourtail.jsmacros.jruby.library.impl.FWrapper;

import java.io.File;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Set;

public class JRubyExtension implements Extension {

    JRubyLanguageDefinition languageDescription;

    @Override
    public void init() {
        Thread t = new Thread(() -> {
            ScriptingContainer instance = RubyExecutionSupport.newContainer(JRubyExtension.class.getClassLoader());
            try {
                instance.runScriptlet("p \"Ruby Pre-Loaded\"");
            } finally {
                instance.terminate();
            }
        }, "JRuby-Preload");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public int getPriority() {
        return 0;
    }

    @Override
    public String getLanguageImplName() {
        return "jruby";
    }

    @Override
    public ExtMatch extensionMatch(File file) {
        return switch (LanguageFileMatch.match(file, "rb", getLanguageImplName(), false)) {
            case NAMED -> ExtMatch.MATCH_WITH_NAME;
            case MATCH -> ExtMatch.MATCH;
            case NONE -> ExtMatch.NOT_MATCH;
        };
    }

    @Override
    public String defaultFileExtension() {
        return "rb";
    }

    @Override
    public BaseLanguage<?, ?> getLanguage(Core<?, ?> core) {
        if (languageDescription == null) {
            languageDescription = ClassLoaderContext.call(
                JRubyExtension.class.getClassLoader(),
                () -> new JRubyLanguageDefinition(this, core)
            );
        }
        return languageDescription;
    }

    @Override
    public Set<Class<? extends BaseLibrary>> getLibraries() {
        return Sets.newHashSet(FWrapper.class);
    }

    @Override
    public BaseWrappedException<?> wrapException(Throwable ex) {
        if (ex instanceof EvalFailedException) {
            Throwable cause = ex.getCause();
            if (cause instanceof RaiseException) {
                RubyException e = ((RaiseException) ex.getCause()).getException();
                Iterator<StackTraceElement> elements = Arrays.stream(e.getBacktraceElements()).map(RubyStackTraceElement::asStackTraceElement).iterator();
                return new BaseWrappedException<>(e, e.getMessageAsJavaString(), null, elements.hasNext() ? traceStack(elements.next(), elements) : null);
            } else {
                Iterator<StackTraceElement> elements = Arrays.stream(cause.getStackTrace()).iterator();
                return new BaseWrappedException<>(cause, cause.getClass().getName() + ": " + cause.getMessage(), null, elements.hasNext() ? traceStack(elements.next(), elements) : null);
            }
        }
        return null;
    }

    private BaseWrappedException<StackTraceElement> traceStack(StackTraceElement current, Iterator<StackTraceElement> elements) {
        if (current.getClassName().equals("org.jruby.embed.internal.EmbedEvalUnitImpl")) return null;
        if (current.getClassName().startsWith("org.jruby")) return elements.hasNext() ? traceStack(elements.next(), elements) : null;
        BaseWrappedException.SourceLocation loc;
        if (current.getClassName().equals("RUBY")) {
            loc = new BaseWrappedException.GuestLocation(new File(current.getFileName()), -1, -1, current.getLineNumber(), -1);
        } else {
            loc = new BaseWrappedException.HostLocation(current.getClassName() + " " + current.getLineNumber());
        }
        return new BaseWrappedException<>(current, current.getMethodName(), loc, elements.hasNext() ? traceStack(elements.next(), elements) : null);
    }

    @Override
    public boolean isGuestObject(Object o) {
        return o instanceof IRubyObject;
    }

}
