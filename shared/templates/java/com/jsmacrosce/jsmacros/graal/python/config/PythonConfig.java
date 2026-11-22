package @CORE_PACKAGE@.graal.python.config;

import @CORE_PACKAGE@.core.config.Option;

public class PythonConfig {
    @Option(translationKey = "jsmacrospython.usejython",
            group = {"@CORE_SETTINGS_LANGUAGES@", "jsmacrospython.settings.languages.python"})
    public volatile boolean useJython = false;
}
