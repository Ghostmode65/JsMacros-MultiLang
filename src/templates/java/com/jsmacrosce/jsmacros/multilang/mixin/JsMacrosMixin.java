package @CORE_PACKAGE@.multilang.mixin;

import @CORE_PACKAGE@.core.Core;
import @CORE_PACKAGE@.core.language.BaseScriptContext;
import @CORE_PACKAGE@.core.library.PerExecLibrary;
import @CORE_PACKAGE@.core.library.impl.FJsMacros;
import @CORE_PACKAGE@.multilang.LanguageSettings;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(value = FJsMacros.class, remap = false)
public abstract class JsMacrosMixin extends PerExecLibrary {
    protected JsMacrosMixin(BaseScriptContext<?> context) {
        super(context);
    }

    public LanguageSettings getLanguages() {
        return new LanguageSettings(@LANGUAGE_RUNNER@);
    }
}
