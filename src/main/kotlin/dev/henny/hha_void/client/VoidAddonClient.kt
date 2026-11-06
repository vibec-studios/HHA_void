package dev.henny.hha_void.client

import dev.henny.hha.api.client.HhaClientAddon
import dev.henny.hha.api.client.HhaClientAddonContext
import dev.henny.hha_void.VoidItems
import net.minecraft.entity.EquipmentSlot
import net.minecraft.item.ItemStack
import net.minecraft.util.Identifier

/**
 * HUD-Slots in der HHA-Cooldown-Leiste: Void Step (Boots), Void Shards
 * (Schwert), Sky Launch (Mace).
 */
class VoidAddonClient : HhaClientAddon {

    private companion object {
        const val VOID_PURPLE = 0xA46CFF
        const val VOID_MAGENTA = 0xD05CE8
    }

    override fun onInitializeClient(context: HhaClientAddonContext) {
        context.registerHudCooldown(Identifier.of("hha_void", "hud/collapse"), VOID_MAGENTA) { player ->
            equippedOrNull(player.getEquippedStack(EquipmentSlot.HEAD), VoidItems.VOID_HELMET)
        }
        context.registerHudCooldown(Identifier.of("hha_void", "hud/meteor"), VOID_PURPLE) { player ->
            equippedOrNull(player.getEquippedStack(EquipmentSlot.CHEST), VoidItems.VOID_CHESTPLATE)
        }
        context.registerHudCooldown(Identifier.of("hha_void", "hud/pounce"), VOID_PURPLE) { player ->
            equippedOrNull(player.getEquippedStack(EquipmentSlot.LEGS), VoidItems.VOID_LEGGINGS)
        }
        context.registerHudCooldown(Identifier.of("hha_void", "hud/dash"), VOID_PURPLE) { player ->
            equippedOrNull(player.getEquippedStack(EquipmentSlot.FEET), VoidItems.VOID_BOOTS)
        }
        context.registerHudCooldown(Identifier.of("hha_void", "hud/shards"), VOID_MAGENTA) { player ->
            player.mainHandStack.takeIf { it.isOf(VoidItems.VOID_SWORD) }
                ?: player.offHandStack.takeIf { it.isOf(VoidItems.VOID_SWORD) }
        }
        context.registerHudCooldown(Identifier.of("hha_void", "hud/launch"), VOID_MAGENTA) { player ->
            player.mainHandStack.takeIf { it.isOf(VoidItems.VOID_MACE) }
                ?: player.offHandStack.takeIf { it.isOf(VoidItems.VOID_MACE) }
        }
    }

    private fun equippedOrNull(stack: ItemStack, item: net.minecraft.item.Item): ItemStack? =
        stack.takeIf { it.isOf(item) }
}
