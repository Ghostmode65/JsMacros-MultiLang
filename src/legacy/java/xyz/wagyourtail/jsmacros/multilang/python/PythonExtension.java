package xyz.wagyourtail.jsmacros.multilang.python;

import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.SourceSection;
import org.graalvm.polyglot.Value;
import org.jsmacros.multilang.shared.ClassLoaderContext;
import org.jsmacros.multilang.shared.LanguageFileMatch;
import org.jsmacros.multilang.shared.python.PythonExecutionSupport;
import xyz.wagyourtail.jsmacros.core.Core;
import xyz.wagyourtail.jsmacros.core.extensions.Extension;
import xyz.wagyourtail.jsmacros.core.language.BaseLanguage;
import xyz.wagyourtail.jsmacros.core.language.BaseWrappedException;
import xyz.wagyourtail.jsmacros.core.library.BaseLibrary;

import java.io.File;
import java.util.ArrayList;
import java.util.Set;

public final class PythonExtension implements Extension {
    private PythonLanguageDefinition language;

    @Override
    public void init() {
        PythonExecutionSupport.preload(Core.getInstance().profile::logError);
    }

    @Override public int getPriority() { return 0; }
    @Override public String getLanguageImplName() { return "graalpy"; }
    @Override public String defaultFileExtension() { return "py"; }
    @Override public java.util.Set<java.net.URL> getDependencies() { return Set.of(); }

    @Override
    public ExtMatch extensionMatch(File file) {
        return switch (LanguageFileMatch.match(file, "py", getLanguageImplName(), false)) {
            case NAMED -> ExtMatch.MATCH_WITH_NAME;
            case MATCH -> ExtMatch.MATCH;
            case NONE -> ExtMatch.NOT_MATCH;
        };
    }

    @Override
    public synchronized BaseLanguage<?, ?> getLanguage(Core<?, ?> runner) {
        if (language == null) {
            language = ClassLoaderContext.call(getClass().getClassLoader(),
                () -> new PythonLanguageDefinition(this, runner));
        }
        return language;
    }

    @Override
    public Set<Class<? extends BaseLibrary>> getLibraries() {
        return Set.of(FWrapper.class);
    }

    @Override
    public BaseWrappedException<?> wrapException(Throwable error) {
        if (!(error instanceof PolyglotException polyglot)) return null;
        var frames = new ArrayList<PolyglotException.StackFrame>();
        polyglot.getPolyglotStackTrace().forEach(frames::add);
        BaseWrappedException<?> trace = null;
        for (int i = frames.size() - 1; i >= 0; i--) {
            var frame = frames.get(i);
            if (frame.isGuestFrame()) {
                trace = new BaseWrappedException<>(frame, " at " + frame.getRootName(),
                    location(frame.getSourceLocation()), trace);
            } else {
                trace = BaseWrappedException.wrapHostElement(frame.toHostFrame(), trace);
            }
        }
        return new BaseWrappedException<>(polyglot, polyglot.getMessage(), location(polyglot.getSourceLocation()), trace);
    }

    private static BaseWrappedException.SourceLocation location(SourceSection section) {
        if (section == null) return null;
        String path = section.getSource().getPath();
        if (path == null) {
            return new BaseWrappedException.HostLocation(section.getSource().getName() + " " + section.getStartLine());
        }
        return new BaseWrappedException.GuestLocation(new File(path), section.getCharIndex(), section.getCharEndIndex(),
            section.getStartLine(), section.getStartColumn());
    }

    @Override public boolean isGuestObject(Object value) { return value instanceof Value; }
}
