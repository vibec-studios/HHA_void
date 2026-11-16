package dev.henny.hha_void.mixin.client;

import dev.henny.hha_void.client.boss.LeviathanClient;
import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Wer auf dem Leviathan steht, wird nach der eigenen Bewegung und vor dem Bewegungspaket auf die Rückenfläche gesetzt –
 * so meldet der Client dem Server „auf dem Boden" (kein Fallschaden, kein Schweben) an der richtigen Stelle.
 */
@Mixin(ClientPlayerEntity.class)
public abstract class ClientPlayerEntityMixin {
    @Inject(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/network/ClientPlayerEntity;sendMovementPackets()V"))
    private void hha_void$standOnLeviathan(CallbackInfo ci) {
        LeviathanClient.beforeMovementPacket((ClientPlayerEntity) (Object) this);
    }
}
