package dev.henny.hha_void.boss

import dev.henny.hha_void.VoidSounds
import dev.henny.hha_void.logic.TickScheduler
import dev.henny.hha_void.logic.VoidVisuals
import net.minecraft.block.Block
import net.minecraft.entity.Entity
import net.minecraft.entity.EntityType
import net.minecraft.entity.ExperienceOrbEntity
import net.minecraft.entity.FallingBlockEntity
import net.minecraft.entity.LivingEntity
import net.minecraft.entity.MovementType
import net.minecraft.entity.SpawnReason
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
import net.minecraft.util.hit.HitResult
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Box
import net.minecraft.util.math.MathHelper
import net.minecraft.util.math.Vec3d
import net.minecraft.world.RaycastContext
import net.minecraft.world.World
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Oruun, der Leviathan (Design: docs/SPEC_LEVIATHAN.md). Ein ~61 Blöcke langer Sternenwal über der Himmelsarena
 * (Plattform Ø ≈ 372): er kreist über den Spielern, rammt quer hindurch, bricht als echter Wal-Breach durch den Boden,
 * schüttelt Reiter ab und verschlingt in der Mitte des Kampfes alle – im Bauch kämpfen sie sich durch seinen ganzen Körper.
 * Von den Außenplattformen aus kann man ihn mit Harpunen-Geschützen festnageln.
 *
 * Ablauf: Intro → Phase I → „Die Tiefe erwacht" (70 %) → Phase II → Verschlingen (45 %) → Bauch (5 Abschnitte) →
 * Ausbruch (er taumelt) → Phase III „Raserei" (tiefer, nach jedem Angriff erschöpft/Tiefflug) → Tod.
 *
 * Körper: [LeviathanBody] rechnet Hitboxen, Rückenfläche und Maul/Herz aus den echten Modellknochen (Rig = Kopie des
 * Modells, Biegung deterministisch aus der Lage der letzten Ticks). Server-Boxen liegen dort, wo die Clients ihn sehen
 * ([CLIENT_LAG] Ticks zurück); Treffer-Zeitpunkte von Haltungen werden um dieselbe Zeit verzögert geprüft.
 */
class VoidBossEntity(type: EntityType<out VoidBossEntity>, world: World) : HostileEntity(type, world) {

    private val bossBar = ServerBossBar(displayName, BossBar.Color.PURPLE, BossBar.Style.NOTCHED_10).also {
        it.setDarkenSky(true)
    }

    private class Cast(val id: Int, val duration: Int, val body: (Int) -> Unit) {
        var age = 0
    }

    /** Mitte des Arenabodens (Block unter dem Altar); null, wenn per Befehl ohne Arena beschworen. */
    var arena: BlockPos? = null
    private var homePoint: Vec3d? = null

    private var phase = 1

    /** Ticks seit Beginn des Auftritts; -1 = noch nicht begonnen, [INTRO_TICKS] = vorbei. */
    private var intro = -1
    private var introDir = Vec3d(1.0, 0.0, 0.0)

    // Szene (Phasenübergang): solange sie läuft, ruht der Kampf
    private var sceneKind = -1
    private var sceneAge = 0
    private var sceneLength = 0
    private var sceneDir = Vec3d(1.0, 0.0, 0.0)
    private var sceneSpot = Vec3d.ZERO
    private var sceneFlag = false

    private var cast: Cast? = null
    private var recovery = 60
    private var lastCast = -1
    private val cooldowns = IntArray(ATTACK_COUNT)
    private var nextLine = 300
    private var lonelyTicks = 0

    // Kreisbahn um den Schwerpunkt der Spieler ([focus]) – die Arena ist zu groß, um um ihre Mitte zu kreisen
    private var orbitAngle = 0.0
    private var orbitDir = 1.0
    private var focus: Vec3d? = null

    private var ramsSinceBeach = 0

    /** Gestrandet/taumelnd/festgenagelt: alle Treffer zählen mehr, das Herz liegt offen. */
    private var exposed = false
    private val bodyHits = HashMap<UUID, Int>()

    // Mahlstrom
    private var holeCenter: Vec3d? = null
    private var holeId = 0
    private var holeTicks = 0
    private val chomped = HashMap<UUID, Int>()

    private var novaReady = 0
    private var reelStart = 0
    private var pendingLoot: DamageSource? = null

    // ---- Harpunen (Außenplattformen)
    private class Tether(val id: Int, val from: Vec3d, val volume: Int, val until: Int)
    private val tethers = ArrayList<Tether>()
    private var pinCooldown = 0

    // ---- Segmente (Hitboxen) und Lage-Verlauf
    private val parts = arrayOfNulls<LeviathanPartEntity>(LeviathanBody.COUNT)
    private var poseStart = 0
    private var previousPose = WhalePose.HIDDEN
    private var previousStart = 0
    private val partHits = HashMap<Any, Int>()
    private var lastHurtSound = -100
    private var swimPhase = 0f

    private class Snap(val pose: Int, val poseAge: Float, val prev: Int, val prevAge: Float, val age: Float, val phase: Int)
    private val samples = ArrayList<LeviathanBody.Sample>()
    private val snaps = ArrayList<Snap>()
    private var seenFrame: LeviathanBody.Frame? = null
    private var seenPrevFrame: LeviathanBody.Frame? = null
    private var nowFrame: LeviathanBody.Frame? = null
    private var nowFrameAge = -1

    /** Wer gerade auf ihm steht (Ticks am Stück) – er will sie loswerden. */
    private val riders = HashMap<UUID, Int>()
    private val lastRide = HashMap<UUID, Int>()

    /** > 0: der Rücken trägt nicht (Fassrolle, steiles Abtauchen) – Reiter verlieren den Halt. */
    private var slippery = 0

    // ---- Bauch-Phase
    private var belly: LeviathanBelly? = null
    private var bellyState = BELLY_NONE
    private var bellyTicks = 0
    private val swallowed = LinkedHashSet<UUID>()
    private var bellyDone = false
    private var restoreBelly: BlockPos? = null

    // ---- Einstürzende Inseln (Phase II)
    private val collapse = ArrayList<BlockPos>()

    // ---- Gelände
    /** > 0: er bricht absichtlich durch die Plattform (Durchbruch, Abtauchen) – kein Geländeschutz. */
    private var phaseThrough = 0
    private var aheadLift = 0.0

    private class Scar(val spot: Vec3d, val blocks: ArrayList<Pair<BlockPos, net.minecraft.block.BlockState>>, var healAt: Int)
    private val scars = ArrayList<Scar>()

    // ---- Client: Lage-Verlauf, Frames und Bewegung für Renderer und Reiter
    private val clientSamples = ArrayList<LeviathanBody.Sample>()
    private var clientMotion = LeviathanBody.Motion()
    private var clientPrevMotion = LeviathanBody.Motion()
    private val renderMotion = LeviathanBody.Motion()
    var clientFrame: LeviathanBody.Frame? = null
        private set
    var clientPrevFrame: LeviathanBody.Frame? = null
        private set

    init {
        setPersistent()
        setNoGravity(true)
        noClip = true
        experiencePoints = 0
        lookControl = object : net.minecraft.entity.ai.control.LookControl(this) {
            override fun tick() = Unit
        }
    }

    override fun initDataTracker(builder: DataTracker.Builder) {
        super.initDataTracker(builder)
        builder.add(POSE, WhalePose.HIDDEN)
        builder.add(PHASE, 1)
        builder.add(FLAGS, 0)
        builder.add(SWIM, 0f)
    }

    // ---------- Schnittstelle für Modell, Segmente und Tests ----------

    fun castPose(): Int = dataTracker.get(POSE)
    fun bossPhase(): Int = dataTracker.get(PHASE)

    /** Segmente fest (man kann darauf stehen)? */
    fun partsSolid(): Boolean = dataTracker.get(FLAGS) and FLAG_SOLID != 0

    /** Segmente treffbar? (nicht vor dem Auftritt, nicht während Szenen, nicht im Tod) */
    fun partsHittable(): Boolean = dataTracker.get(FLAGS) and FLAG_HITTABLE != 0

    fun bellyStateName(): String = when (bellyState) {
        BELLY_NONE -> if (bellyDone) "done" else "none"
        BELLY_DEVOUR -> "devour"
        BELLY_INSIDE -> belly?.let { "inside(s${it.section} ${it.objectivesTotal() - it.objectivesAlive()}/${it.objectivesTotal()} acid=${it.acidLevel})" } ?: "inside"
        BELLY_BREAKOUT -> "breakout"
        else -> "cleanup"
    }

    fun bellyCenter(): BlockPos? = belly?.center
    fun bellySection(): Int = belly?.section ?: -1
    fun bellyEntry(s: Int): Vec3d? = belly?.entry(s)
    fun riderCount(): Int = riders.size
    fun currentCast(): Int = cast?.id ?: -1
    fun sceneName(): Int = sceneKind
    fun tetherCount(): Int = tethers.size
    fun isExposed(): Boolean = exposed
    fun focusPoint(): Vec3d = focus()

    /** Lage, die die Clients gerade sehen (Server) – für Tests und Angriffe. */
    fun seen(): LeviathanBody.Frame = frame()

    /** Nur für Tests (`/voidboss pose`): setzt die Haltung direkt; mit NoAI bleibt sie stehen. */
    fun forcePose(pose: Int, phase: Int = bossPhase()) {
        setPose(pose)
        dataTracker.set(PHASE, phase)
    }

    private fun setPose(pose: Int) {
        val now = dataTracker.get(POSE)
        if (now != pose) {
            previousPose = now
            previousStart = poseStart
            poseStart = age
        }
        dataTracker.set(POSE, pose)
    }

    // Nur auf dem Client: wann die aktuelle Haltung begann, für weiche Übergänge
    private var clientPose = WhalePose.HIDDEN
    private var clientPoseStart = 0
    private var clientPrevious = WhalePose.HIDDEN
    private var clientPreviousStart = 0

    fun clientShownPose(): Int = clientPose
    fun clientPoseAge(tickDelta: Float): Float = age - clientPoseStart + tickDelta
    fun clientPreviousPose(): Int = clientPrevious
    fun clientPreviousPoseAge(tickDelta: Float): Float = age - clientPreviousStart + tickDelta

    /** Bewegung (Biegung, Schräglage, Schlagwelle) für den Renderer, zwischen den Ticks interpoliert. */
    fun renderMotion(tickDelta: Float): LeviathanBody.Motion = renderMotion.lerp(clientPrevMotion, clientMotion, tickDelta)

    override fun tick() {
        noClip = true
        super.tick()
        val w = entityWorld
        if (w.isClient) {
            val pose = castPose()
            if (pose != clientPose) {
                clientPrevious = clientPose
                clientPreviousStart = clientPoseStart
                clientPose = pose
                clientPoseStart = age
            }
            clientSamples.add(LeviathanBody.Sample(x, y, z, yaw, pitch, dataTracker.get(SWIM)))
            while (clientSamples.size > 12) clientSamples.removeAt(0)
            val m = LeviathanBody.motion(clientSamples, clientSamples.size - 1, LeviathanBody.Motion())
            clientPrevMotion = clientMotion
            clientMotion = m
            clientPrevFrame = clientFrame
            clientFrame = LeviathanBody.solve(
                LeviathanBody.State(entityPos, yaw, pitch, clientPose, clientPoseAge(1f), clientPrevious, clientPreviousPoseAge(1f), age.toFloat(), bossPhase(), m),
            )
        } else if (w is ServerWorld) {
            // Schlagwelle weiterzählen und an die Clients geben (Renderer und Hitboxen nutzen dieselbe Phase)
            val last = samples.lastOrNull()
            val speed = if (last != null) entityPos.distanceTo(Vec3d(last.x, last.y, last.z)).toFloat() else 0f
            val climb = if (last != null) ((y - last.y) / 0.2).toFloat().coerceIn(-1f, 1f) else 0f
            swimPhase = (swimPhase + LeviathanBody.swimRate(speed, climb, castPose() == WhalePose.DIVE)) % (4f * PI.toFloat())
            dataTracker.set(SWIM, swimPhase)
            samples.add(LeviathanBody.Sample(x, y, z, yaw, pitch, swimPhase))
            snaps.add(Snap(castPose(), (age - poseStart).toFloat(), previousPose, (age - previousStart).toFloat(), age.toFloat(), bossPhase()))
            while (samples.size > 16) { samples.removeAt(0); snaps.removeAt(0) }
            seenPrevFrame = seenFrame
            seenFrame = frameAt(CLIENT_LAG)
            updateParts(w)
        }
    }

    // ---------- Lage ----------

    private fun frameAt(lag: Int): LeviathanBody.Frame {
        if (samples.isEmpty()) {
            return LeviathanBody.solve(
                LeviathanBody.State(entityPos, yaw, pitch, castPose(), (age - poseStart).toFloat(), previousPose, (age - previousStart).toFloat(), age.toFloat(), bossPhase(), LeviathanBody.Motion()),
            )
        }
        val at = (samples.size - 1 - lag).coerceAtLeast(0)
        val s = samples[at]
        val n = snaps[at]
        val m = LeviathanBody.motion(samples, at, LeviathanBody.Motion())
        return LeviathanBody.solve(LeviathanBody.State(Vec3d(s.x, s.y, s.z), s.yaw, s.pitch, n.pose, n.poseAge, n.prev, n.prevAge, n.age, n.phase, m))
    }

    /** Was die Spieler sehen (Lage von vor [CLIENT_LAG] Ticks). */
    private fun frame(): LeviathanBody.Frame = seenFrame ?: frameAt(0)

    /** Aktuelle Lage (für Gelände und Steuerung). */
    private fun frameNow(): LeviathanBody.Frame {
        if (nowFrameAge != age || nowFrame == null) {
            nowFrame = frameAt(0)
            nowFrameAge = age
        }
        return nowFrame!!
    }

    /** Lage, wenn er bei [pos] mit Blick [yaw]/[pitch] in Haltung [pose] im Alter [poseAge] wäre. */
    private fun predict(pos: Vec3d, yaw: Float, pitch: Float, pose: Int, poseAge: Float): LeviathanBody.Frame =
        LeviathanBody.solve(LeviathanBody.State(pos, yaw, pitch, pose, poseAge, pose, 0f, age.toFloat(), phase, LeviathanBody.Motion()))

    // ---------- Speichern ----------

    override fun writeCustomData(view: WriteView) {
        super.writeCustomData(view)
        view.putInt("Phase", phase)
        view.putBoolean("BellyDone", bellyDone)
        view.putBoolean("HasArena", arena != null)
        arena?.let {
            view.putInt("ArenaX", it.x)
            view.putInt("ArenaY", it.y)
            view.putInt("ArenaZ", it.z)
        }
        val b = belly?.center ?: restoreBelly
        view.putBoolean("HasBelly", b != null)
        if (b != null) {
            view.putInt("BellyX", b.x)
            view.putInt("BellyY", b.y)
            view.putInt("BellyZ", b.z)
        }
    }

    override fun readCustomData(view: ReadView) {
        super.readCustomData(view)
        phase = view.getInt("Phase", 1)
        bellyDone = view.getBoolean("BellyDone", false)
        dataTracker.set(PHASE, phase)
        arena = if (view.getBoolean("HasArena", false)) {
            BlockPos(view.getInt("ArenaX", 0), view.getInt("ArenaY", 0), view.getInt("ArenaZ", 0))
        } else {
            null
        }
        if (view.getBoolean("HasBelly", false)) {
            restoreBelly = BlockPos(view.getInt("BellyX", 0), view.getInt("BellyY", 0), view.getInt("BellyZ", 0))
        }
        intro = INTRO_TICKS
        setPose(WhalePose.SWIM)
        if (hasCustomName()) bossBar.name = displayName
    }

    // ---------- Grundverhalten ----------

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
    override fun pushAwayFrom(entity: Entity) = Unit

    /** Treffer gehen an die Segmente; der Körper selbst ist nicht anvisierbar. */
    override fun canHit() = false
    override fun canImmediatelyDespawn(distanceSquared: Double) = false
    override fun getAmbientSound(): SoundEvent? = null
    override fun getHurtSound(source: DamageSource): SoundEvent = VoidSounds.WHALE_HURT
    override fun getDeathSound(): SoundEvent? = null
    override fun getSoundVolume() = 6f
    override fun handleFallDamage(fallDistance: Double, damagePerDistance: Float, damageSource: DamageSource) = false

    override fun playHurtSound(source: DamageSource) {
        if (age - lastHurtSound < 8) return
        lastHurtSound = age
        super.playHurtSound(source)
    }

    override fun travel(movementInput: Vec3d) {
        val world = entityWorld as? ServerWorld
        val lift = if (world != null) terrainLift(world) else 0.0
        move(MovementType.SELF, if (lift > 0.0) velocity.add(0.0, lift, 0.0) else velocity)
    }

    // ---------- Gelände: nicht durch Blöcke fliegen ----------

    /**
     * Er fliegt mit noClip. Damit er trotzdem nicht durch Monolithen, Inseln oder den Boden gleitet, prüft dieser Schutz
     * seine Boxen an der nächsten und an einer vorausberechneten Lage und hebt ihn rechtzeitig über Hindernisse.
     * Aus: gestrandet, verborgen, beim Durchbruch/Abtauchen (reißt ein Loch) und unter der Plattform.
     */
    private fun terrainLift(world: ServerWorld): Double {
        val pose = castPose()
        if (pose == WhalePose.HIDDEN || LeviathanBody.isBeachedPose(pose) || phaseThrough > 0 || isDead) return 0.0
        if (bodyCenter().y < groundCenter().y - 4.0) return 0.0
        val boxes = frameNow().terrainBoxes(skipJaw = cast?.id == SNAP)
        val now = obstacleLift(world, boxes, velocity, 2.0)
        if (age % 2 == 0) aheadLift = max(
            obstacleLift(world, boxes, velocity.multiply(4.0), 3.0),
            obstacleLift(world, boxes, velocity.multiply(12.0), 3.5) * 0.7,
        )
        var lift = 0.0
        if (now > 0.0) lift = min(now, 6.0)
        if (aheadLift > 0.0) lift = max(lift, min(aheadLift / 4.0 + 0.1, 2.5))
        return lift
    }

    /** Wie weit müsste er steigen, damit keine Box (um [shift] verschoben) in festen Blöcken steckt? */
    private fun obstacleLift(world: ServerWorld, boxes: List<Box>, shift: Vec3d, step: Double): Double {
        val m = BlockPos.Mutable()
        var need = 0.0
        val ceiling = if (arena != null) groundCenter().y + TribunalBuilder.CEILING + 2.0 else Double.MAX_VALUE
        for (b0 in boxes) {
            val b = b0.offset(shift)
            if (b.minY > ceiling) continue
            val y0 = MathHelper.floor(b.minY)
            val y1 = MathHelper.floor(b.maxY)
            val nx = max(1, ((b.maxX - b.minX) / step).toInt())
            val nz = max(1, ((b.maxZ - b.minZ) / step).toInt())
            for (ix in 0..nx) {
                val x = MathHelper.floor(b.minX + 0.3 + (b.maxX - b.minX - 0.6) * ix / nx)
                for (iz in 0..nz) {
                    val z = MathHelper.floor(b.minZ + 0.3 + (b.maxZ - b.minZ - 0.6) * iz / nz)
                    if (!world.isChunkLoaded(x shr 4, z shr 4)) continue
                    var y = y0
                    while (y <= y1 && !solidAt(world, m.set(x, y, z))) y++
                    if (y > y1) continue
                    var top = y
                    while (top < y1 + 32 && solidAt(world, m.set(x, top + 1, z))) top++
                    need = max(need, top + 1.0 + TERRAIN_CLEARANCE - b.minY)
                }
            }
        }
        return need
    }

    private fun solidAt(world: ServerWorld, pos: BlockPos): Boolean {
        val state = world.getBlockState(pos)
        return !state.isAir && !state.getCollisionShape(world, pos).isEmpty
    }

    /** Gestrandet zerdrückt er, was unter ihm steht (Säulen, Stäbe); alles wächst nach, sobald er wieder abhebt. */
    private fun crushUnderBody(world: ServerWorld, heal: Int = 40, ahead: Vec3d = Vec3d.ZERO) {
        if (arena == null) return
        val c = groundCenter()
        val removed = ArrayList<Pair<BlockPos, net.minecraft.block.BlockState>>()
        val air = net.minecraft.block.Blocks.AIR.defaultState
        val m = BlockPos.Mutable()
        val floorY = MathHelper.floor(c.y)
        for (b0 in frameNow().terrainBoxes()) {
            val b = b0.stretch(ahead)
            for (x in MathHelper.floor(b.minX)..MathHelper.floor(b.maxX - 1e-3))
                for (z in MathHelper.floor(b.minZ)..MathHelper.floor(b.maxZ - 1e-3))
                    for (y in max(floorY, MathHelper.floor(b.minY))..MathHelper.floor(b.maxY - 1e-3)) {
                        m.set(x, y, z)
                        val state = world.getBlockState(m)
                        if (state.isAir || state.hasBlockEntity() || state.isOf(VoidBoss.ALTAR) || state.getHardness(world, m) < 0f) continue
                        removed += m.toImmutable() to state
                        world.setBlockState(m, air, Block.NOTIFY_LISTENERS or Block.FORCE_STATE)
                        if (random.nextInt(3) == 0) world.syncWorldEvent(net.minecraft.world.WorldEvents.BLOCK_BROKEN, m, Block.getRawIdFromState(state))
                    }
        }
        if (removed.isNotEmpty()) scars += Scar(bodyCenter(), removed, age + heal)
    }

    /** Reißt ein Loch in die Plattform (statt hindurchzugleiten); es wächst später wieder zu. */
    private fun tearHole(world: ServerWorld, spot: Vec3d, radius: Double = HOLE_RADIUS, open: Int = HOLE_OPEN_TICKS) {
        if (arena == null) return
        val c = groundCenter()
        val removed = ArrayList<Pair<BlockPos, net.minecraft.block.BlockState>>()
        val ri = radius.toInt() + 1
        val bottom = c.y.toInt() - 66
        val top = c.y.toInt() + 24
        val m = BlockPos.Mutable()
        val air = net.minecraft.block.Blocks.AIR.defaultState
        for (dx in -ri..ri) for (dz in -ri..ri) {
            if (dx * dx + dz * dz > radius * radius) continue
            val x = MathHelper.floor(spot.x) + dx
            val z = MathHelper.floor(spot.z) + dz
            for (y in bottom..top) {
                m.set(x, y, z)
                val state = world.getBlockState(m)
                if (state.isAir || state.hasBlockEntity() || state.isOf(VoidBoss.ALTAR) || state.getHardness(world, m) < 0f) continue
                removed += m.toImmutable() to state
                world.setBlockState(m, air, Block.NOTIFY_LISTENERS or Block.FORCE_STATE)
                if (y >= c.y.toInt() - 3 && random.nextInt(6) == 0) {
                    world.syncWorldEvent(net.minecraft.world.WorldEvents.BLOCK_BROKEN, m, Block.getRawIdFromState(state))
                }
            }
        }
        if (removed.isNotEmpty()) scars += Scar(spot, removed, age + open)
    }

    /** Löcher wieder schließen – nie in Spieler oder den Wal hinein. */
    private fun tickScars(world: ServerWorld, force: Boolean = false) {
        if (scars.isEmpty()) return
        val iter = scars.iterator()
        while (iter.hasNext()) {
            val scar = iter.next()
            if (!force && age < scar.healAt) continue
            if (!force && (age - scar.healAt) % 10 != 0) continue
            val left = ArrayList<Pair<BlockPos, net.minecraft.block.BlockState>>()
            val bodies = if (isRemoved) emptyList() else frameNow().activeBoxes() + frame().activeBoxes()
            for ((pos, state) in scar.blocks) {
                if (!world.getBlockState(pos).isAir) continue
                val box = Box(pos)
                val blocked = bodies.any { it.intersects(box) } || world.getOtherEntities(null, box) { it is LivingEntity && it !is LeviathanPartEntity }.isNotEmpty()
                if (blocked && !force) {
                    left += pos to state
                    continue
                }
                if (!blocked) world.setBlockState(pos, state, Block.NOTIFY_LISTENERS or Block.FORCE_STATE)
            }
            if (scar.blocks.size != left.size) {
                VoidVisuals.burst(world, scar.spot.add(0.0, 1.0, 0.0), 2)
                sound(world, scar.spot, SoundEvents.BLOCK_AMETHYST_BLOCK_RESONATE, 2f, 0.6f)
            }
            if (left.isEmpty() || force) iter.remove() else {
                scar.blocks.clear()
                scar.blocks.addAll(left)
            }
        }
    }

    override fun damage(world: ServerWorld, source: DamageSource, amount: Float): Boolean {
        if (source.isOf(DamageTypes.GENERIC_KILL)) return super.damage(world, source, amount)
        return hurtBy(world, source, amount)
    }

    private fun hurtBy(world: ServerWorld, source: DamageSource, amount: Float): Boolean {
        if (intro < INTRO_TICKS || sceneKind >= 0 || isDead) return false
        if (source.isIn(DamageTypeTags.IS_FIRE) || source.isOf(DamageTypes.WITHER) || source.isOf(DamageTypes.IN_WALL) ||
            source.isOf(DamageTypes.FALL) || source.isOf(DamageTypes.FLY_INTO_WALL) || source.isOf(DamageTypes.OUT_OF_WORLD) ||
            source.isOf(DamageTypes.CRAMMING) || source.isOf(DamageTypes.DROWN)
        ) return false
        val attacker = source.attacker
        if (attacker === this || attacker is VoidDebtorEntity || attacker is LeviathanCoreEntity) return false
        val dealt = min(if (exposed) amount * EXPOSED_FACTOR else amount, MAX_HIT)
        timeUntilRegen = 0
        return super.damage(world, source, dealt)
    }

    /** Treffer an einem Volumen. Je Angreifer (und Schadensart) zählt pro Tick nur ein Volumen. */
    fun damageFromPart(part: LeviathanPartEntity, world: ServerWorld, source: DamageSource, amount: Float): Boolean {
        val key: Any = source.attacker?.uuid ?: source.typeRegistryEntry
        if (partHits[key] == age) return false
        partHits[key] = age
        if (partHits.size > 64) partHits.entries.removeIf { age - it.value > 40 }
        val kind = part.kind()
        val factor = LeviathanBody.damageFactor(kind, exposed)
        val hurt = hurtBy(world, source, amount * factor)
        if (hurt && factor > 1.2f) {
            VoidVisuals.impact(world, part.boundingBox.center, 2.2f)
            sound(world, part.boundingBox.center, SoundEvents.BLOCK_AMETHYST_BLOCK_RESONATE, 1.5f, 1.6f)
        }
        return hurt
    }

    // ---------- Orte ----------

    private fun groundCenter(): Vec3d {
        arena?.let { return Vec3d(it.x + 0.5, it.y + 1.0, it.z + 0.5) }
        return homePoint ?: entityPos.also { homePoint = it }
    }

    /** Wo gekämpft wird: Schwerpunkt der Spieler auf der Plattform (weich nachgeführt). */
    private fun focus(): Vec3d = focus ?: groundCenter().also { focus = it }

    private fun updateFocus(world: ServerWorld) {
        val c = groundCenter()
        val list = targets(world).filter { horizontal(it.entityPos, c) < floorRadius() + 70.0 }
        if (list.isEmpty()) return
        var x = 0.0; var z = 0.0
        for (p in list) { x += p.x; z += p.z }
        val goal = onFloor(Vec3d(x / list.size, c.y, z / list.size), FOCUS_MARGIN)
        val f = focus()
        focus = if (f.squaredDistanceTo(goal) > 120.0 * 120.0) goal else f.lerp(goal, 0.025)
    }

    private fun horizontal(a: Vec3d, b: Vec3d): Double = sqrt((a.x - b.x) * (a.x - b.x) + (a.z - b.z) * (a.z - b.z))

    private fun floorRadius(): Double = if (arena != null) TribunalBuilder.FLOOR_RADIUS.toDouble() else 40.0

    private fun forward(): Vec3d = getRotationVector(pitch, yaw)
    private fun flatForward(): Vec3d = Vec3d.fromPolar(0f, yaw)
    private fun rightVec(): Vec3d = LeviathanBody.right(yaw)

    fun bodyCenter(): Vec3d = entityPos.add(0.0, BODY_UP, 0.0)
    fun mouthPos(): Vec3d = frame().mouth()
    fun heartPos(): Vec3d = frame().heart()
    fun flukePos(): Vec3d = frame().fluke()
    fun blowholePos(): Vec3d = frame().blowhole()

    private fun players(world: ServerWorld, range: Double = ARENA_RANGE): List<ServerPlayerEntity> {
        val c = groundCenter()
        return world.players.filter {
            it.isAlive && !it.isSpectator && it.y > c.y - 70.0 && it.y < c.y + 160.0 &&
                (it.x - c.x) * (it.x - c.x) + (it.z - c.z) * (it.z - c.z) <= range * range
        }
    }

    /** Spieler im Kampf (nicht kreativ, nicht im Bauch). */
    private fun targets(world: ServerWorld): List<ServerPlayerEntity> =
        players(world).filter { !it.isCreative && it.uuid !in swallowed }

    // ---------- Takt ----------

    override fun mobTick(world: ServerWorld) {
        super.mobTick(world)
        setNoGravity(true)
        bossBar.percent = health / maxHealth
        restoreBelly?.let { recoverBelly(world, it) }
        if (phaseThrough > 0) phaseThrough--
        if (pinCooldown > 0) pinCooldown--
        tickScars(world)
        tickTethers(world)

        if (intro < INTRO_TICKS) {
            tickIntro(world)
            return
        }
        updateFocus(world)
        // Sicherung: nie weit außerhalb der Arena oder tief unter ihr landen (dort ticken Chunks evtl. nicht mehr)
        if (arena != null && sceneKind < 0 && bellyState != BELLY_INSIDE &&
            (horizontal(entityPos, groundCenter()) > TribunalBuilder.RADIUS + 140.0 || y < groundCenter().y - 120.0)
        ) {
            val f = focus()
            refreshPositionAndAngles(f.x, f.y + ORBIT_HEIGHT, f.z, yaw, 0f)
            velocity = Vec3d.ZERO
            cast = null
            phaseThrough = 0
            exposed = false
            setPose(WhalePose.SWIM)
        }
        tickHole(world)
        catchFalling(world)
        tickBelly(world)
        tickCollapse(world)
        trackRiders(world)
        if (slippery > 0) slippery--

        if (sceneKind >= 0) {
            tickScene(world)
            return
        }

        val everyone = targets(world)
        if (everyone.isEmpty() && bellyState == BELLY_NONE) {
            if (++lonelyTicks > 600 && health < maxHealth) resetFight(world)
            cast = null
            exposed = false
            cruise(0.5)
            if (castPose() != WhalePose.SWIM) setPose(WhalePose.SWIM)
            return
        }
        lonelyTicks = 0
        target = everyone.minByOrNull { it.squaredDistanceTo(this) }

        checkPhase(world)
        if (sceneKind >= 0) return

        for (i in cooldowns.indices) if (cooldowns[i] > 0) cooldowns[i]--
        if (novaReady > 0) novaReady--
        tickBodyContact(world)

        if (bellyState == BELLY_INSIDE) {
            // Mit vollem Bauch kämpft er kaum: schwebt über den Verschlungenen, draußen Gebliebene bekommen Sternenregen
            val running = cast
            if (running != null) {
                running.body(running.age)
                if (++running.age >= running.duration) cast = null
            } else {
                if (castPose() != WhalePose.GORGED) setPose(WhalePose.GORGED)
                val b = belly
                val over = if (b != null) Vec3d(b.focus().x, groundCenter().y, b.focus().z) else focus()
                fly(over.add(cos(age * 0.01) * 18.0, 26.0, sin(age * 0.01) * 18.0), 0.4, 0.04)
                if (everyone.isNotEmpty() && --recovery <= 0) {
                    recovery = 200
                    start(meteorRain(world))
                }
            }
            return
        }

        if (--nextLine <= 0) {
            nextLine = 460 + random.nextInt(360)
            say(world, "idle${random.nextInt(IDLE_LINES)}")
            sound(world, VoidSounds.WHALE_SONG, 2f, 0.7f + random.nextFloat() * 0.2f)
        }
        if (age % 160 == 0 && cast == null) sound(world, VoidSounds.WHALE_GROAN, 2f, 0.55f + random.nextFloat() * 0.25f)

        val running = cast
        if (running != null) {
            running.body(running.age)
            if (++running.age >= running.duration && cast === running) {
                cast = null
                exposed = false
                slippery = 0
                phaseThrough = 0
                setPose(WhalePose.SWIM)
                syncOrbit()
                recovery = when (phase) { 3 -> 26; 2 -> 34; else -> 44 }
                if (riders.isNotEmpty()) recovery = min(recovery, 20)
                // Phase III: nach einem Angriff ist er oft erschöpft und streift tief über die Plattform
                if (phase >= 3 && running.id != LOW_PASS && running.id != PINNED && running.id != REELING_CAST && random.nextFloat() < 0.45f) {
                    lastCast = LOW_PASS
                    start(lowPass(world, true, null))
                }
            }
            return
        }
        cruise(if (phase >= 3) 0.8 else 0.7, if (phase >= 3) ORBIT_HEIGHT_P3 else ORBIT_HEIGHT)
        if (--recovery <= 0) chooseCast(world)
    }

    private fun resetFight(world: ServerWorld) {
        health = maxHealth
        phase = 1
        dataTracker.set(PHASE, 1)
        endHole(world)
        novaReady = 0
        ramsSinceBeach = 0
        cooldowns.fill(0)
        bellyDone = false
        releaseTethers(world)
    }

    // ---------- Segmente ----------

    private fun updateParts(world: ServerWorld) {
        val pose = castPose()
        val solid = pose != WhalePose.HIDDEN && !isDead && intro >= INTRO_TICKS && slippery <= 0
        val hittable = pose != WhalePose.HIDDEN && !isDead && intro >= INTRO_TICKS && sceneKind < 0
        val flags = (if (solid) FLAG_SOLID else 0) or (if (hittable) FLAG_HITTABLE else 0)
        if (dataTracker.get(FLAGS) != flags) dataTracker.set(FLAGS, flags)
        if (isRemoved) return
        val f = frame()
        for (i in parts.indices) {
            var part = parts[i]
            if (part == null || part.isRemoved) {
                if ((age + i) % 10 != 0) continue
                part = VoidBoss.PART.create(world, SpawnReason.MOB_SUMMONED) ?: continue
                part.setup(this, i)
                part.place(f.boxes[i] ?: Box.of(entityPos, 1.0, 1.0, 1.0))
                if (!world.spawnEntity(part)) continue
                parts[i] = part
            }
            part.place(f.boxes[i])
        }
    }

    private fun discardParts() {
        for (i in parts.indices) {
            parts[i]?.discard()
            parts[i] = null
        }
    }

    /** Hitboxen, wie die Clients sie gerade sehen. */
    private fun seenBoxes(): List<Box> = frame().activeBoxes()

    /** Wer steht auf dem Wal? Gegen die Rückenfläche, die der Client sieht, plus Spielraum (Sprünge, Mace-Schläge). */
    private fun trackRiders(world: ServerWorld) {
        val seen = HashSet<UUID>()
        val f = frame()
        if (castPose() != WhalePose.HIDDEN) {
            for (player in targets(world)) {
                val s = f.surface(player.entityPos, above = 2.5, below = 8.0) ?: continue
                if (player.y < s.y - 2.5 || player.y > s.y + 8.0) continue
                seen += player.uuid
                riders[player.uuid] = (riders[player.uuid] ?: 0) + 1
                lastRide[player.uuid] = age
                // Auf dem Wal gilt man nicht als „fliegend"
                player.networkHandler.resetFloatingTicks()
                if (player.y < s.y + 1.5) player.fallDistance = 0.0
            }
        }
        riders.keys.removeIf { it !in seen && age - (lastRide[it] ?: 0) > 15 }
        if (lastRide.size > 32) lastRide.entries.removeIf { age - it.value > 200 }
    }

    private fun riderPlayers(world: ServerWorld): List<ServerPlayerEntity> = targets(world).filter { it.uuid in riders }

    /** Reiter abwerfen: der Client trägt ihn danach eine Weile nicht mehr mit. */
    private fun fling(player: ServerPlayerEntity, v: Vec3d) {
        VoidVisuals.rideRelease(player, 50)
        riders.remove(player.uuid)
        lastRide.remove(player.uuid)
        launch(player, v)
    }

    // ---------- Fliegen ----------

    private fun fly(goal: Vec3d, speed: Double, agility: Double = 0.06) {
        val to = goal.subtract(entityPos)
        val length = to.length()
        val want = if (length > 0.3) to.multiply(min(speed, length * 0.2 + 0.04) / length) else Vec3d.ZERO
        velocity = velocity.lerp(want, agility)
        velocityDirty = true
        faceVelocity()
    }

    private fun hover(look: Vec3d?, brake: Double = 0.85) {
        velocity = velocity.multiply(brake)
        velocityDirty = true
        if (look != null) faceTowards(look, 3f, 1.5f) else levelOut()
    }

    /** Dreht Körper und Neigung weich in die Flugrichtung; ein Leviathan wendet träge. */
    private fun faceVelocity(maxDown: Float = 40f, maxUp: Float = -28f, rate: Float = 2f) {
        val v = velocity
        if (v.horizontalLengthSquared() > 1.0e-3) {
            val wantYaw = (Math.toDegrees(atan2(-v.x, v.z))).toFloat()
            val diff = abs(MathHelper.wrapDegrees(wantYaw - yaw))
            turnYaw(wantYaw, (diff * 0.08f + 0.3f).coerceAtMost(3.2f))
        }
        val horizontal = sqrt(v.horizontalLengthSquared())
        val wantPitch = if (v.lengthSquared() > 4.0e-3) (-Math.toDegrees(atan2(v.y, horizontal))).toFloat().coerceIn(maxUp, maxDown) else 0f
        val step = (abs(wantPitch - pitch) * 0.07f + 0.2f).coerceAtMost(rate)
        pitch = MathHelper.stepTowards(pitch, wantPitch, step)
    }

    private fun faceTowards(point: Vec3d, yawStep: Float, pitchStep: Float) {
        val d = point.subtract(bodyCenter())
        turnYaw(Math.toDegrees(atan2(-d.x, d.z)).toFloat(), yawStep)
        val horizontal = sqrt(d.x * d.x + d.z * d.z)
        pitch = MathHelper.stepTowards(pitch, (-Math.toDegrees(atan2(d.y, horizontal))).toFloat().coerceIn(-35f, 45f), pitchStep)
    }

    private fun levelOut() {
        pitch = MathHelper.stepTowards(pitch, 0f, 1.5f)
    }

    private fun turnYaw(want: Float, step: Float) {
        val turned = MathHelper.stepUnwrappedAngleTowards(yaw, want, step)
        yaw = turned
        bodyYaw = turned
        headYaw = turned
    }

    private fun yawOf(dir: Vec3d): Float = Math.toDegrees(atan2(-dir.x, dir.z)).toFloat()

    /** Ruhige Kreisbahn über den Spielern; die Höhe atmet langsam. */
    private fun cruise(speed: Double, height: Double = ORBIT_HEIGHT) {
        val c = focus()
        val radius = ORBIT_RADIUS + sin(age * 0.011) * 8.0
        orbitAngle += orbitDir * speed / radius
        if (random.nextInt(3000) == 0) orbitDir = -orbitDir
        val h = height + sin(age * 0.019) * 5.0
        val ahead = orbitAngle + orbitDir * 0.3
        fly(c.add(cos(ahead) * radius, h, sin(ahead) * radius), speed, 0.04)
    }

    private fun syncOrbit() {
        val c = focus()
        orbitAngle = atan2(z - c.z, x - c.x)
    }

    // ---------- Auftritt ----------

    private fun tickIntro(world: ServerWorld) {
        val c = groundCenter()
        val t = ++intro
        if (t == 1) {
            scaleHealth(world, c)
            arena?.let { ArenaRegistry.register(world, it) }
            val nearest = world.players.filter { it.squaredDistanceTo(c) < 160.0 * 160.0 }.minByOrNull { it.squaredDistanceTo(c) }
            introDir = nearest?.entityPos?.subtract(c)?.let { Vec3d(-it.x, 0.0, -it.z) }?.takeIf { it.lengthSquared() > 1.0 }?.normalize()
                ?: Vec3d(1.0, 0.0, 0.0)
            val riftPos = c.add(introDir.multiply(INTRO_RIFT_DISTANCE)).add(0.0, INTRO_RIFT_HEIGHT, 0.0)
            refreshPositionAndAngles(riftPos.x, riftPos.y - BODY_UP, riftPos.z, 0f, 0f)
            faceTowards(c, 360f, 90f)
            velocity = Vec3d.ZERO
            setPose(WhalePose.HIDDEN)
            focus = c
            playCutscene(world, SCENE_INTRO, INTRO_TICKS, c, yawOf(introDir))
            VoidVisuals.rift(world, riftPos, Vec3d(-introDir.z, 0.35, introDir.x), 80f, 150)
            sound(world, riftPos, VoidSounds.RIFT_OPEN, 3f, 0.6f)
            sound(world, riftPos, VoidSounds.WHALE_SONG, 3f, 0.55f)
        }
        when {
            t < INTRO_EMERGE -> velocity = Vec3d.ZERO
            t == INTRO_EMERGE -> {
                setPose(WhalePose.EMERGE)
                sound(world, VoidSounds.WHALE_BREACH, 3f, 0.7f)
                VoidVisuals.nova(world, bodyCenter(), 14f)
            }
            t < INTRO_EMERGE + 60 -> {
                velocity = flatForward().multiply(0.25 + (t - INTRO_EMERGE) * 0.008)
                faceTowards(c.add(0.0, 30.0, 0.0), 1.2f, 0.8f)
            }
            t < INTRO_SONG -> {
                if (t == INTRO_EMERGE + 60) {
                    syncOrbit()
                    orbitDir = 1.0
                }
                orbitAngle += orbitDir * 1.0 / ORBIT_RADIUS
                val ahead = orbitAngle + 0.3
                fly(c.add(cos(ahead) * ORBIT_RADIUS, ORBIT_HEIGHT, sin(ahead) * ORBIT_RADIUS), 1.0, 0.06)
                if (t == INTRO_EMERGE + 120) setPose(WhalePose.SWIM)
            }
            t == INTRO_SONG -> {
                setPose(WhalePose.ROAR)
                hover(c.add(0.0, 10.0, 0.0))
                say(world, "awaken")
                sound(world, VoidSounds.WHALE_SONG, 4f, 0.6f)
                sound(world, VoidSounds.WHALE_GROAN, 4f, 0.5f)
                VoidVisuals.nova(world, mouthPos(), 10f)
            }
            else -> hover(c.add(0.0, 10.0, 0.0), 0.9)
        }
        if (t >= INTRO_TICKS) {
            setPose(WhalePose.SWIM)
            syncOrbit()
            recovery = 50
        }
    }

    private fun scaleHealth(world: ServerWorld, c: Vec3d) {
        val fighters = world.players.count { !it.isSpectator && !it.isCreative && it.squaredDistanceTo(c) < 260.0 * 260.0 }
            .coerceIn(1, MAX_SCALED_PLAYERS)
        val max = BASE_HEALTH + HEALTH_PER_PLAYER * (fighters - 1)
        getAttributeInstance(net.minecraft.entity.attribute.EntityAttributes.MAX_HEALTH)?.baseValue = max
        health = max.toFloat()
    }

    private fun fighters(world: ServerWorld): Int = targets(world).size.coerceAtLeast(1)

    // ---------- Phasen ----------

    private fun checkPhase(world: ServerWorld) {
        if (bellyState != BELLY_NONE) return
        val ratio = health / maxHealth
        when {
            phase == 1 && ratio <= PHASE_2_AT -> startPhase2(world)
            phase == 2 && ratio <= DEVOUR_AT -> if (!bellyDone) startDevour(world) else startPhase3(world)
        }
    }

    private fun beginScene(world: ServerWorld, kind: Int, length: Int, anchor: Vec3d, anchorYaw: Float, viewers: List<ServerPlayerEntity>? = null) {
        sceneKind = kind
        sceneAge = 0
        sceneLength = length
        sceneFlag = false
        cast = null
        exposed = false
        slippery = 0
        endHole(world)
        releaseTethers(world)
        playCutscene(world, kind, length, anchor, anchorYaw, viewers)
    }

    private fun startPhase2(world: ServerWorld) {
        phase = 2
        dataTracker.set(PHASE, 2)
        say(world, "phase2")
        // vorne = vom Schwerpunkt zum Wal: er taucht auf seiner Seite durch den Boden und bricht gegenüber durch
        sceneDir = dirFrom(focus(), entityPos)
        sceneSpot = onFloor(focus().subtract(sceneDir.multiply(58.0)), 30.0)
        beginScene(world, SCENE_PHASE_2, PHASE_2_CUT, focus(), yawOf(sceneDir))
        bossBar.setThickenFog(true)
    }

    private fun startPhase3(world: ServerWorld) {
        phase = 3
        dataTracker.set(PHASE, 3)
        novaReady = 400
        say(world, "phase3")
        beginScene(world, SCENE_PHASE_3, PHASE_3_CUT, focus(), yaw)
    }

    private fun tickScene(world: ServerWorld) {
        val c = focus()
        val a = sceneAge++
        when (sceneKind) {
            SCENE_PHASE_2 -> scenePhase2(world, c, a)
            SCENE_PHASE_3 -> scenePhase3(world, c, a)
            SCENE_DEVOUR -> sceneDevour(world, c, a)
            SCENE_BREAKOUT -> sceneBreakout(world, c, a)
        }
        if (sceneAge >= sceneLength) {
            val ended = sceneKind
            sceneKind = -1
            phaseThrough = 0
            if (castPose() != WhalePose.REELING) setPose(if (bellyState == BELLY_INSIDE) WhalePose.GORGED else WhalePose.SWIM)
            syncOrbit()
            recovery = 30
            if (ended == SCENE_BREAKOUT) start(reeling(world))
        }
    }

    /** „Die Tiefe erwacht": brüllt, taucht durch den Boden, die Plattform reißt, Inseln stürzen, er bricht gegenüber durch. */
    private fun scenePhase2(world: ServerWorld, c: Vec3d, a: Int) {
        val floor = groundCenter().y
        val dive = onFloor(c.add(sceneDir.multiply(28.0)), 30.0)
        when {
            a == 0 -> {
                setPose(WhalePose.ROAR)
                sound(world, VoidSounds.WHALE_GROAN, 4f, 0.45f)
                sound(world, VoidSounds.WHALE_SONG, 4f, 0.5f)
                VoidVisuals.nova(world, mouthPos(), 16f)
                VoidVisuals.starfall(world, c, 110.0, 140, 190)
                for (p in players(world, 260.0)) VoidVisuals.screen(p, 2, 30)
                hover(c, 0.6)
            }
            a < 20 -> hover(c, 0.8)
            a < 60 -> {
                if (a == 20) setPose(WhalePose.DIVE)
                phaseThrough = 3
                fly(dive.add(0.0, -70.0, 0.0), 2.6, 0.16)
                faceVelocity(maxDown = 80f, rate = 4f)
                if (!sceneFlag && mouthPos().y < floor + 2.0) {
                    sceneFlag = true
                    diveImpact(world, dive, scene = true)
                }
            }
            a == 60 -> {
                refreshPositionAndAngles(sceneSpot.x, floor - 80.0, sceneSpot.z, yaw, -80f)
                velocity = Vec3d.ZERO
                sceneFlag = false
                VoidVisuals.zone(world, sceneSpot, 16.0, 40, 0)
                for (i in 0 until 8) {
                    val ang = i * PI / 4 + 0.2
                    VoidVisuals.crack(world, c.add(cos(ang) * 6.0, 0.05, sin(ang) * 6.0), c.add(cos(ang) * 64.0, 0.05, sin(ang) * 64.0), 40, 400)
                }
                startCollapse(world)
                sound(world, c, VoidSounds.GRAVITY_WAVE, 4f, 0.4f)
            }
            a < 100 -> {
                velocity = Vec3d.ZERO
                pitch = -80f
                if (a % 10 == 0) {
                    sound(world, sceneSpot, VoidSounds.WHALE_GROAN, 4f, 0.4f)
                    VoidVisuals.stomp(world, sceneSpot, 0.6f)
                }
            }
            a == 100 -> {
                setPose(WhalePose.BREACH)
                velocity = Vec3d(0.0, 2.8, 0.0)
                pitch = -80f
                phaseThrough = 3
            }
            a < 140 -> {
                if (a < 132) phaseThrough = 3
                if (y + BODY_UP < floor + 45.0) {
                    velocity = Vec3d(0.0, 2.8 * (1.0 - (a - 100) / 60.0).coerceAtLeast(0.4), 0.0)
                    pitch = -80f
                } else {
                    hover(c, 0.85)
                }
                if (!sceneFlag && mouthPos().y > floor - 8.0) breachImpact(world, sceneSpot, scene = true)
                if (a == 130) {
                    setPose(WhalePose.ROAR)
                    sound(world, VoidSounds.WHALE_SONG, 4f, 0.45f)
                }
            }
            else -> {
                if (a == 140) setPose(WhalePose.SWIM)
                cruise(1.0)
            }
        }
    }

    private fun scenePhase3(world: ServerWorld, c: Vec3d, a: Int) {
        when {
            a < 24 -> fly(c.add(0.0, 30.0, 0.0), 1.0, 0.1)
            a == 24 -> {
                setPose(WhalePose.ROAR)
                VoidVisuals.charge(world, heartPos(), 22)
                sound(world, VoidSounds.SUPERNOVA_CHARGE, 3f, 1.1f)
            }
            a == 46 -> {
                VoidVisuals.nova(world, heartPos(), 26f)
                VoidVisuals.skyBeams(world, bodyCenter(), 60)
                VoidVisuals.starfall(world, c, 100.0, 90, 80)
                sound(world, VoidSounds.SUPERNOVA_BLAST, 3f, 0.8f)
                summonStarlings(world, 4)
            }
            else -> hover(c.add(0.0, 8.0, 0.0))
        }
    }

    // ---------- Einstürzende Inseln ----------

    private fun startCollapse(world: ServerWorld) {
        val center = arena ?: return
        for (spot in TribunalBuilder.collapseBlocks()) collapse.add(center.add(spot))
        collapse.shuffle(java.util.Random(age.toLong()))
        collapse.sortBy { it.y }
    }

    private fun tickCollapse(world: ServerWorld) {
        if (collapse.isEmpty()) return
        var n = 0
        while (collapse.isNotEmpty() && n < 40) {
            val pos = collapse.removeAt(collapse.size - 1)
            val state = world.getBlockState(pos)
            if (state.isAir || state.isOf(VoidBoss.ALTAR) || state.hasBlockEntity() || !state.fluidState.isEmpty) continue
            if (n % 3 == 0) {
                val falling = FallingBlockEntity.spawnFromBlock(world, pos, state)
                falling.dropItem = false
                falling.velocity = Vec3d((random.nextDouble() - 0.5) * 0.3, -0.1, (random.nextDouble() - 0.5) * 0.3)
            } else {
                world.setBlockState(pos, net.minecraft.block.Blocks.AIR.defaultState, Block.NOTIFY_LISTENERS)
                if (n % 2 == 0) world.syncWorldEvent(2001, pos, Block.getRawIdFromState(state))
            }
            n++
        }
    }

    // ---------- Angriffe ----------

    private fun chooseCast(world: ServerWorld) {
        val riding = riders.values.maxOrNull() ?: 0
        if (riding >= RIDER_PATIENCE) {
            val shake = arrayListOf(BARREL_ROLL, BACK_QUAKE, TAIL_SWEEP)
            if (phase >= 2) shake += SOUNDING
            shake.remove(lastCast)
            val pick = shake[random.nextInt(shake.size)]
            lastCast = pick
            start(build(world, pick, pickVictim(world) ?: return) ?: return)
            return
        }
        if (phase >= 3 && novaReady <= 0) {
            novaReady = NOVA_COOLDOWN
            lastCast = SUPERNOVA
            start(supernova(world))
            return
        }
        val options = ArrayList<Int>()
        fun offer(id: Int, weight: Int = 1) {
            if (cooldowns[id] <= 0 && id != lastCast) repeat(weight) { options += id }
        }
        offer(TAIL_SLAM, 2)
        offer(BREATH, 2)
        // während er Reiter trägt, nicht rollen (Sternenregen ist ein Ritual mit 90° Schräglage)
        if (riders.isEmpty()) offer(METEORS, 2)
        offer(RAM, 3)
        offer(SNAP, 2)
        offer(GEYSER, 1)
        offer(LOW_PASS, 1)
        if (phase >= 2) {
            offer(MAELSTROM, 2)
            offer(BREACH, 3)
            offer(FLOOD, 2)
            offer(ERUPTION, 2)
            if (starlings(world) < 3) offer(STARLINGS)
        }
        if (phase >= 3) {
            offer(RAM, 1)
            offer(ERUPTION, 1)
            offer(LOW_PASS, 1)
        }
        if (options.isEmpty()) options += RAM
        val pick = options[random.nextInt(options.size)]
        lastCast = pick
        cooldowns[pick] = when (pick) {
            MAELSTROM -> 560
            FLOOD -> 520
            BREACH -> 420
            STARLINGS -> 700
            GEYSER -> 420
            ERUPTION -> 460
            LOW_PASS -> 300
            else -> 0
        }
        val victim = pickVictim(world) ?: return
        start(build(world, pick, victim) ?: return)
    }

    private fun build(world: ServerWorld, id: Int, victim: ServerPlayerEntity): Cast? = when (id) {
        TAIL_SLAM -> flukeSlam(world, victim)
        BREATH -> starBreath(world, victim)
        METEORS -> meteorRain(world)
        RAM -> ram(world, victim)
        MAELSTROM -> maelstrom(world, victim)
        BREACH -> breach(world, victim)
        SNAP -> snap(world, victim)
        STARLINGS -> callStarlings(world)
        SUPERNOVA -> supernova(world)
        GEYSER -> geyser(world)
        FLOOD -> flood(world, victim)
        BARREL_ROLL -> barrelRoll(world)
        BACK_QUAKE -> backQuake(world)
        TAIL_SWEEP -> tailSweep(world)
        SOUNDING -> sounding(world)
        ERUPTION -> eruption(world)
        PINNED -> pinned(world)
        LOW_PASS -> lowPass(world, false, victim)
        else -> null
    }

    /** Nur für Tests (`/voidboss attack <n>`): startet sofort einen bestimmten Angriff (Nummern siehe [ATTACK_NAMES]). */
    fun debugAttack(world: ServerWorld, attack: Int): Boolean {
        if (intro < INTRO_TICKS || sceneKind >= 0 || isDead) return false
        if (attack == DEVOUR) {
            if (bellyState != BELLY_NONE) return false
            if (phase < 2) {
                phase = 2
                dataTracker.set(PHASE, 2)
            }
            startDevour(world)
            return true
        }
        if (bellyState != BELLY_NONE && bellyState != BELLY_INSIDE) return false
        val victim = pickVictim(world) ?: return false
        if (attack == RAM) ramsSinceBeach = 5
        if (attack == PINNED) pinCooldown = 0
        exposed = false
        slippery = 0
        phaseThrough = 0
        cast = build(world, attack, victim) ?: return false
        return true
    }

    /**
     * Nur für Tests (`/voidboss phase <n>`): 2 = Tiefe, 3 = Verschlingen/Bauch, 4 = Bauch bis zur Herzkammer überspringen,
     * 5 = Ziele des aktuellen Bauch-Abschnitts zerstören (Abschnitt für Abschnitt durchspielen).
     */
    fun debugPhase(world: ServerWorld, next: Int): Boolean {
        if (intro < INTRO_TICKS || isDead) return false
        if (next >= 4) {
            val b = belly ?: return false
            if (bellyState != BELLY_INSIDE) return false
            if (next == 4) {
                b.skipToHeart(this)
                for (id in swallowed) world.server!!.playerManager.getPlayer(id)?.let { p ->
                    val e = b.entry(4)
                    p.teleport(world, e.x, e.y + 0.1, e.z, emptySet(), p.yaw, 0f, false)
                }
            } else {
                b.killSection(world)
            }
            return true
        }
        if (sceneKind >= 0) return false
        return when (next) {
            2 -> { if (phase < 2) startPhase2(world); true }
            3 -> debugAttack(world, DEVOUR)
            else -> false
        }
    }

    private fun pickVictim(world: ServerWorld): ServerPlayerEntity? {
        val list = targets(world)
        if (list.isEmpty()) return null
        return if (random.nextInt(3) == 0) list[random.nextInt(list.size)] else list.minByOrNull { it.squaredDistanceTo(this) }
    }

    private fun start(next: Cast) {
        cast = next
    }

    private fun onFloor(around: Vec3d, margin: Double = 2.0): Vec3d {
        val c = groundCenter()
        var dx = around.x - c.x
        var dz = around.z - c.z
        val r = sqrt(dx * dx + dz * dz)
        val max = floorRadius() - margin
        if (r > max && r > 0.01) {
            dx *= max / r
            dz *= max / r
        }
        return Vec3d(c.x + dx, c.y, c.z + dz)
    }

    /** Waagerechte Richtung von [from] nach [to] (sonst Blickrichtung). */
    private fun dirFrom(from: Vec3d, to: Vec3d): Vec3d =
        Vec3d(to.x - from.x, 0.0, to.z - from.z).takeIf { it.lengthSquared() > 1.0 }?.normalize() ?: flatForward()

    private fun dirFromCenter(p: Vec3d): Vec3d = dirFrom(groundCenter(), p)

    /** Steht der Spieler auf dem Plattformboden (nicht auf Monolithen, Bastionen, Außenplattformen)? */
    private fun onArenaFloor(p: ServerPlayerEntity, height: Double = 1.2): Boolean = p.y - groundCenter().y < height

    /**
     * 0 — Flukenschlag: Er stellt sich quer über das Ziel, hebt die Fluke 13 Blöcke über den Rücken und schlägt sie auf
     * die Plattform. Der Kreis liegt genau unter der Fluke (aus den Knochen vorausberechnet). Dazu eine Welle, über die
     * man springen muss (Phase III: zwei).
     */
    private fun flukeSlam(world: ServerWorld, victim: ServerPlayerEntity): Cast {
        val approach = 40
        val slamAt = approach + 28
        val rings = if (phase >= 3) 2 else 1
        val spot = onFloor(victim.entityPos, 8.0)
        val d = dirFrom(spot, entityPos)
        val faceYaw = yawOf(d)
        // grob: Körpermitte 27 Blöcke vor der Fluke; dann mit der vorausberechneten Fluke beim Einschlag nachjustieren
        val guess = spot.add(d.multiply(27.0)).add(0.0, 7.0 - BODY_UP, 0.0)
        val hit = predict(guess, faceYaw, 0f, WhalePose.TAIL_SLAM, 28f).fluke()
        val hoverAt = guess.add(spot.x - hit.x, 0.0, spot.z - hit.z)
        val hits = HashSet<UUID>()
        var waveOrigin = spot
        return Cast(TAIL_SLAM, slamAt + CLIENT_LAG + 24 + rings * 16) { age ->
            when {
                age < approach -> {
                    if (age == 0) setPose(WhalePose.DIVE)
                    fly(hoverAt, 1.8, 0.14)
                    if (age > approach - 16) turnYaw(faceYaw, 6f)
                }
                age == approach -> {
                    setPose(WhalePose.TAIL_SLAM)
                    if (random.nextInt(3) == 0) say(world, "gravity")
                    sound(world, VoidSounds.WHALE_GROAN, 3f, 0.5f)
                    // tatsächliche Stelle unter der Fluke beim Einschlag
                    waveOrigin = predict(entityPos, yaw, pitch, WhalePose.TAIL_SLAM, 28f).fluke().let { Vec3d(it.x, spot.y, it.z) }
                    VoidVisuals.zone(world, waveOrigin, SLAM_RADIUS, 28 + CLIENT_LAG, 0)
                }
                age < slamAt -> {
                    velocity = velocity.multiply(0.6)
                    velocityDirty = true
                    turnYaw(faceYaw, 2f)
                    levelOut()
                }
                else -> hover(null, 0.7)
            }
            if (age == slamAt + CLIENT_LAG) {
                val f = frame().fluke()
                val at = Vec3d(f.x, spot.y, f.z)
                for (player in targets(world)) {
                    if (player.squaredDistanceTo(at.x, player.y, at.z) < SLAM_RADIUS * SLAM_RADIUS && player.y - spot.y < 7.0) {
                        hurtPlayer(world, player, if (phase >= 3) 30f else 26f, physical = true)
                        launch(player, Vec3d(0.0, 1.5, 0.0).add(dirFrom(at, player.entityPos).multiply(0.6)))
                        hits += player.uuid
                    }
                }
                VoidVisuals.nova(world, at.add(0.0, 1.0, 0.0), 12f)
            }
            for (ring in 0 until rings) {
                val t0 = slamAt + CLIENT_LAG + ring * 16
                if (age == t0) {
                    if (ring > 0) hits.clear()
                    VoidVisuals.shockwave(world, waveOrigin.add(0.0, 0.5, 0.0), waveOrigin, WAVE_RANGE)
                    VoidVisuals.stomp(world, waveOrigin, 1f)
                    VoidVisuals.crackStar(world, waveOrigin, 8, 18.0, 10, 80)
                    sound(world, waveOrigin, VoidSounds.GRAVITY_WAVE, 4f, if (ring == 0) 0.7f else 0.6f)
                }
                val since = age - t0
                if (since in 0..WAVE_TICKS) {
                    val k = since / WAVE_TICKS.toDouble()
                    val radius = WAVE_RANGE * (1.0 - (1.0 - k) * (1.0 - k) * (1.0 - k))
                    for (player in targets(world)) {
                        if (player.uuid in hits) continue
                        val dx = player.x - waveOrigin.x
                        val dz = player.z - waveOrigin.z
                        val distance = sqrt(dx * dx + dz * dz)
                        if (player.y - waveOrigin.y < 1.1 && abs(distance - radius) < 2.2) {
                            hits += player.uuid
                            hurtPlayer(world, player, if (phase >= 3) 16f else 14f, physical = true)
                            val out = Vec3d(dx, 0.0, dz).normalize()
                            launch(player, Vec3d(out.x * 0.7, 1.15, out.z * 0.7))
                        }
                    }
                }
            }
        }
    }

    /** Bahn des Sternenatems am Boden: [k] Ticks nach Beginn des Feuers (gleiche Formel wie der Client). */
    private fun breathPoint(a: Vec3d, b: Vec3d, k: Int, fire: Int, passes: Int): Vec3d {
        val half = fire.toDouble() / passes
        var u = (k / half).coerceIn(0.0, passes.toDouble())
        if (u > 1.0) u = 2.0 - u
        val e = u * u * (3 - 2 * u)
        return a.lerp(b, e)
    }

    /**
     * 1 — Sternenatem: Er stellt sich schräg über die Gruppe, das Maul lädt sichtbar auf, eine flackernde Bahn zeigt am
     * Boden, wo der Strahl entlangfegen wird. Dann schießt ein dicker Strahl aus dem Maul und fegt quer über die Bahn
     * (Phase III: hin und zurück). Deckung schützt (der Strahl endet an Blöcken). Wo er den Boden trifft, bleibt eine
     * brennende Spur – am Ende zerspringt sie in Sternensplitter, die aus dem Boden brechen.
     */
    private fun starBreath(world: ServerWorld, victim: ServerPlayerEntity): Cast {
        val approach = 46
        val charge = 25
        val passes = if (phase >= 3) 2 else 1
        val fire = if (phase >= 3) 108 else 52
        val floor = groundCenter().y
        val view = dirFrom(entityPos, victim.entityPos)
        var station = victim.entityPos.subtract(view.multiply(44.0)).let { Vec3d(it.x, floor + 20.0 - BODY_UP, it.z) }
        var pathA = Vec3d.ZERO
        var pathB = Vec3d.ZERO
        val hitUntil = HashMap<UUID, Int>()
        val burnUntil = HashMap<UUID, Int>()
        val trail = ArrayList<Pair<Vec3d, Int>>()
        var started = -1
        return Cast(BREATH, approach + charge + fire + 50) { age ->
            if (started < 0) {
                if (age == 0) setPose(WhalePose.SWIM)
                fly(station, 2.2, 0.14)
                if (age > 12) faceTowards(victim.entityPos, 6f, 2f)
                if (age >= approach || (squaredDistanceTo(station) < 36.0 && age > 16)) {
                    started = age
                    setPose(WhalePose.BREATH)
                    if (random.nextInt(2) == 0) say(world, "breath")
                    // Bahn: quer zur Blickrichtung durch die aktuelle Stelle des Opfers, leicht schräg
                    val v = dirFrom(entityPos, victim.entityPos)
                    val across = Vec3d(-v.z, 0.0, v.x).multiply(if (random.nextBoolean()) 1.0 else -1.0)
                    val c0 = onFloor(victim.entityPos, 4.0)
                    pathA = onFloor(c0.subtract(across.multiply(BREATH_HALF)).subtract(v.multiply(7.0)), 2.0)
                    pathB = onFloor(c0.add(across.multiply(BREATH_HALF)).add(v.multiply(7.0)), 2.0)
                    VoidVisuals.starBreath(world, id, pathA, pathB, charge, fire, passes, 1.7f)
                    VoidVisuals.charge(world, mouthPos(), charge)
                    sound(world, VoidSounds.STAR_BREATH, 3f, 0.55f)
                    sound(world, VoidSounds.SUPERNOVA_CHARGE, 2.5f, 1.4f)
                    for (p in players(world)) p.sendMessage(Text.translatable("hha_void.boss.msg.breath").formatted(Formatting.LIGHT_PURPLE, Formatting.BOLD), true)
                }
                return@Cast
            }
            val a = age - started
            velocity = velocity.multiply(0.7)
            velocityDirty = true
            // während des Feuers den Kopf grob zur Strahlmitte halten (die Bahn läuft am Boden)
            faceTowards(pathA.lerp(pathB, 0.5), 1.2f, 1.2f)
            val k = a - charge - CLIENT_LAG
            if (k in 0 until fire) {
                val mouth = frame().mouth()
                val g = breathPoint(pathA, pathB, k, fire, passes).add(0.0, 0.2, 0.0)
                val ray = world.raycast(RaycastContext(mouth, g, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, this))
                val end = if (ray.type == HitResult.Type.MISS) g else ray.pos
                if (k % 2 == 0) trail += end to age
                if (k % 14 == 0) sound(world, end, VoidSounds.STAR_BREATH, 2.5f, 0.9f)
                if (k % 3 == 0) VoidVisuals.burst(world, end, 1)
                for (player in targets(world)) {
                    if ((hitUntil[player.uuid] ?: 0) > age) continue
                    val body = player.entityPos.add(0.0, 0.9, 0.0)
                    val onBeam = segmentDistance(body, mouth, end) < BREATH_RADIUS
                    val atFoot = body.squaredDistanceTo(end) < 3.4 * 3.4
                    if (onBeam || atFoot) {
                        hitUntil[player.uuid] = age + 10
                        hurtPlayer(world, player, if (phase >= 3) 17f else 14f, physical = false)
                        val push = dirFrom(end, player.entityPos)
                        launch(player, Vec3d(push.x * 0.7, 0.55, push.z * 0.7))
                        VoidVisuals.impact(world, body, 1.6f)
                    }
                }
            }
            // die Brandspur brennt nach
            if (a % 5 == 0 && trail.isNotEmpty()) {
                trail.removeIf { age - it.second > 70 }
                for (player in targets(world)) {
                    if ((burnUntil[player.uuid] ?: 0) > age || !onArenaFloor(player, 1.5)) continue
                    if (trail.any { it.first.squaredDistanceTo(player.x, it.first.y, player.z) < 1.8 * 1.8 }) {
                        burnUntil[player.uuid] = age + 10
                        hurtPlayer(world, player, 3f, physical = false)
                    }
                }
            }
            // die Spur zerspringt in Sternensplitter
            if (k == fire + 2) {
                val spots = ArrayList<Vec3d>()
                var last: Vec3d? = null
                for ((p, _) in trail) if (last == null || p.squaredDistanceTo(last) > 5.5 * 5.5) { spots += p; last = p }
                for (s in spots) {
                    val ground = Vec3d(s.x, floor, s.z)
                    VoidVisuals.pillar(world, ground, 14, 7f, light = true)
                    TickScheduler.schedule(14) {
                        if (!isAlive) return@schedule
                        for (player in targets(world)) {
                            if (player.squaredDistanceTo(ground.x, player.y, ground.z) < 3.0 * 3.0 && abs(player.y - ground.y) < 3.0) {
                                hurtPlayer(world, player, 10f, physical = false)
                                launch(player, Vec3d(0.0, 1.1, 0.0))
                            }
                        }
                    }
                }
                sound(world, pathA.lerp(pathB, 0.5), SoundEvents.BLOCK_AMETHYST_BLOCK_BREAK, 4f, 0.6f)
            }
            if (k >= fire + 20) cast?.let { it.age = it.duration }
        }
    }

    private fun segmentDistance(p: Vec3d, a: Vec3d, b: Vec3d): Double {
        val ab = b.subtract(a)
        val l2 = ab.lengthSquared()
        val t = if (l2 < 1e-6) 0.0 else (p.subtract(a).dotProduct(ab) / l2).coerceIn(0.0, 1.0)
        return p.distanceTo(a.add(ab.multiply(t)))
    }

    /** 2 — Sternenregen: Brocken alter Welten stürzen auf markierte Stellen – auf jedes Ziel und um die Gruppe verstreut. */
    private fun meteorRain(world: ServerWorld): Cast {
        val waves = 2 + phase
        return Cast(METEORS, WhalePose.duration(WhalePose.DEBRIS) + 30) { age ->
            if (age == 0) {
                setPose(WhalePose.DEBRIS)
                if (random.nextInt(2) == 0) say(world, "debris")
                sound(world, VoidSounds.DEBRIS, 3f, 0.5f)
                VoidVisuals.starfall(world, focus(), 80.0, 40, 80)
            }
            cruise(0.35)
            if (age >= 8 && (age - 8) % 14 == 0 && (age - 8) / 14 < waves) {
                val spots = ArrayList<Vec3d>()
                for (player in targets(world)) {
                    val lead = player.entityPos.add(player.velocity.x * 12.0, 0.0, player.velocity.z * 12.0)
                    spots += findGround(world, lead) ?: continue
                }
                repeat(3 + phase * 2) {
                    val angle = random.nextDouble() * PI * 2.0
                    val r = sqrt(random.nextDouble()) * 55.0
                    findGround(world, onFloor(focus().add(cos(angle) * r, 0.0, sin(angle) * r)).add(0.0, 2.0, 0.0))?.let { spots += it }
                }
                for (spot in spots) dropRock(world, spot, 26)
            }
        }
    }

    private fun dropRock(world: ServerWorld, ground: Vec3d, delay: Int) {
        VoidVisuals.riftRock(world, ground, delay)
        TickScheduler.schedule(delay) {
            if (!isAlive) return@schedule
            sound(world, ground, VoidSounds.DEBRIS, 2f, 0.7f + random.nextFloat() * 0.4f)
            VoidVisuals.impact(world, ground.add(0.0, 0.2, 0.0), 3.4f)
            for (player in players(world)) {
                if (player.uuid in swallowed) continue
                val dx = player.x - ground.x
                val dz = player.z - ground.z
                if (dx * dx + dz * dz < ROCK_RADIUS * ROCK_RADIUS && abs(player.y - ground.y) < 3.5) {
                    hurtPlayer(world, player, 16f, physical = true)
                    launch(player, Vec3d(dx * 0.14, 0.7, dz * 0.14))
                }
            }
        }
    }

    /**
     * 3 — Rammstoß (Moby Dick): Er schwenkt auf das Ziel ein, die Bahn leuchtet sofort am Boden auf; nach kurzem Lauern
     * (0,6 s) jagt er mit 3,3 Blöcken/Tick dicht über der Plattform hindurch. Treffer entlang aller sichtbaren Boxen
     * (auch zwischen zwei Ticks, kein Durchrutschen). Jeder zweite endet mit einer Bruchlandung beim Ziel: gestrandet,
     * Herz offen.
     */
    private fun ram(world: ServerWorld, victim: ServerPlayerEntity): Cast {
        val c = groundCenter()
        val beach = arena != null && ramsSinceBeach >= 1
        if (beach) ramsSinceBeach = 0 else ramsSinceBeach++
        val through = onFloor(victim.entityPos, 24.0)
        val dir = pickRamDir(through, dirFrom(entityPos, through))
        val startPos = through.subtract(dir.multiply(RAM_RUNUP)).let { Vec3d(it.x, c.y + RAM_Y, it.z) }
        val endPos = through.add(dir.multiply(RAM_LENGTH - RAM_RUNUP)).let { Vec3d(it.x, c.y + RAM_Y, it.z) }
        val lineUp = 34
        val warn = 12
        val speed = if (phase >= 3) 3.7 else 3.3
        val beachedTicks = WhalePose.duration(WhalePose.BEACHED)
        var chargeStart = -1
        var beachStart = -1
        var lurk = -1
        bodyHits.clear()
        return Cast(RAM, lineUp + warn + 60 + if (beach) beachedTicks + 20 else 0) { age ->
            when {
                lurk < 0 -> {
                    if (age == 0) {
                        setPose(WhalePose.SWIM)
                        say(world, "ram")
                        sound(world, VoidSounds.WHALE_GROAN, 4f, 0.4f)
                        VoidVisuals.line(world, Vec3d(startPos.x, c.y, startPos.z), Vec3d(endPos.x, c.y, endPos.z), RAM_HALF_WIDTH, lineUp + warn)
                        for (p in players(world)) p.sendMessage(Text.translatable("hha_void.boss.msg.ram").formatted(Formatting.RED, Formatting.BOLD), true)
                    }
                    val far = squaredDistanceTo(startPos) > 50.0 * 50.0
                    fly(if (far) startPos.add(0.0, 18.0, 0.0) else startPos, 3.2, 0.22)
                    if (!far) turnYaw(yawOf(dir), 7f)
                    if (age >= lineUp || (!far && squaredDistanceTo(startPos) < 5.0 * 5.0)) {
                        lurk = age
                        setPose(WhalePose.RAM)
                    }
                }
                age < lurk + warn -> {
                    // lauert, Kopf gesenkt, schnaubt
                    fly(startPos, 0.5, 0.3)
                    turnYaw(yawOf(dir), 8f)
                    pitch = MathHelper.stepTowards(pitch, 4f, 2f)
                    if ((age - lurk) % 6 == 0) sound(world, VoidSounds.WHALE_HURT, 2.5f, 0.4f)
                }
                beachStart < 0 -> {
                    if (chargeStart < 0) {
                        chargeStart = age
                        sound(world, VoidSounds.WHALE_BREACH, 4f, 0.55f)
                        VoidVisuals.nova(world, bodyCenter(), 8f)
                    }
                    velocity = velocity.lerp(dir.multiply(speed), 0.5).let { Vec3d(it.x, (c.y + RAM_Y - y) * 0.3, it.z) }
                    velocityDirty = true
                    turnYaw(yawOf(dir), 4f)
                    pitch = MathHelper.stepTowards(pitch, 0f, 2f)
                    hitAlongBody(world, if (phase >= 3) 30f else 26f, heavy = true, sweep = true)
                    // Rammbock: niedrige Bauten auf der Bahn zerbersten (wachsen später nach), statt dass er drüberhüpft
                    phaseThrough = 2
                    if (age % 2 == 0) crushUnderBody(world, heal = 160, ahead = velocity.multiply(3.0))
                    if (age % 4 == 0) VoidVisuals.stomp(world, Vec3d(x, c.y, z), 0.35f)
                    val travelled = (x - startPos.x) * dir.x + (z - startPos.z) * dir.z
                    // rammt durch das Ziel und strandet dahinter (wer noch drunter steht, landet oben)
                    if (beach && travelled >= RAM_RUNUP + 8.0) {
                        beachStart = age
                        crash(world, onFloor(Vec3d(x, c.y, z), 30.0), yawOf(dir))
                    } else if (travelled > RAM_LENGTH - 8.0 || age - chargeStart > 60) {
                        cast?.let { it.age = it.duration - 26 }
                        beachStart = Int.MAX_VALUE
                    }
                }
                beachStart == Int.MAX_VALUE -> fly(focus().add(dir.multiply(ORBIT_RADIUS)).add(0.0, ORBIT_HEIGHT, 0.0), 1.6, 0.08)
                else -> beachedTick(world, age - beachStart, beachedTicks)
            }
        }
    }

    /** Bruchlandung bei [at]: gestrandet, Herz offen, Schockwelle. */
    private fun crash(world: ServerWorld, at: Vec3d, faceYaw: Float) {
        val c = groundCenter()
        refreshPositionAndAngles(at.x, c.y + LeviathanBody.BEACH_LIFT, at.z, faceYaw, 0f)
        bodyYaw = faceYaw
        headYaw = faceYaw
        velocity = Vec3d.ZERO
        exposed = true
        setPose(WhalePose.BEACHED)
        crushUnderBody(world)
        val ground = Vec3d(at.x, c.y, at.z)
        VoidVisuals.shockwave(world, ground.add(0.0, 0.5, 0.0), ground, 34.0)
        VoidVisuals.stomp(world, ground, 1f)
        VoidVisuals.crackStar(world, ground, 10, 24.0, 10, 160)
        sound(world, VoidSounds.GRAVITY_WAVE, 4f, 0.5f)
        sound(world, VoidSounds.WHALE_GROAN, 4f, 0.4f)
        for (player in targets(world)) {
            if (player.uuid in riders) continue
            if (player.squaredDistanceTo(ground) < 16.0 * 16.0 && player.y - c.y < 3.0) {
                hurtPlayer(world, player, 14f, physical = true)
                val out = dirFrom(ground, player.entityPos)
                launch(player, Vec3d(out.x * 2.1, 1.0, out.z * 2.1))
            }
        }
        // wer unter dem liegenden Körper stecken würde, landet oben auf ihm (nie im Wal eingeklemmt)
        val lying = predict(entityPos, faceYaw, 0f, WhalePose.BEACHED, 10f)
        val bodies = lying.terrainBoxes()
        for (player in targets(world)) {
            if (bodies.none { it.intersects(player.boundingBox) }) continue
            val top = lying.surface(player.entityPos.add(0.0, 30.0, 0.0), above = 0.0, below = 60.0)
            if (top != null) player.requestTeleport(player.x, top.y + 0.2, player.z)
            else launch(player, dirFrom(ground, player.entityPos).multiply(2.5).add(0.0, 1.2, 0.0))
        }
        for (player in players(world)) {
            player.sendMessage(Text.translatable("hha_void.boss.msg.beached").formatted(Formatting.GOLD, Formatting.BOLD), true)
        }
    }

    /** Gestrandet liegen und sich am Ende hochstemmen; beendet den Angriff. */
    private fun beachedTick(world: ServerWorld, bt: Int, beachedTicks: Int) {
        if (bt < beachedTicks - 20) {
            velocity = Vec3d.ZERO
            pitch = 0f
            if (bt % 30 == 15) sound(world, VoidSounds.WHALE_GROAN, 3f, 0.4f + random.nextFloat() * 0.2f)
            if (bt % 40 == 20) VoidVisuals.charge(world, heartPos(), 20)
        } else {
            if (bt == beachedTicks - 20) {
                exposed = false
                sound(world, VoidSounds.WHALE_SONG, 3f, 0.8f)
                VoidVisuals.nova(world, bodyCenter(), 10f)
                releaseTethers(world)
                for (p in riderPlayers(world)) fling(p, rightVec().multiply(if (random.nextBoolean()) 1.2 else -1.2).add(0.0, 0.9, 0.0))
            }
            velocity = velocity.lerp(Vec3d(0.0, 0.8, 0.0).add(flatForward().multiply(0.4)), 0.15)
            velocityDirty = true
            if (bt >= beachedTicks + 10) cast?.let { it.age = it.duration }
        }
    }

    /** Richtung für den Rammstoß nahe [preferred] mit möglichst viel Abstand zu Monolithen und Bastionen. */
    private fun pickRamDir(through: Vec3d, preferred: Vec3d): Vec3d {
        val c = groundCenter()
        val base = atan2(preferred.z, preferred.x)
        var best = preferred
        var bestScore = -1e9
        for (i in -6..6) {
            val a = base + i * 0.12
            val d = Vec3d(cos(a), 0.0, sin(a))
            var clearance = 1e9
            for ((ox, oz, r) in TribunalBuilder.OBSTACLES) {
                val m = Vec3d(c.x + ox - through.x, 0.0, c.z + oz - through.z)
                val along = m.x * d.x + m.z * d.z
                if (along < -RAM_RUNUP - 10 || along > RAM_LENGTH - RAM_RUNUP + 10) continue
                val lateral = abs(m.x * d.z - m.z * d.x)
                clearance = min(clearance, lateral - r)
            }
            val score = min(clearance, 14.0) - abs(i) * 0.6
            if (score > bestScore) {
                bestScore = score
                best = d
            }
        }
        return best
    }

    /**
     * 4 — Mahlstrom: Nahe der Gruppe reißt er das Maul auf, ein Sog zieht alle zu ihm, die freie Sicht auf sein Maul
     * haben (Monolithen schützen). Wer das Maul erreicht, wird zermalmt und ausgespuckt.
     */
    private fun maelstrom(world: ServerWorld, victim: ServerPlayerEntity): Cast {
        val c = groundCenter()
        val side = dirFrom(victim.entityPos, entityPos)
        val station = onFloor(victim.entityPos.add(side.multiply(50.0)), 6.0).add(0.0, 4.0, 0.0)
        val approach = 40
        val length = WhalePose.duration(WhalePose.INHALE)
        return Cast(MAELSTROM, approach + length) { age ->
            if (age < approach) {
                if (age == 0) setPose(WhalePose.SWIM)
                fly(station, 2.0, 0.12)
                if (age > approach - 18) faceTowards(victim.entityPos, 5f, 2f)
                return@Cast
            }
            val a = age - approach
            hover(focus(), 0.8)
            if (a == 0) {
                setPose(WhalePose.INHALE)
                say(world, "maelstrom")
                val holeAt = mouthPos().add(frame().headForward().multiply(10.0))
                holeId = VoidVisuals.newId()
                VoidVisuals.blackHole(world, holeId, holeAt, onFloor(holeAt, 0.0).let { Vec3d(it.x, c.y, it.z) }, length - 6)
                for (player in targets(world)) VoidVisuals.holeTether(world, holeId, player.id, holeAt)
                holeCenter = holeAt
                holeTicks = length - 6
                sound(world, holeAt, VoidSounds.BLACK_HOLE, 4f, 0.6f)
            }
            if (a == 50) sound(world, VoidSounds.BLACK_HOLE, 4f, 0.5f)
        }
    }

    private fun tickHole(world: ServerWorld) {
        val center = holeCenter ?: return
        if (--holeTicks <= 0) {
            holeCenter = null
            VoidVisuals.nova(world, center, 12f)
            for (player in players(world)) {
                if (!player.isOnGround || player.uuid in swallowed) continue
                val away = dirFrom(center, player.entityPos)
                launch(player, Vec3d(away.x * 0.5, 0.45, away.z * 0.5))
            }
            return
        }
        if (sceneKind >= 0) return
        if (holeTicks % 60 == 0) sound(world, center, VoidSounds.BLACK_HOLE, 3f, 0.7f)
        for (player in targets(world)) {
            val toHole = center.subtract(player.eyePos)
            val distance = toHole.length()
            val sheltered = world.raycast(RaycastContext(player.eyePos, center, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, this)).type != HitResult.Type.MISS
            if (distance < 90.0 && !sheltered) {
                val pull = toHole.normalize().multiply(0.05 + 0.28 / (distance / 10.0 + 1.0))
                launch(player, player.velocity.add(pull), keepFall = true)
            }
            if (distance < 10.0 && (chomped[player.uuid] ?: 0) <= age) {
                chomped[player.uuid] = age + 40
                hurtPlayer(world, player, if (phase >= 3) 26f else 22f, physical = true)
                val back = dirFrom(center, player.entityPos)
                launch(player, Vec3d(back.x * 2.0, 0.9, back.z * 2.0))
                VoidVisuals.impact(world, player.entityPos.add(0.0, 1.0, 0.0), 2.5f)
                sound(world, player.entityPos, VoidSounds.SWALLOW, 3f, 0.8f)
            }
        }
    }

    private fun endHole(world: ServerWorld) {
        holeCenter?.let { VoidVisuals.blackHoleEnd(world, holeId, it) }
        holeCenter = null
    }

    /**
     * 5 — Durchbruch (Wal-Breach): Er stürzt sich kopfüber durch die Plattform (Loch, Schockwelle), rumpelt darunter
     * entlang – dann bricht er unter einem Ziel durch den Boden, schießt in einem Bogen hoch über die Arena, kippt am
     * Scheitel nach vorn und kracht 70 Blöcke weiter wieder durch die Plattform (zweites Loch, Trümmer, Schockwelle).
     * Danach taucht er durch dieses Loch wieder auf.
     */
    private fun breach(world: ServerWorld, victim: ServerPlayerEntity): Cast {
        val c = groundCenter()
        val floor = c.y
        val entry = onFloor(entityPos.add(flatForward().multiply(26.0)), 30.0)
        var spot = Vec3d.ZERO
        var land = Vec3d.ZERO
        var stage = 0
        var stageAt = 0
        var vh = Vec3d.ZERO
        var vy = 0.0
        var flags = 0
        return Cast(BREACH, 300) { age ->
            phaseThrough = 3
            if (age == 0) {
                setPose(WhalePose.DIVE)
                say(world, "breach")
                releaseTethers(world)
            }
            when (stage) {
                0 -> {
                    // kopfüber in den Boden
                    fly(entry.add(0.0, -70.0, 0.0), 2.6, 0.18)
                    faceVelocity(maxDown = 80f, rate = 5f)
                    if (age > 8) slippery = 3
                    if (flags and 1 == 0 && mouthPos().y < floor + 2.0) {
                        flags = flags or 1
                        for (p in riderPlayers(world)) fling(p, Vec3d(0.0, 0.8, 0.0))
                        diveImpact(world, entry, scene = false)
                    }
                    if (y < floor - 55.0 || age > 56) { stage = 1; stageAt = age + 1 }
                }
                1 -> {
                    // unter der Plattform zum Opfer
                    if (age == stageAt) {
                        spot = onFloor(victim.entityPos, 24.0)
                        val away = dirFrom(focus(), spot).let { if (it.lengthSquared() < 0.5) flatForward() else it }
                        val turn = (random.nextDouble() - 0.5) * 1.6
                        val ad = Vec3d(away.x * cos(turn) - away.z * sin(turn), 0.0, away.x * sin(turn) + away.z * cos(turn))
                        land = onFloor(spot.add(ad.multiply(BREACH_FLIGHT)), 30.0)
                        refreshPositionAndAngles(spot.x, floor - 72.0 - BODY_UP, spot.z, yawOf(dirFrom(spot, land)), -80f)
                        velocity = Vec3d.ZERO
                        VoidVisuals.zone(world, spot, BREACH_RADIUS, 34, 0)
                        VoidVisuals.crackStar(world, spot, 7, BREACH_RADIUS + 4.0, 30, 120)
                        for (p in players(world)) p.sendMessage(Text.translatable("hha_void.boss.msg.breach").formatted(Formatting.RED, Formatting.BOLD), true)
                    }
                    velocity = Vec3d.ZERO
                    pitch = -80f
                    if ((age - stageAt) % 8 == 0) {
                        sound(world, spot, VoidSounds.WHALE_GROAN, 3f, 0.4f)
                        VoidVisuals.stomp(world, spot, 0.45f)
                    }
                    if (age - stageAt >= 34) {
                        stage = 2; stageAt = age
                        setPose(WhalePose.BREACH)
                        vy = BREACH_UP
                        // Flugzeit über dem Boden = 2·vy/g → waagerechtes Tempo bis zur Landestelle
                        vh = dirFrom(spot, land).multiply(horizontal(spot, land) / (2.0 * BREACH_UP / BREACH_G))
                    }
                }
                2 -> {
                    // senkrecht hoch durch den Boden, dann im Bogen über die Arena
                    val above = mouthPos().y > floor - 4.0
                    if (flags and 2 == 0 && mouthPos().y > floor - 8.0) {
                        flags = flags or 2
                        breachImpact(world, spot, scene = false)
                    }
                    if (above) vy -= BREACH_G
                    velocity = if (above) Vec3d(vh.x, vy, vh.z) else Vec3d((spot.x - x) * 0.2, BREACH_UP, (spot.z - z) * 0.2)
                    velocityDirty = true
                    val want = (-Math.toDegrees(atan2(velocity.y, max(0.05, velocity.horizontalLength())))).toFloat().coerceIn(-85f, 80f)
                    pitch = MathHelper.stepTowards(pitch, want, 5f)
                    if (vh.horizontalLengthSquared() > 1e-4) turnYaw(yawOf(vh), 3f)
                    if (age - stageAt == 70) setPose(WhalePose.DIVE)
                    if (above && vy < 0 && mouthPos().y < floor + 2.0 && flags and 4 == 0) {
                        flags = flags or 4
                        landImpact(world, Vec3d(mouthPos().x, floor, mouthPos().z))
                        stage = 3; stageAt = age
                    }
                    if (age - stageAt > 120) { stage = 3; stageAt = age }
                }
                3 -> {
                    // weiter in die Tiefe, dann durch das Loch zurück
                    velocity = velocity.lerp(Vec3d(0.0, -1.6, 0.0), 0.2)
                    velocityDirty = true
                    pitch = MathHelper.stepTowards(pitch, 80f, 5f)
                    if (y < floor - 50.0 || age - stageAt > 30) { stage = 4; stageAt = age; setPose(WhalePose.SWIM) }
                }
                else -> {
                    val hole = Vec3d(land.x, floor, land.z)
                    val goal = hole.add(dirFrom(spot, land).multiply(30.0)).add(0.0, 26.0, 0.0)
                    if (y + BODY_UP < floor + 6.0) {
                        velocity = Vec3d((hole.x - x) * 0.1, 2.2, (hole.z - z) * 0.1)
                        velocityDirty = true
                        pitch = MathHelper.stepTowards(pitch, -70f, 6f)
                    } else {
                        fly(goal, 1.6, 0.1)
                        faceVelocity(rate = 4f)
                        if (age - stageAt > 30) cast?.let { it.age = it.duration }
                    }
                    if (age - stageAt > 90) cast?.let { it.age = it.duration }
                }
            }
        }
    }

    /** Kopfüber in die Plattform: Loch, Trümmer, Welle (Durchbruch-Eintritt, Abtauchen, Phase-II-Szene). */
    private fun diveImpact(world: ServerWorld, spot: Vec3d, scene: Boolean) {
        tearHole(world, spot, 12.0, HOLE_OPEN_TICKS + 60)
        VoidVisuals.shockwave(world, spot.add(0.0, 0.5, 0.0), spot, 34.0)
        VoidVisuals.stomp(world, spot, 0.9f)
        VoidVisuals.cometImpact(world, spot.add(0.0, 1.0, 0.0), 10.0)
        sound(world, spot, VoidSounds.WHALE_BREACH, 4f, 0.6f)
        sound(world, spot, SoundEvents.ENTITY_GENERIC_EXPLODE.value(), 4f, 0.5f)
        if (scene) return
        for (player in targets(world)) {
            val d = horizontal(player.entityPos, spot)
            if (d < 14.0 && player.y - spot.y < 6.0) {
                hurtPlayer(world, player, 14f, physical = true)
                launch(player, dirFrom(spot, player.entityPos).multiply(1.2).add(0.0, 0.9, 0.0))
            }
        }
    }

    /** Durchstoß von unten: Loch, Säule, alle im Kreis fliegen hoch (und können auf ihm landen). */
    private fun breachImpact(world: ServerWorld, spot: Vec3d, scene: Boolean) {
        sceneFlag = true
        tearHole(world, spot)
        VoidVisuals.nova(world, spot.add(0.0, 2.0, 0.0), 22f)
        VoidVisuals.shockwave(world, spot.add(0.0, 0.5, 0.0), spot, 48.0)
        VoidVisuals.stomp(world, spot, 1f)
        VoidVisuals.skyBeams(world, spot, 30)
        VoidVisuals.cometImpact(world, spot.add(0.0, 1.0, 0.0), 12.0)
        sound(world, spot, VoidSounds.WHALE_BREACH, 4f, 0.5f)
        sound(world, spot, VoidSounds.GRAVITY_WAVE, 4f, 0.45f)
        for (player in targets(world)) {
            val dx = player.x - spot.x
            val dz = player.z - spot.z
            val d = sqrt(dx * dx + dz * dz)
            if (d < BREACH_RADIUS && player.y - spot.y < 12.0) {
                if (!scene) hurtPlayer(world, player, if (phase >= 3) 26f else 22f, physical = true)
                launch(player, Vec3d(dx / (d + 1.0) * 0.6, 2.1, dz / (d + 1.0) * 0.6))
            } else if (d < 48.0 && player.isOnGround) {
                if (!scene) hurtPlayer(world, player, 10f, physical = true)
                launch(player, Vec3d(dx / d * 0.7, 0.7, dz / d * 0.7))
            }
        }
    }

    /** Am Ende des Bogens kracht er zurück durch die Plattform. */
    private fun landImpact(world: ServerWorld, spot: Vec3d) {
        tearHole(world, spot, HOLE_RADIUS, HOLE_OPEN_TICKS + 80)
        VoidVisuals.nova(world, spot.add(0.0, 2.0, 0.0), 26f)
        VoidVisuals.shockwave(world, spot.add(0.0, 0.5, 0.0), spot, 44.0)
        VoidVisuals.stomp(world, spot, 1f)
        VoidVisuals.crackStar(world, spot, 12, 26.0, 10, 160)
        VoidVisuals.cometImpact(world, spot.add(0.0, 1.0, 0.0), 16.0)
        sound(world, spot, VoidSounds.GRAVITY_WAVE, 4f, 0.4f)
        sound(world, spot, SoundEvents.ENTITY_GENERIC_EXPLODE.value(), 4f, 0.4f)
        for (player in targets(world)) {
            val d = horizontal(player.entityPos, spot)
            if (d < HOLE_RADIUS + 2.0 && player.y - spot.y < 8.0) {
                hurtPlayer(world, player, if (phase >= 3) 26f else 22f, physical = true)
                launch(player, dirFrom(spot, player.entityPos).multiply(1.4).add(0.0, 1.0, 0.0))
            } else if (d < 44.0 && player.isOnGround) {
                hurtPlayer(world, player, 9f, physical = true)
                launch(player, dirFrom(spot, player.entityPos).multiply(0.8).add(0.0, 0.7, 0.0))
            }
        }
    }

    /**
     * 6 — Kieferschnappen: Er stellt sich vor das Ziel, reißt das Maul auf und schnellt vor; der Vorstoß ist so berechnet,
     * dass das Maul beim Zuschnappen (Tick 20 der Haltung) genau am Ziel ist. Getroffen wird, wer in Maul oder Kiefer
     * steckt – geprüft an der Lage, die der Spieler in diesem Moment sieht.
     */
    private fun snap(world: ServerWorld, victim: ServerPlayerEntity): Cast {
        val approach = 60
        val hits = HashSet<UUID>()
        var started = -1
        return Cast(SNAP, approach + WhalePose.duration(WhalePose.SWALLOW) + CLIENT_LAG) { age ->
            if (started < 0) {
                if (age == 0) setPose(WhalePose.SWIM)
                val back = dirFrom(victim.entityPos, entityPos)
                val station = victim.entityPos.add(back.multiply(30.0)).add(0.0, 6.0 - BODY_UP, 0.0)
                fly(station, 2.2, 0.16)
                if (age > 14) faceTowards(victim.entityPos.add(0.0, 1.0, 0.0), 6f, 3f)
                if (age >= approach || (age > 16 && squaredDistanceTo(station) < 64.0)) {
                    started = age
                    setPose(WhalePose.SWALLOW)
                    say(world, "snap")
                    VoidVisuals.zone(world, Vec3d(victim.x, victim.y, victim.z), 7.0, 20 + CLIENT_LAG, 0)
                    sound(world, VoidSounds.WHALE_GROAN, 3f, 0.7f)
                }
                return@Cast
            }
            val a = age - started
            if (a < 20) {
                // Vorstoß: wo muss er stehen, damit das Maul bei Tick 20 am (vorausberechneten) Ziel ist?
                val aim = victim.entityPos.add(victim.velocity.x * (20 - a), 3.2, victim.velocity.z * (20 - a))
                faceTowards(aim, 6f, 3f)
                val offset = predict(entityPos, yaw, pitch, WhalePose.SWALLOW, 20f).mouth().subtract(entityPos)
                val want = aim.subtract(offset).subtract(entityPos).multiply(1.0 / (20 - a))
                val v = if (want.length() > 3.2) want.normalize().multiply(3.2) else want
                velocity = velocity.lerp(v, if (a < 6) 0.25 else 0.6)
                velocityDirty = true
            } else {
                hover(null, 0.7)
            }
            if (a == 20) sound(world, VoidSounds.SWALLOW, 4f, 0.8f)
            if (a in 20 + CLIENT_LAG..21 + CLIENT_LAG) {
                val f = frame()
                val m = f.mouth()
                val jaws = (0 until LeviathanBody.COUNT).filter { LeviathanBody.VOLUMES[it].kind == LeviathanBody.JAW || LeviathanBody.VOLUMES[it].name == "head0" }
                    .mapNotNull { f.boxes[it]?.expand(1.2) }
                for (player in targets(world)) {
                    if (player.uuid in hits) continue
                    val box = player.boundingBox
                    if (player.entityPos.add(0.0, 1.0, 0.0).squaredDistanceTo(m) < SNAP_REACH * SNAP_REACH || jaws.any { it.intersects(box) }) {
                        hits += player.uuid
                        hurtPlayer(world, player, if (phase >= 3) 30f else 26f, physical = true)
                        launch(player, f.headForward().multiply(1.6).add(0.0, 0.7, 0.0))
                        VoidVisuals.impact(world, player.entityPos.add(0.0, 1.0, 0.0), 2.5f)
                    }
                }
            }
        }
    }

    /** 7 — Sternlinge: ruft kleine Sternrochen aus der Leere (mehr bei mehr Spielern). */
    private fun callStarlings(world: ServerWorld): Cast =
        Cast(STARLINGS, WhalePose.duration(WhalePose.ROAR)) { age ->
            if (age == 0) {
                setPose(WhalePose.ROAR)
                say(world, "starlings")
                sound(world, VoidSounds.WHALE_SONG, 3f, 1.1f)
            }
            hover(focus(), 0.85)
            if (age == 24) summonStarlings(world, (2 + phase + targets(world).size / 2).coerceAtMost(9))
        }

    private fun starlings(world: ServerWorld): Int =
        world.getEntitiesByType(VoidBoss.DEBTOR, boundingBox.expand(ARENA_RANGE)) { it.isAlive }.size

    private fun summonStarlings(world: ServerWorld, count: Int, around: Vec3d? = null, spread: Double = 20.0) {
        val c = around ?: focus().add(0.0, 6.0, 0.0)
        for (i in 0 until count) {
            val angle = i * (PI * 2.0 / count) + random.nextDouble()
            val pos = c.add(cos(angle) * spread * (0.5 + random.nextDouble() * 0.5), random.nextDouble() * 4.0, sin(angle) * spread * (0.5 + random.nextDouble() * 0.5))
            val starling = VoidBoss.DEBTOR.create(world, SpawnReason.MOB_SUMMONED) ?: continue
            starling.refreshPositionAndAngles(pos.x, pos.y, pos.z, random.nextFloat() * 360f, 0f)
            starling.setOwner(this)
            starling.setLifeTicks(20 * 45 + random.nextInt(200))
            world.spawnEntity(starling)
            VoidVisuals.rift(world, pos, Vec3d(0.2, 1.0, 0.1), 3f, 22)
            sound(world, pos, VoidSounds.STARLING_AMBIENT, 1.5f, 1f)
        }
    }

    /**
     * 8 — Supernova (Phase III): Er schwebt über die Gruppe, das Licht in ihm lädt sich auf. Am Ende trifft die
     * Explosion jeden, der freie Sicht auf sein Herz hat. Danach ist er ausgebrannt und sinkt zu Boden (Schadensfenster).
     */
    private fun supernova(world: ServerWorld): Cast {
        val approach = 40
        val charge = 110
        return Cast(SUPERNOVA, approach + WhalePose.duration(WhalePose.SUPERNOVA)) { age ->
            if (age < approach) {
                if (age == 0) setPose(WhalePose.SWIM)
                fly(focus().add(0.0, 30.0, 0.0), 1.6, 0.1)
                return@Cast
            }
            val a = age - approach
            hover(null, 0.7)
            if (a == 0) {
                setPose(WhalePose.SUPERNOVA)
                say(world, "supernova")
                showTitle(world, "supernova")
                VoidVisuals.charge(world, heartPos(), charge)
                sound(world, VoidSounds.SUPERNOVA_CHARGE, 4f, 0.8f)
            }
            if (a in 1 until charge && a % 20 == 0) {
                for (player in targets(world)) {
                    val safe = !seesHeart(world, player)
                    player.sendMessage(
                        Text.translatable(if (safe) "hha_void.boss.msg.hidden" else "hha_void.boss.msg.exposed")
                            .formatted(if (safe) Formatting.GREEN else Formatting.RED, Formatting.BOLD),
                        true,
                    )
                }
            }
            if (a == charge) {
                val heart = heartPos()
                VoidVisuals.nova(world, heart, 60f)
                VoidVisuals.skyBeams(world, heart, 40)
                sound(world, VoidSounds.SUPERNOVA_BLAST, 4f, 0.8f)
                for (player in targets(world)) {
                    if (seesHeart(world, player)) {
                        hurtPlayer(world, player, SUPERNOVA_DAMAGE, physical = false)
                        val away = dirFrom(heart, player.entityPos)
                        launch(player, Vec3d(away.x * 1.4, 0.8, away.z * 1.4))
                    } else {
                        player.sendMessage(Text.translatable("hha_void.boss.msg.survived").formatted(Formatting.LIGHT_PURPLE), true)
                    }
                }
            }
            if (a == WhalePose.duration(WhalePose.SUPERNOVA) - 1 && phase >= 3) {
                TickScheduler.schedule(1) { if (isAlive && cast == null && sceneKind < 0) start(spent(world)) }
            }
        }
    }

    /** Nach der Supernova ausgebrannt: sinkt neben der Gruppe zu Boden und bleibt liegen. */
    private fun spent(world: ServerWorld): Cast {
        val c = groundCenter()
        val spot = onFloor(focus().add(dirFrom(focus(), entityPos).multiply(20.0)), 30.0)
        var landed = -1
        val ticks = WhalePose.duration(WhalePose.BEACHED)
        return Cast(SPENT, 60 + ticks + 20) { age ->
            if (landed < 0) {
                if (age == 0) {
                    setPose(WhalePose.DIVE)
                    for (p in players(world)) p.sendMessage(Text.translatable("hha_void.boss.msg.spent").formatted(Formatting.GOLD, Formatting.BOLD), true)
                }
                fly(spot.add(0.0, LeviathanBody.BEACH_LIFT + 1.0, 0.0), 1.8, 0.16)
                if (age >= 50 || squaredDistanceTo(spot.add(0.0, LeviathanBody.BEACH_LIFT, 0.0)) < 16.0) {
                    landed = age
                    crash(world, Vec3d(x, c.y, z), yaw)
                }
            } else {
                beachedTick(world, age - landed, ticks)
            }
        }
    }

    private fun seesHeart(world: ServerWorld, player: ServerPlayerEntity): Boolean {
        val hit = world.raycast(RaycastContext(heartPos(), player.eyePos, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, this))
        return hit.type == HitResult.Type.MISS
    }

    /** 9 — Blasloch-Geysir: Fontäne aus dem Blasloch, dann brechen um die Gruppe herum Säulen aus dem Boden. */
    private fun geyser(world: ServerWorld): Cast {
        val waves = intArrayOf(14, 34, 54)
        val delay = 26
        return Cast(GEYSER, 40 + WhalePose.duration(WhalePose.BLOWHOLE) + delay) { age ->
            val c = focus()
            if (age < 40) {
                if (age == 0) setPose(WhalePose.SWIM)
                fly(c.add(0.0, 36.0, 0.0), 1.6, 0.1)
                return@Cast
            }
            val a = age - 40
            hover(null, 0.8)
            if (a == 0) {
                setPose(WhalePose.BLOWHOLE)
                say(world, "geyser")
                sound(world, VoidSounds.WHALE_SONG, 3f, 1.3f)
            }
            for ((wi, at) in waves.withIndex()) {
                if (a == at + CLIENT_LAG) {
                    val hole = blowholePos()
                    VoidVisuals.pillar(world, hole, 1, 14f, light = true)
                    VoidVisuals.nova(world, hole, 6f)
                    sound(world, hole, VoidSounds.STAR_BREATH, 3f, 1.4f)
                    for (p in riderPlayers(world)) {
                        if (p.squaredDistanceTo(hole) < 14.0 * 14.0) {
                            hurtPlayer(world, p, 10f, physical = false)
                            fling(p, Vec3d((random.nextDouble() - 0.5) * 1.2, 2.0, (random.nextDouble() - 0.5) * 1.2))
                        }
                    }
                    val spots = ArrayList<Vec3d>()
                    for (player in targets(world)) findGround(world, player.entityPos)?.let { spots += it }
                    val ring = 12.0 + wi * 16.0
                    val n = 5 + wi * 3 + targets(world).size / 2
                    for (i in 0 until n) {
                        val ang = i * PI * 2 / n + wi * 0.4
                        findGround(world, onFloor(c.add(cos(ang) * ring, 0.0, sin(ang) * ring)).add(0.0, 2.0, 0.0))?.let { spots += it }
                    }
                    for (spot in spots) {
                        VoidVisuals.zone(world, spot, GEYSER_RADIUS, delay, 2)
                        TickScheduler.schedule(delay) {
                            if (!isAlive) return@schedule
                            VoidVisuals.pillar(world, spot, 1, 20f, light = true)
                            for (player in targets(world)) {
                                if (player.squaredDistanceTo(spot.x, player.y, spot.z) < GEYSER_RADIUS * GEYSER_RADIUS && abs(player.y - spot.y) < 4.0) {
                                    hurtPlayer(world, player, 16f, physical = false)
                                    launch(player, Vec3d(0.0, 1.6, 0.0))
                                }
                            }
                        }
                    }
                    sound(world, c, VoidSounds.DEBRIS, 2f, 1.5f)
                }
            }
        }
    }

    /**
     * 10 — Void-Flutwelle: Am Rand der Gruppe peitscht er die Fluke flach durch die Luft – eine Wand aus Leere rollt
     * 150 Blöcke weit über die Plattform. Sicher: in den cyan markierten Lücken oder höher als die Welle (Monolithen,
     * Bastionen, Außenplattformen).
     */
    private fun flood(world: ServerWorld, victim: ServerPlayerEntity): Cast {
        val c = groundCenter()
        val f0 = focus()
        val side = dirFrom(f0, entityPos)
        val across = Vec3d(-side.z, 0.0, side.x)
        val startWave = onFloor(f0.add(side.multiply(FLOOD_HALF)), 1.0)
        val station = startWave.add(side.multiply(18.0)).add(0.0, 6.0, 0.0)
        val dir = side.multiply(-1.0)
        val speed = if (phase >= 3) 2.2 else 1.8
        val length = FLOOD_HALF * 2.0 + 10.0
        val travel = (length / speed).toInt()
        val warmup = 36
        val gapWidth = if (phase >= 3) 3.5 else 4.5
        val g1 = (random.nextDouble() * 2 - 1) * FLOOD_WIDTH * 0.5
        var g2 = (random.nextDouble() * 2 - 1) * FLOOD_WIDTH * 0.5
        if (abs(g2 - g1) < 22.0) g2 = if (g1 > 0) g1 - 30.0 else g1 + 30.0
        val wallHeight = 7
        val approach = 40
        val hit = HashSet<UUID>()
        return Cast(FLOOD, approach + warmup + travel + 10) { age ->
            if (age < approach) {
                if (age == 0) setPose(WhalePose.SWIM)
                fly(station, 2.0, 0.12)
                if (age > approach - 16) turnYaw(yawOf(across), 5f)
                return@Cast
            }
            val a = age - approach
            hover(station.add(across.multiply(50.0)), 0.8)
            if (a == 0) {
                setPose(WhalePose.FLOOD)
                say(world, "flood")
                sound(world, VoidSounds.WHALE_GROAN, 4f, 0.45f)
                VoidVisuals.wave(world, Vec3d(startWave.x, c.y, startWave.z), dir.multiply(speed), Vec3d(g1, g2, gapWidth), FLOOD_WIDTH, wallHeight, warmup + travel + 6, warmup)
                var s = 6.0
                while (s < length) {
                    for (g in doubleArrayOf(g1, g2)) {
                        val p = startWave.add(dir.multiply(s)).add(across.multiply(g))
                        if (horizontal(p, c) < floorRadius()) VoidVisuals.zone(world, Vec3d(p.x, c.y, p.z), gapWidth, warmup + (s / speed).toInt(), 1)
                    }
                    s += 12.0
                }
                for (p in players(world)) p.sendMessage(Text.translatable("hha_void.boss.msg.flood").formatted(Formatting.AQUA, Formatting.BOLD), true)
            }
            if (a == warmup - 4) sound(world, VoidSounds.GRAVITY_WAVE, 4f, 0.5f)
            if (a >= warmup) {
                val front = startWave.add(dir.multiply((a - warmup) * speed))
                if ((a - warmup) % 10 == 0) sound(world, front, VoidSounds.BLACK_HOLE, 2.5f, 1.6f)
                for (player in targets(world)) {
                    if (player.uuid in hit) continue
                    val rel = player.entityPos.subtract(front)
                    val along = rel.x * dir.x + rel.z * dir.z
                    val o = rel.x * across.x + rel.z * across.z
                    if (abs(along) > speed + 0.8 || abs(o) > FLOOD_WIDTH) continue
                    if (abs(o - g1) < gapWidth || abs(o - g2) < gapWidth) continue
                    if (player.y - c.y >= wallHeight) continue
                    hit += player.uuid
                    hurtPlayer(world, player, if (phase >= 3) 20f else 17f, physical = false)
                    launch(player, Vec3d(dir.x * 1.5, 0.6, dir.z * 1.5))
                    VoidVisuals.impact(world, player.entityPos.add(0.0, 1.0, 0.0), 2f)
                }
            }
        }
    }

    // ---------- Abschütteln (wer auf ihm steht) ----------

    private fun warnRiders(world: ServerWorld, key: String) {
        for (p in riderPlayers(world)) p.sendMessage(Text.translatable("hha_void.boss.msg.$key").formatted(Formatting.RED, Formatting.BOLD), true)
    }

    /** 11 — Fassrolle: Er zittert, dann rollt er sich einmal um die eigene Achse – wer auf ihm steht, fliegt herunter. */
    private fun barrelRoll(world: ServerWorld): Cast {
        var flung = false
        return Cast(BARREL_ROLL, WhalePose.duration(WhalePose.BARREL_ROLL) + 6) { age ->
            if (age == 0) {
                setPose(WhalePose.BARREL_ROLL)
                say(world, "shake${random.nextInt(3)}")
                warnRiders(world, "roll")
                sound(world, VoidSounds.WHALE_GROAN, 4f, 0.6f)
            }
            cruise(0.45)
            if (age in 20..50) slippery = 3
            if (age == 26 + CLIENT_LAG && !flung) {
                flung = true
                for (p in riderPlayers(world)) {
                    val side = dirFrom(bodyCenter(), p.entityPos).takeIf { horizontal(p.entityPos, bodyCenter()) > 0.3 } ?: rightVec()
                    hurtPlayer(world, p, 12f, physical = true)
                    fling(p, Vec3d(side.x * 1.9, 0.75, side.z * 1.9))
                }
                sound(world, VoidSounds.GRAVITY_WAVE, 3f, 0.9f)
            }
        }
    }

    /** 12 — Rückenbeben: der Rücken zittert immer stärker und schnellt dann hoch – Reiter werden in den Himmel geworfen. */
    private fun backQuake(world: ServerWorld): Cast =
        Cast(BACK_QUAKE, WhalePose.duration(WhalePose.BACK_QUAKE) + 6) { age ->
            if (age == 0) {
                setPose(WhalePose.BACK_QUAKE)
                say(world, "shake${random.nextInt(3)}")
                warnRiders(world, "quake")
                sound(world, VoidSounds.WHALE_GROAN, 4f, 0.8f)
            }
            hover(null, 0.85)
            if (age in 4..22 && age % 6 == 0) {
                for (p in riderPlayers(world)) VoidVisuals.impact(world, p.entityPos, 0.8f)
                VoidVisuals.stomp(world, bodyCenter(), 0.3f)
            }
            if (age == 24 + CLIENT_LAG) {
                val f = frame()
                for (i in 0..4) VoidVisuals.pillar(world, f.spine(0.2 + i * 0.13).add(0.0, 7.5, 0.0), 1, 9f, light = true)
                VoidVisuals.nova(world, f.back(), 10f)
                sound(world, VoidSounds.WHALE_BREACH, 3.5f, 1.1f)
                for (p in riderPlayers(world)) {
                    hurtPlayer(world, p, 14f, physical = true)
                    val side = if (random.nextBoolean()) rightVec() else rightVec().multiply(-1.0)
                    fling(p, side.multiply(1.3).add(0.0, 2.0, 0.0))
                }
            }
        }

    /** 13 — Schwanzpeitsche: die Fluke biegt sich nach vorn und fegt von hinten nach vorn über den ganzen Rücken. */
    private fun tailSweep(world: ServerWorld): Cast {
        val hit = HashSet<UUID>()
        return Cast(TAIL_SWEEP, WhalePose.duration(WhalePose.TAIL_SWEEP) + 6 + CLIENT_LAG) { age ->
            if (age == 0) {
                setPose(WhalePose.TAIL_SWEEP)
                say(world, "shake${random.nextInt(3)}")
                warnRiders(world, "sweep")
                sound(world, VoidSounds.WHALE_GROAN, 4f, 0.7f)
            }
            hover(null, 0.85)
            val a = age - CLIENT_LAG
            if (a in 24..48) {
                // die Fluke aus den Knochen: wer in ihrer Nähe auf dem Rücken steht, fliegt
                val f = frame()
                val fluke = f.boxes[LeviathanBody.indexOf("fluke")]?.expand(3.0)
                val lobes = listOf("fluke_l", "fluke_r", "fluke_l2", "fluke_r2").mapNotNull { f.boxes[LeviathanBody.indexOf(it)]?.expand(2.0) }
                if (a % 3 == 0) {
                    val l = f.boxes[LeviathanBody.indexOf("fluke_l2")]?.center
                    val r = f.boxes[LeviathanBody.indexOf("fluke_r2")]?.center
                    if (l != null && r != null) VoidVisuals.streak(world, l, r)
                }
                for (p in riderPlayers(world)) {
                    if (p.uuid in hit) continue
                    val box = p.boundingBox
                    // bis Tick 44 hat die Fluke den ganzen Rücken überstrichen: spätestens dann fliegt jeder Reiter
                    if (fluke?.intersects(box) == true || lobes.any { it.intersects(box) } || a == 44) {
                        hit += p.uuid
                        hurtPlayer(world, p, 16f, physical = true)
                        val side = if (p.entityPos.subtract(bodyCenter()).dotProduct(rightVec()) > 0) rightVec() else rightVec().multiply(-1.0)
                        fling(p, side.multiply(1.6).add(flatForward().multiply(0.6)).add(0.0, 0.9, 0.0))
                        VoidVisuals.impact(world, p.entityPos.add(0.0, 1.0, 0.0), 2f)
                    }
                }
                if (a == 36) sound(world, VoidSounds.GRAVITY_WAVE, 3f, 1.3f)
            }
        }
    }

    /** 14 — Abtauchen: er stürzt sich kopfüber durch die Plattform; wer oben bleibt, fällt ins Loch. Daneben bricht er wieder auf. */
    private fun sounding(world: ServerWorld): Cast {
        val floor = groundCenter().y
        val entry = onFloor(entityPos.add(flatForward().multiply(18.0)), 30.0)
        val turn = if (random.nextBoolean()) 1.2 else -1.2
        val f = flatForward()
        val exit = onFloor(entry.add(Vec3d(f.x * cos(turn) - f.z * sin(turn), 0.0, f.x * sin(turn) + f.z * cos(turn)).multiply(44.0)), 30.0)
        var stage = 0
        var stageAt = 0
        return Cast(SOUNDING, 170) { age ->
            phaseThrough = 3
            if (age == 0) {
                setPose(WhalePose.DIVE)
                say(world, "shake${random.nextInt(3)}")
                warnRiders(world, "dive")
                sound(world, VoidSounds.WHALE_SONG, 3f, 0.6f)
            }
            when (stage) {
                0 -> {
                    fly(entry.add(0.0, -60.0, 0.0), 2.3, 0.16)
                    faceVelocity(maxDown = 80f, rate = 4f)
                    if (pitch > 30f) slippery = 3
                    if (age == 18) for (p in riderPlayers(world)) fling(p, Vec3d(0.0, 0.3, 0.0))
                    if (mouthPos().y < floor + 2.0 && stageAt == 0) {
                        stageAt = -1
                        diveImpact(world, entry, scene = false)
                    }
                    if (y < floor - 45.0 || age > 60) { stage = 1; stageAt = age + 1 }
                }
                1 -> {
                    if (age == stageAt) {
                        refreshPositionAndAngles(exit.x, floor - 60.0 - BODY_UP, exit.z, yaw, -75f)
                        velocity = Vec3d.ZERO
                        VoidVisuals.zone(world, exit, 11.0, 24, 0)
                    }
                    velocity = Vec3d.ZERO
                    if (age - stageAt >= 24) { stage = 2; stageAt = age; setPose(WhalePose.BREACH) }
                }
                2 -> {
                    velocity = Vec3d((exit.x - x) * 0.2, 2.4, (exit.z - z) * 0.2)
                    velocityDirty = true
                    pitch = -75f
                    if (stageAt > 0 && mouthPos().y > floor - 6.0) {
                        stageAt = -stageAt
                        tearHole(world, exit, 11.0)
                        VoidVisuals.shockwave(world, exit.add(0.0, 0.5, 0.0), exit, 30.0)
                        VoidVisuals.nova(world, exit.add(0.0, 2.0, 0.0), 14f)
                        sound(world, exit, VoidSounds.WHALE_BREACH, 4f, 0.7f)
                        for (player in targets(world)) {
                            if (horizontal(player.entityPos, exit) < 11.0 && player.y - exit.y < 8.0) {
                                hurtPlayer(world, player, 12f, physical = true)
                                launch(player, Vec3d(0.0, 1.8, 0.0))
                            }
                        }
                    }
                    if (y + BODY_UP > floor + 24.0) cast?.let { it.age = it.duration }
                }
            }
        }
    }

    /**
     * 16 — Leere-Eruption: Um die Gruppe leuchten mehrere Kreissektoren der Plattform auf; nach 2,5 s bricht dort die
     * Leere aus dem Boden. Sicher: in den dunklen Sektoren, oder erhöht (Monolithen, Ruinen, Bastionen, Außenplattformen).
     * Phase III: zwei Wellen.
     */
    private fun eruption(world: ServerWorld): Cast {
        val delay = 50
        val waves = if (phase >= 3) 2 else 1
        val rings = ArrayList<IntArray>()
        val base = random.nextDouble() * PI * 2
        var center = focus()
        return Cast(ERUPTION, 20 + waves * 50 + delay + CLIENT_LAG) { age ->
            if (age == 0) {
                setPose(WhalePose.ROAR)
                // Mitte etwas zum Wal hin versetzt: die Gruppe steht mitten in den Sektoren, nicht im sicheren Kern
                center = onFloor(focus().add(dirFrom(focus(), entityPos).multiply(26.0)), 20.0)
                for (p in players(world)) p.sendMessage(Text.translatable("hha_void.boss.msg.eruption").formatted(Formatting.DARK_PURPLE, Formatting.BOLD), true)
            }
            hover(center, 0.85)
            for (w in 0 until waves) {
                val at = 10 + w * 50
                if (age == at) {
                    if (w > 0) setPose(WhalePose.ROAR)
                    sound(world, center, VoidSounds.GRAVITY_WAVE, 4f, 0.4f)
                    // der Sektor, in dem die meisten Spieler stehen, ist immer dabei – stillstehen geht nicht
                    val counts = IntArray(8)
                    for (p in targets(world)) {
                        var a = atan2(p.z - center.z, p.x - center.x) - base
                        while (a < 0) a += 2 * PI
                        counts[(a / (PI / 4)).toInt() % 8]++
                    }
                    val busiest = counts.indices.maxBy { counts[it] }
                    val rest = (0 until 8).filter { it != busiest }.shuffled(java.util.Random(age * 31L + this.age)).take(if (phase >= 3) 3 else 2)
                    val pick = (rest + busiest).toIntArray()
                    rings += pick
                    for (s in pick) VoidVisuals.sector(world, center, base + s * PI / 4, base + (s + 1) * PI / 4, 3.0, ERUPTION_RADIUS, delay)
                }
                if (age == at + delay) {
                    val pick = rings[w]
                    sound(world, center, SoundEvents.ENTITY_GENERIC_EXPLODE.value(), 4f, 0.5f)
                    for (s in pick) {
                        val mid = base + (s + 0.5) * PI / 4
                        for (k in 1..4) VoidVisuals.pillar(world, onFloor(center.add(cos(mid) * k * 16.0, 0.0, sin(mid) * k * 16.0)), 1, 9f, light = false)
                    }
                    for (player in targets(world)) {
                        val d = horizontal(player.entityPos, center)
                        if (d < 3.0 || d > ERUPTION_RADIUS || !onArenaFloor(player, 2.5)) continue
                        var ang = atan2(player.z - center.z, player.x - center.x) - base
                        while (ang < 0) ang += 2 * PI
                        val sector = (ang / (PI / 4)).toInt() % 8
                        if (sector in pick) {
                            hurtPlayer(world, player, if (phase >= 3) 20f else 18f, physical = false)
                            launch(player, Vec3d(0.0, 1.3, 0.0))
                            VoidVisuals.impact(world, player.entityPos, 2f)
                        }
                    }
                }
            }
        }
    }

    /**
     * 18 — Tiefflug: er streift langsam und tief über die Plattform durch die Gruppe – man kann ihn im Nahkampf treffen
     * und auf ihn springen. In Phase III kommt das nach vielen Angriffen von selbst (er ist erschöpft).
     */
    private fun lowPass(world: ServerWorld, tired: Boolean, victim: ServerPlayerEntity?): Cast {
        val c = groundCenter()
        val through = victim?.let { onFloor(it.entityPos, 24.0) } ?: focus()
        val dir = pickRamDir(through, dirFrom(entityPos, through))
        val from = onFloor(through.subtract(dir.multiply(LOW_PASS_HALF)), 20.0).add(0.0, LOW_PASS_Y, 0.0)
        val to = onFloor(through.add(dir.multiply(LOW_PASS_HALF)), 20.0).add(0.0, LOW_PASS_Y, 0.0)
        var started = -1
        return Cast(LOW_PASS, 60 + (LOW_PASS_HALF * 2 / LOW_PASS_SPEED).toInt() + 30) { age ->
            if (started < 0) {
                if (age == 0) setPose(WhalePose.SWIM)
                fly(from, 2.4, 0.16)
                if (squaredDistanceTo(from) < 64.0 || age > 58) {
                    started = age
                    for (p in players(world)) p.sendMessage(Text.translatable(if (tired) "hha_void.boss.msg.tired" else "hha_void.boss.msg.lowpass").formatted(Formatting.GOLD, Formatting.BOLD), true)
                    sound(world, VoidSounds.WHALE_GROAN, 3f, 0.4f)
                }
                return@Cast
            }
            val travelled = (x - from.x) * dir.x + (z - from.z) * dir.z
            if (travelled < LOW_PASS_HALF * 2) {
                velocity = velocity.lerp(dir.multiply(LOW_PASS_SPEED).add(0.0, (c.y + LOW_PASS_Y - y) * 0.2, 0.0), 0.2)
                phaseThrough = 2
                if (age % 3 == 0) crushUnderBody(world, heal = 160, ahead = velocity.multiply(4.0))
                velocityDirty = true
                turnYaw(yawOf(dir), 2f)
                pitch = MathHelper.stepTowards(pitch, 0f, 1f)
                if (age % 40 == 0) sound(world, VoidSounds.WHALE_GROAN, 2f, 0.45f)
            } else {
                fly(to.add(0.0, ORBIT_HEIGHT, 0.0), 1.2, 0.06)
                if (age - started > LOW_PASS_HALF * 2 / LOW_PASS_SPEED + 20) cast?.let { it.age = it.duration }
            }
        }
    }

    // ---------- Harpunen (Außenplattformen) ----------

    /** Wie viele Harpunen gleichzeitig sitzen müssen, damit er festgenagelt wird. */
    fun harpoonsNeeded(world: ServerWorld): Int = when (fighters(world)) {
        1 -> 1
        in 2..5 -> 2
        else -> 3
    }

    /** Ziel für ein Geschütz bei [muzzle]: nächstes Rumpf-Volumen in Reichweite (Index, Weltpunkt). */
    fun harpoonTarget(muzzle: Vec3d): Pair<Int, Vec3d>? {
        if (intro < INTRO_TICKS || castPose() == WhalePose.HIDDEN || isDead) return null
        val f = frame()
        var best: Pair<Int, Vec3d>? = null
        var bestD = HarpoonLauncherBlock.RANGE * HarpoonLauncherBlock.RANGE
        for (name in arrayOf("neck1", "chest0", "chest1", "chest2", "belly0", "belly1", "hip0", "hip1", "tail0")) {
            val i = LeviathanBody.indexOf(name)
            val c = f.centers[i] ?: continue
            val d = c.squaredDistanceTo(muzzle)
            if (d < bestD) { bestD = d; best = i to c }
        }
        return best
    }

    fun harpoonHit(world: ServerWorld, tether: Int, muzzle: Vec3d, volume: Int, shooter: ServerPlayerEntity) {
        val canPin = intro >= INTRO_TICKS && sceneKind < 0 && bellyState == BELLY_NONE && !isDead && pinCooldown <= 0 &&
            cast?.id != PINNED && cast?.id != BREACH && cast?.id != SOUNDING && !LeviathanBody.isBeachedPose(castPose())
        val at = frame().centers[volume] ?: bodyCenter()
        VoidVisuals.impact(world, at, 2f)
        sound(world, at, SoundEvents.ITEM_TRIDENT_HIT, 3f, 0.6f)
        if (!canPin) {
            VoidVisuals.harpoonEnd(world, tether, muzzle)
            shooter.sendMessage(Text.translatable("hha_void.boss.msg.harpoon_bounce").formatted(Formatting.GRAY), true)
            return
        }
        tethers += Tether(tether, muzzle, volume, age + TETHER_TICKS)
        val need = harpoonsNeeded(world)
        for (p in players(world)) {
            p.sendMessage(Text.translatable("hha_void.boss.msg.harpoon_hit", tethers.size, need).formatted(Formatting.AQUA, Formatting.BOLD), true)
        }
        sound(world, VoidSounds.WHALE_HURT, 4f, 0.5f)
        if (tethers.size >= need) {
            pinCooldown = PIN_COOLDOWN
            lastCast = PINNED
            exposed = false
            phaseThrough = 0
            slippery = 0
            endHole(world)
            start(pinned(world))
        }
    }

    private fun tickTethers(world: ServerWorld) {
        if (tethers.isEmpty()) return
        val c = cast?.id
        val it = tethers.iterator()
        while (it.hasNext()) {
            val t = it.next()
            if (age > t.until && c != PINNED) {
                VoidVisuals.harpoonEnd(world, t.id, t.from)
                it.remove()
            }
        }
    }

    private fun releaseTethers(world: ServerWorld) {
        for (t in tethers) VoidVisuals.harpoonEnd(world, t.id, t.from)
        if (tethers.isNotEmpty()) sound(world, SoundEvents.BLOCK_CHAIN_BREAK, 4f, 0.5f)
        tethers.clear()
    }

    /**
     * 17 — Festgenagelt: die Harpunen zerren ihn zu Boden; er kracht auf die Plattform und liegt (Herz offen),
     * bis er sich losreißt. Auch per `/voidboss attack 17`.
     */
    private fun pinned(world: ServerWorld): Cast {
        val c = groundCenter()
        val pull = if (tethers.isEmpty()) Vec3d.ZERO else tethers.map { it.from }.reduce { a, b -> a.add(b) }.multiply(1.0 / tethers.size)
        val toward = if (tethers.isEmpty()) flatForward() else dirFrom(entityPos, pull)
        val spot = onFloor(Vec3d(x, c.y, z).add(toward.multiply(16.0)), 32.0)
        val ticks = WhalePose.duration(WhalePose.BEACHED)
        var landed = -1
        return Cast(PINNED, 50 + ticks + 20) { age ->
            if (landed < 0) {
                if (age == 0) {
                    setPose(WhalePose.STAGGER)
                    say(world, "pinned")
                    showTitle(world, "pinned")
                    sound(world, VoidSounds.WHALE_HURT, 4f, 0.4f)
                    sound(world, VoidSounds.WHALE_GROAN, 4f, 0.35f)
                }
                if (age == 12) setPose(WhalePose.DIVE)
                fly(spot.add(0.0, LeviathanBody.BEACH_LIFT + 2.0, 0.0), 2.0, 0.2)
                faceVelocity(maxDown = 50f)
                if (age >= 44 || squaredDistanceTo(spot.add(0.0, LeviathanBody.BEACH_LIFT, 0.0)) < 25.0) {
                    landed = age
                    crash(world, Vec3d(x, c.y, z), yaw)
                }
            } else {
                beachedTick(world, age - landed, ticks)
            }
        }
    }

    /** Nach dem Ausbruch: er liegt taumelnd auf der Plattform (Schadensfenster), dann rappelt er sich auf. */
    private fun reeling(world: ServerWorld): Cast {
        val remaining = WhalePose.duration(WhalePose.REELING) - (age - reelStart)
        return Cast(REELING_CAST, remaining.coerceIn(40, 200)) { a ->
            exposed = a < remaining - 30
            velocity = Vec3d.ZERO
            pitch = 0f
            if (a % 30 == 10) sound(world, VoidSounds.WHALE_GROAN, 3f, 0.35f + random.nextFloat() * 0.15f)
            if (a == remaining - 30) {
                exposed = false
                for (p in riderPlayers(world)) fling(p, rightVec().multiply(if (random.nextBoolean()) 1.2 else -1.2).add(0.0, 0.9, 0.0))
                VoidVisuals.nova(world, bodyCenter(), 12f)
                sound(world, VoidSounds.WHALE_SONG, 4f, 0.6f)
            }
            if (a > remaining - 30) {
                velocity = Vec3d(0.0, 0.7, 0.0).add(flatForward().multiply(0.3))
                velocityDirty = true
            }
        }
    }

    /**
     * Wer in den fliegenden Körper gerät, wird weggestoßen; Reiter oben drauf nicht. Mit [sweep] zählt auch der Raum
     * zwischen der Lage dieses und des letzten Ticks (bei 3+ Blöcken/Tick rutscht sonst niemand mehr hinein).
     */
    private fun hitAlongBody(world: ServerWorld, amount: Float, heavy: Boolean = false, sweep: Boolean = false, push: Double = if (heavy) 2.2 else 1.2) {
        val f = frame()
        val prev = if (sweep) seenPrevFrame else null
        val boxes = (0 until LeviathanBody.COUNT).mapNotNull { i ->
            val b = f.boxes[i] ?: return@mapNotNull null
            val p = prev?.boxes?.get(i)
            if (p != null) b.union(p) else b
        }
        for (player in targets(world)) {
            if (player.uuid in riders || age - (lastRide[player.uuid] ?: -1000) < 30) continue
            if ((bodyHits[player.uuid] ?: 0) > age) continue
            val box = player.boundingBox
            val part = boxes.firstOrNull { b -> b.expand(0.6).intersects(box) } ?: continue
            bodyHits[player.uuid] = age + 20
            hurtPlayer(world, player, amount, physical = true)
            val side = dirFrom(part.center, player.entityPos).takeIf { horizontal(part.center, player.entityPos) > 0.1 } ?: rightVec()
            val fw = velocity.let { Vec3d(it.x, 0.0, it.z) }.takeIf { it.lengthSquared() > 0.01 }?.normalize() ?: flatForward()
            launch(player, Vec3d(side.x * push + fw.x * 0.8, if (heavy) 0.95 else 0.7, side.z * push + fw.z * 0.8))
            VoidVisuals.impact(world, player.entityPos.add(0.0, 1.0, 0.0), if (heavy) 2.5f else 1.4f)
        }
    }

    private fun tickBodyContact(world: ServerWorld) {
        if (exposed || cast == null && velocity.lengthSquared() < 0.25) return
        if (castPose() == WhalePose.RAM) return
        if (cast?.id == LOW_PASS) {
            if (velocity.lengthSquared() > 0.25) hitAlongBody(world, 5f, push = 0.7)
            return
        }
        hitAlongBody(world, 10f)
    }

    // ---------- Verschlingen und Bauch-Phase ----------

    private fun startDevour(world: ServerWorld) {
        val victims = targets(world).filter { it.isAlive }
        if (victims.isEmpty()) {
            bellyDone = true
            startPhase3(world)
            return
        }
        val c = groundCenter()
        val b = LeviathanBelly(world, LeviathanBelly.centerFor(world, c))
        belly = b
        bellyState = BELLY_DEVOUR
        bellyTicks = 0
        swallowed.clear()
        for (p in victims) {
            swallowed += p.uuid
            LeviathanBelly.mark(p, focus().add(12.0, 0.0, 0.0))
        }
        sceneDir = dirFrom(focus(), entityPos)
        say(world, "devour")
        beginScene(world, SCENE_DEVOUR, DEVOUR_CUT, focus(), yawOf(sceneDir))
    }

    private fun sceneDevour(world: ServerWorld, c: Vec3d, a: Int) {
        val b = belly ?: return
        if (!b.built) b.buildStep(BELLY_BUILD_BUDGET)
        val station = c.add(sceneDir.multiply(82.0)).add(0.0, 16.0, 0.0)
        when {
            a < 40 -> {
                if (a == 0) setPose(WhalePose.SWIM)
                fly(station, 2.4, 0.14)
                if (a > 24) faceTowards(c, 6f, 2f)
            }
            a == 40 -> {
                setPose(WhalePose.DEVOUR)
                sound(world, VoidSounds.WHALE_GROAN, 4f, 0.35f)
                sound(world, VoidSounds.WHALE_SONG, 4f, 0.4f)
                val holeAt = mouthPos().add(frame().headForward().multiply(12.0))
                holeId = VoidVisuals.newId()
                VoidVisuals.blackHole(world, holeId, holeAt, onFloor(holeAt, 0.0), 105)
                VoidVisuals.starfall(world, c, 100.0, 120, 100)
            }
            a < 140 -> {
                velocity = Vec3d(c.x - x, 0.0, c.z - z).normalize().multiply(0.25)
                velocityDirty = true
                faceTowards(c, 2f, 1f)
                val mouth = mouthPos()
                for (p in world.players) {
                    if (p.uuid !in swallowed) continue
                    val to = mouth.subtract(p.entityPos)
                    val d = to.length()
                    if (d > 14.0) launch(p, to.normalize().multiply(0.25 + (a - 40) * 0.004).add(0.0, 0.05, 0.0), keepFall = true)
                    else launch(p, Vec3d.ZERO, keepFall = false)
                    if (a % 6 == 0) VoidVisuals.streak(world, p.entityPos.add(0.0, 1.0, 0.0), mouth)
                }
                if (a % 20 == 0) sound(world, mouth, VoidSounds.BLACK_HOLE, 4f, 0.5f)
            }
            a == 142 -> {
                while (!b.built) b.buildStep(100_000)
                swallowAll(world, b)
                sound(world, VoidSounds.SWALLOW, 4f, 0.5f)
                endHole(world)
            }
            else -> {
                if (a == 160) setPose(WhalePose.GORGED)
                hover(c, 0.9)
            }
        }
    }

    private fun swallowAll(world: ServerWorld, b: LeviathanBelly) {
        var i = 0
        val inside = ArrayList<ServerPlayerEntity>()
        for (id in swallowed.toList()) {
            val p = world.server!!.playerManager.getPlayer(id) ?: continue
            if (!p.isAlive) {
                swallowed.remove(id)
                LeviathanBelly.clear(p)
                continue
            }
            val at = b.spawnPoint(i++)
            p.teleport(world, at.x, at.y + 0.1, at.z, emptySet(), -90f, 0f, false)
            p.velocity = Vec3d.ZERO
            p.velocityDirty = true
            p.fallDistance = 0.0
            p.addStatusEffect(StatusEffectInstance(StatusEffects.RESISTANCE, 110, 4, false, false, false))
            VoidVisuals.screen(p, 4, 200)
            inside += p
        }
        bellyState = BELLY_INSIDE
        bellyTicks = 0
        b.start(this, inside.size.coerceAtLeast(1))
        playCutscene(world, SCENE_BELLY, 100, b.anchor(), -90f, inside)
    }

    private fun tickBelly(world: ServerWorld) {
        val b = belly ?: return
        when (bellyState) {
            BELLY_INSIDE -> tickInside(world, b)
            BELLY_CLEANUP -> {
                if (b.removeStep(BELLY_REMOVE_BUDGET)) {
                    belly = null
                    bellyState = BELLY_NONE
                    bellyDone = true
                }
            }
        }
        if (bellyState == BELLY_DEVOUR || bellyState == BELLY_INSIDE || bellyState == BELLY_BREAKOUT) {
            for (id in swallowed) LeviathanBelly.claim(id, world.server!!.ticks.toLong())
        }
    }

    private fun tickInside(world: ServerWorld, b: LeviathanBelly) {
        val t = ++bellyTicks
        val server = world.server!!
        var offline = 0
        val inside = ArrayList<ServerPlayerEntity>()
        for (id in swallowed.toList()) {
            val p = server.playerManager.getPlayer(id)
            if (p == null) {
                offline++
                continue
            }
            if (!p.isAlive || p.isSpectator || (t > 20 && (p.entityWorld !== world || !b.contains(p.entityPos)))) {
                swallowed.remove(id)
                if (p.isAlive) LeviathanBelly.clear(p)
                continue
            }
            inside += p
            // Verdauung: langsamer Dauerschaden, nach 5 Minuten stärker
            if (t > 200 && t % 60 == 0) {
                hurtPlayer(world, p, if (t > 20 * 300) 3f else 1f, physical = false, inBelly = true)
                VoidVisuals.screen(p, 4, 70)
            }
        }
        if (inside.isEmpty() && (offline == 0 || t > 600)) {
            finishBelly(world, b, eject = false)
            startPhase3(world)
            return
        }
        if (cast == null && t % 50 == 25 && castPose() == WhalePose.GORGED && random.nextInt(3) == 0) {
            setPose(WhalePose.STAGGER)
            TickScheduler.schedule(20) { if (isAlive && bellyState == BELLY_INSIDE && cast == null) setPose(WhalePose.GORGED) }
        }
        if (b.tick(this, inside, t)) {
            startBreakout(world, b)
            return
        }
        if (t > 20 * BELLY_SECONDS) {
            say(world, "vomit")
            for (p in inside) hurtPlayer(world, p, 8f, physical = false, inBelly = true)
            finishBelly(world, b, eject = true)
            startPhase3(world)
        }
    }

    // Hilfen für die Gefahren im Bauch (LeviathanBelly)
    fun bellyHurt(p: ServerPlayerEntity, amount: Float) {
        (entityWorld as? ServerWorld)?.let { hurtPlayer(it, p, amount, physical = false, inBelly = true) }
    }

    fun bellyLaunch(p: ServerPlayerEntity, v: Vec3d) = launch(p, v)
    fun bellyPush(p: ServerPlayerEntity, dv: Vec3d) = launch(p, p.velocity.add(dv), keepFall = true)

    fun bellyStarlings(at: Vec3d, count: Int, cap: Int) {
        val world = entityWorld as? ServerWorld ?: return
        val b = belly ?: return
        val inBelly = world.getEntitiesByType(VoidBoss.DEBTOR, b.bounds()) { it.isAlive }.size
        if (inBelly >= cap) return
        summonStarlings(world, min(count, cap - inBelly), at, 6.0)
    }

    fun coreHit(world: ServerWorld, core: LeviathanCoreEntity, amount: Float) {
        if (isDead) return
        health = max(maxHealth * BELLY_FLOOR, health - amount * CORE_SHARE)
        if (random.nextInt(4) == 0) VoidVisuals.burst(world, core.entityPos.add(0.0, 1.5, 0.0), 1)
    }

    fun coreDestroyed(world: ServerWorld, core: LeviathanCoreEntity) {
        if (isDead) return
        val b = belly
        val inside = swallowed.mapNotNull { world.server!!.playerManager.getPlayer(it) }
        val share = b?.objectiveDied(this, core, inside) ?: 0.03f
        health = max(maxHealth * BELLY_FLOOR, health - maxHealth * share)
        VoidVisuals.nova(world, core.entityPos.add(0.0, 1.5, 0.0), 9f)
        sound(world, core.entityPos, VoidSounds.SUPERNOVA_BLAST, 3f, 1.4f)
        sound(world, VoidSounds.WHALE_HURT, 4f, 0.5f)
        if (core.role == LeviathanCoreEntity.CORE || core.role == LeviathanCoreEntity.HEART) say(world, "belly_core")
        for (p in inside) VoidVisuals.screen(p, 1, 8)
        if (sceneKind < 0 && cast == null) {
            setPose(WhalePose.STAGGER)
            TickScheduler.schedule(20) { if (isAlive && bellyState == BELLY_INSIDE && cast == null) setPose(WhalePose.GORGED) }
        }
    }

    private fun startBreakout(world: ServerWorld, b: LeviathanBelly) {
        bellyState = BELLY_BREAKOUT
        phase = 3
        dataTracker.set(PHASE, 3)
        novaReady = 500
        b.burstMembranes()
        say(world, "breakout")
        val viewers = players(world, 300.0) + swallowed.mapNotNull { world.server!!.playerManager.getPlayer(it) }
        beginScene(world, SCENE_BREAKOUT, BREAKOUT_CUT, focus(), yaw, viewers.distinct())
    }

    private fun sceneBreakout(world: ServerWorld, c: Vec3d, a: Int) {
        val b = belly
        val floor = groundCenter().y
        when {
            a == 0 -> {
                val k = Math.round(MathHelper.wrapDegrees(yaw) / 30f)
                val at = onFloor(c, 34.0)
                refreshPositionAndAngles(at.x, floor + 8.0, at.z, MathHelper.wrapDegrees(k * 30f), 0f)
                bodyYaw = yaw
                headYaw = yaw
                velocity = Vec3d.ZERO
                setPose(WhalePose.BREAKOUT)
            }
            a < 22 -> {
                velocity = Vec3d.ZERO
                pitch = 0f
                if (a % 5 == 0) VoidVisuals.impact(world, frame().flank(), 2.5f)
            }
            a == 22 -> {
                val flank = frame().flank()
                VoidVisuals.nova(world, flank, 20f)
                VoidVisuals.skyBeams(world, flank, 40)
                sound(world, flank, VoidSounds.SUPERNOVA_BLAST, 4f, 0.7f)
                sound(world, VoidSounds.WHALE_HURT, 4f, 0.4f)
                if (b != null) finishBelly(world, b, eject = true)
            }
            a < 60 -> {
                val goal = Vec3d(x, floor + LeviathanBody.BEACH_LIFT, z)
                velocity = goal.subtract(entityPos).multiply(0.15)
                velocityDirty = true
                pitch = MathHelper.stepTowards(pitch, 0f, 3f)
                if (a == 50) {
                    VoidVisuals.shockwave(world, Vec3d(x, floor + 0.5, z), Vec3d(x, floor, z), 30.0)
                    VoidVisuals.stomp(world, Vec3d(x, floor, z), 1f)
                    sound(world, VoidSounds.GRAVITY_WAVE, 4f, 0.5f)
                }
            }
            a == 60 -> {
                refreshPositionAndAngles(x, floor + LeviathanBody.BEACH_LIFT, z, yaw, 0f)
                velocity = Vec3d.ZERO
                setPose(WhalePose.REELING)
                crushUnderBody(world)
                reelStart = age
                exposed = true
            }
            else -> velocity = Vec3d.ZERO
        }
    }

    /**
     * Holt alle aus dem Bauch: [eject] = aus der aufgerissenen Flanke ins Freie (sicher über der Plattform),
     * sonst an ihren Rückkehrpunkt. Danach wird der Innenraum abgebaut.
     */
    private fun finishBelly(world: ServerWorld, b: LeviathanBelly, eject: Boolean) {
        val c = focus()
        var i = 0
        for (id in swallowed.toList()) {
            val p = world.server!!.playerManager.getPlayer(id) ?: continue
            LeviathanBelly.clear(p)
            if (!p.isAlive) continue
            val n = swallowed.size.coerceAtLeast(1)
            val ang = (i++).toDouble() / n * PI * 0.8 - PI * 0.4
            val side = if (eject && arena != null) rightVec() else Vec3d(1.0, 0.0, 0.0)
            val f = flatForward()
            var at = if (eject) bodyCenter().add(side.multiply(16.0 + 2.0 * cos(ang))).add(f.multiply(sin(ang) * 14.0)) else c.add(cos(ang) * 14.0, 0.0, sin(ang) * 14.0)
            at = safeSpot(world, onFloor(at, 6.0).add(0.0, if (eject) 9.0 else 1.5, 0.0))
            p.teleport(world, at.x, at.y, at.z, emptySet(), p.yaw, 0f, false)
            p.fallDistance = 0.0
            p.addStatusEffect(StatusEffectInstance(StatusEffects.SLOW_FALLING, 90, 0, false, false, true))
            p.addStatusEffect(StatusEffectInstance(StatusEffects.RESISTANCE, 60, 2, false, false, false))
            launch(p, side.multiply(0.6).add(0.0, 0.5, 0.0))
            VoidVisuals.screen(p, 1, 12)
            VoidVisuals.burst(world, at, 2)
        }
        swallowed.clear()
        b.discardObjectives()
        for (core in world.getEntitiesByType(VoidBoss.CORE, b.bounds()) { true }) core.discard()
        for (s in world.getEntitiesByType(VoidBoss.DEBTOR, b.bounds()) { true }) s.discard()
        bellyState = BELLY_CLEANUP
    }

    private fun safeSpot(world: ServerWorld, at: Vec3d): Vec3d {
        val pos = BlockPos.Mutable()
        for (dy in 0..40) {
            pos.set(at.x, at.y + dy, at.z)
            if (world.getBlockState(pos).getCollisionShape(world, pos).isEmpty &&
                world.getBlockState(pos.up()).getCollisionShape(world, pos.up()).isEmpty
            ) return Vec3d(at.x, pos.y.toDouble(), at.z)
        }
        return at
    }

    private fun recoverBelly(world: ServerWorld, center: BlockPos) {
        restoreBelly = null
        if (belly != null) return
        val b = LeviathanBelly(world, center)
        belly = b
        for (p in world.players) {
            if (b.contains(p.entityPos) || LeviathanBelly.isMarked(p)) swallowed += p.uuid
        }
        finishBelly(world, b, eject = false)
        if (phase < 3 && health / maxHealth <= DEVOUR_AT) {
            phase = 3
            dataTracker.set(PHASE, 3)
        }
        bellyDone = true
    }

    // ---------- Schutz vor der Leere ----------

    private fun catchFalling(world: ServerWorld) {
        if (arena == null) return
        ArenaGuard.active(uuid, world.registryKey, groundCenter(), focus(), world.time)
    }

    override fun remove(reason: RemovalReason) {
        val w = entityWorld
        if (w is ServerWorld && reason.shouldDestroy()) {
            ArenaGuard.ended(uuid)
            releaseTethers(w)
            belly?.let { b ->
                if (bellyState != BELLY_CLEANUP) finishBelly(w, b, eject = false)
                while (!b.removeStep(200_000)) Unit
                belly = null
            }
            discardParts()
            tickScars(w, force = true)
        } else if (reason == RemovalReason.UNLOADED_TO_CHUNK || reason == RemovalReason.UNLOADED_WITH_PLAYER) {
            discardParts()
        }
        super.remove(reason)
    }

    // ---------- Schaden ----------

    private fun hurtPlayer(world: ServerWorld, victim: LivingEntity, amount: Float, physical: Boolean, inBelly: Boolean = false) {
        if (sceneKind >= 0 || intro < INTRO_TICKS || isDead) return
        if (!inBelly && victim.uuid in swallowed) return
        val source = if (physical) world.damageSources.mobAttack(this) else world.damageSources.indirectMagic(this, this)
        victim.damage(world, source, amount)
    }

    override fun onKilledOther(world: ServerWorld, other: LivingEntity, damageSource: DamageSource?): Boolean {
        if (other is PlayerEntity) say(world, "player_down")
        return super.onKilledOther(world, other, damageSource)
    }

    // ---------- Tod ----------

    override fun onDeath(damageSource: DamageSource) {
        val world = entityWorld as? ServerWorld
        if (world != null) {
            cast = null
            exposed = false
            sceneKind = -1
            endHole(world)
            releaseTethers(world)
            belly?.let { if (bellyState != BELLY_CLEANUP) finishBelly(world, it, eject = false) }
            setPose(WhalePose.DEATH)
            say(world, "death")
            sound(world, VoidSounds.WHALE_DEATH, 4f, 0.7f)
            sound(world, VoidSounds.WHALE_GROAN, 4f, 0.35f)
            playCutscene(world, SCENE_DEATH, DEATH_TICKS, focus(), yaw)
            VoidVisuals.starfall(world, focus(), 110.0, 160, 220)
            for (starling in world.getEntitiesByType(VoidBoss.DEBTOR) { it.squaredDistanceTo(this) < 300.0 * 300.0 }) starling.kill(world)
        }
        super.onDeath(damageSource)
    }

    override fun drop(world: ServerWorld, damageSource: DamageSource) {
        pendingLoot = damageSource
    }

    /** Statt umzufallen brüllt er ein letztes Mal, bäumt sich auf, dreht sich auf den Rücken und zerfällt in ein Sternbild. */
    override fun updatePostDeath() {
        deathTime++
        val world = entityWorld as? ServerWorld ?: return
        bossBar.percent = 0f
        tickBelly(world)
        tickScars(world)
        val c = focus()
        val goal = c.add(0.0, 30.0 + deathTime * 0.08, 0.0)
        velocity = velocity.lerp(goal.subtract(entityPos).multiply(0.03), 0.1)
        velocityDirty = true
        levelOut()
        move(MovementType.SELF, velocity)
        updateParts(world)

        if (deathTime > 50 && deathTime % 4 == 0) {
            val at = frame().spine(random.nextDouble()).add((random.nextDouble() - 0.5) * 10.0, (random.nextDouble() - 0.5) * 8.0, (random.nextDouble() - 0.5) * 10.0)
            VoidVisuals.burst(world, at, 2)
            if (deathTime % 16 == 0) sound(world, at, SoundEvents.BLOCK_AMETHYST_BLOCK_RESONATE, 3f, 0.5f + random.nextFloat() * 0.6f)
            if (deathTime in 90..200 && deathTime % 20 == 0) VoidVisuals.nova(world, at, 8f)
        }
        if (deathTime == 1 || deathTime == 50) VoidVisuals.nova(world, mouthPos(), 12f)
        if (deathTime == CONSTELLATION_AT) {
            drawConstellation(world)
            VoidVisuals.nova(world, bodyCenter(), 40f)
            VoidVisuals.skyBeams(world, bodyCenter(), 80)
            sound(world, VoidSounds.SUPERNOVA_BLAST, 4f, 1.2f)
            say(world, "death_final")
        }
        if (deathTime >= DEATH_TICKS) {
            val drop = safeSpot(world, onFloor(c, 20.0).add(0.0, 1.0, 0.0))
            refreshPositionAndAngles(drop.x, drop.y, drop.z, yaw, 0f)
            pendingLoot?.let { super.drop(world, it) }
            dropStack(world, VoidBoss.songBook())
            ExperienceOrbEntity.spawn(world, drop, 900)
            VoidVisuals.nova(world, drop, 6f)
            remove(Entity.RemovalReason.KILLED)
        }
    }

    private fun drawConstellation(world: ServerWorld) {
        val c = focus().add(0.0, 70.0, 0.0)
        val dir = flatForward()
        val side = Vec3d(-dir.z, 0.0, dir.x)
        val k = 2.4
        val shape = listOf(
            Triple(14.0, 0.0, 0.0), Triple(9.0, 0.0, 4.0), Triple(2.0, 0.0, 5.0), Triple(-6.0, 0.0, 3.5),
            Triple(-12.0, 0.0, 1.0), Triple(-17.0, 0.0, 4.5), Triple(-17.0, 0.0, -2.5), Triple(-12.0, 0.0, 1.0),
            Triple(-5.0, 0.0, -3.0), Triple(3.0, 0.0, -3.5), Triple(9.0, 0.0, -2.5), Triple(14.0, 0.0, 0.0),
        )
        val points = shape.map { (f, s, u) -> c.add(dir.multiply(f * k)).add(side.multiply(s * k)).add(0.0, u * k, 0.0) }
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            val mid = a.add(b).multiply(0.5).add(0.0, 1.2, 0.0)
            VoidVisuals.arc(world, a, mid, b, 1, 600)
            VoidVisuals.burst(world, a, 2)
        }
        val fin = c.add(dir.multiply(4.0 * k)).add(0.0, -1.0 * k, 0.0)
        VoidVisuals.arc(world, points[9], fin.add(side.multiply(2.0 * k)), fin.add(side.multiply(5.0 * k)).add(0.0, -3.0 * k, 0.0), 1, 600)
    }

    // ---------- Hilfen ----------

    private fun playCutscene(world: ServerWorld, scene: Int, ticks: Int, anchor: Vec3d, anchorYaw: Float, viewers: List<ServerPlayerEntity>? = null) {
        val list = viewers ?: players(world, 300.0).filter { it.uuid !in swallowed || scene == SCENE_DEVOUR }
        for (player in list) {
            VoidVisuals.cutsceneTo(player, scene, id, anchor, anchorYaw)
            player.addStatusEffect(StatusEffectInstance(StatusEffects.RESISTANCE, ticks + 10, 4, false, false, false))
        }
    }

    private fun launch(player: ServerPlayerEntity, velocity: Vec3d, keepFall: Boolean = false) {
        if (!keepFall) player.fallDistance = 0.0
        dev.henny.hha.logic.Fx.launchPlayer(player, velocity)
    }

    private fun findGround(world: ServerWorld, around: Vec3d): Vec3d? {
        var pos = BlockPos.ofFloored(around.x, around.y + 3.0, around.z)
        repeat(24) {
            val below = pos.down()
            if (!world.getBlockState(below).getCollisionShape(world, below).isEmpty && world.getBlockState(pos).isAir) {
                return Vec3d(around.x, pos.y.toDouble(), around.z)
            }
            pos = below
        }
        return null
    }

    private fun say(world: ServerWorld, key: String) {
        val line = Text.literal("♪ ").formatted(Formatting.DARK_AQUA)
            .append(Text.translatable("hha_void.boss.name").formatted(Formatting.DARK_PURPLE, Formatting.BOLD))
            .append(Text.literal(": ").formatted(Formatting.DARK_GRAY))
            .append(Text.translatable("hha_void.boss.line.$key").formatted(Formatting.LIGHT_PURPLE, Formatting.ITALIC))
        val listeners = players(world, 300.0).toMutableList()
        for (id in swallowed) world.server!!.playerManager.getPlayer(id)?.let { if (it !in listeners) listeners += it }
        for (player in listeners) player.sendMessage(line, false)
    }

    private fun showTitle(world: ServerWorld, key: String) {
        for (player in players(world, 300.0)) {
            player.networkHandler.sendPacket(SubtitleS2CPacket(Text.translatable("hha_void.boss.title.$key.sub").formatted(Formatting.LIGHT_PURPLE)))
            player.networkHandler.sendPacket(TitleS2CPacket(Text.translatable("hha_void.boss.title.$key").formatted(Formatting.GOLD, Formatting.BOLD)))
        }
    }

    private fun sound(world: ServerWorld, event: SoundEvent, volume: Float, pitch: Float) =
        sound(world, bodyCenter(), event, volume, pitch)

    /** Laut genug für die große Arena (Reichweite ≈ 16 × Lautstärke). */
    private fun sound(world: ServerWorld, at: Vec3d, event: SoundEvent, volume: Float, pitch: Float) =
        world.playSound(null, at.x, at.y, at.z, event, SoundCategory.HOSTILE, volume * 3.5f, pitch)

    companion object {
        private val POSE: TrackedData<Int> = DataTracker.registerData(VoidBossEntity::class.java, TrackedDataHandlerRegistry.INTEGER)
        private val PHASE: TrackedData<Int> = DataTracker.registerData(VoidBossEntity::class.java, TrackedDataHandlerRegistry.INTEGER)
        private val FLAGS: TrackedData<Int> = DataTracker.registerData(VoidBossEntity::class.java, TrackedDataHandlerRegistry.INTEGER)
        private val SWIM: TrackedData<Float> = DataTracker.registerData(VoidBossEntity::class.java, TrackedDataHandlerRegistry.FLOAT)
        private const val FLAG_SOLID = 1
        private const val FLAG_HITTABLE = 2

        // Körpermaße (passend zum Modell, siehe LeviathanBody)
        const val BODY_UP = LeviathanBody.BODY_UP
        const val MOUTH_FORWARD = LeviathanBody.MOUTH_FORWARD
        const val HEART_FORWARD = LeviathanBody.HEART_FORWARD
        const val FLUKE_BACK = LeviathanBody.FLUKE_BACK

        // Auftritt (muss zu VoidScene.INTRO passen)
        const val INTRO_TICKS = 220
        private const val INTRO_EMERGE = 34
        private const val INTRO_SONG = 168
        private const val INTRO_RIFT_DISTANCE = 88.0
        private const val INTRO_RIFT_HEIGHT = 42.0

        private const val SCENE_INTRO = 0
        private const val SCENE_PHASE_2 = 1
        private const val SCENE_PHASE_3 = 2
        private const val SCENE_DEATH = 5
        private const val SCENE_DEVOUR = 6
        private const val SCENE_BELLY = 7
        private const val SCENE_BREAKOUT = 8
        private const val PHASE_2_CUT = 200
        private const val PHASE_3_CUT = 110
        private const val DEVOUR_CUT = 180
        private const val BREAKOUT_CUT = 150
        const val DEATH_TICKS = 260
        private const val CONSTELLATION_AT = 150

        private const val PHASE_2_AT = 0.70f
        private const val DEVOUR_AT = 0.45f

        // Arena und Flug
        private const val ARENA_RANGE = TribunalBuilder.RADIUS + 120.0
        private const val ORBIT_RADIUS = 64.0
        private const val ORBIT_HEIGHT = 26.0
        private const val ORBIT_HEIGHT_P3 = 17.0
        private const val FOCUS_MARGIN = 30.0
        private const val RAM_Y = 2.0
        private const val IDLE_LINES = 6

        // Leben
        private const val BASE_HEALTH = 900.0
        private const val HEALTH_PER_PLAYER = 420.0
        private const val MAX_SCALED_PLAYERS = 10
        private const val MAX_HIT = 45f
        private const val EXPOSED_FACTOR = 1.3f

        // Angriffe
        private const val SLAM_RADIUS = 10.0
        private const val WAVE_RANGE = 48.0
        private const val WAVE_TICKS = 30
        private const val BREATH_HALF = 24.0
        private const val BREATH_RADIUS = 2.3
        private const val ROCK_RADIUS = 4.5
        private const val BREACH_RADIUS = 13.0
        private const val BREACH_UP = 2.9
        private const val BREACH_G = 0.11
        private const val BREACH_FLIGHT = 72.0
        private const val SNAP_REACH = 6.5
        private const val GEYSER_RADIUS = 5.0
        private const val NOVA_COOLDOWN = 900
        private const val SUPERNOVA_DAMAGE = 30f
        private const val RIDER_PATIENCE = 100
        private const val RAM_RUNUP = 60.0
        private const val RAM_LENGTH = 150.0
        private const val RAM_HALF_WIDTH = 9.0
        private const val FLOOD_HALF = 70.0
        private const val FLOOD_WIDTH = 80.0
        private const val ERUPTION_RADIUS = 72.0
        private const val LOW_PASS_HALF = 70.0
        private const val LOW_PASS_Y = 2.6
        private const val LOW_PASS_SPEED = 1.0
        private const val TETHER_TICKS = 400
        private const val PIN_COOLDOWN = 900

        /** Wie weit die Clients den Wal hinter dem Server sehen (Interpolation + Netz). */
        const val CLIENT_LAG = 3

        // Gelände
        private const val TERRAIN_CLEARANCE = 0.6
        private const val HOLE_RADIUS = 14.0
        private const val HOLE_OPEN_TICKS = 120

        // Bauch
        private const val BELLY_NONE = 0
        private const val BELLY_DEVOUR = 1
        private const val BELLY_INSIDE = 2
        private const val BELLY_BREAKOUT = 3
        private const val BELLY_CLEANUP = 4
        private const val BELLY_BUILD_BUDGET = 2600
        private const val BELLY_REMOVE_BUDGET = 1800
        private const val BELLY_SECONDS = 450
        private const val CORE_SHARE = 0.3f
        private const val BELLY_FLOOR = 0.1f

        // Angriffsnummern (`/voidboss attack <n>`)
        const val TAIL_SLAM = 0
        const val BREATH = 1
        const val METEORS = 2
        const val RAM = 3
        const val MAELSTROM = 4
        const val BREACH = 5
        const val SNAP = 6
        const val STARLINGS = 7
        const val SUPERNOVA = 8
        const val GEYSER = 9
        const val FLOOD = 10
        const val BARREL_ROLL = 11
        const val BACK_QUAKE = 12
        const val TAIL_SWEEP = 13
        const val SOUNDING = 14
        const val DEVOUR = 15
        const val ERUPTION = 16
        const val PINNED = 17
        const val LOW_PASS = 18
        const val ATTACK_COUNT = 19
        private const val REELING_CAST = 99
        private const val SPENT = 98

        val ATTACK_NAMES = listOf(
            "Flukenschlag", "Sternenatem", "Sternenregen", "Rammstoß", "Mahlstrom", "Durchbruch", "Kieferschnappen",
            "Sternlinge", "Supernova", "Blasloch-Geysir", "Flutwelle", "Fassrolle", "Rückenbeben", "Schwanzpeitsche",
            "Abtauchen", "Verschlingen", "Leere-Eruption", "Festgenagelt", "Tiefflug",
        )
    }
}
