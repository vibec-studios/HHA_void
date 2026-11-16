package dev.henny.hha_void.gametest

import dev.henny.hha_void.boss.HarpoonLauncherEntity
import dev.henny.hha_void.boss.LeviathanBlocks
import dev.henny.hha_void.boss.LeviathanBody
import dev.henny.hha_void.boss.LeviathanCoreEntity
import dev.henny.hha_void.boss.TribunalBuilder
import dev.henny.hha_void.boss.VoidBoss
import dev.henny.hha_void.boss.VoidBossEntity
import dev.henny.hha_void.boss.WhalePose
import dev.henny.hha_void.client.boss.LeviathanClient
import net.minecraft.client.gui.hud.debug.DebugHudEntries
import net.minecraft.client.gui.hud.debug.DebugHudEntryVisibility
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.stat.Stats
import net.minecraft.util.math.Box
import net.minecraft.util.math.Vec3d
import net.minecraft.world.World
import kotlin.math.sqrt

/**
 * Kampf-Durchlauf gegen den Leviathan (HHA_VOID_TEST=fight) in der großen Himmelsarena (Ø ≈ 372) im End:
 *  - hitbox: Hitboxen sichtbar gegen das Modell, mehrere Haltungen (Flug gerade/geneigt, Kiefer offen, Fluke oben, Rolle,
 *    gestrandet) aus mehreren Winkeln, dazu im Kurvenflug,
 *  - attacks: jeder geänderte Angriff gegen einen still stehenden Spieler (Treffer = Schadensstatistik steigt),
 *  - ride: Bruchlandung, rauf auf den Rücken, mitfliegen (Kurven, Steigen/Sinken), Abschütteln,
 *  - harpoon: von einer Außenplattform festnageln,
 *  - phase2 / belly (alle 5 Abschnitte, Nachzügler-Sog, Herzschild) / p3 (Treffbarkeit) / death / robust.
 * Schreibt jede Sekunde den Zustand ins Log ("[fight]") und am Ende eine Zusammenfassung ("[fight] SUMMARY").
 * Teilmengen: HHA_VOID_FIGHT=hitbox,attacks,ride,harpoon,phase2,belly,p3,death,robust (Standard: alles).
 */
object FightShowcase {

    private const val X = 1500
    private const val Y = 180
    private const val Z = 0
    private const val END = "minecraft:the_end"
    private const val BOSS = "@e[type=hha_void:void_boss,limit=1]"

    private class Holder<T>(val value: T)

    private val summary = ArrayList<String>()

    private fun <T> server(t: TestKit, f: (MinecraftServer) -> T): T =
        t.world.server.computeOnServer<Holder<T>, RuntimeException> { Holder(f(it)) }.value

    private fun boss(s: MinecraftServer): VoidBossEntity? =
        s.getWorld(World.END)!!.getEntitiesByType(VoidBoss.BOSS, Box.of(Vec3d(X + 0.5, Y.toDouble(), Z + 0.5), 900.0, 600.0, 900.0)) { true }.firstOrNull()

    private fun player(s: MinecraftServer): ServerPlayerEntity = s.playerManager.playerList.first()

    private fun state(t: TestKit): String {
        val back = t.context.computeOnClient<String, RuntimeException> { "${LeviathanClient.onBack}/${LeviathanClient.isRiding()}" }
        return server(t) { s ->
            val w = s.getWorld(World.END)!!
            val b = boss(s)
            val p = s.playerManager.playerList.firstOrNull()
            val starlings = w.getEntitiesByType(VoidBoss.DEBTOR, Box.of(Vec3d(X + 0.5, Y.toDouble(), Z + 0.5), 600.0, 400.0, 600.0)) { it.isAlive }.size
            val parts = w.getEntitiesByType(VoidBoss.PART, Box.of(Vec3d(X + 0.5, Y.toDouble(), Z + 0.5), 900.0, 600.0, 900.0)) { true }.size
            val pl = p?.let { "y %.1f (%.1f,%.1f) hp %.1f back=%s".format(it.y - Y, it.x - X, it.z - Z, it.health, back) }
            if (b == null) "boss=none parts=$parts player=($pl)"
            else ("pose=${WhalePose.name(b.castPose())} phase=${b.bossPhase()} cast=${b.currentCast()} scene=${b.sceneName()} hp=%.0f/%.0f " +
                "pos=(%.1f, %.1f, %.1f) yaw=%.0f pitch=%.0f belly=${b.bellyStateName()} riders=${b.riderCount()} tethers=${b.tetherCount()} " +
                "exposed=${b.isExposed()} parts=$parts starlings=$starlings player=($pl)").format(b.health, b.maxHealth, b.x - X, b.y - Y, b.z - Z, b.yaw, b.pitch)
        }
    }

    private fun watch(t: TestKit, seconds: Int, label: String, shotEvery: Int = 0, step: Int = 20, face: Boolean = false) {
        val n = seconds * 20 / step
        for (s in 0 until n) {
            t.waitTicks(step)
            println("[fight] $label +${(s + 1) * step / 20.0}s ${state(t)}")
            if (shotEvery > 0 && s % shotEvery == 0) {
                if (face) t.cmd("execute in $END as @p at @s run tp @s ~ ~ ~ facing entity $BOSS eyes")
                t.shot("fight_${label}_${"%02d".format(s)}")
            }
        }
    }

    private fun attack(t: TestKit, n: Int) = t.cmd("execute in $END positioned $X $Y $Z run voidboss attack $n")

    private fun hitboxes(t: TestKit, on: Boolean) = t.context.runOnClient<RuntimeException> {
        it.debugHudEntryList.setEntryVisibility(DebugHudEntries.ENTITY_HITBOXES, if (on) DebugHudEntryVisibility.ALWAYS_ON else DebugHudEntryVisibility.NEVER)
    }

    private fun thirdPerson(t: TestKit, on: Boolean) = t.context.runOnClient<RuntimeException> {
        it.options.perspective = if (on) net.minecraft.client.option.Perspective.THIRD_PERSON_BACK else net.minecraft.client.option.Perspective.FIRST_PERSON
    }

    /** Gesamter erlittener Schaden (auch abgewehrter) des Spielers ×10 – steigt bei jedem Treffer. */
    private fun damage(t: TestKit): Int = server(t) { s ->
        val st = player(s).statHandler
        st.getStat(Stats.CUSTOM.getOrCreateStat(Stats.DAMAGE_TAKEN)) + st.getStat(Stats.CUSTOM.getOrCreateStat(Stats.DAMAGE_RESISTED)) +
            st.getStat(Stats.CUSTOM.getOrCreateStat(Stats.DAMAGE_ABSORBED))
    }

    /** Wie ein Spieler aufspringt: auf den Rücken setzen, und wenn der Client ihn nicht aufnimmt (Wal schon weiter), nochmal. */
    private fun mount(t: TestKit, volume: String) {
        for (i in 0 until 4) {
            climb(t, volume)
            t.waitTicks(8)
            if (t.context.computeOnClient<Boolean, RuntimeException> { LeviathanClient.isRiding() }) return
        }
    }

    /** Stellt den Spieler auf die Rückenfläche über dem Volumen [volume] (Lage, die der Client sieht). */
    private fun climb(t: TestKit, volume: String) {
        val pos = server(t) { s ->
            val b = boss(s) ?: return@server null
            val f = b.seen()
            val c = f.centers[LeviathanBody.indexOf(volume)] ?: return@server null
            val top = f.surface(Vec3d(c.x, c.y + 30.0, c.z), above = 0.0, below = 60.0) ?: return@server null
            Vec3d(c.x, top.y + 0.15, c.z)
        } ?: return
        t.cmd("execute in $END run tp @p ${pos.x} ${pos.y} ${pos.z}")
    }

    /** Kamera relativ zum Wal: [back] hinter, [side] seitlich, [up] über seiner Körpermitte. */
    private fun lookAtBoss(t: TestKit, name: String, back: Double, side: Double, up: Double, wait: Int = 4) {
        val cam = server(t) { s ->
            val b = boss(s) ?: return@server null
            val c = b.entityPos.add(0.0, LeviathanBody.BODY_UP, 0.0)
            val f = LeviathanBody.forward(b.yaw, 0f)
            val r = LeviathanBody.right(b.yaw)
            c.add(f.multiply(-back)).add(r.multiply(side)).add(0.0, up, 0.0) to c
        } ?: return
        t.look(END, cam.first.x, cam.first.y, cam.first.z, cam.second.x, cam.second.y, cam.second.z, name, wait)
    }

    // ------------------------------------------------------------------------------------------------ Teile

    /** Hitboxen gegen das Modell: Wal angehalten (NoAI), Haltung gesetzt, aus drei Winkeln. */
    private fun hitboxTest(t: TestKit) {
        t.cmd("data merge entity $BOSS {NoAI:1b}")
        t.cmd("gamemode spectator @p")
        t.hideHud()
        hitboxes(t, true)
        val cx = X + 0.5; val cz = Z + 30.5
        data class P(val pose: Int, val age: Int, val pitch: Float, val name: String, val floor: Boolean = false)
        for (p in listOf(
            P(WhalePose.SWIM, 10, 0f, "swim"), P(WhalePose.SWIM, 10, 25f, "swim_dive"), P(WhalePose.SWIM, 10, -22f, "swim_climb"),
            P(WhalePose.SWALLOW, 9, 0f, "jaw_open"), P(WhalePose.TAIL_SLAM, 14, 0f, "fluke_up"), P(WhalePose.BARREL_ROLL, 26, 0f, "roll"),
            P(WhalePose.BEACHED, 40, 0f, "beached", floor = true),
        )) {
            val y = if (p.floor) Y + 1 + LeviathanBody.BEACH_LIFT else Y + 18.0
            t.cmd("execute in $END run tp $BOSS $cx $y $cz 30 ${p.pitch}")
            t.cmd("execute in $END positioned $X $Y $Z run voidboss pose ${p.pose}")
            t.waitTicks(p.age)
            lookAtBoss(t, "fight_05_hb_${p.name}_side", back = 4.0, side = 62.0, up = 6.0, wait = 3)
            lookAtBoss(t, "fight_05_hb_${p.name}_front", back = -55.0, side = 30.0, up = 18.0, wait = 3)
            lookAtBoss(t, "fight_05_hb_${p.name}_top", back = 26.0, side = -24.0, up = 44.0, wait = 3)
            val coverage = server(t) { s -> boss(s)?.seen()?.let { f -> "boxes=${f.activeBoxes().size} mouth=${f.mouth().subtract(f.center()).let { v -> "%.1f,%.1f,%.1f".format(v.x, v.y, v.z) }} gape=%.1f".format(f.gape()) } }
            println("[fight] hitbox ${p.name}: $coverage")
        }
        t.cmd("execute in $END positioned $X $Y $Z run voidboss pose ${WhalePose.SWIM}")
        t.cmd("data merge entity $BOSS {NoAI:0b}")
        t.cmd("execute in $END run tp $BOSS $cx ${Y + 30.0} $cz 0 0")
        // im Kurvenflug (Kreisbahn um den Spieler)
        t.cmd("gamemode survival @p")
        t.cmd("execute in $END run tp @p ${X + 0.5} ${Y + 1} ${Z + 30.5} 180 -20")
        t.waitTicks(80)
        t.cmd("gamemode spectator @p")
        for (i in 0 until 3) {
            lookAtBoss(t, "fight_06_hb_turn_$i", back = 10.0, side = 70.0, up = 25.0, wait = 2)
            t.waitTicks(30)
        }
        hitboxes(t, false)
        t.hideHud(false)
        t.cmd("gamemode survival @p")
        t.cmd("execute in $END run tp @p ${X + 0.5} ${Y + 1} ${Z + 30.5} 180 -20")
        t.waitTicks(20)
    }

    /** Angriff [n] gegen den still stehenden Spieler; Treffer = Schadensstatistik steigt. */
    private fun hitTest(t: TestKit, n: Int, label: String, secs: Int, at: Vec3d = Vec3d(X + 0.5, Y + 1.0, Z + 30.5), shots: Int = 2) {
        t.cmd("execute in $END run tp @p ${at.x} ${at.y} ${at.z} 180 -15")
        t.waitTicks(10)
        val before = damage(t)
        attack(t, n)
        watch(t, secs, label, shotEvery = shots, face = true)
        val delta = damage(t) - before
        val line = "attack $n ${VoidBossEntity.ATTACK_NAMES[n]}: ${if (delta > 0) "TRIFFT" else "kein Treffer"} (+${delta / 10.0} Schaden)"
        println("[fight] HIT $line")
        summary += line
    }

    /** Draufstehen: wie viele Proben stand der Spieler auf dem Rücken, wie oft fiel er durch? */
    private fun rideWatch(t: TestKit, ticks: Int, label: String, shotEvery: Int = 0): String {
        var on = 0; var riding = 0; var fell = 0; var n = 0
        var i = 0
        while (i < ticks) {
            t.waitTicks(5)
            i += 5
            n++
            val c = t.context.computeOnClient<Pair<Boolean, Boolean>, RuntimeException> { LeviathanClient.onBack to LeviathanClient.isRiding() }
            if (c.first) on++
            if (c.second) riding++
            val below = server(t) { s ->
                val b = boss(s) ?: return@server false
                val p = player(s)
                val top = b.seen().surface(p.entityPos.add(0.0, 2.0, 0.0), above = 0.0, below = 30.0)
                top != null && p.y < top.y - 1.5
            }
            if (below) fell++
            if (shotEvery > 0 && n % shotEvery == 0) t.shot("fight_${label}_${"%02d".format(n)}")
        }
        val r = "$label: auf dem Rücken $on/$n, getragen $riding/$n, unter der Rückenfläche $fell/$n"
        println("[fight] RIDE $r — ${state(t)}")
        return r
    }

    private fun rideTest(t: TestKit) {
        // Rammstoß mit Bruchlandung, vom Monolithen aus
        val (mx, mz) = TribunalBuilder.MONOLITHS[3]
        t.cmd("execute in $END run tp @p ${X + mx + 0.5} ${Y + 18} ${Z + mz + 0.5} 200 20")
        attack(t, 3)
        watch(t, 3, "20_ram_lineup", shotEvery = 1, step = 10)
        var tries = 0
        while (server(t) { s -> boss(s)?.castPose() } != WhalePose.BEACHED && tries++ < 30) t.waitTicks(5)
        t.waitTicks(10)
        hitboxes(t, true)
        t.hideHud()
        lookAtBoss(t, "fight_22_beached_hitboxes", back = 6.0, side = 44.0, up = 22.0)
        lookAtBoss(t, "fight_22_beached_hitboxes_tail", back = 55.0, side = 24.0, up = 14.0)
        t.hideHud(false)
        hitboxes(t, false)
        thirdPerson(t, true)
        // Fluke (liegt flach am Boden) → Rücken
        climb(t, "fluke")
        t.waitTicks(15)
        println("[fight] on fluke: ${state(t)}")
        climb(t, "chest1")
        summary += rideWatch(t, 60, "23_on_back_beached", shotEvery = 4)
        // er stemmt sich hoch (wirft dabei ab) und fliegt – wieder rauf, der Spieler muss mitfahren (Kurven, Steigen)
        var up = 0
        while (server(t) { s -> boss(s)?.let { it.castPose() == WhalePose.BEACHED || it.y < Y + 8 } ?: false } && up++ < 60) t.waitTicks(5)
        t.waitTicks(20)
        mount(t, "chest1")
        t.waitTicks(4)
        summary += rideWatch(t, 200, "24_riding_flight", shotEvery = 8)
        // Tiefflug und Abtauchen-Steigflug mitfahren
        mount(t, "belly0")
        t.waitTicks(10)
        attack(t, 18)
        summary += rideWatch(t, 120, "24b_riding_lowpass", shotEvery = 8)
        for ((n, label) in listOf(11 to "25_roll", 12 to "26_quake", 13 to "27_sweep", 14 to "28_sounding")) {
            mount(t, "chest1")
            t.waitTicks(20)
            println("[fight] $label before: ${state(t)}")
            attack(t, n)
            watch(t, 4, label, shotEvery = 1, step = 10)
            val off = t.context.computeOnClient<Boolean, RuntimeException> { !LeviathanClient.isRiding() }
            val line = "shake $n ${VoidBossEntity.ATTACK_NAMES[n]}: ${if (off) "abgeworfen" else "noch drauf"}"
            println("[fight] $line")
            summary += line
        }
        thirdPerson(t, false)
        t.cmd("execute in $END run tp @p ${X + 0.5} ${Y + 1} ${Z + 30.5} 180 -20")
        watch(t, 3, "29_after_ride")
    }

    private fun harpoonTest(t: TestKit) {
        val spots = TribunalBuilder.HARPOONS
        // nächste Außenplattform zum Wal
        val spot = server(t) { s ->
            val b = boss(s)!!
            spots.minBy { b.squaredDistanceTo(X + it.x + 0.5, Y + it.y + 0.0, Z + it.z + 0.5) }
        }
        val at = Vec3d(X + spot.x + 0.5, Y + spot.y + 0.0, Z + spot.z + 2.5)
        t.cmd("execute in $END run tp @p ${at.x} ${at.y} ${at.z}")
        watch(t, 6, "35_outpost")
        var pinned = false
        for (i in 0 until 20) {
            val r = server(t) { s ->
                val w = s.getWorld(World.END)!!
                val be = w.getBlockEntity(net.minecraft.util.math.BlockPos(X + spot.x, Y + spot.y, Z + spot.z)) as? HarpoonLauncherEntity
                be?.fire(w, player(s))
                "${be != null} dist=%.0f".format(boss(s)!!.entityPos.distanceTo(at))
            }
            t.waitTicks(30)
            val cast = server(t) { s -> boss(s)?.currentCast() }
            println("[fight] harpoon try $i: $r cast=$cast")
            if (cast == VoidBossEntity.PINNED) { pinned = true; break }
        }
        t.shot("fight_36_harpoon_rope")
        watch(t, 5, "36_pinned", shotEvery = 1)
        val beached = server(t) { s -> boss(s)?.let { it.castPose() == WhalePose.BEACHED && it.isExposed() } ?: false }
        val line = "harpoon: ${if (pinned) "festgenagelt" else "NICHT festgenagelt"}, liegt am Boden+offen=$beached"
        println("[fight] $line")
        summary += line
        t.hideHud()
        lookAtBoss(t, "fight_37_pinned_overview", back = 20.0, side = 60.0, up = 30.0)
        t.hideHud(false)
        t.cmd("execute in $END run tp @p ${X + 0.5} ${Y + 1} ${Z + 30.5} 180 -20")
        watch(t, 8, "38_after_pin")
    }

    private fun bellyTest(t: TestKit) {
        t.cmd("execute in $END run tp @p ${X + 0.5} ${Y + 1} ${Z + 30.5} 180 -20")
        t.cmd("execute in $END positioned $X $Y $Z run voidboss phase 3")
        watch(t, 10, "40_devour", shotEvery = 2)
        watch(t, 5, "41_belly_intro", shotEvery = 1)
        val center = server(t) { s -> boss(s)?.bellyCenter() }
        println("[fight] belly center $center")
        if (center == null) {
            summary += "belly: FEHLT"
            return
        }
        for (sec in 0..4) {
            val e = server(t) { s -> boss(s)?.bellyEntry(sec) } ?: break
            if (sec >= 2) t.cmd("execute in $END run tp @p ${e.x} ${e.y + 0.1} ${e.z}")
            t.waitTicks(20)
            t.shot("fight_42_belly_s${sec}_a")
            // Blick in den Abschnitt (zu den Zielen)
            val look = server(t) { s ->
                val w = s.getWorld(World.END)!!
                val p = player(s)
                w.getEntitiesByType(VoidBoss.CORE, Box.of(p.entityPos, 200.0, 120.0, 200.0)) { it.isAlive }.minByOrNull { it.squaredDistanceTo(p) }?.entityPos
            }
            if (look != null) {
                val p = server(t) { s -> player(s).entityPos }
                t.look(END, p.x, p.y + 1.6, p.z, look.x, look.y + 1.5, look.z, "fight_42_belly_s${sec}_goal", wait = 10)
            }
            watch(t, 4, "43_belly_s$sec")
            if (sec == 4) {
                // das Herz ist geschützt, solange Klappen leben
                val shield = server(t) { s ->
                    val w = s.getWorld(World.END)!!
                    val heart = w.getEntitiesByType(VoidBoss.CORE, Box.of(player(s).entityPos, 200.0, 120.0, 200.0)) { it.isAlive && it.role == LeviathanCoreEntity.HEART }.firstOrNull()
                    val hp0 = heart?.health
                    heart?.damage(w, w.damageSources.playerAttack(player(s)), 30f)
                    val valves = w.getEntitiesByType(VoidBoss.CORE, Box.of(player(s).entityPos, 200.0, 120.0, 200.0)) { it.isAlive && it.role == LeviathanCoreEntity.VALVE }
                    for (v in valves) v.kill(w)
                    "heart hp ${hp0} → ${heart?.health} (geschützt), Klappen ${valves.size} zerstört"
                }
                println("[fight] belly heart: $shield")
                summary += "belly heart: $shield"
                t.waitTicks(10)
                val open = server(t) { s ->
                    val w = s.getWorld(World.END)!!
                    w.getEntitiesByType(VoidBoss.CORE, Box.of(player(s).entityPos, 200.0, 120.0, 200.0)) { it.isAlive && it.role == LeviathanCoreEntity.HEART }.firstOrNull()?.shielded
                }
                println("[fight] belly heart shielded after valves: $open")
            }
            t.cmd("execute in $END positioned $X $Y $Z run voidboss phase 5")
            watch(t, 2, "44_belly_s${sec}_done", shotEvery = 1)
            if (sec == 0) {
                // Nachzügler: nach 15 s reißt der Sog mit
                watch(t, 15, "45_belly_pull")
                val now = server(t) { s -> boss(s)?.bellySection() to player(s).entityPos.subtract(Vec3d.of(center)) }
                println("[fight] belly after pull: section/player=$now")
                summary += "belly pull: $now"
            }
        }
        watch(t, 9, "46_breakout", shotEvery = 1)
        watch(t, 6, "47_reeling", shotEvery = 2)
        val inBelly = server(t) { s -> player(s).y < Y - 30 }
        summary += "belly: Ende im Freien=${!inBelly}, Zustand ${server(t) { s -> boss(s)?.bellyStateName() }}"
        check(!inBelly) { "Spieler steckt nach dem Ausbruch noch unter der Arena" }
    }

    /** Phase III: wie oft ist ein Volumen in Nahkampf-Reichweite eines Spielers am Boden, und zählen echte Schläge? */
    private fun p3Reach(t: TestKit) {
        var reach = 0; var n = 0; var hits = 0
        for (i in 0 until 160) {
            t.waitTicks(5)
            if (i == 40) attack(t, 18)
            val r = server(t) { s ->
                val w = s.getWorld(World.END)!!
                val b = boss(s) ?: return@server Triple(false, false, 99.0)
                val p = player(s)
                val eye = p.eyePos
                val parts = w.getEntitiesByType(VoidBoss.PART, Box.of(eye, 20.0, 20.0, 20.0)) { it.active && it.canHit() }
                val best = parts.minByOrNull { it.boundingBox.squaredMagnitude(eye) }
                val d = best?.let { sqrt(it.boundingBox.squaredMagnitude(eye)) } ?: 99.0
                var hit = false
                if (best != null && d < 4.5) {
                    val hp = b.health
                    p.attack(best)
                    hit = b.health < hp
                }
                Triple(d < 4.5, hit, d)
            }
            n++
            if (r.first) reach++
            if (r.second) hits++
            // der Spieler läuft dem Wal hinterher (bleibt am Boden unter ihm)
            if (i % 8 == 0) server(t) { s ->
                val b = boss(s) ?: return@server Unit
                val p = player(s)
                val c = b.seen().center()
                val dx = c.x - p.x; val dz = c.z - p.z
                val l = sqrt(dx * dx + dz * dz)
                if (l > 6) {
                    val k = (l - 4).coerceAtMost(14.0) / l
                    val nx = p.x + dx * k; val nz = p.z + dz * k
                    if ((nx - X) * (nx - X) + (nz - Z) * (nz - Z) < 170.0 * 170.0) p.requestTeleport(nx, Y + 1.0, nz)
                }
                Unit
            }
            if (i % 20 == 0) println("[fight] p3reach +${i / 4}s dist=%.1f ${state(t)}".format(r.third))
        }
        val line = "phase III: in Reichweite $reach/$n Proben, echte Treffer $hits"
        println("[fight] $line")
        summary += line
    }

    /** Robustheit: zweiter Wal ohne Arena, Verschlingen, mitten im Bauch entfernt → Spieler frei, Innenraum weg. */
    private fun robust(t: TestKit) {
        t.cmd("execute in $END run tp @p ${X + 0.5} ${Y + 1} ${Z + 20.5} 180 0")
        t.waitTicks(20)
        t.cmd("execute in $END positioned ${X + 0.5} ${Y + 1} ${Z + 20.5} rotated 180 0 run voidboss spawn")
        watch(t, 12, "70_robust_intro")
        t.cmd("execute in $END positioned $X $Y $Z run voidboss phase 3")
        watch(t, 12, "71_robust_devour", shotEvery = 6)
        val center = server(t) { s -> boss(s)?.bellyCenter() }
        val inside = server(t) { s -> player(s).let { it.commandTags.contains("hha_void.belly") to it.y } }
        println("[fight] robust: belly $center, player marked/y $inside")
        t.cmd("execute in $END positioned $X $Y $Z run voidboss remove")
        watch(t, 3, "72_robust_removed", shotEvery = 1)
        val after = server(t) { s ->
            val p = player(s)
            val w = s.getWorld(World.END)!!
            val leftovers = center?.let { c ->
                var n = 0
                val belly = listOf(LeviathanBlocks.FLESH, LeviathanBlocks.VEIN, LeviathanBlocks.RIB, LeviathanBlocks.ACID, LeviathanBlocks.MEMBRANE, LeviathanBlocks.LUNG)
                for (x in -100..115 step 1) for (y in -32..40) for (z in -58..50) if (belly.any { b -> w.getBlockState(c.add(x, y, z)).isOf(b) }) n++
                n
            } ?: -1
            "marked=${p.commandTags.contains("hha_void.belly")} y=%.1f alive=${p.isAlive} bellyBlocksLeft=$leftovers cores=${w.getEntitiesByType(VoidBoss.CORE) { true }.size}".format(p.y - Y)
        }
        println("[fight] robust after remove: $after")
        summary += "robust: $after"
        check(after.contains("marked=false") && after.contains("alive=true") && after.contains("bellyBlocksLeft=0")) { "Bauch nach Entfernen nicht sauber: $after" }
    }

    fun run(t: TestKit) {
        val only = System.getenv("HHA_VOID_FIGHT") ?: "hitbox,attacks,ride,harpoon,phase2,belly,p3,death,robust"
        t.context.runOnClient<RuntimeException> { it.options.viewDistance.value = 16; it.options.sendClientSettings() }
        t.cmd("execute in $END run tp @p $X ${Y + 2} $Z")
        t.waitTicks(40)
        val t0 = System.nanoTime()
        t.cmd("execute in $END positioned $X ${Y + 1} $Z run voidboss place")
        var waited = 0
        while (server(t) { TribunalBuilder.isPlacing() } && waited < 6000) { t.waitTicks(5); waited += 5 }
        println("[fight] arena placed after $waited ticks (${(System.nanoTime() - t0) / 1_000_000} ms)")
        t.waitTicks(60)
        t.cmd("gamemode survival @p")
        t.cmd("effect give @p minecraft:resistance infinite 3 true")
        t.cmd("effect give @p minecraft:regeneration infinite 2 true")
        t.cmd("effect give @p minecraft:saturation infinite 0 true")
        t.cmd("item replace entity @p armor.head with minecraft:netherite_helmet")
        t.cmd("item replace entity @p armor.chest with minecraft:netherite_chestplate")
        t.cmd("item replace entity @p armor.legs with minecraft:netherite_leggings")
        t.cmd("item replace entity @p armor.feet with minecraft:netherite_boots")
        t.cmd("item replace entity @p weapon.mainhand with minecraft:mace")
        t.cmd("execute in $END run tp @p ${X + 0.5} ${Y + 1} ${Z + 40.5} 180 10")
        t.waitTicks(30)
        t.shot("fight_00_arena")
        t.cmd("execute in $END run tp @p ${X + 0.5} ${Y + 1} ${Z + 14.5} 180 -10")
        println("[fight] waiting for altar")
        watch(t, 6, "01_altar")
        watch(t, 12, "02_intro", shotEvery = 2)
        t.cmd("execute in $END run tp @p ${X + 0.5} ${Y + 1} ${Z + 30.5} 180 -20")
        watch(t, 4, "03_p1")
        // natürlicher Spawn auf der Arena gesperrt? (hineingesetzter Enderman verschwindet)
        t.cmd("execute in $END run summon minecraft:enderman ${X + 20} ${Y + 1} ${Z + 20}")
        t.waitTicks(90)
        val enders = server(t) { s -> s.getWorld(World.END)!!.getEntitiesByType(net.minecraft.entity.EntityType.ENDERMAN, Box.of(Vec3d(X + 0.5, Y + 0.0, Z + 0.5), 600.0, 300.0, 600.0)) { true }.size }
        summary += "endermen auf der Arena nach 90 Ticks: $enders"
        println("[fight] endermen on arena: $enders")

        if ("hitbox" in only) hitboxTest(t)

        if ("attacks" in only) {
            hitTest(t, 0, "10_slam", 6)
            hitTest(t, 1, "11_breath", 9)
            hitTest(t, 2, "12_meteors", 6)
            hitTest(t, 6, "13_snap", 5)
            hitTest(t, 3, "14_ram", 7, shots = 1)
            hitTest(t, 9, "15_geyser", 7)
            hitTest(t, 10, "16_flood", 9)
            hitTest(t, 18, "17_lowpass", 9)
        }

        if ("ride" in only) rideTest(t)
        if ("harpoon" in only) harpoonTest(t)

        if ("phase2" in only) {
            t.cmd("execute in $END run tp @p ${X + 0.5} ${Y + 1} ${Z + 30.5} 180 -20")
            t.cmd("execute in $END positioned $X $Y $Z run voidboss phase 2")
            watch(t, 11, "30_cut2", shotEvery = 1)
            hitTest(t, 5, "31_breach", 14, shots = 1)
            hitTest(t, 4, "32_maelstrom", 8)
            hitTest(t, 16, "33_eruption", 7, shots = 1)
            hitTest(t, 7, "34_starlings", 4)
        }

        if ("belly" in only) bellyTest(t)

        if ("p3" in only) {
            t.cmd("execute in $END run tp @p ${X + 0.5} ${Y + 1} ${Z + 30.5} 180 -20")
            p3Reach(t)
            hitTest(t, 8, "50_supernova", 18, shots = 3)
            hitTest(t, 1, "51_breath_p3", 11)
            hitTest(t, 3, "52_ram_p3", 9)
            t.cmd("execute in $END run tp @p ${X + 0.5} ${Y - 30} ${Z + 190.5}")
            watch(t, 3, "53_fallcatch")
        }

        if ("death" in only) {
            t.cmd("execute in $END run tp @p ${X + 0.5} ${Y + 1} ${Z + 40.5} 180 -25")
            t.cmd("execute in $END run kill $BOSS")
            watch(t, 15, "60_death", shotEvery = 2)
            val loot = server(t) { s ->
                val p = player(s)
                s.getWorld(World.END)!!.getEntitiesByType(net.minecraft.entity.EntityType.ITEM, Box.of(p.entityPos, 400.0, 120.0, 400.0)) { true }
                    .joinToString { "${it.stack.count}x ${it.stack.item} @(%.0f,%.0f,%.0f)".format(it.x - X, it.y - Y, it.z - Z) }
            }
            println("[fight] loot: $loot")
            summary += "loot: $loot"
            check(server(t) { s -> boss(s) } == null) { "Boss lebt nach dem Tod noch" }
            check(loot.isNotEmpty()) { "keine Beute" }
            val parts = server(t) { s -> s.getWorld(World.END)!!.getEntitiesByType(VoidBoss.PART) { true }.size }
            println("[fight] parts left after death: $parts")
        }
        if ("robust" in only) robust(t)
        val alive = server(t) { s -> s.playerManager.playerList.firstOrNull()?.let { it.isAlive && !it.commandTags.contains("hha_void.belly") } ?: false }
        for (l in summary) println("[fight] SUMMARY $l")
        check(alive) { "Spieler tot oder noch im Bauch markiert" }
        println("[fight] fertig")
    }
}
