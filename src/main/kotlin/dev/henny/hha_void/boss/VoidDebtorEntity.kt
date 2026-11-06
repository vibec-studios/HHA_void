package dev.henny.hha_void.boss

import net.minecraft.entity.EntityType
import net.minecraft.entity.ai.goal.ActiveTargetGoal
import net.minecraft.entity.ai.goal.LookAroundGoal
import net.minecraft.entity.ai.goal.LookAtEntityGoal
import net.minecraft.entity.ai.goal.MeleeAttackGoal
import net.minecraft.entity.ai.goal.SwimGoal
import net.minecraft.entity.ai.goal.WanderAroundFarGoal
import net.minecraft.entity.mob.HostileEntity
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.sound.SoundEvent
import net.minecraft.sound.SoundEvents
import net.minecraft.world.World

/** Schuldner: frühere Konten Vaels, die für ihn eintreiben. Kleine Diener in den späteren Phasen. */
class VoidDebtorEntity(type: EntityType<out VoidDebtorEntity>, world: World) : HostileEntity(type, world) {

    init {
        experiencePoints = 10
    }

    override fun initGoals() {
        goalSelector.add(0, SwimGoal(this))
        goalSelector.add(2, MeleeAttackGoal(this, 1.15, false))
        goalSelector.add(5, WanderAroundFarGoal(this, 0.9))
        goalSelector.add(6, LookAtEntityGoal(this, PlayerEntity::class.java, 12f))
        goalSelector.add(7, LookAroundGoal(this))
        targetSelector.add(1, ActiveTargetGoal(this, PlayerEntity::class.java, true))
    }

    override fun getAmbientSound(): SoundEvent = SoundEvents.ENTITY_VEX_AMBIENT
    override fun getHurtSound(source: net.minecraft.entity.damage.DamageSource): SoundEvent = SoundEvents.ENTITY_VEX_HURT
    override fun getDeathSound(): SoundEvent = SoundEvents.ENTITY_VEX_DEATH
}
