package dev.henny.hha_void.boss

import com.mojang.brigadier.context.CommandContext
import dev.henny.hha_void.VoidItems
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.`object`.builder.v1.block.entity.FabricBlockEntityTypeBuilder
import net.fabricmc.fabric.api.`object`.builder.v1.entity.FabricDefaultAttributeRegistry
import net.minecraft.block.AbstractBlock
import net.minecraft.block.Block
import net.minecraft.block.entity.BlockEntityType
import net.minecraft.block.piston.PistonBehavior
import net.minecraft.component.DataComponentTypes
import net.minecraft.component.type.WrittenBookContentComponent
import net.minecraft.entity.EntityType
import net.minecraft.entity.SpawnGroup
import net.minecraft.entity.SpawnReason
import net.minecraft.entity.attribute.EntityAttributes
import net.minecraft.entity.mob.HostileEntity
import net.minecraft.item.BlockItem
import net.minecraft.item.Item
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.registry.Registries
import net.minecraft.registry.Registry
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.server.command.CommandManager
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.sound.BlockSoundGroup
import net.minecraft.structure.StructurePieceType
import net.minecraft.text.RawFilteredPair
import net.minecraft.text.Text
import net.minecraft.util.Rarity
import net.minecraft.util.math.BlockPos
import net.minecraft.world.gen.structure.StructureType

/** Registrierung des Boss-Inhalts: Entities, Altar, Tribunal-Struktur und Befehle. */
object VoidBoss {

    lateinit var BOSS: EntityType<VoidBossEntity>; private set
    lateinit var DEBTOR: EntityType<VoidDebtorEntity>; private set
    lateinit var ALTAR: Block; private set
    lateinit var ALTAR_ITEM: Item; private set
    lateinit var ALTAR_ENTITY: BlockEntityType<VoidAltarBlockEntity>; private set
    lateinit var TRIBUNAL_TYPE: StructureType<VoidTribunalStructure>; private set
    lateinit var TRIBUNAL_PIECE: StructurePieceType; private set

    /** Für Explosionen: Läuft in der Nähe gerade ein Kampf gegen Vael? */
    @JvmStatic
    fun isBossNearby(entity: net.minecraft.entity.Entity): Boolean {
        val world = entity.entityWorld as? net.minecraft.server.world.ServerWorld ?: return false
        return world.getEntitiesByType(BOSS, entity.boundingBox.expand(ARENA_GUARD_RANGE)) { it.isAlive }.isNotEmpty()
    }

    private const val ARENA_GUARD_RANGE = 96.0

    fun init() {
        val bossKey = RegistryKey.of(RegistryKeys.ENTITY_TYPE, VoidItems.id("void_boss"))
        BOSS = Registry.register(
            Registries.ENTITY_TYPE, bossKey,
            EntityType.Builder.create(EntityType.EntityFactory<VoidBossEntity> { type, world -> VoidBossEntity(type, world) }, SpawnGroup.MONSTER)
                .dimensions(4f, 6.5f)
                .makeFireImmune()
                .maxTrackingRange(16)
                .build(bossKey)
        )
        FabricDefaultAttributeRegistry.register(
            BOSS,
            HostileEntity.createHostileAttributes()
                .add(EntityAttributes.MAX_HEALTH, 600.0)
                .add(EntityAttributes.ARMOR, 12.0)
                .add(EntityAttributes.KNOCKBACK_RESISTANCE, 1.0)
                .add(EntityAttributes.FOLLOW_RANGE, 96.0)
                .add(EntityAttributes.MOVEMENT_SPEED, 0.25)
                .add(EntityAttributes.STEP_HEIGHT, 2.0)
        )

        val debtorKey = RegistryKey.of(RegistryKeys.ENTITY_TYPE, VoidItems.id("void_debtor"))
        DEBTOR = Registry.register(
            Registries.ENTITY_TYPE, debtorKey,
            EntityType.Builder.create(EntityType.EntityFactory<VoidDebtorEntity> { type, world -> VoidDebtorEntity(type, world) }, SpawnGroup.MONSTER)
                .dimensions(1f, 2f)
                .maxTrackingRange(10)
                .build(debtorKey)
        )
        FabricDefaultAttributeRegistry.register(
            DEBTOR,
            HostileEntity.createHostileAttributes()
                .add(EntityAttributes.MAX_HEALTH, 30.0)
                .add(EntityAttributes.ATTACK_DAMAGE, 7.0)
                .add(EntityAttributes.MOVEMENT_SPEED, 0.3)
                .add(EntityAttributes.FOLLOW_RANGE, 48.0)
        )

        val altarKey = RegistryKey.of(RegistryKeys.BLOCK, VoidItems.id("void_altar"))
        ALTAR = Registry.register(
            Registries.BLOCK, altarKey,
            VoidAltarBlock(
                AbstractBlock.Settings.create()
                    .registryKey(altarKey)
                    .strength(-1f, 3600000f)
                    .dropsNothing()
                    .luminance { 11 }
                    .nonOpaque()
                    .sounds(BlockSoundGroup.AMETHYST_BLOCK)
                    .pistonBehavior(PistonBehavior.BLOCK)
            )
        )
        val altarItemKey = RegistryKey.of(RegistryKeys.ITEM, VoidItems.id("void_altar"))
        ALTAR_ITEM = Registry.register(
            Registries.ITEM, altarItemKey,
            BlockItem(ALTAR, Item.Settings().registryKey(altarItemKey).useBlockPrefixedTranslationKey().rarity(Rarity.EPIC))
        )
        ALTAR_ENTITY = Registry.register(
            Registries.BLOCK_ENTITY_TYPE, VoidItems.id("void_altar"),
            FabricBlockEntityTypeBuilder.create({ pos, state -> VoidAltarBlockEntity(pos, state) }, ALTAR).build()
        )

        TRIBUNAL_TYPE = Registry.register(
            Registries.STRUCTURE_TYPE, VoidItems.id("void_tribunal"),
            StructureType { VoidTribunalStructure.CODEC }
        )
        TRIBUNAL_PIECE = Registry.register(
            Registries.STRUCTURE_PIECE, VoidItems.id("void_tribunal"),
            StructurePieceType.Simple { nbt -> VoidTribunalPiece(nbt) }
        )

        registerCommands()
    }

    private fun registerCommands() {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(
                CommandManager.literal("voidboss")
                    .requires(CommandManager.requirePermissionLevel(CommandManager.GAMEMASTERS_CHECK))
                    .then(CommandManager.literal("place").executes(::placeTribunal))
                    .then(CommandManager.literal("spawn").executes(::spawnBoss))
                    .then(CommandManager.literal("remove").executes(::removeBoss))
            )
        }
    }

    /** Baut das Tribunal unter dem Ausführenden; der Altar erwacht, sobald jemand in der Nähe ist. */
    private fun placeTribunal(ctx: CommandContext<ServerCommandSource>): Int {
        val source = ctx.source
        val world = source.world
        val floor = BlockPos.ofFloored(source.position).down()
        TribunalBuilder.placeInWorld(world, floor)
        source.player?.requestTeleport(floor.x + 16.5, floor.y + 1.0, floor.z + 0.5)
        source.sendFeedback({ Text.translatable("hha_void.boss.msg.placed") }, true)
        return 1
    }

    private fun spawnBoss(ctx: CommandContext<ServerCommandSource>): Int {
        val source = ctx.source
        val world = source.world
        val boss = BOSS.create(world, SpawnReason.COMMAND) ?: return 0
        val look = source.rotationVec3()
        val at = source.position.add(look.x * 8.0, 2.0, look.z * 8.0)
        boss.refreshPositionAndAngles(at.x, at.y, at.z, 0f, 0f)
        world.spawnEntity(boss)
        source.sendFeedback({ Text.translatable("hha_void.boss.msg.spawned") }, true)
        return 1
    }

    private fun removeBoss(ctx: CommandContext<ServerCommandSource>): Int {
        val world = ctx.source.world
        val bosses = world.getEntitiesByType(BOSS) { true } + world.getEntitiesByType(DEBTOR) { true }
        bosses.forEach { it.discard() }
        ctx.source.sendFeedback({ Text.translatable("hha_void.boss.msg.removed", bosses.size) }, true)
        return bosses.size
    }

    private fun ServerCommandSource.rotationVec3() =
        net.minecraft.util.math.Vec3d.fromPolar(0f, this.rotation.y)

    /** Beute nach dem Kampf: das Hauptbuch der Leere mit Vaels Lehre. */
    fun ledgerBook(): ItemStack {
        val stack = ItemStack(Items.WRITTEN_BOOK)
        val pages = (1..5).map { RawFilteredPair.of<Text>(Text.translatable("hha_void.boss.book.page$it")) }
        stack.set(
            DataComponentTypes.WRITTEN_BOOK_CONTENT,
            WrittenBookContentComponent(RawFilteredPair.of("Das Hauptbuch der Leere"), "Vael", 0, pages, true)
        )
        return stack
    }
}
