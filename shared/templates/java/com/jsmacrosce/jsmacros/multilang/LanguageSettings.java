package @CORE_PACKAGE@.multilang;

import @CORE_PACKAGE@.core.Core;
import @CORE_PACKAGE@.@PYTHON_CONFIG_PACKAGE@.config.PythonConfig;
import @CORE_PACKAGE@.luaj.config.LuajConfig;

public final class LanguageSettings {
    private final Core<?, ?> runner;

    public LanguageSettings(Core<?, ?> runner) {
        this.runner = runner;
    }

    public LanguageSettings @PYTHON_TOGGLE_METHOD@(boolean enabled) {
        synchronized (runner.config) {
            options(PythonConfig.class).@PYTHON_TOGGLE_FIELD@ = enabled;
            runner.config.saveConfig();
        }
        return this;
    }

    public LanguageSettings luava(boolean enabled) {
        requireLuava(enabled);
        synchronized (runner.config) {
            options(LuajConfig.class).luava = enabled;
            runner.config.saveConfig();
        }
        return this;
    }

    public LanguageSettings luava(boolean enabled, boolean jit) {
        requireLuava(enabled);
        synchronized (runner.config) {
            LuajConfig config = options(LuajConfig.class);
            config.luavaJit = jit;
            config.luava = enabled;
            runner.config.saveConfig();
        }
        return this;
    }

    private static void requireLuava(boolean enabled) {
        if (enabled && Runtime.version().feature() < 21) {
            throw new IllegalStateException("Luava requires Java 21 or newer");
        }
    }

    private <T> T options(Class<T> type) {
        T config = runner.config.getOptions(type);
        if (config == null) throw new IllegalStateException("Language settings are not initialized: " + type.getSimpleName());
        return config;
    }
}
