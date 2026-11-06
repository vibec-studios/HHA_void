package dev.henny.hha_void.client

import dev.henny.hha.client.ClientConfigCache
import dev.henny.hha_void.VoidItems
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback
import net.minecraft.client.MinecraftClient
import net.minecraft.item.Item
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import java.util.Locale
import kotlin.math.floor

/**
 * Dynamische Item-Lore für das Void-Set: Zeilen werden pro Tooltip gebaut
 * und zeigen die ECHTEN Tastenbelegungen (aus den Client-Einstellungen) und
 * die echten Cooldowns/Werte (vom Server gesyncte Config). Bindet jemand die
 * Ability-Tasten um oder ändert ein Admin die Config, stimmt die Lore sofort.
 */
object VoidTooltips {

    fun register() {
        ItemTooltipCallback.EVENT.register { stack, _, _, lines ->
            val lore = loreFor(stack.item) ?: return@register
            var index = minOf(1, lines.size)
            for (line in lore) {
                lines.add(index, line)
                index++
            }
        }
    }

    private fun loreFor(item: Item): List<Text>? = when (item) {
        VoidItems.VOID_HELMET -> listOf(
            line("item.hha_void.void_helmet.lore1"),
            line("item.hha_void.void_helmet.lore2"),
            line("item.hha_void.void_helmet.lore3", hearts("hha_void.escape_health"), seconds("hha_void.escape_invis"), seconds("hha_void.escape_cooldown")),
            line("item.hha_void.void_helmet.lore4"),
        )
        VoidItems.VOID_CHESTPLATE -> listOf(
            line("item.hha_void.void_chestplate.lore1"),
            line("item.hha_void.void_chestplate.lore2", key("key.hha.ability_1"), seconds("hha_void.meteor_cooldown")),
            line("item.hha_void.void_chestplate.lore3"),
            line("item.hha_void.void_chestplate.lore4", key("key.hha.ability_3"), num("hha_void.collapse_range"), seconds("hha_void.collapse_cooldown")),
        )
        VoidItems.VOID_LEGGINGS -> listOf(
            line("item.hha_void.void_leggings.lore1", key("key.hha.ability_2"), num("hha_void.pounce_radius"), seconds("hha_void.pounce_cooldown")),
            line("item.hha_void.void_leggings.lore2", num("hha_void.share_range")),
            line("item.hha_void.void_leggings.lore3"),
        )
        VoidItems.VOID_BOOTS -> listOf(
            line("item.hha_void.void_boots.lore1"),
            line("item.hha_void.void_boots.lore2"),
            line("item.hha_void.void_boots.lore3", jumpKey(), num("hha_void.dash_range"), seconds("hha_void.dash_cooldown")),
        )
        VoidItems.VOID_SWORD -> listOf(
            line("item.hha_void.void_sword.lore1"),
            line("item.hha_void.void_sword.lore2", useKey(), num("hha_void.shard_throw_damage")),
        )
        VoidItems.VOID_MACE -> listOf(
            line("item.hha_void.void_mace.lore1", useKey(), seconds("hha_void.mace_launch_cooldown")),
            line("item.hha_void.void_mace.lore2", num("hha_void.mace_shock_range")),
        )
        else -> null
    }

    private fun line(key: String, vararg args: Any): Text =
        Text.translatable(key, *args).formatted(Formatting.DARK_PURPLE)

    /** Aktuell gebundene Taste eines Keybinds (per Keybind-ID gesucht). */
    private fun key(id: String): String =
        net.minecraft.client.option.KeyBinding.byId(id)?.boundKeyLocalizedText?.string ?: "?"

    private fun jumpKey(): String =
        MinecraftClient.getInstance().options.jumpKey.boundKeyLocalizedText.string

    private fun useKey(): String =
        MinecraftClient.getInstance().options.useKey.boundKeyLocalizedText.string

    /** Zahlenwert unverändert (z. B. Schaden, Blöcke). */
    private fun num(key: String): String = format(ClientConfigCache.num(key))

    /** Tick-Wert als Sekunden. */
    private fun seconds(key: String): String = format(ClientConfigCache.num(key) / 20.0)

    /** Half-Heart-Wert als Herzen. */
    private fun hearts(key: String): String = format(ClientConfigCache.num(key) / 2.0)

    private fun format(value: Double): String =
        if (value == floor(value) && !value.isInfinite()) {
            value.toLong().toString()
        } else {
            String.format(Locale.ROOT, "%.1f", value)
        }
}
