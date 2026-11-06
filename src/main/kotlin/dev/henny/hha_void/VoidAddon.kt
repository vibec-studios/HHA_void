package dev.henny.hha_void

import dev.henny.hha.api.HhaAddon
import dev.henny.hha.api.HhaAddonContext
import dev.henny.hha.api.ability.AbilitySlot
import dev.henny.hha.api.ability.SimpleAbility
import dev.henny.hha.api.set.ArmorSet
import dev.henny.hha.config.HhaConfig
import dev.henny.hha_void.logic.MeteorStrike
import dev.henny.hha_void.logic.TickScheduler
import dev.henny.hha_void.logic.VoidLink
import net.minecraft.world.World
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.minecraft.entity.EquipmentSlot
import net.minecraft.entity.effect.StatusEffectInstance
import net.minecraft.entity.effect.StatusEffects
import net.minecraft.registry.tag.DamageTypeTags
import net.minecraft.server.network.ServerPlayerEntity

/**
 * Void's Armor — HHA-Addon-Einstiegspunkt.
 *
 * Helm: Regeneration I
 * Brustplatte: Fire Resistance
 * Boots: Fall-Immunität + Speed I + Void Step (Doppel-Leertaste: Kurz-TP
 *   durch Wände mit Portal und Schub)
 * Set-Bonus: Void Link — 1/3 deines Schadens springt auf die Verbündeten
 *   des Ziels über
 * Void Sword: Void Shards (6 kreisende Splitter, Rechtsklick wirft einen; sie springen von Gegner zu Gegner und kehren zurück)
 * Void Mace: Sky Launch (Rechtsklick, Mjölnir-Boost nach oben)
 */
class VoidAddon : HhaAddon {

    companion object {
        lateinit var SET: ArmorSet; private set
    }

    override fun onInitialize(context: HhaAddonContext) {
        VoidItems.init()
        dev.henny.hha_void.boss.VoidBoss.init()
        TickScheduler.init()
        VoidLink.init()
        dev.henny.hha_void.logic.MaceShock.init()
        dev.henny.hha_void.logic.MeteorStrike.init()
        dev.henny.hha_void.logic.VoidShards.init()
        dev.henny.hha_void.logic.VoidCollapse.init()
        dev.henny.hha_void.logic.FullInvis.init()
        dev.henny.hha_void.logic.SetPerks.initVoidRescue()
        VoidNetworking.init()

        registerConfig(context)

        SET = context.armorSet("void") {
            helmet(VoidItems.VOID_HELMET)
            chestplate(VoidItems.VOID_CHESTPLATE)
            leggings(VoidItems.VOID_LEGGINGS)
            boots(VoidItems.VOID_BOOTS)
            weapon(VoidItems.VOID_SWORD)
            weapon(VoidItems.VOID_MACE)
            onTick { world, player, state ->
                if (state.full) {
                    dev.henny.hha_void.logic.SetPerks.escapeTick(world, player)
                    dev.henny.hha_void.logic.SetPerks.shareTick(world, player)
                }

                if (world.time % 20L != 0L) return@onTick

                // Set-Bonus: Kind der Nacht — nachts oder im End wird das
                // Set eins mit der Dunkelheit
                if (state.full && HhaConfig.enabled("hha_void.night_passive") &&
                    (world.registryKey == World.END || world.isNight)
                ) {
                    player.addStatusEffect(
                        StatusEffectInstance(StatusEffects.INVISIBILITY, 60, 0, true, false, true)
                    )
                    player.addStatusEffect(
                        StatusEffectInstance(StatusEffects.NIGHT_VISION, 300, 0, true, false, true)
                    )
                    player.addStatusEffect(
                        StatusEffectInstance(StatusEffects.REGENERATION, 60, 0, true, false, true)
                    )
                }

                if (state.helmet) {
                    player.addStatusEffect(
                        StatusEffectInstance(StatusEffects.REGENERATION, 60, 0, true, false, true)
                    )
                }
                if (state.chestplate) {
                    player.addStatusEffect(
                        StatusEffectInstance(StatusEffects.FIRE_RESISTANCE, 60, 0, true, false, true)
                    )
                }
                if (state.boots) {
                    player.addStatusEffect(
                        StatusEffectInstance(StatusEffects.SPEED, 60, 0, true, false, true)
                    )
                }
            }
        }

        // Brustplatte: Meteor Strike auf Ability 1 (Standard G)
        context.registerAbility(
            SimpleAbility(
                context.id("meteor_strike"), AbilitySlot.ABILITY_1,
                available = { player -> SET.hasPiece(player, net.minecraft.entity.EquipmentSlot.CHEST) },
                action = { player -> MeteorStrike.cast(player) },
            )
        )

        // Set-Bonus: Void Collapse auf Ability 3 (Standard U).
        // Die eingebaute Ultra-Ability ist mit isAvailable={true} registriert
        // und gewinnt den Dispatch immer — daher fangen wir ihren Cast ab,
        // wenn das volle Void-Set getragen wird, und feuern stattdessen den
        // Void Collapse. Die eigene Registrierung bleibt als Fallback.
        context.registerAbility(
            SimpleAbility(
                context.id("void_collapse"), AbilitySlot.ABILITY_3,
                available = { player -> SET.hasFullSet(player) },
                action = { player -> dev.henny.hha_void.logic.VoidCollapse.cast(player) },
            )
        )
        dev.henny.hha.api.event.HhaEvents.ABILITY_CAST.register { player, ability ->
            if (ability.id == net.minecraft.util.Identifier.of("hha", "ultra") && SET.hasFullSet(player)) {
                dev.henny.hha_void.logic.VoidCollapse.cast(player)
                false
            } else {
                true
            }
        }

        // Leggings: Void Pounce auf Ability 2 (Standard H)
        context.registerAbility(
            SimpleAbility(
                context.id("void_pounce"), AbilitySlot.ABILITY_2,
                available = { player -> SET.hasPiece(player, net.minecraft.entity.EquipmentSlot.LEGS) },
                action = { player -> dev.henny.hha_void.logic.VoidPounce.cast(player) },
            )
        )

        // Boots: Fall Damage Immunity
        ServerLivingEntityEvents.ALLOW_DAMAGE.register { entity, source, _ ->
            !(entity is ServerPlayerEntity &&
                source.isIn(DamageTypeTags.IS_FALL) &&
                HhaConfig.enabled("hha_void.boots_fall_immunity") &&
                SET.hasPiece(entity, EquipmentSlot.FEET))
        }

        context.logger.info("Void's Armor geladen — das Nichts blickt zurück.")
    }

    private fun registerConfig(context: HhaAddonContext) {
        // Boots — Void Step
        context.registerToggle("boots_fall_immunity", true)
        context.registerToggle("dash", true)
        context.registerNumber("dash_range", 8.0)
        context.registerNumber("dash_boost", 1.1)
        context.registerNumber("dash_cooldown", 80.0)

        // Leggings — Void Pounce
        context.registerToggle("pounce", true)
        context.registerNumber("pounce_radius", 12.0)
        context.registerNumber("pounce_height", 5.0)
        context.registerNumber("pounce_cooldown", 200.0)

        // Set-Bonus — Void Collapse (Ultra)
        context.registerToggle("collapse", true)
        context.registerNumber("collapse_range", 40.0)
        context.registerNumber("collapse_pull_damage", 2.5)
        context.registerNumber("collapse_damage", 15.0)
        context.registerNumber("collapse_bonus_fall", 16.0)
        context.registerNumber("collapse_cooldown", 2400.0)

        // Set-Passiva
        context.registerToggle("full_invis", true)
        context.registerToggle("share_effects", true)
        context.registerNumber("share_range", 70.0)
        context.registerToggle("void_rescue", true)
        context.registerToggle("escape", true)
        context.registerNumber("escape_health", 6.0)
        context.registerNumber("escape_distance", 14.0)
        context.registerNumber("escape_invis", 600.0)
        context.registerNumber("escape_cooldown", 1200.0)

        // Brustplatte — Meteor Strike
        context.registerToggle("meteor", true)
        context.registerNumber("meteor_range", 24.0)
        context.registerNumber("meteor_count", 12.0)
        context.registerNumber("meteor_speed", 1.3)
        context.registerNumber("meteor_explosion_power", 2.0)
        context.registerNumber("meteor_comet_damage", 16.0)
        context.registerNumber("meteor_cooldown", 700.0)

        // Set-Bonus — Kind der Nacht (nachts oder im End)
        context.registerToggle("night_passive", true)

        // Set-Bonus — Void Link (Stufen-Chancen: Rest ist 1/3)
        context.registerToggle("void_link", true)
        context.registerNumber("link_chance_full", 0.20)
        context.registerNumber("link_chance_two", 0.35)
        context.registerNumber("link_range", 8.0)
        context.registerNumber("link_max_targets", 4.0)

        // Void Sword — Void Shards
        context.registerToggle("shards", true)
        context.registerNumber("shard_throw_damage", 7.0)
        context.registerNumber("shard_throw_speed", 1.6)
        context.registerNumber("shard_range", 22.0)
        context.registerNumber("shard_chain_range", 10.0)
        context.registerNumber("shard_max_jumps", 6.0)
        context.registerNumber("shard_throw_cooldown", 6.0)

        // Void Mace — Sky Launch + Schockwelle
        context.registerToggle("mace_launch", true)
        context.registerNumber("mace_launch_power", 2.4)
        context.registerNumber("mace_launch_cooldown", 160.0)
        context.registerToggle("mace_shock", true)
        context.registerNumber("mace_shock_range", 6.0)
        context.registerNumber("mace_shock_window", 200.0)
    }
}
