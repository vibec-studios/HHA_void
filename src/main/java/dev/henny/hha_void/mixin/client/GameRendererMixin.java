package dev.henny.hha_void.mixin.client;

import dev.henny.hha_void.client.fx.VoidCutscene;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Cutscenes: Brennweite der Filmkamera, kein Blockrahmen im Bild. */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    @Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
    private void hha_void$cutsceneFov(Camera camera, float tickProgress, boolean changingFov, CallbackInfoReturnable<Float> cir) {
        if (VoidCutscene.isActive()) cir.setReturnValue(cir.getReturnValueF() * VoidCutscene.fovFactor());
    }

    @Inject(method = "shouldRenderBlockOutline", at = @At("HEAD"), cancellable = true)
    private void hha_void$noOutline(CallbackInfoReturnable<Boolean> cir) {
        if (VoidCutscene.isActive()) cir.setReturnValue(false);
    }
}
