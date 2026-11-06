package dev.henny.hha_void.mixin.client;

import dev.henny.hha_void.client.fx.VoidCutscene;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Die Maus dreht den Spieler während einer Cutscene nicht. */
@Mixin(Entity.class)
public abstract class EntityLookMixin {

    @Inject(method = "changeLookDirection", at = @At("HEAD"), cancellable = true)
    private void hha_void$freezeLook(double cursorDeltaX, double cursorDeltaY, CallbackInfo ci) {
        if (VoidCutscene.isActive()) ci.cancel();
    }
}
