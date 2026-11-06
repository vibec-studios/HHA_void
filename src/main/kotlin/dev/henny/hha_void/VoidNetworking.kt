package dev.henny.hha_void

import dev.henny.hha_void.logic.VoidDash
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.entity.EquipmentSlot
import net.minecraft.network.RegistryByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload

/** C2S-Payload: Spieler hat Void Step ausgelöst (Doppel-Leertaste). Singleton — der Unit-Codec verlangt dieselbe Instanz. */
object DashPayload : CustomPayload {

    val ID = CustomPayload.Id<DashPayload>(VoidItems.id("dash"))
    val CODEC: PacketCodec<RegistryByteBuf, DashPayload> = PacketCodec.unit(this)

    override fun getId(): CustomPayload.Id<out CustomPayload> = ID
}

object VoidNetworking {

    fun init() {
        PayloadTypeRegistry.playC2S().register(DashPayload.ID, DashPayload.CODEC)
        PayloadTypeRegistry.playS2C().register(VoidFxPayload.ID, VoidFxPayload.CODEC)

        ServerPlayNetworking.registerGlobalReceiver(DashPayload.ID) { _, context ->
            val player = context.player()
            if (player.getEquippedStack(EquipmentSlot.FEET).isOf(VoidItems.VOID_BOOTS)) {
                VoidDash.cast(player)
            }
        }
    }
}
