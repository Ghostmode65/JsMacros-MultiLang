package org.jsmacros.multilang.shared.luava;

import org.luava.runtime.LuaException;

import java.io.File;
import java.util.regex.Pattern;

public final class LuavaScriptException extends RuntimeException {
    private static final Pattern LINE = Pattern.compile("(?:^|:)(\\d+):");
    public final File file;
    public final int line;

    public static LuavaScriptException find(Throwable error) {
        for (int i = 0; error != null && i < 16; i++, error = error.getCause()) {
            if (error instanceof LuavaScriptException located) return located;
        }
        return null;
    }

    public LuavaScriptException(LuaException error, File fallback) {
        super(error.getMessage(), error);
        File source = fallback;
        int number = error.getLine();
        for (var frame : error.getLuaFrames()) {
            String name = frame.function == null ? null : frame.function.getSource();
            if (name != null && name.startsWith("@")) {
                source = new File(name.substring(1));
                if (number <= 0) number = frame.line;
                break;
            }
        }
        if (number <= 0 && error.getMessage() != null) {
            var match = LINE.matcher(error.getMessage());
            if (match.find()) number = Integer.parseInt(match.group(1));
        }
        file = source;
        line = number;
    }
}
