package xyz.wagyourtail.jsmacros.jython.config;

import xyz.wagyourtail.jsmacros.core.config.Option;

public class PythonConfig {
    @Option(translationKey = "jsmacrospython.usegraalpy",
            group = {"jsmacros.settings.languages", "jsmacrospython.settings.languages.python"})
    public volatile boolean useGraalPy = false;
}
