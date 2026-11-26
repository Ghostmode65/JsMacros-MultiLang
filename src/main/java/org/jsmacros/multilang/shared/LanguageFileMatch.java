package org.jsmacros.multilang.shared;

import java.io.File;
import java.util.Locale;

public final class LanguageFileMatch {
    public enum Result { NONE, MATCH, NAMED }

    private LanguageFileMatch() {
    }

    public static Result match(File file, String extension, String preferredName,
                               boolean lowercaseBeforeNameMatch) {
        String name = file.getName();
        if (!name.endsWith("." + extension)) {
            return Result.NONE;
        }
        String comparison = lowercaseBeforeNameMatch ? name.toLowerCase(Locale.ROOT) : name;
        return comparison.contains(preferredName) ? Result.NAMED : Result.MATCH;
    }
}
