package dev.henny.hha_void.boss

import com.mojang.serialization.Codec
import net.minecraft.entity.SpawnGroup
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.BlockPos
import net.minecraft.world.PersistentState
import net.minecraft.world.PersistentStateType

/**
 * Wo in einer Welt Arenen stehen (per `/voidboss place` gebaut oder natürlich erzeugt – der Altar meldet sich beim
 * ersten Tick). Auf der ganzen Arena inklusive Inselring spawnen keine Monster (Endermen): `SpawnHelperMixin` fragt
 * [blocksSpawn]. Natürlich erzeugte Arenen sperren zusätzlich per `spawn_overrides` in der Struktur-JSON.
 */
class ArenaRegistry(val centers: MutableList<BlockPos> = ArrayList()) : PersistentState() {

    companion object {
        private val CODEC: Codec<ArenaRegistry> = BlockPos.CODEC.listOf().xmap({ ArenaRegistry(ArrayList(it)) }, { it.centers.toList() })
        private val TYPE = PersistentStateType("hha_void_arenas", { ArenaRegistry() }, CODEC, null)

        /** Horizontaler Radius und Höhenbereich (relativ zum Boden), in dem nichts spawnt. */
        const val RANGE = TribunalBuilder.RADIUS + 110.0
        private const val BELOW = 140.0
        private const val ABOVE = 100.0

        fun of(world: ServerWorld): ArenaRegistry = world.persistentStateManager.getOrCreate(TYPE)

        fun find(world: ServerWorld): ArenaRegistry? = world.persistentStateManager.get(TYPE)

        fun register(world: ServerWorld, center: BlockPos) {
            val reg = of(world)
            if (reg.centers.any { it.getSquaredDistance(center) < 16.0 * 16.0 }) return
            reg.centers.add(center.toImmutable())
            reg.markDirty()
        }

        fun contains(world: ServerWorld, x: Double, y: Double, z: Double): Boolean {
            val reg = world.persistentStateManager.get(TYPE) ?: return false
            for (c in reg.centers) {
                val dx = x - c.x; val dz = z - c.z
                if (dx * dx + dz * dz <= RANGE * RANGE && y > c.y - BELOW && y < c.y + ABOVE) return true
            }
            return false
        }

        @JvmStatic
        fun blocksSpawn(world: ServerWorld, group: SpawnGroup, pos: BlockPos): Boolean =
            group == SpawnGroup.MONSTER && contains(world, pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5)
    }
}

/**
 * Teure Einmal-Berechnungen (Arena-Teile, Bauch-Entwurf) in einem Hintergrund-Thread beim Serverstart, damit der
 * Server-Thread nie darauf wartet. Im Gametest zusätzlich: jeder Server-Tick über 250 ms wird mit Zustand geloggt.
 */
object BossPrewarm {
    private val TEST = System.getenv("HHA_VOID_TEST") != null
    @Volatile private var tickStart = 0L
    @Volatile private var serverThread: Thread? = null
    @Volatile private var tickEnd = 0L

    fun init() {
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register { _ ->
            Thread({
                runCatching { TribunalBuilder.prewarm() }.onFailure { it.printStackTrace() }
                runCatching { LeviathanBelly.prewarm() }.onFailure { it.printStackTrace() }
            }, "hha_void-prewarm").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
        }
        if (TEST) {
            // Stack-Sampler: hängt der Server-Thread > 400 ms (im Tick oder dazwischen), seinen Stack einmal ausgeben
            net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register { _ -> serverThread = Thread.currentThread() }
            Thread({
                var reported = 0L
                while (true) {
                    Thread.sleep(100)
                    val t = serverThread ?: continue
                    val last = maxOf(tickStart, tickEnd)
                    if (last == 0L) continue
                    val ms = (System.nanoTime() - last) / 1_000_000
                    if (ms > 400 && reported != last) {
                        reported = last
                        val st = t.stackTrace.take(28).joinToString("\n    ") { it.toString() }
                        println("[tick] Server-Thread hängt seit ${ms} ms:\n    $st")
                    }
                }
            }, "hha_void-sampler").apply { isDaemon = true }.start()
            net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.START_SERVER_TICK.register { s ->
                tickStart = System.nanoTime()
                val gap = (tickStart - tickEnd) / 1_000_000
                if (tickEnd != 0L && gap > 300) println("[tick] lange Pause zwischen Ticks ${gap} ms vor tick ${s.ticks} (Aufgaben/Chunk-Laden)")
            }
            net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register { s ->
                val ms = (System.nanoTime() - tickStart) / 1_000_000
                if (ms > 250) println("[tick] langer Server-Tick ${ms} ms (tick ${s.ticks}, Arenabau=${TribunalBuilder.isPlacing()})")
                tickEnd = System.nanoTime()
            }
        }
    }
}
