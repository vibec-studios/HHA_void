package dev.henny.hha_void.client.fx

import net.minecraft.client.world.ClientWorld
import net.minecraft.entity.Entity
import net.minecraft.util.math.Vec3d
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/**
 * Basis aller 3D-Effekte. Zeichenphasen pro Frame:
 * [solid] schreibt Tiefe und wird vom Loch mit verzerrt, [before] ist additive
 * Energie vor der Linse, [after] zeichnet Blitze über der Linse.
 */
abstract class VoidEffect(val life: Int) {
    var age = 0
        protected set

    open val finished: Boolean get() = age >= life

    open fun tick(world: ClientWorld) {
        age++
    }

    fun progress(tickDelta: Float) = ((age + tickDelta) / life).coerceIn(0f, 1f)

    open fun solid(c: FxContext) {}
    open fun before(c: FxContext) {}
    open fun after(c: FxContext) {}
}

/** Funken als kurze, ballistische Lichtstriche. */
internal class Sparks(count: Int, speed: Float, seed: Int, private val gravity: Float = 0.012f) {
    private val dirs = Random(seed).let { rnd ->
        List(count) {
            val theta = rnd.nextFloat() * (PI * 2.0).toFloat()
            val y = rnd.nextFloat() * 1.7f - 0.7f
            val xz = kotlin.math.sqrt(max(0f, 1f - y * y))
            val s = speed * (0.45f + rnd.nextFloat() * 0.75f)
            Vector3f(cos(theta) * xz * s, y * s, sin(theta) * xz * s)
        }
    }

    fun draw(c: FxContext, origin: Vector3f, time: Float, fade: Float, color: Int) {
        for ((index, d) in dirs.withIndex()) {
            val t1 = time
            val t0 = max(0f, time - 2.2f)
            val a = Vector3f(origin).add(d.x * t0, d.y * t0 - gravity * t0 * t0, d.z * t0)
            val b = Vector3f(origin).add(d.x * t1, d.y * t1 - gravity * t1 * t1, d.z * t1)
            VoidDraw.ribbon(
                c, listOf(a, b),
                width = { 0.04f },
                color = { Palette.alpha(color, fade * it) },
                seed = index,
            )
        }
    }
}

/** Blitz + Schale + Funken: Teleports und kleine Einschläge. */
class BurstEffect(private val pos: Vec3d, strength: Float) : VoidEffect(18) {
    private val scale = 0.5f + strength * 0.3f
    private val sparks = Sparks((5 * strength).toInt().coerceIn(3, 12), 0.14f * scale, pos.hashCode())

    override fun after(c: FxContext) {
        val p = progress(c.tickDelta)
        val center = c.rel(pos)
        val fade = 1f - p
        VoidDraw.glow(c, center, scale * 1.5f * (0.5f + easeOutCubic(p)), Palette.alpha(Palette.MAGENTA, fade * fade))
        val shell = Matrix4f().translate(center).scale(scale * (0.3f + 1.5f * easeOutCubic(p)))
        VoidDraw.mesh(c, VoidPipelines.ENERGY_LAYER, VoidMeshes.SPHERE, shell, Palette.alpha(Palette.VIOLET, fade * 0.45f), Mode.SHELL, 3)
        sparks.draw(c, center, age + c.tickDelta, fade, Palette.PINK)
    }
}

/** Kleiner Einschlag: Funke und flacher Stoßring. */
class ImpactEffect(private val pos: Vec3d, private val size: Float) : VoidEffect(14) {
    private val sparks = Sparks(4, 0.1f * size, pos.hashCode())

    override fun after(c: FxContext) {
        val p = progress(c.tickDelta)
        val fade = 1f - p
        val center = c.rel(pos)
        VoidDraw.glow(c, center, size * 0.8f * (1f - p * 0.5f), Palette.alpha(Palette.MAGENTA, fade * fade * 0.8f))
        val r = size * (0.3f + 1.6f * easeOutCubic(p))
        VoidDraw.ring(c, center, VoidDraw.GROUND_U, VoidDraw.GROUND_V, r * 0.72f, r, Palette.alpha(Palette.MAGENTA, fade))
        sparks.draw(c, center, age + c.tickDelta, fade, Palette.MAGENTA)
    }
}

/** Rundes Void-Portal mit dunklem Wirbel, leuchtendem Torus und Ausläufern. */
class PortalEffect(private val center: Vec3d, direction: Vec3d, private val scale: Float) : VoidEffect(26) {
    private val normal = Vector3f(direction.x.toFloat(), direction.y.toFloat(), direction.z.toFloat()).let {
        if (it.lengthSquared() < 1e-6f) Vector3f(0f, 1f, 0f) else it.normalize()
    }
    private val axes = VoidDraw.basis(normal)
    private val seed = Random(center.hashCode()).nextInt(255)

    private fun opening(p: Float): Float = when {
        p < 0.22f -> easeOutBack(p / 0.22f)
        p > 0.7f -> 1f - ((p - 0.7f) / 0.3f).let { it * it }
        else -> 1f
    } * scale * 0.95f

    override fun before(c: FxContext) {
        val p = progress(c.tickDelta)
        val r = opening(p)
        if (r <= 0.01f) return
        val at = c.rel(center)
        val (u, v) = axes
        val spin = (age + c.tickDelta) * 0.32f

        VoidDraw.disc(c, VoidPipelines.ENERGY_LAYER, at, u, v, r, Palette.alpha(Palette.VIOLET, 0.96f), Mode.SWIRL, seed)
        val torus = Matrix4f().translate(at).rotate(VoidDraw.alignY(normal)).rotateY(spin).scale(r)
        VoidDraw.mesh(c, VoidPipelines.ENERGY_LAYER, VoidMeshes.TORUS, torus, Palette.alpha(Palette.PINK, 0.95f), Mode.SHELL, seed)
        VoidDraw.ring(c, at, u, v, r * 1.02f, r * 1.28f, Palette.alpha(Palette.VIOLET, 0.55f), rotation = spin)

        // Ausläufer, die aus dem Rand herausdrehen
        for (arm in 0 until 3) {
            val base = arm * (PI.toFloat() * 2f / 3f) + spin
            val points = List(10) { i ->
                val t = i / 9f
                val a = base + t * 2.2f
                val radius = r * (1f + t * 0.75f)
                Vector3f(at)
                    .add(Vector3f(u).mul(cos(a) * radius))
                    .add(Vector3f(v).mul(sin(a) * radius))
                    .add(Vector3f(normal).mul(sin(t * 3f + spin) * 0.25f * r))
            }
            VoidDraw.ribbon(
                c, points,
                width = { (1f - it) * 0.14f * r },
                color = { Palette.alpha(Palette.MAGENTA, (1f - it) * 0.9f) },
                seed = seed + arm,
            )
        }
    }

    override fun after(c: FxContext) {
        val p = progress(c.tickDelta)
        if (p > 0.3f) return
        val fade = 1f - p / 0.3f
        VoidDraw.glow(c, c.rel(center), scale * 1.1f, Palette.alpha(Palette.MAGENTA, fade * fade * 0.7f))
    }
}

/** Verjüngter Energie-Streifen: Dash- und Pounce-Spur. */
class StreakEffect(private val from: Vec3d, private val to: Vec3d) : VoidEffect(12) {
    private val seed = Random(from.hashCode()).nextInt(255)

    override fun before(c: FxContext) {
        val p = progress(c.tickDelta)
        val fade = (1f - p).let { it * it }
        val a = c.rel(from)
        val b = c.rel(to)
        val points = List(16) { i -> Vector3f(a).lerp(b, i / 15f) }
        val width = 0.2f * (1f - p * 0.65f)
        VoidDraw.ribbon(
            c, points,
            width = { width * (0.15f + 0.85f * it) },
            color = { Palette.alpha(Palette.mix(Palette.VIOLET, Palette.PINK, it), fade) },
            seed = seed,
        )
        VoidDraw.ribbon(
            c, points,
            width = { width * 0.3f * (0.15f + 0.85f * it) },
            color = { Palette.alpha(Palette.WHITE, fade) },
            seed = seed + 1,
        )
    }
}

/** Sky Launch: aufsteigender Wirbel aus Energie, darunter ein Stoßring. */
class VortexEffect(private val feet: Vec3d) : VoidEffect(20) {
    private val seed = Random(feet.hashCode()).nextInt(255)

    override fun before(c: FxContext) {
        val p = progress(c.tickDelta)
        val fade = 1f - p
        val base = c.rel(feet)
        val time = age + c.tickDelta
        val height = 2f + easeOutCubic(p) * 3f
        val wide = 0.7f + p * 0.7f

        val funnel = Matrix4f().translate(base).rotateY(time * 0.5f).scale(wide, height, wide)
        VoidDraw.mesh(c, VoidPipelines.ENERGY_LAYER, VoidMeshes.FUNNEL, funnel, Palette.alpha(Palette.VIOLET, fade * 0.35f), Mode.SHELL, seed)

        val r = 0.4f + easeOutCubic(p) * 2.4f
        VoidDraw.ring(c, base, VoidDraw.GROUND_U, VoidDraw.GROUND_V, r * 0.68f, r, Palette.alpha(Palette.MAGENTA, fade))
        VoidDraw.ring(c, base, VoidDraw.GROUND_U, VoidDraw.GROUND_V, r * 0.3f, r * 0.42f, Palette.alpha(Palette.PINK, fade))

        for (arm in 0 until 2) {
            val offset = arm * PI.toFloat()
            val points = List(18) { i ->
                val t = i / 17f
                val a = offset + t * 7f + time * 0.45f
                val radius = wide * (0.1f + 0.9f * (1f - t) * (1f - t)) * 1.05f
                Vector3f(base).add(cos(a) * radius, t * height, sin(a) * radius)
            }
            VoidDraw.ribbon(
                c, points,
                width = { 0.05f + 0.03f * (1f - it) },
                color = { Palette.alpha(Palette.mix(Palette.MAGENTA, Palette.WHITE, it), fade * (1f - it * 0.5f)) },
                seed = seed + arm,
            )
        }
    }

    override fun after(c: FxContext) {
        val p = progress(c.tickDelta)
        if (p > 0.35f) return
        val fade = 1f - p / 0.35f
        VoidDraw.glow(c, c.rel(feet).add(0f, 0.5f, 0f), 1.1f, Palette.alpha(Palette.MAGENTA, fade * fade * 0.7f))
    }
}

/** Mace-Schockwelle: Bodenring, Kuppel, Risse über den Boden. */
class ShockwaveEffect(private val center: Vec3d, private val ground: Vec3d, private val range: Float) : VoidEffect(22) {
    private val cracks = Random(center.hashCode()).let { rnd ->
        List(12) {
            val angle = it * (PI.toFloat() / 6f) + rnd.nextFloat() * 0.4f
            val points = FloatArray(10) { (rnd.nextFloat() - 0.5f) * 0.45f }
            angle to points
        }
    }

    override fun before(c: FxContext) {
        val p = progress(c.tickDelta)
        val fade = 1f - p
        val base = c.rel(ground).add(0f, 0.08f, 0f)
        val r = range * easeOutCubic(p)

        VoidDraw.ring(c, base, VoidDraw.GROUND_U, VoidDraw.GROUND_V, r * 0.62f, r, Palette.alpha(Palette.MAGENTA, fade))
        VoidDraw.ring(c, base, VoidDraw.GROUND_U, VoidDraw.GROUND_V, r * 0.2f, r * 0.5f, Palette.alpha(Palette.VIOLET, fade * 0.7f))

        val dome = Matrix4f().translate(base).scale(r * 0.6f, r * 0.3f, r * 0.6f)
        VoidDraw.mesh(c, VoidPipelines.ENERGY_LAYER, VoidMeshes.DOME, dome, Palette.alpha(Palette.VIOLET, fade * 0.3f), Mode.SHELL, 9)

        for ((index, crack) in cracks.withIndex()) {
            val (angle, wobble) = crack
            val reach = range * 0.85f * easeOutCubic(p * 1.5f)
            val points = List(wobble.size) { i ->
                val t = i / (wobble.size - 1f)
                val a = angle + wobble[i] * t
                Vector3f(base).add(cos(a) * reach * t, 0.03f, sin(a) * reach * t)
            }
            VoidDraw.ribbon(
                c, points,
                width = { 0.08f * (1f - it * 0.8f) },
                color = { Palette.alpha(Palette.PINK, fade * (1f - it * 0.4f)) },
                seed = index,
            )
        }
    }

    override fun after(c: FxContext) {
        val p = progress(c.tickDelta)
        if (p > 0.4f) return
        val fade = 1f - p / 0.4f
        VoidDraw.glow(c, c.rel(center), range * 0.22f, Palette.alpha(Palette.PINK, fade * fade * 0.7f))
    }
}

/** Geschwungener, zuckender Energiebogen; [tier] steuert Dicke und Farbe. */
class ArcEffect(
    private val from: Vec3d,
    private val control: Vec3d,
    private val to: Vec3d,
    private val tier: Int,
    private val flight: Int,
) : VoidEffect(flight + 9) {
    private val seed = Random(from.hashCode() xor to.hashCode()).nextInt(255)

    private fun curve(t: Float, out: Vector3f, a: Vector3f, b: Vector3f, e: Vector3f): Vector3f {
        val k = 1f - t
        return out.set(a).mul(k * k).add(b.x * 2f * k * t, b.y * 2f * k * t, b.z * 2f * k * t).add(e.x * t * t, e.y * t * t, e.z * t * t)
    }

    override fun before(c: FxContext) {
        val time = age + c.tickDelta
        val head = (time / flight).coerceIn(0f, 1f)
        val tail = ((time - flight) / 9f).coerceIn(0f, 1f)
        if (tail >= 1f) return
        val a = c.rel(from)
        val b = c.rel(control)
        val e = c.rel(to)

        val color = when (tier) {
            3 -> Palette.CYAN
            2 -> Palette.PINK
            1 -> Palette.MAGENTA
            else -> Palette.VIOLET
        }
        val baseWidth = when (tier) {
            0 -> 0.04f
            else -> 0.05f + tier * 0.03f
        }
        val jitter = if (tier == 0) 0.05f else 0.14f + tier * 0.03f
        val strands = if (tier >= 2) 2 else 1

        for (strand in 0 until strands) {
            val rnd = Random(seed + strand * 31 + time.toInt() * 7)
            val points = List(26) { i ->
                val t = tail + (head - tail) * (i / 25f)
                val point = curve(t, Vector3f(), a, b, e)
                val taper = 1f - kotlin.math.abs(i / 25f - 0.5f) * 2f
                point.add(
                    (rnd.nextFloat() - 0.5f) * jitter * taper,
                    (rnd.nextFloat() - 0.5f) * jitter * taper,
                    (rnd.nextFloat() - 0.5f) * jitter * taper,
                )
            }
            val strength = if (strand == 0) 1f else 0.55f
            VoidDraw.ribbon(
                c, points,
                width = { baseWidth * strength * (0.25f + 0.75f * it) },
                color = { Palette.alpha(color, (1f - tail) * (0.35f + 0.65f * it) * strength) },
                seed = seed + strand,
                uLength = 3f,
            )
        }
        if (head < 1f) {
            VoidDraw.glow(c, curve(head, Vector3f(), a, b, e), 0.25f + tier * 0.1f, Palette.alpha(color, 0.8f))
        }
    }
}

/** Zeichnet einen Fels samt Plasma-Mantel, Schweif und Leuchtspur. */
private object MeteorLook {

    fun rock(c: FxContext, pos: Vector3f, scale: Float, spin: Float, axis: Vector3f, mesh: Mesh, seed: Int) {
        val matrix = Matrix4f().translate(pos).rotate(Quaternionf().rotationAxis(spin, axis)).scale(scale)
        VoidDraw.mesh(c, VoidPipelines.SOLID_LAYER, mesh, matrix, 0, 0, seed)
    }

    fun plasma(c: FxContext, pos: Vector3f, dir: Vector3f, scale: Float, seed: Int, intensity: Float = 1f) {
        val back = Vector3f(dir).negate()
        if (back.lengthSquared() < 1e-6f) back.set(0f, 1f, 0f) else back.normalize()
        val align = VoidDraw.alignY(back)

        
        val outer = Matrix4f().translate(pos).rotate(align).scale(scale * 1.1f, scale * 5f, scale * 1.1f)
        VoidDraw.mesh(c, VoidPipelines.ENERGY_LAYER, VoidMeshes.TAIL, outer, Palette.alpha(Palette.VIOLET, 0.8f * intensity), Mode.FLAME, seed)
        val inner = Matrix4f().translate(pos).rotate(align).scale(scale * 0.65f, scale * 3f, scale * 0.65f)
        VoidDraw.mesh(c, VoidPipelines.ENERGY_LAYER, VoidMeshes.TAIL, inner, Palette.alpha(Palette.PINK, 0.95f * intensity), Mode.FLAME, seed + 1)

        VoidDraw.glow(c, pos, scale * 1.2f, Palette.alpha(Palette.MAGENTA, 0.35f * intensity), seed)
    }
}

/** Hängt ein 3D-Modell an einen echten Server-Feuerball und ersetzt dessen Sprite. */
class MeteorEffect(
    val entityId: Int,
    private val big: Boolean,
    private val fallbackPos: Vec3d,
    private val onImpact: (Vec3d, Boolean) -> Unit,
) : VoidEffect(400) {
    private val seed = entityId and 0xFF
    private val mesh = if (big) VoidMeshes.ROCKS_DETAILED[seed % VoidMeshes.ROCKS_DETAILED.size] else VoidMeshes.ROCKS[seed % VoidMeshes.ROCKS.size]
    private val scale = if (big) 0.8f else 0.4f
    private val axis = Vector3f(0.6f, 0.7f, 0.3f + (seed % 5) * 0.1f).normalize()
    private val trail = ArrayDeque<Vec3d>()
    private var entity: Entity? = null
    private var lastPos = fallbackPos
    private var waiting = 0
    private var done = false

    override val finished: Boolean get() = done

    override fun tick(world: ClientWorld) {
        super.tick(world)
        val tracked = entity ?: world.getEntityById(entityId)?.also { entity = it }
        if (tracked == null) {
            if (++waiting > 40) done = true
            return
        }
        if (tracked.isRemoved) {
            onImpact(lastPos, big)
            done = true
            return
        }
        lastPos = tracked.entityPos
        trail.addLast(lastPos)
        while (trail.size > 14) trail.removeFirst()
    }

    private fun current(tickDelta: Float): Vec3d = entity?.takeIf { !it.isRemoved }?.getLerpedPos(tickDelta) ?: lastPos

    private fun heading(): Vector3f {
        val last = trail.lastOrNull() ?: return Vector3f(0f, -1f, 0f)
        val previous = trail.elementAtOrNull(trail.size - 3) ?: return Vector3f(0f, -1f, 0f)
        val delta = last.subtract(previous)
        return Vector3f(delta.x.toFloat(), delta.y.toFloat(), delta.z.toFloat())
    }

    override fun solid(c: FxContext) {
        if (entity == null) return
        MeteorLook.rock(c, c.rel(current(c.tickDelta)), scale, (age + c.tickDelta) * 0.2f, axis, mesh, seed)
    }

    override fun before(c: FxContext) {
        if (entity == null) return
        val pos = c.rel(current(c.tickDelta))
        MeteorLook.plasma(c, pos, heading(), scale, seed)
        if (trail.size >= 2) {
            val points = ArrayList<Vector3f>(trail.size + 1)
            for (p in trail) points += c.rel(p)
            points += pos
            VoidDraw.ribbon(
                c, points,
                width = { scale * 0.25f * it },
                color = { Palette.alpha(Palette.mix(Palette.VIOLET, Palette.PINK, it), it * 0.85f) },
                seed = seed,
                uLength = 2f,
            )
        }
    }
}

/** Finale der Brustplatte: riesiger Komet samt Zielmarkierung am Boden. */
class CometEffect(private val ground: Vec3d, fall: Int) : VoidEffect(fall) {
    private val start = ground.add(14.0, 42.0, 8.0)
    private val mesh = VoidMeshes.ROCKS_DETAILED[0]
    private val axis = Vector3f(0.3f, 0.5f, 0.8f).normalize()

    private fun position(p: Float): Vec3d {
        val k = p * p * (1.5f - 0.5f * p)
        return start.lerp(ground, k.toDouble())
    }

    override fun tick(world: ClientWorld) {
        super.tick(world)
        VoidFxClient.shake(ground, 0.05f + 0.25f * progress(0f), 40.0)
    }

    override fun solid(c: FxContext) {
        val p = progress(c.tickDelta)
        MeteorLook.rock(c, c.rel(position(p)), 1.6f, (age + c.tickDelta) * 0.11f, axis, mesh, 7)
    }

    override fun before(c: FxContext) {
        val p = progress(c.tickDelta)
        val here = c.rel(position(p))
        val dir = c.rel(ground).sub(here)
        MeteorLook.plasma(c, here, dir, 1.6f, 7, 1f)

        // Zielmarkierung: zieht sich auf den Einschlagpunkt zusammen
        val mark = c.rel(ground).add(0f, 0.1f, 0f)
        val spin = (age + c.tickDelta) * 0.25f
        val close = 1f - easeOutCubic(p) * 0.55f
        val fadeIn = (p * 4f).coerceAtMost(1f)
        VoidDraw.ring(c, mark, VoidDraw.GROUND_U, VoidDraw.GROUND_V, 3.6f * close, 3.9f * close, Palette.alpha(Palette.PINK, fadeIn * 0.95f), Mode.RUNE, 3, rotation = spin)
        VoidDraw.ring(c, mark, VoidDraw.GROUND_U, VoidDraw.GROUND_V, 2f * close, 2.2f * close, Palette.alpha(Palette.MAGENTA, fadeIn * 0.85f), Mode.RUNE, 4, rotation = -spin * 1.4f)
        VoidDraw.disc(c, VoidPipelines.ENERGY_LAYER, mark, VoidDraw.GROUND_U, VoidDraw.GROUND_V, 4f * close, Palette.alpha(Palette.DEEP, fadeIn * 0.4f), Mode.STAIN, 5)

    }
}

/** Einschlag des Kometen: Blitz, Pilz-Kuppel, zwei Stoßringe und fliegende Brocken. */
class CometImpactEffect(private val center: Vec3d, private val radius: Float) : VoidEffect(38) {

    private class Chunk(val velocity: Vector3f, val axis: Vector3f, val size: Float, val mesh: Mesh)

    private val chunks = Random(center.hashCode()).let { rnd ->
        List(7) { i ->
            val angle = rnd.nextFloat() * (PI * 2.0).toFloat()
            val up = 0.25f + rnd.nextFloat() * 0.4f
            val speed = 0.2f + rnd.nextFloat() * 0.3f
            Chunk(
                Vector3f(cos(angle) * speed, up, sin(angle) * speed),
                Vector3f(rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f).normalize(),
                0.15f + rnd.nextFloat() * 0.25f,
                VoidMeshes.ROCKS[i % VoidMeshes.ROCKS.size],
            )
        }
    }
    private val sparks = Sparks(12, 0.3f, center.hashCode())
    private val reach = radius * 1.3f

    private fun chunkPosition(chunk: Chunk, base: Vector3f, time: Float) = Vector3f(base).add(
        chunk.velocity.x * time,
        chunk.velocity.y * time - 0.045f * time * time,
        chunk.velocity.z * time,
    )

    override fun solid(c: FxContext) {
        val time = age + c.tickDelta
        val base = c.rel(center)
        for ((index, chunk) in chunks.withIndex()) {
            val at = chunkPosition(chunk, base, time)
            val shrink = (1f - progress(c.tickDelta)).coerceAtLeast(0f)
            MeteorLook.rock(c, at, chunk.size * (0.4f + 0.6f * shrink), time * 0.3f, chunk.axis, chunk.mesh, index)
        }
    }

    override fun before(c: FxContext) {
        val p = progress(c.tickDelta)
        val fade = 1f - p
        val base = c.rel(center).add(0f, 0.1f, 0f)
        val time = age + c.tickDelta

        val r1 = reach * easeOutCubic(p)
        VoidDraw.ring(c, base, VoidDraw.GROUND_U, VoidDraw.GROUND_V, r1 * 0.7f, r1, Palette.alpha(Palette.MAGENTA, fade))
        val r2 = reach * 0.6f * easeOutCubic(p * 1.3f)
        VoidDraw.ring(c, base, VoidDraw.GROUND_U, VoidDraw.GROUND_V, r2 * 0.45f, r2, Palette.alpha(Palette.PINK, fade * 0.9f))
        VoidDraw.disc(c, VoidPipelines.ENERGY_LAYER, base, VoidDraw.GROUND_U, VoidDraw.GROUND_V, radius * 1.6f, Palette.alpha(Palette.DEEP, fade * 0.8f), Mode.STAIN, 2)

        val domeRadius = radius * 0.8f * easeOutCubic(p * 1.4f)
        val dome = Matrix4f().translate(base).scale(domeRadius, domeRadius * 0.7f, domeRadius)
        VoidDraw.mesh(c, VoidPipelines.ENERGY_LAYER, VoidMeshes.DOME, dome, Palette.alpha(Palette.VIOLET, fade * 0.5f), Mode.SHELL, 5)

        for (chunk in chunks) {
            val a = chunkPosition(chunk, base, max(0f, time - 3f))
            val b = chunkPosition(chunk, base, time)
            VoidDraw.ribbon(
                c, listOf(a, b),
                width = { chunk.size * 0.2f * it },
                color = { Palette.alpha(Palette.MAGENTA, fade * it) },
            )
        }
    }

    override fun after(c: FxContext) {
        val p = progress(c.tickDelta)
        val base = c.rel(center).add(0f, 1f, 0f)
        if (p < 0.4f) {
            val fade = 1f - p / 0.4f
            VoidDraw.glow(c, base, radius * 0.9f, Palette.alpha(Palette.PINK, fade * fade * 0.8f))
        }
        sparks.draw(c, base, age + c.tickDelta, 1f - p, Palette.PINK)
    }
}

/** Lenkender Void-Splitter: Kristall-Modell mit Doppel-Spirale; Position kommt per Paket. */
class ShardEffect(start: Vec3d, private val seed: Int) : VoidEffect(400) {
    private var previous = start
    private var current = start
    private var velocity = Vec3d(0.0, 1.0, 0.0)
    private var ended = false
    private var fadeTicks = 0
    private val trail = ArrayDeque<Vec3d>()
    private val mesh = VoidMeshes.SHARD_BLADES[seed.mod(VoidMeshes.SHARD_BLADES.size)]

    override val finished: Boolean get() = age >= life || (ended && fadeTicks > 8)

    fun update(pos: Vec3d, vel: Vec3d) {
        previous = current
        current = pos
        velocity = vel
    }

    fun end(pos: Vec3d) {
        previous = current
        current = pos
        ended = true
    }

    override fun tick(world: ClientWorld) {
        super.tick(world)
        if (ended) {
            fadeTicks++
            if (trail.isNotEmpty()) trail.removeFirst()
            return
        }
        trail.addLast(current)
        while (trail.size > 16) trail.removeFirst()
    }

    private fun pos(tickDelta: Float): Vec3d = if (ended) current else previous.lerp(current, tickDelta.toDouble())

    override fun solid(c: FxContext) {
        if (ended) return
        val dir = Vector3f(velocity.x.toFloat(), velocity.y.toFloat(), velocity.z.toFloat())
        if (dir.lengthSquared() < 1e-5f) return
        dir.normalize()
        val matrix = Matrix4f()
            .translate(c.rel(pos(c.tickDelta)))
            .rotate(VoidDraw.alignY(dir))
            .rotateY((age + c.tickDelta) * 0.5f)
            .scale(0.3f, 1f, 0.3f)
        VoidDraw.mesh(c, VoidPipelines.SOLID_LAYER, mesh, matrix, 0, 2, seed and 0xFF)
    }

    override fun before(c: FxContext) {
        if (trail.size < 2) return
        val head = c.rel(pos(c.tickDelta))
        val base = ArrayList<Vector3f>(trail.size + 1)
        for (p in trail) base += c.rel(p)
        if (!ended) base += head
        val time = age + c.tickDelta
        val fade = if (ended) (1f - fadeTicks / 8f).coerceAtLeast(0f) else 1f

        VoidDraw.ribbon(
            c, base,
            width = { 0.08f * it },
            color = { Palette.alpha(Palette.mix(Palette.VIOLET, Palette.MAGENTA, it), it * fade) },
            seed = seed and 0xFF,
        )
        for (arm in 0..1) {
            val helix = base.mapIndexed { i, point ->
                val a = i * 0.9f - time * 0.7f + arm * PI.toFloat()
                val radius = 0.14f * (i.toFloat() / base.size)
                Vector3f(point).add(cos(a) * radius, sin(a) * radius, cos(a + 1.3f) * radius)
            }
            VoidDraw.ribbon(
                c, helix,
                width = { 0.04f * it },
                color = { Palette.alpha(Palette.PINK, it * fade) },
                seed = (seed and 0xFF) + arm,
            )
        }
        if (!ended) VoidDraw.glow(c, head, 0.4f, Palette.alpha(Palette.MAGENTA, 0.6f), seed and 0xFF)
    }
}

/** Abschluss des schwarzen Lochs: Materie entlädt sich in einer Nova. */
class NovaEffect(private val center: Vec3d, private val size: Float) : VoidEffect(28) {
    private val sparks = Sparks(14, 0.3f, center.hashCode())

    override fun after(c: FxContext) {
        val p = progress(c.tickDelta)
        val fade = 1f - p
        val at = c.rel(center)
        val radius = size * easeOutCubic(p)

        val shell = Matrix4f().translate(at).scale(radius)
        VoidDraw.mesh(c, VoidPipelines.ENERGY_LAYER, VoidMeshes.SPHERE, shell, Palette.alpha(Palette.MAGENTA, fade * 0.45f), Mode.SHELL, 11)

        val tilt = Vector3f(0.2f, 1f, 0.1f).normalize()
        val (u, v) = VoidDraw.basis(tilt)
        VoidDraw.ring(c, at, u, v, radius * 0.85f, radius, Palette.alpha(Palette.PINK, fade * 0.8f))

        if (p < 0.45f) {
            val flash = 1f - p / 0.45f
            VoidDraw.glow(c, at, size * 0.2f, Palette.alpha(Palette.PINK, flash * flash * 0.7f))
        }
        sparks.draw(c, at, age + c.tickDelta, fade, Palette.PINK)
    }
}

/**
 * Schuldsäule: Der Boden springt sternförmig auf, Geröll drückt sich hoch, dann schießt eine
 * Kristallgruppe heraus. Am Ende zerspringt sie in Splitter, statt im Boden zu versinken.
 */
class PillarEffect(
    private val ground: Vec3d, private val delay: Int, private val height: Float, light: Boolean = false,
) : VoidEffect(delay + 34) {
    private val seed = ground.hashCode()
    private val spikes = if (light) VoidMeshes.SPIKES_LIGHT else VoidMeshes.SPIKES
    private val spike = spikes[seed.mod(spikes.size)]
    private val rubble = VoidMeshes.SPIKE_RUBBLE[seed.mod(VoidMeshes.SPIKE_RUBBLE.size)]
    private val turn = (seed.mod(4)) * (PI.toFloat() / 2f)
    private val sparks = Sparks(6, 0.17f, seed)
    private val splinters = Random(seed + 3).let { rnd ->
        List(if (light) 4 else 7) {
            val a = rnd.nextFloat() * (PI * 2.0).toFloat()
            val s = 0.1f + rnd.nextFloat() * 0.16f
            Vector3f(cos(a) * s, 0.2f + rnd.nextFloat() * 0.26f, sin(a) * s)
        }
    }
    private val cracks = Random(seed + 5).let { rnd ->
        List(5) { i ->
            val a = i * (PI * 2.0 / 5.0).toFloat() + rnd.nextFloat() * 0.8f
            val reach = 2.6f + rnd.nextFloat() * 1.6f
            List(4) { j ->
                val k = (j + 1) / 4f
                val swing = (rnd.nextFloat() - 0.5f) * 0.7f
                Vector3f(cos(a + swing * 0.3f) * reach * k, 0.03f, sin(a + swing * 0.3f) * reach * k)
            }
        }
    }

    /** 0 unter dem Boden, 1 ganz ausgefahren; zerspringt nach [SHATTER] Ticks. */
    private fun rise(t: Float): Float {
        val after = t - delay
        if (after <= 0f) return 0f
        return easeOutBack((after / 3f).coerceAtMost(1f))
    }

    override fun tick(world: ClientWorld) {
        super.tick(world)
        if (age == delay) {
            VoidFxClient.shake(ground, 0.18f, 16.0)
            VoidFxClient.spawn(ImpactEffect(ground.add(0.0, 0.2, 0.0), 1.6f))
        }
    }

    override fun solid(c: FxContext) {
        val t = age + c.tickDelta
        val base = c.rel(ground)
        val after = t - delay

        // Geröll: drückt sich schon während der Warnung langsam hoch, sinkt am Ende weg
        val push = easeOutCubic((t / delay).coerceAtMost(1f))
        val sink = ((after - SHATTER - 6f) / 12f).coerceIn(0f, 1f)
        val rubbleY = -1.1f + push * 1.1f - sink * 1.2f
        val rubbleSize = 2.1f + (if (after > 0f) 0.5f else push * 0.2f)
        val slab = Matrix4f().translate(base).translate(0f, rubbleY, 0f).rotateY(turn).scale(rubbleSize, 1.5f, rubbleSize)
        VoidDraw.mesh(c, VoidPipelines.SOLID_LAYER, rubble, slab, 0, 2, seed)

        if (after > 0f && after < SHATTER) {
            val rise = rise(t)
            val wobble = if (after < 8f) sin(after * 2.6f) * 0.018f * (8f - after) else 0f
            val main = Matrix4f().translate(base).rotateY(turn).rotateZ(wobble).scale(2.5f + (1f - rise.coerceAtMost(1f)) * -0.8f, height * rise, 2.5f + (1f - rise.coerceAtMost(1f)) * -0.8f)
            VoidDraw.mesh(c, VoidPipelines.SOLID_LAYER, spike, main, 0, 2, seed)
        }
        if (after >= SHATTER) {
            val time = after - SHATTER
            for ((index, s) in splinters.withIndex()) {
                val at = Vector3f(base).add(s.x * time * 3f, height * 0.25f + index * 0.5f + s.y * time * 2f - 0.05f * time * time, s.z * time * 3f)
                val size = (0.75f + (index % 3) * 0.2f) * (1f - time / 16f).coerceAtLeast(0f)
                val m = Matrix4f().translate(at).rotateY(time * 0.5f + index).rotateX(time * 0.4f + index).scale(size * 1.6f, size * 2.4f, size * 1.6f)
                VoidDraw.mesh(c, VoidPipelines.SOLID_LAYER, VoidMeshes.CRYSTALS[index % VoidMeshes.CRYSTALS.size], m, Palette.alpha(Palette.MAGENTA, 1f), 1, index)
            }
        }
    }

    override fun before(c: FxContext) {
        val t = age + c.tickDelta
        val base = c.rel(ground).add(0f, 0.07f, 0f)
        val warn = (t / delay).coerceAtMost(1f)
        val fade = if (t > delay) (1f - (t - delay - SHATTER * 0.5f) / 16f).coerceIn(0f, 1f) else 1f
        if (fade <= 0f) return
        VoidDraw.disc(c, VoidPipelines.ENERGY_LAYER, base, VoidDraw.GROUND_U, VoidDraw.GROUND_V, 2.4f, Palette.alpha(Palette.DEEP, fade * 0.8f), Mode.STAIN, 4)
        if (t <= delay) {
            VoidDraw.ring(c, base, VoidDraw.GROUND_U, VoidDraw.GROUND_V, 2.1f, 2.4f, Palette.alpha(Palette.PINK, fade), Mode.RUNE, 2, rotation = t * 0.1f)
            VoidDraw.ring(c, base, VoidDraw.GROUND_U, VoidDraw.GROUND_V, 2.3f * warn * 0.75f, 2.3f * warn, Palette.alpha(Palette.MAGENTA, fade))
        }
        // Der Sprungstern wächst vom Mittelpunkt nach außen
        for ((index, branch) in cracks.withIndex()) {
            val shown = (1 + (branch.size * warn * 1.2f).toInt()).coerceAtMost(branch.size)
            val line = ArrayList<Vector3f>(shown + 1)
            line += Vector3f(base)
            for (i in 0 until shown) line += Vector3f(base).add(branch[i])
            VoidDraw.ribbon(c, line, width = { 0.13f * (1f - it * 0.6f) }, color = { Palette.alpha(Palette.PINK, fade) }, seed = index, mode = Mode.SKY_RIFT)
        }
    }

    override fun after(c: FxContext) {
        val t = age + c.tickDelta - delay
        if (t <= 0f || t > 12f) return
        sparks.draw(c, c.rel(ground).add(0f, 0.5f, 0f), t, 1f - t / 12f, Palette.PINK)
    }

    private companion object {
        const val SHATTER = 15f
    }
}

/** Inkasso-Kette zwischen zwei Entities, Glieder abwechselnd violett und magenta. */
class ChainEffect(private val fromId: Int, private val toId: Int, duration: Int) : VoidEffect(duration) {

    override fun before(c: FxContext) {
        val world = net.minecraft.client.MinecraftClient.getInstance().world ?: return
        val from = world.getEntityById(fromId) ?: return
        val to = world.getEntityById(toId) ?: return
        val a = c.rel(from.getLerpedPos(c.tickDelta).add(0.0, from.height * 0.62, 0.0))
        val b = c.rel(to.getLerpedPos(c.tickDelta).add(0.0, to.height * 0.55, 0.0))
        val time = age + c.tickDelta
        val fade = (1f - progress(c.tickDelta)).coerceAtLeast(0.35f)
        val links = (a.distance(b) * 1.6f).toInt().coerceIn(6, 60)
        val points = List(links + 1) { i ->
            val t = i.toFloat() / links
            val wobble = sin(t * PI.toFloat()) * 0.35f * sin(time * 0.5f + t * 9f)
            Vector3f(a).lerp(b, t).add(0f, wobble - sin(t * PI.toFloat()) * 0.5f, 0f)
        }
        VoidDraw.ribbon(
            c, points,
            width = { 0.05f },
            color = { t -> Palette.alpha(if ((t * links).toInt() % 2 == 0) Palette.VIOLET else Palette.MAGENTA, fade) },
        )
        val spin = time * 0.3f
        val (u, v) = VoidDraw.basis(Vector3f(sin(spin) * 0.4f, 1f, cos(spin) * 0.4f))
        VoidDraw.ring(c, b, u, v, 0.7f, 0.82f, Palette.alpha(Palette.MAGENTA, fade))
    }
}

/** Tilgungsstrahl: rotierende Balken knapp über dem Boden, mit Warnung vor dem Scharfwerden. */
class BeamEffect(
    private val origin: Vec3d,
    private val arms: Int,
    private val start: Double,
    private val turn: Double,
    private val length: Float,
    duration: Int,
    private val warmup: Int,
) : VoidEffect(duration) {

    override fun tick(world: ClientWorld) {
        super.tick(world)
        
    }

    override fun before(c: FxContext) {
        val t = age + c.tickDelta
        val base = c.rel(origin)
        val live = t >= warmup
        val fadeOut = ((life - t) / 8f).coerceIn(0f, 1f)
        val swept = start + turn * t
        for (arm in 0 until arms) {
            val angle = swept + arm * (PI * 2.0 / arms)
            val tip = Vector3f(base).add((cos(angle) * length).toFloat(), 0f, (sin(angle) * length).toFloat())
            val points = List(9) { i -> Vector3f(base).lerp(tip, i / 8f) }
            if (live) {
                VoidDraw.ribbon(c, points, width = { 0.2f }, color = { Palette.alpha(Palette.MAGENTA, fadeOut * 0.9f) }, seed = arm)
            } else {
                val blink = if ((t.toInt() / 3) % 2 == 0) 0.75f else 0.3f
                VoidDraw.ribbon(c, points, width = { 0.08f }, color = { Palette.alpha(Palette.PINK, blink) }, seed = arm)
            }
        }
        VoidDraw.ring(c, base, VoidDraw.GROUND_U, VoidDraw.GROUND_V, 1f, 1.3f, Palette.alpha(Palette.MAGENTA, fadeOut * 0.7f), Mode.RUNE, 7, rotation = t * 0.08f)
    }
}

/** Aufladung: Schalen und Lichtstriche ziehen sich auf einen Punkt zusammen. */
class ChargeEffect(private val center: Vec3d, duration: Int) : VoidEffect(duration) {
    private val seeds = Random(center.hashCode()).let { rnd ->
        List(6) { Vector3f(rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f).normalize() }
    }

    override fun tick(world: ClientWorld) {
        super.tick(world)
        VoidFxClient.shake(center, 0.03f + 0.2f * progress(0f), 30.0)
    }

    override fun before(c: FxContext) {
        val p = progress(c.tickDelta)
        val t = age + c.tickDelta
        val at = c.rel(center)
        val cycle = (t % 22f) / 22f
        val shell = Matrix4f().translate(at).scale(6f * (1f - cycle) + 0.4f)
        VoidDraw.mesh(c, VoidPipelines.ENERGY_LAYER, VoidMeshes.SPHERE, shell, Palette.alpha(Palette.VIOLET, cycle * 0.4f), Mode.SHELL, 9)
        VoidDraw.glow(c, at, 0.5f + p * 1.5f, Palette.alpha(Palette.PINK, 0.3f + p * 0.4f))
        for ((index, dir) in seeds.withIndex()) {
            val local = ((t * 0.05f + index * 0.17f) % 1f)
            val near = 6f * (1f - local)
            val far = near + 1.2f
            val a = Vector3f(at).add(Vector3f(dir).mul(near))
            val b = Vector3f(at).add(Vector3f(dir).mul(far))
            VoidDraw.ribbon(c, listOf(a, b), width = { 0.05f }, color = { Palette.alpha(Palette.MAGENTA, local) }, seed = index)
        }
    }
}

/** Himmelsschlag: Kreis am Boden, dann fährt ein Strahl aus dem Riss darüber herab. */
class SkyStrikeEffect(private val ground: Vec3d, private val delay: Int) : VoidEffect(delay + 14) {
    private val sparks = Sparks(10, 0.24f, ground.hashCode())
    private val tear = VoidTear.Shape(ground.hashCode())

    override fun tick(world: ClientWorld) {
        super.tick(world)
        if (age == delay) VoidFxClient.shake(ground, 0.45f, 30.0)
    }

    override fun before(c: FxContext) {
        val t = age + c.tickDelta
        val base = c.rel(ground).add(0f, 0.08f, 0f)
        val warn = (t / delay).coerceAtMost(1f)
        VoidDraw.disc(c, VoidPipelines.ENERGY_LAYER, base, VoidDraw.GROUND_U, VoidDraw.GROUND_V, 3f, Palette.alpha(Palette.DEEP, 0.7f), Mode.STAIN, 4)
        VoidDraw.ring(c, base, VoidDraw.GROUND_U, VoidDraw.GROUND_V, 2.7f, 3f, Palette.alpha(Palette.PINK, 1f), Mode.RUNE, 2, rotation = t * 0.12f)
        VoidDraw.ring(c, base, VoidDraw.GROUND_U, VoidDraw.GROUND_V, 2.9f * warn * 0.8f, 2.9f * warn, Palette.alpha(Palette.MAGENTA, 1f))

        val top = Vector3f(base).add(0f, 46f, 0f)

        val after = t - delay
        if (after in 0f..12f) {
            val fade = 1f - after / 12f
            val points = listOf(Vector3f(top), Vector3f(base).add(0f, 23f, 0f), Vector3f(base))
            VoidDraw.ribbon(c, points, width = { 1.1f * fade }, color = { Palette.alpha(Palette.MAGENTA, fade) })
            VoidDraw.ribbon(c, points, width = { 0.5f * fade }, color = { Palette.alpha(Palette.WHITE, fade) }, seed = 1)
        }
    }

    override fun after(c: FxContext) {
        val time = age + c.tickDelta
        val top = c.rel(ground).add(0f, 46.4f, 0f)
        val closing = ((life - time) / 6f).coerceIn(0f, 1f)
        VoidTear.draw(c, top, Vector3f(0.7f, 0f, 0.7f), Vector3f(-0.7f, 0f, 0.7f), 16f, 1.2f, easeOutBack((time / 8f).coerceAtMost(1f)) * closing, tear, 3, time, hot = if (time > delay) 1f else 0f)
        val after = time - delay
        if (after <= 0f) return
        val fade = (1f - after / 14f).coerceAtLeast(0f)
        val base = c.rel(ground).add(0f, 0.4f, 0f)
        VoidDraw.glow(c, base, 2.2f * fade, Palette.alpha(Palette.PINK, fade))
        sparks.draw(c, base, after, fade, Palette.PINK)
    }
}
