package dev.henny.hha_void.gametest

import dev.henny.hha_void.boss.VoidBoss
import dev.henny.hha_void.boss.WhalePose
import net.minecraft.util.math.MathHelper
import net.minecraft.util.math.Vec3d
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Vitrine für Oruun und die Sternlinge (HHA_VOID_TEST=whale): Der Wal hängt mit NoAI hoch im
 * Overworld-Himmel, jede Haltung aus [WhalePose] wird per `/voidboss pose` gesetzt, zu ihren
 * Schlüsselzeitpunkten per `/tick freeze` angehalten und aus mehreren Winkeln fotografiert
 * (Tag = Formen und Haut, Nacht = Leuchten). Gestrandet liegt er auf einer Plattform darunter.
 * Zum Schluss fliegt er (`flight`): eine feste Strecke mit Steigen, Sinken und Kurven und die echte Kreisbahn,
 * fotografiert alle fünf Ticks.
 */
object WhaleShowcase {

    private const val OW = "minecraft:overworld"

    // Entity-Position im Flug und auf der Plattform (Boden-Oberkante y = 200; gestrandet 0,8 tiefer wie im Kampf)
    private const val AIR_Y = 230.0
    private const val FLOOR_Y = 200.0
    private const val BOSS = "@e[type=hha_void:void_boss,limit=1]"

    /** Kamerastandorte relativ zur Körpermitte; der Wal schaut nach +Z, seine linke Seite ist +X. */
    private enum class Cam(val x: Double, val y: Double, val z: Double) {
        SIDE(23.0, 1.0, 0.0),
        RIGHT(-23.0, 1.0, 0.0),
        QUARTER(15.0, 4.0, 21.0),
        FRONT(4.0, -2.0, 24.0),
        BELOW(10.0, -14.0, 13.0),
        REAR(-14.0, 10.0, -20.0),
        TOP(0.5, 30.0, -1.0),
        FAR(40.0, 5.0, 28.0),
    }

    /** Leviathan: 2,4-mal so groß wie der alte Sternenwal – alle Kamera-Abstände wachsen mit. */
    private const val K = dev.henny.hha_void.boss.LeviathanBody.K
    private val BODY_UP = dev.henny.hha_void.boss.LeviathanBody.BODY_UP

    private var centerY = AIR_Y + BODY_UP

    private fun boss(t: TestKit, command: String) = t.cmd("execute in $OW positioned 0 $centerY 0 run $command")

    /** Setzt die Haltung neu (über SWIM, damit sie bei Tick 0 beginnt) und hält das Spiel bei [tick] an. */
    private fun pose(t: TestKit, pose: Int, phase: Int, tick: Int) {
        // erst in Reichweite: nach der FAR-Kamera hat der (kleine) Testserver den Wal nicht mehr beim Client
        t.cmd("execute in $OW run tp @p ${0.5 + Cam.QUARTER.x * K} ${centerY + Cam.QUARTER.y * K} ${0.5 + Cam.QUARTER.z * K}")
        t.cmd("tick unfreeze")
        if (pose != WhalePose.SWIM) {
            boss(t, "voidboss pose ${WhalePose.SWIM} $phase")
            t.waitTicks(14)
        }
        boss(t, "voidboss pose $pose $phase")
        t.waitTicks(tick + 2)
        t.cmd("tick freeze")
        t.waitTicks(2)
    }

    /** Eine Haltung zu mehreren Zeitpunkten: je Tick neu gestartet, angehalten und fotografiert. */
    private fun keys(t: TestKit, pose: Int, name: String, ticks: IntArray, vararg cams: Cam, phase: Int = 1) {
        for (tick in ticks) {
            pose(t, pose, phase, tick)
            shot(t, "%s_%03d".format(name, tick), *cams)
        }
    }

    /** Hitboxen (F3+B) an/aus: zeigt, ob die Segmente zum Modell passen. */
    private fun hitboxes(t: TestKit, on: Boolean) = t.context.runOnClient<RuntimeException> {
        it.debugHudEntryList.setEntryVisibility(
            net.minecraft.client.gui.hud.debug.DebugHudEntries.ENTITY_HITBOXES,
            if (on) net.minecraft.client.gui.hud.debug.DebugHudEntryVisibility.ALWAYS_ON else net.minecraft.client.gui.hud.debug.DebugHudEntryVisibility.NEVER,
        )
    }

    private fun shot(t: TestKit, name: String, vararg cams: Cam) {
        for (cam in cams) {
            t.look(OW, 0.5 + cam.x * K, centerY + cam.y * K, 0.5 + cam.z * K, 0.5, centerY, 0.5, "whale_${name}_${cam.name.lowercase()}", wait = 6)
        }
    }

    fun run(t: TestKit) {
        t.hideHud()
        t.cmd("gamemode spectator @p")
        t.cmd("execute in $OW run tp @p 30.5 ${AIR_Y + 5} 0.5")
        t.waitTicks(60)
        t.cmd("weather clear")
        t.cmd("time set 6000")
        t.cmd("execute in $OW run fill -40 199 -40 40 199 40 minecraft:end_stone")
        t.cmd("execute in $OW run summon hha_void:void_boss 0.5 $AIR_Y 0.5 {NoAI:1b,Rotation:[0f,0f],PersistenceRequired:1b}")
        t.waitTicks(20)

        val only = System.getenv("HHA_VOID_WHALE") ?: "day,poses,beached,p3,night,starlings,flight"

        if ("day" in only) {
            // ---- Ruhiges Gleiten bei Tag: Formen, Haut, Proportionen
            pose(t, WhalePose.SWIM, 1, 30)
            shot(t, "01_swim", Cam.SIDE, Cam.QUARTER, Cam.BELOW, Cam.REAR, Cam.TOP)
            // Markierungen der Server-Punkte Maul/Fluke (Leuchtblöcke) zur Kontrolle der Konstanten
            val mouth = dev.henny.hha_void.boss.VoidBossEntity.MOUTH_FORWARD
            val fluke = dev.henny.hha_void.boss.VoidBossEntity.FLUKE_BACK
            val heart = dev.henny.hha_void.boss.VoidBossEntity.HEART_FORWARD
            val mark = "{block_state:{Name:\"minecraft:lime_concrete\"},brightness:{sky:15,block:15},transformation:{left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],scale:[0.5f,0.5f,0.5f],translation:[-0.25f,-0.25f,-0.25f]},Tags:[\"mark\"]}"
            t.cmd("execute in $OW run summon minecraft:block_display 0.5 ${centerY - 0.8} ${0.5 + mouth} $mark")
            t.cmd("execute in $OW run summon minecraft:block_display 0.5 $centerY ${0.5 - fluke} $mark")
            t.cmd("execute in $OW run summon minecraft:block_display 4.5 ${centerY - 1.6} ${0.5 + heart} $mark")
            t.cmd("execute in $OW run summon minecraft:block_display 0.5 $centerY 0.5 $mark")
            t.cmd("tick unfreeze")
            t.waitTicks(5)
            t.cmd("tick freeze")
            shot(t, "02_marks", Cam.SIDE)
            t.cmd("execute in $OW run kill @e[tag=mark]")
            // Neigung: Sturzflug 40° nach unten, Aufstieg 30° – das Modell dreht sich um die Körpermitte
            t.cmd("tick unfreeze")
            t.cmd("execute in $OW run data merge entity $BOSS {Rotation:[0f,40f]}")
            pose(t, WhalePose.DIVE, 1, 20)
            shot(t, "03_dive_down", Cam.SIDE, Cam.QUARTER)
            t.cmd("tick unfreeze")
            t.cmd("execute in $OW run data merge entity $BOSS {Rotation:[0f,-30f]}")
            t.waitTicks(10)
            t.cmd("tick freeze")
            shot(t, "03_dive_up", Cam.SIDE)
            t.cmd("tick unfreeze")
            t.cmd("execute in $OW run data merge entity $BOSS {Rotation:[0f,0f]}")
            t.waitTicks(10)
        }

        if ("death" in only && "poses" !in only) {
            keys(t, WhalePose.DEATH, "12_death", intArrayOf(28, 60, 75, 110, 140, 200, 240), Cam.QUARTER, Cam.SIDE)
        }

        if ("poses" in only) {
            // ---- Alle Haltungen zu ihren Schlüssel-Ticks (docs/SPEC_LEVIATHAN.md, Abschnitt 3)
            keys(t, WhalePose.EMERGE, "04_emerge", intArrayOf(18, 62, 105), Cam.QUARTER, Cam.SIDE)
            keys(t, WhalePose.SONG, "05_song", intArrayOf(34), Cam.QUARTER, Cam.FRONT)
            keys(t, WhalePose.TAIL_SLAM, "06_slam", intArrayOf(22, 28, 36), Cam.SIDE)
            keys(t, WhalePose.BREATH, "07_breath", intArrayOf(18, 50), Cam.QUARTER, Cam.FRONT)
            keys(t, WhalePose.INHALE, "08_inhale", intArrayOf(40), Cam.QUARTER, Cam.BELOW)
            keys(t, WhalePose.SWALLOW, "09_swallow", intArrayOf(15, 21, 32), Cam.QUARTER, Cam.SIDE)
            keys(t, WhalePose.DEBRIS, "10_debris", intArrayOf(38), Cam.QUARTER, Cam.REAR)
            keys(t, WhalePose.STAGGER, "11_stagger", intArrayOf(5), Cam.QUARTER)
            keys(t, WhalePose.DEATH, "12_death", intArrayOf(28, 75, 140, 230), Cam.QUARTER, Cam.SIDE)
            keys(t, WhalePose.HIDDEN, "13_hidden", intArrayOf(5), Cam.SIDE)
        }

        if ("beached" in only) {
            // ---- Gestrandet auf der Plattform, Herz offen; ein Rüstungsständer als Größenvergleich
            t.cmd("tick unfreeze")
            boss(t, "voidboss pose ${WhalePose.SWIM} 1")
            t.cmd("execute in $OW run tp $BOSS 0.5 ${FLOOR_Y + dev.henny.hha_void.boss.LeviathanBody.BEACH_LIFT} 0.5")
            t.cmd("execute in $OW run summon minecraft:armor_stand -16.5 $FLOOR_Y 3.5 {Rotation:[-90f,0f],Tags:[\"mark\"]}")
            centerY = FLOOR_Y + dev.henny.hha_void.boss.LeviathanBody.BEACHED_CENTER
            t.waitTicks(20)
            pose(t, WhalePose.BEACHED, 1, 50)
            shot(t, "14_beached_050", Cam.RIGHT, Cam.QUARTER, Cam.SIDE, Cam.TOP)
            t.look(OW, -26.5, FLOOR_Y + 3.0, 14.5, -9.5, FLOOR_Y + 2.0, 3.5, "whale_14_beached_heart", wait = 6)
            hitboxes(t, true)
            shot(t, "14_beached_050_boxes", Cam.RIGHT, Cam.SIDE)
            hitboxes(t, false)
            keys(t, WhalePose.BEACHED, "14_beached", intArrayOf(4, 110, 130), Cam.RIGHT)
            pose(t, WhalePose.BEACHED, 3, 50); shot(t, "14_beached_p3", Cam.RIGHT)
            // Taumeln nach dem Ausbruch: liegt wie gestrandet, die Flanke klafft
            keys(t, WhalePose.REELING, "32_reeling", intArrayOf(30, 70, WhalePose.duration(WhalePose.REELING) - 12), Cam.RIGHT, Cam.QUARTER, phase = 3)
            hitboxes(t, true)
            shot(t, "32_reeling_boxes", Cam.RIGHT)
            hitboxes(t, false)
            t.cmd("tick unfreeze")
            t.cmd("execute in $OW run kill @e[tag=mark]")
            boss(t, "voidboss pose ${WhalePose.SWIM} 1")
            t.cmd("execute in $OW run tp $BOSS 0.5 $AIR_Y 0.5")
            centerY = AIR_Y + BODY_UP
            t.waitTicks(20)
        }

        if ("leviathan" in only || "poses" in only) {
            // ---- Leviathan-Haltungen zu ihren Schlüssel-Ticks
            keys(t, WhalePose.ROAR, "21_roar", intArrayOf(6, 24), Cam.QUARTER, Cam.FRONT)
            keys(t, WhalePose.BARREL_ROLL, "22_roll", intArrayOf(14, 35), Cam.QUARTER)
            keys(t, WhalePose.RAM, "23_ram", intArrayOf(20), Cam.FRONT, Cam.SIDE)
            // Durchbruch: der Server stellt ihn senkrecht
            t.cmd("execute in $OW run data merge entity $BOSS {Rotation:[0f,-80f]}")
            keys(t, WhalePose.BREACH, "24_breach", intArrayOf(10, 40, 62), Cam.SIDE, Cam.QUARTER)
            t.cmd("tick unfreeze")
            t.cmd("execute in $OW run data merge entity $BOSS {Rotation:[0f,0f]}")
            keys(t, WhalePose.BLOWHOLE, "25_blowhole", intArrayOf(12, 23), Cam.QUARTER, Cam.SIDE)
            keys(t, WhalePose.DEVOUR, "26_devour", intArrayOf(20, 60, 103, 114), Cam.FRONT, Cam.QUARTER)
            keys(t, WhalePose.BACK_QUAKE, "27_quake", intArrayOf(20, 27), Cam.SIDE)
            keys(t, WhalePose.TAIL_SWEEP, "28_sweep", intArrayOf(24, 34, 44), Cam.SIDE)
            keys(t, WhalePose.GORGED, "29_gorged", intArrayOf(30), Cam.SIDE, Cam.BELOW)
            keys(t, WhalePose.BREAKOUT, "30_breakout", intArrayOf(16, 24, 45), Cam.RIGHT, phase = 3)
            keys(t, WhalePose.FLOOD, "31_flood", intArrayOf(26, 34, 50), Cam.QUARTER, Cam.TOP)
            pose(t, WhalePose.SWIM, 1, 30)
            hitboxes(t, true)
            shot(t, "33_swim_boxes", Cam.SIDE, Cam.TOP)
            hitboxes(t, false)
        }

        if ("p3" in only) {
            // ---- Phase 3: Haut aufgerissen
            pose(t, WhalePose.SWIM, 3, 30); shot(t, "15_p3_swim", Cam.SIDE, Cam.QUARTER, Cam.BELOW)
            pose(t, WhalePose.SUPERNOVA, 3, 95); shot(t, "16_supernova_095", Cam.SIDE, Cam.QUARTER)
            pose(t, WhalePose.SUPERNOVA, 3, 128); shot(t, "16_supernova_128", Cam.QUARTER, Cam.SIDE)
        }

        if ("night" in only) {
            // ---- Nacht: Leuchtschicht, Sterne, Herz
            t.cmd("tick unfreeze")
            t.cmd("time set 18000")
            t.waitTicks(10)
            pose(t, WhalePose.SWIM, 1, 30); shot(t, "17_night_swim", Cam.SIDE, Cam.QUARTER, Cam.BELOW, Cam.FAR)
            pose(t, WhalePose.SONG, 1, 34); shot(t, "18_night_song", Cam.QUARTER)
            pose(t, WhalePose.BREATH, 2, 50); shot(t, "18_night_breath", Cam.FRONT)
            pose(t, WhalePose.SWIM, 3, 30); shot(t, "19_night_p3", Cam.SIDE, Cam.QUARTER)
            pose(t, WhalePose.SUPERNOVA, 3, 128); shot(t, "19_night_supernova", Cam.QUARTER)
            pose(t, WhalePose.DEATH, 3, 190); shot(t, "19_night_death", Cam.QUARTER)
        }

        if ("starlings" in only) {
            // ---- Sternlinge: drei flattern, einer im Sturzangriff
            t.cmd("tick unfreeze")
            boss(t, "voidboss pose ${WhalePose.SWIM} 1")
            t.cmd("time set 6000")
            val sy = AIR_Y + 20
            t.cmd("execute in $OW run summon hha_void:void_debtor 80.5 $sy 30.5 {NoAI:1b,Rotation:[0f,0f]}")
            t.cmd("execute in $OW run summon hha_void:void_debtor 82.5 ${sy + 0.6} 31.5 {NoAI:1b,Rotation:[40f,0f]}")
            t.cmd("execute in $OW run summon hha_void:void_debtor 78.5 ${sy + 0.9} 31.0 {NoAI:1b,Rotation:[-60f,0f]}")
            t.cmd("execute in $OW run tp @p 83.5 ${sy + 2.6} 35.5")
            t.waitTicks(20)
            t.world.server.runOnServer<RuntimeException> { server ->
                val all = server.overworld.getEntitiesByType(VoidBoss.DEBTOR) { true }
                all.minByOrNull { it.x }?.isCharging = true
            }
            t.waitTicks(27)
            t.cmd("tick freeze")
            t.look(OW, 83.5, sy + 2.6, 35.5, 80.5, sy + 0.5, 30.8, "whale_20_starlings_day", wait = 6)
            t.look(OW, 80.5, sy - 1.5, 33.5, 80.5, sy + 0.5, 30.8, "whale_20_starlings_below", wait = 6)
            t.cmd("tick unfreeze")
            t.cmd("time set 18000")
            t.waitTicks(13)
            t.cmd("tick freeze")
            t.look(OW, 83.5, sy + 2.6, 35.5, 80.5, sy + 0.5, 30.8, "whale_20_starlings_night", wait = 6)
        }
        t.cmd("tick unfreeze")
        if ("flight" in only) flight(t)
        println("[gametest] WhaleShowcase fertig")
    }

    // ---------------------------------------------------------------------------------------------
    // Flug: Steigen, Sinken, Kurven – Bildfolgen in kurzen Abständen, um die Bewegung zu beurteilen

    /**
     * Fliegt wie `VoidBossEntity.fly()` + `faceVelocity()` (dieselben Formeln), aber auf einer festen Strecke.
     * Der Wal selbst steht auf NoAI; die Position wird ihm jeden Tick gesetzt und läuft wie im Kampf über das
     * Netzwerk zum Client.
     */
    private class Flyer(var pos: Vec3d, var yaw: Float) {
        var velocity: Vec3d = Vec3d.ZERO
        var pitch = 0f

        fun step(goal: Vec3d, speed: Double, agility: Double) {
            val to = goal.subtract(pos)
            val length = to.length()
            val want = if (length > 0.3) to.multiply(minOf(speed, length * 0.25 + 0.04) / length) else Vec3d.ZERO
            velocity = velocity.lerp(want, agility)
            pos = pos.add(velocity)
            val v = velocity
            if (v.horizontalLengthSquared() > 1.0e-3) {
                val wantYaw = Math.toDegrees(atan2(-v.x, v.z)).toFloat()
                val diff = abs(MathHelper.wrapDegrees(wantYaw - yaw))
                yaw = MathHelper.stepUnwrappedAngleTowards(yaw, wantYaw, (diff * 0.1f + 0.4f).coerceAtMost(4f))
            }
            val horizontal = sqrt(v.horizontalLengthSquared())
            val wantPitch = if (v.lengthSquared() > 4.0e-3) (-Math.toDegrees(atan2(v.y, horizontal))).toFloat().coerceIn(-28f, 40f) else 0f
            pitch = MathHelper.stepTowards(pitch, wantPitch, (abs(wantPitch - pitch) * 0.08f + 0.2f).coerceAtMost(2.2f))
        }
    }

    private fun whale(server: net.minecraft.server.MinecraftServer) = server.overworld.getEntitiesByType(VoidBoss.BOSS) { true }.first()

    /** Stellt die Kamera [dx],[dy],[dz] neben die Körpermitte des Wals; der Blick bleibt waagerecht ausgerichtet. */
    private fun follow(t: TestKit, dx: Double, dy: Double, dz: Double) {
        val p = t.world.server.computeOnServer<Vec3d, RuntimeException> { whale(it).entityPos }
        val cx = p.x + dx
        val cy = p.y + 3.0 + dy
        val cz = p.z + dz
        val yaw = Math.toDegrees(atan2(dx, -dz)).toFloat()
        val pitch = Math.toDegrees(atan2(dy, sqrt(dx * dx + dz * dz))).toFloat()
        t.cmd("execute in $OW run tp @p $cx $cy $cz $yaw $pitch")
    }

    private class Leg(val name: String, val goal: Vec3d, val speed: Double, val agility: Double, val ticks: Int, val top: Boolean)

    /** Läuft im Server-Tick (genau ein Schritt je Tick, wie die KI); null = nichts zu tun. */
    @Volatile
    private var course: ((net.minecraft.server.MinecraftServer) -> Unit)? = null

    @Volatile
    private var courseLeg: Leg? = null

    @Volatile
    private var courseTick = 0
    private var hooked = false

    private fun flight(t: TestKit) {
        t.cmd("time set 6000")
        boss(t, "voidboss pose ${WhalePose.SWIM} 1")
        t.waitTicks(20)
        if (!hooked) {
            hooked = true
            net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register { server -> course?.invoke(server) }
        }

        // ---- feste Strecke: geradeaus, steil hoch, oben weiter, wieder runter, dann eine S-Kurve mit Steigung
        val start = Vec3d(0.5, AIR_Y, 0.5)
        val flyer = Flyer(start, 0f)
        val legs = listOf(
            Leg("a_level", start.add(0.0, 0.0, 70.0), 0.8, 0.06, 40, false),
            Leg("b_climb", start.add(0.0, 30.0, 120.0), 1.0, 0.08, 70, false),
            Leg("c_level", start.add(0.0, 30.0, 220.0), 0.8, 0.06, 45, false),
            Leg("d_sink", start.add(0.0, 4.0, 250.0), 0.8, 0.06, 75, false),
            Leg("e_right", start.add(-70.0, 4.0, 270.0), 0.75, 0.06, 70, true),
            Leg("f_left", start.add(-30.0, 14.0, 360.0), 0.75, 0.06, 80, true),
            Leg("g_back", start.add(-110.0, 22.0, 290.0), 0.75, 0.06, 80, true),
            Leg("h_stop", start.add(-110.0, 22.0, 290.0), 0.0, 0.15, 40, true),
        )
        val total = legs.sumOf { it.ticks }
        courseTick = 0
        courseLeg = legs.first()
        course = { server ->
            var n = courseTick
            val leg = legs.firstOrNull { l -> (n < l.ticks).also { if (!it) n -= l.ticks } }
            courseLeg = leg
            if (leg != null) {
                flyer.step(leg.goal, leg.speed, leg.agility)
                val w = whale(server)
                w.refreshPositionAndAngles(flyer.pos.x, flyer.pos.y, flyer.pos.z, flyer.yaw, flyer.pitch)
                w.bodyYaw = flyer.yaw
                w.headYaw = flyer.yaw
                if (courseTick % 5 == 0) {
                    println("[flight] tick $courseTick ${leg.name} pos=(%.1f, %.1f, %.1f) yaw=%.1f pitch=%.1f v=%.2f".format(
                        flyer.pos.x, flyer.pos.y, flyer.pos.z, flyer.yaw, flyer.pitch, flyer.velocity.length()))
                }
                courseTick++
            }
        }
        var shot = 0
        while (courseTick < total) {
            val leg = courseLeg ?: break
            // Kamera nachführen, kurz warten, Foto
            if (leg.top) follow(t, 0.5, 44.0, -1.0) else follow(t, 34.0, 1.0, 0.0)
            t.waitTicks(4)
            t.shot("whale_30_flight_%02d_%s_t%03d".format(shot++, leg.name, courseTick))
        }
        course = null

        // ---- echte Kreisbahn: ein zweiter Wal mit KI, ohne Gegner zieht er seine Runden (cruise)
        t.world.server.runOnServer<RuntimeException> { server -> whale(server).discard() }
        t.waitTicks(5)
        t.cmd("execute in $OW run tp @p 300.5 ${AIR_Y + 30} 0.5")
        t.waitTicks(40)
        t.cmd("execute in $OW run summon hha_void:void_boss 300.5 $AIR_Y 0.5 {Rotation:[0f,0f],PersistenceRequired:1b}")
        t.waitTicks(10)
        // vom Start in der Mitte steigt er auf die Bahn: von der Seite
        for (i in 0 until 16) {
            follow(t, 34.0, 1.0, 0.0)
            t.waitTicks(4)
            t.shot("whale_31_cruise_rise_%02d".format(i))
        }
        // dann die Runde: von oben und schräg von hinten
        for (i in 0 until 16) {
            follow(t, 0.5, 44.0, -1.0)
            t.waitTicks(4)
            t.shot("whale_32_cruise_top_%02d".format(i))
        }
        for (i in 0 until 12) {
            follow(t, 20.0, 8.0, -26.0)
            t.waitTicks(4)
            t.shot("whale_33_cruise_side_%02d".format(i))
        }
        t.world.server.runOnServer<RuntimeException> { server -> whale(server).discard() }
    }
}
