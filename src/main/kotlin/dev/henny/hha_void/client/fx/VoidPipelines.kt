package dev.henny.hha_void.client.fx

import com.mojang.blaze3d.pipeline.BlendFunction
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.platform.DepthTestFunction
import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.client.gl.RenderPipelines
import net.minecraft.client.gl.UniformType
import net.minecraft.client.render.RenderLayer
import net.minecraft.client.render.RenderSetup
import net.minecraft.client.render.VertexFormats
import net.minecraft.util.Identifier

/** Eigene Render-Pipelines: Energie, Fels/Kristall und die Gravitationslinse. */
object VoidPipelines {

    private fun id(path: String) = Identifier.of("hha_void", path)

    private val ENERGY: RenderPipeline = RenderPipelines.register(
        RenderPipeline.builder(RenderPipelines.TRANSFORMS_PROJECTION_FOG_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
            .withLocation(id("pipeline/energy"))
            .withVertexShader(id("core/void_energy"))
            .withFragmentShader(id("core/void_energy"))
            .withBlend(BlendFunction.TRANSLUCENT)
            .withCull(false)
            .withDepthWrite(false)
            .withVertexFormat(VertexFormats.POSITION_COLOR_TEXTURE_LIGHT_NORMAL, VertexFormat.DrawMode.QUADS)
            .build()
    )

    private val SOLID: RenderPipeline = RenderPipelines.register(
        RenderPipeline.builder(RenderPipelines.TRANSFORMS_PROJECTION_FOG_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
            .withLocation(id("pipeline/solid"))
            .withVertexShader(id("core/void_solid"))
            .withFragmentShader(id("core/void_solid"))
            .withVertexFormat(VertexFormats.POSITION_COLOR_TEXTURE_LIGHT_NORMAL, VertexFormat.DrawMode.QUADS)
            .build()
    )

    val LENS: RenderPipeline = RenderPipelines.register(
        RenderPipeline.builder(RenderPipelines.TRANSFORMS_AND_PROJECTION_SNIPPET)
            .withLocation(id("pipeline/lens"))
            .withVertexShader(id("core/void_lens"))
            .withFragmentShader(id("core/void_lens"))
            .withSampler("SceneSampler")
            .withSampler("DepthSampler")
            .withUniform("VoidHole", UniformType.UNIFORM_BUFFER)
            .withoutBlend()
            .withDepthWrite(false)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withColorWrite(true, false)
            .withVertexFormat(VertexFormats.EMPTY, VertexFormat.DrawMode.TRIANGLES)
            .build()
    )

    val ENERGY_LAYER: RenderLayer = RenderLayer.of("hha_void_energy", RenderSetup.builder(ENERGY).build())
    val SOLID_LAYER: RenderLayer = RenderLayer.of("hha_void_solid", RenderSetup.builder(SOLID).build())

    /** Erzwingt die Registrierung vor dem ersten Shader-Reload. */
    fun init() = Unit
}
