package @CORE_PACKAGE@.graal.python;

import @CORE_PACKAGE@.core.Core;
import @CORE_PACKAGE@.core.extensions.Extension;
import @CORE_PACKAGE@.core.extensions.LanguageExtension;
import @CORE_PACKAGE@.core.extensions.LibraryExtension;
import @CORE_PACKAGE@.core.language.BaseLanguage;
import @CORE_PACKAGE@.core.language.BaseWrappedException;
import @CORE_PACKAGE@.core.library.BaseLibrary;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.SourceSection;
import org.graalvm.polyglot.Value;
import org.jsmacros.multilang.shared.ClassLoaderContext;
import org.jsmacros.multilang.shared.LanguageFileMatch;
import org.jsmacros.multilang.shared.python.PythonExecutionSupport;

import java.io.File;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;

public final class PythonExtension implements LanguageExtension, LibraryExtension {
    private PythonLanguageDefinition graalpy;

    @Override public String getExtensionName() { return "graalpy"; }
    @Override public String maxCoreVersion() { return "@RUBY_MAX_CORE_VERSION@"; }

    @Override
    public void init(Core<?, ?> runner) {
        PythonExecutionSupport.preload(runner.profile::logError);
    }

    @Override
    public Map<String, String> getTranslations(String lang) {
        return Extension.getTranslationsInternal(PythonExtension.class, "@PYTHON_TRANSLATIONS@" + lang + ".json");
    }

    @Override public int getPriority() { return 0; }
    @Override public String defaultFileExtension() { return "py"; }

    @Override
    public ExtMatch extensionMatch(File file) {
        return switch (LanguageFileMatch.match(file, "py", "graalpy", false)) {
            case NAMED -> ExtMatch.MATCH_WITH_NAME;
            case MATCH -> ExtMatch.MATCH;
            case NONE -> ExtMatch.NOT_MATCH;
        };
    }

    @Override
    public synchronized BaseLanguage<?, ?> getLanguage(Core<?, ?> runner) {
        if (graalpy == null) graalpy = ClassLoaderContext.call(getClass().getClassLoader(),
            () -> new PythonLanguageDefinition(this, runner));
        return graalpy;
    }

    @Override
    public Set<Class<? extends BaseLibrary>> getLibraries() {
        return Set.of(FWrapper.class);
    }

    @Override
    public BaseWrappedException<?> wrapException(Throwable error) {
        for (int i = 0; error != null && i < 16; i++, error = error.getCause()) {
            if (error instanceof PythonScriptException python) {
                return wrapGraalPy((PolyglotException) python.getCause(), python.location);
            }
            if (error instanceof PolyglotException python) return wrapGraalPy(python, null);
        }
        return null;
    }

    private static BaseWrappedException<?> wrapGraalPy(PolyglotException error, BaseWrappedException.SourceLocation preferred) {
        var frames = new ArrayList<PolyglotException.StackFrame>();
        error.getPolyglotStackTrace().forEach(frames::add);
        SourceSection source = error.getSourceLocation();
        for (var frame : frames) {
            if (frame.isGuestFrame() && frame.getSourceLocation() != null) {
                source = frame.getSourceLocation();
                break;
            }
        }
        BaseWrappedException<?> trace = null;
        for (int i = frames.size() - 1; i >= 0; i--) {
            var frame = frames.get(i);
            trace = frame.isGuestFrame()
                ? new BaseWrappedException<>(frame, " at " + frame.getRootName(), location(frame.getSourceLocation()), trace)
                : BaseWrappedException.wrapHostElement(frame.toHostFrame(), trace);
        }
        return new BaseWrappedException<>(error, error.getMessage(), preferred == null ? location(source) : preferred, trace);
    }

    private static BaseWrappedException.SourceLocation location(SourceSection section) {
        if (section == null) return null;
        String path = section.getSource().getPath();
        if (path == null) return new BaseWrappedException.HostLocation(section.getSource().getName() + " " + section.getStartLine());
        return new BaseWrappedException.GuestLocation(new File(path), section.getCharIndex(), section.getCharEndIndex(),
            section.getStartLine(), section.getStartColumn());
    }

    @Override public boolean isGuestObject(Object value) { return value instanceof Value; }
}
