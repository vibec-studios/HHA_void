package dev.henny.hha_void.client.boss

import dev.henny.hha_void.boss.LeviathanBody
import dev.henny.hha_void.boss.VoidBoss
import dev.henny.hha_void.boss.VoidBossEntity
import dev.henny.hha_void.boss.VoidDebtorEntity
import dev.henny.hha_void.boss.WhalePose
import net.fabricmc.fabric.api.client.rendering.v1.EntityModelLayerRegistry
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry
import net.minecraft.client.render.OverlayTexture
import net.minecraft.client.render.RenderLayer
import net.minecraft.client.render.RenderLayers
import net.minecraft.client.render.command.OrderedRenderCommandQueue
import net.minecraft.client.render.entity.EntityRendererFactory
import net.minecraft.client.render.entity.MobEntityRenderer
import net.minecraft.client.render.entity.feature.FeatureRenderer
import net.minecraft.client.render.entity.feature.FeatureRendererContext
import net.minecraft.client.render.entity.model.EntityModel
import net.minecraft.client.render.entity.model.EntityModelLayer
import net.minecraft.client.render.entity.state.LivingEntityRenderState
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.util.Identifier
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Box
import net.minecraft.util.math.MathHelper
import org.joml.Quaternionf
import org.joml.Vector3f
import java.util.WeakHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

class WhaleRenderState : LivingEntityRenderState() {
    var pose = WhalePose.HIDDEN
    var poseAge = 100f
    var previousPose = WhalePose.HIDDEN
    var previousPoseAge = 100f
    var phase = 1

    /** 1 direkt nach einem Treffer, fällt in einer halben Sekunde auf 0. */
    var flinch = 0f

    /** Geglättete Neigung des Rumpfs im Bogenmaß (positiv = Kopf runter); folgt der Flugbahn. */
    var pitchRad = 0f

    /** Fortlaufende Phase der Schwimmwelle; läuft schneller, je schneller er fliegt. */
    var swimPhase = 0f

    /** Schräglage in der Kurve im Bogenmaß (positiv = Rechtskurve, rechte Seite unten). */
    var bank = 0f

    /** Der Kopf geht der Drehung voraus: Vorhalt für Hals + Kopf im Bogenmaß (positiv = nach rechts bzw. unten). */
    var leadYaw = 0f
    var leadPitch = 0f

    /** Bauch, Hüfte, Schwanz 1-3 und Fluke ziehen der Bahn nach: Knick je Gelenk im Bogenmaß. */
    val bendPitch = FloatArray(6)
    val bendYaw = FloatArray(6)

    /** Geglättetes Steigen (+1) und Sinken (-1): steuert Schlagstärke und Flossenstellung. */
    var climb = 0f

    /** Nachschwingen der Brustflossen beim Steigen und Sinken. */
    var finLag = 0f

    /** Zuletzt gezeigtes Rig beim Haltungswechsel (Start der Überblendung); null = aus der vorigen Haltung rechnen. */
    var rigFrom: FloatArray? = null

    /** Hierhin schreibt das Modell das gezeigte Rig zurück. */
    var rigOut: FloatArray? = null

    /**
     * Harpunenseile: Richtung, in die das obere Seilstück hängt ([ropeA]), und die träger nachschwingende Richtung
     * der unteren Glieder ([ropeB]) – beide im Bezugssystem des Rumpfs (+Y unten), Länge 1.
     */
    val ropeA = floatArrayOf(0f, 1f, 0f)
    val ropeB = floatArrayOf(0f, 1f, 0f)

    /** Hierhin schreibt das Modell die Drehung des Rumpfs (Neigung, Gieren, Rollen) für die Seile im nächsten Bild. */
    var bodyRot: FloatArray? = null
}

class StarlingRenderState : LivingEntityRenderState() {
    /** 0 = flattert, 1 = Sturzangriff. */
    var dive = 0f
    var seed = 0f
}

/** Leuchtschicht über einem Modell: volle Helligkeit, Textur und Tönung je Bild. */
internal class GlowFeature<S : LivingEntityRenderState, M : EntityModel<S>>(
    context: FeatureRendererContext<S, M>,
    private val layer: (S) -> RenderLayer?,
    private val tint: (S) -> Int,
) : FeatureRenderer<S, M>(context) {
    override fun render(matrices: MatrixStack, queue: OrderedRenderCommandQueue, light: Int, state: S, limbAngle: Float, limbDistance: Float) {
        val l = layer(state) ?: return
        queue.getBatchingQueue(1).submitModel(
            contextModel, state, matrices, l, 0xF000F0, OverlayTexture.DEFAULT_UV, tint(state), null, state.outlineColor, null,
        )
    }
}

internal fun grey(v: Float): Int {
    val c = (255 * v.coerceIn(0f, 1f)).toInt()
    return (0xFF shl 24) or (c shl 16) or (c shl 8) or c
}

class WhaleRenderer(context: EntityRendererFactory.Context) :
    MobEntityRenderer<VoidBossEntity, WhaleRenderState, WhaleModel>(context, WhaleModel(context.getPart(WHALE_LAYER)), 0f) {

    /**
     * Gedächtnis je Wal zwischen den Bildern. Alles, was sich über Zeit einschwingt, lebt hier: die geglättete
     * Ausrichtung, die nachziehende Kette der Schwanzsegmente, Schräglage, Schlagphase, Steigen/Sinken.
     * Winkel in Grad (Yaw fortlaufend, nicht auf ±180 begrenzt).
     */
    private class Memory {
        var time = Float.NaN

        // Bewegung je Tick. Der Client holt die Position in Zweierschritten nach (zwei Ticks dieselbe Richtung),
        // darum zählt das Mittel aus zwei Schritten; zwischen vorigem und aktuellem Mittel wird je Bild interpoliert
        var tick = Int.MIN_VALUE
        var lvx = 0f; var lvy = 0f; var lvz = 0f
        var pvx = 0f; var pvy = 0f; var pvz = 0f
        var cvx = 0f; var cvy = 0f; var cvz = 0f
        var speed = 0f

        // Richtung der Flugbahn und wie stark sie gilt (0 = Entity-Winkel, 1 = Flugbahn)
        var pathYaw = 0f
        var pathPitch = 0f
        var followYaw = 0f
        var followPitch = 0f

        // Ausrichtung des Rumpfs
        var goalYaw = 0f
        var goalPitch = 0f
        val yaw = Spring()
        val pitch = Spring()
        var flight = 1f

        // Welt-Ausrichtung von Bauch, Hüfte, Schwanz 1-3, Fluke: jedes Segment folgt dem davor
        val segYaw = FloatArray(6)
        val segPitch = FloatArray(6)

        // Harpunenseile: Drehung des Rumpfs aus dem letzten Bild, Hängerichtung (schnell/träge) im Rumpf
        val bodyRot = FloatArray(3)
        val ropeA = floatArrayOf(0f, 1f, 0f)
        val ropeB = floatArrayOf(0f, 1f, 0f)
        val ropeVa = FloatArray(3)
        val ropeVb = FloatArray(3)

        var bank = 0f
        var climb = 0f
        var finLag = 0f
        var dive = 0f
        var phase = 0f

        // Überblendung zwischen Haltungen
        var pose = WhalePose.HIDDEN
        val shown = FloatArray(WhaleModel.RIG_SIZE)
        val from = FloatArray(WhaleModel.RIG_SIZE)
        var shownValid = false
        var fromValid = false
    }

    /** Kritisch gedämpfte Feder: folgt dem Ziel ohne Überschwingen; Ort und Tempo bleiben stetig. */
    private class Spring {
        var x = 0f
        var v = 0f

        fun snap(to: Float) {
            x = to
            v = 0f
        }

        /** [lag] = Ticks, die sie einem gleichmäßig wandernden Ziel hinterherläuft. */
        fun step(target: Float, lag: Float, dt: Float) {
            if (dt <= 0f) return
            val w = 2f / lag
            val e = exp(-w * dt)
            val c = x - target
            val k = (v + w * c) * dt
            x = target + (c + k) * e
            v = (v - w * k) * e
        }
    }

    private val memory = WeakHashMap<VoidBossEntity, Memory>()

    init {
        addFeature(GlowFeature(this, { s ->
            if (s.pose == WhalePose.HIDDEN) null else if (s.phase >= 3) GLOW_P3 else GLOW
        }, { s ->
            // die Sterne funkeln leicht; in Phase 3 flackert das Licht in den Rissen
            if (s.phase >= 3) grey(0.88f + 0.12f * sin(s.age * 0.37f) * sin(s.age * 0.13f)) else grey(0.9f + 0.1f * sin(s.age * 0.08f))
        }))
    }

    override fun getTexture(state: WhaleRenderState): Identifier = if (state.phase >= 3) TEXTURE_P3 else TEXTURE

    override fun createRenderState() = WhaleRenderState()

    override fun updateRenderState(entity: VoidBossEntity, state: WhaleRenderState, tickDelta: Float) {
        super.updateRenderState(entity, state, tickDelta)
        // Der Tod läuft als eigene Animation: kein Umfallen, keine rote Tönung über die ganze Szene
        state.deathTime = 0f
        state.hurt = entity.hurtTime > 0 && !entity.isDead
        state.flinch = if (entity.isDead) 0f else ((entity.hurtTime - tickDelta) / 10f).coerceIn(0f, 1f)
        // Haltung und ihr Alter kommen beide aus dem Entity-Tick; der DataTracker kann schon mitten im Tick
        // umspringen, dann liefe die neue Haltung kurz mit dem Alter der alten
        state.pose = entity.clientShownPose()
        state.poseAge = entity.clientPoseAge(tickDelta)
        state.previousPose = entity.clientPreviousPose()
        state.previousPoseAge = entity.clientPreviousPoseAge(tickDelta)
        state.phase = entity.bossPhase()

        val m = memory.getOrPut(entity) { Memory() }
        val now = entity.age + tickDelta
        val gap = now - m.time
        // lange nicht gezeichnet: nichts nachholen, sondern neu aufsetzen
        val stale = m.time.isNaN() || gap > 10f || gap < -2f
        val fresh = stale || state.pose == WhalePose.HIDDEN
        val dt = if (fresh) 0f else gap.coerceIn(0f, 3f)
        m.time = now

        // ---- sichtbare Bewegung
        if (fresh || entity.age != m.tick) {
            val sx = (entity.x - entity.lastX).toFloat()
            val sy = (entity.y - entity.lastY).toFloat()
            val sz = (entity.z - entity.lastZ).toFloat()
            // ein Sprung (Teleport) ist keine Bewegung
            if (sx * sx + sy * sy + sz * sz < 16f) {
                if (fresh || entity.age != m.tick + 1) {
                    m.lvx = sx; m.lvy = sy; m.lvz = sz
                    m.pvx = sx; m.pvy = sy; m.pvz = sz
                } else {
                    m.pvx = m.cvx; m.pvy = m.cvy; m.pvz = m.cvz
                }
                m.cvx = (sx + m.lvx) * 0.5f; m.cvy = (sy + m.lvy) * 0.5f; m.cvz = (sz + m.lvz) * 0.5f
                m.lvx = sx; m.lvy = sy; m.lvz = sz
            }
            m.tick = entity.age
        }
        val vx = MathHelper.lerp(tickDelta, m.pvx, m.cvx)
        val vy = MathHelper.lerp(tickDelta, m.pvy, m.cvy)
        val vz = MathHelper.lerp(tickDelta, m.pvz, m.cvz)
        val flat = sqrt(vx * vx + vz * vz)
        val speed = sqrt(flat * flat + vy * vy)
        if (flat > 0.02f) m.pathYaw = Math.toDegrees(atan2(-vx.toDouble(), vz.toDouble())).toFloat()
        if (speed > 0.02f) m.pathPitch = (-Math.toDegrees(atan2(vy.toDouble(), flat.toDouble())).toFloat()).coerceIn(MAX_CLIMB, MAX_DIVE)

        // ---- Ausrichtung, Biegung, Schräglage, Kopfvorhalt und Schlagwelle: genau die Werte, aus denen auch die Hitboxen
        // gerechnet werden (LeviathanBody.Motion, deterministisch aus der Lage der letzten Ticks; Schlagphase vom Server).
        // So liegt das Bild auch in Kurven, im Steigflug und in jeder Haltung auf den Boxen (und umgekehrt).
        val entityYaw = entity.getLerpedYaw(tickDelta)
        val entityPitch = entity.getLerpedPitch(tickDelta)
        val mo = entity.renderMotion(tickDelta)
        val rootYaw = entityYaw
        state.bodyYaw = MathHelper.wrapDegrees(rootYaw)
        state.pitchRad = if (LeviathanBody.isBeachedPose(state.pose)) 0f else entityPitch * RAD
        for (i in 0 until 6) {
            state.bendYaw[i] = mo.bendYaw[i]
            state.bendPitch[i] = mo.bendPitch[i]
        }
        state.leadYaw = mo.leadYaw
        state.leadPitch = mo.leadPitch
        state.bank = mo.bank
        state.climb = mo.climb
        state.finLag = mo.finLag
        state.swimPhase = mo.swimPhase
        m.speed = speed

        // ---- Harpunenseile: die Schwerkraft (Welt unten) im Rumpf des letzten Bildes, dazu der Fahrtwind, der die
        // Seile nach hinten weht. Das obere Stück folgt zügig, die unteren schwingen träge nach.
        ropes(m, fresh, dt, vx, vy, vz, rootYaw)
        state.bodyRot = m.bodyRot
        m.ropeA.copyInto(state.ropeA)
        m.ropeB.copyInto(state.ropeB)

        // ---- Haltungswechsel: aus dem überblenden, was zuletzt zu sehen war (auch mitten in einer Überblendung)
        if (state.pose != m.pose) {
            m.fromValid = m.shownValid && !stale
            if (m.fromValid) m.shown.copyInto(m.from)
            m.pose = state.pose
        }
        m.shownValid = state.pose != WhalePose.HIDDEN
        state.rigFrom = if (m.fromValid) m.from else null
        state.rigOut = m.shown
    }

    private val ropeQ = Quaternionf()
    private val ropeV = Vector3f()

    /**
     * Hängerichtung der Harpunenseile im Rumpf. Ziel = Welt-unten plus Fahrtwind (gegen die Flugrichtung), gedreht
     * in den Rumpf des letzten Bildes. Zwei gefederte Pendel: das obere Seilstück ([Memory.ropeA]) schwingt zügig,
     * die unteren Glieder ([Memory.ropeB]) hängen träge nach und schwingen über.
     */
    private fun ropes(m: Memory, fresh: Boolean, dt: Float, vx: Float, vy: Float, vz: Float, yawDeg: Float) {
        val y = yawDeg * RAD
        // Welt → Modell (vor der Neigung): vorn = -Z, rechts = -X, oben = -Y
        val fwd = -vx * sin(y) + vz * cos(y)
        val right = -vx * cos(y) - vz * sin(y)
        val wind = 1.3f
        ropeV.set(right * wind, 1f + vy * wind, fwd * wind)
        ropeQ.rotationX(m.bodyRot[0]).mul(Quaternionf().rotationZYX(m.bodyRot[2], m.bodyRot[1], 0f)).conjugate()
        ropeQ.transform(ropeV)
        ropeV.normalize()
        if (fresh) {
            for (i in 0 until 3) {
                m.ropeA[i] = ropeV[i]; m.ropeB[i] = ropeV[i]; m.ropeVa[i] = 0f; m.ropeVb[i] = 0f
            }
            return
        }
        // in kleinen Schritten, damit die Federn auch bei wenigen Bildern stabil bleiben
        var left = dt
        while (left > 0f) {
            val h = minOf(left, 0.5f)
            left -= h
            for (i in 0 until 3) {
                m.ropeVa[i] += ((ropeV[i] - m.ropeA[i]) * 0.09f - m.ropeVa[i] * 0.22f) * h
                m.ropeVb[i] += ((m.ropeA[i] - m.ropeB[i]) * 0.05f - m.ropeVb[i] * 0.14f) * h
            }
            for (i in 0 until 3) {
                m.ropeA[i] += m.ropeVa[i] * h
                m.ropeB[i] += m.ropeVb[i] * h
            }
        }
        normalize(m.ropeA)
        normalize(m.ropeB)
    }

    private fun normalize(v: FloatArray) {
        val l = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        if (l < 1e-4f) { v[0] = 0f; v[1] = 1f; v[2] = 0f; return }
        v[0] /= l; v[1] /= l; v[2] /= l
    }

    private fun ease(dt: Float, ticks: Float) = 1f - exp(-dt / ticks)

    private fun smooth(from: Float, to: Float, x: Float): Float {
        val k = ((x - from) / (to - from)).coerceIn(0f, 1f)
        return k * k * (3f - 2f * k)
    }

    /** Lässt Werte bis [free] unverändert durch und läuft darüber weich gegen [most] aus. */
    private fun limit(x: Float, free: Float, most: Float): Float {
        val a = abs(x)
        if (a <= free) return x
        val over = most - free
        return sign(x) * (free + over * tanh((a - free) / over))
    }

    override fun scale(state: WhaleRenderState, matrices: MatrixStack) {
        matrices.scale(SCALE, SCALE, SCALE)
    }

    /** Auch im dunklen End soll man ihn erkennen: Er hat immer etwas eigenes Licht. */
    override fun getBlockLight(entity: VoidBossEntity, pos: BlockPos): Int = maxOf(super.getBlockLight(entity, pos), 11)

    /** Kopf, Fluke und Flossen ragen weit über die Hitbox hinaus – sonst verschwindet er am Bildrand. */
    override fun getBoundingBox(entity: VoidBossEntity): Box = entity.boundingBox.expand(36.0, 34.0, 36.0)

    companion object {
        /** Leviathan: ≈ 58 Blöcke lang (8 Modelleinheiten = 1 Block bei Skalierung 1). */
        const val SCALE = LeviathanBody.RENDER_SCALE
        private const val RAD = (PI / 180.0).toFloat()

        /** Steiler als der Server ihn neigt, zeigt auch das Bild ihn nicht (Grad; negativ = Nase hoch). */
        private const val MAX_CLIMB = -28f
        private const val MAX_DIVE = 55f

        /** Abstand der Gelenke Bauch, Hüfte, Schwanz 1-3, Fluke zum jeweils vorderen, in Blöcken. */
        private val SEGMENT_LENGTH = floatArrayOf(3.6f, 5.4f, 4.8f, 4.2f, 3.6f, 3.6f)
        private val TEXTURE: Identifier = Identifier.of("hha_void", "textures/entity/void_boss.png")
        private val TEXTURE_P3: Identifier = Identifier.of("hha_void", "textures/entity/void_boss_p3.png")
        private val GLOW: RenderLayer = RenderLayers.eyes(Identifier.of("hha_void", "textures/entity/void_boss_glow.png"))
        private val GLOW_P3: RenderLayer = RenderLayers.eyes(Identifier.of("hha_void", "textures/entity/void_boss_p3_glow.png"))
    }
}

class StarlingRenderer(context: EntityRendererFactory.Context) :
    MobEntityRenderer<VoidDebtorEntity, StarlingRenderState, StarlingModel>(context, StarlingModel(context.getPart(STARLING_LAYER)), 0.3f) {

    private class Memory {
        var time = Float.NaN
        var dive = 0f
    }

    private val memory = WeakHashMap<VoidDebtorEntity, Memory>()

    init {
        addFeature(GlowFeature(this, { GLOW }, { s -> grey(0.85f + 0.15f * sin((s.age + s.seed) * 0.3f)) }))
    }

    override fun getTexture(state: StarlingRenderState): Identifier = TEXTURE

    override fun createRenderState() = StarlingRenderState()

    override fun updateRenderState(entity: VoidDebtorEntity, state: StarlingRenderState, tickDelta: Float) {
        super.updateRenderState(entity, state, tickDelta)
        val m = memory.getOrPut(entity) { Memory() }
        val now = entity.age + tickDelta
        val dt = if (m.time.isNaN()) 0f else (now - m.time).coerceIn(0f, 5f)
        m.time = now
        m.dive += ((if (entity.isCharging) 1f else 0f) - m.dive) * (1f - exp(-dt / 3f))
        state.dive = m.dive
        // jeder flattert in seinem eigenen Takt
        state.seed = (entity.id % 64) * 1.7f
    }

    override fun scale(state: StarlingRenderState, matrices: MatrixStack) {
        matrices.scale(0.8f, 0.8f, 0.8f)
    }

    /** Sie leuchten von selbst. */
    override fun getBlockLight(entity: VoidDebtorEntity, pos: BlockPos): Int = 15

    private companion object {
        val TEXTURE: Identifier = Identifier.of("hha_void", "textures/entity/void_debtor.png")
        val GLOW: RenderLayer = RenderLayers.eyes(Identifier.of("hha_void", "textures/entity/void_debtor_glow.png"))
    }
}

val WHALE_LAYER = EntityModelLayer(Identifier.of("hha_void", "void_boss"), "main")
val STARLING_LAYER = EntityModelLayer(Identifier.of("hha_void", "void_debtor"), "main")

object VoidBossClient {
    fun init() {
        EntityModelLayerRegistry.registerModelLayer(WHALE_LAYER) { WhaleModelData.create() }
        EntityModelLayerRegistry.registerModelLayer(STARLING_LAYER) { StarlingModelData.create() }
        EntityRendererRegistry.register(VoidBoss.BOSS) { WhaleRenderer(it) }
        EntityRendererRegistry.register(VoidBoss.DEBTOR) { StarlingRenderer(it) }
        // Hitbox-Segmente zeichnen nichts selbst – ohne Renderer stürzt der Client ab; Kerne: CoreRenderer (LeviathanClient.kt)
        EntityRendererRegistry.register(VoidBoss.PART) { net.minecraft.client.render.entity.EmptyEntityRenderer(it) }
        EntityModelLayerRegistry.registerModelLayer(CORE_LAYER) { CoreModelData.create() }
        EntityRendererRegistry.register(VoidBoss.CORE) { CoreRenderer(it) }
        LeviathanClient.init()
        net.fabricmc.fabric.api.client.rendering.v1.BlockRenderLayerMap.putBlocks(
            net.minecraft.client.render.BlockRenderLayer.TRANSLUCENT,
            dev.henny.hha_void.boss.LeviathanBlocks.MEMBRANE, dev.henny.hha_void.boss.LeviathanBlocks.STAR_LIFT, dev.henny.hha_void.boss.LeviathanBlocks.ACID, dev.henny.hha_void.boss.LeviathanBlocks.LUNG,
        )
    }
}
