package dev.henny.hha_void.logic

import dev.henny.hha.config.HhaConfig
import dev.henny.hha.logic.Targeting
import dev.henny.hha_void.VoidAddon
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.minecraft.entity.LivingEntity
import net.minecraft.particle.ParticleTypes
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvents
import net.minecraft.util.math.Box
import net.minecraft.util.math.Vec3d
import kotlin.math.abs

/**
 * Set-Bonus: Void Link — dein Schaden hallt IMMER durch das Nichts. Bei
 * jedem Treffer würfelt der Void die Stärke: 1/3 (häufig), 2/3 (seltener)
 * oder der volle Schaden (selten) — ablesbar an der Partikelbahn.
 *
 * Die Bahn fliegt als EIN geschwungener Void-Strom vom Opfer durch alle
 * verlinkten Gegner nacheinander hindurch (Bezier-Kurven mit Spiral-Drall)
 * und entlädt den Schaden bei jedem Durchflug.
 */
object VoidLink {

    private const val LEG_TICKS = 4

    fun init() {
        ServerLivingEntityEvents.AFTER_DAMAGE.register { entity, source, _, damageTaken, blocked ->
            if (VoidDamageGuard.active || blocked || damageTaken <= 0.5f) return@register
            val attacker = source.attacker as? ServerPlayerEntity ?: return@register
            if (entity === attacker) return@register
            val world = entity.entityWorld as? ServerWorld ?: return@register
            if (!HhaConfig.enabled("hha_void.void_link")) return@register
            if (!VoidAddon.SET.hasFullSet(attacker)) return@register
            if (!Targeting.shouldHarm(attacker, entity)) return@register

            spread(world, attacker, entity, damageTaken)
        }
    }

    /** Würfelt die Link-Stufe: 1, 2 oder 3 Drittel des Schadens. */
    private fun rollTier(world: ServerWorld): Int {
        val chanceFull = HhaConfig.num("hha_void.link_chance_full").coerceIn(0.0, 1.0)
        val chanceTwo = HhaConfig.num("hha_void.link_chance_two").coerceIn(0.0, 1.0)
        val roll = world.random.nextDouble()
        return when {
            roll < chanceFull -> 3
            roll < chanceFull + chanceTwo -> 2
            else -> 1
        }
    }

    private fun spread(world: ServerWorld, attacker: ServerPlayerEntity, victim: LivingEntity, damage: Float) {
        val range = HhaConfig.num("hha_void.link_range")
        val maxTargets = HhaConfig.num("hha_void.link_max_targets").toInt().coerceIn(1, 8)

        val tier = rollTier(world)
        val linkDamage = damage * tier / 3.0f
        if (linkDamage < 0.5f) return

        val linked = world.getEntitiesByClass(
            LivingEntity::class.java,
            Box.of(victim.entityPos, range * 2, range * 2, range * 2)
        ) {
            it.isAlive && it != victim && it != attacker &&
                it.squaredDistanceTo(victim) <= range * range &&
                Targeting.shouldHarm(attacker, it)
        }.sortedBy { it.squaredDistanceTo(victim) }.take(maxTargets)

        if (linked.isEmpty()) return

        val origin = victim.entityPos.add(0.0, victim.height * 0.6, 0.0)
        world.playSound(
            null, victim.blockPos, SoundEvents.BLOCK_AMETHYST_BLOCK_RESONATE,
            SoundCategory.PLAYERS, 0.9f, 0.4f + tier * 0.25f
        )
        if (tier == 3) {
            world.spawnParticles(ParticleTypes.SONIC_BOOM, origin.x, origin.y, origin.z, 1, 0.0, 0.0, 0.0, 0.0)
            world.playSound(
                null, victim.blockPos, SoundEvents.ENTITY_WARDEN_SONIC_BOOM,
                SoundCategory.PLAYERS, 0.6f, 1.5f
            )
        }

        flyLeg(world, attacker, origin, linked, 0, linkDamage, tier)
    }

    /**
     * Ein Kettenglied: geschwungene Bezier-Bahn zum nächsten Ziel, dort
     * einschlagen und weiterfliegen zum übernächsten.
     */
    private fun flyLeg(
        world: ServerWorld,
        attacker: ServerPlayerEntity,
        from: Vec3d,
        targets: List<LivingEntity>,
        index: Int,
        linkDamage: Float,
        tier: Int,
    ) {
        if (index >= targets.size) return
        val target = targets[index]
        if (!target.isAlive) {
            flyLeg(world, attacker, from, targets, index + 1, linkDamage, tier)
            return
        }

        val to = target.entityPos.add(0.0, target.height * 0.6, 0.0)
        val control = curveControl(world, from, to)

        // Die Bahn fliegt über mehrere Ticks ab; der Client zeichnet sie als Energiebogen
        VoidVisuals.arc(world, from, control, to, tier, LEG_TICKS)

        TickScheduler.schedule(LEG_TICKS + 1) {
            if (!attacker.isAlive) return@schedule
            val impact = target.entityPos.add(0.0, target.height * 0.6, 0.0)
            if (target.isAlive) {
                VoidDamageGuard.guarded {
                    target.damage(world, world.damageSources.playerAttack(attacker), linkDamage)
                }
                impactFx(world, impact, tier)
            }
            // Weiter zum nächsten Glied der Kette — die Bahn fliegt durch alle durch
            flyLeg(world, attacker, impact, targets, index + 1, linkDamage, tier)
        }
    }

    /** Kontrollpunkt: seitlich + nach oben ausgelenkt — daher die Kurve. */
    private fun curveControl(world: ServerWorld, from: Vec3d, to: Vec3d): Vec3d {
        val delta = to.subtract(from)
        val length = delta.length().coerceAtLeast(0.5)
        val up = if (abs(delta.normalize().y) > 0.9) Vec3d(1.0, 0.0, 0.0) else Vec3d(0.0, 1.0, 0.0)
        val side = delta.crossProduct(up).normalize()
            .multiply((world.random.nextDouble() * 0.9 + 0.35) * length * if (world.random.nextBoolean()) 1.0 else -1.0)
        val lift = (world.random.nextDouble() * 0.5 + 0.25) * length
        return from.add(delta.multiply(0.5)).add(side).add(0.0, lift, 0.0)
    }

    private fun impactFx(world: ServerWorld, impact: Vec3d, tier: Int) {
        VoidVisuals.impact(world, impact, 0.8f + tier * 0.45f)
        if (tier >= 2) {
            world.spawnParticles(ParticleTypes.END_ROD, impact.x, impact.y, impact.z, tier * 4, 0.3, 0.4, 0.3, 0.08)
        }
        world.playSound(
            null, net.minecraft.util.math.BlockPos.ofFloored(impact), SoundEvents.BLOCK_AMETHYST_CLUSTER_BREAK,
            SoundCategory.PLAYERS, 0.7f + tier * 0.1f, 0.8f - tier * 0.15f
        )
    }
}
