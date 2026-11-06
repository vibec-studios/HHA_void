package dev.henny.hha_void.logic

import net.minecraft.particle.ParticleTypes
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvents
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d

/** Void-Look: Portal-Schlieren, Endstaub und dunkle Tinte — gemeinsame Effekt-Helfer. */
object VoidFx {

    /** Dichte Void-Wolke (Teleports, Einschläge). */
    fun burst(world: ServerWorld, center: Vec3d, strength: Int = 1) {
        VoidVisuals.burst(world, center, strength)
        world.spawnParticles(
            ParticleTypes.PORTAL,
            center.x, center.y + 0.6, center.z,
            10 * strength, 0.4, 0.7, 0.4, 0.4
        )
        world.spawnParticles(
            ParticleTypes.END_ROD,
            center.x, center.y + 0.7, center.z,
            3 * strength, 0.3, 0.4, 0.3, 0.02
        )
    }

    /** Energie-Streifen zwischen zwei Punkten: Dash-Spur, Dolch-Flugbahn. */
    fun trail(world: ServerWorld, from: Vec3d, to: Vec3d) {
        VoidVisuals.streak(world, from, to)
    }

    /** Standard-Teleport-Feedback an Start- und Zielpunkt. */
    fun teleportFx(world: ServerWorld, from: Vec3d, to: Vec3d, pitch: Float = 1.0f) {
        burst(world, from)
        burst(world, to)
        world.playSound(
            null, BlockPos.ofFloored(to), SoundEvents.ENTITY_ENDERMAN_TELEPORT,
            SoundCategory.PLAYERS, 1.0f, pitch
        )
    }
}
