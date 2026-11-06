package dev.henny.hha_void.mixin.client;

import dev.henny.hha_void.client.fx.VoidCutscene;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Während einer Cutscene ersetzen Balken und Einblendungen die normale Anzeige. */
@Mixin(InGameHud.class)
public abstract class InGameHudMixin {

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void hha_void$cutsceneHud(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
        if (VoidCutscene.isActive()) {
            VoidCutscene.renderHud(context, tickCounter.getTickProgress(false));
            ci.cancel();
        }
    }
}
