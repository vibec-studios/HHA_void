package dev.henny.hha_void.client

import dev.henny.hha_void.DashPayload
import dev.henny.hha_void.VoidItems
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.entity.EquipmentSlot

/**
 * Void Step: Doppel-Leertaste (zwei Sprungtasten-Flanken innerhalb von
 * 7 Ticks) sendet den Teleport-Wunsch; der Server validiert Boots + Cooldown.
 */
class Hha_voidClient : ClientModInitializer {

    private var tick = 0L
    private var jumpWasPressed = false
    private var lastJumpPress = Long.MIN_VALUE

    override fun onInitializeClient() {
        VoidTooltips.register()
        dev.henny.hha_void.client.fx.VoidFxClient.init()
        dev.henny.hha_void.client.boss.VoidBossClient.init()
        VoidMusic.init()

        ClientTickEvents.END_CLIENT_TICK.register { client ->
            tick++
            val player = client.player ?: run {
                jumpWasPressed = false
                lastJumpPress = Long.MIN_VALUE
                return@register
            }

            val jumpPressed = client.options.jumpKey.isPressed
            if (jumpPressed && !jumpWasPressed) {
                val doubleTap = tick - lastJumpPress in 2..7
                if (doubleTap &&
                    !player.abilities.flying && !player.abilities.allowFlying &&
                    !player.hasVehicle() && !player.isSpectator &&
                    player.getEquippedStack(EquipmentSlot.FEET).isOf(VoidItems.VOID_BOOTS)
                ) {
                    ClientPlayNetworking.send(DashPayload)
                    lastJumpPress = Long.MIN_VALUE
                } else {
                    lastJumpPress = tick
                }
            }
            jumpWasPressed = jumpPressed
        }
    }
}
