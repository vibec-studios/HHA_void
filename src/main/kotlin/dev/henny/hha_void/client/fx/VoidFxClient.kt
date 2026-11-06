package dev.henny.hha_void.client.fx

import dev.henny.hha_void.VoidFxKind
import dev.henny.hha_void.VoidFxPayload
import dev.henny.hha_void.VoidItems
import dev.henny.hha.config.HhaConfig
import dev.henny.hha_void.boss.VoidBossEntity
import dev.henny.hha_void.logic.VoidShards
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents
import net.minecraft.client.MinecraftClient
import net.minecraft.client.render.VertexConsumerProvider
import net.minecraft.client.util.BufferAllocator
import net.minecraft.util.math.Vec3d
import org.joml.Matrix4f
import org.joml.Vector3f
import org.slf4j.LoggerFactory
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Verwaltet alle laufenden 3D-Effekte: Pakete annehmen, ticken, in drei Phasen zeichnen. */
object VoidFxClient {

    private val LOGGER = LoggerFactory.getLogger("hha_void/fx")

    private val effects = ArrayList<VoidEffect>()
    private val holes = HashMap<Int, BlackHoleEffect>()
    private val shards = HashMap<Int, ShardEffect>()
    private val meteors = HashSet<Int>()
    private val skies = HashSet<Int>()
    private val incoming = ArrayList<VoidEffect>()

    /** Spieler mit geworfenen Splittern; alle anderen haben den vollen Ring. */
    private val ringMasks = HashMap<Int, Int>()

    private var provider: VertexConsumerProvider.Immediate? = null
    private var warned = false

    /** Aktuelle Erschütterung der Kamera. */
    private var shakeStrength = 0f

    fun init() {
        VoidPipelines.init()

        ClientPlayNetworking.registerGlobalReceiver(VoidFxPayload.ID) { payload, _ -> handle(payload) }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            clear()
            VoidCutscene.stop()
        }
        ClientTickEvents.END_CLIENT_TICK.register { client -> tick(client) }

        WorldRenderEvents.AFTER_ENTITIES.register { _ -> renderSolid() }
        WorldRenderEvents.END_MAIN.register { _ -> renderMain() }
    }

    fun handle(p: VoidFxPayload) {
        when (p.kind) {
            VoidFxKind.BLACK_HOLE -> {
                val hole = BlackHoleEffect(p.a, p.b, p.x.toInt())
                holes[p.id] = hole
                incoming += hole
                shake(p.a, 0.7f, 64.0)
            }
            VoidFxKind.HOLE_TETHER -> holes[p.id]?.addTether(p.id2)
            VoidFxKind.HOLE_END -> holes[p.id]?.closeSoon()
            VoidFxKind.METEOR -> {
                meteors += p.id
                incoming += MeteorEffect(p.id, p.x > 0.5f, p.a) { pos, big ->
                    incoming += BurstEffect(pos, if (big) 1.6f else 0.7f)
                    incoming += ImpactEffect(pos, if (big) 2.2f else 1.1f)
                    shake(pos, if (big) 0.3f else 0.1f, 24.0)
                }
            }
            VoidFxKind.COMET -> incoming += CometEffect(p.a, p.x.toInt())
            VoidFxKind.COMET_IMPACT -> {
                incoming += CometImpactEffect(p.a, p.x)
                shake(p.a, 1f, 40.0)
            }
            VoidFxKind.SHARD -> {
                val shard = shards[p.id]
                if (shard == null) {
                    val created = ShardEffect(p.a, p.id)
                    created.update(p.a, p.b)
                    shards[p.id] = created
                    incoming += created
                } else {
                    shard.update(p.a, p.b)
                }
            }
            VoidFxKind.SHARD_END -> shards.remove(p.id)?.end(p.a)
            VoidFxKind.SHARD_RING -> if (p.id2 == VoidShards.ALL_READY) ringMasks.remove(p.id) else ringMasks[p.id] = p.id2
            VoidFxKind.PORTAL -> incoming += PortalEffect(p.a, p.b, p.x)
            VoidFxKind.STREAK -> incoming += StreakEffect(p.a, p.b)
            VoidFxKind.VORTEX -> {
                incoming += VortexEffect(p.a)
                shake(p.a, 0.25f, 24.0)
            }
            VoidFxKind.SHOCKWAVE -> {
                incoming += ShockwaveEffect(p.a, p.b, p.x)
                shake(p.a, 0.5f, 28.0)
            }
            VoidFxKind.ARC -> incoming += ArcEffect(p.a, p.b, p.c, p.id, p.x.toInt().coerceAtLeast(1))
            VoidFxKind.IMPACT -> incoming += ImpactEffect(p.a, p.x * 0.7f)
            VoidFxKind.BURST -> incoming += BurstEffect(p.a, p.x * 0.6f)
            VoidFxKind.PILLAR -> incoming += PillarEffect(p.a, p.x.toInt(), p.y, p.id == 1)
            VoidFxKind.CHAIN -> incoming += ChainEffect(p.id, p.id2, p.x.toInt())
            VoidFxKind.BEAM -> incoming += BeamEffect(p.a, p.id, p.b.x, p.b.y, p.x, p.b.z.toInt(), p.id2)
            VoidFxKind.CHARGE -> incoming += ChargeEffect(p.a, p.x.toInt())
            VoidFxKind.STOMP -> {
                shake(p.a, p.x, 36.0)
                if (p.x >= 0.5f) incoming += ImpactEffect(p.a.add(0.0, 0.1, 0.0), 3.4f)
            }
            VoidFxKind.SKY_STRIKE -> {
                incoming += SkyStrikeEffect(p.a, p.x.toInt())
                pulseSky()
            }
            VoidFxKind.RIFT -> incoming += RiftEffect(p.a, p.b, p.x, p.y.toInt(), p.id)
            VoidFxKind.GROUND_CRACK -> incoming += GroundCrackEffect(p.a, p.b, p.id2, p.x.toInt(), p.id)
            VoidFxKind.RIFT_FIST -> incoming += RiftFistEffect(p.a, p.x.toInt(), p.y)
            VoidFxKind.RIFT_ROCK -> {
                incoming += RiftRockEffect(p.a, p.x.toInt())
                pulseSky()
            }
            VoidFxKind.SKY_BEAMS -> incoming += SkyBeamsEffect(p.a, p.x.toInt())
            VoidFxKind.CUTSCENE -> VoidCutscene.start(p.id, p.id2, p.a, p.x)
            VoidFxKind.NOVA -> {
                incoming += NovaEffect(p.a, p.x)
                shake(p.a, (p.x * 0.08f).coerceAtMost(1f), 40.0)
            }
        }
    }

    private fun pulseSky() {
        for (effect in effects) (effect as? SkyRiftEffect)?.pulse()
    }

    /** Effekt, den ein anderer Effekt erzeugt; startet im nächsten Tick. */
    fun spawn(effect: VoidEffect) {
        incoming += effect
    }

    /** Für das Mixin: das Vanilla-Sprite dieser Feuerbälle ersetzt ein 3D-Modell. */
    @JvmStatic
    fun isHiddenEntity(id: Int): Boolean = meteors.contains(id)

    fun shake(at: Vec3d, strength: Float, range: Double) {
        val camera = MinecraftClient.getInstance().gameRenderer?.camera ?: return
        val distance = camera.cameraPos.distanceTo(at)
        if (distance >= range) return
        val falloff = (1.0 - distance / range).toFloat()
        shakeStrength = maxOf(shakeStrength, strength * falloff * 0.45f)
    }

    /** Für das Mixin: Kamera-Versatz in Grad, -1..1 pro Achse mal Stärke. */
    @JvmStatic
    fun shakeYaw(tickProgress: Float): Float = wave(tickProgress, 1.9f, 3.1f) * shakeStrength

    @JvmStatic
    fun shakePitch(tickProgress: Float): Float = wave(tickProgress, 2.3f, 2.7f) * shakeStrength

    private var clock = 0f

    private fun wave(tickProgress: Float, a: Float, b: Float): Float {
        val t = clock + tickProgress
        return sin(t * a) * 0.6f + sin(t * b + 1.3f) * 0.4f
    }

    private fun tick(client: MinecraftClient) {
        val world = client.world
        if (world == null) {
            if (effects.isNotEmpty() || incoming.isNotEmpty()) clear()
            return
        }
        if (client.isPaused) return
        clock += 1f
        VoidCutscene.tick(client)
        shakeStrength *= 0.86f
        if (shakeStrength < 0.01f) shakeStrength = 0f

        syncSky(client)
        effects.addAll(incoming)
        incoming.clear()
        val iterator = effects.iterator()
        while (iterator.hasNext()) {
            val effect = iterator.next()
            guarded { effect.tick(world) }
            if (effect.finished) {
                iterator.remove()
                when (effect) {
                    is BlackHoleEffect -> {
                        holes.values.remove(effect)
                        incoming += NovaEffect(effect.center, 7f)
                        shake(effect.center, 0.9f, 40.0)
                    }
                    is ShardEffect -> shards.values.remove(effect)
                    is MeteorEffect -> meteors.remove(effect.entityId)
                }
            }
        }
    }

    private fun clear() {
        effects.clear()
        incoming.clear()
        holes.clear()
        shards.clear()
        meteors.clear()
        skies.clear()
        ringMasks.clear()
        shakeStrength = 0f
    }

    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            if (!warned) {
                warned = true
                LOGGER.error("3D-Effekt fehlgeschlagen; weitere Meldungen werden unterdrückt", e)
            }
        }
    }

    private fun context(client: MinecraftClient): FxContext {
        val immediate = provider ?: VertexConsumerProvider.immediate(BufferAllocator(1 shl 21)).also { provider = it }
        val camera = client.gameRenderer.camera
        return FxContext(camera.cameraPos, camera.rotation, client.renderTickCounter.getTickProgress(false), immediate)
    }

    private fun renderSolid() {
        val client = MinecraftClient.getInstance()
        val c = context(client)
        for (effect in effects) guarded { effect.solid(c) }
        guarded { renderBossCrystals(client, c) }
        guarded { renderShardRings(client, c) }
        guarded { c.provider.draw() }
    }

    private fun renderMain() {
        val client = MinecraftClient.getInstance()
        val c = context(client)

        for (effect in effects) guarded { effect.before(c) }
        guarded { renderBossHalo(client, c) }
        guarded { renderShardGlow(client, c) }
        guarded { c.provider.draw() }

        for (hole in holes.values) guarded { VoidLens.render(client.framebuffer, hole, c) }

        for (effect in effects) guarded { effect.after(c) }
        guarded { c.provider.draw() }
    }

    // ---------- Schwert: die sechs kreisenden Splitter ----------

    private inline fun forEachRingShard(client: MinecraftClient, c: FxContext, action: (Vector3f, Int, Float) -> Unit) {
        val world = client.world ?: return
        if (!HhaConfig.enabled("hha_void.shards")) return
        val time = (world.time % 24000L).toFloat() + c.tickDelta
        for (player in world.players) {
            if (player.isInvisible || player.isSpectator) continue
            if (player.squaredDistanceTo(c.cam) > 64.0 * 64.0) continue
            if (!player.mainHandStack.isOf(VoidItems.VOID_SWORD) && !player.offHandStack.isOf(VoidItems.VOID_SWORD)) continue
            val mask = ringMasks[player.id] ?: VoidShards.ALL_READY
            val body = c.rel(player.getLerpedPos(c.tickDelta))
            for (i in 0 until VoidShards.COUNT) {
                if (mask and (1 shl i) == 0) continue
                action(Vector3f(body).add(VoidShards.orbitX(i, time), VoidShards.ORBIT_HEIGHT, VoidShards.orbitZ(i, time)), i, time)
            }
        }
    }

    private fun renderShardRings(client: MinecraftClient, c: FxContext) {
        forEachRingShard(client, c) { at, i, time ->
            // Spitze in Flugrichtung; die flache Klinge dreht sich langsam um sich selbst
            val angle = VoidShards.orbitAngle(i, time)
            val heading = Vector3f(-sin(angle), 0f, cos(angle))
            val matrix = Matrix4f().translate(at).rotate(VoidDraw.alignY(heading)).rotateY(time * 0.12f + i).scale(0.26f, 0.72f, 0.26f)
            VoidDraw.mesh(c, VoidPipelines.SOLID_LAYER, VoidMeshes.SHARD_BLADES[i % VoidMeshes.SHARD_BLADES.size], matrix, 0, 2, i)
        }
    }

    private fun renderShardGlow(client: MinecraftClient, c: FxContext) {
        forEachRingShard(client, c) { at, i, time ->
            VoidDraw.glow(c, at, 0.2f, Palette.alpha(Palette.MAGENTA, 0.26f), i)
            // Kurze Leuchtspur auf der Kreisbahn
            val trail = List(6) { k ->
                val back = time - 2.2f - k * 1.5f
                Vector3f(at).add(
                    VoidShards.orbitX(i, back) - VoidShards.orbitX(i, time),
                    0f,
                    VoidShards.orbitZ(i, back) - VoidShards.orbitZ(i, time),
                )
            }
            VoidDraw.ribbon(c, trail, width = { 0.02f }, color = { Palette.alpha(Palette.MAGENTA, (1f - it) * 0.55f) }, seed = i)
        }
    }

    // ---------- Vael: Lichtkranz und kreisende Akten-Kristalle ----------

    private inline fun forEachBoss(client: MinecraftClient, action: (VoidBossEntity) -> Unit) {
        val world = client.world ?: return
        for (entity in world.entities) {
            if (entity is VoidBossEntity && entity.deathTime < 40) action(entity)
        }
    }

    private fun renderBossCrystals(client: MinecraftClient, c: FxContext) {
        forEachBoss(client) { boss ->
            if (boss.bossPhase() < 4) return@forEachBoss
            val time = boss.age + c.tickDelta
            val body = c.rel(boss.getLerpedPos(c.tickDelta))
            for (i in 0 until 5) {
                val angle = time * 0.05f * (if (i % 2 == 0) 1f else -0.7f) + i * ((PI * 2.0).toFloat() / 5f)
                val radius = 3.6f + (i % 2) * 0.8f
                val at = Vector3f(body).add(cos(angle) * radius, 3f + sin(time * 0.05f + i) * 1.4f, sin(angle) * radius)
                val matrix = Matrix4f().translate(at).rotateY(time * 0.12f + i).scale(0.5f, 1.2f, 0.5f)
                VoidDraw.mesh(c, VoidPipelines.SOLID_LAYER, VoidMeshes.CRYSTALS[i % VoidMeshes.CRYSTALS.size], matrix, Palette.alpha(Palette.MAGENTA, 1f), 1, i)
            }
        }
    }

    /** Letzte Phase: Risse zucken durch die Luft um seinen Körper. */
    private fun renderBossHalo(client: MinecraftClient, c: FxContext) {
        forEachBoss(client) { boss ->
            if (boss.bossPhase() < 4) return@forEachBoss
            val time = boss.age + c.tickDelta
            val body = c.rel(boss.getLerpedPos(c.tickDelta))
            val tick = (time / 2f).toInt()
            for (arm in 0 until 4) {
                val rnd = kotlin.random.Random(arm * 97 + tick * 13)
                val base = rnd.nextFloat() * (PI * 2.0).toFloat()
                val points = List(7) { i ->
                    val k = i / 6f
                    val radius = 1.6f + k * 2.6f
                    Vector3f(body).add(
                        cos(base + (rnd.nextFloat() - 0.5f) * 0.5f) * radius,
                        1.5f + arm * 1.1f + (rnd.nextFloat() - 0.5f) * 1.4f,
                        sin(base + (rnd.nextFloat() - 0.5f) * 0.5f) * radius,
                    )
                }
                VoidDraw.ribbon(c, points, width = { 0.09f * (1f - it * 0.7f) }, color = { Palette.alpha(Palette.MAGENTA, 0.9f) }, seed = arm)
            }
        }
    }

    /** Sobald ein Boss in Phase 4 ist, gehört der Himmel ihm: auch für später dazukommende Spieler. */
    private fun syncSky(client: MinecraftClient) {
        forEachBoss(client) { boss ->
            if (boss.bossPhase() >= 2 && !boss.isDead && skies.add(boss.id)) {
                incoming += SkyRiftEffect(boss.id) { skies.remove(boss.id) }
            }
        }
    }
}
