package dev.henny.hha_void.gametest

import dev.henny.hha_void.boss.ArenaRegistry
import dev.henny.hha_void.boss.LeviathanBlocks
import dev.henny.hha_void.boss.TribunalBuilder
import dev.henny.hha_void.boss.VoidBoss
import net.minecraft.entity.EntityType
import net.minecraft.entity.SpawnGroup
import net.minecraft.registry.RegistryKeys
import net.minecraft.util.Identifier
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Box
import net.minecraft.util.math.ChunkPos
import net.minecraft.util.math.Vec3d
import net.minecraft.world.Heightmap
import net.minecraft.world.World
import net.minecraft.world.biome.BiomeKeys
import net.minecraft.world.biome.source.BiomeCoords
import net.minecraft.world.gen.chunk.placement.RandomSpreadStructurePlacement
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Rundflug um die Sternentiefe v4 (Ø ≈ 372): baut sie mit `/voidboss place` im End (x 3000, y 180), misst Bauzeit und
 * längsten Tick, prüft den freien Luftraum, Endermen (Spawn gesperrt, hineinteleportierte verschwinden) und fotografiert.
 * Danach wird eine **natürlich erzeugte** Arena über die echte Platzierung gesucht (Haupt-Struktur + Kacheln) und angeflogen.
 */
object ArenaShowcase {

    private const val END = "minecraft:the_end"

    private class Holder<T>(val value: T)

    private fun <T> server(t: TestKit, f: (net.minecraft.server.MinecraftServer) -> T): T =
        t.world.server.computeOnServer<Holder<T>, RuntimeException> { Holder(f(it)) }.value

    fun run(t: TestKit) {
        t.context.runOnClient<RuntimeException> { it.options.viewDistance.value = 16; it.options.sendClientSettings() }
        t.hideHud()
        t.cmd("gamemode spectator @p")

        if (System.getenv("HHA_VOID_ARENA") == "natural") { natural(t); return }
        val c = BlockPos(3000, 180, 0)
        t.cmd("execute in $END run tp @p ${c.x + 0.5} ${c.y + 12} ${c.z + 0.5}")
        t.waitTicks(60)

        val startMs = System.nanoTime()
        t.world.server.runOnServer<RuntimeException> { srv ->
            val w = srv.getWorld(World.END)!!
            val src = srv.commandSource.withWorld(w).withPosition(Vec3d(c.x + 0.5, c.y + 1.0, c.z + 0.5)).withSilent()
            srv.commandManager.parseAndExecute(src, "voidboss place")
        }
        var ticks = 0
        var worst = 0L
        var last = System.nanoTime()
        while (server(t) { TribunalBuilder.isPlacing() } && ticks < 6000) {
            t.waitTicks(1)
            ticks++
            val now = System.nanoTime()
            worst = maxOf(worst, now - last)
            last = now
        }
        val placeMs = (System.nanoTime() - startMs) / 1e6
        println("[gametest] arena place: $ticks ticks, ${"%.0f".format(placeMs)} ms, längster Tick (Client-Sicht) ${worst / 1_000_000} ms")
        val stats = server(t) { srv ->
            val w = srv.getWorld(World.END)!!
            val altar = w.getBlockState(c.up()).isOf(VoidBoss.ALTAR)
            val harpoons = TribunalBuilder.HARPOONS.count { w.getBlockState(c.add(it)).isOf(LeviathanBlocks.HARPOON) }
            val edge = w.getBlockState(c.add(TribunalBuilder.RADIUS - 3, 0, 0))
            "altar=$altar harpoons=$harpoons/${TribunalBuilder.HARPOONS.size} rimBlock=$edge R=${TribunalBuilder.RADIUS} " +
                "floor=${TribunalBuilder.FLOOR_RADIUS} extent=${TribunalBuilder.EXTENT} harpoonSpots=${TribunalBuilder.HARPOONS}"
        }
        println("[gametest] arena $stats")
        println("[gametest] arena corridor: ${corridorReport(t)}")
        val mem = Runtime.getRuntime().let { (it.totalMemory() - it.freeMemory()) / 1_000_000 }
        println("[gametest] heap after place: $mem MB")
        endermen(t, c, "placed")
        if (System.getenv("HHA_VOID_ARENA") == "place") return

        t.waitTicks(120)
        shots(t, c, "arena")
        natural(t)
    }

    /** Monster-Spawn gesperrt? Ein hineingesetzter Enderman verschwindet? */
    private fun endermen(t: TestKit, c: BlockPos, label: String) {
        val blocked = server(t) { srv ->
            val w = srv.getWorld(World.END)!!
            listOf(
                ArenaRegistry.blocksSpawn(w, SpawnGroup.MONSTER, c.up()),
                ArenaRegistry.blocksSpawn(w, SpawnGroup.MONSTER, c.add(TribunalBuilder.HARPOONS[0])),
                ArenaRegistry.blocksSpawn(w, SpawnGroup.MONSTER, c.add(250, 30, -40)),
                ArenaRegistry.blocksSpawn(w, SpawnGroup.MONSTER, c.add(600, 0, 0)),
            )
        }
        t.cmd("execute in $END run summon minecraft:enderman ${c.x + 40} ${c.y + 1} ${c.z + 12}")
        t.cmd("execute in $END run summon minecraft:enderman ${c.x + 230} ${c.y + 20} ${c.z + 3}")
        t.waitTicks(90)
        val left = server(t) { srv ->
            val w = srv.getWorld(World.END)!!
            w.getEntitiesByType(EntityType.ENDERMAN, Box(Vec3d.ofCenter(c), Vec3d.ofCenter(c)).expand(300.0, 150.0, 300.0)) { true }
                .count { ArenaRegistry.contains(w, it.x, it.y, it.z) }
        }
        println("[gametest] endermen $label: spawnBlocked(mitte, aussenplattform, inselring, ausserhalb)=$blocked, auf der Arena übrig=$left")
    }

    /** Zählt feste Blöcke im Luftraum über der Plattform (TribunalBuilder.CORRIDOR_*) – soll 0 sein. */
    private fun corridorReport(t: TestKit): String = server(t) {
        var n = 0
        val hits = ArrayList<String>()
        val outer = TribunalBuilder.CORRIDOR_OUTER.toInt() + 1
        for (x in -outer..outer step 2) for (z in -outer..outer step 2) {
            val r = sqrt((x * x + z * z).toDouble())
            if (r < TribunalBuilder.CORRIDOR_INNER || r > TribunalBuilder.CORRIDOR_OUTER) continue
            for (y in TribunalBuilder.CORRIDOR_LOW..TribunalBuilder.CORRIDOR_HIGH) {
                val s = TribunalBuilder.stateAt(x, y, z) ?: continue
                if (!s.isAir) { n++; if (hits.size < 6) hits.add("($x,$y,$z)") }
            }
        }
        "$n Blöcke (jede 2. Spalte) $hits"
    }

    private fun view(t: TestKit, c: BlockPos, dx: Double, dy: Double, dz: Double, tx: Double, ty: Double, tz: Double, name: String, wait: Int = 70) {
        val px = c.x + dx; val py = c.y + dy; val pz = c.z + dz
        val vx = tx - dx; val vy = ty - dy; val vz = tz - dz
        val yaw = Math.toDegrees(atan2(-vx, vz)).toFloat()
        val pitch = Math.toDegrees(-atan2(vy, sqrt(vx * vx + vz * vz))).toFloat()
        t.cmd("execute in $END run tp @p $px $py $pz $yaw $pitch")
        t.waitTicks(wait)
        t.shot(name)
    }

    private fun shots(t: TestKit, c: BlockPos, p: String) {
        view(t, c, 330.0, 140.0, 200.0, 0.0, 0.0, 0.0, "${p}_01_far", 200)
        view(t, c, -300.0, -40.0, -220.0, 0.0, 6.0, 0.0, "${p}_02_far_low", 160)
        view(t, c, 200.0, 160.0, 180.0, 0.0, 0.0, 0.0, "${p}_03_oblique", 120)
        view(t, c, 0.5, 330.0, 0.5, 0.5, 0.0, 0.6, "${p}_04_top", 160)
        view(t, c, 120.5, 1.6, 6.5, 0.0, 5.0, 0.0, "${p}_05_ground")
        val (mx, mz) = TribunalBuilder.MONOLITHS[5]
        view(t, c, 8.5, 1.6, -3.5, mx.toDouble(), 8.0, mz.toDouble(), "${p}_06_monolith")
        // Außenplattform mit Harpunen-Geschütz und Sprungpfad
        val h = TribunalBuilder.HARPOONS[0]
        val hl = sqrt((h.x * h.x + h.z * h.z).toDouble())
        view(t, c, h.x - h.x / hl * 10.0 + 6.0, h.y + 3.0, h.z - h.z / hl * 10.0, h.x.toDouble(), h.y + 0.5, h.z.toDouble(), "${p}_07_harpoon")
        view(t, c, h.x + h.x / hl * 14.0, h.y + 6.0, h.z + h.z / hl * 14.0, 0.0, 4.0, 0.0, "${p}_08_from_outpost")
        val pa = Math.toRadians(TribunalBuilder.PATH_STARTS[1])
        view(t, c, cos(pa) * 168.0, 2.6, sin(pa) * 168.0, cos(pa + 0.06) * 215.0, 8.0, sin(pa + 0.06) * 215.0, "${p}_09_path")
        // Rippenbögen (60°), Bastion (120°)
        val ra = Math.toRadians(60.0)
        view(t, c, cos(ra) * 150.0, 2.6, sin(ra) * 150.0 - 10.0, cos(ra) * 190.0, 12.0, sin(ra) * 190.0, "${p}_10_ribs")
        val ba = Math.toRadians(120.0)
        view(t, c, cos(ba) * 140.0, 3.0, sin(ba) * 140.0, cos(ba) * 175.0, 6.0, sin(ba) * 175.0, "${p}_11_bastion")
        view(t, c, 160.0, 36.0, -20.0, -100.0, 20.0, 20.0, "${p}_12_flight")
        view(t, c, 220.0, -110.0, 150.0, 0.0, -30.0, 0.0, "${p}_13_underside", 120)
        view(t, c, 6.5, 3.0, 22.5, TribunalBuilder.LIFT_X.toDouble(), 0.5, TribunalBuilder.LIFT_Z.toDouble(), "${p}_14_lift_top")
    }

    /**
     * Natürlich erzeugte Sternentiefe: dieselbe Platzierung wie die Weltgenerierung (Struktur-Set `void_tribunals`) –
     * erste gültige Stelle im äußeren End suchen, hinfliegen; die Chunks erzeugen Haupt-Struktur und Kacheln selbst.
     */
    private fun natural(t: TestKit) {
        val found = server(t) { srv ->
            val w = srv.getWorld(World.END)!!
            val set = srv.registryManager.getOrThrow(RegistryKeys.STRUCTURE_SET).get(Identifier.of("hha_void", "void_tribunals"))
            val placement = set?.placement() as? RandomSpreadStructurePlacement ?: return@server null
            val gen = w.chunkManager.chunkGenerator
            val noise = w.chunkManager.noiseConfig
            val seed = w.seed
            var spot: BlockPos? = null
            loop@ for (rx in 1..8) for (rz in -3..3) {
                val start = placement.getStartChunk(seed, rx * placement.spacing, rz * placement.spacing)
                val x = start.getOffsetX(8); val z = start.getOffsetZ(8)
                if (x * x + z * z < 1500 * 1500) continue
                val h = gen.getHeightInGround(x, z, Heightmap.Type.WORLD_SURFACE_WG, w, noise)
                if (h < 45) continue
                val biome = gen.biomeSource.getBiome(BiomeCoords.fromBlock(x), BiomeCoords.fromBlock(TribunalBuilder.FLOOR_Y), BiomeCoords.fromBlock(z), noise.multiNoiseSampler)
                if (!biome.matchesKey(BiomeKeys.END_HIGHLANDS) && !biome.matchesKey(BiomeKeys.END_MIDLANDS)) continue
                spot = BlockPos(x, TribunalBuilder.FLOOR_Y, z)
                break@loop
            }
            println("[gametest] natürliche Arena laut Platzierung: $spot (spacing ${placement.spacing}/${placement.separation})")
            spot
        }
        if (found == null) {
            println("[gametest] keine natürliche Sternentiefe gefunden")
            return
        }
        val c = found
        // erst die Mitte, dann eine Außenplattform anfliegen (dort erzeugt nur eine Kachel)
        t.cmd("execute in $END run tp @p ${c.x + 0.5} ${c.y + 40} ${c.z + 0.5}")
        t.waitTicks(300)
        val h = TribunalBuilder.HARPOONS[2]
        t.cmd("execute in $END run tp @p ${c.x + h.x + 0.5} ${c.y + h.y + 20} ${c.z + h.z + 0.5}")
        t.waitTicks(300)
        val report = server(t) { srv ->
            val w = srv.getWorld(World.END)!!
            val altar = w.getBlockState(c.up()).isOf(VoidBoss.ALTAR)
            val outer = TribunalBuilder.HARPOONS.map { w.isChunkLoaded(ChunkPos(c.add(it)).x, ChunkPos(c.add(it)).z) to w.getBlockState(c.add(it)).isOf(LeviathanBlocks.HARPOON) }
            val rim = w.getBlockState(c.add(0, 0, TribunalBuilder.RADIUS - 3))
            "altar=$altar harpoons(loaded,ok)=$outer rim=$rim"
        }
        println("[gametest] natürliche Sternentiefe bei $c: $report")
        view(t, c, 330.0, 140.0, 200.0, 0.0, 0.0, 0.0, "natural_01_far", 240)
        view(t, c, h.x - 20.0, h.y + 14.0, h.z - 10.0, h.x.toDouble(), h.y.toDouble(), h.z.toDouble(), "natural_02_outpost", 160)
        view(t, c, 120.5, 1.6, 4.5, 0.0, 4.0, 0.0, "natural_03_ground", 160)
        view(t, c, 40.0, -60.0, 60.0, TribunalBuilder.LIFT_X.toDouble(), -70.0, TribunalBuilder.LIFT_Z.toDouble(), "natural_04_lift", 140)
        // natürliche Arena: Endermen nach einer Weile zählen (Spawn per spawn_overrides + Mixin gesperrt)
        t.waitTicks(400)
        val count = server(t) { srv ->
            val w = srv.getWorld(World.END)!!
            val all = w.getEntitiesByType(EntityType.ENDERMAN, Box(Vec3d.ofCenter(c), Vec3d.ofCenter(c)).expand(320.0, 160.0, 320.0)) { true }
            "auf der Arena ${all.count { ArenaRegistry.contains(w, it.x, it.y, it.z) || it.squaredDistanceTo(Vec3d.ofCenter(c)) < 280.0 * 280.0 && it.y > c.y - 70 }} / im Umkreis ${all.size}"
        }
        println("[gametest] endermen natural: $count")
    }
}
