package com.jsmacrosce.jsmacros.graal;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.Map;

public class GraalConfig {

    public Map<String, String> extraEngineOptions = new HashMap<>();

    public Map<String, Map<String, String>> extraLangOptions = new HashMap<>();

    public Map<String, String> getJsOptions() {
        return languageOptions("js");
    }

    public Map<String, String> getPythonOptions() {
        return languageOptions("python");
    }

    private Map<String, String> languageOptions(String language) {
        if (extraLangOptions == null) {
            extraLangOptions = new HashMap<>();
        }
        return extraLangOptions.computeIfAbsent(language, ignored -> new HashMap<>());
    }

    @Deprecated
    public void fromV1(JsonObject v1) {
        JsonObject obj = v1.getAsJsonObject("extraJsOptions");
        if (obj == null) {
            return;
        }

        mergeJsOptions(obj);
        v1.remove("extraJsOptions");
    }

    @Deprecated
    public void fromV2(JsonObject v2) {
        JsonObject v1 = v2.getAsJsonObject("js");
        if (v1 != null && v1.isJsonObject()) {
            JsonObject options = v1.getAsJsonObject("extraGraalOptions");
            languageOptions("js");
            if (options != null) {
                mergeJsOptions(options);

                v1.remove("extraGraalOptions");
            }
        }
        v2.remove("js");
    }

    @Deprecated
    public void fromV3(JsonObject v3) {
        JsonObject graalConfig = v3.getAsJsonObject("graal");
        if (graalConfig != null && graalConfig.isJsonObject()) {
            JsonObject options = graalConfig.getAsJsonObject("extraGraalOptions");
            languageOptions("js");
            if (options != null) {
                mergeJsOptions(options);

                graalConfig.remove("extraGraalOptions");
            }
        }
    }

    private void mergeJsOptions(JsonObject options) {
        Map<String, String> jsOptions = languageOptions("js");
        for (Map.Entry<String, JsonElement> entry : options.entrySet()) {
            jsOptions.put(entry.getKey(), entry.getValue().getAsString());
        }
    }
}
