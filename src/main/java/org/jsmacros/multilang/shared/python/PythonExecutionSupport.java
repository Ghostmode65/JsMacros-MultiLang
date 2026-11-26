package org.jsmacros.multilang.shared.python;

import org.graalvm.polyglot.Context;
import org.jsmacros.multilang.shared.ClassLoaderContext;

import java.nio.file.Path;
import java.util.function.Consumer;

public final class PythonExecutionSupport {
    private PythonExecutionSupport() {
    }

    public static Context newContext(Path directory) {
        return ClassLoaderContext.call(PythonExecutionSupport.class.getClassLoader(), () -> {
            Context.Builder builder = Context.newBuilder("python")
                .allowAllAccess(true)
                .option("engine.WarnInterpreterOnly", "false");
            if (directory != null) {
                Path absolute = directory.toAbsolutePath().normalize();
                builder.currentWorkingDirectory(absolute);
                builder.option("python.PythonPath", absolute.toString());
            }
            return builder.build();
        });
    }

    public static void preload(Consumer<Throwable> logError) {
        Thread thread = new Thread(() -> {
            try (Context context = newContext(null)) {
                context.eval("python", "True");
            } catch (Throwable error) {
                logError.accept(error);
            }
        }, "GraalPy-Preload");
        thread.setDaemon(true);
        thread.start();
    }
}
