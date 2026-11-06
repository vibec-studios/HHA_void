package dev.henny.hha_void.mixin.client;

import dev.henny.hha_void.client.fx.VoidFxClient;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.entity.EntityRenderManager;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Blendet das flache Feuerball-Sprite aus, wenn ein 3D-Meteor an dem Projektil hängt. */
@Mixin(EntityRenderManager.class)
public abstract class EntityRenderManagerMixin {

    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private <E extends Entity> void hha_void$hideMeteorSprite(E entity, Frustum frustum, double x, double y, double z, CallbackInfoReturnable<Boolean> cir) {
        if (VoidFxClient.isHiddenEntity(entity.getId())) {
            cir.setReturnValue(false);
        }
    }
}
