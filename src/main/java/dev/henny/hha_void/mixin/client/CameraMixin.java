package dev.henny.hha_void.mixin.client;

import dev.henny.hha_void.client.fx.VoidCutscene;
import dev.henny.hha_void.client.fx.VoidFxClient;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Kamera-Erschütterung bei schweren Void-Effekten (Einschläge, schwarzes Loch). */
@Mixin(Camera.class)
public abstract class CameraMixin {

    @Shadow
    private float pitch;

    @Shadow
    private float yaw;

    @Shadow
    protected abstract void setRotation(float yaw, float pitch);

    @Shadow
    protected abstract void setPos(double x, double y, double z);

    @Inject(method = "update", at = @At("TAIL"))
    private void hha_void$shake(World area, Entity focusedEntity, boolean thirdPerson, boolean inverseView, float tickProgress, CallbackInfo ci) {
        if (VoidCutscene.isActive()) {
            VoidCutscene.update(tickProgress);
            this.setPos(VoidCutscene.camX(), VoidCutscene.camY(), VoidCutscene.camZ());
            this.setRotation(VoidCutscene.camYaw(), VoidCutscene.camPitch());
        }
        float yawOffset = VoidFxClient.shakeYaw(tickProgress);
        float pitchOffset = VoidFxClient.shakePitch(tickProgress);
        if (yawOffset != 0.0F || pitchOffset != 0.0F) {
            this.setRotation(this.yaw + yawOffset, this.pitch + pitchOffset);
        }
    }
}
