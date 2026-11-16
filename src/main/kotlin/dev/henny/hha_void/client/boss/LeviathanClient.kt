package dev.henny.hha_void.client.boss

import dev.henny.hha_void.boss.LeviathanBody
import dev.henny.hha_void.boss.LeviathanPartEntity
import dev.henny.hha_void.boss.VoidBossEntity
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.MinecraftClient
import net.minecraft.client.network.ClientPlayerEntity
import net.minecraft.client.world.ClientWorld
import net.minecraft.util.math.Vec3d

/**
 * Auf dem Rücken des Leviathans stehen (Client-Seite).
 *
 * Die Hitbox-Volumen setzt der Client jeden Tick aus der Lage, die er selbst vom Wal sieht ([VoidBossEntity.clientFrame],
 * gleiche Knochen-Rechnung wie der Server). Wer auf dem Wal steht („Reiter"), kollidiert nicht mit den Boxkanten, sondern
 * steht auf der **glatten Rückenfläche** ([LeviathanBody.Frame.surface]):
 *  - Mitnehmen: Ändert sich die Lage des Wals, wird der Reiter genau wie die Haut unter ihm verschoben und gedreht
 *    ([LeviathanBody.carry]) – auch in Kurven, im Steig- und Sinkflug und wenn sich der Körper biegt. Jede Lageänderung
 *    wird genau einmal übertragen, egal ob der Wal vor oder nach dem Spieler tickt.
 *  - Stehen: kurz vor dem Bewegungspaket ([beforeMovementPacket], Mixin) wird er auf die Fläche gesetzt und meldet
 *    „am Boden" – kein Ruckeln über Boxkanten, kein Durchfallen, kein Fallschaden, Springen und Mace-Schläge gehen.
 *  - Abwurf: der Server schickt `RIDE_RELEASE` (Fassrolle, Rückenbeben …) – dann wird er eine Weile nicht mitgenommen.
 */
object LeviathanClient {

    /** Wal, auf dem der eigene Spieler gerade „mitfährt", das Volumen darunter und die Lage, an die er angepasst ist. */
    private var riding = -1
    private var anchor = -1
    private var aligned: LeviathanBody.Frame? = null
    private var airTicks = 0
    private var steepTicks = 0
    private var releaseTicks = 0
    private val LOG = System.getenv("HHA_VOID_TEST") != null

    /** Für Tests/HUD: steht der Spieler gerade auf dem Wal? */
    @JvmStatic
    var onBack = false
        private set

    /** Für Tests: reitet der Spieler (auch im Sprung über dem Rücken)? */
    @JvmStatic
    fun isRiding() = riding >= 0

    fun init() {
        ClientTickEvents.END_WORLD_TICK.register(::tick)
        LeviathanPartEntity.passThrough = { e -> riding >= 0 && e === MinecraftClient.getInstance().player }
    }

    /** Vom Server: abgeworfen – [ticks] lang nicht wieder aufnehmen. */
    fun release(ticks: Int, why: String = "server") {
        if (riding >= 0 && LOG) println("[ride] release ($why) ticks=$ticks")
        riding = -1
        aligned = null
        releaseTicks = ticks
    }

    private fun boss(world: ClientWorld?): VoidBossEntity? = world?.getEntityById(riding) as? VoidBossEntity

    /** Reiter an die aktuelle Lage des Wals anpassen (Verschiebung + Drehung), genau einmal je neuer Lage. */
    private fun follow(player: ClientPlayerEntity, boss: VoidBossEntity) {
        val now = boss.clientFrame ?: return
        val old = aligned
        if (old === now) return
        aligned = now
        if (old == null || anchor < 0) return
        val next = LeviathanBody.carry(player.entityPos, anchor, old, now) ?: return
        // Sprünge (Teleport des Wals, Haltungswechsel gestrandet ↔ Flug) nicht mitmachen
        if (next.squaredDistanceTo(player.entityPos) > 8.0 * 8.0) return
        player.setPosition(next)
        val turn = LeviathanBody.turn(anchor, old, now)
        player.yaw += turn
        player.headYaw += turn
        player.bodyYaw += turn
    }

    private fun tick(world: ClientWorld) {
        val client = MinecraftClient.getInstance()
        val player = client.player
        if (releaseTicks > 0) releaseTicks--
        for (e in world.entities) {
            if (e !is LeviathanPartEntity) continue
            val boss = world.getEntityById(e.ownerId()) as? VoidBossEntity ?: continue
            val frame = boss.clientFrame ?: continue
            e.place(frame.boxes[e.index()])
        }
        if (player == null || player.isSpectator || player.hasVehicle()) {
            riding = -1
            return
        }
        val current = boss(world)
        if (riding >= 0) {
            if (current == null || current.isRemoved || !current.partsSolid()) {
                release(10, "boss weg/nicht fest")
                return
            }
            follow(player, current)
            return
        }
        if (releaseTicks > 0) return
        // Aufsteigen: steht auf einer Box (oder ganz knapp über dem Rücken)
        for (e in world.entities) {
            if (e !is VoidBossEntity || !e.partsSolid()) continue
            val frame = e.clientFrame ?: continue
            if (e.squaredDistanceTo(player) > 80.0 * 80.0) continue
            val s = frame.surface(player.entityPos, above = 1.0, below = 1.6) ?: continue
            val d = player.y - s.y
            if (d in -1.0..1.6 && s.up > 0.4 && (player.isOnGround || player.velocity.y <= 0.05)) {
                riding = e.id
                anchor = s.volume
                aligned = frame
                airTicks = 0
                break
            }
        }
    }

    /** Nach der eigenen Bewegung, vor dem Bewegungspaket: auf die Rückenfläche setzen (siehe Mixin). */
    @JvmStatic
    fun beforeMovementPacket(player: ClientPlayerEntity) {
        onBack = false
        if (riding < 0) return
        val boss = boss(player.entityWorld as? ClientWorld)
        if (boss == null || !boss.partsSolid()) {
            release(10, "nicht fest")
            return
        }
        follow(player, boss)
        val frame = boss.clientFrame ?: return
        val s = frame.surface(player.entityPos, above = 1.6, below = 14.0)
        if (s == null) {
            // über den Rand gelaufen
            if (++airTicks > 6) release(0, "keine Fläche")
            return
        }
        if (s.up < 0.45) {
            // steil (Flanke, Rolle, Sturzflug): man rutscht zur Rückenmitte, statt sofort abzufallen –
            // erst wenn die Fläche fast senkrecht bleibt, verliert man den Halt
            if (s.up < 0.15) {
                if (++steepTicks > 8) {
                    release(12, "zu steil ${"%.2f".format(s.up)}")
                    return
                }
            } else {
                steepTicks = 0
            }
            frame.centers[s.volume]?.let { c ->
                val dx = c.x - player.x; val dz = c.z - player.z
                val l = kotlin.math.sqrt(dx * dx + dz * dz)
                if (l > 0.3) player.velocity = player.velocity.add(dx / l * 0.06, 0.0, dz / l * 0.06)
            }
        } else {
            steepTicks = 0
        }
        val d = player.y - s.y
        val vy = player.velocity.y
        if (d < 0.05 || (d < 0.3 && vy <= 0.0)) {
            player.setPosition(player.x, s.y, player.z)
            if (vy < 0.0) player.velocity = Vec3d(player.velocity.x, 0.0, player.velocity.z)
            player.isOnGround = true
            player.fallDistance = 0.0
            anchor = s.volume
            airTicks = 0
            onBack = true
        } else {
            anchor = s.volume
            // im Sprung über dem Rücken weiter tragen, aber nicht ewig
            if (++airTicks > 80 || d > 14.0) release(0, "Luft $airTicks d=${"%.1f".format(d)}")
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Sternenkern im Bauch (hha_void:leviathan_core): Kristallherz in einer Fleischkapsel an Sehnen.
// Geometrie/Texturen: tools/textures/GenWhale.java (CoreModelData, textures/entity/leviathan_core*.png).

val CORE_LAYER = net.minecraft.client.render.entity.model.EntityModelLayer(net.minecraft.util.Identifier.of("hha_void", "leviathan_core"), "main")

class CoreRenderState : net.minecraft.client.render.entity.state.LivingEntityRenderState() {
    /** Leben 0..1 (steuert Risse und Unruhe). */
    var hp = 1f

    /** 0..1 während des Zerberstens (Todes-Ticks der Entity). */
    var dying = 0f

    /** 1 direkt nach einem Treffer, fällt in einer halben Sekunde auf 0. */
    var flinch = 0f
    var seed = 0f

    /** Größe der Rolle (Würgknoten … Sternenherz) und ob das Herz geschützt ist. */
    var size = 1f
    var shield = false
}

/** Herzschlag (Doppelschlag), 0..1; schneller, je weniger Leben. */
internal fun coreBeat(t: Float, hp: Float): Float {
    val rate = 0.2f + 0.12f * (1f - hp)
    val a = kotlin.math.max(0f, kotlin.math.sin(t * rate))
    val b = kotlin.math.max(0f, kotlin.math.sin(t * rate - 0.9f))
    return (a * a * a * a + 0.6f * b * b * b * b).coerceAtMost(1f)
}

class CoreModel(root: net.minecraft.client.model.ModelPart) : net.minecraft.client.render.entity.model.EntityModel<CoreRenderState>(root) {

    private fun part(name: String) = CoreModelData.part(root, name)

    private val base = part("root")
    private val heart = part("heart")
    private val crystal = part("crystal")
    private val flare = part("flare")
    private val collar = part("collar")
    private val petals = listOf("n", "e", "s", "w").map { part("petal_${it}_lo") to part("petal_${it}_hi") }
    private val tendons = List(4) { part("tendon${it}_a") to part("tendon${it}_b") }
    private val strands = List(2) { part("strand${it}_a") to part("strand${it}_b") }
    private val orbit = part("orbit")
    private val shards = List(3) { part("shard$it") }

    override fun setAngles(s: CoreRenderState) {
        super.setAngles(s)
        val t = s.age + s.seed
        val hurt = 1f - s.hp
        val beat = coreBeat(t, s.hp)
        val d = s.dying
        val hit = s.flinch * s.flinch
        // je weniger Leben, desto unruhiger zittert er
        val shake = 0.01f * hurt * hurt + 0.06f * hit + 0.05f * d
        base.yaw += kotlin.math.sin(t * 2.7f) * shake
        base.roll += kotlin.math.sin(t * 3.3f + 1f) * shake
        // Kristallherz: dreht sich langsam, schlägt, schwillt beim Zerbersten an und zerfällt dann
        val swell = 1f + 0.13f * beat + 0.08f * hit + 0.5f * d * (1f - d)
        val gone = if (d > 0.6f) 1f - (d - 0.6f) / 0.4f else 1f
        heart.originY += kotlin.math.sin(t * 0.08f) * 0.8f
        crystal.yaw += t * 0.02f
        scale(crystal, swell * gone.coerceAtLeast(0.02f))
        val glow = 1f + 0.8f * beat + 0.6f * hurt + 2.5f * d
        scale(flare, glow * gone.coerceAtLeast(0.02f))
        flare.yaw = t * 0.013f
        // Fleischkapsel: öffnet sich im Herzschlag, mit sinkendem Leben weiter (der Kern liegt bloß), beim Tod ganz
        for ((i, p) in petals.withIndex()) {
            val open = 0.3f * beat + 0.35f * hurt + 1.1f * d + 0.04f * kotlin.math.sin(t * 0.13f + i)
            p.first.pitch += open
            p.second.pitch += 0.4f * beat - 0.3f * d
        }
        collar.yScale = 1f + 0.05f * beat
        // Sehnen spannen sich im Takt und schwingen leicht
        for ((i, p) in tendons.withIndex()) {
            p.first.pitch += 0.04f * beat + 0.03f * kotlin.math.sin(t * 0.09f + i * 1.7f)
            p.second.pitch += 0.05f * kotlin.math.sin(t * 0.09f + i * 1.7f - 0.6f)
        }
        for ((i, p) in strands.withIndex()) {
            p.first.roll += 0.05f * kotlin.math.sin(t * 0.07f + i * 2f)
            p.second.roll += 0.08f * kotlin.math.sin(t * 0.07f + i * 2f - 0.8f) - 0.05f * beat
        }
        // Splitter kreisen, schneller wenn er verletzt ist, fliegen beim Zerbersten davon
        orbit.yaw = t * (0.035f + 0.04f * hurt)
        val fly = 1f + 2.5f * d
        orbit.xScale = fly; orbit.zScale = fly
        for ((i, sh) in shards.withIndex()) {
            sh.originY += kotlin.math.sin(t * 0.1f + i * 2.1f) * 1.5f
            sh.yaw += t * 0.05f
        }
    }

    private fun scale(p: net.minecraft.client.model.ModelPart, k: Float) {
        p.xScale = k; p.yScale = k; p.zScale = k
    }
}

class CoreRenderer(context: net.minecraft.client.render.entity.EntityRendererFactory.Context) :
    net.minecraft.client.render.entity.LivingEntityRenderer<dev.henny.hha_void.boss.LeviathanCoreEntity, CoreRenderState, CoreModel>(
        context, CoreModel(context.getPart(CORE_LAYER)), 0f,
    ) {

    init {
        addFeature(GlowFeature(this, { s -> GLOWS[stage(s)] }, { s -> grey(0.75f + 0.25f * coreBeat(s.age + s.seed, s.hp)) }))
    }

    override fun getTexture(state: CoreRenderState): net.minecraft.util.Identifier = TEXTURES[stage(state)]

    override fun createRenderState() = CoreRenderState()

    override fun scale(state: CoreRenderState, matrices: net.minecraft.client.util.math.MatrixStack) {
        matrices.scale(state.size, state.size, state.size)
    }

    override fun updateRenderState(entity: dev.henny.hha_void.boss.LeviathanCoreEntity, state: CoreRenderState, tickDelta: Float) {
        super.updateRenderState(entity, state, tickDelta)
        state.hp = (entity.health / entity.maxHealth).coerceIn(0f, 1f)
        state.dying = if (entity.deathTime > 0) ((entity.deathTime + tickDelta) / 20f).coerceIn(0f, 1f) else 0f
        state.flinch = if (entity.isDead) 0f else ((entity.hurtTime - tickDelta) / 10f).coerceIn(0f, 1f)
        state.seed = (entity.id % 50) * 3.1f
        state.size = dev.henny.hha_void.boss.LeviathanCoreEntity.scale(entity.role)
        state.shield = entity.shielded
        // kein Umkippen beim Tod: er zerbirst (siehe Modell)
        state.deathTime = 0f
    }

    /** Leuchtet von selbst. */
    override fun getBlockLight(entity: dev.henny.hha_void.boss.LeviathanCoreEntity, pos: net.minecraft.util.math.BlockPos): Int = 15

    override fun hasLabel(entity: dev.henny.hha_void.boss.LeviathanCoreEntity, squaredDistanceToCamera: Double) = false

    private fun stage(s: CoreRenderState) = when {
        s.hp > 0.6f && s.dying == 0f -> 0
        s.hp > 0.25f && s.dying == 0f -> 1
        else -> 2
    }

    private companion object {
        val TEXTURES = listOf("leviathan_core", "leviathan_core_cracked", "leviathan_core_broken").map {
            net.minecraft.util.Identifier.of("hha_void", "textures/entity/$it.png")
        }
        val GLOWS = listOf("leviathan_core", "leviathan_core_cracked", "leviathan_core_broken").map {
            net.minecraft.client.render.RenderLayers.eyes(net.minecraft.util.Identifier.of("hha_void", "textures/entity/${it}_glow.png"))
        }
    }
}
