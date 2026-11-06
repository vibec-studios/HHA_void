package dev.henny.hha_void.logic

import dev.henny.hha.config.HhaConfig
import dev.henny.hha.logic.Targeting
import dev.henny.hha_void.VoidAddon
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.minecraft.entity.LivingEntity
import net.minecraft.entity.damage.DamageTypes
import net.minecraft.entity.effect.StatusEffectCategory
import net.minecraft.entity.effect.StatusEffectInstance
import net.minecraft.entity.effect.StatusEffects
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvents
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.math.Box
import net.minecraft.util.math.Vec3d
import net.minecraft.world.TeleportTarget
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin

/**
 * Weitere Set-Passiva des Void-Sets:
 * - Geteilte Gaben: positive Effekte springen auf getrustete Spieler über
 * - Void-Fang: Sturz ins Void teleportiert zum Spawnpunkt statt zu töten
 * - Notriss: unter der HP-Schwelle Teleport von allen weg + lange Invis
 */
object SetPerks {

    /** Spieler-UUID → Server-Tick, ab dem der Notriss wieder bereit ist. */
    private val escapeReadyAt = HashMap<UUID, Long>()

    fun initVoidRescue() {
        ServerLivingEntityEvents.ALLOW_DAMAGE.register { entity, source, _ ->
            if (entity !is ServerPlayerEntity) return@register true
            if (!source.isOf(DamageTypes.OUT_OF_WORLD)) return@register true
            if (!HhaConfig.enabled("hha_void.void_rescue")) return@register true
            if (!VoidAddon.SET.hasFullSet(entity)) return@register true
            if (entity.y > entity.entityWorld.bottomY - 10) return@register true

            // Der Void verschluckt dich — und spuckt dich am Spawn wieder aus
            val target = entity.getRespawnTarget(true, TeleportTarget.NO_OP)
            entity.teleportTo(target)
            entity.fallDistance = 0.0
            dev.henny.hha.logic.Fx.launchPlayer(entity, Vec3d.ZERO)
            entity.addStatusEffect(StatusEffectInstance(StatusEffects.RESISTANCE, 100, 4, false, false, true))

            val world = entity.entityWorld as? ServerWorld
            world?.let {
                VoidFx.burst(it, entity.entityPos.add(0.0, 1.0, 0.0), 2)
                it.playSound(
                    null, entity.blockPos, SoundEvents.BLOCK_RESPAWN_ANCHOR_DEPLETE.value(),
                    SoundCategory.PLAYERS, 0.8f, 0.6f
                )
            }
            entity.sendMessage(
                Text.translatable("hha_void.msg.void_rescue").formatted(Formatting.DARK_PURPLE), true
            )
            false
        }
    }

    /** Geteilte Gaben — alle ~2s die eigenen positiven Effekte an Trusted weitergeben. */
    fun shareTick(world: ServerWorld, player: ServerPlayerEntity) {
        if (world.time % 40L != 0L) return
        if (!HhaConfig.enabled("hha_void.share_effects")) return

        val beneficial = player.statusEffects.filter {
            it.effectType.value().category == StatusEffectCategory.BENEFICIAL && it.duration > 20
        }
        if (beneficial.isEmpty()) return

        val range = HhaConfig.num("hha_void.share_range")
        val allies = world.getEntitiesByClass(
            ServerPlayerEntity::class.java,
            Box.of(player.entityPos, range * 2, range * 2, range * 2)
        ) {
            it.isAlive && it != player &&
                it.squaredDistanceTo(player) <= range * range &&
                Targeting.isFriendly(player, it)
        }
        if (allies.isEmpty()) return

        for (ally in allies) {
            for (effect in beneficial) {
                ally.addStatusEffect(
                    StatusEffectInstance(
                        effect.effectType,
                        minOf(effect.duration, 100),
                        effect.amplifier,
                        true, false, true,
                    )
                )
            }
            // dezente Void-Brücke zum Verbündeten
            val from = player.entityPos.add(0.0, 1.2, 0.0)
            val to = ally.entityPos.add(0.0, 1.2, 0.0)
            VoidVisuals.arc(world, from, from.add(to).multiply(0.5).add(0.0, 1.0, 0.0), to, 0, 8)
        }
    }

    /** Notriss — unter der Schwelle: Teleport von allen weg + Full-Invis. */
    fun escapeTick(world: ServerWorld, player: ServerPlayerEntity) {
        if (!HhaConfig.enabled("hha_void.escape")) return
        if (player.health > HhaConfig.numF("hha_void.escape_health")) return
        val server = world.server ?: return
        val now = server.ticks.toLong()
        if (now < escapeReadyAt.getOrDefault(player.uuid, 0L)) return

        // Fluchtrichtung: gemittelt von allen Lebewesen in der Nähe weg
        val threats = world.getEntitiesByClass(
            LivingEntity::class.java,
            Box.of(player.entityPos, 40.0, 20.0, 40.0)
        ) { it.isAlive && it != player }

        var away = Vec3d.ZERO
        for (threat in threats) {
            val delta = player.entityPos.subtract(threat.entityPos).let { Vec3d(it.x, 0.0, it.z) }
            if (delta.lengthSquared() > 0.01) {
                away = away.add(delta.normalize().multiply(1.0 / (1.0 + delta.length() * 0.2)))
            }
        }
        val direction = if (away.lengthSquared() > 0.01) {
            away.normalize()
        } else {
            val angle = world.random.nextDouble() * Math.PI * 2.0
            Vec3d(cos(angle), 0.0, sin(angle))
        }

        val distance = HhaConfig.num("hha_void.escape_distance")
        var destination: Vec3d? = null
        var d = distance
        outer@ while (d >= 4.0) {
            for (yOffset in intArrayOf(0, 1, 2, -1, -2, 3)) {
                val delta = direction.multiply(d).add(0.0, yOffset.toDouble(), 0.0)
                if (world.isSpaceEmpty(player, player.boundingBox.offset(delta))) {
                    destination = player.entityPos.add(delta)
                    break@outer
                }
            }
            d -= 2.0
        }
        val target = destination ?: return

        escapeReadyAt[player.uuid] = now + HhaConfig.num("hha_void.escape_cooldown").toLong()

        val from = player.entityPos
        player.requestTeleport(target.x, target.y, target.z)
        player.fallDistance = 0.0
        // Full-Invis übernimmt das Rüstungs-Verstecken (Set-Passiv)
        player.addStatusEffect(
            StatusEffectInstance(StatusEffects.INVISIBILITY, HhaConfig.num("hha_void.escape_invis").toInt(), 0, false, false, true)
        )
        player.addStatusEffect(StatusEffectInstance(StatusEffects.SPEED, 100, 1, false, false, true))

        VoidFx.teleportFx(world, from, target, 0.6f)
        player.sendMessage(
            Text.translatable("hha_void.msg.escape").formatted(Formatting.DARK_PURPLE), true
        )
    }
}
