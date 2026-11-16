package dev.henny.hha_void.boss

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
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundEvent
import net.minecraft.storage.ReadView
import net.minecraft.storage.WriteView
import net.minecraft.util.Arm
import net.minecraft.util.math.Box
import net.minecraft.util.math.Vec3d
import net.minecraft.world.World

/**
 * Ein Hitbox-Volumen des Leviathans ([LeviathanBody.VOLUMES]: Kopf, Kiefer, Hals, Rumpf, Schwanz, Fluke, Flossen,
 * Herz, Blasloch). Die Box ist ein beliebiger Quader (nicht quadratisch wie sonst bei Entities) und wird jeden Tick
 * aus den Modellknochen neu gesetzt – auf dem Client aus der Lage, die der Spieler sieht, auf dem Server aus der um
 * die Netzverzögerung zurückliegenden Lage.
 *
 * - Treffer (Nahkampf, Mace-Smash, Projektile, Flächenschaden) leitet es an den Wal weiter
 *   ([VoidBossEntity.damageFromPart]); je Angreifer zählt pro Tick nur ein Volumen.
 * - Auf dem Client fest wie ein Shulker – außer für den Spieler, der gerade auf ihm steht: der läuft auf der glatten
 *   Rückenfläche ([LeviathanBody.Frame.surface], `client/boss/LeviathanClient`), nicht auf den Boxkanten.
 * - Wird nicht gespeichert; der Wal erzeugt fehlende Volumen neu, verwaiste verschwinden von selbst.
 *
 * LivingEntity (statt einfacher Entity), damit Waffen wie die Mace ihren Treffer vollständig abwickeln.
 */
class LeviathanPartEntity(type: EntityType<out LeviathanPartEntity>, world: World) : LivingEntity(type, world) {

    private var orphanTicks = 0

    // halbe Kanten der aktuellen Box (x, z) und volle Höhe
    private var hx = 0.5
    private var hz = 0.5
    private var h = 1.0

    init {
        noClip = true
        setNoGravity(true)
        // sichtbar für F3+B (der Renderer zeichnet nichts); unsichtbare Entities überspringt das Hitbox-Debugbild
        isInvisible = false
    }

    override fun initDataTracker(builder: DataTracker.Builder) {
        super.initDataTracker(builder)
        builder.add(OWNER, -1)
        builder.add(INDEX, 0)
    }

    fun setup(owner: VoidBossEntity, index: Int) {
        dataTracker.set(OWNER, owner.id)
        dataTracker.set(INDEX, index)
    }

    fun ownerId(): Int = dataTracker.get(OWNER)
    fun index(): Int = dataTracker.get(INDEX).coerceIn(0, LeviathanBody.COUNT - 1)
    fun kind(): Int = LeviathanBody.VOLUMES[index()].kind
    fun volumeName(): String = LeviathanBody.VOLUMES[index()].name

    fun boss(): VoidBossEntity? = entityWorld.getEntityById(ownerId()) as? VoidBossEntity

    /** Ist das Volumen gerade da (nichts vor dem Auftritt)? */
    var active = true
        private set

    /** Vom Wal (Server) bzw. vom Client-Abgleich jeden Tick: an die Box setzen (null = nicht da). */
    fun place(box: Box?) {
        if (box == null) {
            active = false
            return
        }
        active = true
        hx = (box.maxX - box.minX) / 2.0
        hz = (box.maxZ - box.minZ) / 2.0
        h = box.maxY - box.minY
        setPosition((box.minX + box.maxX) / 2.0, box.minY, (box.minZ + box.maxZ) / 2.0)
    }

    override fun calculateDefaultBoundingBox(pos: Vec3d): Box = Box(pos.x - hx, pos.y, pos.z - hz, pos.x + hx, pos.y + h, pos.z + hz)

    override fun getBaseDimensions(pose: EntityPose): EntityDimensions = EntityDimensions.changing(1f, 1f)

    override fun tick() {
        // Keine eigene Bewegung, keine Schwerkraft – nur aufräumen, wenn der Wal fehlt
        age++
        if (!entityWorld.isClient) {
            val boss = boss()
            if (boss == null || boss.isRemoved) {
                if (++orphanTicks > 40) discard()
            } else {
                orphanTicks = 0
            }
        }
    }

    // ---------- Kollision und Treffer ----------

    /** Nur auf dem Client fest (der Server prüft Spielerbewegungen nicht gegen leicht versetzte Boxen). */
    override fun isCollidable(other: Entity?): Boolean =
        entityWorld.isClient && active && other !is LeviathanPartEntity && other !is VoidBossEntity &&
            boss()?.partsSolid() == true && !(other != null && passThrough(other))

    override fun canHit(): Boolean = active && boss()?.partsHittable() == true
    override fun isAttackable(): Boolean = canHit()
    override fun isPushable(): Boolean = false
    override fun pushAwayFrom(entity: Entity) = Unit
    override fun pushAway(entity: Entity) = Unit
    override fun takeKnockback(strength: Double, x: Double, z: Double) = Unit
    override fun isAffectedBySplashPotions(): Boolean = false
    override fun canHaveStatusEffect(effect: StatusEffectInstance): Boolean = false
    override fun shouldRenderName(): Boolean = false
    override fun shouldSave(): Boolean = false
    override fun isMobOrPlayer(): Boolean = false
    override fun getMainArm(): Arm = Arm.RIGHT
    override fun getHurtSound(source: DamageSource): SoundEvent? = null
    override fun getDeathSound(): SoundEvent? = null
    override fun isFireImmune(): Boolean = true
    override fun handleFallDamage(fallDistance: Double, damagePerDistance: Float, damageSource: DamageSource) = false

    override fun damage(world: ServerWorld, source: DamageSource, amount: Float): Boolean {
        // /kill trifft nur das Volumen (der Wal erzeugt es neu), nicht den Wal
        if (source.isOf(DamageTypes.GENERIC_KILL) || source.isOf(DamageTypes.OUT_OF_WORLD)) return super.damage(world, source, amount)
        if (!active) return false
        return boss()?.damageFromPart(this, world, source, amount) ?: false
    }

    override fun readCustomData(view: ReadView) {
        super.readCustomData(view)
    }

    override fun writeCustomData(view: WriteView) {
        super.writeCustomData(view)
    }

    companion object {
        private val OWNER: TrackedData<Int> = DataTracker.registerData(LeviathanPartEntity::class.java, TrackedDataHandlerRegistry.INTEGER)
        private val INDEX: TrackedData<Int> = DataTracker.registerData(LeviathanPartEntity::class.java, TrackedDataHandlerRegistry.INTEGER)

        /** Client: wer gerade auf dem Wal steht, läuft auf der Rückenfläche statt auf den Boxen (setzt LeviathanClient). */
        @JvmStatic
        var passThrough: (Entity) -> Boolean = { false }
    }
}
