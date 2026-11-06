package dev.henny.hha_void.client.fx

import net.minecraft.client.render.RenderLayer
import net.minecraft.client.render.VertexConsumer
import net.minecraft.client.render.VertexConsumerProvider
import net.minecraft.util.math.Vec3d
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.PI

/** Pro Frame: Kamera, Zeit und Ausgabe-Puffer. Alle Vertices sind kamera-relativ. */
class FxContext(
    val cam: Vec3d,
    val camRotation: Quaternionf,
    val tickDelta: Float,
    val provider: VertexConsumerProvider.Immediate,
) {
    val camRight: Vector3f = Vector3f(1f, 0f, 0f).rotate(camRotation)
    val camUp: Vector3f = Vector3f(0f, 1f, 0f).rotate(camRotation)

    fun rel(p: Vec3d) = Vector3f((p.x - cam.x).toFloat(), (p.y - cam.y).toFloat(), (p.z - cam.z).toFloat())

    fun buffer(layer: RenderLayer): VertexConsumer = provider.getBuffer(layer)
}

/** Shader-Modi aus `void_energy.fsh`. */
object Mode {
    const val RIBBON = 0
    const val SHELL = 1
    const val GLOW = 2
    const val SWIRL = 3
    const val SHOCK = 4
    const val FLAME = 5
    const val RUNE = 6
    const val STAIN = 7
    const val SKY_RIFT = 8
    const val SKY_DOME = 9
}

/** Farben der Void-Palette. */
object Palette {
    const val DEEP = 0x2A0A5E
    const val VIOLET = 0x7B2CFF
    const val MAGENTA = 0xD14CFF
    const val PINK = 0xFF7BE8
    const val CYAN = 0x7DE3FF
    const val WHITE = 0xFFFFFF

    fun alpha(rgb: Int, a: Float): Int = ((a.coerceIn(0f, 1f) * 255f).toInt() shl 24) or (rgb and 0xFFFFFF)

    fun mix(from: Int, to: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        fun channel(shift: Int): Int {
            val a = (from shr shift) and 0xFF
            val b = (to shr shift) and 0xFF
            return (a + (b - a) * k).toInt() shl shift
        }
        return channel(16) or channel(8) or channel(0)
    }
}

object VoidDraw {

    private const val PIXEL = 0.125f
    private const val MIN_HALF = 0.03125f
    private const val MAX_CHAIN_CUBES = 180
    private const val PIXELS_PER_BLOCK = 3f

    private fun put(
        vc: VertexConsumer, x: Float, y: Float, z: Float, color: Int, u: Float, v: Float,
        mode: Int, seed: Int, nx: Float, ny: Float, nz: Float,
    ) {
        vc.vertex(x, y, z).color(color).texture(u, v).light(mode, seed).normal(nx, ny, nz)
    }

    /** Kamera-zugewandter Pixel-Stern; die Pixel behalten bei jeder Größe dieselbe Weltgröße. */
    fun glow(c: FxContext, center: Vector3f, size: Float, color: Int, seed: Int = 0) {
        flatQuad(c, VoidPipelines.ENERGY_LAYER, center, c.camRight, c.camUp, size, color, Mode.GLOW, seed, 0f, 0f)
    }

    /** Kette aus kleinen, am Blockraster ausgerichteten Würfeln durch beliebige Punkte. [t] läuft von 0 bis 1. */
    inline fun ribbon(
        c: FxContext,
        points: List<Vector3f>,
        width: (Float) -> Float,
        color: (Float) -> Int,
        seed: Int = 0,
        uLength: Float = 1f,
        mode: Int = Mode.RIBBON,
    ) {
        if (points.size < 2) return
        val count = points.size
        val widths = FloatArray(count) { width(it / (count - 1f)) }
        val colors = IntArray(count) { color(it / (count - 1f)) }
        cubeChain(c, points, widths, colors, seed, mode)
    }

    @PublishedApi
    internal fun cubeChain(
        c: FxContext, points: List<Vector3f>, widths: FloatArray, colors: IntArray, seed: Int, mode: Int,
    ) {
        val vc = c.buffer(VoidPipelines.ENERGY_LAYER)
        var lastX = Double.NaN
        var lastY = Double.NaN
        var lastZ = Double.NaN
        var cubes = 0
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            val length = a.distance(b)
            val stepSize = (widths[i] * 1.5f).coerceAtLeast(PIXEL)
            val steps = (length / stepSize).toInt().coerceIn(1, 96)
            for (s in 0 until steps) {
                if (cubes >= MAX_CHAIN_CUBES) return
                val t = s.toFloat() / steps
                val half = (Math.round((widths[i] + (widths[i + 1] - widths[i]) * t) * 16f) / 16f).coerceAtLeast(MIN_HALF)
                // Am Weltraster einrasten: gibt Treppenstufen statt glatter Linien
                val x = snap(a.x + (b.x - a.x) * t + c.cam.x)
                val y = snap(a.y + (b.y - a.y) * t + c.cam.y)
                val z = snap(a.z + (b.z - a.z) * t + c.cam.z)
                if (x == lastX && y == lastY && z == lastZ) continue
                lastX = x
                lastY = y
                lastZ = z
                val colorAt = if (t < 0.5f) colors[i] else colors[i + 1]
                cube(vc, (x - c.cam.x).toFloat(), (y - c.cam.y).toFloat(), (z - c.cam.z).toFloat(), half, colorAt, mode, seed)
                cubes++
            }
        }
    }

    private fun snap(value: Double): Double = (kotlin.math.floor(value * 8.0) + 0.5) / 8.0

    /** Würfel; nur die drei zur Kamera (Ursprung) zeigenden Seiten. */
    private fun cube(vc: VertexConsumer, x: Float, y: Float, z: Float, h: Float, color: Int, mode: Int, seed: Int) {
        val sx = if (x > 0f) -1f else 1f
        val sy = if (y > 0f) -1f else 1f
        val sz = if (z > 0f) -1f else 1f
        val fx = x + sx * h
        val fy = y + sy * h
        val fz = z + sz * h
        put(vc, fx, y - h, z - h, color, 0f, 0f, mode, seed, sx, 0f, 0f)
        put(vc, fx, y + h, z - h, color, 1f, 0f, mode, seed, sx, 0f, 0f)
        put(vc, fx, y + h, z + h, color, 1f, 1f, mode, seed, sx, 0f, 0f)
        put(vc, fx, y - h, z + h, color, 0f, 1f, mode, seed, sx, 0f, 0f)
        put(vc, x - h, fy, z - h, color, 0f, 0f, mode, seed, 0f, sy, 0f)
        put(vc, x + h, fy, z - h, color, 1f, 0f, mode, seed, 0f, sy, 0f)
        put(vc, x + h, fy, z + h, color, 1f, 1f, mode, seed, 0f, sy, 0f)
        put(vc, x - h, fy, z + h, color, 0f, 1f, mode, seed, 0f, sy, 0f)
        put(vc, x - h, y - h, fz, color, 0f, 0f, mode, seed, 0f, 0f, sz)
        put(vc, x + h, y - h, fz, color, 1f, 0f, mode, seed, 0f, 0f, sz)
        put(vc, x + h, y + h, fz, color, 1f, 1f, mode, seed, 0f, 0f, sz)
        put(vc, x - h, y + h, fz, color, 0f, 1f, mode, seed, 0f, 0f, sz)
    }

    /**
     * Flacher Ring in der Ebene [axisU]/[axisV]. Geometrie ist ein einziges Quad; der Shader
     * rastert den Kreis pixelweise (Normalen-Kanal: x = Innenradius-Anteil, y = Drehung).
     */
    fun ring(
        c: FxContext, center: Vector3f, axisU: Vector3f, axisV: Vector3f,
        innerRadius: Float, outerRadius: Float, color: Int,
        mode: Int = Mode.SHOCK, seed: Int = 0, rotation: Float = 0f,
    ) {
        if (outerRadius <= 0.02f) return
        val turn = ((rotation / (PI * 2.0).toFloat()) % 1f)
        flatQuad(c, VoidPipelines.ENERGY_LAYER, center, axisU, axisV, outerRadius, color, mode, seed, innerRadius / outerRadius, turn)
    }

    /** Einzelnes Quad als Scheibe; der Shader schneidet den Kreis pixelweise aus. */
    fun disc(
        c: FxContext, layer: RenderLayer, center: Vector3f, axisU: Vector3f, axisV: Vector3f,
        radius: Float, color: Int, mode: Int, seed: Int = 0,
    ) {
        flatQuad(c, layer, center, axisU, axisV, radius, color, mode, seed, 0f, 0f)
    }

    private fun flatQuad(
        c: FxContext, layer: RenderLayer, center: Vector3f, axisU: Vector3f, axisV: Vector3f,
        radius: Float, color: Int, mode: Int, seed: Int, inner: Float, turn: Float,
    ) {
        val vc = c.buffer(layer)
        // Obere Bits: Pixel pro Radius, untere Bits: Seed
        val cells = (radius * PIXELS_PER_BLOCK).toInt().coerceIn(8, 127)
        val encoded = (cells shl 8) or (seed and 0xFF)
        fun corner(su: Float, sv: Float) {
            put(
                vc,
                center.x + (axisU.x * su + axisV.x * sv) * radius,
                center.y + (axisU.y * su + axisV.y * sv) * radius,
                center.z + (axisU.z * su + axisV.z * sv) * radius,
                color, su, sv, mode, encoded, inner, turn, 0f,
            )
        }
        corner(-1f, -1f)
        corner(1f, -1f)
        corner(1f, 1f)
        corner(-1f, 1f)
    }

    /** Zeichnet ein Modell. Ohne eigene Vertexfarben im Modell gilt [color] für alle Punkte. */
    fun mesh(c: FxContext, layer: RenderLayer, mesh: Mesh, matrix: Matrix4f, color: Int, mode: Int, seed: Int = 0) {
        val vc = c.buffer(layer)
        val p = Vector3f()
        val n = Vector3f()
        val pos = mesh.pos
        val nrm = mesh.nrm
        val uv = mesh.uv
        val col = mesh.col
        for (i in 0 until mesh.vertexCount) {
            matrix.transformPosition(pos[i * 3], pos[i * 3 + 1], pos[i * 3 + 2], p)
            matrix.transformDirection(nrm[i * 3], nrm[i * 3 + 1], nrm[i * 3 + 2], n).normalize()
            put(vc, p.x, p.y, p.z, col?.get(i) ?: color, uv[i * 2], uv[i * 2 + 1], mode, seed, n.x, n.y, n.z)
        }
    }

    /** Dreht +Y auf [direction]. */
    fun alignY(direction: Vector3f): Quaternionf =
        Quaternionf().rotationTo(0f, 1f, 0f, direction.x, direction.y, direction.z)

    /** Zwei zueinander und zu [normal] senkrechte Einheitsvektoren. */
    fun basis(normal: Vector3f): Pair<Vector3f, Vector3f> {
        val n = Vector3f(normal).normalize()
        val ref = if (kotlin.math.abs(n.y) > 0.95f) Vector3f(1f, 0f, 0f) else Vector3f(0f, 1f, 0f)
        val u = Vector3f(ref).cross(n).normalize()
        val v = Vector3f(n).cross(u).normalize()
        return u to v
    }

    val GROUND_U = Vector3f(1f, 0f, 0f)
    val GROUND_V = Vector3f(0f, 0f, 1f)
}

fun easeOutCubic(t: Float): Float {
    val k = 1f - t.coerceIn(0f, 1f)
    return 1f - k * k * k
}

fun easeOutBack(t: Float): Float {
    val k = t.coerceIn(0f, 1f) - 1f
    return 1f + 2.4f * k * k * k + 1.4f * k * k
}
