package dev.henny.hha_void.mixin.client;

import dev.henny.hha_void.client.fx.VoidCutscene;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Während einer Cutscene wird nicht geschlagen, benutzt oder ein Menü geöffnet. */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {

    @Inject(method = "handleInputEvents", at = @At("HEAD"), cancellable = true)
    private void hha_void$blockInput(CallbackInfo ci) {
        if (VoidCutscene.isActive()) ci.cancel();
    }
}
