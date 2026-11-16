package dev.henny.hha_void.boss

import com.mojang.serialization.MapCodec
import dev.henny.hha_void.logic.TickScheduler
import dev.henny.hha_void.logic.VoidVisuals
import net.minecraft.block.BlockRenderType
import net.minecraft.block.BlockState
import net.minecraft.block.BlockWithEntity
import net.minecraft.block.ShapeContext
import net.minecraft.block.entity.BlockEntity
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.item.ItemStack
import net.minecraft.server.network.ServerPlayerEntity
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
import net.minecraft.util.hit.HitResult
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Box
import net.minecraft.util.math.Vec3d
import net.minecraft.util.shape.VoxelShape
import net.minecraft.world.BlockView
import net.minecraft.world.RaycastContext
import net.minecraft.world.World

/**
 * Harpunen-Geschütz auf den Außenplattformen der Sternentiefe (Moby Dick): Rechtsklick feuert eine Harpune mit Seil
 * auf Oruun, wenn er in Reichweite ist und freies Schussfeld besteht (das Geschütz zielt selbst). Sitzen genug Harpunen
 * gleichzeitig ([VoidBossEntity.harpoonsNeeded]), wird er zu Boden gezerrt und liegt festgenagelt – Schadensfenster.
 * Nachladen [RELOAD] Ticks. Unzerstörbar wie die Arena.
 */
class HarpoonLauncherBlock(settings: Settings) : BlockWithEntity(settings) {

    override fun getCodec(): MapCodec<out BlockWithEntity> = CODEC
    override fun getRenderType(state: BlockState) = BlockRenderType.MODEL
    override fun createBlockEntity(pos: BlockPos, state: BlockState) = HarpoonLauncherEntity(pos, state)
    override fun getOutlineShape(state: BlockState, world: BlockView, pos: BlockPos, context: ShapeContext): VoxelShape = SHAPE

    override fun onUse(state: BlockState, world: World, pos: BlockPos, player: PlayerEntity, hit: BlockHitResult): ActionResult {
        if (world is ServerWorld && player is ServerPlayerEntity) (world.getBlockEntity(pos) as? HarpoonLauncherEntity)?.fire(world, player)
        return ActionResult.SUCCESS
    }

    override fun onUseWithItem(
        stack: ItemStack, state: BlockState, world: World, pos: BlockPos, player: PlayerEntity, hand: Hand, hit: BlockHitResult,
    ): ActionResult {
        if (world is ServerWorld && player is ServerPlayerEntity) (world.getBlockEntity(pos) as? HarpoonLauncherEntity)?.fire(world, player)
        return ActionResult.SUCCESS
    }

    companion object {
        val CODEC: MapCodec<HarpoonLauncherBlock> = createCodec(::HarpoonLauncherBlock)
        private val SHAPE: VoxelShape = createCuboidShape(1.0, 0.0, 1.0, 15.0, 15.0, 15.0)
        const val RELOAD = 240
        const val RANGE = 150.0
    }
}

class HarpoonLauncherEntity(pos: BlockPos, state: BlockState) : BlockEntity(LeviathanBlocks.HARPOON_ENTITY, pos, state) {

    private var readyAt = 0L

    fun fire(world: ServerWorld, player: ServerPlayerEntity) {
        val now = world.time
        if (now < readyAt) {
            msg(player, Text.translatable("hha_void.boss.msg.harpoon_reload", ((readyAt - now) / 20 + 1).toInt()), Formatting.GRAY)
            return
        }
        val muzzle = Vec3d.ofCenter(pos).add(0.0, 0.9, 0.0)
        val boss = world.getEntitiesByType(VoidBoss.BOSS, Box(muzzle, muzzle).expand(HarpoonLauncherBlock.RANGE)) { it.isAlive }
            .minByOrNull { it.squaredDistanceTo(muzzle) }
        if (boss == null) {
            msg(player, Text.translatable("hha_void.boss.msg.harpoon_range"), Formatting.GRAY)
            return
        }
        val target = boss.harpoonTarget(muzzle)
        if (target == null) {
            msg(player, Text.translatable("hha_void.boss.msg.harpoon_range"), Formatting.GRAY)
            return
        }
        val (volume, at) = target
        // die eigene Plattform und Deckungsmauer (erste 14 Blöcke) zählen nicht – der Bolzen fliegt im Bogen darüber
        val from = muzzle.add(at.subtract(muzzle).normalize().multiply(14.0))
        val blocked = world.raycast(RaycastContext(from, at, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, player))
        if (blocked.type != HitResult.Type.MISS && blocked.pos.squaredDistanceTo(at) > 12.0 * 12.0) {
            msg(player, Text.translatable("hha_void.boss.msg.harpoon_blocked"), Formatting.GRAY)
            return
        }
        readyAt = now + HarpoonLauncherBlock.RELOAD
        markDirty()
        val travel = (muzzle.distanceTo(at) / 7.0).toInt().coerceIn(3, 30)
        val tether = VoidVisuals.newId()
        VoidVisuals.harpoon(world, tether, boss.id, volume, muzzle, travel, 600)
        world.playSound(null, pos, SoundEvents.ITEM_CROSSBOW_SHOOT, SoundCategory.PLAYERS, 3f, 0.5f)
        world.playSound(null, pos, SoundEvents.ITEM_TRIDENT_THROW.value(), SoundCategory.PLAYERS, 3f, 0.6f)
        TickScheduler.schedule(travel) {
            if (boss.isRemoved || !boss.isAlive) {
                VoidVisuals.harpoonEnd(world, tether, muzzle)
                return@schedule
            }
            boss.harpoonHit(world, tether, muzzle, volume, player)
        }
    }

    private fun msg(player: ServerPlayerEntity, text: Text, color: Formatting) = player.sendMessage(text.copy().formatted(color), true)

    override fun writeData(view: WriteView) {
        super.writeData(view)
        view.putLong("ReadyAt", readyAt)
    }

    override fun readData(view: ReadView) {
        super.readData(view)
        readyAt = view.getLong("ReadyAt", 0L)
    }
}
