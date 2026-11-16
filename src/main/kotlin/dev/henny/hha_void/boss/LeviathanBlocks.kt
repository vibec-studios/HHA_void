package dev.henny.hha_void.boss

import dev.henny.hha_void.VoidItems
import net.minecraft.block.AbstractBlock
import net.minecraft.block.Block
import net.minecraft.block.BlockState
import net.minecraft.block.ShapeContext
import net.minecraft.block.TransparentBlock
import net.minecraft.block.piston.PistonBehavior
import net.minecraft.entity.Entity
import net.minecraft.entity.EntityCollisionHandler
import net.minecraft.entity.LivingEntity
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.registry.Registries
import net.minecraft.registry.Registry
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.BlockSoundGroup
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import net.minecraft.util.shape.VoxelShape
import net.minecraft.world.BlockView
import net.minecraft.world.World

/**
 * Blöcke des Leviathan-Inneren (Bauch-Phase) und der Sternenaufzug der Himmelsarena.
 * Alle unzerstörbar für Spieler, ohne Drops und ohne Items. Texturen (teils animiert):
 * `tools/textures/GenLeviathanBlocks.java`.
 */
object LeviathanBlocks {

    lateinit var FLESH: Block; private set
    lateinit var VEIN: Block; private set
    lateinit var RIB: Block; private set
    lateinit var ACID: Block; private set
    lateinit var MEMBRANE: Block; private set
    lateinit var STAR_LIFT: Block; private set
    lateinit var LUNG: Block; private set
    lateinit var HARPOON: Block; private set
    lateinit var HARPOON_ENTITY: net.minecraft.block.entity.BlockEntityType<HarpoonLauncherEntity>; private set

    private fun settings(key: RegistryKey<Block>, sounds: BlockSoundGroup) = AbstractBlock.Settings.create()
        .registryKey(key)
        .strength(-1f, 3600000f)
        .dropsNothing()
        .pistonBehavior(PistonBehavior.BLOCK)
        .allowsSpawning { _, _, _, _ -> false }
        .sounds(sounds)

    private fun <B : Block> register(name: String, make: (RegistryKey<Block>) -> B): B {
        val key = RegistryKey.of(RegistryKeys.BLOCK, VoidItems.id(name))
        return Registry.register(Registries.BLOCK, key, make(key))
    }

    fun init() {
        FLESH = register("leviathan_flesh") { Block(settings(it, BlockSoundGroup.SLIME)) }
        VEIN = register("leviathan_vein") { Block(settings(it, BlockSoundGroup.HONEY).luminance { 13 }.emissiveLighting { _, _, _ -> true }) }
        RIB = register("leviathan_rib") { Block(settings(it, BlockSoundGroup.BONE)) }
        ACID = register("leviathan_acid") { AcidBlock(settings(it, BlockSoundGroup.HONEY).luminance { 7 }.nonOpaque().emissiveLighting { _, _, _ -> true }) }
        MEMBRANE = register("leviathan_membrane") {
            MembraneBlock(
                settings(it, BlockSoundGroup.SLIME).nonOpaque().luminance { 6 }
                    .solidBlock { _, _, _ -> false }.suffocates { _, _, _ -> false }.blockVision { _, _, _ -> false },
            )
        }
        STAR_LIFT = register("star_lift") {
            StarLiftBlock(
                settings(it, BlockSoundGroup.AMETHYST_BLOCK).noCollision().nonOpaque().luminance { 12 }
                    .solidBlock { _, _, _ -> false }.suffocates { _, _, _ -> false }.blockVision { _, _, _ -> false },
            )
        }
        LUNG = register("leviathan_lung") {
            LungBlock(
                settings(it, BlockSoundGroup.SLIME).nonOpaque().luminance { 5 }
                    .solidBlock { _, _, _ -> false }.suffocates { _, _, _ -> false }.blockVision { _, _, _ -> false },
            )
        }
        HARPOON = register("harpoon_launcher") { HarpoonLauncherBlock(settings(it, BlockSoundGroup.ANVIL).nonOpaque().luminance { 9 }) }
        HARPOON_ENTITY = Registry.register(
            Registries.BLOCK_ENTITY_TYPE, VoidItems.id("harpoon_launcher"),
            net.fabricmc.fabric.api.`object`.builder.v1.block.entity.FabricBlockEntityTypeBuilder.create({ pos, state -> HarpoonLauncherEntity(pos, state) }, HARPOON).build(),
        )
    }
}

/** Verdauungssäure: man sinkt halb ein und verätzt (Schaden alle 10 Ticks). */
class AcidBlock(settings: Settings) : Block(settings) {

    override fun getCollisionShape(state: BlockState, world: BlockView, pos: BlockPos, context: ShapeContext): VoxelShape = SHAPE

    override fun onEntityCollision(state: BlockState, world: World, pos: BlockPos, entity: Entity, handler: EntityCollisionHandler, bl: Boolean) {
        if (entity !is LivingEntity || entity is LeviathanCoreEntity || entity is LeviathanPartEntity) return
        entity.velocity = entity.velocity.multiply(0.6, 1.0, 0.6)
        val server = world as? ServerWorld ?: return
        if (entity is PlayerEntity && (entity.isCreative || entity.isSpectator)) return
        if (server.time % 10L == 0L) entity.damage(server, server.damageSources.magic(), ACID_DAMAGE)
    }

    companion object {
        private val SHAPE: VoxelShape = createCuboidShape(0.0, 0.0, 0.0, 16.0, 7.0, 16.0)
        const val ACID_DAMAGE = 3f
    }
}

/** Durchscheinende Magenwand; bricht beim Ausbruch. */
class MembraneBlock(settings: Settings) : TransparentBlock(settings)

/**
 * Sternenaufzug: Lichtsäule ohne Kollision, die Spieler wie eine Blasensäule nach oben trägt.
 * Ganz oben (kein Aufzug mehr über dem nächsten Block) wird sie sanft, damit man seitlich aussteigen kann.
 * Läuft auch auf dem Client (dort bewegt sich der Spieler).
 */
class StarLiftBlock(settings: Settings) : Block(settings) {

    override fun onEntityCollision(state: BlockState, world: World, pos: BlockPos, entity: Entity, handler: EntityCollisionHandler, bl: Boolean) {
        if (entity !is LivingEntity || entity is LeviathanPartEntity) return
        val top = !world.getBlockState(pos.up(2)).isOf(this)
        val v = entity.velocity
        val want = if (top) 0.25 else 0.95
        val vy = if (v.y < want) minOf(v.y + 0.3, want) else want
        entity.velocity = Vec3d(v.x * 0.9, vy, v.z * 0.9)
        entity.fallDistance = 0.0
    }
}

/** Lungengewebe: federnd, kein Fallschaden (wer von der Wendelrampe stürzt, landet weich). */
class LungBlock(settings: Settings) : TransparentBlock(settings) {
    override fun onLandedUpon(world: World, state: BlockState, pos: BlockPos, entity: Entity, fallDistance: Double) {
        entity.handleFallDamage(fallDistance, 0f, world.damageSources.fall())
    }

    override fun onEntityLand(world: net.minecraft.world.BlockView, entity: Entity) {
        val v = entity.velocity
        if (v.y < -0.3 && !entity.bypassesLandingEffects()) entity.velocity = Vec3d(v.x, -v.y * 0.55, v.z) else super.onEntityLand(world, entity)
    }
}
