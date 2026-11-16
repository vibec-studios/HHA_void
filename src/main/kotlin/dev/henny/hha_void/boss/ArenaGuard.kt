package dev.henny.hha_void.boss

import dev.henny.hha_void.VoidSounds
import dev.henny.hha_void.logic.VoidVisuals
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.entity.effect.StatusEffectInstance
import net.minecraft.entity.effect.StatusEffects
import net.minecraft.registry.RegistryKey
import net.minecraft.server.MinecraftServer
import net.minecraft.sound.SoundCategory
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.math.Vec3d
import net.minecraft.world.World
import java.util.UUID
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Schutz vor der Leere während eines Kampfes: Wer unter die Arena fällt, wird von Oruuns Schwerkraft
 * zurück auf die Plattform geholt (statt zu sterben).
 *
 * Läuft absichtlich im globalen Server-Tick und nicht im Tick des Wals: In der großen Arena kann ein
 * Spieler so weit vom Wal entfernt sein, dass dessen Chunk gerade nicht tickt – die Rettung muss trotzdem greifen.
 */
object ArenaGuard {

    private class Fight(val world: RegistryKey<World>, val center: Vec3d, var focus: Vec3d, var lastSeen: Long)

    private val fights = HashMap<UUID, Fight>()

    /**
     * Wie weit unter dem Boden jemand gerettet wird, und mit wie viel Schaden. Die Unterseite der Himmelsarena reicht
     * 64 Blöcke tief; wer daran vorbei fällt, ist schon schnell – Runterfallen soll wehtun, aber nicht töten.
     */
    private const val CATCH_DEPTH = 40.0
    private const val CATCH_DAMAGE = 9f

    /** Horizontaler Bereich um die Arenamitte (Plattform + Inselring + Rand). */
    private const val CATCH_RANGE = TribunalBuilder.RADIUS + 130.0

    /** Ohne Lebenszeichen des Wals (z. B. Server-Neustart ohne Spieler) läuft der Schutz nach 10 min aus. */
    private const val STALE_TICKS = 20L * 60 * 10

    /** Vom Wal jeden Tick aufgerufen, solange er mit Arena kämpft. */
    fun active(boss: UUID, world: RegistryKey<World>, center: Vec3d, focus: Vec3d, time: Long) {
        val fight = fights[boss]
        if (fight == null) {
            fights[boss] = Fight(world, center, focus, time)
        } else {
            fight.lastSeen = time
            fight.focus = focus
        }
    }

    fun ended(boss: UUID) {
        fights.remove(boss)
    }

    /** Sichere Stelle nahe dem Kampfort: fester Boden darunter (kein Loch), sonst die Arenamitte. */
    private fun landing(world: net.minecraft.server.world.ServerWorld, fight: Fight): Vec3d {
        val pos = net.minecraft.util.math.BlockPos.Mutable()
        repeat(12) {
            val angle = world.random.nextDouble() * PI * 2.0
            val r = 10.0 + world.random.nextDouble() * 22.0
            val at = fight.focus.add(cos(angle) * r, 0.0, sin(angle) * r)
            pos.set(at.x, fight.center.y - 1.0, at.z)
            if (!world.getBlockState(pos).getCollisionShape(world, pos).isEmpty) return Vec3d(at.x, fight.center.y + 1.0, at.z)
        }
        return fight.center.add(3.0, 1.0, 3.0)
    }

    fun init() {
        ServerTickEvents.END_SERVER_TICK.register(::tick)
    }

    /**
     * Endermen, die auf eine Arena teleportiert sind (Spawns verhindert schon `SpawnHelperMixin`), verschwinden wieder –
     * nur wo ein Spieler in der Nähe ist (sonst tickt dort ohnehin nichts).
     */
    private fun sweepEndermen(server: MinecraftServer) {
        for (world in server.worlds) {
            val reg = ArenaRegistry.find(world) ?: continue
            if (reg.centers.isEmpty()) continue
            for (c in reg.centers) {
                val r = ArenaRegistry.RANGE
                if (world.players.none { (it.x - c.x) * (it.x - c.x) + (it.z - c.z) * (it.z - c.z) < (r + 64) * (r + 64) }) continue
                val box = net.minecraft.util.math.Box(c.x - r, c.y - 140.0, c.z - r, c.x + r, c.y + 100.0, c.z + r)
                for (e in world.getEntitiesByType(net.minecraft.entity.EntityType.ENDERMAN, box) { !it.hasCustomName() }) {
                    if (ArenaRegistry.contains(world, e.x, e.y, e.z)) e.discard()
                }
            }
        }
    }

    private fun tick(server: MinecraftServer) {
        if (server.ticks % 40 == 7) sweepEndermen(server)
        if (fights.isEmpty() || server.ticks % 4 != 0) return
        val iterator = fights.values.iterator()
        while (iterator.hasNext()) {
            val fight = iterator.next()
            val world = server.getWorld(fight.world)
            if (world == null || world.time - fight.lastSeen > STALE_TICKS) {
                iterator.remove()
                continue
            }
            val c = fight.center
            for (player in world.players) {
                if (player.isSpectator || player.isCreative || !player.isAlive) continue
                // im Bauch des Leviathans (unter der Arena) ist man nicht gefallen
                if (LeviathanBelly.isMarked(player)) continue
                if (player.y > c.y - CATCH_DEPTH) continue
                val dx = player.x - c.x
                val dz = player.z - c.z
                if (dx * dx + dz * dz > CATCH_RANGE * CATCH_RANGE) continue
                val at = landing(world, fight)
                player.requestTeleport(at.x, at.y + 6.0, at.z)
                player.velocity = Vec3d.ZERO
                player.velocityDirty = true
                player.fallDistance = 0.0
                player.removeStatusEffect(StatusEffects.LEVITATION)
                player.addStatusEffect(StatusEffectInstance(StatusEffects.SLOW_FALLING, 40, 0, false, false, true))
                player.addStatusEffect(StatusEffectInstance(StatusEffects.SLOWNESS, 60, 1, false, false, true))
                player.addStatusEffect(StatusEffectInstance(StatusEffects.WEAKNESS, 60, 0, false, false, true))
                player.damage(world, world.damageSources.magic(), CATCH_DAMAGE)
                player.sendMessage(Text.translatable("hha_void.boss.msg.caught").formatted(Formatting.LIGHT_PURPLE), true)
                VoidVisuals.nova(world, at.add(0.0, 1.0, 0.0), 2.5f)
                world.playSound(null, at.x, at.y, at.z, VoidSounds.GRAVITY_WAVE, SoundCategory.HOSTILE, 2f, 1.5f)
            }
        }
    }
}
