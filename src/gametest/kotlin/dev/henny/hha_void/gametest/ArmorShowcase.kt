package dev.henny.hha_void.gametest

import net.minecraft.client.gui.screen.ingame.InventoryScreen
import net.minecraft.client.option.Perspective
import net.minecraft.util.Arm

/**
 * Rüstungs-/Waffen-Vitrine (HHA_VOID_TEST=armor): baut eine Bühne hoch über der Oberwelt und
 * fotografiert Void-Rüstung, -Schwert, -Mace, -Barren und den Ruf der Tiefe.
 *
 *   armor_01..  Mannequins (Void vorn / seitlich / hinten, dazwischen Vanilla-Schwert/-Mace zum Vergleich)
 *               bei Tag, dann bei Nacht (Glow-Ebene)
 *   armor_1x    Spieler in voller Rüstung, 3rd Person (hinten/vorn), Schwert und Mace
 *   armor_16-19 F5 mit normalem Sichtfeld (Mace rechte/linke Hand von hinten, Mace und Schwert von vorn)
 *   armor_2x    1st Person mit Schwert und Mace (Tag, Nacht, armor_26/27: linke Hand)
 *   armor_3x    Inventar (Icons)
 *   armor_4x    Items am Boden, im Rahmen, Altar-Block (Tag/Nacht)
 *   armor_5x    Unsichtbarkeit per Trank am Spieler: volle Rüstung samt Glow muss verschwinden
 */
object ArmorShowcase {

    private const val OW = "minecraft:overworld"
    private const val Y = 200

    private const val SET =
        "head:{id:\"hha_void:void_helmet\",count:1},chest:{id:\"hha_void:void_chestplate\",count:1}," +
            "legs:{id:\"hha_void:void_leggings\",count:1},feet:{id:\"hha_void:void_boots\",count:1}"

    /** Mannequin (Spielermodell) mit Item in der rechten Hand; armor=false: ohne Rüstung (Vanilla-Vergleich) */
    private fun mannequin(t: TestKit, x: Double, z: Double, yaw: Int, hand: String?, armor: Boolean = true) {
        val parts = ArrayList<String>()
        if (armor) parts.add(SET)
        if (hand != null) parts.add("mainhand:{id:\"$hand\",count:1}")
        t.cmd(
            "execute in $OW run summon minecraft:mannequin $x ${Y + 1} $z " +
                "{immovable:1b,hide_description:1b,Tags:[\"void_show\"],Rotation:[${yaw}f,0f],equipment:{${parts.joinToString(",")}}}",
        )
    }

    private fun fov(t: TestKit, value: Int) = t.context.runOnClient<RuntimeException> { it.options.fov.value = value }

    private fun perspective(t: TestKit, p: Perspective) =
        t.context.runOnClient<RuntimeException> { it.options.perspective = p }

    private fun wear(t: TestKit) {
        t.cmd("item replace entity @p armor.head with hha_void:void_helmet")
        t.cmd("item replace entity @p armor.chest with hha_void:void_chestplate")
        t.cmd("item replace entity @p armor.legs with hha_void:void_leggings")
        t.cmd("item replace entity @p armor.feet with hha_void:void_boots")
    }

    /** wie TestKit.look, aber (ex,ey,ez) ist die AUGEN-Position der Kamera (Spieler-Augenhöhe 1,62) */
    private fun eye(t: TestKit, ex: Double, ey: Double, ez: Double, tx: Double, ty: Double, tz: Double, name: String) {
        val vx = tx - ex; val vy = ty - ey; val vz = tz - ez
        val yaw = Math.toDegrees(Math.atan2(-vx, vz)).toFloat()
        val pitch = Math.toDegrees(-Math.atan2(vy, Math.sqrt(vx * vx + vz * vz))).toFloat()
        t.cmd("execute in $OW run tp @p $ex ${ey - 1.62} $ez $yaw $pitch")
        t.waitTicks(15)
        t.shot(name)
    }

    private fun hold(t: TestKit, item: String) = t.cmd("item replace entity @p weapon.mainhand with hha_void:$item")

    fun run(t: TestKit) {
        t.cmd("difficulty peaceful")
        t.cmd("weather clear")
        t.cmd("time set 6000")
        t.cmd("execute in $OW run tp @p 0.5 ${Y + 1} 6.5 180 0")
        t.waitTicks(20)
        // Bühne: dunkler Boden, Rückwand
        t.cmd("execute in $OW run fill -14 $Y -14 14 $Y 14 minecraft:gray_concrete")
        t.cmd("execute in $OW run fill -14 ${Y + 1} -8 14 ${Y + 6} -8 minecraft:light_gray_concrete")
        t.cmd("execute in $OW run tp @p 0.5 ${Y + 1} 6.5 180 0")
        t.waitTicks(20)
        t.world.clientWorld.waitForChunksRender()
        t.hideHud()
        // Kamerafahrten als Zuschauer: keine Schwerkraft, keine Kollision (sonst rutscht die Kamera durch den Boden)
        t.cmd("gamemode spectator @p")

        // ---------------- Mannequins: Void vorn | Vanilla vorn | Void seitlich | Vanilla seitlich | Void hinten
        // (Mannequins haben keine Set-Passive: saubere Sicht auf Rüstung und Waffenhaltung, Vanilla zum Vergleich)
        mannequin(t, -5.5, -3.5, 0, "hha_void:void_sword")
        mannequin(t, -2.5, -3.5, 0, "minecraft:netherite_sword", armor = false)
        mannequin(t, 0.5, -3.5, -90, "hha_void:void_mace")
        mannequin(t, 3.5, -3.5, -90, "minecraft:mace", armor = false)
        mannequin(t, 6.5, -3.5, 180, "hha_void:void_sword")
        t.waitTicks(20)
        for ((label, time) in listOf("day" to 6000, "night" to 18000)) {
            t.cmd("time set $time")
            t.waitTicks(30)
            eye(t, 0.5, Y + 2.4, 3.2, 0.5, Y + 1.9, -3.5, "armor_01_${label}_row")
            eye(t, -5.5, Y + 2.1, -0.9, -5.5, Y + 1.95, -3.5, "armor_02_${label}_front")
            eye(t, 0.5, Y + 2.1, -0.9, 0.5, Y + 1.95, -3.5, "armor_03_${label}_side")
            eye(t, 6.5, Y + 2.1, -0.9, 6.5, Y + 1.95, -3.5, "armor_04_${label}_back")
            // Nahaufnahme Helm + Brust
            eye(t, -4.9, Y + 2.5, -2.3, -5.5, Y + 2.3, -3.5, "armor_05_${label}_closeup")
        }

        // ---------------- Waffenhaltung im Vergleich mit Vanilla: je ein Mannequin, Blick nach Osten,
        // einmal exakt von seiner rechten Seite und einmal exakt von vorn (gleicher Abstand -> direkt vergleichbar)
        t.cmd("time set 6000")
        val compare = listOf(
            "void_sword" to "hha_void:void_sword", "vanilla_sword" to "minecraft:netherite_sword",
            "void_mace" to "hha_void:void_mace", "vanilla_mace" to "minecraft:mace",
        )
        compare.forEachIndexed { i, (_, item) -> mannequin(t, -6.5 + i * 4, 5.5 + i * 2.0, -90, item, armor = false) }
        t.waitTicks(20)
        compare.forEachIndexed { i, (name, _) ->
            val x = -6.5 + i * 4; val z = 5.5 + i * 2.0
            eye(t, x + 0.9, Y + 1.9, z + 3.0, x + 0.9, Y + 1.8, z, "armor_06_hand_side_$name")
            eye(t, x + 2.6, Y + 1.9, z + 0.3, x, Y + 1.8, z + 0.3, "armor_07_hand_front_$name")
        }

        // ---------------- Spieler, 3rd Person
        t.cmd("time set 6000")
        t.cmd("gamemode creative @p")
        wear(t)
        hold(t, "void_sword")
        t.cmd("execute in $OW run tp @p 8.5 ${Y + 1} 3.5 180 5")
        t.waitTicks(20)
        fov(t, 30)
        perspective(t, Perspective.THIRD_PERSON_BACK)
        t.waitTicks(10); t.shot("armor_10_3rd_back_sword")
        perspective(t, Perspective.THIRD_PERSON_FRONT)
        t.waitTicks(10); t.shot("armor_11_3rd_front_sword")
        hold(t, "void_mace")
        t.waitTicks(10); t.shot("armor_12_3rd_front_mace")
        perspective(t, Perspective.THIRD_PERSON_BACK)
        t.waitTicks(10); t.shot("armor_13_3rd_back_mace")
        // Nachts gibt "Kind der Nacht" dem Träger Unsichtbarkeit (ambient). Ob die Rüstung dabei sichtbar
        // bleibt, regelt VoidInvisRender/FullInvis (nicht Teil dieser Vitrine) – die Bilder zeigen den Ist-Zustand.
        t.cmd("time set 18000")
        t.waitTicks(30); t.shot("armor_14_3rd_back_mace_night")
        perspective(t, Perspective.THIRD_PERSON_FRONT)
        hold(t, "void_sword")
        t.waitTicks(10); t.shot("armor_15_3rd_front_sword_night")

        // ---------------- 1st Person
        fov(t, 70)
        perspective(t, Perspective.FIRST_PERSON)
        t.hideHud(false)
        t.cmd("time set 6000")
        t.cmd("item replace entity @p hotbar.1 with hha_void:void_mace")
        t.cmd("item replace entity @p hotbar.2 with hha_void:void_ingot 8")
        t.cmd("item replace entity @p hotbar.3 with hha_void:void_altar")
        t.cmd("item replace entity @p hotbar.4 with minecraft:netherite_sword")
        t.cmd("item replace entity @p hotbar.5 with minecraft:mace")
        t.cmd("execute in $OW run tp @p 8.5 ${Y + 1} 3.5 180 0")
        for ((slot, name) in listOf(0 to "sword", 1 to "mace", 4 to "vanilla_sword", 5 to "vanilla_mace", 2 to "ingot", 3 to "altar")) {
            t.context.runOnClient<RuntimeException> { it.player!!.inventory.selectedSlot = slot }
            t.waitTicks(25); t.shot("armor_2${slot}_1st_$name")
        }
        t.context.runOnClient<RuntimeException> { it.player!!.inventory.selectedSlot = 0 }
        t.cmd("time set 18000")
        t.waitTicks(30); t.shot("armor_28_1st_sword_night")
        t.context.runOnClient<RuntimeException> { it.player!!.inventory.selectedSlot = 1 }
        t.waitTicks(25); t.shot("armor_29_1st_mace_night")
        t.cmd("time set 6000")
        // linke Hand (Linkshänder-Option): muss das Spiegelbild der rechten Hand zeigen
        t.context.runOnClient<RuntimeException> { it.options.mainArm.value = Arm.LEFT; it.options.sendClientSettings() }
        for ((slot, name) in listOf(0 to "sword", 1 to "mace")) {
            t.context.runOnClient<RuntimeException> { it.player!!.inventory.selectedSlot = slot }
            t.waitTicks(25); t.shot("armor_2${6 + slot}_1st_${name}_left")
        }
        // F5 mit normalem Sichtfeld (so sieht der Spieler selbst den Hammer), rechte und linke Hand
        t.hideHud()
        perspective(t, Perspective.THIRD_PERSON_BACK)
        t.waitTicks(10); t.shot("armor_16_f5_back_mace_left")
        t.context.runOnClient<RuntimeException> { it.options.mainArm.value = Arm.RIGHT; it.options.sendClientSettings() }
        t.waitTicks(10); t.shot("armor_17_f5_back_mace")
        perspective(t, Perspective.THIRD_PERSON_FRONT)
        t.waitTicks(10); t.shot("armor_18_f5_front_mace")
        t.context.runOnClient<RuntimeException> { it.player!!.inventory.selectedSlot = 0 }
        t.waitTicks(10); t.shot("armor_19_f5_front_sword")
        perspective(t, Perspective.FIRST_PERSON)
        t.hideHud(false)

        // ---------------- Inventar
        t.cmd("gamemode survival @p")
        t.waitTicks(10)
        t.context.runOnClient<RuntimeException> { it.setScreen(InventoryScreen(it.player!!)) }
        t.waitTicks(20); t.shot("armor_30_inventory")
        t.context.runOnClient<RuntimeException> { it.setScreen(null) }
        t.cmd("gamemode creative @p")

        // ---------------- Items am Boden / im Rahmen / Altar (Zuschauer: weckt den Altar nicht)
        t.cmd("gamemode spectator @p")
        t.hideHud()
        val ax = 60
        t.cmd("execute in $OW run tp @p ${ax + 0.5} ${Y + 2} 4.5 180 10")
        t.waitTicks(20)
        t.cmd("execute in $OW run fill ${ax - 6} $Y -6 ${ax + 6} $Y 6 minecraft:end_stone")
        t.cmd("execute in $OW run fill ${ax - 6} ${Y + 1} -4 ${ax + 6} ${Y + 4} -4 minecraft:light_gray_concrete")
        t.cmd("execute in $OW run setblock $ax ${Y + 1} 0 hha_void:void_altar")
        t.cmd("execute in $OW run setblock ${ax + 3} ${Y + 1} 0 hha_void:void_altar")
        var i = 0
        for (item in listOf("void_sword", "void_mace", "void_ingot", "void_helmet", "void_altar")) {
            t.cmd("execute in $OW run summon item ${ax - 4.5 + i * 0.9} ${Y + 1.1} 1.5 {Item:{id:\"hha_void:$item\",count:1},PickupDelay:32767,Age:-32768,NoGravity:1b}")
            i++
        }
        i = 0
        for (item in listOf("void_sword", "void_mace", "void_ingot", "void_chestplate", "void_altar")) {
            t.cmd("execute in $OW run summon item_frame ${ax - 5 + i} ${Y + 2} -3 {Facing:3b,Fixed:1b,block_pos:[I;${ax - 5 + i},${Y + 2},-3],Item:{id:\"hha_void:$item\",count:1}}")
            i++
        }
        t.world.clientWorld.waitForChunksRender()
        for ((label, time) in listOf("day" to 6000, "night" to 18000)) {
            t.cmd("time set $time")
            t.waitTicks(30)
            eye(t, ax + 1.5, Y + 2.3, 3.2, ax + 1.5, Y + 1.5, 0.5, "armor_40_${label}_altar")
            eye(t, ax + 2.0, Y + 1.9, 1.6, ax + 0.5, Y + 1.5, 0.5, "armor_41_${label}_altar_close")
            eye(t, ax + 0.5, Y + 3.2, 1.3, ax + 0.5, Y + 1.5, 0.5, "armor_42_${label}_altar_top")
            eye(t, ax - 2.7, Y + 1.9, 3.3, ax - 2.7, Y + 1.3, 1.5, "armor_43_${label}_ground_items")
            eye(t, ax - 3.0, Y + 2.5, -0.6, ax - 3.0, Y + 2.5, -3.0, "armor_44_${label}_frames")
        }
        t.cmd("execute in $OW run fill ${ax - 6} ${Y + 1} -6 ${ax + 6} ${Y + 4} 6 minecraft:air")
        t.cmd("kill @e[type=item]")
        t.cmd("kill @e[type=item_frame]")

        // ---------------- Unsichtbarkeit (echter Trank-Effekt am Spieler): Rüstung + Glow müssen weg sein
        t.cmd("time set 6000")
        t.cmd("gamemode creative @p")
        t.hideHud()
        wear(t)
        hold(t, "void_sword")
        t.cmd("execute in $OW run tp @p 8.5 ${Y + 1} 3.5 180 5")
        fov(t, 30)
        perspective(t, Perspective.THIRD_PERSON_FRONT)
        t.waitTicks(30); t.shot("armor_50_visible_player_front")
        t.cmd("effect give @p minecraft:invisibility 60 0")
        t.waitTicks(30); t.shot("armor_51_invis_player_front")
        perspective(t, Perspective.THIRD_PERSON_BACK)
        t.waitTicks(10); t.shot("armor_52_invis_player_back")
        // ohne volles Set greift Full-Invis nicht: die übrigen Teile samt Glow sind wieder da
        t.cmd("item replace entity @p armor.feet with minecraft:air")
        t.waitTicks(20); t.shot("armor_53_invis_player_no_boots")
        t.cmd("effect clear @p")
        fov(t, 70)
        perspective(t, Perspective.FIRST_PERSON)
        println("[gametest] ArmorShowcase fertig")
    }
}
