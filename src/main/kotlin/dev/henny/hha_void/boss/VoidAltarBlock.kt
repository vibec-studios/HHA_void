package dev.henny.hha_void.boss

import com.mojang.serialization.MapCodec
import dev.henny.hha_void.VoidItems
import dev.henny.hha_void.logic.VoidVisuals
import net.minecraft.block.BlockRenderType
import net.minecraft.block.BlockState
import net.minecraft.block.BlockWithEntity
import net.minecraft.block.ShapeContext
import net.minecraft.block.entity.BlockEntity
import net.minecraft.block.entity.BlockEntityTicker
import net.minecraft.block.entity.BlockEntityType
import net.minecraft.entity.SpawnReason
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.item.ItemStack
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvents
import net.minecraft.storage.ReadView
import net.minecraft.storage.WriteView
import net.minecraft.text.Text
import net.minecraft.util.ActionResult
import net.minecraft.util.Formatting
import net.minecraft.util.Hand
import net.minecraft.util.hit.BlockHitResult
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import net.minecraft.util.shape.VoxelShape
import net.minecraft.world.BlockView
import net.minecraft.world.World

/**
 * Der Ruf der Tiefe in der Mitte der Sternentiefe. Sobald jemand herantritt, singt er und ruft
 * Oruun, den Sternenwal. Danach ist er verbraucht, bis jemand ihn mit einem Void-Barren neu stimmt.
 */
class VoidAltarBlock(settings: Settings) : BlockWithEntity(settings) {

    override fun getCodec(): MapCodec<out BlockWithEntity> = CODEC

    override fun getRenderType(state: BlockState) = BlockRenderType.MODEL

    override fun createBlockEntity(pos: BlockPos, state: BlockState) = VoidAltarBlockEntity(pos, state)

    override fun getOutlineShape(state: BlockState, world: BlockView, pos: BlockPos, context: ShapeContext): VoxelShape = SHAPE

    override fun <T : BlockEntity> getTicker(world: World, state: BlockState, type: BlockEntityType<T>): BlockEntityTicker<T>? =
        if (world.isClient) null else validateTicker(type, VoidBoss.ALTAR_ENTITY) { w, pos, _, altar -> altar.tick(w as ServerWorld, pos) }

    override fun onUseWithItem(
        stack: ItemStack, state: BlockState, world: World, pos: BlockPos,
        player: PlayerEntity, hand: Hand, hit: BlockHitResult,
    ): ActionResult {
        val altar = world.getBlockEntity(pos) as? VoidAltarBlockEntity ?: return ActionResult.PASS
        if (!stack.isOf(VoidItems.VOID_INGOT) || !altar.spent) return ActionResult.PASS_TO_DEFAULT_BLOCK_ACTION
        if (world is ServerWorld) {
            if (!player.abilities.creativeMode) stack.decrement(1)
            altar.rearm()
            player.sendMessage(Text.translatable("hha_void.boss.msg.rearmed").formatted(Formatting.LIGHT_PURPLE), true)
            world.playSound(null, pos, SoundEvents.BLOCK_RESPAWN_ANCHOR_CHARGE, SoundCategory.BLOCKS, 1f, 0.6f)
        }
        return ActionResult.SUCCESS
    }

    companion object {
        val CODEC: MapCodec<VoidAltarBlock> = createCodec(::VoidAltarBlock)
        private val SHAPE: VoxelShape = createCuboidShape(2.0, 0.0, 2.0, 14.0, 13.0, 14.0)
    }
}

class VoidAltarBlockEntity(pos: BlockPos, state: BlockState) : BlockEntity(VoidBoss.ALTAR_ENTITY, pos, state) {

    var spent = false
        private set
    private var awakening = -1

    fun rearm() {
        spent = false
        awakening = -1
        markDirty()
    }

    private var registered = false

    fun tick(world: ServerWorld, pos: BlockPos) {
        // natürlich erzeugte Arenen melden sich hier an (keine Endermen auf der Arena, siehe ArenaRegistry)
        if (!registered) {
            registered = true
            ArenaRegistry.register(world, pos.down())
        }
        if (spent) return
        val center = Vec3d.ofBottomCenter(pos)

        if (awakening < 0) {
            if (world.time % 20L != 0L) return
            val near = world.players.any { !it.isSpectator && it.squaredDistanceTo(center) < WAKE_RANGE * WAKE_RANGE }
            if (near) awakening = 0
            return
        }

        awakening++
        val where = center.add(0.0, 7.0, 0.0)
        if (awakening == 1) {
            VoidVisuals.charge(world, where, AWAKEN_TICKS)
            world.playSound(null, pos, dev.henny.hha_void.VoidSounds.WHALE_SONG, SoundCategory.HOSTILE, 3f, 0.6f)
            announce(world, center, "hha_void.boss.line.summon")
        }
        if (awakening % 12 == 0) {
            world.playSound(null, pos, SoundEvents.BLOCK_AMETHYST_BLOCK_RESONATE, SoundCategory.HOSTILE, 2f, 0.5f + awakening * 0.008f)
        }
        if (awakening >= AWAKEN_TICKS) {
            spent = true
            awakening = -1
            markDirty()
            val boss = VoidBoss.BOSS.create(world, SpawnReason.TRIGGERED) ?: return
            boss.refreshPositionAndAngles(where.x, where.y, where.z, 0f, 0f)
            boss.arena = pos.down()
            world.spawnEntity(boss)
            VoidVisuals.nova(world, where, 4f)
        }
    }

    private fun announce(world: ServerWorld, center: Vec3d, key: String) {
        for (player in world.players) {
            if (player.squaredDistanceTo(center) < 128.0 * 128.0) {
                player.sendMessage(Text.translatable(key).formatted(Formatting.DARK_PURPLE, Formatting.ITALIC), false)
            }
        }
    }

    override fun writeData(view: WriteView) {
        super.writeData(view)
        view.putBoolean("Spent", spent)
    }

    override fun readData(view: ReadView) {
        super.readData(view)
        spent = view.getBoolean("Spent", false)
    }

    private companion object {
        const val WAKE_RANGE = 22.0
        const val AWAKEN_TICKS = 90
    }
}
