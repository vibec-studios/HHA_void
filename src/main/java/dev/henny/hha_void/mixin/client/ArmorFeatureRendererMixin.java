package dev.henny.hha_void.mixin.client;

import dev.henny.hha_void.client.VoidInvisRender;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.feature.ArmorFeatureRenderer;
import net.minecraft.client.render.entity.state.BipedEntityRenderState;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Full-Invis (Set-Passiv): Unsichtbare Träger des vollen Void-Sets bekommen
 * ihre Rüstung gar nicht erst gerendert — echte Komplett-Unsichtbarkeit,
 * obwohl die Rüstung angelegt bleibt.
 */
@Mixin(ArmorFeatureRenderer.class)
public abstract class ArmorFeatureRendererMixin {

    @Inject(
        method = "render(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;ILnet/minecraft/client/render/entity/state/BipedEntityRenderState;FF)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void hha_void$hideFullInvisArmor(
        MatrixStack matrices,
        OrderedRenderCommandQueue queue,
        int light,
        BipedEntityRenderState state,
        float limbAngle,
        float limbDistance,
        CallbackInfo ci
    ) {
        if (VoidInvisRender.shouldHideArmor(state)) {
            ci.cancel();
        }
    }
}
