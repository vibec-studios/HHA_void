package dev.henny.hha_void.client.fx

import dev.henny.hha_void.boss.VoidBossEntity
import net.minecraft.client.MinecraftClient
import net.minecraft.client.world.ClientWorld
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvents
import net.minecraft.util.math.Vec3d
import org.joml.Matrix4f
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/** Gemeinsames Aussehen aller Risse: Rand aus Glut, Kern aus Nichts, Sprünge zur Seite. */
object VoidTear {

    class Shape(seed: Int, points: Int = 15) {
        private val rnd = Random(seed)
        val zigzag = FloatArray(points) { i -> (if (i % 2 == 0) 1f else -1f) * (0.2f + rnd.nextFloat() * 0.8f) }
        val branches = List(4) {
            val at = 2 + rnd.nextInt(points - 4)
            val side = if (rnd.nextBoolean()) 1f else -1f
            Triple(at, side, 0.1f + rnd.nextFloat() * 0.12f)
        }
    }

    private fun profile(k: Float) = sin(k * PI.toFloat()).coerceAtLeast(0f).pow(0.6f)

    /**
     * Zeichnet einen Riss um [center] herum. [open] läuft von 0 (geschlossener Strich) bis 1,
     * [hot] hellt den Rand auf, etwa wenn gerade etwas herauskommt.
     */
    fun draw(
        c: FxContext, center: Vector3f, along: Vector3f, across: Vector3f,
        length: Float, width: Float, open: Float, shape: Shape, seed: Int, time: Float,
        alpha: Float = 1f, hot: Float = 0f,
    ) {
        if (open <= 0.01f || alpha <= 0.01f) return
        val count = shape.zigzag.size
        val shownLength = length * (0.35f + 0.65f * open)
        val points = List(count) { i ->
            val k = i / (count - 1f) - 0.5f
            val crawl = sin(time * 0.17f + i * 1.9f) * 0.012f * length
            val side = shape.zigzag[i] * 0.07f * length * open + crawl
            Vector3f(center).add(Vector3f(along).mul(k * shownLength)).add(Vector3f(across).mul(side))
        }
        val w = width * open
        val flicker = 0.85f + sin(time * 0.5f + seed) * 0.15f
        val rim = Palette.mix(Palette.MAGENTA, Palette.WHITE, hot * 0.5f)
        val edge = Palette.mix(Palette.PINK, Palette.WHITE, hot * 0.7f)
        VoidDraw.ribbon(c, points, width = { w * profile(it) * 1.5f + 0.08f }, color = { Palette.alpha(rim, alpha * flicker * 0.8f) }, seed = seed, mode = Mode.SKY_RIFT)
        VoidDraw.ribbon(c, points, width = { w * profile(it) * 1.05f + 0.05f }, color = { Palette.alpha(edge, alpha) }, seed = seed, mode = Mode.SKY_RIFT)
        VoidDraw.ribbon(c, points, width = { w * profile(it) * 0.7f }, color = { Palette.alpha(0x07020F, alpha) }, seed = seed, mode = Mode.SKY_RIFT)

        // Haarrisse, die vom Hauptriss in die Welt laufen
        for ((at, side, reach) in shape.branches) {
            val base = points[at]
            val slant = (at - count / 2) * 0.02f
            val branch = List(4) { j ->
                val k = j / 3f
                Vector3f(base)
                    .add(Vector3f(across).mul(side * k * reach * length * open))
                    .add(Vector3f(along).mul(k * slant * length + (if (j % 2 == 0) 0f else 0.012f * length)))
            }
            VoidDraw.ribbon(c, branch, width = { (w * 0.16f + 0.05f) * (1f - it * 0.8f) }, color = { Palette.alpha(edge, alpha * flicker) }, seed = seed + at, mode = Mode.SKY_RIFT)
        }
    }

    /** Zwei zueinander senkrechte Achsen: [along] folgt dem Riss, die andere steht quer dazu. */
    fun across(along: Vector3f): Vector3f {
        val up = if (abs(along.y) > 0.9f) Vector3f(1f, 0f, 0f) else Vector3f(0f, 1f, 0f)
        return Vector3f(along).cross(up).normalize()
    }
}

/** Riss in der Welt: öffnet sich ruckartig, saugt Funken ein, schnappt am Ende zu. */
class RiftEffect(
    private val center: Vec3d, direction: Vec3d, private val length: Float, life: Int, private val seed: Int,
) : VoidEffect(life) {
    private val shape = VoidTear.Shape(seed)
    private val along = Vector3f(direction.x.toFloat(), direction.y.toFloat(), direction.z.toFloat()).normalize()
    private val across = VoidTear.across(along)
    private val motes = Random(seed + 7).let { rnd ->
        List(10) { Vector3f(rnd.nextFloat() * 6.28f, rnd.nextFloat() * 6.28f, rnd.nextFloat()) }
    }

    private fun openness(t: Float): Float {
        val opening = easeOutBack((t / 6f).coerceAtMost(1f))
        val closing = ((life - t) / 5f).coerceIn(0f, 1f)
        return opening * closing * closing
    }

    override fun tick(world: ClientWorld) {
        super.tick(world)
        if (age == 1) {
            VoidFxClient.shake(center, 0.12f * length.coerceAtMost(10f) / 3f, 26.0)
            world.playSoundClient(center.x, center.y, center.z, SoundEvents.BLOCK_AMETHYST_BLOCK_RESONATE, SoundCategory.HOSTILE, 1.2f, 0.5f, false)
        }
    }

    override fun solid(c: FxContext) {
        val t = age + c.tickDelta
        val open = openness(t)
        if (open <= 0.05f) return
        val base = c.rel(center)
        // Abgebrochene Kristalle an den Lippen des Risses
        for (i in 0 until 5) {
            val k = (i + 1) / 6f - 0.5f
            val side = if (i % 2 == 0) 1f else -1f
            val at = Vector3f(base).add(Vector3f(along).mul(k * length * 0.8f)).add(Vector3f(across).mul(side * length * 0.05f * open))
            val point = Vector3f(across).mul(side).add(Vector3f(along).mul(k))
            val size = length * 0.1f * open * (1f - abs(k))
            val matrix = Matrix4f().translate(at).rotate(VoidDraw.alignY(point.normalize())).scale(size * 2.2f, size * 2.4f, size * 2.2f)
            VoidDraw.mesh(c, VoidPipelines.SOLID_LAYER, VoidMeshes.CRYSTALS[(seed + i).mod(VoidMeshes.CRYSTALS.size)], matrix, Palette.alpha(Palette.MAGENTA, 1f), 1, i)
        }
    }

    override fun after(c: FxContext) {
        val t = age + c.tickDelta
        val open = openness(t)
        val base = c.rel(center)
        val flare = (1f - t / 8f).coerceAtLeast(0f)
        VoidTear.draw(c, base, along, across, length, length * 0.085f, open, shape, seed, t, hot = flare)

        // Lose Funken werden hineingezogen
        for ((index, mote) in motes.withIndex()) {
            val cycle = (t * 0.04f + mote.z) % 1f
            val distance = (1f - cycle) * length * 0.55f
            val from = Vector3f(cos(mote.x) * distance, sin(mote.y) * distance * 0.6f, sin(mote.x) * distance)
            val to = Vector3f(from).mul(0.86f)
            VoidDraw.ribbon(
                c, listOf(Vector3f(base).add(from), Vector3f(base).add(to)),
                width = { 0.05f }, color = { Palette.alpha(Palette.PINK, open * cycle) }, seed = index,
            )
        }
    }
}

/** Glühender Sprung im Boden, der sich von [from] nach [to] durchfrisst. */
class GroundCrackEffect(
    private val from: Vec3d, private val to: Vec3d, private val runTicks: Int, life: Int, seed: Int,
) : VoidEffect(life) {
    private val points: List<Vec3d> = Random(seed).let { rnd ->
        val delta = to.subtract(from)
        val side = Vec3d(-delta.z, 0.0, delta.x).normalize()
        List(CRACK_POINTS) { i ->
            val k = i / (CRACK_POINTS - 1.0)
            val swing = if (i == 0 || i == CRACK_POINTS - 1) 0.0 else (rnd.nextDouble() - 0.5) * delta.length() * 0.16
            from.add(delta.multiply(k)).add(side.multiply(swing))
        }
    }

    override fun before(c: FxContext) {
        val t = age + c.tickDelta
        val run = (t / runTicks.coerceAtLeast(1)).coerceAtMost(1f)
        val fade = ((life - t) / 14f).coerceIn(0f, 1f)
        val shown = (CRACK_POINTS * run).toInt().coerceIn(2, CRACK_POINTS)
        val list = points.subList(0, shown).map { c.rel(it).add(0f, 0.03f, 0f) }
        val heat = (1f - t / (runTicks + 12f)).coerceIn(0f, 1f)
        VoidDraw.ribbon(c, list, width = { 0.11f * (1f - it * 0.5f) * fade }, color = { Palette.alpha(Palette.mix(Palette.MAGENTA, Palette.WHITE, heat * 0.6f), fade) }, mode = Mode.SKY_RIFT)
        VoidDraw.ribbon(c, list, width = { 0.05f * (1f - it * 0.5f) * fade }, color = { Palette.alpha(0x07020F, fade) }, mode = Mode.SKY_RIFT)
    }

    private companion object {
        const val CRACK_POINTS = 12
    }
}

/** Rissgriff: Über dem Ziel reißt die Luft auf, Vaels eigene Faust schlägt daraus herab. */
class RiftFistEffect(private val ground: Vec3d, private val delay: Int, private val height: Float) : VoidEffect(delay + 22) {
    private val seed = ground.hashCode()
    private val shape = VoidTear.Shape(seed)

    /** 0 = oben im Riss, 1 = am Boden. */
    private fun reach(t: Float): Float {
        val after = t - delay
        if (after <= 0f) return 0f
        val down = (after / 3f).coerceAtMost(1f)
        val up = ((after - 11f) / 7f).coerceIn(0f, 1f)
        return down * down * (1f - up * up)
    }

    override fun tick(world: ClientWorld) {
        super.tick(world)
        if (age == delay + 3) {
            VoidFxClient.shake(ground, 0.75f, 30.0)
            VoidFxClient.spawn(ImpactEffect(ground.add(0.0, 0.2, 0.0), 3.4f))
            for (i in 0 until 6) {
                val a = i * (PI * 2.0 / 6.0) + seed
                VoidFxClient.spawn(GroundCrackEffect(ground, ground.add(cos(a) * 7.0, 0.0, sin(a) * 7.0), 5, 46, seed + i))
            }
        }
    }

    override fun solid(c: FxContext) {
        val t = age + c.tickDelta
        val k = reach(t)
        if (k <= 0f) return
        val top = c.rel(ground).add(0f, height, 0f)
        val fistLength = 3.4f
        val tipY = height - (height - fistLength * 0.75f) * k
        val fistAt = c.rel(ground).add(0f, tipY, 0f)
        val fist = Matrix4f().translate(fistAt).rotateY(seed * 0.7f).scale(2.6f, fistLength, 2.6f)
        VoidDraw.mesh(c, VoidPipelines.SOLID_LAYER, VoidMeshes.FIST, fist, 0, 2, seed)
        val forearmBottom = fistAt.y + fistLength * 0.3f
        val forearmLength = top.y + 0.5f - forearmBottom
        if (forearmLength > 0.2f) {
            val arm = Matrix4f().translate(fistAt.x, forearmBottom, fistAt.z).rotateY(seed * 0.7f).scale(2f, forearmLength, 2f)
            VoidDraw.mesh(c, VoidPipelines.SOLID_LAYER, VoidMeshes.FOREARM, arm, 0, 2, seed)
        }
    }

    override fun before(c: FxContext) {
        val t = age + c.tickDelta
        if (t > delay + 4f) return
        val base = c.rel(ground).add(0f, 0.08f, 0f)
        val warn = (t / delay).coerceAtMost(1f)
        VoidDraw.disc(c, VoidPipelines.ENERGY_LAYER, base, VoidDraw.GROUND_U, VoidDraw.GROUND_V, 3.8f, Palette.alpha(Palette.DEEP, 0.65f), Mode.STAIN, 4)
        VoidDraw.ring(c, base, VoidDraw.GROUND_U, VoidDraw.GROUND_V, 3.5f, 3.8f, Palette.alpha(Palette.PINK, 1f), Mode.RUNE, 2, rotation = t * 0.12f)
        VoidDraw.ring(c, base, VoidDraw.GROUND_U, VoidDraw.GROUND_V, 3.7f * warn * 0.8f, 3.7f * warn, Palette.alpha(Palette.MAGENTA, 1f))
    }

    override fun after(c: FxContext) {
        val t = age + c.tickDelta
        val closing = ((life - t) / 5f).coerceIn(0f, 1f)
        val open = easeOutBack((t / 7f).coerceAtMost(1f)) * closing
        val top = c.rel(ground).add(0f, height, 0f)
        val hot = if (t > delay - 2f && t < delay + 8f) 1f else 0f
        VoidTear.draw(c, top, TEAR_ALONG, TEAR_ACROSS, 15f, 1.5f, open, shape, seed, t, hot = hot)
    }

    private companion object {
        val TEAR_ALONG = Vector3f(0.8f, 0f, 0.6f)
        val TEAR_ACROSS = Vector3f(-0.6f, 0f, 0.8f)
    }
}

/** Rissregen: Ein Felsbrocken fällt aus einem Spalt im Himmel. */
class RiftRockEffect(private val ground: Vec3d, private val delay: Int) : VoidEffect(delay + 8) {
    private val seed = ground.hashCode()
    private val mesh = VoidMeshes.ROCKS[seed.mod(VoidMeshes.ROCKS.size)]
    private val shape = VoidTear.Shape(seed)

    private fun heightAt(t: Float): Float {
        val k = (t / delay).coerceIn(0f, 1f)
        return FALL_HEIGHT * (1f - k * k)
    }

    override fun tick(world: ClientWorld) {
        super.tick(world)
        if (age == delay) {
            VoidFxClient.shake(ground, 0.3f, 24.0)
            VoidFxClient.spawn(ImpactEffect(ground.add(0.0, 0.2, 0.0), 2f))
            VoidFxClient.spawn(BurstEffect(ground.add(0.0, 0.4, 0.0), 0.8f))
        }
    }

    override fun solid(c: FxContext) {
        val t = age + c.tickDelta
        if (t > delay) return
        val at = c.rel(ground).add(0f, heightAt(t) + 0.8f, 0f)
        val matrix = Matrix4f().translate(at).rotateY(t * 0.3f).rotateX(t * 0.2f).scale(1.15f)
        VoidDraw.mesh(c, VoidPipelines.SOLID_LAYER, mesh, matrix, 0, 0, seed)
    }

    override fun before(c: FxContext) {
        val t = age + c.tickDelta
        if (t > delay) return
        val base = c.rel(ground).add(0f, 0.08f, 0f)
        val warn = (t / delay).coerceAtMost(1f)
        VoidDraw.ring(c, base, VoidDraw.GROUND_U, VoidDraw.GROUND_V, 2.2f, 2.5f, Palette.alpha(Palette.PINK, 0.9f), Mode.RUNE, 2, rotation = t * 0.12f)
        VoidDraw.ring(c, base, VoidDraw.GROUND_U, VoidDraw.GROUND_V, 2.4f * warn * 0.75f, 2.4f * warn, Palette.alpha(Palette.MAGENTA, 1f))
        val rock = c.rel(ground).add(0f, heightAt(t) + 0.8f, 0f)
        val tail = Vector3f(rock).add(0f, 4f, 0f)
        VoidDraw.ribbon(c, listOf(tail, rock), width = { 0.1f + it * 0.5f }, color = { Palette.alpha(Palette.MAGENTA, it * 0.8f) }, seed = 2)
    }

    override fun after(c: FxContext) {
        val t = age + c.tickDelta
        val closing = ((delay + 8f - t) / 10f).coerceIn(0f, 1f)
        val top = c.rel(ground).add(0f, FALL_HEIGHT + 1f, 0f)
        VoidTear.draw(c, top, TEAR_ALONG, TEAR_ACROSS, 8f, 0.7f, easeOutBack((t / 5f).coerceAtMost(1f)) * closing, shape, seed, t)
    }

    private companion object {
        const val FALL_HEIGHT = 30f
        val TEAR_ALONG = Vector3f(0.6f, 0f, -0.8f)
        val TEAR_ACROSS = Vector3f(0.8f, 0f, 0.6f)
    }
}

/** Die Risse geben ihm alles zurück: Strahlen fahren aus dem Himmel in seine Trümmer. */
class SkyBeamsEffect(private val center: Vec3d, life: Int) : VoidEffect(life) {
    private val roots = List(BEAMS) { i ->
        val angle = i * (PI * 2.0 / BEAMS).toFloat() + 0.4f
        Vector3f(cos(angle) * 38f, 72f + (i % 3) * 8f, sin(angle) * 38f)
    }
    private val rocks = Random(center.hashCode()).let { rnd ->
        List(10) { Vector3f(rnd.nextFloat() * 6.28f, 4f + rnd.nextFloat() * 7f, rnd.nextFloat()) }
    }

    override fun tick(world: ClientWorld) {
        super.tick(world)
        VoidFxClient.shake(center, 0.18f, 40.0)
    }

    override fun solid(c: FxContext) {
        val t = age + c.tickDelta
        val base = c.rel(center)
        val fade = (t / 12f).coerceAtMost(1f) * ((life - t) / 12f).coerceIn(0f, 1f)
        // Lose Brocken heben ab und werden zu ihm zurückgezogen
        for ((index, rock) in rocks.withIndex()) {
            val cycle = (t * 0.018f + rock.z) % 1f
            val radius = rock.y * (1f - cycle)
            val at = Vector3f(base).add(cos(rock.x + cycle * 3f) * radius, cycle * 5f + 0.4f, sin(rock.x + cycle * 3f) * radius)
            val matrix = Matrix4f().translate(at).rotateY(t * 0.2f + index).rotateX(t * 0.15f).scale(0.5f * fade)
            VoidDraw.mesh(c, VoidPipelines.SOLID_LAYER, VoidMeshes.ROCKS[index % VoidMeshes.ROCKS.size], matrix, 0, 0, index)
        }
    }

    override fun after(c: FxContext) {
        val t = age + c.tickDelta
        val base = c.rel(center).add(0f, 1.2f, 0f)
        val grow = (t / 16f).coerceAtMost(1f)
        val fade = ((life - t) / 12f).coerceIn(0f, 1f)
        val roll = (t / 2f).toInt()
        for ((index, root) in roots.withIndex()) {
            if (t < index * 3f) continue
            val rnd = Random(index * 31 + roll * 7)
            val top = Vector3f(base).add(root)
            val points = List(8) { i ->
                val k = i / 7f
                val spread = if (i == 0 || i == 7) 0f else 2.4f
                Vector3f(top).lerp(base, k * grow).add((rnd.nextFloat() - 0.5f) * spread, 0f, (rnd.nextFloat() - 0.5f) * spread)
            }
            VoidDraw.ribbon(c, points, width = { 0.34f * fade }, color = { Palette.alpha(Palette.MAGENTA, 0.75f * fade) }, seed = index, mode = Mode.SKY_RIFT)
            VoidDraw.ribbon(c, points, width = { 0.13f * fade }, color = { Palette.alpha(Palette.WHITE, fade) }, seed = index, mode = Mode.SKY_RIFT)
        }
        VoidDraw.glow(c, base, 2.2f * grow * fade, Palette.alpha(Palette.PINK, fade * 0.7f), 3)
    }

    private companion object {
        const val BEAMS = 6
    }
}

/**
 * Der Himmel über dem Kampf. Risse gehören zum ganzen Kampf: In Phase II tauchen die ersten
 * auf, in III werden es mehr, beim falschen Tod schließen sie sich (der Sieg soll echt wirken),
 * in Phase IV reißt alles auf, und je weniger Leben er hat, desto weiter klaffen sie.
 */
class SkyRiftEffect(private val bossId: Int, private val onEnd: () -> Unit) : VoidEffect(Int.MAX_VALUE) {

    private class Rift(val direction: Vector3f, val along: Vector3f, val across: Vector3f, val length: Float, val width: Float, val shape: VoidTear.Shape) {
        var open = 0f
        var flare = 0f
    }

    private val rifts = Random(bossId).let { rnd ->
        List(RIFT_COUNT) { index ->
            // Die ersten Risse stehen weit oben, damit man sie während des Kampfs sieht
            val yaw = index * 2.4f + rnd.nextFloat() * 0.7f
            val tilt = if (index < 5) 0.9f + rnd.nextFloat() * 0.45f else 0.25f + rnd.nextFloat() * 1f
            val dir = Vector3f(cos(yaw) * cos(tilt), sin(tilt), sin(yaw) * cos(tilt))
            val (u, v) = VoidDraw.basis(dir)
            val heading = rnd.nextFloat() * (PI * 2.0).toFloat()
            val along = Vector3f(u).mul(cos(heading)).add(Vector3f(v).mul(sin(heading)))
            val across = Vector3f(u).mul(-sin(heading)).add(Vector3f(v).mul(cos(heading)))
            Rift(dir, along, across, 55f + rnd.nextFloat() * 70f, 2f + rnd.nextFloat() * 2.2f, VoidTear.Shape(bossId + index * 11))
        }
    }
    private var dome = 0f
    private var flash = 0f
    private var healthGap = 0f
    private var done = false
    private var deadFor = 0
    private val shards = Random(bossId + 1).let { rnd ->
        List(8) { Vector3f(rnd.nextFloat() * 6.28f, 0.2f + rnd.nextFloat() * 0.8f, rnd.nextFloat()) }
    }

    override val finished: Boolean get() = done

    /** Ein Angriff kam aus dem Himmel: ein Riss flammt auf, der Himmel zuckt. */
    fun pulse() {
        flash = 1f
        val open = rifts.filter { it.open > 0.5f }
        if (open.isNotEmpty()) open[(age * 7).mod(open.size)].flare = 1f
    }

    override fun tick(world: ClientWorld) {
        super.tick(world)
        val boss = world.getEntityById(bossId) as? VoidBossEntity
        val phase = boss?.bossPhase() ?: 0
        val dying = boss == null || boss.isRemoved || boss.isDead
        if (dying) deadFor++
        val wanted = when {
            dying -> 0
            phase >= 3 -> RIFT_COUNT
            phase == 2 -> 4
            else -> 0
        }
        for ((index, rift) in rifts.withIndex()) {
            // Beim Aufreißen springen sie schnell, beim Schließen eilt keiner: einer nach dem anderen
            val goal = if (index < wanted) 1f else 0f
            val allowed = !dying || deadFor > 30 + index * 5
            if (goal > rift.open) {
                rift.open = (rift.open + 0.05f).coerceAtMost(1f)
            } else if (allowed) {
                rift.open = (rift.open - 0.035f).coerceAtLeast(0f)
            }
            rift.flare = (rift.flare - 0.08f).coerceAtLeast(0f)
        }
        val domeGoal = if (!dying && phase >= 3) 1f else 0f
        if (domeGoal > dome) {
            dome = (dome + 0.02f).coerceAtMost(1f)
        } else if (!dying || deadFor > 40) {
            dome = (dome - 0.012f).coerceAtLeast(0f)
        }
        val hurt = if (boss != null && phase >= 3 && !dying) 1f - boss.health / boss.maxHealth else 0f
        healthGap += (hurt - healthGap) * 0.05f

        flash = (flash - 0.12f).coerceAtLeast(0f)
        if (dome > 0.5f && world.random.nextInt(60) == 0) pulse()
        if (flash >= 1f && dome > 0.1f) {
            val player = MinecraftClient.getInstance().player
            if (player != null) {
                world.playSound(player, player.x, player.y + 40.0, player.z, SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.WEATHER, 0.6f, 0.6f)
            }
        }
        if (dying && dome <= 0f && rifts.all { it.open <= 0f }) {
            done = true
            onEnd()
        }
    }

    override fun after(c: FxContext) {
        val t = age + c.tickDelta
        if (dome > 0.003f) {
            val matrix = Matrix4f().scale(SKY_RADIUS)
            val tint = Palette.mix(Palette.DEEP, Palette.MAGENTA, flash * 0.6f)
            VoidDraw.mesh(c, VoidPipelines.ENERGY_LAYER, VoidMeshes.SKY_DOME, matrix, Palette.alpha(tint, dome * (0.55f + flash * 0.25f)), Mode.SKY_DOME, 0)
        }

        val spread = 1f + healthGap * 0.8f
        for ((index, rift) in rifts.withIndex()) {
            if (rift.open <= 0.01f) continue
            val center = Vector3f(rift.direction).mul(SKY_RADIUS * 0.96f)
            val open = easeOutBack(rift.open)
            VoidTear.draw(
                c, center, rift.along, rift.across, rift.length, rift.width * spread * (1f + flash * 0.2f),
                open, rift.shape, index, t, alpha = rift.open.coerceAtMost(1f), hot = rift.flare,
            )
        }

        // Aus den offenen Rissen fällt Glut
        if (dome > 0.1f) {
            for ((index, shard) in shards.withIndex()) {
                val cycle = (t * 0.012f + shard.z) % 1f
                val yaw = shard.x
                val height = 0.95f - cycle * 0.8f
                val pos = Vector3f(cos(yaw + cycle * 0.25f), height, sin(yaw + cycle * 0.25f)).normalize().mul(SKY_RADIUS * 0.94f)
                val tail = Vector3f(cos(yaw + cycle * 0.25f - 0.02f), height + 0.1f, sin(yaw + cycle * 0.25f - 0.02f)).normalize().mul(SKY_RADIUS * 0.94f)
                VoidDraw.ribbon(c, listOf(tail, pos), width = { 0.2f + it * 0.55f }, color = { Palette.alpha(Palette.PINK, dome * it) }, seed = index, mode = Mode.SKY_RIFT)
            }
        }
    }

    private companion object {
        const val RIFT_COUNT = 14
        const val SKY_RADIUS = 150f
    }
}
