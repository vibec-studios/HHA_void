package dev.henny.hha_void.boss

import dev.henny.hha_void.VoidSounds
import dev.henny.hha_void.logic.TickScheduler
import dev.henny.hha_void.logic.VoidVisuals
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.block.Block
import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.entity.SpawnReason
import net.minecraft.entity.effect.StatusEffectInstance
import net.minecraft.entity.effect.StatusEffects
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket
import net.minecraft.network.packet.s2c.play.TitleS2CPacket
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvents
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.Identifier
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Box
import net.minecraft.util.math.Vec3d
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * „Im Bauch des Leviathans" – ein zusammenhängender Weg durch seinen ganzen inneren Körper, unter der Arena gebaut
 * (gleiche Gegend, damit der Wal oben weiter tickt) und danach wieder entfernt; was vorher dort stand, kommt zurück.
 *
 * Abschnitte (Membran-Tore dazwischen öffnen sich erst, wenn die Aufgabe des Abschnitts erfüllt ist):
 *  0. **Schlund** – zwei Würgknoten an den Wänden; Schluckreflexe werfen zurück.
 *  1. **Magen** – drei Magenkerne auf Fleischhügeln; Säure steigt, Fresszellen (Sternlinge).
 *  2. **Gedärme** – enger, gewundener Gang mit Säuretümpeln, Zotten und Peristaltik-Wellen; zwei Ganglien in Seitentaschen.
 *  3. **Lunge** – hohe Kammer mit Wendelrampe (Lücken), Atemzüge saugen und tragen; drei Lungenbläschen oben.
 *  4. **Herzkammer** – vier Herzklappen schützen das Sternenherz; jeder Herzschlag ist eine Schockwelle.
 * Fortschritt nur gemeinsam: Wer zurückbleibt, wird 15 s nach dem Öffnen eines Tores mitgerissen (niemand steckt fest).
 */
class LeviathanBelly(val world: ServerWorld, val center: BlockPos) {

    private val snapshot = HashMap<Long, BlockState>()
    private var buildIndex = 0
    private var removeIndex = 0
    var acidLevel = ACID_START
        private set

    val built get() = buildIndex >= Design.cells.size
    val removed get() = removeIndex >= Design.cells.size

    /** Freigeschalteter Abschnitt (0 … 4); 5 = Herz zerstört. */
    var section = 0
        private set
    private var sectionTicks = 0
    private var pullAt = -1
    private val objectives = ArrayList<LeviathanCoreEntity>()
    private var objectivesTotal = 0
    private val opened = BooleanArray(Design.GATES.size)
    private var players = 1

    // ------------------------------------------------------------------------------------------------ Bauen

    fun buildStep(budget: Int): Boolean {
        val pos = BlockPos.Mutable()
        var n = 0
        while (buildIndex < Design.cells.size && n < budget) {
            val cell = Design.cells[buildIndex++]
            pos.set(center.x + cell.x, center.y + cell.y, center.z + cell.z)
            if (world.isOutOfHeightLimit(pos)) continue
            val old = world.getBlockState(pos)
            if (!old.isAir && !old.isOf(cell.state.block)) snapshot[pos.asLong()] = old
            if (old != cell.state) world.setBlockState(pos, cell.state, Block.NOTIFY_LISTENERS or Block.FORCE_STATE or Block.SKIP_DROPS)
            n++
        }
        return built
    }

    fun removeStep(budget: Int): Boolean {
        val pos = BlockPos.Mutable()
        var n = 0
        while (removeIndex < Design.cells.size && n < budget) {
            val cell = Design.cells[removeIndex++]
            pos.set(center.x + cell.x, center.y + cell.y, center.z + cell.z)
            if (world.isOutOfHeightLimit(pos)) continue
            val back = snapshot[pos.asLong()] ?: Blocks.AIR.defaultState
            if (world.getBlockState(pos) != back) world.setBlockState(pos, back, Block.NOTIFY_LISTENERS or Block.FORCE_STATE or Block.SKIP_DROPS)
            n++
        }
        if (removeIndex >= Design.cells.size) {
            for (y in ACID_START..ACID_MAX) for ((x, z) in Design.acidColumns) {
                pos.set(center.x + x, center.y + y, center.z + z)
                if (world.getBlockState(pos).isOf(LeviathanBlocks.ACID)) world.setBlockState(pos, Blocks.AIR.defaultState, Block.NOTIFY_LISTENERS)
            }
        }
        return removed
    }

    /** Die Säure im Magen steigt um eine Lage (bis [ACID_MAX]). */
    fun raiseAcid(): Boolean {
        if (acidLevel >= ACID_MAX) return false
        acidLevel++
        val pos = BlockPos.Mutable()
        val acid = LeviathanBlocks.ACID.defaultState
        for ((x, z) in Design.acidColumns) {
            for (y in ACID_START..acidLevel) {
                pos.set(center.x + x, center.y + y, center.z + z)
                if (world.getBlockState(pos).isAir) world.setBlockState(pos, acid, Block.NOTIFY_LISTENERS)
            }
        }
        return true
    }

    /** Ein Tor öffnet sich: die Membran reißt mit Splittern. */
    private fun openGate(i: Int) {
        if (i !in opened.indices || opened[i]) return
        opened[i] = true
        val pos = BlockPos.Mutable()
        var fx = 0
        for (cell in Design.GATES[i]) {
            pos.set(center.x + cell.x, center.y + cell.y, center.z + cell.z)
            val s = world.getBlockState(pos)
            if (!s.isOf(LeviathanBlocks.MEMBRANE)) continue
            if (fx++ % 3 == 0) world.syncWorldEvent(2001, pos, Block.getRawIdFromState(s))
            world.setBlockState(pos, Blocks.AIR.defaultState, Block.NOTIFY_LISTENERS)
        }
        val at = at(Design.GATE_CENTERS[i])
        world.playSound(null, at.x, at.y, at.z, SoundEvents.ENTITY_SLIME_SQUISH, SoundCategory.HOSTILE, 4f, 0.4f)
        world.playSound(null, at.x, at.y, at.z, VoidSounds.WHALE_GROAN, SoundCategory.HOSTILE, 4f, 0.5f)
        VoidVisuals.nova(world, at, 6f)
    }

    /** Alle Membranen reißen (Ausbruch). */
    fun burstMembranes() {
        for (i in opened.indices) openGate(i)
    }

    // ------------------------------------------------------------------------------------------------ Orte

    fun at(rel: Vec3d): Vec3d = Vec3d(center.x + 0.5 + rel.x, center.y + rel.y, center.z + 0.5 + rel.z)

    fun bounds(): Box = Box(
        center.x + Design.MIN_X - 2.0, center.y + Design.MIN_Y - 2.0, center.z + Design.MIN_Z - 2.0,
        center.x + Design.MAX_X + 3.0, center.y + Design.MAX_Y + 3.0, center.z + Design.MAX_Z + 3.0,
    )

    fun contains(p: Vec3d) = bounds().contains(p)

    /** Kamera-Anker der Bauch-Szene: Mitte des Schlunds (vorne = +x, dort geht es weiter). */
    fun anchor(): Vec3d = at(Design.THROAT)

    fun spawnPoint(i: Int): Vec3d = at(Design.SPAWNS[i % Design.SPAWNS.size])

    /** Eingang des Abschnitts [s] (für Nachzügler). */
    fun entry(s: Int): Vec3d = at(Design.ENTRIES[s.coerceIn(0, Design.ENTRIES.size - 1)])

    /** In welchem Abschnitt liegt [p]? (-1 = nirgends) */
    fun sectionOf(p: Vec3d): Int {
        val x = p.x - center.x - 0.5; val y = p.y - center.y; val z = p.z - center.z - 0.5
        var best = -1
        var bestD = Double.MAX_VALUE
        for (r in Design.REGIONS) {
            val d = r.norm(x, y, z)
            if (d < 1.25 && d < bestD) { bestD = d; best = r.section }
        }
        return best
    }

    /** Mitte der Abschnitts-Region (für den Wal, der oben darüber schwebt). */
    fun focus(): Vec3d = at(Design.ENTRIES[min(section, Design.ENTRIES.size - 1)])

    fun objectivesAlive(): Int = objectives.count { it.isAlive }
    fun objectivesTotal(): Int = objectivesTotal

    // ------------------------------------------------------------------------------------------------ Ablauf

    /** Beginn: Abschnitt 0 (Schlund) mit seinen Würgknoten. */
    fun start(boss: VoidBossEntity, players: Int) {
        this.players = players.coerceIn(1, 10)
        section = 0
        sectionTicks = 0
        spawnObjectives(boss, 0)
    }

    private fun hp(role: Int): Double {
        val (base, per) = when (role) {
            LeviathanCoreEntity.KNOT -> 50.0 to 25.0
            LeviathanCoreEntity.CORE -> 80.0 to 40.0
            LeviathanCoreEntity.GANGLION -> 60.0 to 30.0
            LeviathanCoreEntity.ALVEOLUS -> 45.0 to 22.0
            LeviathanCoreEntity.VALVE -> 45.0 to 22.0
            else -> 260.0 to 120.0
        }
        return base + per * (players - 1)
    }

    private fun spawnObjectives(boss: VoidBossEntity, s: Int) {
        objectives.removeIf { !it.isAlive || it.isRemoved }
        for ((role, spot) in Design.OBJECTIVES[s]) {
            val core = VoidBoss.CORE.create(world, SpawnReason.MOB_SUMMONED) ?: continue
            core.owner = boss
            core.role = role
            val p = at(spot)
            core.refreshPositionAndAngles(p.x, p.y, p.z, 0f, 0f)
            val hp = hp(role)
            core.getAttributeInstance(net.minecraft.entity.attribute.EntityAttributes.MAX_HEALTH)?.baseValue = hp
            core.health = hp.toFloat()
            if (role == LeviathanCoreEntity.HEART) core.shielded = true
            world.spawnEntity(core)
            objectives += core
            VoidVisuals.burst(world, p.add(0.0, 1.5, 0.0), 2)
        }
        objectivesTotal = objectives.count { it.isAlive }
    }

    /** Debug/Test (`/voidboss phase 4`): alle Abschnitte überspringen bis zur Herzkammer. */
    fun skipToHeart(boss: VoidBossEntity) {
        for (o in objectives) if (o.isAlive) o.discard()
        objectives.clear()
        for (i in 0 until 4) openGate(i)
        section = 4
        sectionTicks = 0
        spawnObjectives(boss, 4)
    }

    /** Test: zerstört alle Ziele des aktuellen Abschnitts. */
    fun killSection(world: ServerWorld) {
        for (o in objectives.toList()) if (o.isAlive) {
            o.shielded = false
            o.kill(world)
        }
    }

    /** Ein Organ ist gestorben; gibt zurück, welcher Anteil des Wal-Lebens dabei verloren geht. */
    fun objectiveDied(boss: VoidBossEntity, core: LeviathanCoreEntity, inside: List<ServerPlayerEntity>): Float {
        val share = when (core.role) {
            LeviathanCoreEntity.KNOT -> 0.01f
            LeviathanCoreEntity.CORE -> 0.025f
            LeviathanCoreEntity.GANGLION -> 0.015f
            LeviathanCoreEntity.ALVEOLUS -> 0.012f
            LeviathanCoreEntity.VALVE -> 0.01f
            else -> 0.05f
        }
        // die letzte Klappe: das Herz liegt frei
        if (core.role == LeviathanCoreEntity.VALVE && objectives.none { it !== core && it.isAlive && it.role == LeviathanCoreEntity.VALVE }) {
            for (h in objectives) if (h.isAlive && h.role == LeviathanCoreEntity.HEART) h.shielded = false
            tell(inside, "heart_open", Formatting.GOLD)
            world.playSound(null, core.x, core.y, core.z, SoundEvents.ENTITY_WARDEN_HEARTBEAT, SoundCategory.HOSTILE, 4f, 1.4f)
        }
        return share
    }

    /**
     * Ein Tick der Bauch-Phase für die Spieler [inside]. Rückgabe: true, sobald das Sternenherz zerstört ist.
     */
    fun tick(boss: VoidBossEntity, inside: List<ServerPlayerEntity>, t: Int): Boolean {
        sectionTicks++
        objectives.removeIf { it.isRemoved && !it.isAlive && it.deathTime > 20 }
        // Abschnitt erledigt → Tor auf, nächster Abschnitt
        if (sectionTicks > 20 && objectives.none { it.isAlive }) {
            if (section >= 4) {
                section = 5
                return true
            }
            openGate(section)
            section++
            sectionTicks = 0
            pullAt = 300
            spawnObjectives(boss, section)
            title(inside, "belly_s$section")
        }
        if (pullAt > 0 && --pullAt == 0) {
            // Nachzügler reißt der Sog mit
            for (p in inside) {
                val s = sectionOf(p.entityPos)
                if (s in 0 until section) {
                    val e = entry(section)
                    p.teleport(world, e.x, e.y + 0.1, e.z, emptySet(), p.yaw, 0f, false)
                    p.fallDistance = 0.0
                    p.sendMessage(Text.translatable("hha_void.boss.msg.belly_pulled").formatted(Formatting.LIGHT_PURPLE), true)
                    VoidVisuals.screen(p, 4, 60)
                }
            }
        }
        for (p in inside) {
            // aus dem Gang gefallen (oder irgendwo festgesteckt): an den Eingang des Abschnitts
            if (sectionOf(p.entityPos) < 0 || p.y < center.y + Design.MIN_Y + 1) {
                val e = entry(section)
                p.teleport(world, e.x, e.y + 0.1, e.z, emptySet(), p.yaw, 0f, false)
                p.fallDistance = 0.0
            }
        }
        hazards(boss, inside, t)
        if (t % 40 == 20) {
            for (p in inside) {
                val alive = objectivesAlive()
                p.sendMessage(
                    Text.translatable("hha_void.boss.msg.belly_goal", Text.translatable("hha_void.boss.belly.s${min(section, 4)}"), objectivesTotal - alive, objectivesTotal)
                        .formatted(Formatting.RED, Formatting.BOLD),
                    true,
                )
            }
        }
        if (t % 50 == 0) world.playSound(null, center.x + 0.0, center.y + 0.0, center.z + 0.0, SoundEvents.ENTITY_WARDEN_HEARTBEAT, SoundCategory.HOSTILE, 3f, 0.5f)
        return false
    }

    private fun hazards(boss: VoidBossEntity, inside: List<ServerPlayerEntity>, t: Int) {
        val st = sectionTicks
        val bySection = inside.groupBy { sectionOf(it.entityPos) }
        // 0 Schlund: Schluckreflex wirft zurück Richtung Maul
        bySection[0]?.let { list ->
            if (st % 160 == 120) for (p in list) p.sendMessage(Text.translatable("hha_void.boss.msg.belly_gag").formatted(Formatting.GOLD), true)
            if (st % 160 == 0 && st > 0) {
                world.playSound(null, list[0].x, list[0].y, list[0].z, VoidSounds.SWALLOW, SoundCategory.HOSTILE, 3f, 0.5f)
                for (p in list) {
                    boss.bellyHurt(p, 2f)
                    boss.bellyLaunch(p, Vec3d(-1.0, 0.35, 0.0))
                    VoidVisuals.screen(p, 2, 14)
                }
            }
        }
        // 1 Magen: Säure steigt, Fresszellen
        bySection[1]?.let { list ->
            if (st > 0 && st % 500 == 0 && raiseAcid()) {
                for (p in list) p.sendMessage(Text.translatable("hha_void.boss.msg.acid").formatted(Formatting.GOLD, Formatting.BOLD), true)
            }
            if (st % 160 == 80) boss.bellyStarlings(at(Design.STOMACH).add(0.0, -6.0, 0.0), 1 + list.size / 2, 2 + list.size)
        }
        // 2 Gedärme: Peristaltik – zwei Stellen ziehen sich zusammen
        bySection[2]?.let { list ->
            if (st % 90 == 0) {
                val rnd = java.util.Random((t * 31L) xor center.asLong())
                val spots = ArrayList<Vec3d>()
                for (p in list) spots += p.entityPos
                spots += at(Design.pathPoint(rnd.nextDouble()))
                for (s in spots) {
                    val g = Vec3d(s.x, center.y + Design.GUT_WALK.toDouble(), s.z)
                    VoidVisuals.zone(world, g, 4.0, 26, 2)
                    TickScheduler.schedule(26) {
                        if (boss.isRemoved) return@schedule
                        world.playSound(null, g.x, g.y, g.z, SoundEvents.ENTITY_SLIME_SQUISH, SoundCategory.HOSTILE, 3f, 0.5f)
                        VoidVisuals.impact(world, g.add(0.0, 1.0, 0.0), 2f)
                        for (p in list) if (p.isAlive && p.squaredDistanceTo(g.x, p.y, g.z) < 16.0 && abs(p.y - g.y) < 3.0) {
                            boss.bellyHurt(p, 7f)
                            boss.bellyLaunch(p, Vec3d((p.x - g.x) * 0.15, 0.6, (p.z - g.z) * 0.15))
                        }
                    }
                }
            }
            if (st % 240 == 120) boss.bellyStarlings(list[0].entityPos.add(0.0, 2.0, 0.0), 1, 2 + list.size / 2)
        }
        // 3 Lunge: Einatmen saugt nach unten und innen, Ausatmen trägt nach oben
        bySection[3]?.let { list ->
            val c = st % 200
            val axis = at(Design.LUNG)
            if (c == 0) for (p in list) p.sendMessage(Text.translatable("hha_void.boss.msg.belly_inhale").formatted(Formatting.AQUA), true)
            if (c == 110) for (p in list) p.sendMessage(Text.translatable("hha_void.boss.msg.belly_exhale").formatted(Formatting.AQUA), true)
            for (p in list) {
                val toAxis = Vec3d(axis.x - p.x, 0.0, axis.z - p.z)
                if (c in 10..70 && !p.isOnGround) {
                    boss.bellyPush(p, toAxis.normalize().multiply(0.03).add(0.0, -0.04, 0.0))
                } else if (c in 110..150) {
                    boss.bellyPush(p, toAxis.normalize().multiply(-0.015).add(0.0, if (p.velocity.y < 0.5) 0.12 else 0.0, 0.0))
                    p.fallDistance = 0.0
                }
            }
            if (c == 110) VoidVisuals.pillar(world, axis.add(0.0, Design.LUNG_FLOOR - Design.LUNG.y, 0.0), 1, 30f, light = true)
        }
        // 4 Herzkammer: Herzschlag = Schockwelle über den Boden
        bySection[4]?.let { list ->
            if (st % 50 == 0) {
                val heart = objectives.firstOrNull { it.isAlive && it.role == LeviathanCoreEntity.HEART }
                val c = at(Design.HEART_ROOM).let { Vec3d(it.x, center.y + Design.HEART_WALK.toDouble(), it.z) }
                world.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_WARDEN_HEARTBEAT, SoundCategory.HOSTILE, 5f, 0.6f)
                VoidVisuals.shockwave(world, c.add(0.0, 0.5, 0.0), c, 22.0)
                VoidVisuals.stomp(world, c, 0.6f)
                val hits = HashSet<UUID>()
                for (k in 0..22) TickScheduler.schedule(k) {
                    if (boss.isRemoved) return@schedule
                    val radius = k * 1.0
                    for (p in list) {
                        if (!p.isAlive || p.uuid in hits) continue
                        val d = sqrt(p.squaredDistanceTo(c.x, p.y, c.z))
                        if (abs(d - radius) < 1.3 && p.y - c.y < 1.0) {
                            hits += p.uuid
                            boss.bellyHurt(p, 8f)
                            val out = Vec3d(p.x - c.x, 0.0, p.z - c.z).normalize()
                            boss.bellyLaunch(p, Vec3d(out.x * 0.8, 0.6, out.z * 0.8))
                        }
                    }
                }
                if (heart != null && st % 150 == 0) boss.bellyStarlings(heart.entityPos.add(0.0, 6.0, 0.0), 1 + list.size / 3, 3 + list.size)
            }
        }
    }

    private fun tell(list: List<ServerPlayerEntity>, key: String, color: Formatting) {
        for (p in list) p.sendMessage(Text.translatable("hha_void.boss.msg.$key").formatted(color, Formatting.BOLD), false)
    }

    private fun title(list: List<ServerPlayerEntity>, key: String) {
        for (p in list) {
            p.networkHandler.sendPacket(SubtitleS2CPacket(Text.translatable("hha_void.boss.title.$key.sub").formatted(Formatting.LIGHT_PURPLE)))
            p.networkHandler.sendPacket(TitleS2CPacket(Text.translatable("hha_void.boss.title.$key").formatted(Formatting.RED, Formatting.BOLD)))
            VoidVisuals.screen(p, 1, 10)
        }
        world.playSound(null, center.x + 0.0, center.y + 0.0, center.z + 0.0, VoidSounds.WHALE_GROAN, SoundCategory.HOSTILE, 4f, 0.45f)
    }

    fun discardObjectives() {
        for (o in objectives) if (!o.isRemoved) o.discard()
        objectives.clear()
    }

    // ------------------------------------------------------------------------------------------------ Entwurf

    private class Cell(val x: Int, val y: Int, val z: Int, val state: BlockState)

    /** Raum eines Abschnitts: Ellipsoid (a, c, b = halbe x/y/z) oder Kapsel (Strecke p→q, Radius r). */
    private class Region(
        val section: Int,
        val cx: Double, val cy: Double, val cz: Double,
        val a: Double, val c: Double, val b: Double,
        val qx: Double = Double.NaN, val qy: Double = 0.0, val qz: Double = 0.0, val r: Double = 0.0,
        /** begehbarer Boden (darunter Fleisch). */
        val floorA: Double, val floorB: Double = floorA,
    ) {
        val capsule get() = !qx.isNaN()
        val minX get() = if (capsule) min(cx, qx) - r else cx - a
        val maxX get() = if (capsule) max(cx, qx) + r else cx + a
        val minY get() = if (capsule) min(cy, qy) - r else cy - c
        val maxY get() = if (capsule) max(cy, qy) + r else cy + c
        val minZ get() = if (capsule) min(cz, qz) - r else cz - b
        val maxZ get() = if (capsule) max(cz, qz) + r else cz + b

        /** Strecken-Parameter (0..1) des nächsten Punkts auf der Kapsel-Achse. */
        fun t(x: Double, y: Double, z: Double): Double {
            val dx = qx - cx; val dy = qy - cy; val dz = qz - cz
            val l2 = dx * dx + dy * dy + dz * dz
            return if (l2 < 1e-9) 0.0 else (((x - cx) * dx + (y - cy) * dy + (z - cz) * dz) / l2).coerceIn(0.0, 1.0)
        }

        /** 1 = Rand des Innenraums (normiert). */
        fun norm(x: Double, y: Double, z: Double, grow: Double = 0.0): Double {
            if (!capsule) {
                val ex = (x - cx) / (a + grow); val ey = (y - cy) / (c + grow); val ez = (z - cz) / (b + grow)
                return sqrt(ex * ex + ey * ey + ez * ez)
            }
            val k = t(x, y, z)
            val px = cx + (qx - cx) * k; val py = cy + (qy - cy) * k; val pz = cz + (qz - cz) * k
            val d = sqrt((x - px) * (x - px) + (y - py) * (y - py) + (z - pz) * (z - pz))
            return d / (r + grow)
        }

        fun floorAt(x: Double, y: Double, z: Double): Double = if (capsule) floorA + (floorB - floorA) * t(x, y, z) else floorA
    }

    private object Design {
        const val SHELL = 3.0

        // Mittelpunkte der großen Kammern (relativ zur Bauchmitte)
        val THROAT = Vec3d(-62.0, 0.0, -34.0)
        val STOMACH = Vec3d(2.0, -8.0, -34.0)
        val LUNG = Vec3d(84.0, 9.0, 26.0)
        val HEART_ROOM = Vec3d(92.0, -4.0, -16.0)

        /** Böden (oberster fester Block; man steht eine Lage darüber). */
        const val THROAT_FLOOR = -7
        const val STOMACH_FLOOR = -15
        const val GUT_WALK = -14
        const val LUNG_FLOOR = -15.0
        const val HEART_WALK = -14

        /** Gedärme: gewundene Röhre (Mittellinie). */
        val GUT = listOf(
            Vec3d(42.0, -12.0, -34.0), Vec3d(54.0, -12.0, -34.0), Vec3d(54.0, -12.0, -14.0), Vec3d(30.0, -12.0, -14.0),
            Vec3d(30.0, -12.0, 6.0), Vec3d(54.0, -12.0, 6.0), Vec3d(54.0, -12.0, 26.0), Vec3d(68.0, -12.0, 26.0),
        )
        const val GUT_R = 4.5

        fun pathPoint(k: Double): Vec3d {
            var total = 0.0
            for (i in 0 until GUT.size - 1) total += GUT[i].distanceTo(GUT[i + 1])
            var left = k.coerceIn(0.0, 1.0) * total
            for (i in 0 until GUT.size - 1) {
                val l = GUT[i].distanceTo(GUT[i + 1])
                if (left <= l) return GUT[i].lerp(GUT[i + 1], left / l)
                left -= l
            }
            return GUT.last()
        }

        val POCKETS = listOf(Vec3d(60.0, -12.0, -24.0), Vec3d(24.0, -12.0, -4.0))

        val REGIONS: List<Region> = buildList {
            add(Region(0, THROAT.x, THROAT.y, THROAT.z, 30.0, 11.0, 14.0, floorA = THROAT_FLOOR.toDouble()))
            // Schlund → Magen (fällt ab)
            add(Region(0, -38.0, -2.0, -34.0, 0.0, 0.0, 0.0, -24.0, -9.0, -34.0, 6.0, floorA = THROAT_FLOOR.toDouble(), floorB = STOMACH_FLOOR.toDouble()))
            add(Region(1, STOMACH.x, STOMACH.y, STOMACH.z, 32.0, 14.0, 20.0, floorA = STOMACH_FLOOR.toDouble()))
            add(Region(1, 30.0, -10.0, -34.0, 0.0, 0.0, 0.0, 44.0, -12.0, -34.0, 5.0, floorA = STOMACH_FLOOR.toDouble(), floorB = GUT_WALK - 1.0))
            for (i in 0 until GUT.size - 1) {
                val p = GUT[i]; val q = GUT[i + 1]
                add(Region(2, p.x, p.y, p.z, 0.0, 0.0, 0.0, q.x, q.y, q.z, GUT_R, floorA = GUT_WALK - 1.0))
            }
            for (p in POCKETS) add(Region(2, p.x, p.y, p.z, 4.5, 4.5, 4.5, floorA = GUT_WALK - 1.0))
            add(Region(3, LUNG.x, LUNG.y, LUNG.z, 18.0, 24.0, 18.0, floorA = LUNG_FLOOR))
            // Lunge → Herzkammer
            add(Region(3, 84.0, -11.0, 12.0, 0.0, 0.0, 0.0, 90.0, -11.0, -1.0, 4.5, floorA = GUT_WALK - 1.0))
            add(Region(4, HEART_ROOM.x, HEART_ROOM.y, HEART_ROOM.z, 20.0, 14.0, 18.0, floorA = HEART_WALK - 1.0))
        }

        /** Tore (Membranen): Ebene durch [p] mit Normale [n], innerhalb der Region. */
        private val GATE_DEF = listOf(
            Vec3d(-31.0, -5.0, -34.0) to Vec3d(1.0, 0.0, 0.0),
            Vec3d(37.0, -11.0, -34.0) to Vec3d(1.0, 0.0, 0.0),
            Vec3d(64.0, -12.0, 26.0) to Vec3d(1.0, 0.0, 0.0),
            Vec3d(87.0, -11.0, 5.5) to Vec3d(0.42, 0.0, -0.91),
        )
        val GATE_CENTERS: List<Vec3d> = GATE_DEF.map { it.first }
        val GATES: List<ArrayList<Cell>> = List(GATE_DEF.size) { ArrayList() }

        val SPAWNS: List<Vec3d> = listOf(
            Vec3d(-78.0, -6.0, -36.0), Vec3d(-74.0, -6.0, -30.0), Vec3d(-70.0, -6.0, -38.0), Vec3d(-66.0, -6.0, -32.0),
            Vec3d(-80.0, -6.0, -32.0), Vec3d(-72.0, -6.0, -34.0), Vec3d(-76.0, -6.0, -40.0), Vec3d(-68.0, -6.0, -28.0),
            Vec3d(-82.0, -6.0, -36.0), Vec3d(-64.0, -6.0, -36.0),
        )

        val ENTRIES: List<Vec3d> = listOf(
            Vec3d(-72.0, -6.0, -34.0), Vec3d(-22.0, -13.0, -34.0), Vec3d(46.0, -14.0, -34.0),
            Vec3d(70.0, -14.0, 26.0), Vec3d(88.0, -14.0, -6.0),
        )

        /** Hügel im Magen: x, z (relativ zur Magenmitte), Radius, Oberkante. Die ersten drei tragen Kerne. */
        val MOUNDS = listOf(
            doubleArrayOf(-17.0, -9.0, 4.5, -11.0), doubleArrayOf(17.0, 8.0, 4.5, -11.0), doubleArrayOf(3.0, 13.0, 3.8, -10.0),
            doubleArrayOf(-6.0, 7.0, 3.2, -12.0), doubleArrayOf(8.0, -9.0, 3.2, -12.0), doubleArrayOf(-25.0, 3.0, 3.0, -12.0),
            doubleArrayOf(25.0, -3.0, 3.0, -12.0),
        )

        /** Ziele je Abschnitt: (Rolle, Fußpunkt). */
        val OBJECTIVES: List<List<Pair<Int, Vec3d>>> = listOf(
            listOf(LeviathanCoreEntity.KNOT to Vec3d(-52.0, -6.0, -43.0), LeviathanCoreEntity.KNOT to Vec3d(-46.0, -6.0, -27.0)),
            MOUNDS.take(3).map { LeviathanCoreEntity.CORE to Vec3d(STOMACH.x + it[0], it[3] + 1.0, STOMACH.z + it[1]) },
            POCKETS.map { LeviathanCoreEntity.GANGLION to Vec3d(it.x, GUT_WALK.toDouble(), it.z) },
            listOf(
                LeviathanCoreEntity.ALVEOLUS to lungPad(5.3, 10.0), LeviathanCoreEntity.ALVEOLUS to lungPad(6.3, 14.5),
                LeviathanCoreEntity.ALVEOLUS to lungPad(7.3, 19.0),
            ),
            listOf(
                LeviathanCoreEntity.VALVE to Vec3d(HEART_ROOM.x + 12.0, HEART_WALK.toDouble(), HEART_ROOM.z),
                LeviathanCoreEntity.VALVE to Vec3d(HEART_ROOM.x - 12.0, HEART_WALK.toDouble(), HEART_ROOM.z),
                LeviathanCoreEntity.VALVE to Vec3d(HEART_ROOM.x, HEART_WALK.toDouble(), HEART_ROOM.z + 10.0),
                LeviathanCoreEntity.VALVE to Vec3d(HEART_ROOM.x + 5.0, HEART_WALK.toDouble(), HEART_ROOM.z - 10.0),
                LeviathanCoreEntity.HEART to Vec3d(HEART_ROOM.x, HEART_WALK + 3.0, HEART_ROOM.z),
            ),
        )

        // Lunge: Wendelrampe an der Innenwand (Radius 12–15,5), steigt 1 Block je 3 Blöcke Weg
        private const val RAMP_R0 = 11.5
        private const val RAMP_R1 = 15.5
        private const val RAMP_START = 0.6
        private const val RAMP_RISE = 14.0 * 1.0 / 3.0 // Höhe je Radiant bei r = 14

        fun rampHeight(theta: Double): Double = LUNG_FLOOR + (theta - RAMP_START) * RAMP_RISE

        /** Bläschen hängen innen neben der Rampe beim Winkel [theta] (Höhe ≈ Rampe + [up] über dem Boden). */
        fun lungPad(theta: Double, up: Double): Vec3d {
            val h = rampHeight(theta) + 1.0
            return Vec3d(LUNG.x + cos(theta) * 9.5, h + 0.5 + up * 0.0, LUNG.z + sin(theta) * 9.5)
        }

        private fun rampAt(x: Double, y: Int, z: Double): Boolean {
            val dx = x - LUNG.x; val dz = z - LUNG.z
            val r = sqrt(dx * dx + dz * dz)
            if (r < RAMP_R0 || r > RAMP_R1) return false
            var a = atan2(dz, dx)
            if (a < 0) a += 2 * PI
            for (k in 0..2) {
                val theta = a + k * 2 * PI
                if (theta < RAMP_START) continue
                val h = rampHeight(theta)
                if (h > 24.0) continue
                // zwei Lücken zum Springen
                if (theta in 2.3..2.48 || theta in 4.4..4.58) continue
                val top = floor(h).toInt()
                if (y == top || y == top - 1) return true
            }
            return false
        }

        val cells = ArrayList<Cell>()
        val acidColumns = ArrayList<Pair<Int, Int>>()
        var MIN_X = 0; var MAX_X = 0; var MIN_Y = 0; var MAX_Y = 0; var MIN_Z = 0; var MAX_Z = 0

        private fun hash(x: Int, y: Int, z: Int): Double {
            var h = x * 73856093 xor (y * 19349663) xor (z * 83492791)
            h = (h xor (h ushr 13)) * 1274126177
            return ((h xor (h ushr 16)) and 0xFFFF) / 65535.0
        }

        private fun smooth(x: Double, z: Double, salt: Int): Double {
            val xi = floor(x).toInt(); val zi = floor(z).toInt()
            val fx = x - xi; val fz = z - zi
            val sx = fx * fx * (3 - 2 * fx); val sz = fz * fz * (3 - 2 * fz)
            val a = hash(xi, salt, zi); val b = hash(xi + 1, salt, zi)
            val c = hash(xi, salt, zi + 1); val d = hash(xi + 1, salt, zi + 1)
            return (a + (b - a) * sx) * (1 - sz) + (c + (d - c) * sx) * sz
        }

        private fun inMound(x: Double, y: Int, z: Double): Boolean = MOUNDS.any { m ->
            if (y > m[3]) return@any false
            val d = sqrt((x - STOMACH.x - m[0]) * (x - STOMACH.x - m[0]) + (z - STOMACH.z - m[1]) * (z - STOMACH.z - m[1]))
            d < m[2] + (m[3] - y) * 0.45
        }

        /** Säuretümpel in den Gedärmen (Weg-Anteil). */
        private val POOLS = doubleArrayOf(0.14, 0.33, 0.5, 0.68, 0.86)

        init {
            val flesh = LeviathanBlocks.FLESH.defaultState
            val vein = LeviathanBlocks.VEIN.defaultState
            val rib = LeviathanBlocks.RIB.defaultState
            val acid = LeviathanBlocks.ACID.defaultState
            val membrane = LeviathanBlocks.MEMBRANE.defaultState
            val lung = LeviathanBlocks.LUNG.defaultState
            val air = Blocks.AIR.defaultState
            MIN_X = floor(REGIONS.minOf { it.minX } - SHELL).toInt(); MAX_X = floor(REGIONS.maxOf { it.maxX } + SHELL).toInt() + 1
            MIN_Y = floor(REGIONS.minOf { it.minY } - SHELL).toInt(); MAX_Y = floor(REGIONS.maxOf { it.maxY } + SHELL).toInt() + 1
            MIN_Z = floor(REGIONS.minOf { it.minZ } - SHELL).toInt(); MAX_Z = floor(REGIONS.maxOf { it.maxZ } + SHELL).toInt() + 1
            val poolPoints = POOLS.map { pathPoint(it) }
            val villi = (1..14).map { pathPoint(it / 15.0 + 0.03) }
            val acidCols = HashSet<Long>()
            for (x in MIN_X..MAX_X) for (z in MIN_Z..MAX_Z) for (y in MIN_Y..MAX_Y) {
                val px = x.toDouble(); val py = y.toDouble(); val pz = z.toDouble()
                var inside: Region? = null
                var wall = false
                for (r in REGIONS) {
                    if (px < r.minX - SHELL - 1 || px > r.maxX + SHELL + 1 || py < r.minY - SHELL - 1 || py > r.maxY + SHELL + 1 ||
                        pz < r.minZ - SHELL - 1 || pz > r.maxZ + SHELL + 1
                    ) continue
                    if (r.norm(px, py, pz) < 1.0) { inside = r; break }
                    if (r.norm(px, py, pz, SHELL) < 1.0) wall = true
                }
                val state: BlockState = if (inside == null) {
                    if (!wall) continue
                    val ribBand = (x + 64).mod(8) == 0 && pz < -12 && px > -26 && px < 30
                    val veinLine = abs(smooth(px / 5.0, pz / 5.0 + py / 4.0, 7) - 0.5) < 0.035 || hash(x, y, z) < 0.015
                    when {
                        ribBand -> rib
                        veinLine -> vein
                        else -> flesh
                    }
                } else {
                    val r = inside
                    val floorTop = r.floorAt(px, py, pz)
                    var gate = -1
                    for ((gi, g) in GATE_DEF.withIndex()) {
                        val (gp, n) = g
                        if (abs((px - gp.x) * n.x + (py - gp.y) * n.y + (pz - gp.z) * n.z) < 1.0 &&
                            (px - gp.x) * (px - gp.x) + (py - gp.y) * (py - gp.y) + (pz - gp.z) * (pz - gp.z) < 8.0 * 8.0
                        ) { gate = gi; break }
                    }
                    when {
                        gate >= 0 && py > floorTop -> membrane.also { GATES[gate].add(Cell(x, y, z, membrane)) }
                        r.section == 1 && r.a == 32.0 -> {
                            // Magen: Säurerinne, Bodenwellen, Hügel, Rippen
                            val trench = abs(pz - STOMACH.z) < 3.5 && abs(px - STOMACH.x) < 22
                            val bump = STOMACH_FLOOR + (smooth(px / 7.0, pz / 7.0, 3) * 1.6 - 0.8).toInt()
                            when {
                                trench && y <= STOMACH_FLOOR - 3 -> flesh
                                trench && y <= STOMACH_FLOOR - 1 -> acid
                                !trench && y <= bump -> if (hash(x, y, z) < 0.03) vein else flesh
                                inMound(px, y, pz) -> if (y >= -12 && hash(x, y, z) < 0.25) vein else flesh
                                (x + 64).mod(8) == 0 && r.norm(px, py, pz) > 0.9 && y > STOMACH_FLOOR -> rib
                                else -> {
                                    if (y in STOMACH_FLOOR - 1..STOMACH_FLOOR + 1 && !trench) acidCols.add((x.toLong() shl 32) or (z.toLong() and 0xFFFFFFFFL))
                                    if (trench && y == STOMACH_FLOOR) acidCols.add((x.toLong() shl 32) or (z.toLong() and 0xFFFFFFFFL))
                                    air
                                }
                            }
                        }
                        r.section == 2 -> {
                            val pool = poolPoints.any { p -> (px - p.x) * (px - p.x) + (pz - p.z) * (pz - p.z) < 2.6 * 2.6 }
                            val villus = villi.withIndex().any { (i, p) ->
                                val side = if (i % 2 == 0) 2.2 else -2.2
                                // Zotte seitlich versetzt: quer zur Richtung (grob über x/z-Achsen)
                                val ox = p.x + if (i % 3 == 0) side else 0.0
                                val oz = p.z + if (i % 3 == 0) 0.0 else side
                                (px - ox) * (px - ox) + (pz - oz) * (pz - oz) < 1.1
                            }
                            when {
                                y <= floorTop - 1 -> if (hash(x, y, z) < 0.05) vein else flesh
                                y <= floorTop -> if (pool) acid else flesh
                                villus && py < floorTop + 3 -> if (hash(x, y, z) < 0.2) vein else flesh
                                else -> air
                            }
                        }
                        r.section == 3 && r.a == 18.0 -> when {
                            py <= floorTop -> if (y.toDouble() == floorTop) lung else flesh
                            rampAt(px, y, pz) -> if (hash(x, y, z) < 0.1) vein else flesh
                            else -> air
                        }
                        r.section == 4 && r.a == 20.0 -> {
                            val rr = sqrt((px - HEART_ROOM.x) * (px - HEART_ROOM.x) + (pz - HEART_ROOM.z) * (pz - HEART_ROOM.z))
                            when {
                                py <= floorTop -> if (rr < 3.5 && y.toDouble() == floorTop) vein else flesh
                                // Aorta-Bögen: leuchtende Adern an der Decke
                                r.norm(px, py, pz) > 0.92 && hash(x, y, z) < 0.25 -> vein
                                else -> air
                            }
                        }
                        else -> if (py <= floorTop) flesh else air
                    }
                }
                cells.add(Cell(x, y, z, state))
            }
            for (key in acidCols) {
                val x = (key shr 32).toInt(); val z = key.toInt()
                if (MOUNDS.any { m -> (x - STOMACH.x - m[0]) * (x - STOMACH.x - m[0]) + (z - STOMACH.z - m[1]) * (z - STOMACH.z - m[1]) < (m[2] + 1.5) * (m[2] + 1.5) }) continue
                acidColumns.add(x to z)
            }
            cells.sortBy { it.y }
        }
    }

    companion object {
        /** Bauch-Entwurf (≈ 1,4 Mio. Zellen geprüft) vorab berechnen – im Hintergrund beim Serverstart, nicht beim Verschlingen. */
        fun prewarm() {
            val t0 = System.nanoTime()
            Design.cells.size
            println("[belly] Entwurf vorberechnet in ${(System.nanoTime() - t0) / 1_000_000} ms, ${Design.cells.size} Zellen")
        }

        const val ACID_START = -16
        const val ACID_MAX = -12

        /** Abstand der Bauchmitte unter dem Arenaboden. */
        const val DEPTH = 96

        fun centerFor(world: ServerWorld, floor: Vec3d): BlockPos {
            var y = floor.y.toInt() - DEPTH
            val min = world.bottomY + 34
            if (y < min) y = (floor.y.toInt() + 80).coerceAtMost(world.topYInclusive - 40)
            return BlockPos.ofFloored(floor.x, y.toDouble(), floor.z)
        }

        // ---------- Wer im Bauch steckt, hat eine Marke (überlebt Abmelden und Neustart) ----------

        private const val TAG = "hha_void.belly"
        private const val RET = "hha_void.ret|"

        private val claimed = HashMap<UUID, Long>()

        fun claim(player: UUID, time: Long) {
            claimed[player] = time
        }

        fun mark(player: ServerPlayerEntity, home: Vec3d) {
            clear(player)
            player.addCommandTag(TAG)
            val w = player.entityWorld as ServerWorld
            player.addCommandTag("$RET${w.registryKey.value}|${home.x.toInt()}|${home.y.toInt()}|${home.z.toInt()}")
        }

        fun clear(player: ServerPlayerEntity) {
            player.removeCommandTag(TAG)
            player.commandTags.filter { it.startsWith(RET) }.forEach { player.removeCommandTag(it) }
            claimed.remove(player.uuid)
        }

        fun isMarked(player: ServerPlayerEntity) = player.commandTags.contains(TAG)

        fun sendHome(player: ServerPlayerEntity) {
            val tag = player.commandTags.firstOrNull { it.startsWith(RET) }
            clear(player)
            val server = (player.entityWorld as ServerWorld).server!!
            val parts = tag?.removePrefix(RET)?.split('|')
            val world = parts?.getOrNull(0)?.let { Identifier.tryParse(it) }
                ?.let { server.getWorld(RegistryKey.of(RegistryKeys.WORLD, it)) }
            val x = parts?.getOrNull(1)?.toDoubleOrNull()
            val y = parts?.getOrNull(2)?.toDoubleOrNull()
            val z = parts?.getOrNull(3)?.toDoubleOrNull()
            if (world == null || x == null || y == null || z == null) return
            player.teleport(world, x + 0.5, y + 1.0, z + 0.5, emptySet(), player.yaw, 0f, false)
            player.fallDistance = 0.0
            player.addStatusEffect(StatusEffectInstance(StatusEffects.SLOW_FALLING, 100, 0, false, false, true))
            player.addStatusEffect(StatusEffectInstance(StatusEffects.RESISTANCE, 60, 2, false, false, true))
        }

        fun init() {
            ServerPlayConnectionEvents.JOIN.register { handler, _, server ->
                val player = handler.player
                if (!isMarked(player)) return@register
                player.addStatusEffect(StatusEffectInstance(StatusEffects.SLOW_FALLING, 120, 0, false, false, true))
                player.addStatusEffect(StatusEffectInstance(StatusEffects.RESISTANCE, 120, 3, false, false, true))
                TickScheduler.schedule(60) {
                    if (player.isRemoved || !isMarked(player)) return@schedule
                    val last = claimed[player.uuid]
                    val now = server.ticks.toLong()
                    if (last == null || now - last > 40) sendHome(player)
                }
            }
            ServerPlayerEvents.AFTER_RESPAWN.register { _, newPlayer, _ ->
                if (isMarked(newPlayer)) clear(newPlayer)
            }
        }
    }
}
