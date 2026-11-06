package dev.henny.hha_void.client.fx

import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.buffers.Std140Builder
import com.mojang.blaze3d.buffers.Std140SizeCalculator
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import com.mojang.blaze3d.textures.GpuTexture
import com.mojang.blaze3d.textures.GpuTextureView
import com.mojang.blaze3d.textures.TextureFormat
import net.minecraft.client.gl.Framebuffer
import org.joml.Matrix4f
import org.joml.Vector3f
import org.joml.Vector4f
import org.lwjgl.system.MemoryStack
import java.util.OptionalInt

/**
 * Vollbild-Pass der Gravitationslinse: kopiert das fertige Weltbild in eine eigene
 * Textur und raymarcht darüber das schwarze Loch (siehe `void_lens.fsh`).
 */
object VoidLens {

    private val UNIFORM_SIZE = Std140SizeCalculator().putVec4().putVec4().putVec4().get()

    private var copy: GpuTexture? = null
    private var copyView: GpuTextureView? = null
    private var uniforms: GpuBuffer? = null

    private fun ensureResources(width: Int, height: Int) {
        val device = RenderSystem.getDevice()
        val existing = copy
        if (existing == null || existing.getWidth(0) != width || existing.getHeight(0) != height) {
            copyView?.close()
            existing?.close()
            val usage = GpuTexture.USAGE_COPY_DST or GpuTexture.USAGE_TEXTURE_BINDING
            val texture = device.createTexture({ "hha_void lens scene" }, usage, TextureFormat.RGBA8, width, height, 1, 1)
            copy = texture
            copyView = device.createTextureView(texture)
        }
        if (uniforms == null) {
            uniforms = device.createBuffer({ "hha_void lens uniforms" }, GpuBuffer.USAGE_UNIFORM or GpuBuffer.USAGE_COPY_DST, UNIFORM_SIZE.toLong())
        }
    }

    fun render(framebuffer: Framebuffer, hole: BlackHoleEffect, c: FxContext) {
        val colorTexture = framebuffer.colorAttachment ?: return
        val colorView = framebuffer.colorAttachmentView ?: return
        val depthView = framebuffer.depthAttachmentView ?: return
        val horizon = hole.horizon(c.tickDelta)
        if (horizon <= 0.01f) return

        ensureResources(colorTexture.getWidth(0), colorTexture.getHeight(0))
        val scene = copy ?: return
        val sceneView = copyView ?: return
        val buffer = uniforms ?: return

        val encoder = RenderSystem.getDevice().createCommandEncoder()
        encoder.copyTextureToTexture(colorTexture, scene, 0, 0, 0, 0, 0, scene.getWidth(0), scene.getHeight(0))

        val center: Vector3f = c.rel(hole.center)
        val normal = hole.diskNormal(c.tickDelta)
        MemoryStack.stackPush().use { stack ->
            val data = Std140Builder.onStack(stack, UNIFORM_SIZE)
                .putVec4(center.x, center.y, center.z, horizon)
                .putVec4(normal.x, normal.y, normal.z, hole.diskOuter(c.tickDelta))
                .putVec4(hole.diskTime(c.tickDelta), hole.intensity(c.tickDelta), hole.flash(c.tickDelta), 0f)
                .get()
            encoder.writeToBuffer(buffer.slice(), data)
        }

        val transforms = RenderSystem.getDynamicUniforms()
            .write(RenderSystem.getModelViewMatrix(), Vector4f(1f, 1f, 1f, 1f), Vector3f(), Matrix4f())
        val sampler = RenderSystem.getSamplerCache().get(FilterMode.LINEAR)
        val depthSampler = RenderSystem.getSamplerCache().get(FilterMode.NEAREST)

        encoder.createRenderPass({ "hha_void black hole" }, colorView, OptionalInt.empty()).use { pass ->
            pass.setPipeline(VoidPipelines.LENS)
            RenderSystem.bindDefaultUniforms(pass)
            pass.setUniform("DynamicTransforms", transforms)
            pass.setUniform("VoidHole", buffer)
            pass.bindTexture("SceneSampler", sceneView, sampler)
            pass.bindTexture("DepthSampler", depthView, depthSampler)
            pass.draw(0, 3)
        }
    }
}
