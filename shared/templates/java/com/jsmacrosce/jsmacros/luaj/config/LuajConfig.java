package @CORE_PACKAGE@.luaj.config;
import @CORE_PACKAGE@.core.config.Option;

public class LuajConfig {
    @Option(translationKey = "jsmacroslua.luava",
            group = {"@CORE_SETTINGS_LANGUAGES@", "jsmacroslua.settings.languages.lua"})
    public boolean luava = false;

    @Option(translationKey = "jsmacroslua.luavajit",
            group = {"@CORE_SETTINGS_LANGUAGES@", "jsmacroslua.settings.languages.lua"})
    public volatile boolean luavaJit = false;

    @Option(translationKey = "jsmacroslua.globalcontext",
            group = {"@CORE_SETTINGS_LANGUAGES@", "jsmacroslua.settings.languages.lua"})
    public boolean useGlobalContext = false;

    @Option(translationKey = "jsmacroslua.splitglobalcontext",
            group = {"@CORE_SETTINGS_LANGUAGES@", "jsmacroslua.settings.languages.lua"})
    public boolean splitGlobalContext = false;
}
