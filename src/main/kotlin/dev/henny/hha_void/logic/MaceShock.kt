package dev.henny.hha_void.logic

import dev.henny.hha.config.HhaConfig
import dev.henny.hha.logic.Targeting
import dev.henny.hha_void.VoidItems
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.minecraft.entity.LivingEntity
import net.minecraft.particle.ParticleTypes
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvents
import net.minecraft.util.math.Box
import net.minecraft.util.math.Vec3d
import java.util.UUID

/**
 * Void Mace: Schockwelle — nach Sky Launch ist der nächste Mace-Treffer
 * aufgeladen: sein GESAMTER Schaden wird gleichzeitig auf alle anderen
 * Gegner (Mobs + nicht-getrustete Spieler) im Umkreis übertragen.
 */
object MaceShock {

    /** Spieler-UUID → Server-Tick, bis zu dem der nächste Mace-Hit aufgeladen ist. */
    private val armedUntil = HashMap<UUID, Long>()

    fun arm(player: ServerPlayerEntity) {
        val server = player.entityWorld.server ?: return
        armedUntil[player.uuid] = server.ticks.toLong() + HhaConfig.num("hha_void.mace_shock_window").toLong()
    }

    fun init() {
        ServerLivingEntityEvents.AFTER_DAMAGE.register { entity, source, _, damageTaken, blocked ->
            if (VoidDamageGuard.active || blocked || damageTaken <= 0.5f) return@register
            val attacker = source.attacker as? ServerPlayerEntity ?: return@register
            if (entity === attacker) return@register
            if (!attacker.mainHandStack.isOf(VoidItems.VOID_MACE)) return@register
            if (!HhaConfig.enabled("hha_void.mace_shock")) return@register
            val world = entity.entityWorld as? ServerWorld ?: return@register
            if (!Targeting.shouldHarm(attacker, entity)) return@register

            val server = world.server ?: return@register
            val until = armedUntil[attacker.uuid] ?: return@register
            if (server.ticks.toLong() > until) {
                armedUntil.remove(attacker.uuid)
                return@register
            }
            armedUntil.remove(attacker.uuid)

            shockwave(world, attacker, entity, damageTaken)
        }
    }

    /** Voller Schaden auf alle Gegner im Umkreis — gleichzeitig. */
    private fun shockwave(world: ServerWorld, attacker: ServerPlayerEntity, victim: LivingEntity, damage: Float) {
        val range = HhaConfig.num("hha_void.mace_shock_range")
        val center = victim.entityPos.add(0.0, victim.height * 0.5, 0.0)

        val targets = world.getEntitiesByClass(
            LivingEntity::class.java,
            Box.of(center, range * 2, range * 2, range * 2)
        ) {
            it.isAlive && it != victim && it != attacker &&
                it.entityPos.distanceTo(center) <= range &&
                Targeting.shouldHarm(attacker, it)
        }

        // Impact-Feedback gibt es immer — auch ohne weitere Ziele
        VoidVisuals.shockwave(world, center, victim.entityPos, range)
        world.playSound(
            null, victim.blockPos, SoundEvents.ITEM_MACE_SMASH_GROUND_HEAVY,
            SoundCategory.PLAYERS, 1.0f, 0.6f
        )
        world.playSound(
            null, victim.blockPos, SoundEvents.ENTITY_WARDEN_SONIC_BOOM,
            SoundCategory.PLAYERS, 0.9f, 1.1f
        )

        for (target in targets) {
            val impact = target.entityPos.add(0.0, target.height * 0.5, 0.0)
            VoidVisuals.arc(world, center, center.add(impact).multiply(0.5).add(0.0, 1.2, 0.0), impact, 3, 3)
            VoidDamageGuard.guarded {
                target.damage(world, world.damageSources.playerAttack(attacker), damage)
            }
            val away = target.entityPos.subtract(victim.entityPos)
            val horizontal = away.horizontalLength().coerceAtLeast(0.1)
            target.velocity = Vec3d(away.x / horizontal * 0.6, 0.35, away.z / horizontal * 0.6)
            target.velocityDirty = true

            world.spawnParticles(ParticleTypes.CRIT, impact.x, impact.y, impact.z, 10, 0.3, 0.3, 0.3, 0.12)
            VoidVisuals.impact(world, impact, 1.3f)
        }
    }
}
