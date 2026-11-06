package dev.henny.hha_void.client.boss

import dev.henny.hha_void.boss.VoidBoss
import dev.henny.hha_void.boss.VoidBossEntity
import net.fabricmc.fabric.api.client.rendering.v1.EntityModelLayerRegistry
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry
import net.minecraft.client.render.RenderLayers
import net.minecraft.client.render.command.OrderedRenderCommandQueue
import net.minecraft.client.render.entity.EntityRendererFactory
import net.minecraft.client.render.entity.MobEntityRenderer
import net.minecraft.client.render.entity.feature.FeatureRenderer
import net.minecraft.client.render.entity.model.EntityModelLayer
import net.minecraft.client.render.entity.state.LivingEntityRenderState
import net.minecraft.client.render.OverlayTexture
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.entity.mob.MobEntity
import net.minecraft.util.Identifier

/** Haltungen aus Sicht des Modells; dieselben Werte setzt der Server beim jeweiligen Angriff. */
typealias VoidBossPoses = VoidBossEntity.Poses

class VoidBossRenderState : LivingEntityRenderState() {
    var pose = 0
    var poseAge = 100f
    var previousPose = 0
    var previousPoseAge = 100f
    var phase = 1
    var swing = 0f
    var flinch = 0f
}

/** Gemeinsamer Renderer: Vael selbst und, kleiner, seine Schuldner. */
class VoidBossRenderer<T : MobEntity>(
    context: EntityRendererFactory.Context,
    private val modelScale: Float,
    shadow: Float,
) : MobEntityRenderer<T, VoidBossRenderState, VoidBossModel>(context, VoidBossModel(context.getPart(LAYER)), shadow) {

    init {
        // Leuchtende Stellen; in der letzten Phase mit den Rissen im Körper
        addFeature(object : FeatureRenderer<VoidBossRenderState, VoidBossModel>(this) {
            override fun render(
                matrices: MatrixStack, queue: OrderedRenderCommandQueue, light: Int,
                state: VoidBossRenderState, limbAngle: Float, limbDistance: Float,
            ) {
                val layer = if (state.phase >= 4) GLOW_REBORN else GLOW
                queue.getBatchingQueue(1).submitModel(
                    contextModel, state, matrices, layer, light, OverlayTexture.DEFAULT_UV, -1, null, state.outlineColor, null,
                )
            }
        })
    }

    override fun getTexture(state: VoidBossRenderState): Identifier = if (state.phase >= 4) TEXTURE_REBORN else TEXTURE

    override fun createRenderState() = VoidBossRenderState()

    override fun updateRenderState(entity: T, state: VoidBossRenderState, tickDelta: Float) {
        super.updateRenderState(entity, state, tickDelta)
        state.swing = entity.getHandSwingProgress(tickDelta)
        state.flinch = ((entity.hurtTime - tickDelta) / 10f).coerceIn(0f, 1f)
        // Die lange Sterbeszene soll nicht komplett rot eingefärbt sein
        state.hurt = entity.hurtTime > 0 && entity.deathTime == 0
        if (entity is VoidBossEntity) {
            state.pose = entity.castPose()
            state.poseAge = entity.clientPoseAge(tickDelta)
            state.previousPose = entity.clientPreviousPose()
            state.previousPoseAge = entity.clientPreviousPoseAge(tickDelta)
            state.phase = entity.bossPhase()
        }
    }

    override fun scale(state: VoidBossRenderState, matrices: MatrixStack) {
        matrices.scale(modelScale, modelScale, modelScale)
    }

    /** Auch im dunklen End soll man den Koloss erkennen: Er hat immer etwas eigenes Licht. */
    override fun getBlockLight(entity: T, pos: net.minecraft.util.math.BlockPos): Int =
        maxOf(super.getBlockLight(entity, pos), 11)

    /** Vael fällt nicht um: Der Tod läuft als eigene Animation im Modell. */
    override fun getLyingPositionRotationDegrees() = 0f

    private companion object {
        val TEXTURE: Identifier = Identifier.of("hha_void", "textures/entity/void_boss.png")
        val TEXTURE_REBORN: Identifier = Identifier.of("hha_void", "textures/entity/void_boss_reborn.png")
        val GLOW = RenderLayers.eyes(Identifier.of("hha_void", "textures/entity/void_boss_glow.png"))
        val GLOW_REBORN = RenderLayers.eyes(Identifier.of("hha_void", "textures/entity/void_boss_reborn_glow.png"))
    }
}

val LAYER = EntityModelLayer(Identifier.of("hha_void", "void_boss"), "main")

object VoidBossClient {
    fun init() {
        EntityModelLayerRegistry.registerModelLayer(LAYER) { VoidBossModel.texturedModelData() }
        EntityRendererRegistry.register(VoidBoss.BOSS) { VoidBossRenderer(it, 1.95f, 2.4f) }
        EntityRendererRegistry.register(VoidBoss.DEBTOR) { VoidBossRenderer(it, 0.6f, 0.6f) }
    }
}
