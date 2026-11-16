package dev.henny.hha_void.client.fx

import dev.henny.hha_void.boss.LeviathanCoreEntity
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.world.ClientWorld
import net.minecraft.util.math.Vec3d
import org.joml.Matrix4f
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Warnzone am Boden: dunkler Fleck, ein Runenring und eine Füllung, die bis zum Einschlag nach außen wächst.
 * Stil 0 = Gefahr (magenta), 1 = sicher (cyan, z. B. die Lücke in der Flutwelle), 2 = Geysir (pink, pulsiert).
 */
class ZoneEffect(private val ground: Vec3d, private val radius: Float, private val delay: Int, private val style: Int) :
    VoidEffect(delay + 10) {

    override fun before(c: FxContext) {
        val t = age + c.tickDelta
        val warn = (t / delay).coerceIn(0f, 1f)
        val fade = if (t > delay) (1f - (t - delay) / 10f).coerceIn(0f, 1f) else (t / 4f).coerceAtMost(1f)
        val base = c.rel(ground).add(0f, 0.08f, 0f)
        val color = when (style) { 1 -> Palette.CYAN; 2 -> Palette.PINK; else -> Palette.MAGENTA }
        VoidDraw.disc(c, VoidPipelines.ENERGY_LAYER, base, VoidDraw.GROUND_U, VoidDraw.GROUND_V, radius, Palette.alpha(Palette.DEEP, fade * 0.65f), Mode.STAIN, 5)
        VoidDraw.ring(c, base, VoidDraw.GROUND_U, VoidDraw.GROUND_V, radius * 0.92f, radius, Palette.alpha(color, fade), Mode.RUNE, 3, rotation = t * 0.05f)
        if (t <= delay) {
            val pulse = if (style == 2) 0.75f + 0.25f * sin(t * 0.8f) else 1f
            VoidDraw.ring(c, base, VoidDraw.GROUND_U, VoidDraw.GROUND_V, radius * warn * 0.8f, radius * warn, Palette.alpha(color, fade * 0.8f * pulse))
        } else {
            VoidDraw.glow(c, Vector3f(base).add(0f, 1f, 0f), radius * 0.5f, Palette.alpha(color, fade * 0.6f))
        }
    }
}

/**
 * Flutwelle aus Leere: eine Wand aus Lichtwürfeln, die quer über die Arena läuft. Wo die Lücken sind, fehlt sie;
 * deren Ränder leuchten cyan. Steht während [warmup] am Start und baut sich auf.
 */
class WaveEffect(
    private val start: Vec3d, private val step: Vec3d, private val gaps: Vec3d,
    private val halfLength: Float, private val height: Int, duration: Int, private val warmup: Int,
) : VoidEffect(duration) {
    private val dir = Vec3d(step.x, 0.0, step.z).normalize()
    private val across = Vec3d(-dir.z, 0.0, dir.x)
    private val seed = Random(start.hashCode()).nextInt(255)

    private fun front(t: Float): Vec3d = start.add(step.multiply(max(0f, t - warmup).toDouble()))

    private fun inGap(o: Double) = abs(o - gaps.x) < gaps.z || abs(o - gaps.y) < gaps.z

    override fun tick(world: ClientWorld) {
        super.tick(world)
        if (age > warmup && age % 3 == 0) VoidFxClient.shake(front(age.toFloat()), 0.12f, 40.0)
    }

    override fun before(c: FxContext) {
        val t = age + c.tickDelta
        val build = (t / warmup.coerceAtLeast(1)).coerceIn(0f, 1f)
        val fade = ((life - t) / 10f).coerceIn(0f, 1f)
        val f = front(t)
        val columns = (halfLength * 2 / 2.6f).toInt()
        for (i in 0..columns) {
            val o = -halfLength + i * 2.6
            if (inGap(o)) continue
            val swell = 0.7f + 0.3f * sin(t * 0.35f + i * 0.7f)
            val h = height * build * swell
            val foot = f.add(across.multiply(o))
            val a = c.rel(foot)
            val b = Vector3f(a).add(0f, h, 0f)
            // die Krone kippt nach vorn über
            val crest = Vector3f(b).add((dir.x * 1.6).toFloat(), -0.6f, (dir.z * 1.6).toFloat())
            VoidDraw.ribbon(
                c, listOf(a, b, crest),
                width = { 1.25f - 0.6f * it },
                color = { Palette.alpha(Palette.mix(Palette.DEEP, Palette.MAGENTA, it), fade * (0.55f + 0.45f * it)) },
                seed = seed + i,
            )
        }
        // Lückenränder
        for (g in doubleArrayOf(gaps.x - gaps.z, gaps.x + gaps.z, gaps.y - gaps.z, gaps.y + gaps.z)) {
            if (abs(g) > halfLength) continue
            val a = c.rel(f.add(across.multiply(g)))
            VoidDraw.ribbon(c, listOf(a, Vector3f(a).add(0f, height * 1.3f, 0f)), width = { 0.25f }, color = { Palette.alpha(Palette.CYAN, fade) }, seed = seed)
        }
    }
}

/** Rammspur: zwei leuchtende Kanten und laufende Pfeile entlang der Bahn, bis der Wal kommt. */
class LineEffect(private val from: Vec3d, private val to: Vec3d, private val halfWidth: Float, private val delay: Int) :
    VoidEffect(delay + 30) {
    private val dir = to.subtract(from).let { Vec3d(it.x, 0.0, it.z) }.normalize()
    private val across = Vec3d(-dir.z, 0.0, dir.x)
    private val length = Vec3d(to.x - from.x, 0.0, to.z - from.z).length()

    override fun before(c: FxContext) {
        val t = age + c.tickDelta
        val fade = if (t > delay) (1f - (t - delay) / 30f).coerceIn(0f, 1f) else (t / 6f).coerceAtMost(1f)
        val grow = (t / (delay * 0.4f)).coerceIn(0f, 1f)
        for (side in intArrayOf(-1, 1)) {
            val points = List(24) { i ->
                c.rel(from.add(dir.multiply(length * grow * i / 23.0)).add(across.multiply(side * halfWidth.toDouble()))).add(0f, 0.1f, 0f)
            }
            VoidDraw.ribbon(c, points, width = { 0.18f }, color = { Palette.alpha(Palette.MAGENTA, fade) }, seed = side + 3)
        }
        // Pfeile laufen in Angriffsrichtung
        val spacing = 9.0
        val shift = (t * 0.9) % spacing
        var s = shift
        while (s < length * grow) {
            val tip = from.add(dir.multiply(s))
            val l = tip.subtract(dir.multiply(3.0)).add(across.multiply(halfWidth * 0.6))
            val r = tip.subtract(dir.multiply(3.0)).subtract(across.multiply(halfWidth * 0.6))
            val pts = listOf(c.rel(l), c.rel(tip), c.rel(r)).map { it.add(0f, 0.12f, 0f) }
            VoidDraw.ribbon(c, pts, width = { 0.22f }, color = { Palette.alpha(Palette.PINK, fade * 0.9f) }, seed = s.toInt())
            s += spacing
        }
    }
}

/** Sternschnuppen: fallen schräg aus großer Höhe über [center]. */
class StarfallEffect(private val center: Vec3d, private val radius: Float, private val count: Int, duration: Int) : VoidEffect(duration) {
    private val rnd = Random(center.hashCode())

    override fun tick(world: ClientWorld) {
        super.tick(world)
        val perTick = (count / life.toFloat()).let { if (it < 1f) (if (rnd.nextFloat() < it) 1 else 0) else it.toInt() }
        repeat(perTick) {
            val a = rnd.nextFloat() * PI.toFloat() * 2f
            val r = sqrt(rnd.nextFloat()) * radius
            val top = center.add(cos(a) * r.toDouble(), 70.0 + rnd.nextDouble() * 40.0, sin(a) * r.toDouble())
            val slant = Vec3d(cos(a + 1.2) * 18.0, -34.0 - rnd.nextDouble() * 20.0, sin(a + 1.2) * 18.0)
            VoidFxClient.spawn(StreakEffect(top, top.add(slant)))
        }
    }
}

/**
 * Bildschirm-Überblendungen über dem ganzen HUD (auch während Cutscenes): Schwarzblende, Blitz, Puls, Bauch-Vignette.
 */
object VoidScreen {
    private var mode = -1
    private var age = 0
    private var a = 0
    private var b = 0
    private var bellyTicks = 0

    fun start(mode: Int, a: Int, b: Int) {
        if (mode == 4) {
            bellyTicks = a
            return
        }
        this.mode = mode
        this.age = 0
        this.a = a.coerceAtLeast(1)
        this.b = b
    }

    fun tick() {
        if (mode >= 0) age++
        if (bellyTicks > 0) bellyTicks--
        val end = when (mode) { 0 -> a + b + 20; 1 -> a; 2 -> a; 3 -> a; else -> 0 }
        if (mode >= 0 && age > end) mode = -1
    }

    fun clear() {
        mode = -1
        bellyTicks = 0
    }

    /** Gerade schwarz (für Kamera/Musik)? */
    fun isBlack(): Boolean = mode == 0 && age >= a && age <= a + b

    @JvmStatic
    fun render(context: DrawContext, tickProgress: Float) {
        val w = context.scaledWindowWidth
        val h = context.scaledWindowHeight
        if (bellyTicks > 0) {
            val t = (MinecraftClient.getInstance().world?.time ?: 0L) + tickProgress
            val pulse = 0.55f + 0.2f * sin(t * 0.18f) + 0.1f * sin(t * 0.47f)
            val fade = (bellyTicks / 20f).coerceAtMost(1f)
            val edge = (h * 0.22f).toInt()
            for (i in 0 until 8) {
                val k = 1f - i / 8f
                val alpha = (k * k * pulse * fade * 150f).toInt().coerceIn(0, 255)
                val col = (alpha shl 24) or 0x3A0614
                val inset = edge * i / 8
                context.fill(0, inset, w, inset + edge / 8 + 1, col)
                context.fill(0, h - inset - edge / 8 - 1, w, h - inset, col)
                context.fill(inset, 0, inset + edge / 8 + 1, h, col)
                context.fill(w - inset - edge / 8 - 1, 0, w - inset, h, col)
            }
        }
        if (mode < 0) return
        val t = age + tickProgress
        val alpha: Float
        val rgb: Int
        when (mode) {
            0 -> {
                rgb = 0x000000
                alpha = when {
                    t < a -> t / a
                    t < a + b -> 1f
                    else -> 1f - (t - a - b) / 20f
                }
            }
            1 -> { rgb = 0xF6E8FF; alpha = (1f - t / a) * 0.85f }
            2 -> { rgb = 0x8A0A2A; alpha = sin((t / a).coerceIn(0f, 1f) * PI.toFloat()) * 0.45f }
            else -> { rgb = 0x000000; alpha = 1f - t / a }
        }
        val v = (alpha.coerceIn(0f, 1f) * 255f).toInt()
        if (v > 0) context.fill(0, 0, w, h, (v shl 24) or rgb)
    }
}

/** Kerne im Bauch: Lichtkranz und Runenring um den Kern (das Modell zeichnet `CoreRenderer` in client/boss/LeviathanClient.kt). */
object CoreFx {

    private inline fun forEachCore(client: MinecraftClient, action: (LeviathanCoreEntity) -> Unit) {
        val world = client.world ?: return
        for (entity in world.entities) if (entity is LeviathanCoreEntity && entity.isAlive) action(entity)
    }

    fun glow(client: MinecraftClient, c: FxContext) {
        forEachCore(client) { core ->
            val time = core.age + c.tickDelta
            // derselbe Herzschlag wie das Modell
            val beat = dev.henny.hha_void.client.boss.coreBeat(time + (core.id % 50) * 3.1f, (core.health / core.maxHealth).coerceIn(0f, 1f))
            val at = c.rel(core.getLerpedPos(c.tickDelta)).add(0f, 1.7f, 0f)
            VoidDraw.glow(c, at, 2.6f + beat * 1.2f, Palette.alpha(Palette.PINK, 0.35f + 0.3f * beat), core.id)
            VoidDraw.ring(c, Vector3f(at).add(0f, -1.6f, 0f), VoidDraw.GROUND_U, VoidDraw.GROUND_V, 2.2f, 2.8f + beat, Palette.alpha(Palette.MAGENTA, 0.5f), Mode.RUNE, 2, rotation = time * 0.04f)
        }
    }
}
