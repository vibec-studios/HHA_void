package dev.henny.hha_void.logic

import dev.henny.hha.config.HhaConfig
import dev.henny.hha.logic.Targeting
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.entity.LivingEntity
import net.minecraft.entity.effect.StatusEffectInstance
import net.minecraft.entity.effect.StatusEffects
import net.minecraft.entity.projectile.ProjectileUtil
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvents
import net.minecraft.util.hit.HitResult
import net.minecraft.util.math.Box
import net.minecraft.util.math.MathHelper
import net.minecraft.util.math.Vec3d
import net.minecraft.world.RaycastContext
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin

/**
 * Void Sword Aktiv: Sechs Splitter kreisen um den Spieler, solange er das Schwert hält. Jeder
 * Rechtsklick wirft den vordersten: Er fliegt zum Fadenkreuz, durchbohrt alles auf dem Weg und
 * springt von Gegner zu Gegner, bevor er zurückkommt. Erst danach kann er wieder geworfen werden.
 */
object VoidShards {

    const val COUNT = 6
    const val ALL_READY = (1 shl COUNT) - 1

    private const val HIT_PADDING = 0.45
    private const val MAX_LIFE = 240
    private const val CHASE_TICKS = 50
    private const val OVERSHOOT = 5.0

    /** Lage eines kreisenden Splitters relativ zu den Füßen; Server und Client rechnen gleich. */
    fun orbitX(index: Int, time: Float): Float = cos(orbitAngle(index, time)) * ORBIT_RADIUS
    const val ORBIT_HEIGHT = 0.9f
    fun orbitZ(index: Int, time: Float): Float = sin(orbitAngle(index, time)) * ORBIT_RADIUS
    fun orbitAngle(index: Int, time: Float): Float = time * 0.09f + index * (MathHelper.TAU / COUNT)

    private const val ORBIT_RADIUS = 1.5f

    private class Flying(
        val world: ServerWorld,
        val owner: ServerPlayerEntity,
        val slot: Int,
        var pos: Vec3d,
        var aim: Vec3d,
        var target: LivingEntity?,
        var velocity: Vec3d,
        val speed: Double,
        var jumpsLeft: Int,
    ) {
        var returning = false
        var life = MAX_LIFE
        var sinceHit = 0
        val alreadyHit = HashSet<UUID>()
        val fxId = VoidVisuals.newId()
    }

    private val flying = ArrayList<Flying>()

    /** Bereite Splitter pro Spieler als Bitmaske; kein Eintrag heißt: alle da. */
    private val ready = HashMap<UUID, Int>()

    fun readyMask(player: ServerPlayerEntity): Int = ready[player.uuid] ?: ALL_READY

    fun init() {
        ServerTickEvents.END_SERVER_TICK.register { _ ->
            if (flying.isEmpty()) return@register
            val iterator = flying.iterator()
            while (iterator.hasNext()) {
                val shard = iterator.next()
                val gone = shard.owner.isRemoved || !shard.owner.isAlive || shard.owner.entityWorld !== shard.world
                if (gone || --shard.life <= 0 || tickShard(shard)) {
                    VoidVisuals.shardEnd(shard.world, shard.fxId, shard.pos)
                    iterator.remove()
                    giveBack(shard)
                }
            }
        }
    }

    /** Wirft den Splitter, der gerade am ehesten vor dem Spieler ist. @return false, wenn alle unterwegs sind. */
    fun throwNext(world: ServerWorld, player: ServerPlayerEntity): Boolean {
        val mask = readyMask(player)
        if (mask == 0) return false

        val look = player.getRotationVec(1.0f).normalize()
        val time = (world.time % 24000L).toFloat()
        val slot = (0 until COUNT)
            .filter { mask and (1 shl it) != 0 }
            .maxBy { orbitX(it, time) * look.x + orbitZ(it, time) * look.z }
        val start = player.entityPos.add(orbitX(slot, time).toDouble(), ORBIT_HEIGHT.toDouble(), orbitZ(slot, time).toDouble())

        // Ziel ist das Fadenkreuz, begrenzt durch die Reichweite und die erste Wand
        val range = HhaConfig.num("hha_void.shard_range")
        val eye = player.eyePos
        val far = eye.add(look.multiply(range))
        val hit = world.raycast(RaycastContext(eye, far, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, player))
        val aim = if (hit.type == HitResult.Type.MISS) far else hit.pos

        val speed = HhaConfig.num("hha_void.shard_throw_speed")
        val toAim = aim.subtract(start)
        val launch = if (toAim.lengthSquared() > 1e-4) toAim.normalize().multiply(speed) else look.multiply(speed)
        flying += Flying(world, player, slot, start, aim, lookedAt(player, range), launch, speed, HhaConfig.num("hha_void.shard_max_jumps").toInt())
        setMask(world, player, mask and (1 shl slot).inv())

        val used = COUNT - Integer.bitCount(mask)
        world.playSound(null, player.blockPos, SoundEvents.ITEM_TRIDENT_THROW.value(), SoundCategory.PLAYERS, 0.9f, 1.2f + used * 0.1f)
        world.playSound(null, player.blockPos, SoundEvents.BLOCK_AMETHYST_CLUSTER_BREAK, SoundCategory.PLAYERS, 0.5f, 1.4f + used * 0.08f)
        return true
    }

    /** @return true, wenn der Splitter wieder beim Spieler angekommen ist. */
    private fun tickShard(shard: Flying): Boolean {
        val previous = shard.pos
        if (shard.returning) {
            val goal = shard.owner.entityPos.add(0.0, 1.0, 0.0)
            val toGoal = goal.subtract(previous)
            val distance = toGoal.length()
            // Auf dem Rückweg zieht es ihn immer schneller zum Spieler, damit er ihn sicher einholt
            val speed = shard.speed * 1.35
            if (distance <= speed + 0.6) {
                hitAlongPath(shard, previous, goal)
                shard.pos = goal
                shard.world.playSound(null, shard.owner.blockPos, SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 0.7f, 1.5f)
                return true
            }
            shard.pos = previous.add(toGoal.multiply(speed / distance))
            hitAlongPath(shard, previous, shard.pos)
        } else {
            flyOut(shard, previous)
        }
        VoidVisuals.shard(shard.world, shard.fxId, shard.pos, shard.pos.subtract(previous))
        return false
    }

    private fun flyOut(shard: Flying, previous: Vec3d) {
        // Ziel gestorben, oder die Jagd dauert zu lange: das nächste suchen oder umkehren
        val current = shard.target
        if (current != null && (!current.isAlive || current.isRemoved || ++shard.sinceHit > CHASE_TICKS)) {
            shard.target = nextJump(shard)
            shard.sinceHit = 0
            if (shard.target == null) {
                turnAround(shard)
                return
            }
        }

        val target = shard.target
        val goal = target?.let { it.entityPos.add(0.0, it.height * 0.5, 0.0) } ?: shard.aim
        val toGoal = goal.subtract(previous)
        val distance = toGoal.length().coerceAtLeast(0.001)
        if (target == null) {
            // Gerader Flug zum Fadenkreuz
            shard.velocity = toGoal.multiply(shard.speed / distance)
            if (distance <= shard.speed) {
                shard.pos = goal
                hitAlongPath(shard, previous, goal)
                if (!shard.returning && shard.target == null) turnAround(shard)
                return
            }
        } else {
            // Beim Springen schwenkt er in einem Bogen auf das neue Ziel ein
            val desired = toGoal.multiply(1.0 / distance)
            shard.velocity = shard.velocity.multiply(0.6).add(desired.multiply(shard.speed * 0.62)).normalize().multiply(shard.speed)
        }
        shard.pos = previous.add(shard.velocity)
        if (hitAlongPath(shard, previous, shard.pos) == 0 && target != null && shard.pos.distanceTo(goal) < 1.4) {
            registerHit(shard, target)
        }
    }

    private fun turnAround(shard: Flying) {
        shard.returning = true
        shard.target = null
        // Auf dem Rückweg darf er dieselben Gegner noch einmal treffen
        shard.alreadyHit.clear()
    }

    /** @return wie viele Gegner auf diesem Wegstück getroffen wurden. */
    private fun hitAlongPath(shard: Flying, from: Vec3d, to: Vec3d): Int {
        val world = shard.world
        val owner = shard.owner
        val box = Box(from, to).expand(HIT_PADDING + 1.0)
        var hits = 0
        for (target in world.getEntitiesByClass(LivingEntity::class.java, box) {
            it.isAlive && it !== owner && it.uuid !in shard.alreadyHit && Targeting.shouldHarm(owner, it)
        }) {
            val body = target.boundingBox.expand(HIT_PADDING)
            if (!body.contains(from) && body.raycast(from, to).isEmpty) continue
            registerHit(shard, target)
            hits++
        }
        return hits
    }

    private fun registerHit(shard: Flying, target: LivingEntity) {
        val world = shard.world
        shard.alreadyHit += target.uuid
        shard.sinceHit = 0
        target.damage(world, world.damageSources.playerAttack(shard.owner), HhaConfig.numF("hha_void.shard_throw_damage"))
        target.addStatusEffect(StatusEffectInstance(StatusEffects.SLOWNESS, 30, 0, false, true, true))
        VoidVisuals.impact(world, target.entityPos.add(0.0, target.height * 0.5, 0.0), 1.1f)
        world.playSound(null, target.blockPos, SoundEvents.ENTITY_ARROW_HIT_PLAYER, SoundCategory.PLAYERS, 0.6f, 0.6f + shard.alreadyHit.size * 0.12f)
        if (shard.returning) return

        // Weiter zum nächsten Gegner; gibt es keinen, fliegt er noch ein Stück durch und kehrt dann um
        val next = if (shard.jumpsLeft > 0) nextJump(shard) else null
        if (next != null) {
            shard.jumpsLeft--
            shard.target = next
        } else {
            shard.target = null
            val direction = if (shard.velocity.lengthSquared() > 1e-4) shard.velocity.normalize() else Vec3d(0.0, 0.0, 1.0)
            shard.aim = shard.pos.add(direction.multiply(OVERSHOOT))
        }
    }

    private fun nextJump(shard: Flying): LivingEntity? {
        val range = HhaConfig.num("hha_void.shard_chain_range")
        return shard.world.getEntitiesByClass(LivingEntity::class.java, Box.of(shard.pos, range * 2, range * 2, range * 2)) {
            it.isAlive && it !== shard.owner && it.uuid !in shard.alreadyHit &&
                it.squaredDistanceTo(shard.pos) <= range * range && Targeting.shouldHarm(shard.owner, it)
        }.minByOrNull { it.squaredDistanceTo(shard.pos) }
    }

    /** Gegner direkt im Fadenkreuz: Auf ihn lenkt der Splitter, auch wenn er sich bewegt. */
    private fun lookedAt(player: ServerPlayerEntity, range: Double): LivingEntity? {
        val start = player.eyePos
        val end = start.add(player.getRotationVec(1.0f).multiply(range))
        val hit = ProjectileUtil.raycast(
            player, start, end, Box(start, end).expand(1.5),
            { it is LivingEntity && it.isAlive && !it.isSpectator && Targeting.shouldHarm(player, it) },
            range * range,
        )
        return hit?.entity as? LivingEntity
    }

    private fun giveBack(shard: Flying) {
        val owner = shard.owner
        val mask = readyMask(owner) or (1 shl shard.slot)
        setMask(shard.world, owner, mask)
    }

    private fun setMask(world: ServerWorld, player: ServerPlayerEntity, mask: Int) {
        if (mask == ALL_READY) ready.remove(player.uuid) else ready[player.uuid] = mask
        VoidVisuals.shardRing(world, player.id, mask, player.entityPos)
    }
}
