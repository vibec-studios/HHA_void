package dev.henny.hha_void.boss

import dev.henny.hha_void.VoidSounds
import net.minecraft.entity.Entity
import net.minecraft.entity.EntityDimensions
import net.minecraft.entity.EntityPose
import net.minecraft.entity.EntityType
import net.minecraft.entity.LivingEntity
import net.minecraft.entity.damage.DamageSource
import net.minecraft.entity.damage.DamageTypes
import net.minecraft.entity.data.DataTracker
import net.minecraft.entity.data.TrackedData
import net.minecraft.entity.data.TrackedDataHandlerRegistry
import net.minecraft.entity.effect.StatusEffectInstance
import net.minecraft.registry.tag.DamageTypeTags
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvent
import net.minecraft.sound.SoundEvents
import net.minecraft.storage.ReadView
import net.minecraft.storage.WriteView
import net.minecraft.text.Text
import net.minecraft.util.Arm
import net.minecraft.util.Formatting
import net.minecraft.world.World

/**
 * Organ im Bauch des Leviathans – Ziel eines Abschnitts ([LeviathanBelly]): Würgknoten (Schlund), Magenkern,
 * Ganglion (Gedärme), Lungenbläschen, Herzklappe und das Sternenherz selbst. Steht still, pulsiert, kann zerstört
 * werden. Jeder Treffer trifft zum Teil auch den Wal; zerstörte Organe reißen ihm ein Stück Leben heraus.
 * Das Sternenherz ist geschützt ([shielded]), solange noch eine Herzklappe schlägt.
 * Gezeichnet von `CoreRenderer` (client/boss/LeviathanClient.kt) in der Größe der Rolle ([scale]).
 * Wird nicht gespeichert – die Bauch-Phase endet bei einem Neustart ohnehin.
 */
class LeviathanCoreEntity(type: EntityType<out LeviathanCoreEntity>, world: World) : LivingEntity(type, world) {

    /** Der Wal, dem das Organ gehört (nur Server). */
    var owner: VoidBossEntity? = null

    init {
        setNoGravity(true)
    }

    override fun initDataTracker(builder: DataTracker.Builder) {
        super.initDataTracker(builder)
        builder.add(ROLE, CORE)
        builder.add(SHIELD, false)
    }

    var role: Int
        get() = dataTracker.get(ROLE)
        set(v) {
            dataTracker.set(ROLE, v)
            calculateDimensions()
        }

    var shielded: Boolean
        get() = dataTracker.get(SHIELD)
        set(v) = dataTracker.set(SHIELD, v)

    override fun onTrackedDataSet(data: TrackedData<*>) {
        super.onTrackedDataSet(data)
        if (data == ROLE) calculateDimensions()
    }

    override fun getBaseDimensions(pose: EntityPose): EntityDimensions {
        val k = scale(role)
        return EntityDimensions.changing(2.4f * k, 3.4f * k)
    }

    override fun tick() {
        super.tick()
        velocity = net.minecraft.util.math.Vec3d.ZERO
        if (!entityWorld.isClient) {
            val boss = owner
            if (boss == null || boss.isRemoved || !boss.isAlive) discard()
        }
    }

    override fun damage(world: ServerWorld, source: DamageSource, amount: Float): Boolean {
        if (source.isOf(DamageTypes.GENERIC_KILL)) return super.damage(world, source, amount)
        if (source.isIn(DamageTypeTags.IS_FIRE) || source.isOf(DamageTypes.IN_WALL) || source.isOf(DamageTypes.FALL)) return false
        // Nur Spieler (und ihre Geschosse) verletzen die Organe – nicht die Säure oder die Sternlinge
        val attacker = source.attacker
        if (attacker !is net.minecraft.entity.player.PlayerEntity) return false
        if (shielded) {
            if (age % 10 == 0 || hurtTime == 0) {
                world.playSound(null, x, y, z, SoundEvents.BLOCK_AMETHYST_BLOCK_HIT, SoundCategory.HOSTILE, 2f, 0.5f)
                (attacker as? net.minecraft.server.network.ServerPlayerEntity)?.sendMessage(
                    Text.translatable("hha_void.boss.msg.heart_shielded").formatted(Formatting.GOLD), true,
                )
            }
            return false
        }
        timeUntilRegen = 0
        val dealt = amount.coerceAtMost(40f)
        val hurt = super.damage(world, source, dealt)
        if (hurt) owner?.coreHit(world, this, dealt)
        return hurt
    }

    override fun onDeath(damageSource: DamageSource) {
        super.onDeath(damageSource)
        (entityWorld as? ServerWorld)?.let { owner?.coreDestroyed(it, this) }
    }

    override fun getHurtSound(source: DamageSource): SoundEvent = VoidSounds.WHALE_HURT
    override fun getDeathSound(): SoundEvent = VoidSounds.SUPERNOVA_BLAST
    override fun getSoundPitch(): Float = if (role == HEART) 0.9f else 1.6f
    override fun isPushable() = false
    override fun pushAwayFrom(entity: Entity) = Unit
    override fun takeKnockback(strength: Double, x: Double, z: Double) = Unit
    override fun canHaveStatusEffect(effect: StatusEffectInstance) = false
    override fun isAffectedBySplashPotions() = false
    override fun shouldSave() = false
    override fun getMainArm(): Arm = Arm.RIGHT
    override fun handleFallDamage(fallDistance: Double, damagePerDistance: Float, damageSource: DamageSource) = false
    override fun readCustomData(view: ReadView) = super.readCustomData(view)
    override fun writeCustomData(view: WriteView) = super.writeCustomData(view)

    companion object {
        private val ROLE: TrackedData<Int> = DataTracker.registerData(LeviathanCoreEntity::class.java, TrackedDataHandlerRegistry.INTEGER)
        private val SHIELD: TrackedData<Boolean> = DataTracker.registerData(LeviathanCoreEntity::class.java, TrackedDataHandlerRegistry.BOOLEAN)

        const val KNOT = 0
        const val CORE = 1
        const val GANGLION = 2
        const val ALVEOLUS = 3
        const val VALVE = 4
        const val HEART = 5

        /** Größe der Rolle relativ zum Kern-Modell (2,4 × 3,4 Blöcke). */
        fun scale(role: Int): Float = when (role) {
            KNOT -> 0.85f
            GANGLION -> 0.8f
            ALVEOLUS -> 1.0f
            VALVE -> 0.9f
            HEART -> 2.2f
            else -> 1f
        }

        fun nameKey(role: Int): String = when (role) {
            KNOT -> "knot"; GANGLION -> "ganglion"; ALVEOLUS -> "alveolus"; VALVE -> "valve"; HEART -> "heart"; else -> "core"
        }
    }
}
