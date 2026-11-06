package dev.henny.hha_void.client.fx

import net.minecraft.block.BlockRenderType
import net.minecraft.block.BlockState
import net.minecraft.client.MinecraftClient
import net.minecraft.client.render.OverlayTexture
import net.minecraft.client.render.WorldRenderer
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.client.world.ClientWorld
import net.minecraft.particle.BlockStateParticleEffect
import net.minecraft.particle.ParticleTypes
import net.minecraft.sound.SoundCategory
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.RotationAxis
import net.minecraft.util.math.Vec3d
import net.minecraft.world.Heightmap
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Das schwarze Loch des Void Collapse. Horizont, Scheibe und Lichtablenkung kommen
 * aus der Linse ([VoidLens]); hier sitzt alles Drumherum: echte Blöcke, die aus dem
 * Boden gerissen werden, kreisende Brocken, Materieströme, Jets und Fesseln.
 */
private const val CLOSE_SOON_TICKS = 18

class BlackHoleEffect(
    val center: Vec3d,
    private val ground: Vec3d,
    duration: Int,
) : VoidEffect(duration) {

    private class Debris(
        val state: BlockState,
        var pos: Vec3d,
        var velocity: Vec3d,
        val axis: Vector3f,
        val size: Float,
        var previous: Vec3d = pos,
    )

    private class OrbitRock(val mesh: Mesh, val radius: Float, val phase: Float, val speed: Float, val lift: Float, val size: Float, val axis: Vector3f)

    private val rnd = Random(center.hashCode())
    private val debris = ArrayList<Debris>()
    private val tethers = LinkedHashSet<Int>()
    private val orbiters = List(6) { i ->
        OrbitRock(
            VoidMeshes.ROCKS[i % VoidMeshes.ROCKS.size],
            radius = 4.5f + rnd.nextFloat() * 3f,
            phase = rnd.nextFloat() * (PI * 2.0).toFloat(),
            speed = 0.05f + rnd.nextFloat() * 0.025f,
            lift = (rnd.nextFloat() - 0.5f) * 1.1f,
            size = 0.14f + rnd.nextFloat() * 0.22f,
            axis = Vector3f(rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f).normalize(),
        )
    }
    private val holeHeight = (center.y - ground.y).toFloat()

    /** Springt in die Schlussphase: das Loch fällt in einer knappen Sekunde zusammen. */
    fun closeSoon() {
        if (life - age > CLOSE_SOON_TICKS) age = life - CLOSE_SOON_TICKS
    }

    fun addTether(entityId: Int) {
        tethers += entityId
    }

    // ---------- Hüllkurven ----------

    private val closing: Float get() = 16f

    /** Aufblühen, langsames Wachsen, am Ende sekundenschnell in sich zusammenfallen. */
    fun horizon(tickDelta: Float): Float {
        val t = age + tickDelta
        val grow = easeOutBack((t / 26f).coerceIn(0f, 1f))
        val swell = 1f + 0.16f * (t / life).coerceIn(0f, 1f)
        val breath = 1f + 0.015f * sin(t * 0.3f)
        val remaining = life - t
        val collapse = if (remaining < closing) (remaining / closing).coerceAtLeast(0f).let { it * it } else 1f
        return HORIZON * grow * swell * breath * collapse
    }

    fun intensity(tickDelta: Float): Float {
        val t = age + tickDelta
        val remaining = life - t
        return min((t / 18f).coerceIn(0f, 1f), (remaining / 8f).coerceIn(0f, 1f))
    }

    /** Heller Puls beim Öffnen und beim Zusammenbruch. */
    fun flash(tickDelta: Float): Float {
        val t = age + tickDelta
        val open = (1f - t / 7f).coerceIn(0f, 1f)
        val close = ((closing * 0.45f - (life - t)) / (closing * 0.45f)).coerceIn(0f, 1f)
        return open * open * 0.05f + close * close * close * 0.12f
    }

    fun diskNormal(tickDelta: Float): Vector3f {
        val t = (age + tickDelta) * 0.02f
        val tilt = 0.27f
        return Vector3f(sin(tilt) * cos(t), cos(tilt), sin(tilt) * sin(t)).normalize()
    }

    fun diskOuter(tickDelta: Float): Float = horizon(tickDelta) * 6.5f

    fun diskTime(tickDelta: Float): Float = (age + tickDelta) / 20f

    // ---------- Simulation ----------

    override fun tick(world: ClientWorld) {
        super.tick(world)
        val remaining = life - age
        VoidFxClient.shake(center, if (remaining < closing) 0.5f else 0.06f, 40.0)

        if (remaining > closing + 10 && age % 2 == 0) rip(world)
        val rs = horizon(0f).coerceAtLeast(0.2f)
        val iterator = debris.iterator()
        while (iterator.hasNext()) {
            val piece = iterator.next()
            val toCenter = center.subtract(piece.pos)
            val distance = toCenter.length()
            if (distance < rs * 1.5) {
                iterator.remove()
                continue
            }
            val pull = toCenter.multiply(1.0 / distance)
            val swirl = Vec3d(-pull.z, 0.0, pull.x)

            // Die Scheibe wirft Trümmer wieder aus: im Bogen über das Schlachtfeld
            if (distance < rs * 7.0 && rnd.nextFloat() < 0.05f) {
                val outward = pull.multiply(-(0.2 + rnd.nextDouble() * 0.25))
                throwPiece(piece, swirl.multiply(0.4 + rnd.nextDouble() * 0.35).add(outward).add(0.0, 0.2 + rnd.nextDouble() * 0.3, 0.0))
                iterator.remove()
                continue
            }
            piece.velocity = piece.velocity.multiply(0.965)
                .add(pull.multiply(0.042 + 0.34 / (distance + 1.0)))
                .add(swirl.multiply(0.03))
            piece.previous = piece.pos
            piece.pos = piece.pos.add(piece.velocity)
        }
        // Beim Zusammenbruch fliegt alles, was noch in der Luft ist, in alle Richtungen davon
        if (remaining == closing.toInt()) {
            for (piece in debris) {
                val away = piece.pos.subtract(center).normalize()
                throwPiece(piece, away.multiply(0.5 + rnd.nextDouble() * 0.45).add(0.0, 0.2 + rnd.nextDouble() * 0.25, 0.0))
            }
            debris.clear()
        }
    }

    private fun throwPiece(piece: Debris, velocity: Vec3d) {
        VoidFxClient.spawn(ThrownBlockEffect(piece.state, piece.pos, velocity, piece.axis, piece.size))
    }

    /** Reißt einen sichtbaren Oberflächenblock als Trümmerstück aus dem Boden (nur optisch). */
    private fun rip(world: ClientWorld) {
        if (debris.size >= MAX_DEBRIS) return
        val angle = rnd.nextFloat() * (PI * 2.0).toFloat()
        val distance = 3f + sqrt(rnd.nextFloat()) * 9f
        val x = (ground.x + cos(angle) * distance).toInt()
        val z = (ground.z + sin(angle) * distance).toInt()
        val y = world.getTopY(Heightmap.Type.MOTION_BLOCKING, x, z) - 1
        val pos = BlockPos(x, y, z)
        val state = world.getBlockState(pos)
        if (state.isAir || !state.fluidState.isEmpty || state.renderType != BlockRenderType.MODEL) return

        val start = Vec3d(x + 0.5, y + 1.1, z + 0.5)
        debris += Debris(
            state,
            start,
            Vec3d((rnd.nextFloat() - 0.5) * 0.1, 0.2 + rnd.nextFloat() * 0.25, (rnd.nextFloat() - 0.5) * 0.1),
            Vector3f(rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f).normalize(),
            0.25f + rnd.nextFloat() * 0.3f,
        )
        val dust = BlockStateParticleEffect(ParticleTypes.BLOCK, state)
        repeat(3) {
            world.addParticleClient(dust, start.x, start.y, start.z, 0.0, 0.08, 0.0)
        }
    }

    // ---------- Zeichnen ----------

    override fun solid(c: FxContext) {
        val time = age + c.tickDelta
        val rs = horizon(c.tickDelta)
        if (rs < 0.05f) return

        // Echte Blockmodelle im Sog
        for (piece in debris) {
            val pos = piece.previous.lerp(piece.pos, c.tickDelta.toDouble())
            val distance = pos.distanceTo(center).toFloat()
            val shrink = (distance / (rs * 3f)).coerceIn(0.15f, 1f)
            drawFlyingBlock(c, piece.state, pos, piece.axis, time * 0.22f, piece.size * shrink)
        }

        // Felsbrocken in der Scheibenebene
        val normal = diskNormal(c.tickDelta)
        val (u, v) = VoidDraw.basis(normal)
        val base = c.rel(center)
        val shrink = rs / HORIZON
        for ((index, rock) in orbiters.withIndex()) {
            val angle = rock.phase + time * rock.speed
            val radius = rock.radius * shrink
            val at = Vector3f(base)
                .add(Vector3f(u).mul(cos(angle) * radius))
                .add(Vector3f(v).mul(sin(angle) * radius))
                .add(Vector3f(normal).mul(rock.lift))
            val matrix = Matrix4f().translate(at)
                .rotate(Quaternionf().rotationAxis(time * 0.1f, rock.axis))
                .scale(rock.size * shrink.coerceAtMost(1f))
            VoidDraw.mesh(c, VoidPipelines.SOLID_LAYER, rock.mesh, matrix, 0, 0, index)
        }
    }

    override fun before(c: FxContext) {
        val time = age + c.tickDelta
        val rs = horizon(c.tickDelta)
        val strength = intensity(c.tickDelta)
        if (rs < 0.05f || strength <= 0f) return
        val top = c.rel(center)
        val root = c.rel(ground).add(0f, 0.06f, 0f)
        val world = MinecraftClient.getInstance().world

        // Dunkler Fleck und Runenringe, wo der Boden zerfällt
        VoidDraw.disc(c, VoidPipelines.ENERGY_LAYER, root, VoidDraw.GROUND_U, VoidDraw.GROUND_V, 6.5f, Palette.alpha(Palette.DEEP, strength * 0.65f), Mode.STAIN, 7)
        VoidDraw.ring(c, root, VoidDraw.GROUND_U, VoidDraw.GROUND_V, 4.6f, 5f, Palette.alpha(Palette.MAGENTA, strength * 0.7f), Mode.RUNE, 2, rotation = time * 0.05f)
        VoidDraw.ring(c, root, VoidDraw.GROUND_U, VoidDraw.GROUND_V, 6f, 6.3f, Palette.alpha(Palette.VIOLET, strength * 0.6f), Mode.RUNE, 3, rotation = -time * 0.03f)
        val pulse = ((time % 30f) / 30f)
        VoidDraw.ring(c, root, VoidDraw.GROUND_U, VoidDraw.GROUND_V, 8f * (1f - pulse) * 0.8f, 8f * (1f - pulse), Palette.alpha(Palette.VIOLET, strength * pulse * 0.5f))

        // Materietrichter vom Boden hinauf zum Loch
        val funnel = Matrix4f().translate(root).rotateY(time * 0.14f).scale(3.5f, holeHeight, 3.5f)
        VoidDraw.mesh(c, VoidPipelines.ENERGY_LAYER, VoidMeshes.FUNNEL, funnel, Palette.alpha(Palette.VIOLET, strength * 0.28f), Mode.SHELL, 12)

        // Spiralige Materieströme
        for (arm in 0 until 3) {
            val offset = arm * (PI * 2.0 / 3.0).toFloat()
            val points = List(28) { i ->
                val t = i / 27f
                val radius = (5f * (1f - t) * (1f - t) + rs * 1.1f)
                val a = offset + t * 8.5f + time * 0.11f
                Vector3f(root).add(cos(a) * radius, t * holeHeight, sin(a) * radius)
            }
            VoidDraw.ribbon(
                c, points,
                width = { 0.05f + 0.08f * it },
                color = { Palette.alpha(Palette.mix(Palette.VIOLET, Palette.PINK, it), strength * (0.12f + 0.88f * it)) },
                seed = arm,
                uLength = 3f,
            )
        }

        // Polar-Jets
        val normal = diskNormal(c.tickDelta)
        for (side in intArrayOf(1, -1)) {
            val dir = Vector3f(normal).mul(side.toFloat())
            val length = if (side > 0) 9f else holeHeight * 0.6f
            val jet = Matrix4f().translate(top).rotate(VoidDraw.alignY(dir)).rotateY(time * 0.4f).scale(rs * 0.7f, length, rs * 0.7f)
            VoidDraw.mesh(c, VoidPipelines.ENERGY_LAYER, VoidMeshes.JET, jet, Palette.alpha(Palette.VIOLET, strength * 0.35f), Mode.SHELL, 13 + side)
        }

        // Energie-Fesseln zu den Opfern
        if (world != null) {
            for (id in tethers) {
                val entity = world.getEntityById(id) ?: continue
                if (entity.isRemoved) continue
                val body = c.rel(entity.getLerpedPos(c.tickDelta).add(0.0, entity.height * 0.5, 0.0))
                drawTether(c, body, top, id, time, strength, entity.width)
            }
        }
    }

    private fun drawTether(c: FxContext, from: Vector3f, to: Vector3f, seed: Int, time: Float, strength: Float, bodyWidth: Float) {
        val delta = Vector3f(to).sub(from)
        val (u, v) = VoidDraw.basis(delta)
        val points = List(24) { i ->
            val t = i / 23f
            val wobble = sin(t * PI.toFloat()) * 0.6f
            val a = t * 9f + time * 0.35f + seed
            Vector3f(from).add(delta.x * t, delta.y * t, delta.z * t)
                .add(Vector3f(u).mul(cos(a) * wobble))
                .add(Vector3f(v).mul(sin(a) * wobble))
        }
        VoidDraw.ribbon(
            c, points,
            width = { 0.05f + 0.04f * sin(it * PI.toFloat()) },
            color = { Palette.alpha(Palette.mix(Palette.PINK, Palette.VIOLET, it), strength * (1f - it * 0.5f)) },
            seed = seed and 0xFF,
            uLength = 3f,
        )
        val spin = time * 0.3f + seed
        val radius = (bodyWidth * 0.75f).coerceAtLeast(0.5f)
        val (ringU, ringV) = VoidDraw.basis(Vector3f(sin(spin), 1f, cos(spin)))
        VoidDraw.ring(c, from, ringU, ringV, radius * 0.85f, radius, Palette.alpha(Palette.MAGENTA, strength))
        VoidDraw.ring(c, from, ringV, ringU, radius * 1.1f, radius * 1.22f, Palette.alpha(Palette.VIOLET, strength * 0.8f))
    }

    override fun after(c: FxContext) {
        val t = age + c.tickDelta
        if (t < 10f) {
            val fade = 1f - t / 10f
            VoidDraw.glow(c, c.rel(center), 4f * fade, Palette.alpha(Palette.PINK, fade * 0.7f))
        }
    }

    private companion object {
        const val HORIZON = 0.7f
        const val MAX_DEBRIS = 36
    }
}

/** Zeichnet ein echtes, rotierendes Blockmodell an einer freien Weltposition. */
internal fun drawFlyingBlock(c: FxContext, state: BlockState, pos: Vec3d, axis: Vector3f, angle: Float, size: Float) {
    val client = MinecraftClient.getInstance()
    val world = client.world ?: return
    val at = c.rel(pos)
    val matrices = MatrixStack()
    matrices.translate(at.x, at.y, at.z)
    matrices.multiply(RotationAxis.of(axis).rotation(angle))
    matrices.scale(size, size, size)
    matrices.translate(-0.5f, -0.5f, -0.5f)
    val light = WorldRenderer.getLightmapCoordinates(world, BlockPos.ofFloored(pos))
    client.blockRenderManager.renderBlockAsEntity(state, matrices, c.provider, light, OverlayTexture.DEFAULT_UV)
}

/** Vom Loch ausgeworfener Block: fliegt ballistisch und zerschellt beim Aufprall (nur optisch). */
class ThrownBlockEffect(
    private val state: BlockState,
    start: Vec3d,
    private var velocity: Vec3d,
    private val axis: Vector3f,
    private val size: Float,
) : VoidEffect(160) {
    private var pos = start
    private var previous = start
    private var landed = false

    override val finished: Boolean get() = landed || age >= life

    override fun tick(world: ClientWorld) {
        super.tick(world)
        velocity = velocity.multiply(0.985).add(0.0, -0.045, 0.0)
        previous = pos
        val next = pos.add(velocity)
        val blockPos = BlockPos.ofFloored(next)
        if (!world.getBlockState(blockPos).getCollisionShape(world, blockPos).isEmpty) {
            land(world)
            return
        }
        pos = next
    }

    private fun land(world: ClientWorld) {
        landed = true
        val dust = BlockStateParticleEffect(ParticleTypes.BLOCK, state)
        repeat(12) {
            world.addParticleClient(
                dust, pos.x, pos.y + 0.2, pos.z,
                (world.random.nextDouble() - 0.5) * 0.6, world.random.nextDouble() * 0.5, (world.random.nextDouble() - 0.5) * 0.6,
            )
        }
        val player = MinecraftClient.getInstance().player
        world.playSound(player, pos.x, pos.y, pos.z, state.soundGroup.breakSound, SoundCategory.BLOCKS, 0.7f, 0.75f)
    }

    override fun solid(c: FxContext) {
        if (landed) return
        drawFlyingBlock(c, state, previous.lerp(pos, c.tickDelta.toDouble()), axis, (age + c.tickDelta) * 0.32f, size)
    }
}
