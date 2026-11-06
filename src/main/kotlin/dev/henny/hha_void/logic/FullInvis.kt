package dev.henny.hha_void.logic

import com.mojang.datafixers.util.Pair
import dev.henny.hha.config.HhaConfig
import dev.henny.hha_void.VoidAddon
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.PlayerLookup
import net.minecraft.entity.EquipmentSlot
import net.minecraft.entity.effect.StatusEffects
import net.minecraft.item.ItemStack
import net.minecraft.network.packet.s2c.play.EntityEquipmentUpdateS2CPacket
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import java.util.UUID

/**
 * Set-Passiv: Full-Invis — sobald der Träger des vollen Sets Unsichtbarkeit
 * hat (egal woher: Trank, Nacht-Passiv, Void Collapse, Notriss), verschwindet
 * auch die Rüstung: andere Clients bekommen leere Equipment-Pakete. Die
 * Rüstung bleibt dabei natürlich angelegt.
 */
object FullInvis {

    private val hidden = HashSet<UUID>()

    fun init() {
        ServerTickEvents.END_SERVER_TICK.register { server: MinecraftServer ->
            for (player in server.playerManager.playerList) {
                val shouldHide = player.isAlive &&
                    HhaConfig.enabled("hha_void.full_invis") &&
                    VoidAddon.SET.hasFullSet(player) &&
                    player.hasStatusEffect(StatusEffects.INVISIBILITY)

                if (shouldHide) {
                    // Regelmäßig neu senden, falls Vanilla zwischendurch resynct hat
                    if (hidden.add(player.uuid) || server.ticks % 10 == 0) hide(player)
                } else if (hidden.remove(player.uuid)) {
                    reveal(player)
                }
            }
        }
    }

    private fun hide(player: ServerPlayerEntity) {
        sendEquipment(player) { ItemStack.EMPTY }
    }

    private fun reveal(player: ServerPlayerEntity) {
        sendEquipment(player) { slot -> player.getEquippedStack(slot) }
    }

    private fun sendEquipment(player: ServerPlayerEntity, stack: (EquipmentSlot) -> ItemStack) {
        val equipment = EquipmentSlot.entries.map { slot -> Pair.of(slot, stack(slot).copy()) }
        val packet = EntityEquipmentUpdateS2CPacket(player.id, equipment)
        for (observer in PlayerLookup.tracking(player)) {
            observer.networkHandler.sendPacket(packet)
        }
    }
}
