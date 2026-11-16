package dev.henny.hha_void.gametest

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext

/**
 * Startet das echte Spiel und macht Screenshots (build/run/clientGameTest/screenshots).
 * Modus per Umgebungsvariable HHA_VOID_TEST (kombinierbar, z. B. "armor,whale"):
 *   armor  – Rüstung/Waffen-Vitrine      (ArmorShowcase.kt)
 *   whale  – Sternenwal-Modell + Posen    (WhaleShowcase.kt)
 *   arena  – Arena-Rundflug               (ArenaShowcase.kt)
 *   fight  – Kampf/Cutscenes              (FightShowcase.kt)
 * Jeder Teil steht in seiner eigenen Datei, damit parallele Arbeit nicht kollidiert.
 */
class VoidClientGameTest : FabricClientGameTest {

    override fun runTest(context: ClientGameTestContext) {
        val mode = System.getProperty("hha_void.test") ?: System.getenv("HHA_VOID_TEST") ?: "armor"
        println("[gametest] mode $mode")
        context.worldBuilder()
            .adjustSettings {
                it.setGameMode(net.minecraft.client.gui.screen.world.WorldCreator.Mode.CREATIVE)
                // natürliche Arenen (Struktur + Kacheln) sollen im Test wirklich entstehen
                it.setGenerateStructures(true)
            }
            .create().use { world ->
                world.clientWorld.waitForChunksRender()
                val t = TestKit(context, world)
                if (mode.contains("armor")) ArmorShowcase.run(t)
                if (mode.contains("whale")) WhaleShowcase.run(t)
                if (mode.contains("arena")) ArenaShowcase.run(t)
                if (mode.contains("fight")) FightShowcase.run(t)
            }
    }
}

/** Kleine Helfer für alle Vitrinen. */
class TestKit(val context: ClientGameTestContext, val world: TestSingleplayerContext) {

    fun cmd(s: String) = world.server.runCommand(s)

    fun waitTicks(n: Int) = context.waitTicks(n)

    fun shot(name: String) {
        // Chat-Rückmeldungen würden das Bild verdecken
        context.runOnClient<RuntimeException> { it.inGameHud.chatHud.clear(false) }
        context.takeScreenshot(name)
    }

    fun hideHud(hidden: Boolean = true) = context.runOnClient<RuntimeException> { it.options.hudHidden = hidden }

    /** Teleportiert den Spieler nach (px,py,pz) mit Blick auf (tx,ty,tz), wartet und macht ein Foto. */
    fun look(dim: String, px: Double, py: Double, pz: Double, tx: Double, ty: Double, tz: Double, name: String, wait: Int = 25) {
        val vx = tx - px; val vy = ty - py; val vz = tz - pz
        val yaw = Math.toDegrees(Math.atan2(-vx, vz)).toFloat()
        val pitch = Math.toDegrees(-Math.atan2(vy, Math.sqrt(vx * vx + vz * vz))).toFloat()
        cmd("execute in $dim run tp @p $px $py $pz $yaw $pitch")
        waitTicks(wait)
        shot(name)
    }
}
