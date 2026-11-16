package dev.henny.hha_void.client.fx

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sqrt

/** Fertiges 3D-Modell: Quads als flache Arrays. */
class Mesh(val pos: FloatArray, val nrm: FloatArray, val uv: FloatArray, val col: IntArray?) {
    val vertexCount: Int get() = pos.size / 3
}

private class MeshBuilder {
    private val pos = ArrayList<Float>()
    private val nrm = ArrayList<Float>()
    private val uv = ArrayList<Float>()
    private val col = ArrayList<Int>()

    fun vertex(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float, u: Float, v: Float, color: Int) {
        pos += x; pos += y; pos += z
        nrm += nx; nrm += ny; nrm += nz
        uv += u; uv += v
        col += color
    }

    /** Achsparallele Quader-Seite. [axis]: 0 = x, 1 = y, 2 = z; [sign] zeigt die Richtung der Normalen. */
    fun face(
        axis: Int, sign: Int,
        x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float,
        u: Float, v: Float, color: (Float, Float, Float) -> Int,
    ) {
        val nx = if (axis == 0) sign.toFloat() else 0f
        val ny = if (axis == 1) sign.toFloat() else 0f
        val nz = if (axis == 2) sign.toFloat() else 0f
        fun put(x: Float, y: Float, z: Float) = vertex(x, y, z, nx, ny, nz, u, v, color(x, y, z))
        // Reihenfolge gegen den Uhrzeigersinn von außen gesehen, sonst schneidet das Culling die Fläche weg
        when (axis) {
            0 -> if (sign > 0) {
                put(x1, y0, z0); put(x1, y1, z0); put(x1, y1, z1); put(x1, y0, z1)
            } else {
                put(x0, y0, z1); put(x0, y1, z1); put(x0, y1, z0); put(x0, y0, z0)
            }
            1 -> if (sign > 0) {
                put(x0, y1, z1); put(x1, y1, z1); put(x1, y1, z0); put(x0, y1, z0)
            } else {
                put(x0, y0, z0); put(x1, y0, z0); put(x1, y0, z1); put(x0, y0, z1)
            }
            else -> if (sign > 0) {
                put(x0, y0, z1); put(x1, y0, z1); put(x1, y1, z1); put(x0, y1, z1)
            } else {
                put(x0, y1, z0); put(x1, y1, z0); put(x1, y0, z0); put(x0, y0, z0)
            }
        }
    }

    fun build(withColors: Boolean) = Mesh(
        pos.toFloatArray(), nrm.toFloatArray(), uv.toFloatArray(),
        if (withColors) col.toIntArray() else null,
    )
}

/** Prozedurale Block-Modelle; werden einmal gebaut und dann nur noch transformiert. */
object VoidMeshes {

    private val NO_COLOR: (Float, Float, Float) -> Int = { _, _, _ -> 0 }

    // ---------- Rauschen für die Fels-Form ----------

    private fun hash(x: Int, y: Int, z: Int, seed: Int): Float {
        var h = x * 374761393 + y * 668265263 + z * 2147483647 + seed * 1274126177
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0xFFFFFF) / 16777215f
    }

    private fun noise(x: Float, y: Float, z: Float, seed: Int): Float {
        val ix = floor(x).toInt()
        val iy = floor(y).toInt()
        val iz = floor(z).toInt()
        val fx = x - ix
        val fy = y - iy
        val fz = z - iz
        val ux = fx * fx * (3 - 2 * fx)
        val uy = fy * fy * (3 - 2 * fy)
        val uz = fz * fz * (3 - 2 * fz)
        fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
        val a = lerp(hash(ix, iy, iz, seed), hash(ix + 1, iy, iz, seed), ux)
        val b = lerp(hash(ix, iy + 1, iz, seed), hash(ix + 1, iy + 1, iz, seed), ux)
        val c = lerp(hash(ix, iy, iz + 1, seed), hash(ix + 1, iy, iz + 1, seed), ux)
        val d = lerp(hash(ix, iy + 1, iz + 1, seed), hash(ix + 1, iy + 1, iz + 1, seed), ux)
        return lerp(lerp(a, b, uy), lerp(c, d, uy), uz)
    }

    // ---------- Voxel-Bausteine ----------

    /**
     * Baut ein Gitter aus Würfeln über x, z in -1..1 und y in [yMin]..[yMax].
     * Es entstehen nur Außenflächen; u = Winkel um Y, v = Höhe im Gitter.
     */
    private fun voxels(
        xzCells: Int, yCells: Int, yMin: Float, yMax: Float,
        withColors: Boolean = false,
        color: (Float, Float, Float) -> Int = NO_COLOR,
        filled: (Float, Float, Float) -> Boolean,
    ): Mesh {
        val builder = MeshBuilder()
        val dx = 2f / xzCells
        val dy = (yMax - yMin) / yCells
        fun cx(i: Int) = -1f + (i + 0.5f) * dx
        fun cy(j: Int) = yMin + (j + 0.5f) * dy
        val grid = Array(xzCells) { i ->
            Array(yCells) { j -> BooleanArray(xzCells) { k -> filled(cx(i), cy(j), cx(k)) } }
        }
        fun at(i: Int, j: Int, k: Int) =
            i in 0 until xzCells && j in 0 until yCells && k in 0 until xzCells && grid[i][j][k]

        val neighbours = arrayOf(
            intArrayOf(0, 1), intArrayOf(0, -1), intArrayOf(1, 1), intArrayOf(1, -1), intArrayOf(2, 1), intArrayOf(2, -1),
        )
        for (i in 0 until xzCells) for (j in 0 until yCells) for (k in 0 until xzCells) {
            if (!grid[i][j][k]) continue
            val x0 = cx(i) - dx / 2f
            val x1 = cx(i) + dx / 2f
            val y0 = cy(j) - dy / 2f
            val y1 = cy(j) + dy / 2f
            val z0 = cx(k) - dx / 2f
            val z1 = cx(k) + dx / 2f
            val u = (atan2(cx(k), cx(i)) / (2f * PI.toFloat()) + 1f) % 1f
            val v = (j + 0.5f) / yCells
            for ((axis, sign) in neighbours.map { it[0] to it[1] }) {
                val open = when (axis) {
                    0 -> !at(i + sign, j, k)
                    1 -> !at(i, j + sign, k)
                    else -> !at(i, j, k + sign)
                }
                if (open) builder.face(axis, sign, x0, y0, z0, x1, y1, z1, u, v, color)
            }
        }
        return builder.build(withColors)
    }

    private class Layer(val half: Float, val y0: Float, val y1: Float, val ox: Float = 0f, val oz: Float = 0f)

    /** Stapel aus quadratischen Quadern; v = Höhe im Stapel. Treppenform statt glatter Kegel. */
    private fun boxStack(layers: List<Layer>, yMin: Float, yMax: Float): Mesh {
        val builder = MeshBuilder()
        for (layer in layers) {
            val x0 = layer.ox - layer.half
            val x1 = layer.ox + layer.half
            val z0 = layer.oz - layer.half
            val z1 = layer.oz + layer.half
            val v = ((layer.y0 + layer.y1) / 2f - yMin) / (yMax - yMin)
            for (axis in 0..2) for (sign in intArrayOf(1, -1)) {
                builder.face(axis, sign, x0, layer.y0, z0, x1, layer.y1, z1, 0.5f, v, NO_COLOR)
            }
        }
        return builder.build(withColors = false)
    }

    private fun steppedLayers(count: Int, half: (Float) -> Float): List<Layer> = List(count) { i ->
        val v = i.toFloat() / count
        Layer(half(v + 0.5f / count), v, (i + 1f) / count)
    }

    // ---------- Formen ----------

    /** Blockiger Brocken; Vertexfarbe = Objektposition + Hitze für den Fels-Shader. */
    private fun rock(seed: Int, cells: Int): Mesh {
        val s = seed * 13
        return voxels(
            cells, cells, -1f, 1f, withColors = true,
            color = { x, y, z ->
                val heat = noise(x * 2.4f, y * 2.4f, z * 2.4f + s, seed + 3)
                packObjectColor(x, y, z, heat)
            },
            filled = { x, y, z ->
                val length = sqrt(x * x + y * y + z * z).coerceAtLeast(0.001f)
                val dx = x / length
                val dy = y / length
                val dz = z / length
                val big = noise(dx * 1.6f + s, dy * 1.6f, dz * 1.6f, seed)
                val mid = noise(dx * 3.7f, dy * 3.7f + s, dz * 3.7f, seed + 1)
                length < 0.52f + big * 0.42f + mid * 0.2f
            },
        )
    }

    private fun packObjectColor(x: Float, y: Float, z: Float, heat: Float): Int {
        fun byte(value: Float) = ((value * 0.5f + 0.5f).coerceIn(0f, 1f) * 255f).toInt()
        val a = (heat.coerceIn(0f, 1f) * 255f).toInt()
        return (a shl 24) or (byte(x) shl 16) or (byte(y) shl 8) or byte(z)
    }

    /** Getreppter Kristall entlang +Y; Spitze bei y = 1, kurzes Ende bei y = -0.4. */
    private fun crystal(seed: Int): Mesh {
        val widths = floatArrayOf(0.05f, 0.12f, 0.17f, 0.19f, 0.17f, 0.14f, 0.1f, 0.06f, 0.03f)
        val step = 1.4f / widths.size
        val layers = widths.mapIndexed { i, half ->
            val wobbleX = (noise(i * 1.7f, seed * 3.1f, 0f, seed) - 0.5f) * 0.05f
            val wobbleZ = (noise(0f, i * 2.3f, seed * 1.3f, seed + 1) - 0.5f) * 0.05f
            Layer(half, -0.4f + i * step, -0.4f + (i + 1) * step, wobbleX, wobbleZ)
        }
        return boxStack(layers, -0.4f, 1f)
    }

    /**
     * Blockmodell mit fester Farbe pro Würfel über x, z in -1..1 und y in [yMin]..[yMax].
     * [paint] liefert ARGB (a = Eigenleuchten) oder 0 für leer.
     */
    private fun painted(xzCells: Int, yCells: Int, yMin: Float, yMax: Float, paint: (Float, Float, Float) -> Int): Mesh {
        val builder = MeshBuilder()
        val dx = 2f / xzCells
        val dy = (yMax - yMin) / yCells
        val grid = Array(xzCells) { i ->
            Array(yCells) { j ->
                IntArray(xzCells) { k -> paint(-1f + (i + 0.5f) * dx, yMin + (j + 0.5f) * dy, -1f + (k + 0.5f) * dx) }
            }
        }
        fun at(i: Int, j: Int, k: Int) = i in 0 until xzCells && j in 0 until yCells && k in 0 until xzCells && grid[i][j][k] != 0
        for (i in 0 until xzCells) for (j in 0 until yCells) for (k in 0 until xzCells) {
            val color = grid[i][j][k]
            if (color == 0) continue
            val x0 = -1f + i * dx
            val y0 = yMin + j * dy
            val z0 = -1f + k * dx
            val solid: (Float, Float, Float) -> Int = { _, _, _ -> color }
            val v = (j + 0.5f) / yCells
            if (!at(i + 1, j, k)) builder.face(0, 1, x0, y0, z0, x0 + dx, y0 + dy, z0 + dx, 0.5f, v, solid)
            if (!at(i - 1, j, k)) builder.face(0, -1, x0, y0, z0, x0 + dx, y0 + dy, z0 + dx, 0.5f, v, solid)
            if (!at(i, j + 1, k)) builder.face(1, 1, x0, y0, z0, x0 + dx, y0 + dy, z0 + dx, 0.5f, v, solid)
            if (!at(i, j - 1, k)) builder.face(1, -1, x0, y0, z0, x0 + dx, y0 + dy, z0 + dx, 0.5f, v, solid)
            if (!at(i, j, k + 1)) builder.face(2, 1, x0, y0, z0, x0 + dx, y0 + dy, z0 + dx, 0.5f, v, solid)
            if (!at(i, j, k - 1)) builder.face(2, -1, x0, y0, z0, x0 + dx, y0 + dy, z0 + dx, 0.5f, v, solid)
        }
        return builder.build(withColors = true)
    }

    private class Shard(val bx: Float, val bz: Float, val tx: Float, val ty: Float, val tz: Float, val radius: Float)

    private val AMETHYST = intArrayOf(0x4A2A90, 0x6D42C4, 0x8E60E4, 0xB98CF8, 0xDCC0FF)

    /** Kristallgruppe: rautenförmige, getreppte Schäfte; zur Spitze hin hell und leuchtend. */
    private fun spike(seed: Int, xzCells: Int, yCells: Int): Mesh {
        val rnd = kotlin.random.Random(seed)
        fun r(from: Float, to: Float) = from + rnd.nextFloat() * (to - from)
        val shards = ArrayList<Shard>()
        shards += Shard(0f, 0f, r(-0.1f, 0.1f), 1f, r(-0.1f, 0.1f), 0.44f)
        for (i in 0 until 4) {
            val a = i * 1.57f + r(-0.45f, 0.45f)
            val out = r(0.62f, 0.86f)
            shards += Shard(kotlin.math.cos(a) * 0.3f, kotlin.math.sin(a) * 0.3f, kotlin.math.cos(a) * out, r(0.32f, 0.66f), kotlin.math.sin(a) * out, r(0.16f, 0.25f))
        }
        return painted(xzCells, yCells, 0f, 1f) { x, y, z ->
            var color = 0
            for ((index, s) in shards.withIndex()) {
                val along = y / s.ty
                if (along > 1f) continue
                val cx = s.bx + (s.tx - s.bx) * along
                val cz = s.bz + (s.tz - s.bz) * along
                val radius = s.radius * (1f - along).pow(0.72f) + 0.02f
                val dx = x - cx
                val dz = z - cz
                if (kotlin.math.abs(dx) + kotlin.math.abs(dz) > radius) continue
                // Facetten: eine Seite hell, die andere dunkel, dazu Bänder und eine Leuchtkante
                val facet = if (dx + dz > 0f) 1 else 0
                val band = (hash((y * 40f).toInt(), index, 0, seed) * 2.2f).toInt()
                val tone = (1 + facet + band - (if (along < 0.15f) 1 else 0)).coerceIn(0, 4)
                val edge = kotlin.math.abs(kotlin.math.abs(dx) - kotlin.math.abs(dz)) < 0.03f
                color = when {
                    along > 0.84f -> 0xFFFFEAFF.toInt()
                    edge && along > 0.3f -> (0xC0 shl 24) or 0xEFC4FF
                    else -> (0x18 shl 24) or AMETHYST[tone]
                }
                break
            }
            color
        }
    }

    /** Aufgebrochener Boden um den Kristall: Purpur, Endstein und Obsidian in schiefen Schollen. */
    private fun rubbleBase(seed: Int): Mesh = painted(16, 8, 0f, 1f) { x, y, z ->
        val radius = sqrt(x * x + z * z)
        val lump = noise(x * 3.4f + seed, 0f, z * 3.4f, seed)
        val top = (0.95f - radius * 0.72f) * (0.45f + lump * 1.05f)
        if (y > top || radius > 0.98f || radius < 0.18f) {
            0
        } else {
            val h = hash((x * 10f).toInt(), (y * 8f).toInt(), (z * 10f).toInt(), seed)
            val kind = noise(x * 2.2f, 3f, z * 2.2f, seed + 4)
            when {
                kind > 0.58f -> if (h > 0.5f) (1 shl 24) or 0xD8DA9E else (1 shl 24) or 0xBFC184
                kind > 0.38f -> if (h > 0.5f) (1 shl 24) or 0xB98BBB else (1 shl 24) or 0x9A6EA0
                else -> if (h > 0.85f) (0x90 shl 24) or 0xB050F0 else (1 shl 24) or 0x352450
            }
        }
    }

    /**
     * Klingensplitter: flache Raute mit vier Facetten, leuchtendem Grat und abgebrochenen Kanten,
     * unten ein dunkler Ansatz mit Kern. Spitze bei y = 1, Länge über y = -0.34..1.
     */
    private fun blade(seed: Int): Mesh = painted(16, 40, -0.34f, 1f) { x, y, z ->
        val layer = ((y + 0.34f) * 30f).toInt()
        val width = when {
            y < -0.04f -> 0.26f + (y + 0.34f) * 2f
            y < 0.1f -> 0.92f
            else -> 0.92f * (1f - (y - 0.1f) / 0.9f).pow(0.8f) + 0.015f
        }
        val across = kotlin.math.abs(x) + kotlin.math.abs(z) / 0.5f
        // Abgebrochene Stellen: eine Seite pro Scharte, damit der Umriss nicht glatt wirkt
        val side = if (x > 0f) 0 else 1
        val chipped = y > 0.12f && y < 0.82f && hash(layer / 3, side, 0, seed) > 0.72f
        val limit = if (chipped) width * 0.76f else width
        if (across > limit) {
            0
        } else {
            // Schneide: nur die äußersten Würfel der Klinge, nicht die ganze Oberfläche
            val rim = kotlin.math.abs(x) / limit
            val tone = hash(layer, (x * 8f).toInt(), (z * 8f).toInt(), seed)
            when {
                y < -0.04f -> if (kotlin.math.abs(x) < 0.16f && kotlin.math.abs(z) < 0.16f) (0xFF shl 24) or 0xE070FF else (0x08 shl 24) or 0x26163F
                y < 0.02f -> (0x10 shl 24) or 0x3A2466
                y > 0.86f -> (0xFF shl 24) or 0xFFF0FF
                kotlin.math.abs(x) < 0.07f -> (0xD0 shl 24) or 0xEFBCFF
                rim > 0.78f -> (0x60 shl 24) or 0xCFA8FF
                x > 0f && z > 0f -> (0x14 shl 24) or (if (tone > 0.8f) 0xB07CF8 else 0x9A62F0)
                x > 0f -> (0x14 shl 24) or (if (tone > 0.8f) 0x8A58E8 else 0x7A46DC)
                z > 0f -> (0x14 shl 24) or (if (tone > 0.8f) 0x6C40C8 else 0x5C32B4)
                else -> (0x14 shl 24) or (if (tone > 0.8f) 0x7E4CD8 else 0x6E3CC8)
            }
        }
    }

    // ---------- Fertige Modelle ----------

    val SHARD_BLADES: List<Mesh> by lazy { (1..3).map { blade(it) } }

    val SPIKES: List<Mesh> by lazy { (1..3).map { spike(it, 18, 30) } }
    val SPIKES_LIGHT: List<Mesh> by lazy { (4..5).map { spike(it, 10, 16) } }
    val SPIKE_RUBBLE: List<Mesh> by lazy { (1..2).map { rubbleBase(it) } }

    /** Vaels Faust: Knöchel nach unten, Ansatz des Handgelenks oben. */
    val FIST: Mesh by lazy {
        painted(16, 16, -1f, 0.34f) { x, y, z ->
            val h = hash((x * 12f).toInt(), (y * 12f).toInt(), (z * 12f).toInt(), 9)
            val stone = (1 shl 24) or (if (h > 0.72f) 0xF0F2C2 else if (h > 0.25f) 0xE2E4AA else 0xCDCF96)
            val mortar = (1 shl 24) or 0xAEB07A
            when {
                y > 0f -> if (kotlin.math.abs(x) < 0.42f && kotlin.math.abs(z) < 0.42f) (0xFF shl 24) or 0xF0A0FF else 0
                y > -0.72f -> if (kotlin.math.abs(x) < 0.95f && kotlin.math.abs(z) < 0.85f) {
                    if (kotlin.math.abs(y + 0.36f) < 0.03f) mortar else stone
                } else if (x > 0.95f && y < -0.2f && z < 0.2f && z > -0.5f) {
                    stone
                } else {
                    0
                }
                else -> {
                    val finger = ((x + 1f) * 2f).toInt().coerceIn(0, 3)
                    val gap = kotlin.math.abs(((x + 1f) * 2f) - finger - 0.5f) > 0.42f
                    if (!gap && kotlin.math.abs(x) < 0.95f && kotlin.math.abs(z) < 0.8f) {
                        if (y > -0.8f && z < -0.45f) (0x20 shl 24) or 0xF5F7D0 else stone
                    } else {
                        0
                    }
                }
            }
        }
    }

    /** Unterarm über der Faust: Stein mit goldener Schelle; y läuft von 0 bis 1. */
    val FOREARM: Mesh by lazy {
        painted(8, 24, 0f, 1f) { x, y, z ->
            val h = hash((x * 8f).toInt(), (y * 24f).toInt(), (z * 8f).toInt(), 11)
            when {
                y < 0.08f -> (0xFF shl 24) or 0xF0A0FF
                y < 0.2f -> (1 shl 24) or (if (h > 0.5f) 0xE0B454 else 0xBA8A38)
                else -> (1 shl 24) or (if ((y * 12f).toInt() % 3 == 0) 0xAEB07A else if (h > 0.5f) 0xE2E4AA else 0xCDCF96)
            }
        }
    }

    val ROCKS_DETAILED: List<Mesh> by lazy { (1..3).map { rock(it, 12) } }
    val ROCKS: List<Mesh> by lazy { (4..8).map { rock(it, 7) } }
    val CRYSTALS: List<Mesh> by lazy { (1..4).map { crystal(it) } }

    /** Hohle Block-Kugel. */
    val SPHERE: Mesh by lazy {
        voxels(14, 14, -1f, 1f) { x, y, z ->
            val r = sqrt(x * x + y * y + z * z)
            r <= 1f && r > 0.8f
        }
    }

    /** Obere Hälfte der Block-Kugel. */
    val DOME: Mesh by lazy {
        voxels(20, 10, 0f, 1f) { x, y, z ->
            val r = sqrt(x * x + y * y + z * z)
            r <= 1f && r > 0.88f
        }
    }

    /** Trichter aus Block-Ringen: breite Wurzel unten, schlanker Hals oben. */
    val FUNNEL: Mesh by lazy {
        voxels(26, 18, 0f, 1f) { x, y, z ->
            val wanted = 0.12f + 0.88f * (1f - y).pow(2.6f)
            val r = sqrt(x * x + z * z)
            kotlin.math.abs(r - wanted) < 0.055f
        }
    }

    /** Schweif: breiter Kopf bei y = 0, läuft treppenförmig aus. */
    val TAIL: Mesh by lazy {
        boxStack(steppedLayers(10) { v -> sqrt((v * 4f).coerceAtMost(1f)) * (1f - v).pow(1.2f) * 0.85f }, 0f, 1f)
    }

    /** Polar-Jet im Stil eines Leuchtfeuer-Strahls: nach oben stufenweise breiter. */
    val JET: Mesh by lazy {
        boxStack(steppedLayers(9) { v -> 0.14f + 0.4f * v.pow(1.5f) }, 0f, 1f)
    }

    /** Gerader Strahl: quadratische Säule (halbe Breite 1) von y 0 bis 1 – für den Sternenatem. */
    val ROD: Mesh by lazy { boxStack(listOf(Layer(1f, 0f, 1f)), 0f, 1f) }

    /** Glatte Kuppel für den Himmel; v läuft vom Zenit (0) bis unter den Horizont (1). */
    val SKY_DOME: Mesh by lazy {
        val builder = MeshBuilder()
        val rings = 12
        val segments = 32
        fun put(i: Int, j: Int) {
            val v = i.toFloat() / rings
            val polar = v * 1.9f
            val azimuth = j * (2f * PI.toFloat()) / segments
            val x = kotlin.math.sin(polar) * kotlin.math.cos(azimuth)
            val y = kotlin.math.cos(polar)
            val z = kotlin.math.sin(polar) * kotlin.math.sin(azimuth)
            builder.vertex(x, y, z, -x, -y, -z, j.toFloat() / segments, v, 0)
        }
        for (i in 0 until rings) for (j in 0 until segments) {
            put(i, j)
            put(i, j + 1)
            put(i + 1, j + 1)
            put(i + 1, j)
        }
        builder.build(withColors = false)
    }

    /** Ring aus Blöcken in der XZ-Ebene. */
    val TORUS: Mesh by lazy {
        voxels(36, 2, -0.056f, 0.056f) { x, _, z ->
            val r = sqrt(x * x + z * z)
            r <= 1f && r > 0.89f
        }
    }
}
