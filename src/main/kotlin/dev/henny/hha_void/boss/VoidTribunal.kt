package dev.henny.hha_void.boss

import com.mojang.serialization.MapCodec
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.block.Block
import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.nbt.NbtCompound
import net.minecraft.registry.RegistryKeys
import net.minecraft.server.world.ServerWorld
import net.minecraft.state.property.Properties
import net.minecraft.structure.StructureContext
import net.minecraft.structure.StructurePiece
import net.minecraft.util.Identifier
import net.minecraft.util.math.BlockBox
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.ChunkPos
import net.minecraft.util.math.Direction
import net.minecraft.util.math.random.Random
import net.minecraft.world.Heightmap
import net.minecraft.world.StructureWorldAccess
import net.minecraft.world.biome.BiomeKeys
import net.minecraft.world.biome.source.BiomeCoords
import net.minecraft.world.gen.StructureAccessor
import net.minecraft.world.gen.chunk.ChunkGenerator
import net.minecraft.world.gen.chunk.placement.RandomSpreadStructurePlacement
import net.minecraft.world.gen.structure.Structure
import net.minecraft.world.gen.structure.StructureType
import java.util.Optional
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Die Sternentiefe v4 – Himmelsarena des Leviathans: Hauptplattform Ø ≈ 372 (Radius [RADIUS]), Inselring und sechs
 * Außenplattformen mit Harpunen-Geschützen bis r ≈ 275.
 *
 * Ebenen: Sternenboden (y 0), äußere Stufe mit Brüstung (y 1), drei Bastionen am Rand (y 7), 30 Monolithen in zwei Ringen
 * (Deckung), Ruinenmauern, drei Walrippen-Bögen über dem Rand, Trümmerinseln bis y 48, sechs Sprungpfade zu den
 * Außenplattformen (Harpunen, Deckungsmauer). Sternenaufzug von der Insel darunter.
 *
 * **Sparsam:** kein Gesamt-Raster mehr. Jeder Chunk (bzw. jedes Rechteck) wird für sich prozedural erzeugt ([Region],
 * 16×16×137 Bytes); große Einzelteile (Inseln, Rippen, Sternbilder, Kristalle) sind kleine vorberechnete Listen.
 * `/voidboss place` baut zeitbegrenzt Chunk für Chunk ([BUDGET_MS] je Tick); natürliche Erzeugung über die Haupt-
 * struktur (inneres 17×17-Chunk-Feld) plus Kachel-Struktur [VoidTribunalTileStructure] für den Rest.
 * (Klassennamen „Tribunal" bleiben, weil Struktur-/Piece-Typ so registriert sind.)
 */
object TribunalBuilder {

    /** Radius der Hauptplattform (bis zur Außenkante der Brüstung) – Durchmesser ≈ 372. */
    const val RADIUS = 186

    /** Bis hierhin ist der Boden flach (y = 0). */
    const val FLOOR_RADIUS = 176

    /** Bis zu dieser Höhe über dem Boden ist der Luftraum über der Plattform frei. */
    const val CEILING = 72

    /** Bodenhöhe der natürlich erzeugten Arena (End, Bauhöhe 0–255). */
    const val FLOOR_Y = 176

    /** Luftraum über der Plattform, der frei von Bauten sein muss (Test zählt Blöcke). */
    const val CORRIDOR_INNER = 0.0
    const val CORRIDOR_OUTER = FLOOR_RADIUS - 2.0
    const val CORRIDOR_LOW = 21
    const val CORRIDOR_HIGH = 58

    /** Radius der Außenplattformen. */
    const val OUTER_R = 226.0

    // ---------------------------------------------------------------- Maße
    private const val MONO_HALF = 2
    private const val MONO_HEIGHT = 14
    const val MONO_RING = 62.0
    private const val MONO_RING_OUT = 132.0

    private const val DAIS_R = 8.5
    private const val FIELD_R = 118.0
    private const val HORIZON_W = 2.4
    private const val BRICK_RING_W = 7.0
    private const val BAND_SECTOR_DEG = 7.5
    private const val PARAPET_FROM = RADIUS - 0.6
    private const val POST_STEP_DEG = 3.0
    private const val PATH_GAP_DEG = 1.3

    /** Aufzug: Spalte (x, z) relativ zur Mitte. */
    const val LIFT_X = 0
    const val LIFT_Z = 16

    /** Innere Monolithen (Deckung nahe der Mitte): 12 × 5×5, r 62, Winkel 15° + k·30°. */
    val MONOLITHS: List<Pair<Int, Int>> = (0 until 12).map {
        val a = Math.toRadians(15.0 + it * 30.0)
        (cos(a) * MONO_RING).roundToInt() to (sin(a) * MONO_RING).roundToInt()
    }

    /** Äußere Monolithen: 18 bei r 132. */
    val MONOLITHS_OUT: List<Pair<Int, Int>> = (0 until 18).map {
        val a = Math.toRadians(10.0 + it * 20.0)
        (cos(a) * MONO_RING_OUT).roundToInt() to (sin(a) * MONO_RING_OUT).roundToInt()
    }

    /** Sprungpfade (Winkel der Tore in der Brüstung) – je einer zu einer Außenplattform. */
    val PATH_STARTS = doubleArrayOf(30.0, 90.0, 150.0, 210.0, 270.0, 330.0)
    private val RIB_ANGLES = doubleArrayOf(60.0, 180.0, 300.0)

    /** Bastionen am Rand. */
    val BASTIONS = doubleArrayOf(0.0, 120.0, 240.0)
    private const val BASTION_INNER = 166.0
    private const val BASTION_HALF_DEG = 3.4
    private const val BASTION_TOP = 7

    private val RUINS: List<Pair<Double, Double>> = buildList {
        for (i in 0 until 16) add((i * 22.5 + 11.0) to (102.0 + (i % 3) * 3.0))
        for (i in 0 until 6) add((i * 60.0 + 40.0) to 38.0)
    }

    /** Hindernisse auf der Plattform (x, z, Radius) – für Bahnen dicht über dem Boden (Rammstoß, Tiefflug). */
    val OBSTACLES: List<Triple<Double, Double, Double>> by lazy {
        val out = ArrayList<Triple<Double, Double, Double>>()
        for ((x, z) in MONOLITHS + MONOLITHS_OUT) out.add(Triple(x.toDouble(), z.toDouble(), 4.0))
        for (a in BASTIONS) {
            val r = Math.toRadians(a)
            out.add(Triple(cos(r) * 174.0, sin(r) * 174.0, 10.0))
        }
        out
    }

    /** Wo die Harpunen-Geschütze stehen (relativ zur Bodenmitte, Block des Geschützes). */
    val HARPOONS: List<BlockPos> by lazy { ISLES.filter { it.kind == 3 }.map { BlockPos(it.x.roundToInt(), it.top + 1, it.z.roundToInt()) } }

    /** Radius, in dem `/voidboss place` den Luftraum von y 1 bis [CEILING] räumt. */
    private const val CLEAR_RADIUS = RADIUS + 12.0

    // Ausdehnung relativ zur Bodenmitte
    const val EXTENT = 282
    private const val Y_MIN = -64
    private const val Y_MAX = CEILING
    private const val HEIGHT = Y_MAX - Y_MIN + 1

    // ---------------------------------------------------------------- Material
    private val AIR = Blocks.AIR.defaultState
    private val END_STONE = Blocks.END_STONE.defaultState
    private val END_BRICK = Blocks.END_STONE_BRICKS.defaultState
    private val END_BRICK_STAIRS = Blocks.END_STONE_BRICK_STAIRS.defaultState
    private val PURPUR = Blocks.PURPUR_BLOCK.defaultState
    private val PURPUR_PILLAR = Blocks.PURPUR_PILLAR.defaultState
    private val PURPUR_SLAB = Blocks.PURPUR_SLAB.defaultState
    private val OBSIDIAN = Blocks.OBSIDIAN.defaultState
    private val CRYING = Blocks.CRYING_OBSIDIAN.defaultState
    private val AMETHYST = Blocks.AMETHYST_BLOCK.defaultState
    private val BLACKSTONE = Blocks.BLACKSTONE.defaultState
    private val BS_BRICKS = Blocks.POLISHED_BLACKSTONE_BRICKS.defaultState
    private val BS_CRACKED = Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS.defaultState
    private val BS_CHISELED = Blocks.CHISELED_POLISHED_BLACKSTONE.defaultState
    private val DS_TILES = Blocks.DEEPSLATE_TILES.defaultState
    private val DS_TILES_CRACKED = Blocks.CRACKED_DEEPSLATE_TILES.defaultState
    private val DS_POLISHED = Blocks.POLISHED_DEEPSLATE.defaultState
    private val DS_CHISELED = Blocks.CHISELED_DEEPSLATE.defaultState
    private val CALCITE = Blocks.CALCITE.defaultState
    private val DIORITE = Blocks.DIORITE.defaultState
    private val GLASS_PURPLE = Blocks.PURPLE_STAINED_GLASS.defaultState
    private val GLASS_MAGENTA = Blocks.MAGENTA_STAINED_GLASS.defaultState
    private val SEA_LANTERN = Blocks.SEA_LANTERN.defaultState
    private val FROGLIGHT = Blocks.PEARLESCENT_FROGLIGHT.defaultState
    private val LANTERN = Blocks.LANTERN.defaultState
    private val SOUL_LANTERN = Blocks.SOUL_LANTERN.defaultState
    private val SOUL_LANTERN_HANGING = Blocks.SOUL_LANTERN.defaultState.with(Properties.HANGING, true)
    private val ROD_UP = Blocks.END_ROD.defaultState.with(Properties.FACING, Direction.UP)
    private val ROD_DOWN = Blocks.END_ROD.defaultState.with(Properties.FACING, Direction.DOWN)
    private val CLUSTER_UP = Blocks.AMETHYST_CLUSTER.defaultState.with(Properties.FACING, Direction.UP)
    private val CLUSTER_DOWN = Blocks.AMETHYST_CLUSTER.defaultState.with(Properties.FACING, Direction.DOWN)
    private val BUD_UP = Blocks.LARGE_AMETHYST_BUD.defaultState.with(Properties.FACING, Direction.UP)
    private fun bone(axis: Direction.Axis): BlockState = Blocks.BONE_BLOCK.defaultState.with(Properties.AXIS, axis)
    private val lift: BlockState get() = LeviathanBlocks.STAR_LIFT.defaultState
    private val harpoon: BlockState get() = LeviathanBlocks.HARPOON.defaultState

    // ---------------------------------------------------------------- Palette (gemeinsam, threadsicher)
    private val palette = ArrayList<BlockState?>().also { it.add(null) }
    private val paletteIndex = HashMap<BlockState, Int>()

    private fun indexOf(s: BlockState): Int = synchronized(palette) {
        paletteIndex.getOrPut(s) {
            palette.add(s)
            check(palette.size <= 255) { "Palette voll" }
            palette.size - 1
        }
    }

    private fun stateOf(i: Int): BlockState? = synchronized(palette) { palette[i] }

    /** Ausschnitt [x0]..[x0]+[w]-1 × [z0]..[z0]+[d]-1 (relativ zur Bodenmitte), volle Höhe. */
    private class Region(val x0: Int, val z0: Int, val w: Int, val d: Int) {
        val cells = ByteArray(w * d * HEIGHT)
        val x1 get() = x0 + w - 1
        val z1 get() = z0 + d - 1
        private fun idx(x: Int, y: Int, z: Int) = ((x - x0) * d + (z - z0)) * HEIGHT + (y - Y_MIN)
        fun inside(x: Int, y: Int, z: Int) = x in x0..x1 && z in z0..z1 && y in Y_MIN..Y_MAX
        operator fun set(x: Int, y: Int, z: Int, s: BlockState) {
            if (!inside(x, y, z)) return
            cells[idx(x, y, z)] = indexOf(s).toByte()
        }
        operator fun get(x: Int, y: Int, z: Int): BlockState? = if (inside(x, y, z)) stateOf(cells[idx(x, y, z)].toInt() and 0xFF) else null
        fun isSolid(x: Int, y: Int, z: Int): Boolean = get(x, y, z).let { it != null && !it.isAir }
        /** Schneidet sich das Rechteck mit [minX]..[maxX] × [minZ]..[maxZ]? */
        fun hits(minX: Double, maxX: Double, minZ: Double, maxZ: Double) = maxX >= x0 && minX <= x1 && maxZ >= z0 && minZ <= z1
        fun xs(from: Int, to: Int) = max(from, x0)..min(to, x1)
        fun zs(from: Int, to: Int) = max(from, z0)..min(to, z1)
    }

    /** Erzeugt den Ausschnitt (deterministisch, unabhängig von anderen Ausschnitten). */
    private fun region(x0: Int, z0: Int, w: Int = 16, d: Int = 16): Region = Region(x0, z0, w, d).also { Design.run(it) }

    // ---------------------------------------------------------------- öffentliche API

    /** Ruft [put] für jeden Block des Baus auf, relativ zur Bodenmitte (Test/Statistik; langsam). */
    fun build(put: (Int, Int, Int, BlockState) -> Unit) {
        var x0 = -EXTENT
        while (x0 <= EXTENT) {
            var z0 = -EXTENT
            while (z0 <= EXTENT) {
                val r = region(x0, z0)
                for (x in r.x0..r.x1) for (z in r.z0..r.z1) for (y in Y_MIN..Y_MAX) {
                    val s = r[x, y, z] ?: continue
                    put(x, y, z, s)
                }
                z0 += 16
            }
            x0 += 16
        }
    }

    private val cache = LinkedHashMap<Long, Region>(16, 0.75f, true)

    /** Der Block des Baus an dieser relativen Stelle, oder null, wenn der Bau sie nicht berührt. */
    fun stateAt(x: Int, y: Int, z: Int): BlockState? {
        val cx = Math.floorDiv(x, 16) * 16
        val cz = Math.floorDiv(z, 16) * 16
        val key = (cx.toLong() shl 32) or (cz.toLong() and 0xFFFFFFFFL)
        val r = synchronized(cache) {
            cache.getOrPut(key) { region(cx, cz) }.also { if (cache.size > 24) cache.remove(cache.keys.first()) }
        }
        return r[x, y, z]
    }

    /** Blöcke von vier kleineren äußeren Trümmerinseln, die beim Übergang in Phase II in die Tiefe stürzen. */
    fun collapseBlocks(): List<BlockPos> {
        val chosen = ISLES.filter { it.kind == 1 && it.size in 3.0..6.5 && it.r > 232.0 }.sortedBy { it.seed }.take(4)
        val out = ArrayList<BlockPos>()
        for (isle in chosen) {
            val reach = ceil(isle.size * 1.3).toInt() + 2
            val bx = isle.x.roundToInt(); val bz = isle.z.roundToInt()
            val r = region(bx - reach, bz - reach, 2 * reach + 1, 2 * reach + 1)
            for (x in r.x0..r.x1) for (z in r.z0..r.z1) for (y in isle.top - 24..isle.top + 8) {
                if (r.isSolid(x, y, z)) out.add(BlockPos(x, y, z))
            }
        }
        return out
    }

    /** Läuft gerade ein `/voidboss place`? (für Tests) */
    fun isPlacing(): Boolean = jobs.isNotEmpty()

    /**
     * Baut die Arena mit Bodenmitte [center] über mehrere Ticks (je Tick höchstens [BUDGET_MS] ms): Chunk für Chunk von
     * innen nach außen der Bau und der freie Luftraum darüber, zuletzt der Aufzug bis zum Grund darunter.
     */
    fun placeInWorld(world: ServerWorld, center: BlockPos, done: () -> Unit = {}) {
        ensureTicking()
        ArenaRegistry.register(world, center)
        jobs.add(PlaceJob(world, center, done))
    }

    fun bounds(center: BlockPos, liftBottom: Int = 0): BlockBox = BlockBox(
        center.x - EXTENT, center.y + min(Y_MIN, liftBottom - 2), center.z - EXTENT,
        center.x + EXTENT, center.y + Y_MAX, center.z + EXTENT,
    )

    /** Erzeugt den Teil des Baus, der in [chunkBox] liegt (Weltgenerierung, pro Chunk). */
    fun generateIn(world: StructureWorldAccess, center: BlockPos, chunkBox: BlockBox, liftBottom: Int) {
        val box = bounds(center, liftBottom)
        val x0 = max(chunkBox.minX, box.minX); val x1 = min(chunkBox.maxX, box.maxX)
        val z0 = max(chunkBox.minZ, box.minZ); val z1 = min(chunkBox.maxZ, box.maxZ)
        val y0 = max(chunkBox.minY, box.minY); val y1 = min(chunkBox.maxY, box.maxY)
        if (x0 > x1 || z0 > z1 || y0 > y1) return
        val r = region(x0 - center.x, z0 - center.z, x1 - x0 + 1, z1 - z0 + 1)
        val pos = BlockPos.Mutable()
        for (x in x0..x1) for (z in z0..z1) for (y in max(y0, center.y + Y_MIN)..min(y1, center.y + Y_MAX)) {
            val s = r[x - center.x, y - center.y, z - center.z] ?: continue
            pos.set(x, y, z)
            if (s.isAir && world.getBlockState(pos).isAir) continue
            world.setBlockState(pos, s, Block.NOTIFY_LISTENERS)
        }
        val lx = center.x + LIFT_X; val lz = center.z + LIFT_Z
        if (lx in x0..x1 && lz in z0..z1) {
            for (y in max(y0, center.y + liftBottom)..min(y1, center.y + Y_MIN - 1)) {
                pos.set(lx, y, lz)
                world.setBlockState(pos, lift, Block.NOTIFY_LISTENERS)
            }
            liftPad(center, liftBottom) { px, py, pz, s ->
                if (px in x0..x1 && pz in z0..z1 && py in y0..y1) {
                    pos.set(px, py, pz)
                    world.setBlockState(pos, s, Block.NOTIFY_LISTENERS)
                }
            }
        }
    }

    private fun liftPad(center: BlockPos, liftBottom: Int, put: (Int, Int, Int, BlockState) -> Unit) {
        val lx = center.x + LIFT_X; val ly = center.y + liftBottom - 1; val lz = center.z + LIFT_Z
        for (dx in -2..2) for (dz in -2..2) {
            if (dx == 0 && dz == 0) continue
            val ring = abs(dx) == 2 || abs(dz) == 2
            put(lx + dx, ly, lz + dz, if (ring) CRYING else AMETHYST)
            if (ring && abs(dx) == 2 && abs(dz) == 2) put(lx + dx, ly + 1, lz + dz, ROD_UP)
        }
        put(lx, ly, lz, SEA_LANTERN)
    }

    // ---------------------------------------------------------------- Bau über mehrere Ticks
    private const val BUDGET_MS = 14L
    private const val LOAD_AHEAD = 24
    private const val MAX_PENDING = 4

    private class PlaceJob(val world: ServerWorld, val center: BlockPos, val done: () -> Unit) {
        /** Welt-Chunks, die die Arena berührt, von innen nach außen. */
        val tiles: List<ChunkPos> = buildList {
            val c0 = ChunkPos(center.add(-EXTENT, 0, -EXTENT))
            val c1 = ChunkPos(center.add(EXTENT, 0, EXTENT))
            for (cx in c0.x..c1.x) for (cz in c0.z..c1.z) {
                val p = ChunkPos(cx, cz)
                val nx = (p.startX..p.endX).minOf { abs(it - center.x) }.toDouble()
                val nz = (p.startZ..p.endZ).minOf { abs(it - center.z) }.toDouble()
                if (nx * nx + nz * nz <= EXTENT.toDouble() * EXTENT) add(p)
            }
        }.sortedBy { p -> (p.centerX - center.x).toLong() * (p.centerX - center.x) + (p.centerZ - center.z).toLong() * (p.centerZ - center.z) }
        val done0 = BooleanArray(tiles.size)
        val requested = BooleanArray(tiles.size)
        /** fertig, sobald der Chunk geladen ist (nie im Server-Thread darauf warten) */
        val ready = arrayOfNulls<java.util.concurrent.CompletableFuture<*>>(tiles.size)
        var next = 0
        var stage = 0
        var waited = 0
    }

    private val jobs = ArrayList<PlaceJob>()
    private var ticking = false

    private fun ensureTicking() {
        if (ticking) return
        ticking = true
        ServerTickEvents.END_SERVER_TICK.register { _ ->
            val job = jobs.firstOrNull() ?: return@register
            if (runCatching { step(job) }.getOrElse { it.printStackTrace(); true }) {
                jobs.remove(job)
                runCatching(job.done)
            }
        }
    }

    private fun writeTile(job: PlaceJob, chunk: ChunkPos) {
        val w = job.world
        val c = job.center
        val pos = BlockPos.Mutable()
        val r = region(chunk.startX - c.x, chunk.startZ - c.z)
        for (x in r.x0..r.x1) for (z in r.z0..r.z1) {
            val clear = x * x + z * z <= CLEAR_RADIUS * CLEAR_RADIUS
            for (y in Y_MIN..Y_MAX) {
                val s = r[x, y, z]
                pos.set(c.x + x, c.y + y, c.z + z)
                if (w.isOutOfHeightLimit(pos)) continue
                if (s == null) {
                    if (!clear || y < 1 || w.getBlockState(pos).isAir) continue
                    w.setBlockState(pos, AIR, Block.NOTIFY_LISTENERS or Block.FORCE_STATE or Block.SKIP_DROPS)
                    continue
                }
                if (w.getBlockState(pos) == s) continue
                w.setBlockState(pos, s, Block.NOTIFY_LISTENERS or Block.FORCE_STATE or Block.SKIP_DROPS)
            }
        }
    }

    /**
     * Ein Tick Bauarbeit; true = fertig. Chunks werden vorab asynchron geladen/erzeugt (Ticket), geschrieben wird nur in
     * schon geladene – so blockiert kein Tick auf die Welterzeugung.
     */
    private fun step(job: PlaceJob): Boolean {
        val w = job.world
        val c = job.center
        val pos = BlockPos.Mutable()
        val until = System.nanoTime() + BUDGET_MS * 1_000_000
        if (job.stage == 0) {
            while (job.next < job.tiles.size && job.done0[job.next]) job.next++
            val end = min(job.tiles.size, job.next + LOAD_AHEAD)
            // höchstens MAX_PENDING Chunks gleichzeitig anfordern: das Fertigstellen geladener Chunks läuft im
            // Server-Thread zwischen den Ticks – viele auf einmal ergaben Pausen von mehreren Sekunden
            var pending = 0
            for (i in job.next until end) if (job.requested[i] && !job.done0[i]) pending++
            for (i in job.next until end) {
                if (pending >= MAX_PENDING) break
                if (job.requested[i]) continue
                job.requested[i] = true
                pending++
                job.ready[i] = w.chunkManager.addChunkLoadingTicket(net.minecraft.server.world.ChunkTicketType.PORTAL, job.tiles[i], 0)
            }
            var progress = false
            for (i in job.next until end) {
                if (System.nanoTime() > until) break
                if (job.done0[i] || !job.requested[i]) continue
                val p = job.tiles[i]
                // erst schreiben, wenn das Lade-Future fertig ist – sonst lädt getBlockState den Chunk synchron im Server-Thread
                if (job.ready[i]?.isDone != true && job.waited < 600) continue
                writeTile(job, p)
                job.done0[i] = true
                w.chunkManager.removeTicket(net.minecraft.server.world.ChunkTicketType.PORTAL, p, 0)
                progress = true
            }
            job.waited = if (progress) 0 else job.waited + 1
            if (job.next >= job.tiles.size) job.stage = 1
            return false
        }
        // Aufzug: bis zum ersten festen Block unter dem Bau (höchstens 160 tiefer); ohne Grund kein Aufzug
        val lx = c.x + LIFT_X; val lz = c.z + LIFT_Z
        var y = c.y + Y_MIN - 1
        val stop = max(w.bottomY + 1, c.y + Y_MIN - 160)
        while (y > stop) {
            pos.set(lx, y, lz)
            if (!w.getBlockState(pos).isAir) break
            y--
        }
        if (y > stop) {
            val bottom = y + 1 - c.y
            for (yy in c.y + bottom..c.y + Y_MIN - 1) {
                pos.set(lx, yy, lz)
                w.setBlockState(pos, lift, Block.NOTIFY_LISTENERS)
            }
            liftPad(c, bottom) { px, py, pz, s -> pos.set(px, py, pz); w.setBlockState(pos, s, Block.NOTIFY_LISTENERS) }
        }
        return true
    }

    // ---------------------------------------------------------------- Rauschen
    private fun hash(x: Int, z: Int, salt: Int): Double {
        var h = x * 73856093 xor (z * 19349663) xor (salt * 83492791)
        h = (h xor (h ushr 13)) * 1274126177
        return ((h xor (h ushr 16)) and 0xFFFF) / 65535.0
    }

    private fun hash3(x: Int, y: Int, z: Int, salt: Int) = hash(x * 31 + y * 1013, z - y * 7, salt)

    private fun smooth(x: Double, z: Double, salt: Int): Double {
        val xi = floor(x).toInt(); val zi = floor(z).toInt()
        val fx = x - xi; val fz = z - zi
        val sx = fx * fx * (3 - 2 * fx); val sz = fz * fz * (3 - 2 * fz)
        val a = hash(xi, zi, salt); val b = hash(xi + 1, zi, salt)
        val c = hash(xi, zi + 1, salt); val d = hash(xi + 1, zi + 1, salt)
        return (a + (b - a) * sx) * (1 - sz) + (c + (d - c) * sx) * sz
    }

    private fun angleDeg(x: Double, z: Double): Double = (Math.toDegrees(atan2(z, x)) + 360.0) % 360.0
    private fun angleDist(a: Double, b: Double): Double = abs(((a - b) % 360.0 + 540.0) % 360.0 - 180.0)

    // ---------------------------------------------------------------- vorberechnete Einzelteile

    /** Insel: Mitte, Oberkante, Größe; [kind] 0 = Pfadstein, 1 = Trümmer, 2 = Leuchtfeuer, 3 = Außenplattform (Harpune). */
    private class Isle(val x: Double, val z: Double, val top: Int, val size: Double, val kind: Int, val seed: Int) {
        val r get() = sqrt(x * x + z * z)
    }

    private val ISLES: List<Isle> by lazy { Design.isles() }

    /** Fertige Blöcke einer Insel (inkl. Deko) – klein, einmal berechnet. */
    private class Cells(val xs: IntArray, val ys: IntArray, val zs: IntArray, val states: Array<BlockState>, val minX: Int, val maxX: Int, val minZ: Int, val maxZ: Int)

    private val ISLE_CELLS: List<Cells> by lazy { ISLES.map { Design.islandCells(it) } }
    private val RIB_CELLS: List<Cells> by lazy { RIB_ANGLES.map { Design.ribcageCells(it) } }
    private val STAR_CELLS: Cells by lazy { Design.constellationCells() }

    /** Einmal-Berechnungen (Inseln, Rippen, Sternbilder, Kristalle, ein Ausschnitt) vorab – im Hintergrund beim Serverstart. */
    fun prewarm() {
        val t0 = System.nanoTime()
        ISLE_CELLS.size; RIB_CELLS.size; STAR_CELLS.xs.size; CRYSTALS.size; OBSTACLES.size; HARPOONS.size
        region(0, 0)
        println("[tribunal] vorberechnet in ${(System.nanoTime() - t0) / 1_000_000} ms")
    }

    private class Crystal(val x: Int, val z: Int, val len: Int, val tip: BlockState, val glow: Boolean)

    private val CRYSTALS: List<Crystal> by lazy {
        val rnd = java.util.Random(2718281L)
        List(900) {
            val a = rnd.nextDouble() * 2 * PI
            val r = sqrt(rnd.nextDouble()) * (RADIUS - 2.0)
            val len = rnd.nextInt(7)
            val glow = rnd.nextInt(3) == 0
            val tip = when (rnd.nextInt(5)) { 0 -> SOUL_LANTERN_HANGING; 1 -> ROD_DOWN; else -> CLUSTER_DOWN }
            Crystal((cos(a) * r).roundToInt(), (sin(a) * r).roundToInt(), len, tip, glow)
        }
    }

    // ---------------------------------------------------------------- Entwurf

    private object Design {
        private const val maxDepth = -Y_MIN - 6

        fun run(g: Region) {
            platform(g)
            starMap(g)
            dais(g)
            rim(g)
            for (a in BASTIONS) bastion(g, a)
            for ((i, ra) in RUINS.withIndex()) ruin(g, ra.first, ra.second, i)
            for ((mx, mz) in MONOLITHS + MONOLITHS_OUT) monolith(g, mx, mz)
            for (c in RIB_CELLS) write(g, c)
            for (c in ISLE_CELLS) write(g, c)
            hangingCrystals(g)
            liftShaft(g)
            g[0, 1, 0] = VoidBoss.ALTAR.defaultState
        }

        private fun write(g: Region, c: Cells) {
            if (c.maxX < g.x0 || c.minX > g.x1 || c.maxZ < g.z0 || c.minZ > g.z1) return
            for (i in c.xs.indices) g[c.xs[i], c.ys[i], c.zs[i]] = c.states[i]
        }

        // ---- Plattform mit zerklüfteter Unterseite
        fun undersideDepth(x: Int, z: Int, r: Double): Int {
            val t = (RADIUS + 0.5 - r).coerceAtLeast(0.0)
            val base = 3.0 + 16.0 * sqrt((t / RADIUS).coerceIn(0.0, 1.0))
            var d = base + smooth(x / 12.0, z / 12.0, 1) * 5.0 + hash(x, z, 2) * 1.5
            val cell = 10
            val cx = Math.floorDiv(x, cell); val cz = Math.floorDiv(z, cell)
            for (ox in -1..1) for (oz in -1..1) {
                val qx = cx + ox; val qz = cz + oz
                if (hash(qx, qz, 11) > 0.36) continue
                val px = qx * cell + cell / 2 + (hash(qx, qz, 13) * 2 - 1) * 2.5
                val pz = qz * cell + cell / 2 + (hash(qx, qz, 14) * 2 - 1) * 2.5
                val dd = sqrt((x - px) * (x - px) + (z - pz) * (z - pz))
                val len = (6.0 + hash(qx, qz, 12) * 22.0) * (0.4 + 0.6 * (t / RADIUS))
                val w = 4.5 + hash(qx, qz, 15) * 2.0
                if (dd < w) d = max(d, base + 2.0 + len * (1 - dd / w))
            }
            return d.toInt().coerceIn(2, maxDepth)
        }

        private fun platform(g: Region) {
            if (!g.hits(-RADIUS - 1.0, RADIUS + 1.0, -RADIUS - 1.0, RADIUS + 1.0)) return
            for (x in g.xs(-RADIUS - 1, RADIUS + 1)) for (z in g.zs(-RADIUS - 1, RADIUS + 1)) {
                val r = sqrt((x * x + z * z).toDouble())
                if (r > RADIUS + 0.5) continue
                val depth = undersideDepth(x, z, r)
                g[x, 0, z] = END_BRICK
                val rimFace = r > RADIUS - 1.2
                for (k in 1..depth) {
                    val q = k / maxDepth.toDouble() + (smooth(x / 9.0, z / 9.0, 3) - 0.5) * 0.3
                    val band = ((k + smooth(x / 11.0, z / 11.0, 4) * 6).toInt() / 3) % 4
                    val s = when {
                        rimFace && k <= 2 -> END_BRICK
                        rimFace && k == 3 -> PURPUR
                        k == depth -> if (hash(x, z, 5) < 0.3) CRYING else OBSIDIAN
                        hash3(x, k, z, 6) < 0.02 -> AMETHYST
                        q < 0.3 -> if (band == 3 && k > 3) OBSIDIAN else END_STONE
                        q < 0.6 -> when (band) { 0, 1 -> END_STONE; 2 -> OBSIDIAN; else -> BLACKSTONE }
                        else -> if (hash3(x, k, z, 7) < 0.15) CRYING else OBSIDIAN
                    }
                    g[x, -k, z] = s
                }
            }
        }

        private fun glow(g: Region, x: Int, y: Int, z: Int, glass: BlockState, light: BlockState) {
            g[x, y, z] = glass
            g[x, y - 1, z] = light
        }

        private fun ringAt(r: Double, radius: Double, width: Double = 1.0) = r >= radius - width / 2 && r < radius + width / 2

        // ---- Sternenkarte im Boden
        private fun starMap(g: Region) {
            val horizonEnd = FIELD_R + HORIZON_W
            val brickEnd = horizonEnd + BRICK_RING_W
            for (x in g.xs(-FLOOR_RADIUS - 1, FLOOR_RADIUS + 1)) for (z in g.zs(-FLOOR_RADIUS - 1, FLOOR_RADIUS + 1)) {
                val r = sqrt((x * x + z * z).toDouble())
                if (r > FLOOR_RADIUS + 0.5) continue
                val a = angleDeg(x.toDouble(), z.toDouble())
                when {
                    r < FIELD_R -> {
                        val n = smooth(x / 9.0, z / 9.0, 21)
                        g[x, 0, z] = when {
                            n < 0.22 -> DS_POLISHED
                            hash(x, z, 22) < 0.12 -> DS_TILES_CRACKED
                            else -> DS_TILES
                        }
                        val h = hash(x, z, 23)
                        if (h < 0.003) glow(g, x, 0, z, GLASS_PURPLE, SEA_LANTERN)
                        else if (h < 0.018) g[x, 0, z] = CALCITE
                        if (ringAt(r, 16.0)) g[x, 0, z] = CRYING
                        if (ringAt(r, 34.0, 0.8) && ((a / 0.9).toInt() % 3 != 0)) g[x, 0, z] = OBSIDIAN
                        if (ringAt(r, MONO_RING) && ((a / 0.8).toInt() % 2 == 0)) g[x, 0, z] = OBSIDIAN
                        if (ringAt(r, 92.0, 1.2) && ((a / 0.6).toInt() % 4 != 0)) g[x, 0, z] = CRYING
                    }
                    r < horizonEnd -> g[x, 0, z] = PURPUR
                    r < brickEnd -> g[x, 0, z] = END_BRICK
                    else -> {
                        val sector = a % BAND_SECTOR_DEG
                        val line = min(sector, BAND_SECTOR_DEG - sector) * PI / 180.0 * r < 0.7
                        g[x, 0, z] = when {
                            line -> PURPUR
                            ringAt(r, 152.0, 2.0) -> DS_POLISHED
                            ringAt(r, MONO_RING_OUT, 1.0) -> OBSIDIAN
                            else -> END_BRICK
                        }
                        // Lichter mitten in jedem Feld
                        val mid = (a % BAND_SECTOR_DEG) - BAND_SECTOR_DEG / 2
                        if (abs(mid) * PI / 180.0 * r < 0.5 && (ringAt(r, 142.0) || ringAt(r, 164.0))) glow(g, x, 0, z, GLASS_MAGENTA, FROGLIGHT)
                    }
                }
            }
            // Achsen von der Mitte zu den inneren Monolithen
            for ((mx, mz) in MONOLITHS) {
                val len = sqrt((mx * mx + mz * mz).toDouble())
                val ux = mx / len; val uz = mz / len
                var s = DAIS_R + 1.0
                var i = 0
                while (s < FIELD_R) {
                    if (abs(s - len) > MONO_HALF + 1.6) {
                        val x = (ux * s).roundToInt(); val z = (uz * s).roundToInt()
                        if (g.inside(x, 0, z)) g[x, 0, z] = if (i % 3 == 0) CRYING else OBSIDIAN
                    }
                    s += 1.0; i++
                }
            }
            write(g, STAR_CELLS)
        }

        fun constellationCells(): Cells {
            val map = LinkedHashMap<Long, Pair<IntArray, BlockState>>()
            fun put(x: Int, y: Int, z: Int, s: BlockState) {
                map[(x.toLong() shl 40) xor ((y.toLong() and 0xFFFF) shl 20) xor (z.toLong() and 0xFFFFF)] = intArrayOf(x, y, z) to s
            }
            val centers = doubleArrayOf(0.0, 72.0, 144.0, 216.0, 288.0)
            for ((w, c) in centers.withIndex()) {
                val (stars, links) = if (w == 0) whaleConstellation() else randomConstellation(w)
                val rad = Math.toRadians(c)
                val ca = cos(rad); val sa = sin(rad)
                val world = stars.map { (ra, lat) -> Pair(ra * ca - lat * sa, ra * sa + lat * ca) }
                for ((i, j) in links) {
                    val a = world[i]; val b = world[j]
                    val dx = b.first - a.first; val dz = b.second - a.second
                    val len = sqrt(dx * dx + dz * dz)
                    var s = 0.0
                    while (s <= len) {
                        val px = a.first + dx * s / len; val pz = a.second + dz * s / len
                        if (s > 2.0 && s < len - 2.0) {
                            val x = px.roundToInt(); val z = pz.roundToInt()
                            if (!nearMonolith(x, z, MONO_HALF + 1) && x * x + z * z > DAIS_R * DAIS_R) put(x, 0, z, AMETHYST)
                        }
                        s += 0.3
                    }
                }
                for ((i, p) in world.withIndex()) {
                    val x = p.first.roundToInt(); val z = p.second.roundToInt()
                    if (nearMonolith(x, z, MONO_HALF + 2)) continue
                    put(x, 0, z, GLASS_PURPLE); put(x, -1, z, SEA_LANTERN)
                    for (d in Direction.Type.HORIZONTAL) {
                        val sx = x + d.offsetX; val sz = z + d.offsetZ
                        if (i == 0) {
                            put(sx, 0, sz, GLASS_MAGENTA); put(sx, -1, sz, FROGLIGHT)
                            put(sx + d.offsetX, 0, sz + d.offsetZ, AMETHYST)
                        } else {
                            put(sx, 0, sz, AMETHYST)
                        }
                    }
                }
            }
            return toCells(map.values.toList())
        }

        private fun toCells(list: List<Pair<IntArray, BlockState>>): Cells {
            val xs = IntArray(list.size) { list[it].first[0] }
            val ys = IntArray(list.size) { list[it].first[1] }
            val zs = IntArray(list.size) { list[it].first[2] }
            val st = Array(list.size) { list[it].second }
            return Cells(xs, ys, zs, st, xs.minOrNull() ?: 0, xs.maxOrNull() ?: 0, zs.minOrNull() ?: 0, zs.maxOrNull() ?: 0)
        }

        private fun whaleConstellation(): Pair<List<Pair<Double, Double>>, List<Pair<Int, Int>>> {
            val k = 3.3
            val stars = listOf(
                31.5 to 0.5, 27.5 to -3.5, 20.0 to -6.0, 14.0 to -6.0, 10.5 to -1.0,
                8.5 to -6.0, 8.0 to 4.5, 17.0 to 4.5, 27.0 to 3.5, 18.5 to 9.5,
            ).map { (a, b) -> a * k to b * k }
            val links = listOf(0 to 1, 1 to 2, 2 to 3, 3 to 4, 4 to 5, 4 to 6, 4 to 7, 7 to 8, 8 to 0, 7 to 9)
            return stars to links
        }

        private fun randomConstellation(w: Int): Pair<List<Pair<Double, Double>>, List<Pair<Int, Int>>> {
            val rnd = java.util.Random(4242L + w * 977L)
            val n = 7 + rnd.nextInt(3)
            val pts = ArrayList<Pair<Double, Double>>()
            var guard = 0
            while (pts.size < n && guard++ < 3000) {
                val ra = DAIS_R + 10.0 + rnd.nextDouble() * (FIELD_R - DAIS_R - 20.0)
                val lat = (rnd.nextDouble() * 2 - 1) * ra * 0.5
                if (pts.any { (pa, pl) -> (pa - ra) * (pa - ra) + (pl - lat) * (pl - lat) < 16.0 * 16.0 }) continue
                val rad = Math.toRadians(w * 72.0)
                val wx = ra * cos(rad) - lat * sin(rad); val wz = ra * sin(rad) + lat * cos(rad)
                if (nearMonolith(wx.roundToInt(), wz.roundToInt(), MONO_HALF + 3)) continue
                pts.add(ra to lat)
            }
            val order = ArrayList<Pair<Double, Double>>()
            val left = pts.sortedBy { it.first }.toMutableList()
            order.add(left.removeAt(0))
            while (left.isNotEmpty()) {
                val last = order.last()
                val next = left.minBy { (a, l) -> (a - last.first) * (a - last.first) + (l - last.second) * (l - last.second) }
                left.remove(next); order.add(next)
            }
            val links = ArrayList<Pair<Int, Int>>()
            for (i in 0 until order.size - 1) links.add(i to i + 1)
            if (order.size >= 5 && rnd.nextBoolean()) links.add(order.size - 1 to order.size - 4)
            return order to links
        }

        private fun nearMonolith(x: Int, z: Int, dist: Int) = (MONOLITHS + MONOLITHS_OUT).any { (mx, mz) -> abs(mx - x) <= dist && abs(mz - z) <= dist }

        // ---- Sockel mit dem Ruf der Tiefe
        private fun dais(g: Region) {
            if (!g.hits(-10.0, 10.0, -10.0, 10.0)) return
            val r0 = DAIS_R.toInt() + 1
            for (x in g.xs(-r0, r0)) for (z in g.zs(-r0, r0)) {
                val r = sqrt((x * x + z * z).toDouble())
                if (r > DAIS_R) continue
                g[x, 0, z] = when {
                    r < 1.5 -> DS_CHISELED
                    r < 3.5 -> CRYING
                    r < 6.0 -> AMETHYST
                    else -> END_BRICK
                }
                if (r >= DAIS_R - 1.1) g[x, 1, z] = PURPUR_SLAB
            }
            for ((mx, mz) in MONOLITHS) {
                val len = sqrt((mx * mx + mz * mz).toDouble())
                val x = (mx / len * (DAIS_R - 0.6)).roundToInt(); val z = (mz / len * (DAIS_R - 0.6)).roundToInt()
                g[x, 1, z] = PURPUR_PILLAR
                g[x, 2, z] = ROD_UP
            }
        }

        // ---- Rand
        private fun inPathGap(a: Double) = PATH_STARTS.any { angleDist(a, it) < PATH_GAP_DEG }

        private fun rim(g: Region) {
            if (!g.hits(-RADIUS - 1.0, RADIUS + 1.0, -RADIUS - 1.0, RADIUS + 1.0)) return
            for (x in g.xs(-RADIUS - 1, RADIUS + 1)) for (z in g.zs(-RADIUS - 1, RADIUS + 1)) {
                val r = sqrt((x * x + z * z).toDouble())
                if (r <= FLOOR_RADIUS + 0.5 || r > RADIUS + 0.5) continue
                val a = angleDeg(x.toDouble(), z.toDouble())
                g[x, 0, z] = END_STONE
                g[x, 1, z] = if (r < FLOOR_RADIUS + 1.4) PURPUR else END_BRICK
                if (r >= PARAPET_FROM && !inPathGap(a)) g[x, 2, z] = PURPUR_SLAB
            }
            val stepMid = (FLOOR_RADIUS + RADIUS) / 2.0 + 0.5
            val postR = RADIUS - 0.1
            val posts = (360.0 / POST_STEP_DEG).toInt()
            for (i in 0 until posts) {
                val deg = POST_STEP_DEG / 2 + i * POST_STEP_DEG
                val a = Math.toRadians(deg)
                val mx = (cos(a) * stepMid).roundToInt(); val mz = (sin(a) * stepMid).roundToInt()
                if (g.inside(mx, 1, mz)) glow(g, mx, 1, mz, GLASS_MAGENTA, FROGLIGHT)
                if (PATH_STARTS.any { angleDist(deg, it) < PATH_GAP_DEG + 2 }) continue
                if (BASTIONS.any { angleDist(deg, it) < BASTION_HALF_DEG + 1 }) continue
                val px = (cos(a) * postR).roundToInt(); val pz = (sin(a) * postR).roundToInt()
                if (!g.inside(px, 2, pz)) continue
                g[px, 2, pz] = PURPUR_PILLAR
                g[px, 3, pz] = PURPUR_PILLAR
                g[px, 4, pz] = if (i % 2 == 0) SOUL_LANTERN else LANTERN
            }
            for (p in PATH_STARTS) for (side in intArrayOf(-1, 1)) {
                val a = Math.toRadians(p + side * (PATH_GAP_DEG + 0.4))
                val px = (cos(a) * postR).roundToInt(); val pz = (sin(a) * postR).roundToInt()
                if (!g.inside(px, 2, pz)) continue
                for (y in 2..7) g[px, y, pz] = if (y == 7) AMETHYST else PURPUR_PILLAR
                g[px, 8, pz] = ROD_UP
            }
        }

        // ---- Bastion: erhöhte Plattform am Rand (y 7), Rampe zur Mitte
        private fun bastion(g: Region, angle: Double) {
            val rad = Math.toRadians(angle)
            val cx = cos(rad) * 172.0; val cz = sin(rad) * 172.0
            if (!g.hits(cx - 22, cx + 22, cz - 22, cz + 22)) return
            val r1 = RADIUS + 3.0
            for (x in g.xs((cx - 22).toInt(), (cx + 22).toInt())) for (z in g.zs((cz - 22).toInt(), (cz + 22).toInt())) {
                val r = sqrt((x * x + z * z).toDouble())
                if (r < BASTION_INNER - 9.0 || r > r1) continue
                val off = angleDist(angleDeg(x.toDouble(), z.toDouble()), angle)
                val halfWidth = BASTION_HALF_DEG * (if (r < BASTION_INNER) 0.55 else 1.0)
                if (off > halfWidth) continue
                if (r >= BASTION_INNER) {
                    for (y in 1 until BASTION_TOP) {
                        g[x, y, z] = if (y == BASTION_TOP - 1 && off > halfWidth - 0.3) PURPUR else if ((x + y + z) % 5 == 0) BS_CRACKED else BS_BRICKS
                    }
                    g[x, BASTION_TOP, z] = if (hash(x, z, 61) < 0.08) CRYING else END_BRICK
                    if (r > RADIUS) for (k in 1..(4 + (hash(x, z, 62) * 6).toInt())) g[x, -k, z] = if (k > 3) OBSIDIAN else END_STONE
                    if (off > halfWidth - 0.35 || r > r1 - 0.8) g[x, BASTION_TOP + 1, z] = PURPUR_SLAB
                } else {
                    val h = ((r - (BASTION_INNER - 9.0)) / 1.3).toInt().coerceIn(0, BASTION_TOP - 1)
                    for (y in 1..h) g[x, y, z] = END_BRICK
                }
            }
            val bx = (cos(rad) * (RADIUS - 3.0)).roundToInt(); val bz = (sin(rad) * (RADIUS - 3.0)).roundToInt()
            if (g.inside(bx, BASTION_TOP, bz)) {
                g[bx, BASTION_TOP + 1, bz] = AMETHYST
                g[bx, BASTION_TOP + 2, bz] = SEA_LANTERN
                for (i in 3..6) g[bx, BASTION_TOP + i, bz] = ROD_UP
            }
        }

        // ---- Ruinenmauer (Deckung bis Brusthöhe)
        private fun ruin(g: Region, angle: Double, r: Double, seed: Int) {
            val rad = Math.toRadians(angle)
            val ux = cos(rad); val uz = sin(rad)
            if (!g.hits(ux * r - 10, ux * r + 10, uz * r - 10, uz * r + 10)) return
            val tx = -uz; val tz = ux
            val half = 4 + seed % 3
            for (i in -half..half) {
                val x = (ux * r + tx * i).roundToInt(); val z = (uz * r + tz * i).roundToInt()
                val h = (2 + 3.0 * (1 - abs(i) / (half + 1.0)) + hash(x, z, 71 + seed) * 2.2).toInt()
                if (hash(x, z, 72 + seed) < 0.15) continue
                for (y in 1..h) {
                    val s = when {
                        y == h && hash3(x, y, z, 73) < 0.4 -> BS_CRACKED
                        y == 1 -> BS_CHISELED
                        else -> if (hash3(x, y, z, 74) < 0.2) DS_TILES_CRACKED else BS_BRICKS
                    }
                    g[x, y, z] = s
                    g[x + (if (abs(ux) > abs(uz)) 1 else 0), y, z + (if (abs(ux) > abs(uz)) 0 else 1)] = s
                }
            }
            for (k in 0 until 4) {
                val ox = (ux * (r - 3.0 - hash(seed, k, 75) * 3) + tx * (hash(seed, k, 76) * 2 - 1) * half).roundToInt()
                val oz = (uz * (r - 3.0 - hash(seed, k, 75) * 3) + tz * (hash(seed, k, 76) * 2 - 1) * half).roundToInt()
                g[ox, 1, oz] = if (k % 2 == 0) BS_CRACKED else END_BRICK_STAIRS
            }
        }

        // ---- Monolith: Walwirbel-Stele, 5×5 voll bis y 14
        private fun monolith(g: Region, cx: Int, cz: Int) {
            if (!g.hits(cx - 5.0, cx + 5.0, cz - 5.0, cz + 5.0)) return
            val h = MONO_HALF
            val fx = if (abs(cx) >= abs(cz)) -Integer.signum(cx) else 0
            val fz = if (abs(cx) >= abs(cz)) 0 else -Integer.signum(cz)
            for (dx in -h - 1..h + 1) for (dz in -h - 1..h + 1) {
                if (abs(dx) == h + 1 || abs(dz) == h + 1) {
                    g[cx + dx, 0, cz + dz] = if (abs(dx) == h + 1 && abs(dz) == h + 1) BS_CHISELED else BS_BRICKS
                }
            }
            for (dx in -h..h) for (dz in -h..h) {
                val shell = abs(dx) == h || abs(dz) == h
                val corner = abs(dx) == h && abs(dz) == h
                val onFront = (fx != 0 && dx == fx * h) || (fz != 0 && dz == fz * h)
                val onBack = (fx != 0 && dx == -fx * h) || (fz != 0 && dz == -fz * h)
                val lateral = if (fx != 0) dz else dx
                for (y in 1..MONO_HEIGHT) {
                    val band = y % 4 == 0
                    val s = when {
                        !shell -> OBSIDIAN
                        y == 1 -> DS_TILES
                        band -> if (corner) DS_POLISHED else DS_TILES
                        corner -> CALCITE
                        onFront && ((lateral == 0 && y in 6..9) || (abs(lateral) == 1 && y == 7)) -> CRYING
                        onBack && lateral == 0 && y in 6..9 -> AMETHYST
                        else -> bone(Direction.Axis.Y)
                    }
                    g[cx + dx, y, cz + dz] = s
                }
            }
            val top = MONO_HEIGHT + 1
            for (dx in -2..2) for (dz in -2..2) g[cx + dx, top, cz + dz] = if (dx == 0 && dz == 0) AMETHYST else CALCITE
            g[cx, top + 1, cz] = AMETHYST
            g[cx, top + 2, cz] = CLUSTER_UP
            val spurY = MONO_HEIGHT - 3
            for (d in Direction.Type.HORIZONTAL) {
                val ax = if (d.offsetX != 0) Direction.Axis.X else Direction.Axis.Z
                for (k in 1..2) g[cx + d.offsetX * (h + k), spurY, cz + d.offsetZ * (h + k)] = bone(ax)
                g[cx + d.offsetX * (h + 2), spurY + 1, cz + d.offsetZ * (h + 2)] = if ((cx + cz + d.ordinal) % 2 == 0) SOUL_LANTERN else BUD_UP
            }
        }

        // ---- Walrippen-Bögen über dem Rand
        fun ribcageCells(angle: Double): Cells {
            val out = ArrayList<Pair<IntArray, BlockState>>()
            val rad = Math.toRadians(angle)
            val ux = cos(rad); val uz = sin(rad)
            val tx = -uz; val tz = ux
            for (o in doubleArrayOf(-24.0, -12.0, 0.0, 12.0, 24.0)) {
                val s = 1.0 - abs(o) / 24.0 * 0.22
                rib(out, ux, uz, tx, tz, o, s)
            }
            val spineR = RADIUS + 7.0
            val ax = if (abs(tx) > abs(tz)) Direction.Axis.X else Direction.Axis.Z
            for (i in -28..28) {
                val px = ux * spineR + tx * i; val pz = uz * spineR + tz * i
                val y = if (abs(i) % 5 == 0) -22 else -21
                for (dy in 0..3) for (w in -1..1) {
                    val wx = (px + ux * w).roundToInt(); val wz = (pz + uz * w).roundToInt()
                    out.add(intArrayOf(wx, y - dy, wz) to if (abs(i) % 5 == 0) CALCITE else bone(ax))
                }
            }
            return toCells(out)
        }

        private fun rib(out: ArrayList<Pair<IntArray, BlockState>>, ux: Double, uz: Double, tx: Double, tz: Double, o: Double, s: Double) {
            val r0 = RADIUS + 6.0; val y0 = -20.0
            val r1 = RADIUS + 15.0; val y1 = 24.0 * s
            val r2 = FLOOR_RADIUS - 1.0 + (1 - s) * 8.0; val y2 = 12.0 * s
            data class Hit(val d: Double, val dirR: Double, val dirY: Double)
            val best = HashMap<Long, Hit>()
            fun pk(x: Int, y: Int, z: Int) = (x.toLong() and 0xFFFF) or ((y.toLong() and 0xFFFF) shl 16) or ((z.toLong() and 0xFFFF) shl 32)
            var t = 0.0
            while (t <= 1.0) {
                val mt = 1 - t
                val r = mt * mt * r0 + 2 * t * mt * r1 + t * t * r2
                val y = mt * mt * y0 + 2 * t * mt * y1 + t * t * y2
                val dR = 2 * mt * (r1 - r0) + 2 * t * (r2 - r1)
                val dY = 2 * mt * (y1 - y0) + 2 * t * (y2 - y1)
                val px = ux * r + tx * o; val pz = uz * r + tz * o
                val thick = (3.2 - 1.7 * t) * (0.85 + 0.15 * s)
                val reach = ceil(thick).toInt() + 1
                val bx = px.roundToInt(); val by = y.roundToInt(); val bz = pz.roundToInt()
                for (ix in bx - reach..bx + reach) for (iy in by - reach..by + reach) for (iz in bz - reach..bz + reach) {
                    val d = sqrt((ix - px) * (ix - px) + (iy - y) * (iy - y) + (iz - pz) * (iz - pz))
                    if (d > thick) continue
                    val k = pk(ix, iy, iz)
                    val prev = best[k]
                    if (prev == null || d / thick < prev.d) best[k] = Hit(d / thick, dR, dY)
                }
                t += 0.0025
            }
            for ((k, h) in best) {
                val x = (k and 0xFFFF).toShort().toInt()
                val y = ((k shr 16) and 0xFFFF).toShort().toInt()
                val z = ((k shr 32) and 0xFFFF).toShort().toInt()
                if (y in 0..2 && x * x + z * z <= (RADIUS + 0.5) * (RADIUS + 0.5)) continue
                val axis = when {
                    abs(h.dirY) > abs(h.dirR) -> Direction.Axis.Y
                    abs(ux) > abs(uz) -> Direction.Axis.X
                    else -> Direction.Axis.Z
                }
                out.add(intArrayOf(x, y, z) to when {
                    h.d < 0.55 -> bone(axis)
                    hash3(x, y, z, 31) < 0.22 -> DIORITE
                    else -> CALCITE
                })
            }
        }

        // ---- Inseln: Sprungpfade, Außenplattformen, Trümmer
        fun isles(): List<Isle> {
            val out = ArrayList<Isle>()
            val pathSize = 2.8
            for ((pi, start) in PATH_STARTS.withIndex()) {
                val platformTop = 9 + (pi % 3) * 4
                var theta = Math.toRadians(start)
                var r = RADIUS + 0.5 + pathSize + 1.2
                val drift = if (pi % 2 == 0) 1.0 else -1.0
                var top = 1
                while (top < platformTop) {
                    out.add(Isle(cos(theta) * r, sin(theta) * r, top, pathSize, 0, 100 + pi * 50 + top))
                    // nächster Stein 6,3 Blöcke weiter, eine Stufe höher; schräg nach außen
                    val dr = 2.4
                    theta += drift * sqrt(6.3 * 6.3 - dr * dr) / r
                    r += dr
                    top++
                }
                // Außenplattform mit Harpunen-Geschütz direkt hinter dem letzten Stein
                val size = 12.0
                r += size + 1.0
                out.add(Isle(cos(theta) * r, sin(theta) * r, platformTop, size, 3, 190 + pi))
            }
            val rnd = java.util.Random(31415926L)
            var guard = 0
            var made = 0
            while (made < 72 && guard++ < 20000) {
                val a = rnd.nextDouble() * 360.0
                val size = 3.0 + rnd.nextDouble().pow(1.4) * 8.0
                val top = 3 + rnd.nextInt(46)
                val inner = RADIUS + 10.0
                val span = 270.0 - inner - 2 * size
                if (span <= 0) continue
                val r = inner + size + rnd.nextDouble() * span
                if (RIB_ANGLES.any { angleDist(a, it) < 9 } && r - size < RADIUS + 22 && top < 26) continue
                if (BASTIONS.any { angleDist(a, it) < 5 } && r - size < RADIUS + 6) continue
                val x = cos(Math.toRadians(a)) * r; val z = sin(Math.toRadians(a)) * r
                val clash = out.any { o ->
                    val dh = sqrt((o.x - x) * (o.x - x) + (o.z - z) * (o.z - z))
                    val gap = if (o.kind == 3) 9.0 else if (o.kind == 0) 6.0 else 4.0
                    dh < o.size + size + gap && abs(o.top - top) < (o.size + size) * 1.4 + 6
                }
                if (clash) continue
                out.add(Isle(x, z, top, size, 1, 300 + made))
                made++
            }
            out.filter { it.kind == 1 && it.size > 6.0 }.sortedByDescending { it.top }.take(4).forEach {
                out[out.indexOf(it)] = Isle(it.x, it.z, it.top, it.size, 2, it.seed)
            }
            return out
        }

        fun islandCells(isle: Isle): Cells {
            val map = LinkedHashMap<Long, Pair<IntArray, BlockState>>()
            fun key(x: Int, y: Int, z: Int) = (x.toLong() shl 40) xor ((y.toLong() and 0xFFFF) shl 20) xor (z.toLong() and 0xFFFFF)
            fun put(x: Int, y: Int, z: Int, s: BlockState) { map[key(x, y, z)] = intArrayOf(x, y, z) to s }
            fun solid(x: Int, y: Int, z: Int) = map[key(x, y, z)]?.second?.isAir == false
            val s = isle.size
            val reach = ceil(s * 1.2).toInt() + 1
            val bx = isle.x.roundToInt(); val bz = isle.z.roundToInt()
            val tops = ArrayList<Pair<Int, Int>>()
            val flat = isle.kind == 0 || isle.kind == 3
            for (x in bx - reach..bx + reach) for (z in bz - reach..bz + reach) {
                val d = sqrt((x - isle.x) * (x - isle.x) + (z - isle.z) * (z - isle.z))
                val e = if (flat) s else s * (0.8 + 0.4 * smooth(x / 3.5, z / 3.5, isle.seed))
                if (d > e) continue
                val q = d / e
                val depth = 1 + ((1 - q * q).pow(0.7) * s * (if (isle.kind == 3) 1.0 else 1.35) + hash(x, z, isle.seed) * 2.0).toInt()
                val dome = if (isle.kind == 1 || isle.kind == 2) (if (s > 4.2 && q < 0.45) 1 else 0) else 0
                val top = isle.top + dome
                for (k in 0..depth) {
                    val y = top - k
                    val st = when {
                        k == 0 -> when {
                            isle.kind == 0 -> if (hash(x, z, 41) < 0.2) END_STONE else END_BRICK
                            isle.kind == 3 -> when {
                                q < 0.18 -> CRYING
                                q > 0.9 -> PURPUR
                                hash(x, z, isle.seed + 3) < 0.12 -> DS_TILES_CRACKED
                                else -> if (((x + z) and 1) == 0) END_BRICK else DS_TILES
                            }
                            hash(x, z, isle.seed + 1) < 0.18 -> END_BRICK
                            hash(x, z, isle.seed + 2) < 0.05 -> PURPUR
                            else -> END_STONE
                        }
                        k <= 2 -> END_STONE
                        k == depth -> if (hash(x, z, 42) < 0.3) CRYING else OBSIDIAN
                        hash3(x, k, z, 43) < 0.04 -> AMETHYST
                        q + hash3(x, k, z, 44) * 0.3 > 0.85 || k > depth * 0.6 -> OBSIDIAN
                        else -> END_STONE
                    }
                    put(x, y, z, st)
                }
                if (hash(x, z, 45) < 0.12 && depth > 3) put(x, isle.top - depth - 1, z, CLUSTER_DOWN)
                tops.add(x to z)
            }
            val topY = { x: Int, z: Int ->
                var y = isle.top + 1
                while (solid(x, y, z)) y++
                y
            }
            when (isle.kind) {
                0 -> if (isle.seed % 3 == 0) {
                    val r = isle.r
                    val lx = (isle.x + isle.x / r * 1.6).roundToInt(); val lz = (isle.z + isle.z / r * 1.6).roundToInt()
                    if (solid(lx, isle.top, lz)) put(lx, isle.top + 1, lz, SOUL_LANTERN)
                }
                1 -> decorate(isle, tops, topY, 3 + isle.size.toInt(), ::put, ::solid)
                2 -> {
                    decorate(isle, tops, topY, 5, ::put, ::solid)
                    beacon(bx, bz, isle.top + (if (isle.size > 4.2) 1 else 0), ::put)
                }
                3 -> outpost(isle, ::put)
            }
            return toCells(map.values.toList())
        }

        /** Außenplattform: Harpunen-Geschütz in der Mitte, Deckungsmauer zur Arena hin, Leuchten am Rand. */
        private fun outpost(isle: Isle, put: (Int, Int, Int, BlockState) -> Unit) {
            val bx = isle.x.roundToInt(); val bz = isle.z.roundToInt()
            val y = isle.top + 1
            put(bx, y, bz, harpoon)
            for (d in Direction.Type.HORIZONTAL) {
                put(bx + d.offsetX, isle.top, bz + d.offsetZ, AMETHYST)
                put(bx + d.offsetX * 2, isle.top, bz + d.offsetZ * 2, SEA_LANTERN)
            }
            // Deckungsmauer (Bogen 120°) auf der Seite zur Arena, mit zwei Lücken
            val toward = atan2(-isle.z, -isle.x)
            val wallR = isle.size - 2.5
            var a = -1.05
            while (a <= 1.05) {
                if (abs(a) in 0.42..0.58) { a += 0.04; continue }
                val wx = (isle.x + cos(toward + a) * wallR).roundToInt(); val wz = (isle.z + sin(toward + a) * wallR).roundToInt()
                val h = 3 + (hash(wx, wz, 91) * 1.6).toInt()
                for (k in 1..h) put(wx, isle.top + k, wz, if (k == h && hash3(wx, k, wz, 92) < 0.4) BS_CRACKED else BS_BRICKS)
                a += 0.04
            }
            // Leuchten rundherum
            for (i in 0 until 8) {
                val ang = toward + PI + (i - 3.5) * 0.45
                val lx = (isle.x + cos(ang) * (isle.size - 1.2)).roundToInt(); val lz = (isle.z + sin(ang) * (isle.size - 1.2)).roundToInt()
                put(lx, isle.top + 1, lz, PURPUR_PILLAR)
                put(lx, isle.top + 2, lz, if (i % 2 == 0) SOUL_LANTERN else LANTERN)
            }
        }

        private fun decorate(
            isle: Isle, tops: List<Pair<Int, Int>>, topY: (Int, Int) -> Int, count: Int,
            put: (Int, Int, Int, BlockState) -> Unit, solid: (Int, Int, Int) -> Boolean,
        ) {
            val rnd = java.util.Random(isle.seed * 7919L)
            repeat(count) {
                val (x, z) = tops[rnd.nextInt(tops.size)]
                if (abs(x - isle.x) < 2.6 && abs(z - isle.z) < 2.6) return@repeat
                val y = topY(x, z)
                if (solid(x, y, z)) return@repeat
                put(x, y, z, when (rnd.nextInt(5)) { 0 -> ROD_UP; 1 -> BUD_UP; else -> CLUSTER_UP })
            }
            if (isle.kind == 1 && isle.size > 3.5) {
                val (x, z) = tops[rnd.nextInt(tops.size)]
                val y = topY(x, z)
                val h = 1 + rnd.nextInt(3)
                for (i in 0 until h) put(x, y + i, z, if (i == h - 1 && rnd.nextBoolean()) CALCITE else PURPUR_PILLAR)
                put(x, y + h, z, if (rnd.nextBoolean()) LANTERN else ROD_UP)
            }
        }

        private fun beacon(x: Int, z: Int, top: Int, put: (Int, Int, Int, BlockState) -> Unit) {
            for (d in Direction.Type.HORIZONTAL) {
                put(x + d.offsetX, top + 1, z + d.offsetZ, CRYING)
                put(x + d.offsetX * 2, top + 1, z + d.offsetZ * 2, CLUSTER_UP)
            }
            put(x, top + 1, z, AMETHYST)
            put(x, top + 2, z, SEA_LANTERN)
            for (i in 3..8) put(x, top + i, z, ROD_UP)
        }

        private fun hangingCrystals(g: Region) {
            for (c in CRYSTALS) {
                if (!g.inside(c.x, 0, c.z)) continue
                val r = sqrt((c.x * c.x + c.z * c.z).toDouble())
                var y = -undersideDepth(c.x, c.z, r) - 1
                for (i in 0 until c.len) { g[c.x, y, c.z] = if (i == c.len - 1 && c.glow) FROGLIGHT else AMETHYST; y-- }
                g[c.x, y, c.z] = c.tip
            }
        }

        private fun liftShaft(g: Region) {
            if (!g.hits(LIFT_X - 3.0, LIFT_X + 3.0, LIFT_Z - 3.0, LIFT_Z + 3.0)) return
            for (y in Y_MIN..0) g[LIFT_X, y, LIFT_Z] = lift
            for (d in Direction.Type.HORIZONTAL) {
                g[LIFT_X + d.offsetX, 0, LIFT_Z + d.offsetZ] = CRYING
                g[LIFT_X + d.offsetX * 2, 0, LIFT_Z + d.offsetZ * 2] = AMETHYST
            }
            for ((dx, dz) in listOf(1 to 1, -1 to 1, 1 to -1, -1 to -1)) g[LIFT_X + dx, 0, LIFT_Z + dz] = GLASS_MAGENTA
        }
    }

    // ---------------------------------------------------------------- natürliche Erzeugung: wo stehen Arenen?

    /** Struktur-Set der Arena (für die Kacheln, die dieselben Startpunkte rechnen). */
    val SET_ID: Identifier = Identifier.of("hha_void", "void_tribunals")

    /** Bodenmitte einer Arena, deren Haupt-Struktur in [chunk] startet – oder null, wenn dort keine entsteht. */
    fun siteAt(context: Structure.Context, chunk: ChunkPos): BlockPos? {
        val x = chunk.getOffsetX(8); val z = chunk.getOffsetZ(8)
        val ground = context.chunkGenerator().getHeightInGround(x, z, Heightmap.Type.WORLD_SURFACE_WG, context.world(), context.noiseConfig())
        if (ground < 45) return null
        val sampler = context.noiseConfig().multiNoiseSampler
        val biome = context.biomeSource().getBiome(BiomeCoords.fromBlock(x), BiomeCoords.fromBlock(FLOOR_Y), BiomeCoords.fromBlock(z), sampler)
        if (!biome.matchesKey(BiomeKeys.END_HIGHLANDS) && !biome.matchesKey(BiomeKeys.END_MIDLANDS)) return null
        return BlockPos(x, FLOOR_Y, z)
    }

    /** Unterkante des Aufzugs relativ zum Boden (Insel darunter oder 104 Blöcke tief). */
    fun liftBottom(context: Structure.Context, center: BlockPos): Int {
        val liftGround = context.chunkGenerator().getHeightInGround(
            center.x + LIFT_X, center.z + LIFT_Z, Heightmap.Type.WORLD_SURFACE_WG, context.world(), context.noiseConfig(),
        )
        return if (liftGround > 10) liftGround + 1 - center.y else -104
    }
}

/**
 * Natürliche Erzeugung im End: über den äußeren Inseln, Boden hoch am Himmel ([TribunalBuilder.FLOOR_Y]).
 * Diese Haupt-Struktur erzeugt alles im Umkreis von 8 Chunks um ihren Start; den Rest der (größeren) Arena legen die
 * Kacheln ([VoidTribunalTileStructure]) Chunk für Chunk.
 */
class VoidTribunalStructure(config: Config) : Structure(config) {

    override fun getStructurePosition(context: Context): Optional<StructurePosition> {
        if (DEBUG && debugCalls++ < 6) println("[tribunal] main check ${context.chunkPos()}")
        val center = TribunalBuilder.siteAt(context, context.chunkPos()) ?: return Optional.empty()
        if (DEBUG) println("[tribunal] main start at ${context.chunkPos()} center $center")
        val liftBottom = TribunalBuilder.liftBottom(context, center)
        return Optional.of(StructurePosition(center) { collector -> collector.addPiece(VoidTribunalPiece(center, liftBottom)) })
    }

    override fun getType(): StructureType<*> = VoidBoss.TRIBUNAL_TYPE

    companion object {
        val CODEC: MapCodec<VoidTribunalStructure> = createCodec(::VoidTribunalStructure)
    }
}

class VoidTribunalPiece : StructurePiece {

    private val center: BlockPos
    private val liftBottom: Int

    /** Chunk-Kachel: nur dieser Chunk (sonst die ganze Arena). */
    private val tile: ChunkPos?

    constructor(center: BlockPos, liftBottom: Int, tile: ChunkPos? = null) : super(
        VoidBoss.TRIBUNAL_PIECE, 0,
        if (tile == null) TribunalBuilder.bounds(center, liftBottom) else tileBox(center, liftBottom, tile),
    ) {
        this.center = center
        this.liftBottom = liftBottom
        this.tile = tile
    }

    constructor(nbt: NbtCompound) : super(VoidBoss.TRIBUNAL_PIECE, nbt) {
        center = BlockPos(nbt.getInt("CX", 0), nbt.getInt("CY", 0), nbt.getInt("CZ", 0))
        liftBottom = nbt.getInt("Lift", 0)
        tile = if (nbt.getBoolean("Tile", false)) ChunkPos(nbt.getInt("TX", 0), nbt.getInt("TZ", 0)) else null
    }

    override fun writeNbt(context: StructureContext, nbt: NbtCompound) {
        nbt.putInt("CX", center.x)
        nbt.putInt("CY", center.y)
        nbt.putInt("CZ", center.z)
        nbt.putInt("Lift", liftBottom)
        nbt.putBoolean("Tile", tile != null)
        tile?.let { nbt.putInt("TX", it.x); nbt.putInt("TZ", it.z) }
    }

    override fun generate(
        world: StructureWorldAccess, structureAccessor: StructureAccessor, chunkGenerator: ChunkGenerator,
        random: Random, chunkBox: BlockBox, chunkPos: ChunkPos, pivot: BlockPos,
    ) {
        // Weltgenerierung ruft die Haupt-Struktur nur in Chunks mit Referenz auf (8 Chunks um den Start), `/place structure`
        // dagegen für die ganze Box – beides erzeugt genau diesen Chunk; den Rest im Weltlauf legen die Kacheln
        TribunalBuilder.generateIn(world, center, chunkBox, liftBottom)
    }

    companion object {
        /** Chunks um den Start, die die Haupt-Struktur selbst erzeugt (Referenzradius 8). */
        const val INNER_CHUNKS = 7

        fun tileBox(center: BlockPos, liftBottom: Int, tile: ChunkPos): BlockBox {
            val all = TribunalBuilder.bounds(center, liftBottom)
            return BlockBox(tile.startX, all.minY, tile.startZ, tile.endX, all.maxY, tile.endZ)
        }
    }
}

/**
 * Kachel der Arena: prüft für jeden Chunk, ob er im Bereich einer Arena liegt, die die Haupt-Struktur in einem nahen
 * Startchunk erzeugt (gleiche Platzierung/Salt wie `void_tribunals`), aber weiter als 7 Chunks davon entfernt ist –
 * dann erzeugt diese Kachel genau diesen Chunk. Trägt dieselben `spawn_overrides` (keine Monster auf der Arena).
 */
class VoidTribunalTileStructure(config: Config) : Structure(config) {

    override fun getStructurePosition(context: Context): Optional<StructurePosition> {
        val set = context.dynamicRegistryManager().getOrThrow(RegistryKeys.STRUCTURE_SET).get(TribunalBuilder.SET_ID) ?: return Optional.empty()
        val placement = set.placement() as? RandomSpreadStructurePlacement ?: return Optional.empty()
        val chunk = context.chunkPos()
        val spacing = placement.spacing
        val rx = Math.floorDiv(chunk.x, spacing); val rz = Math.floorDiv(chunk.z, spacing)
        val reach = TribunalBuilder.EXTENT / 16 + 1
        for (ox in -1..1) for (oz in -1..1) {
            val start = placement.getStartChunk(context.seed(), (rx + ox) * spacing, (rz + oz) * spacing)
            val dx = chunk.x - start.x; val dz = chunk.z - start.z
            if (abs(dx) > reach || abs(dz) > reach) continue
            if (abs(dx) <= VoidTribunalPiece.INNER_CHUNKS && abs(dz) <= VoidTribunalPiece.INNER_CHUNKS) continue
            // nur Chunks, die die Arena wirklich berührt (Kreis)
            val cx = chunk.getOffsetX(8) - start.getOffsetX(8); val cz = chunk.getOffsetZ(8) - start.getOffsetZ(8)
            if (sqrt((cx * cx + cz * cz).toDouble()) > TribunalBuilder.EXTENT + 12) continue
            val center = TribunalBuilder.siteAt(context, start) ?: continue
            if (DEBUG && (chunk.x + chunk.z) % 37 == 0) println("[tribunal] tile $chunk for $center")
            val liftBottom = TribunalBuilder.liftBottom(context, center)
            val pos = BlockPos(chunk.getOffsetX(8), TribunalBuilder.FLOOR_Y, chunk.getOffsetZ(8))
            return Optional.of(StructurePosition(pos) { collector -> collector.addPiece(VoidTribunalPiece(center, liftBottom, chunk)) })
        }
        return Optional.empty()
    }

    override fun getType(): StructureType<*> = VoidBoss.TRIBUNAL_TILE_TYPE

    companion object {
        val CODEC: MapCodec<VoidTribunalTileStructure> = createCodec(::VoidTribunalTileStructure)
    }
}

/** Test-Log der natürlichen Erzeugung (`-Dhha_void.debugTribunal=true`). */
internal val DEBUG = System.getProperty("hha_void.debugTribunal") == "true" || System.getenv("HHA_VOID_TEST")?.contains("arena") == true
internal var debugCalls = 0
