package dev.henny.hha_void.logic

import dev.henny.hha.config.HhaConfig
import dev.henny.hha.logic.Cooldowns
import dev.henny.hha.logic.Fx
import dev.henny.hha.logic.Targeting
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.entity.EquipmentSlot
import net.minecraft.entity.LivingEntity
import net.minecraft.entity.effect.StatusEffectInstance
import net.minecraft.entity.effect.StatusEffects
import net.minecraft.particle.ParticleTypes
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvents
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Box
import net.minecraft.util.math.Vec3d
import java.util.UUID

/**
 * Set-Bonus (Ultra-Taste): Void Collapse — alle Gegner in großer Reichweite
 * werden langsam und unaufhaltsam zum Aktivierungspunkt gesogen (bewegungs-
 * unfähig, leichter Schaden), dann in die Luft geschleudert und hart zu
 * Boden geschmettert. Der Caster bekommt 30s Stärke und 60s Unsichtbarkeit.
 */
object VoidCollapse {

    private const val PULL_MAX_TICKS = 120
    private const val FLING_TICKS = 12
    private const val WELL_TICKS = PULL_MAX_TICKS + 40

    /** Das Loch schwebt über dem Aktivierungspunkt: der Wurf schleudert die Opfer direkt hinein. */
    private const val HOLE_HEIGHT = 6.0

    private enum class State { PULL, FLING, SLAM }

    private class Victim(
        val world: ServerWorld,
        val entity: LivingEntity,
        val owner: UUID,
        val point: Vec3d,
        var state: State = State.PULL,
        var age: Int = 0,
        var stateAge: Int = 0,
    )

    private class Well(val world: ServerWorld, val point: Vec3d, var remaining: Int = WELL_TICKS)

    private val victims = ArrayList<Victim>()
    private val wells = ArrayList<Well>()

    fun init() {
        ServerTickEvents.END_SERVER_TICK.register { _ ->
            if (wells.isNotEmpty()) {
                val iterator = wells.iterator()
                while (iterator.hasNext()) {
                    val well = iterator.next()
                    tickWell(well)
                    if (--well.remaining <= 0) iterator.remove()
                }
            }
            if (victims.isNotEmpty()) {
                val iterator = victims.iterator()
                while (iterator.hasNext()) {
                    val victim = iterator.next()
                    if (!victim.entity.isAlive || tickVictim(victim)) iterator.remove()
                }
            }
        }
    }

    fun cast(player: ServerPlayerEntity) {
        val world = player.entityWorld as? ServerWorld ?: return
        if (!HhaConfig.enabled("hha_void.collapse")) return
        val helmet = player.getEquippedStack(EquipmentSlot.HEAD)
        if (player.itemCooldownManager.isCoolingDown(helmet)) return

        val range = HhaConfig.num("hha_void.collapse_range")
        val point = player.entityPos
        val targets = world.getEntitiesByClass(
            LivingEntity::class.java,
            Box.of(point, range * 2, range * 2, range * 2)
        ) {
            it.isAlive && it != player &&
                it.squaredDistanceTo(player) <= range * range &&
                Targeting.shouldHarm(player, it)
        }
        if (targets.isEmpty()) {
            player.sendMessage(
                Text.translatable("hha_void.msg.no_targets").formatted(Formatting.RED), true
            )
            return
        }
        Cooldowns.set(player, helmet, "hha_void.collapse_cooldown")

        // Der Void belohnt den Kollaps: 30s Stärke, 60s Unsichtbarkeit
        player.addStatusEffect(StatusEffectInstance(StatusEffects.STRENGTH, 600, 0, false, false, true))
        player.addStatusEffect(StatusEffectInstance(StatusEffects.INVISIBILITY, 1200, 0, false, false, true))

        val holeId = VoidVisuals.newId()
        VoidVisuals.blackHole(world, holeId, point.add(0.0, HOLE_HEIGHT, 0.0), point, WELL_TICKS)
        for (target in targets) {
            victims.removeIf { it.entity === target }
            victims.add(Victim(world, target, player.uuid, point))
            VoidVisuals.holeTether(world, holeId, target.id, point)
        }
        wells.add(Well(world, point))

        world.playSound(
            null, BlockPos.ofFloored(point), SoundEvents.BLOCK_END_PORTAL_SPAWN,
            SoundCategory.PLAYERS, 1.0f, 0.4f
        )
        world.playSound(
            null, BlockPos.ofFloored(point), SoundEvents.ENTITY_WARDEN_SONIC_CHARGE,
            SoundCategory.PLAYERS, 1.0f, 0.5f
        )
    }

    /** Ambient-Sound am Sogpunkt; das Bild kommt vom Client (schwarzes Loch). */
    private fun tickWell(well: Well) {
        val world = well.world
        val point = well.point
        if (well.remaining % 25 == 0) {
            world.playSound(
                null, BlockPos.ofFloored(point), SoundEvents.BLOCK_PORTAL_AMBIENT,
                SoundCategory.PLAYERS, 0.6f, 0.4f
            )
        }
    }

    /** @return true, wenn das Opfer fertig ist. */
    private fun tickVictim(victim: Victim): Boolean {
        val entity = victim.entity
        val world = victim.world
        victim.age++
        victim.stateAge++

        when (victim.state) {
            State.PULL -> {
                val bodyCenter = entity.entityPos.add(0.0, entity.height * 0.5, 0.0)
                val toPoint = victim.point.add(0.0, 1.0, 0.0).subtract(bodyCenter)
                val dist = toPoint.length()

                if (dist < 2.0 || victim.stateAge > PULL_MAX_TICKS) {
                    victim.state = State.FLING
                    victim.stateAge = 0
                    setVelocity(entity, Vec3d(
                        (world.random.nextDouble() - 0.5) * 0.3,
                        1.5,
                        (world.random.nextDouble() - 0.5) * 0.3,
                    ))
                    world.playSound(
                        null, entity.blockPos, SoundEvents.ENTITY_BREEZE_SHOOT,
                        SoundCategory.PLAYERS, 0.9f, 0.6f
                    )
                    return false
                }

                // Langsam, unaufhaltsam: Bewegung wird jeden Tick überschrieben
                val pull = toPoint.multiply(1.0 / dist).multiply(0.22)
                setVelocity(entity, pull)
                entity.fallDistance = 0.0
                entity.addStatusEffect(StatusEffectInstance(StatusEffects.SLOWNESS, 15, 6, false, false, false))
                // Im Sog sieht man nur noch das Nichts
                entity.addStatusEffect(StatusEffectInstance(StatusEffects.BLINDNESS, 60, 0, false, false, false))

                // Leichter Schaden, solange der Sog zerrt
                if (victim.stateAge % 20 == 0) {
                    val owner = world.server?.playerManager?.getPlayer(victim.owner)
                    val source = owner?.let { world.damageSources.playerAttack(it) } ?: world.damageSources.magic()
                    VoidDamageGuard.guarded {
                        entity.damage(world, source, HhaConfig.numF("hha_void.collapse_pull_damage"))
                    }
                }
            }

            State.FLING -> {
                entity.fallDistance = 0.0
                if (victim.stateAge >= FLING_TICKS) {
                    victim.state = State.SLAM
                    victim.stateAge = 0
                    setVelocity(entity, Vec3d(0.0, -3.0, 0.0))
                    entity.fallDistance += HhaConfig.num("hha_void.collapse_bonus_fall")
                }
            }

            State.SLAM -> {
                if (entity.isOnGround || entity.isTouchingWater) {
                    val owner = world.server?.playerManager?.getPlayer(victim.owner)
                    val source = owner?.let { world.damageSources.playerAttack(it) } ?: world.damageSources.magic()
                    VoidDamageGuard.guarded {
                        entity.damage(world, source, HhaConfig.numF("hha_void.collapse_damage"))
                    }
                    entity.addStatusEffect(StatusEffectInstance(StatusEffects.SLOWNESS, 100, 3, false, true, true))
                    entity.addStatusEffect(StatusEffectInstance(StatusEffects.WEAKNESS, 100, 0, false, true, true))
                    entity.addStatusEffect(StatusEffectInstance(StatusEffects.BLINDNESS, 120, 0, false, true, true))

                    VoidVisuals.impact(world, entity.entityPos.add(0.0, 0.1, 0.0), 2.2f)
                    world.spawnParticles(
                        ParticleTypes.CRIT,
                        entity.x, entity.y + 0.3, entity.z,
                        14, 0.5, 0.2, 0.5, 0.12
                    )
                    world.playSound(
                        null, entity.blockPos, SoundEvents.ITEM_MACE_SMASH_GROUND_HEAVY,
                        SoundCategory.PLAYERS, 1.0f, 0.6f
                    )
                    return true
                }
                if (victim.stateAge > 80) return true
            }
        }
        return victim.age > 300
    }

    private fun setVelocity(entity: LivingEntity, velocity: Vec3d) {
        if (entity is ServerPlayerEntity) {
            Fx.launchPlayer(entity, velocity)
        } else {
            entity.velocity = velocity
            entity.velocityDirty = true
        }
    }
}
