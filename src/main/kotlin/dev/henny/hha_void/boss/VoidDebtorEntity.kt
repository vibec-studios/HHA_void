package dev.henny.hha_void.boss

import dev.henny.hha_void.VoidSounds
import net.minecraft.entity.EntityType
import net.minecraft.entity.damage.DamageSource
import net.minecraft.entity.mob.VexEntity
import net.minecraft.sound.SoundEvent
import net.minecraft.world.World

/**
 * Sternling: kleiner leuchtender Sternrochen, den Oruun aus der Leere ruft. Fliegt wie ein Vex
 * (durch Wände, Sturzangriffe auf das Ziel seines Herrn) und verglüht nach einer Weile von selbst.
 * Die ID bleibt `void_debtor`, damit alte Welten nichts verlieren.
 */
class VoidDebtorEntity(type: EntityType<out VoidDebtorEntity>, world: World) : VexEntity(type, world) {

    init {
        experiencePoints = 6
    }

    override fun getAmbientSound(): SoundEvent = VoidSounds.STARLING_AMBIENT
    override fun getHurtSound(source: DamageSource): SoundEvent = VoidSounds.STARLING_HURT
    override fun getDeathSound(): SoundEvent = VoidSounds.STARLING_DEATH
}
