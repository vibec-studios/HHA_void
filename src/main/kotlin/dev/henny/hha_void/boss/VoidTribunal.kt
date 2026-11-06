package dev.henny.hha_void.boss

import com.mojang.serialization.MapCodec
import net.minecraft.block.Block
import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.nbt.NbtCompound
import net.minecraft.server.world.ServerWorld
import net.minecraft.structure.StructureContext
import net.minecraft.structure.StructurePiece
import net.minecraft.util.BlockRotation
import net.minecraft.util.math.BlockBox
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.ChunkPos
import net.minecraft.util.math.random.Random
import net.minecraft.world.StructureWorldAccess
import net.minecraft.world.gen.StructureAccessor
import net.minecraft.world.gen.chunk.ChunkGenerator
import net.minecraft.world.gen.structure.Structure
import net.minecraft.world.gen.structure.StructureType
import java.util.Optional
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Das Tribunal: schwebender Gerichtssaal aus Schwarzstein und Obsidian. Runde Plattform,
 * Säulen als Deckung vor dem Erlass, Rang mit Akten ringsum und ein Lichtkranz darüber.
 * Rein prozedural, damit Weltgenerierung und `/voidboss place` denselben Bau liefern.
 */
object TribunalBuilder {

    const val RADIUS = 28
    private const val CLEAR_HEIGHT = 26

    private val TILE = Blocks.POLISHED_BLACKSTONE_BRICKS.defaultState
    private val TILE_CRACKED = Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS.defaultState
    private val POLISHED = Blocks.POLISHED_BLACKSTONE.defaultState
    private val CHISELED = Blocks.CHISELED_POLISHED_BLACKSTONE.defaultState
    private val OBSIDIAN = Blocks.OBSIDIAN.defaultState
    private val CRYING = Blocks.CRYING_OBSIDIAN.defaultState
    private val AMETHYST = Blocks.AMETHYST_BLOCK.defaultState
    private val END_BRICK = Blocks.END_STONE_BRICKS.defaultState
    private val END_STONE = Blocks.END_STONE.defaultState
    private val WALL = Blocks.POLISHED_BLACKSTONE_BRICK_WALL.defaultState
    private val SHELF = Blocks.CHISELED_BOOKSHELF.defaultState
    private val ROD = Blocks.END_ROD.defaultState
    private val GLASS = Blocks.PURPLE_STAINED_GLASS.defaultState
    private val AIR = Blocks.AIR.defaultState

    private fun hash(x: Int, z: Int, salt: Int): Double {
        var h = x * 73856093 xor (z * 19349663) xor (salt * 83492791)
        h = (h xor (h ushr 13)) * 1274126177
        return ((h xor (h ushr 16)) and 0xFFFF) / 65535.0
    }

    /** Wie tief der Sockel unter dem Boden an dieser Stelle reicht. */
    fun undersideDepth(r: Double, x: Int, z: Int): Int =
        (((RADIUS - r) * 0.72) + hash(x, z, 1) * 3.0 + 1.0).toInt().coerceAtLeast(1)

    /** Ruft [put] für jeden Block des Baus auf, relativ zur Bodenmitte (Boden auf y = 0). */
    fun build(put: (Int, Int, Int, BlockState) -> Unit) {
        // Luftraum und Plattform
        for (x in -RADIUS - 1..RADIUS + 1) for (z in -RADIUS - 1..RADIUS + 1) {
            val r = sqrt((x * x + z * z).toDouble())
            if (r > RADIUS + 0.5) continue
            for (y in 1..CLEAR_HEIGHT) put(x, y, z, AIR)
            put(x, 0, z, floorBlock(x, z, r))
            val depth = undersideDepth(r, x, z)
            for (d in 1..depth) {
                val edge = d == depth || r > RADIUS - 2.0
                val state = when {
                    edge -> if (hash(x, z, d) < 0.25) CRYING else OBSIDIAN
                    r > RADIUS * 0.6 -> END_STONE
                    else -> OBSIDIAN
                }
                put(x, -d, z, state)
            }
        }

        buildDais(put)
        buildGallery(put)
        for (i in 0 until 8) {
            val angle = i * (PI / 4.0) + PI / 8.0
            buildPillar((cos(angle) * 17.0).roundToInt(), (sin(angle) * 17.0).roundToInt(), if (i % 3 == 2) 8 else 15, put)
        }
        buildHalo(put)
    }

    private fun floorBlock(x: Int, z: Int, r: Double): BlockState {
        val angle = atan2(z.toDouble(), x.toDouble())
        val spoke = abs(((angle / (PI / 4.0)) - floor(angle / (PI / 4.0) + 0.5)))
        val onSpoke = spoke * r * (PI / 4.0) < 0.9
        return when {
            r > RADIUS - 1.5 -> END_BRICK
            r in 25.0..26.0 -> OBSIDIAN
            r in 10.0..11.0 -> AMETHYST
            r in 20.0..21.0 -> if (((angle + PI) * 8.0).toInt() % 2 == 0) AMETHYST else OBSIDIAN
            onSpoke && r > 6.0 -> CRYING
            r < 7.0 -> if ((x + z) and 1 == 0) OBSIDIAN else POLISHED
            hash(x, z, 2) < 0.13 -> TILE_CRACKED
            hash(x, z, 3) < 0.035 -> CRYING
            else -> TILE
        }
    }

    /** Stufen zum Pult in der Mitte. */
    private fun buildDais(put: (Int, Int, Int, BlockState) -> Unit) {
        for (x in -4..4) for (z in -4..4) {
            val r = sqrt((x * x + z * z).toDouble())
            if (r <= 4.3) put(x, 0, z, if (r < 2.0) CRYING else CHISELED)
        }
        put(0, 1, 0, VoidBoss.ALTAR.defaultState)
        for (i in 0 until 4) {
            val x = if (i < 2) 4 else -4
            val z = if (i % 2 == 0) 4 else -4
            put(x, 1, z, CHISELED)
            put(x, 2, z, ROD)
        }
    }

    /** Rang am Rand: niedrige Mauer mit Aktenregalen, durchbrochen von Toren. */
    private fun buildGallery(put: (Int, Int, Int, BlockState) -> Unit) {
        val steps = 240
        for (i in 0 until steps) {
            val angle = i * (2.0 * PI / steps)
            val x = (cos(angle) * 27.0).roundToInt()
            val z = (sin(angle) * 27.0).roundToInt()
            val slot = ((angle / (PI / 4.0)) % 1.0)
            if (slot < 0.1) continue
            put(x, 1, z, WALL)
            if (slot in 0.42..0.58) {
                put(x, 1, z, SHELF)
                put(x, 2, z, SHELF)
                put(x, 3, z, POLISHED)
                put(x, 4, z, ROD)
            }
        }
    }

    /** Dreifach breite Säule: Sie muss einen Spieler vor dem Erlass verdecken können. */
    private fun buildPillar(cx: Int, cz: Int, height: Int, put: (Int, Int, Int, BlockState) -> Unit) {
        for (dx in -1..1) for (dz in -1..1) {
            for (y in 1..height) {
                val trim = y % 5 == 0
                val corner = abs(dx) + abs(dz) == 2
                val state = when {
                    y == height -> AMETHYST
                    trim -> if (corner) OBSIDIAN else CRYING
                    corner -> OBSIDIAN
                    else -> POLISHED
                }
                put(cx + dx, y, cz + dz, state)
            }
        }
        for (dx in -2..2) for (dz in -2..2) {
            if (abs(dx) == 2 || abs(dz) == 2) put(cx + dx, 1, cz + dz, CHISELED)
        }
        put(cx, height + 1, cz, ROD)
    }

    /** Schwebender Lichtkranz über dem Saal, das Urteil von oben. */
    private fun buildHalo(put: (Int, Int, Int, BlockState) -> Unit) {
        val steps = 180
        for (i in 0 until steps) {
            val angle = i * (2.0 * PI / steps)
            if (((angle / (PI / 2.0)) % 1.0) < 0.12) continue
            val x = (cos(angle) * 15.0).roundToInt()
            val z = (sin(angle) * 15.0).roundToInt()
            put(x, 22, z, if (i % 6 == 0) CRYING else GLASS)
            put(x, 23, z, OBSIDIAN)
        }
    }

    fun placeInWorld(world: ServerWorld, center: BlockPos) {
        val pos = BlockPos.Mutable()
        build { x, y, z, state ->
            pos.set(center.x + x, center.y + y, center.z + z)
            world.setBlockState(pos, state, Block.NOTIFY_LISTENERS or Block.FORCE_STATE)
        }
    }

    fun bounds(center: BlockPos): BlockBox = BlockBox(
        center.x - RADIUS - 1, center.y - 26, center.z - RADIUS - 1,
        center.x + RADIUS + 1, center.y + CLEAR_HEIGHT, center.z + RADIUS + 1,
    )
}

/** Natürliche Erzeugung im End: wie Endstädte nur auf den äußeren Inseln mit genug Boden. */
class VoidTribunalStructure(config: Config) : Structure(config) {

    override fun getStructurePosition(context: Context): Optional<StructurePosition> {
        val ground = getShiftedPos(context, BlockRotation.NONE)
        if (ground.y < 45) return Optional.empty()
        val center = ground.up(9)
        return Optional.of(StructurePosition(center) { collector -> collector.addPiece(VoidTribunalPiece(center)) })
    }

    override fun getType(): StructureType<*> = VoidBoss.TRIBUNAL_TYPE

    companion object {
        val CODEC: MapCodec<VoidTribunalStructure> = createCodec(::VoidTribunalStructure)
    }
}

class VoidTribunalPiece : StructurePiece {

    private val center: BlockPos

    constructor(center: BlockPos) : super(VoidBoss.TRIBUNAL_PIECE, 0, TribunalBuilder.bounds(center)) {
        this.center = center
    }

    constructor(nbt: NbtCompound) : super(VoidBoss.TRIBUNAL_PIECE, nbt) {
        center = BlockPos(nbt.getInt("CX", 0), nbt.getInt("CY", 0), nbt.getInt("CZ", 0))
    }

    override fun writeNbt(context: StructureContext, nbt: NbtCompound) {
        nbt.putInt("CX", center.x)
        nbt.putInt("CY", center.y)
        nbt.putInt("CZ", center.z)
    }

    override fun generate(
        world: StructureWorldAccess, structureAccessor: StructureAccessor, chunkGenerator: ChunkGenerator,
        random: Random, chunkBox: BlockBox, chunkPos: ChunkPos, pivot: BlockPos,
    ) {
        val pos = BlockPos.Mutable()
        TribunalBuilder.build { x, y, z, state ->
            pos.set(center.x + x, center.y + y, center.z + z)
            if (chunkBox.contains(pos)) world.setBlockState(pos, state, Block.NOTIFY_LISTENERS)
        }
    }
}
