package dev.henny.hha_void.boss

import dev.henny.hha_void.logic.TickScheduler
import dev.henny.hha_void.logic.VoidVisuals
import net.minecraft.entity.Entity
import net.minecraft.entity.EntityType
import net.minecraft.entity.LivingEntity
import net.minecraft.entity.boss.BossBar
import net.minecraft.entity.boss.ServerBossBar
import net.minecraft.entity.damage.DamageSource
import net.minecraft.entity.damage.DamageTypes
import net.minecraft.entity.data.DataTracker
import net.minecraft.entity.data.TrackedData
import net.minecraft.entity.data.TrackedDataHandlerRegistry
import net.minecraft.entity.effect.StatusEffectInstance
import net.minecraft.entity.effect.StatusEffects
import net.minecraft.entity.mob.HostileEntity
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket
import net.minecraft.network.packet.s2c.play.TitleS2CPacket
import net.minecraft.registry.tag.DamageTypeTags
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvent
import net.minecraft.sound.SoundEvents
import net.minecraft.storage.ReadView
import net.minecraft.storage.WriteView
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import net.minecraft.world.World
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Vael, der Letzte Gläubiger. Alles, was existiert, hat sich vom Nichts geliehen;
 * er ist gekommen, um zu kassieren. Daraus folgen seine Mechaniken:
 * - Schuld: Treffer von ihm erhöhen deine Schuld. Bei [MAX_DEBT] wird sie fällig.
 * - Abzahlung: Schaden an ihm tilgt Schuld.
 * - Raten: Er nimmt pro Treffer höchstens [MAX_INSTALLMENT] Schaden an.
 */
class VoidBossEntity(type: EntityType<out VoidBossEntity>, world: World) : HostileEntity(type, world) {

    private val bossBar = ServerBossBar(displayName, BossBar.Color.PURPLE, BossBar.Style.NOTCHED_10).also {
        it.setDarkenSky(true)
    }

    /** Geplanter Angriff: läuft [duration] Ticks und ruft jeden Tick [body] mit dem Alter auf. */
    private class Cast(val pose: Int, val duration: Int, val body: (Int) -> Unit) {
        var age = 0
    }

    /** Lenkender Riss-Splitter: rein serverseitig simuliert, Client zeichnet über SHARD-Pakete. */
    private class Bolt(var pos: Vec3d, var velocity: Vec3d, val target: LivingEntity, var life: Int, val fxId: Int)

    /** Mitte des Arena-Bodens; null, wenn der Boss ohne Tribunal beschworen wurde. */
    var arena: BlockPos? = null
    private var homePoint: Vec3d? = null

    private var phase = 1
    private var intro = INTRO_TICKS

    /** Solange eine Cutscene läuft, ruht der Kampf und niemand kann getroffen werden. */
    private var cutscene = 0
    private var debtorsAfterCut = 0

    /** Vael ist gerade im Riss verschwunden und nicht zu treffen. */
    private var inRift = false
    private var landed = false
    private var cast: Cast? = null
    private var recovery = 60
    private var lastCast = -1
    private var introGround: Vec3d? = null
    private var nextLine = 200
    private var singularityReady = 0
    private var ultimateReady = 0
    private var meleeCooldown = 0
    private var lonelyTicks = 0

    private val bolts = ArrayList<Bolt>()
    private val debt = HashMap<UUID, Int>()
    private val paid = HashMap<UUID, Float>()
    private val beamHits = HashMap<UUID, Int>()

    private var lastStepPos = Vec3d.ZERO
    private var stuckTicks = 0
    private var wasOnGround = true

    /** Falscher Tod: tickt hoch, bis er in der letzten Phase zurückkehrt; 0 = läuft nicht. */
    private var rebirth = 0
    private var reborn = false

    private var holeCenter: Vec3d? = null
    private var holeId = 0
    private var holeTicks = 0

    init {
        setPersistent()
        experiencePoints = 500
    }

    override fun initDataTracker(builder: DataTracker.Builder) {
        super.initDataTracker(builder)
        builder.add(POSE, Poses.HOLD)
        builder.add(PHASE, 1)
    }

    /** Haltungen des Modells; pro Fähigkeit eine eigene Animation. */
    object Poses {
        const val IDLE = 0
        const val VERDICT = 1
        const val VOLLEY = 2
        const val COLLECT = 3
        const val SEIZURE = 4
        const val SWEEP = 5
        const val SINGULARITY = 6
        const val REMISSION = 8
        const val ROAR = 9
        const val INTRO = 10
        const val DEATH = 11
        const val FALSE_DEATH = 12
        const val REASSEMBLE = 13
        const val SKY = 14
        const val FISSURE = 15
        const val HOLD = 16
        const val FALL = 17
        const val LAND = 18
        const val RIP = 19
        const val EXIT = 20
        const val REACH = 21
        const val STIR = 22
    }

    fun castPose(): Int = dataTracker.get(POSE)

    // Nur auf dem Client: wann die aktuelle Haltung begann, für weiche Übergänge
    private var clientPose = Poses.HOLD
    private var clientPoseStart = 0
    private var clientPrevious = Poses.IDLE
    private var clientPreviousStart = 0

    fun clientPoseAge(tickDelta: Float): Float = age - clientPoseStart + tickDelta
    fun clientPreviousPose(): Int = clientPrevious
    fun clientPreviousPoseAge(tickDelta: Float): Float = age - clientPreviousStart + tickDelta

    override fun tick() {
        super.tick()
        if (entityWorld.isClient) {
            val pose = castPose()
            if (pose != clientPose) {
                clientPrevious = clientPose
                clientPreviousStart = clientPoseStart
                clientPose = pose
                clientPoseStart = age
            }
        }
    }

    fun bossPhase(): Int = dataTracker.get(PHASE)

    // ---------- Speichern ----------

    override fun writeCustomData(view: WriteView) {
        super.writeCustomData(view)
        view.putInt("Phase", phase)
        view.putBoolean("Reborn", reborn)
        view.putInt("Rebirth", rebirth)
        view.putBoolean("HasArena", arena != null)
        arena?.let {
            view.putInt("ArenaX", it.x)
            view.putInt("ArenaY", it.y)
            view.putInt("ArenaZ", it.z)
        }
    }

    override fun readCustomData(view: ReadView) {
        super.readCustomData(view)
        phase = view.getInt("Phase", 1)
        reborn = view.getBoolean("Reborn", false)
        rebirth = view.getInt("Rebirth", 0)
        dataTracker.set(PHASE, phase)
        arena = if (view.getBoolean("HasArena", false)) {
            BlockPos(view.getInt("ArenaX", 0), view.getInt("ArenaY", 0), view.getInt("ArenaZ", 0))
        } else {
            null
        }
        intro = 0
        setNoGravity(false)
        dataTracker.set(POSE, Poses.IDLE)
        if (phase >= 4) {
            bossBar.name = Text.translatable("entity.hha_void.void_boss_reborn").formatted(Formatting.DARK_RED, Formatting.BOLD)
            bossBar.color = BossBar.Color.RED
        } else if (hasCustomName()) {
            bossBar.name = displayName
        }
    }

    // ---------- Bossbar und Grundverhalten ----------

    override fun onStartedTrackingBy(player: ServerPlayerEntity) {
        super.onStartedTrackingBy(player)
        bossBar.addPlayer(player)
    }

    override fun onStoppedTrackingBy(player: ServerPlayerEntity) {
        super.onStoppedTrackingBy(player)
        bossBar.removePlayer(player)
    }

    override fun checkDespawn() = Unit
    override fun canUsePortals(allowVehicles: Boolean) = false
    override fun isPushable() = false
    override fun canHit() = true
    override fun canImmediatelyDespawn(distanceSquared: Double) = false
    override fun getAmbientSound(): SoundEvent = SoundEvents.ENTITY_WARDEN_AMBIENT
    override fun getHurtSound(source: DamageSource): SoundEvent = SoundEvents.BLOCK_AMETHYST_BLOCK_RESONATE
    override fun getDeathSound(): SoundEvent = SoundEvents.ENTITY_WITHER_DEATH
    override fun handleFallDamage(fallDistance: Double, damagePerDistance: Float, damageSource: DamageSource) = false

    override fun damage(world: ServerWorld, source: DamageSource, amount: Float): Boolean {
        // /kill und ähnliche Befehle sollen immer durchgehen
        if (source.isOf(DamageTypes.GENERIC_KILL)) return super.damage(world, source, amount)
        if (intro > 0 || cutscene > 0 || rebirth > 0 || inRift) return false
        if (source.isIn(DamageTypeTags.IS_FIRE) || source.isOf(DamageTypes.WITHER) || source.isOf(DamageTypes.IN_WALL)) return false
        if (source.isOf(DamageTypes.OUT_OF_WORLD)) {
            home()?.let { refreshPositionAndAngles(it.x, it.y, it.z, yaw, pitch) }
            return false
        }
        // Er nimmt nur Raten an: Ein einzelner Treffer kann ihn nie ganz bezahlen
        val installment = min(amount, MAX_INSTALLMENT)
        val hurt = super.damage(world, source, installment)
        val attacker = source.attacker
        if (hurt && attacker is ServerPlayerEntity) repay(attacker, installment)
        return hurt
    }

    private fun home(): Vec3d {
        arena?.let { return Vec3d(it.x + 0.5, it.y + 6.0, it.z + 0.5) }
        return homePoint ?: entityPos.also { homePoint = it }
    }

    private fun groundCenter(): Vec3d {
        arena?.let { return Vec3d(it.x + 0.5, it.y + 1.0, it.z + 0.5) }
        return home().add(0.0, -6.0, 0.0)
    }

    private fun players(world: ServerWorld, range: Double = ARENA_RANGE): List<ServerPlayerEntity> =
        world.players.filter { it.isAlive && !it.isSpectator && it.squaredDistanceTo(home()) <= range * range }

    private fun targets(world: ServerWorld): List<ServerPlayerEntity> =
        players(world).filter { !it.isCreative }

    // ---------- Der Takt des Kampfes ----------

    override fun mobTick(world: ServerWorld) {
        super.mobTick(world)
        if (rebirth > 0) {
            tickRebirth(world)
            return
        }
        bossBar.percent = health / maxHealth

        tickBolts(world)
        tickSingularity(world)

        if (intro > 0) {
            tickIntro(world)
            return
        }
        if (cutscene > 0) {
            velocity = Vec3d(0.0, velocity.y, 0.0)
            velocityDirty = true
            if (--cutscene == 0) {
                dataTracker.set(POSE, Poses.IDLE)
                if (debtorsAfterCut > 0) summonDebtors(world, debtorsAfterCut)
                debtorsAfterCut = 0
            }
            return
        }

        val everyone = players(world)
        if (everyone.isEmpty()) {
            if (++lonelyTicks > 600 && health < maxHealth) {
                health = maxHealth
                setPhase(world, 1, announce = false)
                debt.clear()
                bolts.clear()
                cast = null
                if (inRift) {
                    inRift = false
                    dataTracker.set(POSE, Poses.IDLE)
                }
            }
            move(world, null)
            return
        }
        lonelyTicks = 0

        val targetsNow = targets(world)
        val target = targetsNow.minByOrNull { it.squaredDistanceTo(this) }
        move(world, target)

        checkPhase(world)
        if (cutscene > 0) return

        if (--nextLine <= 0) {
            nextLine = 340 + random.nextInt(320)
            say(world, "idle${random.nextInt(IDLE_LINES)}")
        }
        if (singularityReady > 0) singularityReady--
        if (ultimateReady > 0) ultimateReady--
        if (meleeCooldown > 0) meleeCooldown--

        if (target != null && meleeCooldown == 0 && target.squaredDistanceTo(this) < 42.0) melee(world, target)

        val running = cast
        if (running != null) {
            running.body(running.age)
            if (++running.age >= running.duration) {
                cast = null
                dataTracker.set(POSE, Poses.IDLE)
                recovery = if (phase >= 4) 16 else if (phase == 3) 22 else if (phase == 2) 34 else 46
            }
            return
        }
        if (target == null) return
        if (--recovery <= 0) chooseCast(world, target)
    }

    private fun checkPhase(world: ServerWorld) {
        if (reborn) return
        val ratio = health / maxHealth
        val wanted = if (ratio > 0.66f) 1 else if (ratio > 0.33f) 2 else 3
        if (wanted > phase) setPhase(world, wanted, announce = true)
    }

    private fun setPhase(world: ServerWorld, next: Int, announce: Boolean) {
        phase = next
        dataTracker.set(PHASE, next)
        if (!announce) return

        cast = null
        inRift = false
        endBolts(world)
        endHole(world)
        dataTracker.set(POSE, Poses.ROAR)
        say(world, "phase$next")
        VoidVisuals.nova(world, entityPos.add(0.0, 2.5, 0.0), 3.5f)
        sound(world, SoundEvents.ENTITY_WARDEN_SONIC_BOOM, 1.4f, 0.6f)
        sound(world, SoundEvents.BLOCK_END_PORTAL_SPAWN, 1.0f, 0.5f)
        VoidVisuals.crackStar(world, entityPos, if (next == 2) 5 else 7, if (next == 2) 10.0 else 14.0, 10, 100)
        debtorsAfterCut = if (next == 2) 3 else 5
        playCutscene(world, if (next == 2) SCENE_PHASE_2 else SCENE_PHASE_3, PHASE_CUT_TICKS, entityPos)
        cutscene = PHASE_CUT_TICKS
    }

    /** Spieler sind während der Szene eingefroren, darum schützt sie ein unsichtbarer Schild. */
    private fun playCutscene(world: ServerWorld, scene: Int, ticks: Int, anchor: Vec3d) {
        VoidVisuals.cutscene(world, scene, id, anchor, yaw)
        for (player in players(world, 128.0)) {
            player.addStatusEffect(StatusEffectInstance(StatusEffects.RESISTANCE, ticks + 10, 4, false, false, false))
        }
    }

    private fun endHole(world: ServerWorld) {
        holeCenter?.let { VoidVisuals.blackHoleEnd(world, holeId, it) }
        holeCenter = null
    }

    private fun endBolts(world: ServerWorld) {
        for (bolt in bolts) VoidVisuals.shardEnd(world, bolt.fxId, bolt.pos)
        bolts.clear()
    }

    /** Auftritt: Der Himmel reißt auf, Vael stürzt heraus, schlägt ein und richtet sich auf. */
    private fun tickIntro(world: ServerWorld) {
        val t = INTRO_TICKS - intro
        intro--
        val ground = home().let { if (arena != null) groundCenter() else introGround ?: entityPos.also { p -> introGround = p } }
        if (t == 0) {
            val nearest = world.players.filter { it.squaredDistanceTo(ground) < 128.0 * 128.0 }.minByOrNull { it.squaredDistanceTo(ground) }
            if (nearest != null) {
                val look = nearest.entityPos.subtract(ground)
                val faced = (Math.toDegrees(atan2(look.z, look.x)) - 90.0).toFloat()
                yaw = faced
                bodyYaw = faced
                headYaw = faced
            }
            setNoGravity(true)
            refreshPositionAndAngles(ground.x, ground.y + INTRO_HEIGHT, ground.z, yaw, 0f)
            VoidVisuals.rift(world, ground.add(0.0, INTRO_HEIGHT + 2.0, 0.0), Vec3d(1.0, 0.0, 0.25), 30f, INTRO_DROP + 26)
            playCutscene(world, SCENE_INTRO, INTRO_TICKS, ground)
            sound(world, ground, SoundEvents.BLOCK_END_PORTAL_SPAWN, 1.6f, 0.5f)
            sound(world, ground, SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, 1.4f, 0.5f)
        }
        when {
            t < INTRO_DROP -> {
                velocity = Vec3d.ZERO
                velocityDirty = true
            }
            t == INTRO_DROP -> {
                setNoGravity(false)
                dataTracker.set(POSE, Poses.FALL)
                sound(world, ground, SoundEvents.ENTITY_ENDER_DRAGON_FLAP, 1.6f, 0.5f)
            }
            !landed -> {
                velocity = Vec3d(0.0, -2.3, 0.0)
                velocityDirty = true
                if (t > INTRO_DROP + 30) refreshPositionAndAngles(ground.x, ground.y, ground.z, yaw, 0f)
                if (isOnGround || t > INTRO_DROP + 30) {
                    landed = true
                    wasOnGround = true
                    dataTracker.set(POSE, Poses.LAND)
                    stomp(world, 1f)
                    VoidVisuals.nova(world, entityPos.add(0.0, 0.6, 0.0), 4.5f)
                    VoidVisuals.crackStar(world, entityPos, 7, 12.0, 7, 90)
                    sound(world, SoundEvents.ENTITY_GENERIC_EXPLODE.value(), 1.7f, 0.5f)
                    sound(world, SoundEvents.ENTITY_WARDEN_SONIC_BOOM, 1.2f, 0.5f)
                }
            }
        }
        if (t == INTRO_ROAR) {
            dataTracker.set(POSE, Poses.ROAR)
            say(world, "awaken")
            sound(world, SoundEvents.ENTITY_WARDEN_ROAR, 1.8f, 0.5f)
            sound(world, SoundEvents.ENTITY_ENDER_DRAGON_GROWL, 1.5f, 0.6f)
            VoidVisuals.nova(world, entityPos.add(0.0, 4.0, 0.0), 3.5f)
        }
        if (intro == 0) dataTracker.set(POSE, Poses.IDLE)
    }

    /** Geht wie ein Golem: läuft am Boden, bleibt bei schweren Angriffen stehen. */
    private fun move(world: ServerWorld, target: LivingEntity?) {
        val center = home()
        val ground = groundCenter()

        // Durch ein Loch im Tribunal gefallen: zurück auf den Boden der Arena
        if (y < ground.y - 8.0) {
            refreshPositionAndAngles(center.x, ground.y + 1.0, center.z, yaw, pitch)
            velocity = Vec3d.ZERO
            return
        }

        val pose = castPose()
        val walking = intro <= 0 && (pose == Poses.IDLE || pose == Poses.VOLLEY)
        val toCenter = intro <= 0 && (pose == Poses.REMISSION || pose == Poses.SWEEP)
        val goal: Vec3d? = when {
            toCenter -> center
            walking && target != null -> {
                val fromHome = target.entityPos.subtract(center).let { Vec3d(it.x, 0.0, it.z) }
                if (fromHome.length() > 21.0) center.add(fromHome.normalize().multiply(21.0)) else target.entityPos
            }
            else -> null
        }

        var moving = false
        if (goal != null) {
            val delta = Vec3d(goal.x - x, 0.0, goal.z - z)
            val stopAt = if (toCenter) 1.0 else 5.5
            if (delta.length() > stopAt) {
                val speed = if (phase >= 4) 0.24 else if (phase == 3) 0.2 else 0.16
                val step = delta.normalize().multiply(speed)
                velocity = Vec3d(step.x, velocity.y, step.z)
                velocityDirty = true
                moving = true
            }
        }
        if (!moving) {
            velocity = Vec3d(velocity.x * 0.5, velocity.y, velocity.z * 0.5)
            velocityDirty = true
        }

        // An einer Säule hängen geblieben: mit einem Satz drüber
        if (moving && isOnGround && squaredDistanceTo(lastStepPos) < 0.0004) {
            if (++stuckTicks > 24) {
                velocity = velocity.add(0.0, 1.05, 0.0)
                stuckTicks = 0
                sound(world, SoundEvents.ENTITY_IRON_GOLEM_ATTACK, 1.2f, 0.5f)
            }
        } else {
            stuckTicks = 0
        }
        lastStepPos = entityPos

        // Schritte und Landungen spürt man
        if (moving && isOnGround && age % 14 == 0) stomp(world, 0.16f)
        if (isOnGround && !wasOnGround) stomp(world, 0.8f)
        wasOnGround = isOnGround

        val faceTo = target?.entityPos ?: goal
        if (faceTo != null) {
            val look = faceTo.subtract(entityPos)
            val wantedYaw = (Math.toDegrees(atan2(look.z, look.x)) - 90.0).toFloat()
            val turned = yaw + net.minecraft.util.math.MathHelper.wrapDegrees(wantedYaw - yaw) * 0.14f
            yaw = turned
            bodyYaw = turned
            headYaw = turned
        }
    }

    private fun stomp(world: ServerWorld, strength: Float) {
        sound(world, SoundEvents.ENTITY_IRON_GOLEM_STEP, 0.7f + strength, 0.5f)
        val under = blockPos.down()
        val state = world.getBlockState(under)
        if (!state.isAir) {
            world.spawnParticles(
                net.minecraft.particle.BlockStateParticleEffect(net.minecraft.particle.ParticleTypes.BLOCK, state),
                x, y + 0.2, z, (8 + strength * 24).toInt(), 1.1, 0.1, 1.1, 0.1,
            )
        }
        VoidVisuals.stomp(world, entityPos, strength)
    }

    // ---------- Angriffe ----------

    private fun chooseCast(world: ServerWorld, target: ServerPlayerEntity) {
        if (phase >= 3 && health / maxHealth < (if (reborn) 0.5f else 0.2f) && ultimateReady <= 0) {
            ultimateReady = if (reborn) 700 else 1400
            start(remission(world))
            return
        }

        val options = ArrayList<Int>()
        options += PILLARS
        options += VOLLEY
        options += COLLECT
        options += REACH
        if (phase >= 2) {
            options += RIFT_STEP
            options += SEIZURE
            options += SWEEP
            if (singularityReady <= 0) options += SINGULARITY
        }
        if (reborn) {
            // In der letzten Phase kommen die neuen Angriffe doppelt so oft
            options += SKY_STRIKE
            options += SKY_STRIKE
            options += FISSURE
            options += FISSURE
            options += RIFT_RAIN
            options += RIFT_RAIN
        }
        options.remove(lastCast)
        val pick = options[random.nextInt(options.size)]
        lastCast = pick

        start(
            when (pick) {
                PILLARS -> verdictPillars(world)
                VOLLEY -> riftVolley(world)
                COLLECT -> collection(world, target)
                SEIZURE -> seizure(world)
                SWEEP -> erasureSweep(world)
                SINGULARITY -> summonSingularity(world)
                SKY_STRIKE -> skyStrikes(world)
                FISSURE -> fissure(world, target)
                REACH -> riftGrasp(world, target)
                RIFT_STEP -> riftStep(world, target)
                RIFT_RAIN -> riftRain(world)
                else -> verdictPillars(world)
            }
        )
    }

    private fun start(next: Cast) {
        cast = next
        dataTracker.set(POSE, next.pose)
    }

    /** Schuldsäulen: Unter jedem Ziel kündigt sich ein Kreis an, dann bricht eine Kristallsäule heraus. */
    private fun verdictPillars(world: ServerWorld): Cast {
        val waves = if (phase == 3) 5 else 3
        sound(world, SoundEvents.ENTITY_EVOKER_PREPARE_ATTACK, 1.2f, 0.6f)
        return Cast(Poses.VERDICT, waves * 14 + 10) { age ->
            if (age % 14 == 0) {
                for (player in targets(world)) {
                    val aim = player.entityPos.add(player.velocity.x * 12.0, 0.0, player.velocity.z * 12.0)
                    val ground = findGround(world, aim) ?: continue
                    pillarAt(world, ground)
                    if (phase >= 2) {
                        val a = random.nextDouble() * Math.PI * 2.0
                        findGround(world, aim.add(cos(a) * 4.0, 0.0, sin(a) * 4.0))?.let { pillarAt(world, it) }
                    }
                }
            }
        }
    }

    private fun pillarAt(world: ServerWorld, ground: Vec3d, delay: Int = 16, light: Boolean = false) {
        VoidVisuals.pillar(world, ground, delay, 6.5f, light)
        TickScheduler.schedule(delay) {
            if (!isAlive) return@schedule
            sound(world, ground, SoundEvents.BLOCK_AMETHYST_CLUSTER_BREAK, 1.2f, 0.5f)
            sound(world, ground, SoundEvents.ENTITY_EVOKER_FANGS_ATTACK, 1.0f, 0.7f)
            for (player in players(world)) {
                val dx = player.x - ground.x
                val dz = player.z - ground.z
                if (dx * dx + dz * dz < 5.5 && player.y in ground.y - 1.0..ground.y + 8.0) {
                    strike(world, player, 12f)
                    launch(player, Vec3d(dx * 0.1, 1.05, dz * 0.1))
                }
            }
        }
    }

    /** Rissalve: Hinter ihm reißt die Luft an drei Stellen auf, daraus schießen lenkende Splitter. */
    private fun riftVolley(world: ServerWorld): Cast {
        val shots = if (phase == 3) 10 else if (phase == 2) 8 else 6
        val duration = shots * 5 + 8
        val back = Vec3d(-sin(Math.toRadians(yaw.toDouble())), 0.0, cos(Math.toRadians(yaw.toDouble()))).multiply(-1.0)
        val side = Vec3d(-back.z, 0.0, back.x)
        val ports = listOf(
            entityPos.add(back.multiply(2.5)).add(side.multiply(5.5)).add(0.0, 6.0, 0.0),
            entityPos.add(back.multiply(4.0)).add(0.0, 8.5, 0.0),
            entityPos.add(back.multiply(2.5)).add(side.multiply(-5.5)).add(0.0, 6.0, 0.0),
        )
        for ((index, port) in ports.withIndex()) {
            VoidVisuals.rift(world, port, Vec3d(side.x * 0.3 * (index - 1), 1.0, side.z * 0.3 * (index - 1)), 5.5f, duration + 6)
        }
        sound(world, SoundEvents.BLOCK_AMETHYST_CLUSTER_BREAK, 1.3f, 0.5f)
        return Cast(Poses.VOLLEY, duration) { age ->
            if (age % 5 == 0 && age / 5 < shots) {
                val all = targets(world)
                if (all.isNotEmpty()) {
                    val target = all[(age / 5) % all.size]
                    val origin = ports[(age / 5) % ports.size]
                    val out = origin.subtract(entityPos.add(0.0, 4.0, 0.0)).normalize().multiply(0.7)
                    bolts += Bolt(origin, out, target, 120, VoidVisuals.newId())
                    sound(world, origin, SoundEvents.ENTITY_SHULKER_SHOOT, 0.9f, 0.6f + random.nextFloat() * 0.3f)
                }
            }
        }
    }

    /** Rissgriff: Er rammt den Arm in einen Riss vor sich; die Faust kommt über dem Ziel heraus. */
    private fun riftGrasp(world: ServerWorld, target: ServerPlayerEntity): Cast {
        val rad = Math.toRadians(yaw.toDouble())
        val forward = Vec3d(-sin(rad), 0.0, cos(rad))
        val right = Vec3d(-cos(rad), 0.0, -sin(rad))
        return Cast(Poses.REACH, 48) { age ->
            if (age == 8) {
                sound(world, SoundEvents.BLOCK_AMETHYST_BLOCK_RESONATE, 1.4f, 0.5f)
                VoidVisuals.rift(world, entityPos.add(forward.multiply(5.2)).add(right.multiply(2.3)).add(0.0, 4.3, 0.0), Vec3d(right.x * 0.2, 1.0, right.z * 0.2), 6.5f, 34)
                val aim = target.entityPos.add(target.velocity.x * 12.0, 0.0, target.velocity.z * 12.0)
                findGround(world, aim)?.let { ground ->
                    val delay = 12
                    VoidVisuals.riftFist(world, ground, delay, FIST_HEIGHT)
                    TickScheduler.schedule(delay + 3) {
                        if (!isAlive) return@schedule
                        sound(world, ground, SoundEvents.ENTITY_GENERIC_EXPLODE.value(), 1.2f, 0.7f)
                        sound(world, ground, SoundEvents.ITEM_MACE_SMASH_GROUND_HEAVY, 1.4f, 0.6f)
                        for (player in players(world)) {
                            val dx = player.x - ground.x
                            val dz = player.z - ground.z
                            if (dx * dx + dz * dz < 13.0 && player.y in ground.y - 1.0..ground.y + 6.0) {
                                strike(world, player, 15f)
                                launch(player, Vec3d(dx * 0.14, 0.75, dz * 0.14))
                            }
                        }
                    }
                }
            }
        }
    }

    /** Rissschritt: Er reißt die Luft auf, verschwindet und bricht neben dem Ziel wieder hervor. */
    private fun riftStep(world: ServerWorld, target: ServerPlayerEntity): Cast {
        val rad = Math.toRadians(yaw.toDouble())
        val forward = Vec3d(-sin(rad), 0.0, cos(rad))
        var exitGround: Vec3d? = null
        return Cast(Poses.RIP, 66) { age ->
            if (age == 9) {
                VoidVisuals.rift(world, entityPos.add(forward.multiply(3.0)).add(0.0, 4.0, 0.0), Vec3d(0.0, 1.0, 0.0), 9f, 22)
                sound(world, SoundEvents.ITEM_TRIDENT_THUNDER.value(), 1.3f, 0.5f)
            }
            if (age == 20) {
                inRift = true
                dataTracker.set(POSE, Poses.HOLD)
                sound(world, SoundEvents.ENTITY_ENDERMAN_TELEPORT, 1.5f, 0.5f)
                val from = target.entityPos
                val behind = Vec3d(-sin(Math.toRadians(target.yaw.toDouble())), 0.0, cos(Math.toRadians(target.yaw.toDouble()))).multiply(-5.0)
                val center = home()
                var spot = from.add(behind)
                val away = Vec3d(spot.x - center.x, 0.0, spot.z - center.z)
                if (away.length() > 21.0) spot = center.add(away.normalize().multiply(21.0))
                val ground = findGround(world, spot) ?: findGround(world, from) ?: entityPos
                exitGround = ground
                VoidVisuals.rift(world, ground.add(0.0, 3.8, 0.0), Vec3d(0.0, 1.0, 0.0), 9f, 26)
                VoidVisuals.pillar(world, ground, 18, 0f, light = true)
                sound(world, ground, SoundEvents.BLOCK_AMETHYST_BLOCK_RESONATE, 1.6f, 0.6f)
            }
            if (age == 38) {
                val ground = exitGround ?: entityPos
                refreshPositionAndAngles(ground.x, ground.y, ground.z, yaw, 0f)
                velocity = Vec3d.ZERO
                val look = target.entityPos.subtract(ground)
                val faced = (Math.toDegrees(atan2(look.z, look.x)) - 90.0).toFloat()
                yaw = faced
                bodyYaw = faced
                headYaw = faced
                inRift = false
                dataTracker.set(POSE, Poses.EXIT)
            }
            if (age == 42) {
                stomp(world, 1f)
                VoidVisuals.crackStar(world, entityPos, 5, 7.0, 5, 50)
                sound(world, SoundEvents.ENTITY_GENERIC_EXPLODE.value(), 1.2f, 0.6f)
                for (player in players(world)) {
                    val dx = player.x - x
                    val dz = player.z - z
                    if (dx * dx + dz * dz < 44.0 && player.y in y - 1.0..y + 5.0) {
                        strike(world, player, 13f)
                        launch(player, Vec3d(dx * 0.11, 0.7, dz * 0.11))
                    }
                }
            }
        }
    }

    /** Rissregen: Die Risse im Himmel spucken Felsbrocken auf alle Ziele. */
    private fun riftRain(world: ServerWorld): Cast {
        say(world, "rain")
        sound(world, SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, 1.4f, 0.5f)
        return Cast(Poses.SKY, 66) { age ->
            if (age % 5 == 3) {
                for (player in targets(world)) {
                    val a = random.nextDouble() * Math.PI * 2.0
                    val spread = if (age % 15 == 3) 0.0 else 3.0 + random.nextDouble() * 6.0
                    val aim = player.entityPos.add(cos(a) * spread + player.velocity.x * 10.0, 0.0, sin(a) * spread + player.velocity.z * 10.0)
                    val ground = findGround(world, aim) ?: continue
                    val delay = 20
                    VoidVisuals.riftRock(world, ground, delay)
                    TickScheduler.schedule(delay) {
                        if (!isAlive) return@schedule
                        sound(world, ground, SoundEvents.ENTITY_GENERIC_EXPLODE.value(), 0.6f, 0.9f)
                        for (other in players(world)) {
                            val dx = other.x - ground.x
                            val dz = other.z - ground.z
                            if (dx * dx + dz * dz < 6.5 && other.y in ground.y - 1.0..ground.y + 4.0) {
                                strike(world, other, 8f)
                                launch(other, Vec3d(dx * 0.12, 0.5, dz * 0.12))
                            }
                        }
                    }
                }
            }
        }
    }

    private fun tickBolts(world: ServerWorld) {
        if (bolts.isEmpty()) return
        val iterator = bolts.iterator()
        while (iterator.hasNext()) {
            val bolt = iterator.next()
            if (!bolt.target.isAlive || --bolt.life <= 0) {
                VoidVisuals.shardEnd(world, bolt.fxId, bolt.pos)
                iterator.remove()
                continue
            }
            val aim = bolt.target.entityPos.add(0.0, bolt.target.height * 0.5, 0.0)
            val desired = aim.subtract(bolt.pos).normalize()
            bolt.velocity = bolt.velocity.multiply(0.88).add(desired.multiply(0.14)).normalize().multiply(0.72)
            bolt.pos = bolt.pos.add(bolt.velocity)
            VoidVisuals.shard(world, bolt.fxId, bolt.pos, bolt.velocity)
            if (bolt.pos.squaredDistanceTo(aim) < 1.6) {
                strike(world, bolt.target, 7f)
                VoidVisuals.impact(world, aim, 1.4f)
                VoidVisuals.shardEnd(world, bolt.fxId, bolt.pos)
                iterator.remove()
            }
        }
    }

    /** Inkasso: Eine Kette zerrt das Ziel heran und zieht Leben ab, das Vael sich gutschreibt. */
    private fun collection(world: ServerWorld, target: ServerPlayerEntity): Cast {
        sound(world, SoundEvents.ITEM_TRIDENT_RIPTIDE_1.value(), 1.2f, 0.5f)
        VoidVisuals.chain(world, id, target.id, entityPos, 44)
        return Cast(Poses.COLLECT, 44) { age ->
            if (target.isAlive && target.squaredDistanceTo(this) > 12.0) {
                val pull = entityPos.add(0.0, 1.0, 0.0).subtract(target.entityPos).normalize().multiply(0.36)
                launch(target, pull)
            }
            if (age % 12 == 11 && target.isAlive) {
                strike(world, target, 4f)
                heal(10f)
                sound(world, target.entityPos, SoundEvents.BLOCK_RESPAWN_ANCHOR_DEPLETE.value(), 0.9f, 1.4f)
            }
        }
    }

    /** Pfändung: Für kurze Zeit gehört dein Gegenstand in der Hand ihm. */
    private fun seizure(world: ServerWorld): Cast {
        say(world, "seizure")
        sound(world, SoundEvents.ENTITY_ELDER_GUARDIAN_CURSE, 1.2f, 0.7f)
        return Cast(Poses.SEIZURE, 30) { age ->
            if (age == 18) {
                for (player in targets(world)) {
                    VoidVisuals.chain(world, id, player.id, entityPos, 16)
                    val held = player.mainHandStack
                    if (!held.isEmpty) player.itemCooldownManager.set(held, 120)
                    player.addStatusEffect(StatusEffectInstance(StatusEffects.WEAKNESS, 120, 1, false, true, true))
                    player.addStatusEffect(StatusEffectInstance(StatusEffects.MINING_FATIGUE, 120, 1, false, true, true))
                    addDebt(world, player, 1)
                    player.sendMessage(Text.translatable("hha_void.boss.msg.seized").formatted(Formatting.LIGHT_PURPLE), true)
                }
                sound(world, SoundEvents.BLOCK_CHAIN_BREAK, 1.2f, 0.5f)
            }
        }
    }

    /** Tilgungsstrahl: Rotierende Balken knapp über dem Boden. Wer nicht springt, wird getilgt. */
    private fun erasureSweep(world: ServerWorld): Cast {
        val origin = groundCenter().add(0.0, 1.0, 0.0)
        val arms = if (phase == 3) 4 else 2
        val start = random.nextDouble() * Math.PI * 2.0
        val turn = (if (random.nextBoolean()) 1 else -1) * (if (phase == 3) 0.05 else 0.04)
        val duration = 150
        val warmup = 26
        say(world, "sweep")
        sound(world, SoundEvents.ENTITY_WARDEN_SONIC_CHARGE, 1.3f, 0.7f)
        VoidVisuals.beam(world, origin, arms, start, turn, BEAM_LENGTH, duration, warmup)
        return Cast(Poses.SWEEP, duration) { age ->
            if (age >= warmup) {
                for (player in players(world)) {
                    if (player.y > origin.y + 0.2 || player.y + player.height < origin.y - 0.6) continue
                    val dx = player.x - origin.x
                    val dz = player.z - origin.z
                    val distance = kotlin.math.sqrt(dx * dx + dz * dz)
                    if (distance > BEAM_LENGTH) continue
                    val here = atan2(dz, dx)
                    val swept = start + turn * age
                    for (arm in 0 until arms) {
                        val angle = swept + arm * (Math.PI * 2.0 / arms)
                        val diff = net.minecraft.util.math.MathHelper.wrapDegrees(Math.toDegrees(here - angle))
                        val miss = kotlin.math.abs(sin(Math.toRadians(diff))) * distance
                        if (kotlin.math.abs(diff) < 90.0 && miss < 0.7 && (beamHits[player.uuid] ?: 0) <= age) {
                            beamHits[player.uuid] = age + 14
                            strike(world, player, 14f)
                            launch(player, Vec3d(cos(angle) * 0.4, 0.5, sin(angle) * 0.4))
                        }
                    }
                }
            }
            if (age == duration - 1) beamHits.clear()
        }
    }

    /** Singularität: Über dem Tribunal öffnet sich ein schwarzes Loch und zieht alles an. */
    private fun summonSingularity(world: ServerWorld): Cast {
        singularityReady = 520
        val ground = groundCenter()
        val center = ground.add(0.0, 8.0, 0.0)
        holeId = VoidVisuals.newId()
        VoidVisuals.blackHole(world, holeId, center, ground, SINGULARITY_TICKS)
        for (player in players(world)) VoidVisuals.holeTether(world, holeId, player.id, center)
        holeCenter = center
        holeTicks = SINGULARITY_TICKS
        say(world, "singularity")
        sound(world, SoundEvents.BLOCK_END_PORTAL_SPAWN, 1.3f, 0.4f)
        sound(world, SoundEvents.ENTITY_WARDEN_SONIC_CHARGE, 1.3f, 0.5f)
        return Cast(Poses.SINGULARITY, 40) { _ -> }
    }

    private fun tickSingularity(world: ServerWorld) {
        val center = holeCenter ?: return
        if (--holeTicks <= 0) {
            holeCenter = null
            for (player in players(world)) {
                val away = player.entityPos.subtract(center).normalize()
                launch(player, Vec3d(away.x * 1.1, 0.7, away.z * 1.1))
            }
            return
        }
        if (cutscene > 0 || intro > 0) return
        for (player in targets(world)) {
            val toHole = center.subtract(player.entityPos)
            val distance = toHole.length()
            if (distance < 42.0) {
                val pull = toHole.normalize().multiply(0.075 + 0.25 / (distance + 2.0))
                launch(player, player.velocity.add(pull))
            }
            if (distance < 6.0 && holeTicks % 20 == 0) strike(world, player, 7f)
        }
    }

    /**
     * Der Erlass: lange Ladezeit in der Arena-Mitte, dann trifft die Tilgung jeden, der
     * freie Sicht auf Vael hat. Die Säulen des Tribunals sind die einzige Deckung.
     */
    private fun remission(world: ServerWorld): Cast {
        val duration = 110
        say(world, "remission")
        showTitle(world, "remission")
        VoidVisuals.charge(world, home().add(0.0, 3.0, 0.0), duration)
        sound(world, SoundEvents.ENTITY_WARDEN_SONIC_CHARGE, 1.5f, 0.4f)
        return Cast(Poses.REMISSION, duration) { age ->
            if (age % 20 == 0) sound(world, SoundEvents.BLOCK_BEACON_POWER_SELECT, 1.3f, 0.5f + age * 0.01f)
            if (age == duration - 1) {
                val origin = entityPos.add(0.0, 3.0, 0.0)
                VoidVisuals.nova(world, origin, 11f)
                sound(world, SoundEvents.ENTITY_WARDEN_SONIC_BOOM, 1.6f, 0.4f)
                sound(world, SoundEvents.ENTITY_GENERIC_EXPLODE.value(), 1.5f, 0.5f)
                for (player in targets(world)) {
                    if (canSee(player)) {
                        strike(world, player, if (arena != null) 34f else 20f, debtAmount = 2)
                    } else {
                        player.sendMessage(Text.translatable("hha_void.boss.msg.survived").formatted(Formatting.LIGHT_PURPLE), true)
                    }
                }
            }
        }
    }

    private fun melee(world: ServerWorld, target: ServerPlayerEntity) {
        meleeCooldown = 36
        swingHand(net.minecraft.util.Hand.MAIN_HAND)
        strike(world, target, 12f)
        val away = target.entityPos.subtract(entityPos).let { Vec3d(it.x, 0.0, it.z) }.normalize()
        launch(target, Vec3d(away.x * 1.2, 0.5, away.z * 1.2))
        VoidVisuals.impact(world, target.entityPos.add(0.0, 1.0, 0.0), 1.2f)
        sound(world, SoundEvents.ITEM_MACE_SMASH_AIR, 1.0f, 0.6f)
    }

    private fun summonDebtors(world: ServerWorld, count: Int) {
        val ground = groundCenter()
        for (i in 0 until count) {
            val angle = i * (Math.PI * 2.0 / count) + random.nextDouble()
            val pos = ground.add(cos(angle) * 12.0, 0.2, sin(angle) * 12.0)
            val debtor = VoidBoss.DEBTOR.create(world, net.minecraft.entity.SpawnReason.MOB_SUMMONED) ?: continue
            debtor.refreshPositionAndAngles(pos.x, pos.y, pos.z, random.nextFloat() * 360f, 0f)
            world.spawnEntity(debtor)
            VoidVisuals.rift(world, pos.add(0.0, 1.5, 0.0), Vec3d(0.2, 1.0, 0.1), 4f, 26)
        }
    }

    /** Himmelsschlag: Aus den Rissen im Himmel fährt je ein Strahl auf jedes Ziel herab. */
    private fun skyStrikes(world: ServerWorld): Cast {
        say(world, "sky")
        return Cast(Poses.SKY, 66) { age ->
            if (age % 22 == 14) {
                sound(world, SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, 1.1f, 0.7f)
                for (player in targets(world)) {
                    val aim = player.entityPos.add(player.velocity.x * 10.0, 0.0, player.velocity.z * 10.0)
                    val ground = findGround(world, aim) ?: continue
                    val delay = 18
                    VoidVisuals.skyStrike(world, ground, delay)
                    TickScheduler.schedule(delay) {
                        if (!isAlive) return@schedule
                        sound(world, ground, SoundEvents.ENTITY_LIGHTNING_BOLT_IMPACT, 1.2f, 0.7f)
                        for (other in players(world)) {
                            val dx = other.x - ground.x
                            val dz = other.z - ground.z
                            if (dx * dx + dz * dz < 9.0) {
                                strike(world, other, 13f)
                                launch(other, Vec3d(dx * 0.1, 0.8, dz * 0.1))
                            }
                        }
                    }
                }
            }
        }
    }

    /** Riss: Ein Stampfer schickt eine Linie aus Säulen über den Boden auf das Ziel zu. */
    private fun fissure(world: ServerWorld, target: ServerPlayerEntity): Cast {
        return Cast(Poses.FISSURE, 34) { age ->
            if (age == 15) {
                stomp(world, 0.9f)
                sound(world, SoundEvents.ENTITY_WARDEN_ATTACK_IMPACT, 1.2f, 0.6f)
                val flat = target.entityPos.subtract(entityPos).let { Vec3d(it.x, 0.0, it.z) }
                if (flat.lengthSquared() > 0.01) {
                    val dir = flat.normalize()
                    val side = Vec3d(-dir.z, 0.0, dir.x)
                    for (lane in -1..1) {
                        val first = entityPos.add(dir.multiply(1.0))
                        val last = entityPos.add(dir.multiply(2.0 + 9 * 2.6)).add(side.multiply(lane * (1.0 + 9 * 0.5)))
                        VoidVisuals.crack(world, first, last, 28, 90)
                        for (i in 1..9) {
                            val at = entityPos.add(dir.multiply(2.0 + i * 2.6)).add(side.multiply(lane * (1.0 + i * 0.5)))
                            findGround(world, at)?.let { pillarAt(world, it, 8 + i * 2, light = true) }
                        }
                    }
                }
            }
        }
    }

    // ---------- Der falsche Tod ----------

    /** Beim ersten Tod stirbt er nicht wirklich: Erst bricht er zusammen, dann bricht der Himmel auf. */
    private fun startRebirth(world: ServerWorld) {
        reborn = true
        rebirth = 1
        health = 1f
        cast = null
        endHole(world)
        cutscene = 0
        inRift = false
        endBolts(world)
        debt.clear()
        for (debtor in world.getEntitiesByType(VoidBoss.DEBTOR) { it.squaredDistanceTo(this) < 96.0 * 96.0 }) debtor.kill(world)
        // Phase 0: Die Risse im Himmel schließen sich, der Sieg soll echt aussehen
        phase = 0
        dataTracker.set(PHASE, 0)
        dataTracker.set(POSE, Poses.FALSE_DEATH)
        bossBar.isVisible = false
        bossBar.setDarkenSky(false)
        say(world, "death")
        playCutscene(world, SCENE_COLLAPSE, REBIRTH_BREAK_END, entityPos)
        sound(world, SoundEvents.ENTITY_IRON_GOLEM_DEATH, 1.3f, 0.5f)
    }

    private fun victorySound(world: ServerWorld) {
        // Der Erfolgston soll den Sieg verkaufen
        for (player in players(world, 128.0)) {
            player.networkHandler.sendPacket(
                net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket(
                    net.minecraft.registry.entry.RegistryEntry.of(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE),
                    SoundCategory.MASTER, player.x, player.y, player.z, 1f, 1f, random.nextLong(),
                )
            )
        }
    }

    private fun tickRebirth(world: ServerWorld) {
        val t = ++rebirth
        velocity = Vec3d(0.0, velocity.y, 0.0)
        velocityDirty = true

        if (t == 30) {
            sound(world, SoundEvents.BLOCK_DEEPSLATE_BRICKS_BREAK, 1.6f, 0.5f)
            VoidVisuals.burst(world, entityPos.add(0.0, 1.0, 0.0), 2)
            VoidVisuals.crackStar(world, entityPos, 5, 7.0, 8, 60)
        }
        if (t == 38) victorySound(world)

        // Ruhe nach dem Sieg, dann der erste Herzschlag: Der Spieler darf sich kurz umsehen
        if (t in REBIRTH_HEART until REBIRTH_RISE && (t - REBIRTH_HEART) % (if (t < REBIRTH_START) 24 else if (t < REBIRTH_SKY) 12 else 16) == 0) {
            sound(world, SoundEvents.ENTITY_WARDEN_HEARTBEAT, 1.8f, 0.6f)
        }
        if (t == REBIRTH_START) {
            playCutscene(world, SCENE_REBIRTH, REBIRTH_END - REBIRTH_START, entityPos)
        }
        if (t == REBIRTH_START + 18) {
            dataTracker.set(POSE, Poses.STIR)
            sound(world, SoundEvents.BLOCK_DEEPSLATE_BRICKS_HIT, 1.8f, 0.5f)
        }
        if (t == REBIRTH_SKY) {
            // Phase 4 startet auf dem Client die Kuppel und alle Risse auf einmal
            phase = 4
            dataTracker.set(PHASE, 4)
            say(world, "rise")
            sound(world, SoundEvents.ENTITY_WITHER_SPAWN, 1.6f, 0.5f)
            sound(world, SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, 1.8f, 0.5f)
            bossBar.name = Text.translatable("entity.hha_void.void_boss_reborn").formatted(Formatting.DARK_RED, Formatting.BOLD)
            bossBar.color = BossBar.Color.RED
            bossBar.setDarkenSky(true)
            bossBar.isVisible = true
            bossBar.percent = 0f
        }
        if (t > REBIRTH_SKY && t <= REBIRTH_DONE) {
            bossBar.percent = ((t - REBIRTH_SKY).toFloat() / (REBIRTH_DONE - REBIRTH_SKY)).coerceAtMost(1f)
        }
        if (t == REBIRTH_RISE) {
            dataTracker.set(POSE, Poses.REASSEMBLE)
            VoidVisuals.skyBeams(world, entityPos, REBIRTH_DONE - REBIRTH_RISE)
            sound(world, SoundEvents.BLOCK_RESPAWN_ANCHOR_CHARGE, 1.6f, 0.5f)
            sound(world, SoundEvents.BLOCK_BEACON_ACTIVATE, 1.6f, 0.5f)
        }
        if (t in REBIRTH_RISE until REBIRTH_DONE && t % 8 == 0) {
            sound(world, SoundEvents.BLOCK_DEEPSLATE_BRICKS_PLACE, 1.4f, 0.5f + random.nextFloat() * 0.3f)
        }
        if (t == REBIRTH_DONE) {
            getAttributeInstance(net.minecraft.entity.attribute.EntityAttributes.MAX_HEALTH)?.baseValue = REBORN_HEALTH
            health = maxHealth
            dataTracker.set(POSE, Poses.ROAR)
            say(world, "phase4")
            VoidVisuals.nova(world, entityPos.add(0.0, 3.0, 0.0), 7f)
            VoidVisuals.crackStar(world, entityPos, 8, 18.0, 9, 120)
            sound(world, SoundEvents.ENTITY_WARDEN_SONIC_BOOM, 1.8f, 0.5f)
            sound(world, SoundEvents.ENTITY_WARDEN_ROAR, 1.8f, 0.5f)
            sound(world, SoundEvents.ENTITY_ENDER_DRAGON_GROWL, 1.8f, 0.5f)
        }
        if (t >= REBIRTH_END) {
            rebirth = 0
            cast = null
            recovery = 30
            dataTracker.set(POSE, Poses.IDLE)
            summonDebtors(world, 4)
        }
    }

    // ---------- Schuld ----------

    private fun strike(world: ServerWorld, victim: LivingEntity, amount: Float, debtAmount: Int = 1) {
        if (intro > 0 || cutscene > 0 || rebirth > 0) return
        if (victim.damage(world, world.damageSources.indirectMagic(this, this), amount) && victim is ServerPlayerEntity) {
            addDebt(world, victim, debtAmount)
        }
    }

    private fun addDebt(world: ServerWorld, player: ServerPlayerEntity, amount: Int) {
        val now = (debt[player.uuid] ?: 0) + amount
        if (now >= MAX_DEBT) {
            debt[player.uuid] = 0
            showDebt(player, 0)
            player.damage(world, world.damageSources.magic(), DUE_DAMAGE)
            player.addStatusEffect(StatusEffectInstance(StatusEffects.BLINDNESS, 60, 0, false, false, true))
            player.sendMessage(Text.translatable("hha_void.boss.msg.due").formatted(Formatting.DARK_PURPLE, Formatting.BOLD), false)
            VoidVisuals.nova(world, player.entityPos.add(0.0, 1.0, 0.0), 2f)
            sound(world, player.entityPos, SoundEvents.BLOCK_BELL_RESONATE, 1.2f, 0.6f)
        } else {
            debt[player.uuid] = now
            showDebt(player, now)
        }
    }

    private fun repay(player: ServerPlayerEntity, amount: Float) {
        val pot = (paid[player.uuid] ?: 0f) + amount
        if (pot >= REPAY_PER_STACK) {
            paid[player.uuid] = pot - REPAY_PER_STACK
            val stacks = debt[player.uuid] ?: 0
            if (stacks > 0) {
                debt[player.uuid] = stacks - 1
                showDebt(player, stacks - 1)
            }
        } else {
            paid[player.uuid] = pot
        }
    }

    private fun showDebt(player: ServerPlayerEntity, stacks: Int) {
        val bar = Text.literal("▮".repeat(stacks)).formatted(Formatting.LIGHT_PURPLE)
            .append(Text.literal("▯".repeat(MAX_DEBT - stacks)).formatted(Formatting.DARK_GRAY))
        player.sendMessage(Text.translatable("hha_void.boss.msg.debt", bar).formatted(Formatting.DARK_PURPLE), true)
    }

    override fun onKilledOther(world: ServerWorld, other: LivingEntity, damageSource: DamageSource?): Boolean {
        if (other is PlayerEntity) say(world, "account_closed")
        return super.onKilledOther(world, other, damageSource)
    }

    // ---------- Tod ----------

    override fun onDeath(damageSource: DamageSource) {
        val world = entityWorld as? ServerWorld
        if (world != null && !reborn && !damageSource.isOf(DamageTypes.GENERIC_KILL)) {
            startRebirth(world)
            return
        }
        super.onDeath(damageSource)
        if (world == null) return
        say(world, "death_true")
        endBolts(world)
        cast = null
        endHole(world)
        inRift = false
        dataTracker.set(POSE, Poses.DEATH)
        playCutscene(world, SCENE_TRUE_DEATH, DEATH_TICKS, entityPos)
        for (debtor in world.getEntitiesByType(VoidBoss.DEBTOR) { it.squaredDistanceTo(this) < 96.0 * 96.0 }) debtor.kill(world)
    }

    /** Statt umzufallen zerbröckelt er während der Schlussszene. */
    override fun updatePostDeath() {
        deathTime++
        val world = entityWorld as? ServerWorld ?: return
        velocity = Vec3d(0.0, velocity.y, 0.0)
        velocityDirty = true
        bossBar.percent = 0f
        if (deathTime > 60 && deathTime % 7 == 0) {
            val r = random
            VoidVisuals.burst(world, entityPos.add((r.nextDouble() - 0.5) * 2.0, 0.5 + r.nextDouble() * 3.0, (r.nextDouble() - 0.5) * 2.0), 1)
            sound(world, SoundEvents.BLOCK_AMETHYST_BLOCK_BREAK, 1.2f, 0.5f)
        }
        if (deathTime == 60) {
            sound(world, SoundEvents.ENTITY_WARDEN_DEATH, 1.6f, 0.6f)
            VoidVisuals.crackStar(world, entityPos, 6, 10.0, 20, 60)
        }
        if (deathTime >= DEATH_TICKS) {
            VoidVisuals.nova(world, entityPos.add(0.0, 2.0, 0.0), 8f)
            sound(world, SoundEvents.ENTITY_ENDER_DRAGON_DEATH, 1.2f, 1.1f)
            say(world, "death_final")
            dropStack(world, VoidBoss.ledgerBook())
            remove(Entity.RemovalReason.KILLED)
        }
    }

    // ---------- Hilfen ----------

    private fun launch(player: ServerPlayerEntity, velocity: Vec3d) {
        player.fallDistance = 0.0
        dev.henny.hha.logic.Fx.launchPlayer(player, velocity)
    }

    private fun findGround(world: ServerWorld, around: Vec3d): Vec3d? {
        var pos = BlockPos.ofFloored(around.x, around.y + 2.0, around.z)
        repeat(16) {
            val below = pos.down()
            if (!world.getBlockState(below).getCollisionShape(world, below).isEmpty && world.getBlockState(pos).isAir) {
                return Vec3d(around.x, pos.y.toDouble(), around.z)
            }
            pos = below
        }
        return null
    }

    private fun say(world: ServerWorld, key: String) {
        val line = Text.translatable("hha_void.boss.name").formatted(Formatting.DARK_PURPLE, Formatting.BOLD)
            .append(Text.literal(": ").formatted(Formatting.DARK_GRAY))
            .append(Text.translatable("hha_void.boss.line.$key").formatted(Formatting.LIGHT_PURPLE, Formatting.ITALIC))
        for (player in players(world, 128.0)) player.sendMessage(line, false)
    }

    private fun showTitle(world: ServerWorld, key: String) {
        for (player in players(world, 128.0)) {
            player.networkHandler.sendPacket(SubtitleS2CPacket(Text.translatable("hha_void.boss.title.$key.sub").formatted(Formatting.LIGHT_PURPLE)))
            player.networkHandler.sendPacket(TitleS2CPacket(Text.translatable("hha_void.boss.title.$key").formatted(Formatting.DARK_PURPLE, Formatting.BOLD)))
        }
    }

    private fun sound(world: ServerWorld, event: SoundEvent, volume: Float, pitch: Float) =
        sound(world, entityPos, event, volume, pitch)

    private fun sound(world: ServerWorld, at: Vec3d, event: SoundEvent, volume: Float, pitch: Float) =
        world.playSound(null, at.x, at.y, at.z, event, SoundCategory.HOSTILE, volume * 2f, pitch)

    companion object {
        private val POSE: TrackedData<Int> = DataTracker.registerData(VoidBossEntity::class.java, TrackedDataHandlerRegistry.INTEGER)
        private val PHASE: TrackedData<Int> = DataTracker.registerData(VoidBossEntity::class.java, TrackedDataHandlerRegistry.INTEGER)

        private const val INTRO_TICKS = 190
        private const val INTRO_HEIGHT = 34.0
        private const val INTRO_DROP = 60
        private const val INTRO_ROAR = 135
        private const val PHASE_CUT_TICKS = 64

        // Müssen zu den Nummern in VoidScene passen; die Klasse liegt nur auf dem Client
        private const val SCENE_INTRO = 0
        private const val SCENE_PHASE_2 = 1
        private const val SCENE_PHASE_3 = 2
        private const val SCENE_COLLAPSE = 3
        private const val SCENE_REBIRTH = 4
        private const val SCENE_TRUE_DEATH = 5

        private const val FIST_HEIGHT = 17f
        private const val ARENA_RANGE = 64.0
        private const val IDLE_LINES = 6

        const val MAX_DEBT = 5
        private const val DUE_DAMAGE = 16f
        private const val REPAY_PER_STACK = 30f
        const val MAX_INSTALLMENT = 30f

        private const val BEAM_LENGTH = 30f
        private const val SINGULARITY_TICKS = 150
        private const val DEATH_TICKS = 130

        private const val PILLARS = 0
        private const val VOLLEY = 1
        private const val COLLECT = 2
        private const val SEIZURE = 3
        private const val SWEEP = 4
        private const val SINGULARITY = 5
        private const val SKY_STRIKE = 7
        private const val FISSURE = 8
        private const val REACH = 9
        private const val RIFT_STEP = 10
        private const val RIFT_RAIN = 11

        private const val REBORN_HEALTH = 350.0
        private const val REBIRTH_BREAK_END = 78
        private const val REBIRTH_HEART = 100
        private const val REBIRTH_START = 130
        private const val REBIRTH_SKY = REBIRTH_START + 42
        private const val REBIRTH_RISE = REBIRTH_START + 95
        private const val REBIRTH_DONE = REBIRTH_START + 205
        private const val REBIRTH_END = REBIRTH_START + 265
    }
}
