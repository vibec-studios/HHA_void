package dev.henny.hha_void.logic

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.server.MinecraftServer

/** Kleiner Server-Tick-Scheduler für verzögerte Aktionen (Dolche, Meteore, Slams). */
object TickScheduler {

    private class Task(var delay: Int, val action: () -> Unit)

    private val tasks = ArrayList<Task>()
    private val incoming = ArrayList<Task>()

    fun init() {
        ServerTickEvents.END_SERVER_TICK.register { _: MinecraftServer ->
            if (incoming.isNotEmpty()) {
                tasks.addAll(incoming)
                incoming.clear()
            }
            val iterator = tasks.iterator()
            while (iterator.hasNext()) {
                val task = iterator.next()
                if (--task.delay <= 0) {
                    iterator.remove()
                    runCatching(task.action)
                }
            }
        }
    }

    /** Führt [action] nach [delayTicks] Server-Ticks aus (auch aus Ticks heraus planbar). */
    fun schedule(delayTicks: Int, action: () -> Unit) {
        if (delayTicks <= 0) {
            action()
        } else {
            incoming.add(Task(delayTicks, action))
        }
    }
}
