package dev.henny.hha_void.client.fx

import dev.henny.hha_void.boss.VoidBossEntity
import net.minecraft.block.ShapeContext
import net.minecraft.client.MinecraftClient
import net.minecraft.client.world.ClientWorld
import net.minecraft.util.hit.HitResult
import net.minecraft.util.math.Vec3d
import net.minecraft.world.RaycastContext
import org.joml.Matrix4f
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

// Leviathan, Runde 3: Sternenatem, Harpunen, Sektor-Eruption (Server: VoidVisuals.starBreath / harpoon / sector)

/** Mitte der Maulöffnung des Wals [bossId] im aktuellen Bild (zwischen den Ticks interpoliert). */
private fun bossMouth(bossId: Int, tickDelta: Float): Vec3d? {
    val boss = MinecraftClient.getInstance().world?.getEntityById(bossId) as? VoidBossEntity ?: return null
    val now = boss.clientFrame?.mouth() ?: return null
    val before = boss.clientPrevFrame?.mouth() ?: now
    return before.lerp(now, tickDelta.toDouble())
}

/**
 * Sternenatem: Aufladen (Glut im Maul, Funken strömen hinein, flackernde Bahn am Boden mit Zielring), dann ein dicker,
 * glänzender Strahl aus dem Maul, der über die Bahn fegt (Phase III hin und zurück) und an Deckung endet. Wo er
 * auftrifft, glüht eine Brandspur nach. Gleiche Bahn-Formel wie der Server (`VoidBossEntity.breathPoint`).
 */
class StarBreathEffect(
    private val bossId: Int, private val from: Vec3d, private val to: Vec3d,
    private val charge: Int, private val fire: Int, private val passes: Int, private val width: Float,
) : VoidEffect(charge + fire + 75) {
    private val trail = ArrayList<Pair<Vec3d, Int>>()
    private var lastMouth: Vec3d? = null
    private val rnd = Random(from.hashCode())
    private val sparks = List(10) { Vector3f(rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f).normalize() }

    private fun ground(k: Float): Vec3d {
        val half = fire.toFloat() / passes
        var u = (k / half).coerceIn(0f, passes.toFloat())
        if (u > 1f) u = 2f - u
        val e = u * u * (3 - 2 * u)
        return from.lerp(to, e.toDouble())
    }

    private fun end(world: ClientWorld, mouth: Vec3d, g: Vec3d): Vec3d {
        val hit = world.raycast(RaycastContext(mouth, g, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, ShapeContext.absent()))
        return if (hit.type == HitResult.Type.MISS) g else hit.pos
    }

    override fun tick(world: ClientWorld) {
        super.tick(world)
        val k = age - charge
        if (k in 0 until fire) {
            val m = bossMouth(bossId, 1f) ?: lastMouth
            if (m != null) {
                val e = end(world, m, ground(k.toFloat()).add(0.0, 0.2, 0.0))
                if (k % 2 == 0) trail += e to age
                VoidFxClient.shake(e, 0.35f, 22.0)
            }
        } else if (age < charge) {
            bossMouth(bossId, 1f)?.let { VoidFxClient.shake(it, 0.05f + 0.15f * age / charge, 40.0) }
        }
        trail.removeIf { age - it.second > 70 }
    }

    override fun before(c: FxContext) {
        val world = MinecraftClient.getInstance().world ?: return
        val t = age + c.tickDelta
        val mouth = bossMouth(bossId, c.tickDelta) ?: lastMouth ?: return
        lastMouth = mouth
        val m = c.rel(mouth)
        // ---- Brandspur
        if (trail.size >= 2) {
            val pts = trail.map { c.rel(it.first).add(0f, 0.12f, 0f) }
            val ages = trail.map { it.second }
            VoidDraw.ribbon(c, pts, width = { 0.55f }, color = { k ->
                val i = (k * (ages.size - 1)).toInt().coerceIn(0, ages.size - 1)
                val heat = (1f - (age - ages[i]) / 70f).coerceIn(0f, 1f)
                Palette.alpha(Palette.mix(Palette.DEEP, Palette.PINK, heat), 0.25f + 0.7f * heat)
            }, seed = 5)
            VoidDraw.ribbon(c, pts, width = { 0.2f }, color = { k ->
                val i = (k * (ages.size - 1)).toInt().coerceIn(0, ages.size - 1)
                val heat = (1f - (age - ages[i]) / 50f).coerceIn(0f, 1f)
                Palette.alpha(Palette.WHITE, heat)
            }, seed = 6)
        }
        if (t < charge) {
            // ---- Aufladen
            val k = t / charge
            VoidDraw.glow(c, m, 0.8f + 3.2f * k, Palette.alpha(Palette.PINK, 0.4f + 0.5f * k), 3)
            VoidDraw.glow(c, m, 0.4f + 1.4f * k, Palette.alpha(Palette.WHITE, 0.3f + 0.6f * k), 4)
            for ((i, d) in sparks.withIndex()) {
                val local = (t * 0.07f + i * 0.13f) % 1f
                val r = 12f * (1f - local)
                val a = Vector3f(m).add(Vector3f(d).mul(r))
                val b = Vector3f(m).add(Vector3f(d).mul(r + 2.2f))
                VoidDraw.ribbon(c, listOf(a, b), width = { 0.09f }, color = { Palette.alpha(Palette.MAGENTA, local) }, seed = i)
            }
            // die Bahn am Boden flackert, ein Zielring wartet am Anfang
            val blink = if ((t.toInt() / 3) % 2 == 0) 0.9f else 0.45f
            val pts = List(24) { i -> c.rel(ground(fire * i / 23f / passes)).add(0f, 0.15f, 0f) }
            VoidDraw.ribbon(c, pts, width = { 0.12f + 0.12f * k }, color = { Palette.alpha(Palette.PINK, blink) }, seed = 8)
            val a = c.rel(from).add(0f, 0.1f, 0f)
            VoidDraw.ring(c, a, VoidDraw.GROUND_U, VoidDraw.GROUND_V, 2.6f * k, 3.4f, Palette.alpha(Palette.MAGENTA, 0.9f), Mode.RUNE, 4, rotation = t * 0.1f)
            return
        }
        val k = t - charge
        if (k > fire) {
            val fade = (1f - (k - fire) / 12f).coerceIn(0f, 1f)
            if (fade > 0f) VoidDraw.glow(c, m, 3f * fade, Palette.alpha(Palette.PINK, fade), 3)
            return
        }
        // ---- Strahl
        val g = ground(k).add(0.0, 0.2, 0.0)
        val e = end(world, mouth, g)
        val p = c.rel(e)
        val dir = Vector3f(p).sub(m)
        val len = dir.length()
        if (len < 0.5f) return
        dir.normalize()
        val q = VoidDraw.alignY(dir)
        val pulse = 1f + 0.12f * sin(t * 1.7f) + 0.06f * sin(t * 4.1f)
        val fadeIn = (k / 3f).coerceAtMost(1f) * ((fire - k) / 4f).coerceIn(0f, 1f)
        for ((scale, color) in listOf(
            1.35f to Palette.alpha(Palette.VIOLET, 0.28f * fadeIn),
            1f to Palette.alpha(Palette.MAGENTA, 0.55f * fadeIn),
            0.42f to Palette.alpha(Palette.WHITE, 0.95f * fadeIn),
        )) {
            val w = width * scale * pulse
            val mat = Matrix4f().translate(m).rotate(q).scale(w, len, w)
            VoidDraw.mesh(c, VoidPipelines.ENERGY_LAYER, VoidMeshes.ROD, mat, color, Mode.SHELL, 21)
        }
        // Spiralen um den Strahl
        val (u, v) = VoidDraw.basis(dir)
        for (s in 0 until 2) {
            val pts = List(28) { i ->
                val f = i / 27f
                val ang = f * 14f + t * 0.6f + s * PI.toFloat()
                Vector3f(m).add(Vector3f(dir).mul(len * f)).add(Vector3f(u).mul(cos(ang) * width * 1.7f)).add(Vector3f(v).mul(sin(ang) * width * 1.7f))
            }
            VoidDraw.ribbon(c, pts, width = { 0.12f }, color = { Palette.alpha(Palette.PINK, 0.8f * fadeIn) }, seed = 30 + s)
        }
        VoidDraw.glow(c, m, 4f * pulse, Palette.alpha(Palette.PINK, 0.9f * fadeIn), 3)
        VoidDraw.glow(c, p, 3.4f * pulse, Palette.alpha(Palette.WHITE, 0.9f * fadeIn), 5)
        val base = Vector3f(p).add(0f, 0.1f, 0f)
        VoidDraw.disc(c, VoidPipelines.ENERGY_LAYER, base, VoidDraw.GROUND_U, VoidDraw.GROUND_V, 3.4f, Palette.alpha(Palette.DEEP, 0.7f * fadeIn), Mode.STAIN, 6)
        VoidDraw.ring(c, base, VoidDraw.GROUND_U, VoidDraw.GROUND_V, 2.2f, 3.6f + 0.6f * sin(t * 2f), Palette.alpha(Palette.MAGENTA, fadeIn))
        for ((i, d) in sparks.withIndex()) {
            val local = (t * 0.12f + i * 0.1f) % 1f
            val a = Vector3f(p).add(d.x * 4f * local, abs(d.y) * 5f * local, d.z * 4f * local)
            VoidDraw.glow(c, a, 0.35f * (1f - local), Palette.alpha(Palette.PINK, 1f - local), i)
        }
    }
}

/**
 * Harpune vom Geschütz [from]: der Bolzen fliegt [travel] Ticks lang ins Volumen [volume] des Wals, danach hängt ein
 * gespanntes Seil zwischen Geschütz und Körper, bis der Server es löst ([end]).
 */
class HarpoonEffect(
    private val bossId: Int, private val volume: Int, private val from: Vec3d, private val travel: Int, hold: Int,
) : VoidEffect(travel + hold) {
    private var endAt = -1
    private var lastTarget: Vec3d? = null

    fun end() {
        if (endAt < 0) endAt = age
    }

    override fun tick(world: ClientWorld) {
        super.tick(world)
        if (endAt >= 0 && age - endAt > 10) age = life
    }

    private fun target(tickDelta: Float): Vec3d? {
        val boss = MinecraftClient.getInstance().world?.getEntityById(bossId) as? VoidBossEntity ?: return lastTarget
        val now = boss.clientFrame?.centers?.getOrNull(volume) ?: return lastTarget
        val before = boss.clientPrevFrame?.centers?.getOrNull(volume) ?: now
        return before.lerp(now, tickDelta.toDouble()).also { lastTarget = it }
    }

    override fun before(c: FxContext) {
        val t = age + c.tickDelta
        val goal = target(c.tickDelta) ?: return
        val a = c.rel(from)
        val fade = if (endAt >= 0) (1f - (t - endAt) / 10f).coerceIn(0f, 1f) else 1f
        if (t < travel) {
            val k = t / travel
            val g = c.rel(goal)
            val tip = Vector3f(a).lerp(g, k)
            val back = Vector3f(tip).sub(Vector3f(g).sub(a).normalize().mul(2.5f))
            VoidDraw.ribbon(c, listOf(back, tip), width = { 0.14f + 0.1f * it }, color = { Palette.alpha(Palette.WHITE, 1f) }, seed = 41)
            VoidDraw.glow(c, tip, 0.8f, Palette.alpha(Palette.CYAN, 0.9f), 42)
            VoidDraw.ribbon(c, listOf(a, Vector3f(a).lerp(tip, 0.5f).add(0f, -1f * k, 0f), tip), width = { 0.07f }, color = { Palette.alpha(Palette.PINK, 0.8f) }, seed = 43)
            return
        }
        val b = c.rel(goal)
        val loose = if (endAt >= 0) (t - endAt) / 10f else 0f
        val sag = Vector3f(a).lerp(b, 0.5f).add(0f, -(0.04f + 0.3f * loose) * a.distance(b) * 0.25f, 0f)
        val pts = List(20) { i ->
            val f = i / 19f
            val mt = 1 - f
            Vector3f(a).mul(mt * mt).add(Vector3f(sag).mul(2 * f * mt)).add(Vector3f(b).mul(f * f))
        }
        val pulse = 0.6f + 0.4f * sin(t * 0.5f)
        VoidDraw.ribbon(c, pts, width = { 0.16f }, color = { Palette.alpha(Palette.mix(Palette.VIOLET, Palette.PINK, pulse), fade) }, seed = 44)
        VoidDraw.glow(c, b, 1.2f, Palette.alpha(Palette.CYAN, 0.8f * fade), 45)
        VoidDraw.glow(c, a, 0.8f, Palette.alpha(Palette.CYAN, 0.6f * fade), 46)
    }

    companion object {
        val active = HashMap<Int, HarpoonEffect>()
    }
}

/** Kreissektor am Boden leuchtet auf und füllt sich bis zur Eruption; danach glüht er kurz aus. */
class SectorEffect(
    private val center: Vec3d, private val a0: Double, private val a1: Double, private val inner: Float, private val outer: Float, private val delay: Int,
) : VoidEffect(delay + 16) {
    override fun before(c: FxContext) {
        val t = age + c.tickDelta
        val warn = (t / delay).coerceIn(0f, 1f)
        val fade = if (t > delay) (1f - (t - delay) / 16f).coerceIn(0f, 1f) else (t / 6f).coerceAtMost(1f)
        val base = c.rel(center).add(0f, 0.09f, 0f)
        for (a in doubleArrayOf(a0, a1)) {
            val p0 = Vector3f(base).add((cos(a) * inner).toFloat(), 0f, (sin(a) * inner).toFloat())
            val p1 = Vector3f(base).add((cos(a) * outer).toFloat(), 0f, (sin(a) * outer).toFloat())
            VoidDraw.ribbon(c, listOf(p0, p1), width = { 0.22f }, color = { Palette.alpha(Palette.MAGENTA, fade) }, seed = 51)
        }
        val arc = List(16) { i ->
            val a = a0 + (a1 - a0) * i / 15
            Vector3f(base).add((cos(a) * outer).toFloat(), 0f, (sin(a) * outer).toFloat())
        }
        VoidDraw.ribbon(c, arc, width = { 0.22f }, color = { Palette.alpha(Palette.MAGENTA, fade) }, seed = 52)
        val reach = inner + (outer - inner) * warn
        var r = inner + 4f
        while (r < reach) {
            val n = max(2, ((a1 - a0) * r / 7.0).toInt())
            for (i in 0..n) {
                val a = a0 + (a1 - a0) * (i + 0.5) / (n + 1)
                val p = Vector3f(base).add((cos(a) * r).toFloat(), 0f, (sin(a) * r).toFloat())
                val pulse = 0.7f + 0.3f * sin(t * 0.6f + r * 0.2f)
                VoidDraw.disc(c, VoidPipelines.ENERGY_LAYER, p, VoidDraw.GROUND_U, VoidDraw.GROUND_V, 2.8f, Palette.alpha(Palette.mix(Palette.DEEP, Palette.MAGENTA, warn), fade * 0.55f * pulse), Mode.STAIN, i)
                if (t > delay) VoidDraw.glow(c, Vector3f(p).add(0f, 2f, 0f), 2.4f * fade, Palette.alpha(Palette.PINK, fade), i)
            }
            r += 7f
        }
    }
}

/**
 * Test-Hilfe: zeichnet die Hitbox-Volumen des Wals (wie der Client sie setzt) als Drahtgitter, wenn F3+B an ist und das
 * Spiel als Gametest läuft (`HHA_VOID_TEST` gesetzt). Im normalen Spiel zeigt F3+B die Boxen wie gewohnt selbst.
 * Farben: Kopf/Kiefer gelb, Rumpf weiß, Schwanz/Fluke cyan, Flossen grün, Herz/Blasloch rot.
 */
object LeviathanHitboxOverlay {
    private val enabled = System.getenv("HHA_VOID_TEST") != null

    fun draw(client: MinecraftClient, c: FxContext) {
        if (!enabled) return
        if (!client.debugHudEntryList.isEntryVisible(net.minecraft.client.gui.hud.debug.DebugHudEntries.ENTITY_HITBOXES)) return
        val world = client.world ?: return
        for (e in world.entities) {
            if (e !is VoidBossEntity) continue
            val f = e.clientFrame ?: continue
            for (i in 0 until dev.henny.hha_void.boss.LeviathanBody.COUNT) {
                val b = f.boxes[i] ?: continue
                val color = when (dev.henny.hha_void.boss.LeviathanBody.VOLUMES[i].kind) {
                    dev.henny.hha_void.boss.LeviathanBody.HEAD, dev.henny.hha_void.boss.LeviathanBody.JAW -> 0xFFE45C
                    dev.henny.hha_void.boss.LeviathanBody.TAIL, dev.henny.hha_void.boss.LeviathanBody.FLUKE -> 0x7DE3FF
                    dev.henny.hha_void.boss.LeviathanBody.FIN -> 0x7DFF8A
                    dev.henny.hha_void.boss.LeviathanBody.HEART, dev.henny.hha_void.boss.LeviathanBody.BLOWHOLE -> 0xFF4060
                    else -> 0xFFFFFF
                }
                val xs = doubleArrayOf(b.minX, b.maxX); val ys = doubleArrayOf(b.minY, b.maxY); val zs = doubleArrayOf(b.minZ, b.maxZ)
                fun p(ix: Int, iy: Int, iz: Int) = c.rel(Vec3d(xs[ix], ys[iy], zs[iz]))
                val edges = listOf(
                    p(0, 0, 0) to p(1, 0, 0), p(0, 1, 0) to p(1, 1, 0), p(0, 0, 1) to p(1, 0, 1), p(0, 1, 1) to p(1, 1, 1),
                    p(0, 0, 0) to p(0, 1, 0), p(1, 0, 0) to p(1, 1, 0), p(0, 0, 1) to p(0, 1, 1), p(1, 0, 1) to p(1, 1, 1),
                    p(0, 0, 0) to p(0, 0, 1), p(1, 0, 0) to p(1, 0, 1), p(0, 1, 0) to p(0, 1, 1), p(1, 1, 0) to p(1, 1, 1),
                )
                for ((a, z) in edges) VoidDraw.ribbon(c, listOf(a, z), width = { 0.06f }, color = { Palette.alpha(color, 0.95f) }, seed = i)
            }
        }
    }
}
