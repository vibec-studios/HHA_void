package dev.henny.hha_void.item

import dev.henny.hha.config.HhaConfig
import dev.henny.hha.logic.Cooldowns
import dev.henny.hha.logic.Fx
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.item.MaceItem
import net.minecraft.particle.ParticleTypes
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvents
import net.minecraft.util.ActionResult
import net.minecraft.util.Hand
import net.minecraft.util.math.Vec3d
import net.minecraft.world.World

/**
 * Void Mace — echte Vanilla-Mace (Smash-Attack bleibt erhalten).
 * Aktiv (Rechtsklick): Sky Launch — die Mace reißt dich wie Mjölnir in den
 * Himmel; danach den Smash-Attack aus der Höhe setzen.
 */
class VoidMaceItem(settings: Settings) : MaceItem(settings) {

    override fun use(world: World, user: PlayerEntity, hand: Hand): ActionResult {
        val stack = user.getStackInHand(hand)
        if (user.itemCooldownManager.isCoolingDown(stack)) return ActionResult.PASS
        if (!HhaConfig.enabled("hha_void.mace_launch")) return ActionResult.PASS
        if (world !is ServerWorld || user !is ServerPlayerEntity) return ActionResult.SUCCESS

        Cooldowns.set(user, stack, "hha_void.mace_launch_cooldown")

        // Der nächste Mace-Treffer entlädt seinen vollen Schaden als Schockwelle
        dev.henny.hha_void.logic.MaceShock.arm(user)

        // Mjölnir-Moment: steil nach oben, ein Hauch nach vorne
        val power = HhaConfig.num("hha_void.mace_launch_power")
        val look = user.getRotationVec(1.0f)
        Fx.launchPlayer(
            user,
            Vec3d(look.x * 0.55, power, look.z * 0.55)
        )
        user.fallDistance = 0.0

        val feet = user.entityPos.add(0.0, 0.1, 0.0)
        dev.henny.hha_void.logic.VoidVisuals.vortex(world, feet)
        Fx.spiral(world, user.entityPos, 0.8, 2.5, ParticleTypes.END_ROD, 12)
        world.playSound(
            null, user.blockPos, SoundEvents.ENTITY_WIND_CHARGE_WIND_BURST.value(),
            SoundCategory.PLAYERS, 1.0f, 0.8f
        )
        world.playSound(
            null, user.blockPos, SoundEvents.ENTITY_BREEZE_SHOOT,
            SoundCategory.PLAYERS, 1.0f, 0.6f
        )
        world.playSound(
            null, user.blockPos, SoundEvents.ITEM_TRIDENT_RIPTIDE_3.value(),
            SoundCategory.PLAYERS, 0.7f, 1.2f
        )
        return ActionResult.SUCCESS
    }
}
