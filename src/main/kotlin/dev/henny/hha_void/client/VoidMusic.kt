package dev.henny.hha_void.client

import dev.henny.hha_void.VoidItems
import dev.henny.hha_void.VoidSounds
import dev.henny.hha_void.boss.VoidBossEntity
import dev.henny.hha_void.boss.WhalePose
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.block.Block
import net.minecraft.block.Blocks
import net.minecraft.client.MinecraftClient
import net.minecraft.client.sound.MovingSoundInstance
import net.minecraft.client.sound.SoundInstance
import net.minecraft.client.world.ClientWorld
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.registry.Registries
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvent
import net.minecraft.util.math.BlockPos
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/**
 * Musik des Sternenwal-Kampfs (nur Client).
 *
 * - Lebender Oruun (`VoidBossEntity`) im Umkreis von 120 Blöcken, nicht mehr `HIDDEN`: Phasen-Track zu
 *   `bossPhase()` als Loop; beim Phasenwechsel weiches Überblenden. Vanilla-Musik bleibt so lange stumm.
 * - `castPose() == DEATH` oder Boss tot: `music.whale_victory` einmal, danach wieder normale Musik.
 * - Kein Boss, aber in der Arena (Ruf der Tiefe im Umkreis von 40 Blöcken): leise `ambient.deep`.
 *
 * Boss-Tracks laufen in der Kategorie MUSIK (Musik-Regler), das Arena-Ambiente in UMGEBUNG.
 */
object VoidMusic {

    private const val BOSS_RANGE = 200.0
    /** einmal begonnen, reißt die Musik nicht beim kleinsten Schritt über die Grenze ab */
    private const val BOSS_KEEP = 260.0
    private const val ARENA_RANGE = 70
    private const val AMBIENT_VOLUME = 0.6f

    /** Nicht-positionaler Klang, der sich selbst ein- und ausblendet. */
    private class FadingSound(
        event: SoundEvent, category: SoundCategory, loop: Boolean,
        private val maxVolume: Float, private val fadeIn: Float, private val fadeOut: Float,
    ) : MovingSoundInstance(event, category, SoundInstance.createRandom()) {
        var target = maxVolume

        init {
            repeat = loop
            repeatDelay = 0
            relative = true
            attenuationType = SoundInstance.AttenuationType.NONE
            volume = 0.001f
        }

        override fun tick() {
            volume = if (volume < target) min(target, volume + fadeIn) else max(target, volume - fadeOut)
            if (target <= 0f && volume <= 0.001f) setDone()
        }

        fun fadeAway() { target = 0f }
        fun restore() { target = maxVolume }

        // startet auch bei Regler 0, damit späteres Hochdrehen wirkt
        override fun shouldAlwaysPlay(): Boolean = true
    }

    private var music: FadingSound? = null
    private var musicEvent: SoundEvent? = null
    private var musicTicks = 0
    private var victory: FadingSound? = null
    private var victoryTicks = 0
    private var victoryFor: UUID? = null
    private var ambient: FadingSound? = null
    private var ambientTicks = 0

    private var fightBoss: UUID? = null
    private var fightBossDying = false
    private var arenaCheck = 0
    private var inArena = false

    fun init() {
        ClientTickEvents.END_CLIENT_TICK.register(::tick)
    }

    private fun tick(client: MinecraftClient) {
        val world = client.world
        val player = client.player
        if (world == null || player == null) {
            reset(client)
            return
        }

        val boss = findBoss(world, player)
        val dying = boss != null && (boss.castPose() == WhalePose.DEATH || boss.isDead)
        val active = boss != null && !dying && boss.castPose() != WhalePose.HIDDEN

        // Sieg: Todes-Haltung/Tod gesehen, oder der sterbende Boss ist schon entfernt worden
        if (boss != null) {
            if (dying && victoryFor != boss.uuid) startVictory(client, boss.uuid)
            fightBoss = boss.uuid
            fightBossDying = dying
        } else {
            val last = fightBoss
            if (last != null && fightBossDying && victoryFor != last) startVictory(client, last)
            fightBoss = null
            fightBossDying = false
        }

        // Sieges-Track fertig?
        victory?.let {
            victoryTicks++
            if (it.isDone || (victoryTicks > 40 && !client.soundManager.isPlaying(it)) || victoryTicks > 20 * 60) victory = null
        }

        val wanted = when {
            victory != null -> null
            active -> phaseTrack(boss!!.bossPhase())
            else -> null
        }
        setMusic(client, wanted)

        // Arena-Ambiente
        if (--arenaCheck <= 0) {
            arenaCheck = 20
            inArena = nearAltar(world, player.blockPos)
        }
        setAmbient(client, inArena && music == null && victory == null && !active)

        // Vanilla-Musik schweigt, solange unsere läuft
        if (music != null || victory != null) client.musicTracker.stop()
    }

    private fun phaseTrack(phase: Int): SoundEvent = when (phase.coerceIn(1, 3)) {
        1 -> VoidSounds.MUSIC_PHASE1
        2 -> VoidSounds.MUSIC_PHASE2
        else -> VoidSounds.MUSIC_PHASE3
    }

    private fun setMusic(client: MinecraftClient, event: SoundEvent?) {
        val current = music
        // von außen gestoppt (z. B. /stopsound, Ressourcen neu geladen)? Dann neu starten.
        if (current != null && ++musicTicks > 40 && !client.soundManager.isPlaying(current)) {
            music = null
            musicEvent = null
        }
        if (event == musicEvent && (event == null || music != null)) return
        music?.fadeAway() // altes Stück blendet aus, während das neue einblendet
        music = null
        musicEvent = event
        musicTicks = 0
        if (event != null) {
            music = FadingSound(event, SoundCategory.MUSIC, true, 1f, 0.025f, 0.02f).also { client.soundManager.play(it) }
        }
    }

    private fun startVictory(client: MinecraftClient, boss: UUID) {
        victoryFor = boss
        music?.fadeAway()
        music = null
        musicEvent = null
        victory?.fadeAway()
        victoryTicks = 0
        victory = FadingSound(VoidSounds.MUSIC_VICTORY, SoundCategory.MUSIC, false, 1f, 0.05f, 0.02f)
            .also { client.soundManager.play(it) }
    }

    private fun setAmbient(client: MinecraftClient, on: Boolean) {
        val cur = ambient
        if (cur != null && (cur.isDone || (++ambientTicks > 40 && !client.soundManager.isPlaying(cur)))) ambient = null
        if (on) {
            val a = ambient
            if (a == null) {
                ambientTicks = 0
                ambient = FadingSound(VoidSounds.AMBIENT_DEEP, SoundCategory.AMBIENT, true, AMBIENT_VOLUME, 0.008f, 0.02f)
                    .also { client.soundManager.play(it) }
            } else a.restore()
        } else ambient?.let {
            it.fadeAway()
            if (it.isDone) ambient = null
        }
    }

    private fun findBoss(world: ClientWorld, player: PlayerEntity): VoidBossEntity? {
        val range = if (fightBoss != null) BOSS_KEEP else BOSS_RANGE
        var best: VoidBossEntity? = null
        var bestDist = range * range
        for (e in world.entities) {
            if (e !is VoidBossEntity || e.isRemoved) continue
            val d = e.squaredDistanceTo(player)
            if (d <= bestDist) {
                best = e
                bestDist = d
            }
        }
        return best
    }

    private fun altarBlock(): Block? =
        Registries.BLOCK.get(VoidItems.id("void_altar")).takeIf { it != Blocks.AIR }

    /** Sucht den Ruf der Tiefe im Umkreis; prüft erst die Paletten der Chunk-Abschnitte (billig). */
    private fun nearAltar(world: ClientWorld, pos: BlockPos): Boolean {
        val altar = altarBlock() ?: return false
        val r = ARENA_RANGE
        val minSec = world.getSectionIndex(max(world.bottomY, pos.y - r))
        val maxSec = world.getSectionIndex(min(world.topYInclusive, pos.y + r))
        for (cx in ((pos.x - r) shr 4)..((pos.x + r) shr 4)) for (cz in ((pos.z - r) shr 4)..((pos.z + r) shr 4)) {
            val sections = world.getChunk(cx, cz).sectionArray
            for (i in minSec..maxSec) {
                val s = sections.getOrNull(i) ?: continue
                if (s.isEmpty || !s.hasAny { it.isOf(altar) }) continue
                val baseY = world.sectionIndexToCoord(i) shl 4
                for (x in 0..15) for (y in 0..15) for (z in 0..15) {
                    if (!s.getBlockState(x, y, z).isOf(altar)) continue
                    val dx = (cx shl 4) + x - pos.x
                    val dy = baseY + y - pos.y
                    val dz = (cz shl 4) + z - pos.z
                    if (dx * dx + dy * dy + dz * dz <= r * r) return true
                }
            }
        }
        return false
    }

    private fun reset(client: MinecraftClient) {
        for (s in listOfNotNull(music, victory, ambient)) client.soundManager.stop(s)
        music = null
        musicEvent = null
        victory = null
        ambient = null
        fightBoss = null
        fightBossDying = false
        inArena = false
        arenaCheck = 0
    }
}
