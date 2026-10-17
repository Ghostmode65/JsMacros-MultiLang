package xyz.wagyourtail.jsmacros.jython;

import com.google.common.collect.Sets;
import org.python.core.*;
import org.python.util.PythonInterpreter;
import org.jsmacros.multilang.shared.ClassLoaderContext;
import org.jsmacros.multilang.shared.LanguageFileMatch;
import xyz.wagyourtail.jsmacros.core.Core;
import xyz.wagyourtail.jsmacros.core.extensions.Extension;
import xyz.wagyourtail.jsmacros.core.language.BaseLanguage;
import xyz.wagyourtail.jsmacros.core.language.BaseWrappedException;
import xyz.wagyourtail.jsmacros.core.library.BaseLibrary;
import xyz.wagyourtail.jsmacros.jython.language.impl.JythonLanguageDescription;
import xyz.wagyourtail.jsmacros.jython.library.impl.FWrapper;
import xyz.wagyourtail.jsmacros.jython.config.PythonConfig;
import xyz.wagyourtail.jsmacros.multilang.python.PythonExtension;

import java.io.File;
import java.util.Set;

public class JythonExtension implements Extension {
    private static JythonLanguageDescription languageDescription;
    private final PythonExtension graalpy = new PythonExtension();

    @Override
    public void init() {
        try {
            Core.getInstance().config.addOptions("python", PythonConfig.class);
        } catch (IllegalAccessException | InstantiationException error) {
            throw new RuntimeException(error);
        }
        Thread t = new Thread(() -> {
            try (PythonInterpreter interp = JythonLanguageDescription.createInterp(new File("./"))) {
                interp.exec("print(\"Jython Loaded.\")");
            } catch(Exception e) {
                e.printStackTrace();
            }
        });
        t.start();
    }

    @Override
    public int getPriority() {
        return 0;
    }

    @Override
    public String getLanguageImplName() {
        return "jython";
    }

    @Override
    public ExtMatch extensionMatch(File file) {
        return switch (LanguageFileMatch.match(file, "py", getLanguageImplName(), false)) {
            case NAMED -> ExtMatch.MATCH_WITH_NAME;
            case MATCH -> ExtMatch.MATCH;
            case NONE -> ExtMatch.NOT_MATCH;
        };
    }

    @Override
    public String defaultFileExtension() {
        return "py";
    }

    @Override
    public synchronized BaseLanguage<?, ?> getLanguage(Core<?, ?> core) {
        PythonConfig config = core.config.getOptions(PythonConfig.class);
        if (config != null && config.useGraalPy) {
            return graalpy.getLanguage(core);
        }
        if (languageDescription == null) {
            languageDescription = ClassLoaderContext.call(
                JythonExtension.class.getClassLoader(),
                () -> new JythonLanguageDescription(this, core)
            );
        }
        return languageDescription;
    }

    @Override
    public Set<Class<? extends BaseLibrary>> getLibraries() {
        return Sets.newHashSet(FWrapper.class, xyz.wagyourtail.jsmacros.multilang.python.FWrapper.class);
    }

    @Override
    public BaseWrappedException<?> wrapException(Throwable ex) {
        BaseWrappedException<?> pythonError = graalpy.wrapException(ex);
        if (pythonError != null) return pythonError;
        if (ex instanceof PyException) {
            String message = ((PyType) ((PyException) ex).type).fastGetName().replace("exceptions.", "");
            if (ex instanceof PySyntaxError) {
                if (((PyBaseExceptionDerived)((PySyntaxError) ex).value).args instanceof PyTuple) {
                    PyTuple exceptionData = (PyTuple) ((PyBaseExceptionDerived) ((PySyntaxError) ex).value).args;
                    message += ": " + exceptionData.get(0);
                    PyTuple locationData = (PyTuple) exceptionData.get(1);
                    String fileName = (String) locationData.get(0);
                    int line = (int) locationData.get(1);
                    int col = (int) locationData.get(2);
                    BaseWrappedException.SourceLocation loc = new BaseWrappedException.GuestLocation(new File(fileName), -1, -1, line, col);
                    return new BaseWrappedException<>(ex, message, loc, ((PyException) ex).traceback != null ? wrapTraceback(((PyException) ex).traceback) : null);
                }
            } else {
                if (((PyException) ex).value instanceof PyString) message += ": " + ((PyException) ex).value.asString();
                else if (ex.getCause() != null) {
                    String intMessage = ex.getCause().getMessage();
                    if (intMessage != null) message += ": " + intMessage;
                }
                return new BaseWrappedException<>(ex, message, null, ((PyException) ex).traceback != null ? wrapTraceback(((PyException) ex).traceback) : null);
            }
        }
        return null;
    }


    public BaseWrappedException<?> wrapTraceback(PyTraceback traceback) {
        BaseWrappedException.GuestLocation loc = new BaseWrappedException.GuestLocation(new File(traceback.tb_frame.f_code.co_filename), -1, -1, traceback.tb_lineno, -1);
        return new BaseWrappedException<>(traceback, " at " + traceback.tb_frame.f_code.co_name, loc, traceback.tb_next != null ? wrapTraceback((PyTraceback) traceback.tb_next) : null);
    }

    @Override
    public boolean isGuestObject(Object o) {
        return o instanceof PyObject || graalpy.isGuestObject(o);
    }

}
