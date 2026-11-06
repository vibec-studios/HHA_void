package dev.henny.hha_void.item

import dev.henny.hha.config.HhaConfig
import dev.henny.hha.logic.Cooldowns
import dev.henny.hha_void.logic.VoidShards
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.item.Item
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.text.Text
import net.minecraft.util.ActionResult
import net.minecraft.util.Formatting
import net.minecraft.util.Hand
import net.minecraft.world.World

/**
 * Void Sword — 9 Schaden.
 * Aktiv (Rechtsklick): Void Shards — wirft einen der sechs kreisenden Splitter.
 * Er springt von Gegner zu Gegner und kommt zurück.
 */
class VoidSwordItem(settings: Settings) : Item(settings) {

    override fun use(world: World, user: PlayerEntity, hand: Hand): ActionResult {
        val stack = user.getStackInHand(hand)
        if (user.itemCooldownManager.isCoolingDown(stack)) return ActionResult.PASS
        if (!HhaConfig.enabled("hha_void.shards")) return ActionResult.PASS
        if (world !is ServerWorld || user !is ServerPlayerEntity) return ActionResult.SUCCESS

        if (!VoidShards.throwNext(world, user)) {
            user.sendMessage(
                Text.translatable("hha_void.msg.no_shards").formatted(Formatting.LIGHT_PURPLE), true
            )
            return ActionResult.PASS
        }
        Cooldowns.set(user, stack, "hha_void.shard_throw_cooldown")
        return ActionResult.SUCCESS
    }
}
