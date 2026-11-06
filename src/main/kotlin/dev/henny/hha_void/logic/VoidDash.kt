package dev.henny.hha_void.logic

import dev.henny.hha.config.HhaConfig
import dev.henny.hha.logic.Cooldowns
import dev.henny.hha.logic.Fx
import net.minecraft.entity.EquipmentSlot
import net.minecraft.particle.ParticleTypes
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvents
import net.minecraft.util.math.Vec3d
import kotlin.math.abs

/**
 * Void-Boots: Void Step — Doppel-Leertaste teleportiert dich in
 * Blickrichtung (durch Wände, egal wo sie stehen) und gibt dir einen Schub
 * nach vorne. Nach unten geht es nie: Blickst du abwärts, bleibt der
 * Teleport auf Starthöhe. An Ein- und Austritt öffnet sich ein Void-Portal.
 */
object VoidDash {

    fun cast(player: ServerPlayerEntity) {
        val world = player.entityWorld as? ServerWorld ?: return
        if (!HhaConfig.enabled("hha_void.dash")) return
        val boots = player.getEquippedStack(EquipmentSlot.FEET)
        if (player.itemCooldownManager.isCoolingDown(boots)) return

        val range = HhaConfig.num("hha_void.dash_range").coerceIn(2.0, 24.0)

        // Nie unter Starthöhe: abwärts gerichteter Blick wird eingeebnet
        var direction = player.getRotationVec(1.0f).normalize()
        if (direction.y < 0) {
            direction = Vec3d(direction.x, 0.0, direction.z)
            if (direction.lengthSquared() < 0.01) return
            direction = direction.normalize()
        }

        val from = player.entityPos

        // Weiteste freie Position entlang der Linie — Wände dazwischen egal
        var d = range
        var destination: Vec3d? = null
        while (d >= 1.0) {
            val delta = direction.multiply(d)
            if (world.isSpaceEmpty(player, player.boundingBox.offset(delta))) {
                destination = from.add(delta)
                break
            }
            d -= 0.25
        }
        val target = destination ?: return

        Cooldowns.set(player, boots, "hha_void.dash_cooldown")

        player.requestTeleport(target.x, target.y, target.z)
        player.fallDistance = 0.0

        // Schub nach vorne, damit es sich wie ein Dash anfühlt
        val boost = HhaConfig.num("hha_void.dash_boost")
        Fx.launchPlayer(
            player,
            Vec3d(
                direction.x * boost,
                (direction.y * boost * 0.4).coerceIn(0.0, 0.35) + 0.08,
                direction.z * boost,
            )
        )

        VoidVisuals.streak(world, from.add(0.0, 1.0, 0.0), target.add(0.0, 1.0, 0.0))
        portal(world, from.add(0.0, 1.0, 0.0), direction)
        portal(world, target.add(0.0, 1.0, 0.0), direction)
        world.playSound(
            null, player.blockPos, SoundEvents.ENTITY_ENDERMAN_TELEPORT,
            SoundCategory.PLAYERS, 0.9f, 1.5f
        )
        world.playSound(
            null, player.blockPos, SoundEvents.BLOCK_PORTAL_TRAVEL,
            SoundCategory.PLAYERS, 0.25f, 1.8f
        )
    }

    /** Rundes Void-Portal, aufgespannt quer zur Bewegungsrichtung. */
    fun portal(world: ServerWorld, center: Vec3d, direction: Vec3d) {
        val ref = if (abs(direction.y) > 0.98) Vec3d(1.0, 0.0, 0.0) else Vec3d(0.0, 1.0, 0.0)
        val right = direction.crossProduct(ref).normalize()
        val up = right.crossProduct(direction).normalize()

        VoidVisuals.portal(world, center, direction)
        Fx.orientedRing(world, center, right, up, 1.3, ParticleTypes.END_ROD, 8)
    }
}
