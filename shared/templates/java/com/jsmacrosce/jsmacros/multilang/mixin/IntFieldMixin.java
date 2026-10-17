package @CORE_PACKAGE@.multilang.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "@CORE_PACKAGE@.client.gui.settings.settingfields.IntField", remap = false)
public abstract class IntFieldMixin {
    @Inject(method = "lambda$init$0", at = @At("HEAD"), cancellable = true, require = 1)
    private void multilang$validateInteger(String value, CallbackInfo callback) {
        if (value.isEmpty()) return; // The core treats a cleared field as zero.
        try {
            Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            callback.cancel();
        }
    }
}
