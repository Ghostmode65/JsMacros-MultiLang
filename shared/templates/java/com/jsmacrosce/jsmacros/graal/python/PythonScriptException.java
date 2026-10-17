package @CORE_PACKAGE@.graal.python;

import @CORE_PACKAGE@.core.language.BaseWrappedException;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;

import java.io.File;

public final class PythonScriptException extends RuntimeException {
    public final BaseWrappedException.SourceLocation location;

    public PythonScriptException(PolyglotException error) {
        super(error.getMessage(), error);
        BaseWrappedException.SourceLocation found = null;
        try {
            Value exception = error.getGuestObject();
            if (exception != null) {
                if (error.isSyntaxError()) {
                    Value file = exception.getMember("filename");
                    Value line = exception.getMember("lineno");
                    Value column = exception.getMember("offset");
                    if (file != null && !file.isNull() && line != null && line.fitsInInt()) {
                        found = location(file.asString(), line.asInt(), column != null && column.fitsInInt() ? column.asInt() : -1);
                    }
                } else {
                    Value traceback = exception.getMember("__traceback__");
                    while (traceback != null && !traceback.isNull()) {
                        String file = traceback.getMember("tb_frame").getMember("f_code").getMember("co_filename").asString();
                        found = location(file, traceback.getMember("tb_lineno").asInt(), -1);
                        traceback = traceback.getMember("tb_next");
                    }
                }
            }
        } catch (RuntimeException unavailable) {
        }
        location = found;
    }

    private static BaseWrappedException.SourceLocation location(String file, int line, int column) {
        return file.startsWith("<") ? new BaseWrappedException.HostLocation(file + " " + line)
            : new BaseWrappedException.GuestLocation(new File(file), -1, -1, line, column);
    }
}
