package dev.henny.hha_void

import net.minecraft.registry.Registries
import net.minecraft.registry.Registry
import net.minecraft.sound.SoundEvent

/**
 * Eigene Klänge des Sternenwal-Kampfs (alle synthetisiert, Generator in tools/sounds/).
 * Die IDs entsprechen den Einträgen in assets/hha_void/sounds.json.
 */
object VoidSounds {

    private val all = ArrayList<SoundEvent>()

    private fun of(path: String): SoundEvent = SoundEvent.of(VoidItems.id(path)).also { all += it }

    // Musik (Kategorie MUSIC/RECORDS, gesteuert von client/VoidMusic)
    val MUSIC_PHASE1 = of("music.whale_phase1")
    val MUSIC_PHASE2 = of("music.whale_phase2")
    val MUSIC_PHASE3 = of("music.whale_phase3")
    val MUSIC_VICTORY = of("music.whale_victory")
    val AMBIENT_DEEP = of("ambient.deep")

    // Der Wal
    val WHALE_SONG = of("whale.song")
    val WHALE_GROAN = of("whale.groan")
    val WHALE_HURT = of("whale.hurt")
    val WHALE_DEATH = of("whale.death")
    val WHALE_BREACH = of("whale.breach")

    // Angriffe
    val RIFT_OPEN = of("rift_open")
    val GRAVITY_WAVE = of("gravity_wave")
    val BLACK_HOLE = of("black_hole")
    val DEBRIS = of("debris")
    val STAR_BREATH = of("star_breath")
    val SWALLOW = of("swallow")
    val SUPERNOVA_CHARGE = of("supernova_charge")
    val SUPERNOVA_BLAST = of("supernova_blast")

    // Sternlinge
    val STARLING_AMBIENT = of("starling.ambient")
    val STARLING_HURT = of("starling.hurt")
    val STARLING_DEATH = of("starling.death")

    fun init() {
        for (event in all) Registry.register(Registries.SOUND_EVENT, event.id, event)
    }
}
