package dev.henny.hha_void.logic

import dev.henny.hha.config.HhaConfig
import dev.henny.hha.logic.Cooldowns
import dev.henny.hha.logic.Targeting
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.entity.EquipmentSlot
import net.minecraft.entity.LivingEntity
import net.minecraft.entity.projectile.FireballEntity
import net.minecraft.entity.projectile.ProjectileEntity
import net.minecraft.entity.projectile.SmallFireballEntity
import net.minecraft.particle.ParticleTypes
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvents
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.math.Box

/**
 * Void-Brustplatte: Meteor Strike — zielsuchende Meteore stürzen auf alle
 * Gegner in Reichweite. Jeder dritte Meteor ist ein großer Brocken, der beim
 * Einschlag explodiert. Die Meteore korrigieren ihre Flugbahn jeden Tick und
 * verfolgen ihr Ziel. Finale: der Void-Komet auf das zäheste Ziel.
 */
object MeteorStrike {

    private class Homing(
        val world: ServerWorld,
        val meteor: ProjectileEntity,
        val target: LivingEntity,
        val big: Boolean,
        var life: Int = 160,
    )

    private const val COMET_FALL_TICKS = 18

    private val homing = ArrayList<Homing>()

    fun init() {
        ServerTickEvents.END_SERVER_TICK.register { _ ->
            if (homing.isEmpty()) return@register
            val iterator = homing.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.meteor.isRemoved || !entry.target.isAlive || --entry.life <= 0) {
                    iterator.remove()
                    continue
                }
                steer(entry)
            }
        }
    }

    /** Flugbahn-Korrektur: der Meteor giert jeden Tick auf sein Ziel zu. */
    private fun steer(entry: Homing) {
        val meteor = entry.meteor
        val aim = entry.target.entityPos.add(0.0, entry.target.height * 0.5, 0.0)
        val desired = aim.subtract(meteor.entityPos).normalize()
        val speed = HhaConfig.num("hha_void.meteor_speed")
        val velocity = meteor.velocity.multiply(0.72).add(desired.multiply(0.5)).normalize().multiply(speed)
        meteor.velocity = velocity
        meteor.velocityDirty = true
    }

    fun cast(player: ServerPlayerEntity) {
        val world = player.entityWorld as? ServerWorld ?: return
        if (!HhaConfig.enabled("hha_void.meteor")) return
        val chest = player.getEquippedStack(EquipmentSlot.CHEST)
        if (player.itemCooldownManager.isCoolingDown(chest)) return

        val range = HhaConfig.num("hha_void.meteor_range")
        val box = Box.of(player.entityPos, range * 2, range, range * 2)
        val targets = world.getEntitiesByClass(LivingEntity::class.java, box) {
            it.isAlive && it != player &&
                it.squaredDistanceTo(player) <= range * range &&
                Targeting.shouldHarm(player, it)
        }.sortedBy { it.squaredDistanceTo(player) }

        if (targets.isEmpty()) {
            player.sendMessage(
                Text.translatable("hha_void.msg.no_targets").formatted(Formatting.RED), true
            )
            return
        }
        Cooldowns.set(player, chest, "hha_void.meteor_cooldown")

        world.playSound(null, player.blockPos, SoundEvents.ENTITY_GHAST_WARN, SoundCategory.PLAYERS, 1.0f, 0.6f)
        world.playSound(null, player.blockPos, SoundEvents.BLOCK_END_PORTAL_SPAWN, SoundCategory.PLAYERS, 0.5f, 1.7f)
        VoidFx.burst(world, player.entityPos.add(0.0, 1.0, 0.0))

        val count = HhaConfig.num("hha_void.meteor_count").toInt().coerceIn(1, 32)
        for (i in 0 until count) {
            val target = targets[i % targets.size]
            val big = i % 3 == 0
            TickScheduler.schedule(4 + i * 3) { launchMeteor(world, player, target, big) }
        }

        // Finale: Void-Komet auf das Ziel mit den meisten HP
        val cometTarget = targets.maxByOrNull { it.health }
        if (cometTarget != null) {
            TickScheduler.schedule(8 + count * 3) { announceComet(world, player, cometTarget) }
        }
    }

    private fun launchMeteor(world: ServerWorld, player: ServerPlayerEntity, target: LivingEntity, big: Boolean) {
        if (!target.isAlive || !player.isAlive) return

        val aim = target.entityPos.add(0.0, target.height * 0.5, 0.0)
        val angle = world.random.nextDouble() * Math.PI * 2.0
        val spawn = aim.add(
            Math.cos(angle) * (4.0 + world.random.nextDouble() * 4.0),
            12.0 + world.random.nextDouble() * 5.0,
            Math.sin(angle) * (4.0 + world.random.nextDouble() * 4.0),
        )
        val velocity = aim.subtract(spawn).normalize().multiply(HhaConfig.num("hha_void.meteor_speed"))

        val meteor: ProjectileEntity = if (big) {
            // Großer Brocken: explodiert beim Einschlag
            FireballEntity(world, player, velocity, HhaConfig.num("hha_void.meteor_explosion_power").toInt().coerceIn(0, 3))
        } else {
            SmallFireballEntity(world, player, velocity)
        }
        meteor.setPosition(spawn.x, spawn.y, spawn.z)
        world.spawnEntity(meteor)
        homing.add(Homing(world, meteor, target, big))

        VoidVisuals.meteor(world, meteor.id, spawn, big)
        VoidVisuals.portal(world, spawn, velocity, if (big) 1.8f else 1.1f)
        world.playSound(
            null, target.blockPos, SoundEvents.ENTITY_GHAST_SHOOT,
            SoundCategory.PLAYERS, 0.8f, if (big) 0.8f else 1.4f
        )
    }

    /** Komet ankündigen: Void-Säule über dem Ziel, dann Einschlag. */
    private fun announceComet(world: ServerWorld, player: ServerPlayerEntity, target: LivingEntity) {
        if (!target.isAlive || !player.isAlive) return
        val ground = target.entityPos

        VoidVisuals.comet(world, ground, COMET_FALL_TICKS)
        world.playSound(
            null, target.blockPos, SoundEvents.ENTITY_WARDEN_SONIC_CHARGE,
            SoundCategory.PLAYERS, 1.0f, 1.3f
        )

        TickScheduler.schedule(COMET_FALL_TICKS) { cometImpact(world, player, ground) }
    }

    private fun cometImpact(world: ServerWorld, player: ServerPlayerEntity, center: net.minecraft.util.math.Vec3d) {
        val damage = HhaConfig.numF("hha_void.meteor_comet_damage")
        val box = Box.of(center, 9.0, 6.0, 9.0)
        for (victim in world.getEntitiesByClass(LivingEntity::class.java, box, {
            it.isAlive && it != player && it.entityPos.distanceTo(center) <= 4.5 && Targeting.shouldHarm(player, it)
        })) {
            VoidDamageGuard.guarded {
                victim.damage(world, world.damageSources.playerAttack(player), damage)
            }
            victim.setOnFireFor(4.0f)
            val away = victim.entityPos.subtract(center)
            val horizontal = away.horizontalLength().coerceAtLeast(0.1)
            victim.velocity = net.minecraft.util.math.Vec3d(
                away.x / horizontal * 0.9, 0.85, away.z / horizontal * 0.9
            )
            victim.velocityDirty = true
        }

        VoidVisuals.cometImpact(world, center, 4.5)
        world.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, center.x, center.y + 0.5, center.z, 1, 0.0, 0.0, 0.0, 0.0)
        world.playSound(
            null, net.minecraft.util.math.BlockPos.ofFloored(center),
            SoundEvents.ENTITY_GENERIC_EXPLODE.value(), SoundCategory.PLAYERS, 1.0f, 0.5f
        )
        world.playSound(
            null, net.minecraft.util.math.BlockPos.ofFloored(center),
            SoundEvents.ENTITY_WARDEN_SONIC_BOOM, SoundCategory.PLAYERS, 1.0f, 0.8f
        )
    }
}
