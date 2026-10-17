package org.jsmacros.multilang.shared;

import java.util.function.Supplier;

public final class ClassLoaderContext {
    private ClassLoaderContext() {
    }

    public static <T> T call(ClassLoader loader, Supplier<T> action) {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);
        try {
            return action.get();
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    public static void run(ClassLoader loader, Runnable action) {
        call(loader, () -> {
            action.run();
            return null;
        });
    }
}
