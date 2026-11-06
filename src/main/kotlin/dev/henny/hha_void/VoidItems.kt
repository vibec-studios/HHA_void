package dev.henny.hha_void

import dev.henny.hha_void.item.VoidMaceItem
import dev.henny.hha_void.item.VoidSwordItem
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup
import net.minecraft.component.DataComponentTypes
import net.minecraft.item.Item
import net.minecraft.item.ItemStack
import net.minecraft.item.ToolMaterial
import net.minecraft.item.equipment.ArmorMaterial
import net.minecraft.item.equipment.EquipmentAsset
import net.minecraft.item.equipment.EquipmentAssetKeys
import net.minecraft.item.equipment.EquipmentType
import net.minecraft.registry.Registries
import net.minecraft.registry.Registry
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.registry.tag.BlockTags
import net.minecraft.registry.tag.TagKey
import net.minecraft.sound.SoundEvents
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import net.minecraft.util.Rarity

object VoidItems {

    private const val MACE_ATTACK_DAMAGE = 2.0f

    fun id(path: String): Identifier = Identifier.of("hha_void", path)

    val VOID_REPAIR_TAG: TagKey<Item> = TagKey.of(RegistryKeys.ITEM, id("void_repair"))

    val VOID_EQUIPMENT_ASSET: RegistryKey<EquipmentAsset> =
        RegistryKey.of(EquipmentAssetKeys.REGISTRY_KEY, id("void"))

    private val DEFENSE = mapOf(
        EquipmentType.HELMET to 3,
        EquipmentType.CHESTPLATE to 8,
        EquipmentType.LEGGINGS to 6,
        EquipmentType.BOOTS to 3,
    )

    val VOID_ARMOR_MATERIAL = ArmorMaterial(
        40, DEFENSE, 17,
        SoundEvents.ITEM_ARMOR_EQUIP_NETHERITE,
        3.5f, 0.1f, VOID_REPAIR_TAG, VOID_EQUIPMENT_ASSET,
    )

    val VOID_TOOL_MATERIAL = ToolMaterial(
        BlockTags.INCORRECT_FOR_NETHERITE_TOOL,
        2531, 9.0f, 5.0f, 17, VOID_REPAIR_TAG,
    )

    lateinit var VOID_INGOT: Item; private set

    lateinit var VOID_HELMET: Item; private set
    lateinit var VOID_CHESTPLATE: Item; private set
    lateinit var VOID_LEGGINGS: Item; private set
    lateinit var VOID_BOOTS: Item; private set
    lateinit var VOID_SWORD: Item; private set
    lateinit var VOID_MACE: Item; private set

    fun init() {
        VOID_INGOT = register("void_ingot") { s -> Item(s.fireproof().rarity(Rarity.RARE)) }

        // Lore kommt clientseitig dynamisch aus VoidTooltips (echte Keybinds + Config-Werte)
        VOID_HELMET = registerArmor("void_helmet", EquipmentType.HELMET)
        VOID_CHESTPLATE = registerArmor("void_chestplate", EquipmentType.CHESTPLATE)
        VOID_LEGGINGS = registerArmor("void_leggings", EquipmentType.LEGGINGS)
        VOID_BOOTS = registerArmor("void_boots", EquipmentType.BOOTS)

        VOID_SWORD = register("void_sword") { s ->
            VoidSwordItem(
                s.sword(VOID_TOOL_MATERIAL, 5.0f, -2.4f)
                    .fireproof().rarity(Rarity.EPIC)
                    .component(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, false)
            )
        }
        VOID_MACE = register("void_mace") { s ->
            VoidMaceItem(
                s.sword(VOID_TOOL_MATERIAL, MACE_ATTACK_DAMAGE, -2.8f)
                    .fireproof().rarity(Rarity.EPIC)
                    .component(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, false)
            )
        }

        registerItemGroup()
    }

    private fun registerArmor(name: String, type: EquipmentType): Item =
        register(name) { s ->
            Item(
                s.armor(VOID_ARMOR_MATERIAL, type)
                    .fireproof().rarity(Rarity.EPIC)
                    .component(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, false)
            )
        }

    private fun register(name: String, factory: (Item.Settings) -> Item): Item {
        val key = RegistryKey.of(RegistryKeys.ITEM, id(name))
        return Registry.register(Registries.ITEM, key, factory(Item.Settings().registryKey(key)))
    }

    private fun registerItemGroup() {
        val key = RegistryKey.of(RegistryKeys.ITEM_GROUP, id("void_set"))
        Registry.register(
            Registries.ITEM_GROUP, key,
            FabricItemGroup.builder()
                .icon { ItemStack(VOID_SWORD) }
                .displayName(Text.translatable("itemGroup.hha_void.void_set"))
                .entries { _, entries ->
                    entries.add(VOID_INGOT)
                    entries.add(VOID_HELMET)
                    entries.add(VOID_CHESTPLATE)
                    entries.add(VOID_LEGGINGS)
                    entries.add(VOID_BOOTS)
                    entries.add(VOID_SWORD)
                    entries.add(VOID_MACE)
                    entries.add(dev.henny.hha_void.boss.VoidBoss.ALTAR_ITEM)
                }
                .build()
        )
    }
}
