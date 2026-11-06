package dev.henny.hha_void.logic

import dev.henny.hha.config.HhaConfig
import dev.henny.hha.logic.Cooldowns
import net.minecraft.entity.EquipmentSlot
import net.minecraft.entity.LivingEntity
import net.minecraft.particle.ParticleTypes
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvents
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.math.Box
import net.minecraft.util.math.Vec3d

/**
 * Void-Leggings: Void Pounce — teleportiert dich über das Wesen mit den
 * WENIGSTEN Leben im Umkreis und gibt dir einen kleinen Schub nach oben.
 * Perfekt, um mit der Mace von oben einzuschlagen.
 */
object VoidPounce {

    fun cast(player: ServerPlayerEntity) {
        val world = player.entityWorld as? ServerWorld ?: return
        if (!HhaConfig.enabled("hha_void.pounce")) return
        val leggings = player.getEquippedStack(EquipmentSlot.LEGS)
        if (player.itemCooldownManager.isCoolingDown(leggings)) return

        val radius = HhaConfig.num("hha_void.pounce_radius")
        val candidates = world.getEntitiesByClass(
            LivingEntity::class.java,
            Box.of(player.entityPos, radius * 2, radius * 2, radius * 2)
        ) {
            it.isAlive && it != player && !it.isSpectator &&
                it.squaredDistanceTo(player) <= radius * radius
        }
        if (candidates.isEmpty()) {
            player.sendMessage(
                Text.translatable("hha_void.msg.no_targets").formatted(Formatting.RED), true
            )
            return
        }

        // Das schwächste Ziel — der Void riecht Blut
        val target = candidates.minByOrNull { it.health } ?: return
        val height = HhaConfig.num("hha_void.pounce_height")

        // Freie Position über dem Wesen suchen (notfalls etwas niedriger)
        var destination: Vec3d? = null
        var h = height
        while (h >= 2.0) {
            val pos = target.entityPos.add(0.0, target.height + h, 0.0)
            val delta = pos.subtract(player.entityPos)
            if (world.isSpaceEmpty(player, player.boundingBox.offset(delta))) {
                destination = pos
                break
            }
            h -= 1.0
        }
        if (destination == null) {
            player.sendMessage(
                Text.translatable("hha_void.msg.no_targets").formatted(Formatting.RED), true
            )
            return
        }

        Cooldowns.set(player, leggings, "hha_void.pounce_cooldown")

        val from = player.entityPos
        player.requestTeleport(destination.x, destination.y, destination.z)
        player.fallDistance = 0.0

        // Kleiner Schub nach oben — Zeit zum Zielen, mehr Smash-Fallhöhe
        dev.henny.hha.logic.Fx.launchPlayer(player, Vec3d(0.0, 0.55, 0.0))

        // Aufs Ziel schauen — bereit zum Einschlag
        player.lookAt(
            net.minecraft.command.argument.EntityAnchorArgumentType.EntityAnchor.EYES,
            target.entityPos.add(0.0, target.height * 0.5, 0.0)
        )

        VoidDash.portal(world, from.add(0.0, 1.0, 0.0), Vec3d(0.0, 1.0, 0.0))
        VoidDash.portal(world, destination.add(0.0, 1.0, 0.0), Vec3d(0.0, -1.0, 0.0))
        VoidFx.trail(world, from.add(0.0, 1.0, 0.0), destination)
        world.spawnParticles(
            ParticleTypes.REVERSE_PORTAL,
            target.x, target.y + target.height + 0.5, target.z,
            10, 0.3, 0.3, 0.3, 0.05
        )
        world.playSound(
            null, player.blockPos, SoundEvents.ENTITY_ENDERMAN_TELEPORT,
            SoundCategory.PLAYERS, 0.9f, 0.8f
        )
        world.playSound(
            null, target.blockPos, SoundEvents.ENTITY_PHANTOM_SWOOP,
            SoundCategory.PLAYERS, 0.9f, 0.9f
        )
    }
}
