// GENERIERT von tools/textures/GenWhale.java - nicht von Hand ändern
package dev.henny.hha_void.client.boss

import net.minecraft.client.model.Dilation
import net.minecraft.client.model.ModelData
import net.minecraft.client.model.ModelPart
import net.minecraft.client.model.ModelPartBuilder
import net.minecraft.client.model.ModelTransform
import net.minecraft.client.model.TexturedModelData

/**
 * Sternling (kleiner Sternrochen): 16 Einheiten = 1 Block, +Y unten, Blick nach -Z.
 */
object StarlingModelData {
    private const val S = 1f

    fun create(): TexturedModelData {
        val data = ModelData()
        val root = data.root
        val p_ray = root.addChild(
            "ray",
            ModelPartBuilder.create()
                .uv(0, 0).cuboid(-3f, -1.5f, -5f, 6f, 3f, 10f, Dilation.NONE, S, S)
                .uv(24, 24).cuboid(-2f, -2.5f, -3f, 4f, 1f, 6f, Dilation.NONE, S, S)
                .uv(44, 33).cuboid(-3f, -0.5f, -8f, 2f, 2f, 3f, Dilation.NONE, S, S)
                .uv(54, 33).cuboid(1f, -0.5f, -8f, 2f, 2f, 3f, Dilation.NONE, S, S),
            ModelTransform.of(0f, 19.5f, 0f, 0f, 0f, 0f),
        )
        val p_wing_r = p_ray.addChild(
            "wing_r",
            ModelPartBuilder.create()
                .uv(32, 0).cuboid(-6f, -0.5f, -5f, 6f, 1f, 10f, Dilation.NONE, S, S)
                .uv(32, 13).cuboid(-10f, -0.5f, -4f, 4f, 1f, 8f, Dilation.NONE, S, S),
            ModelTransform.of(-3f, 0f, 0f, 0f, 0f, 0f),
        )
        val p_wing_r2 = p_wing_r.addChild(
            "wing_r2",
            ModelPartBuilder.create()
                .uv(44, 24).cuboid(-4f, -0.5f, -3f, 4f, 1f, 6f, Dilation.NONE, S, S)
                .uv(0, 40).cuboid(-7f, -0.5f, -1f, 3f, 1f, 4f, Dilation.NONE, S, S)
                .uv(46, 40).cuboid(-9f, -0.5f, 1f, 2f, 1f, 2f, Dilation.NONE, S, S),
            ModelTransform.of(-10f, 0f, 0f, 0f, 0f, 0f),
        )
        val p_wing_l = p_ray.addChild(
            "wing_l",
            ModelPartBuilder.create()
                .uv(0, 13).cuboid(0f, -0.5f, -5f, 6f, 1f, 10f, Dilation.NONE, S, S)
                .uv(0, 24).cuboid(6f, -0.5f, -4f, 4f, 1f, 8f, Dilation.NONE, S, S),
            ModelTransform.of(3f, 0f, 0f, 0f, 0f, 0f),
        )
        val p_wing_l2 = p_wing_l.addChild(
            "wing_l2",
            ModelPartBuilder.create()
                .uv(0, 33).cuboid(0f, -0.5f, -3f, 4f, 1f, 6f, Dilation.NONE, S, S)
                .uv(14, 40).cuboid(4f, -0.5f, -1f, 3f, 1f, 4f, Dilation.NONE, S, S)
                .uv(54, 40).cuboid(7f, -0.5f, 1f, 2f, 1f, 2f, Dilation.NONE, S, S),
            ModelTransform.of(10f, 0f, 0f, 0f, 0f, 0f),
        )
        val p_tail1 = p_ray.addChild(
            "tail1",
            ModelPartBuilder.create()
                .uv(20, 33).cuboid(-0.5f, -0.5f, 0f, 1f, 1f, 5f, Dilation.NONE, S, S),
            ModelTransform.of(0f, 0f, 5f, 0f, 0f, 0f),
        )
        val p_tail2 = p_tail1.addChild(
            "tail2",
            ModelPartBuilder.create()
                .uv(32, 33).cuboid(-0.5f, -0.5f, 0f, 1f, 1f, 5f, Dilation.NONE, S, S),
            ModelTransform.of(0f, 0f, 5f, 0f, 0f, 0f),
        )
        val p_tail3 = p_tail2.addChild(
            "tail3",
            ModelPartBuilder.create()
                .uv(28, 40).cuboid(-0.5f, -0.5f, 0f, 1f, 1f, 4f, Dilation.NONE, S, S)
                .uv(38, 40).cuboid(-1f, -1f, 4f, 2f, 2f, 2f, Dilation.NONE, S, S),
            ModelTransform.of(0f, 0f, 5f, 0f, 0f, 0f),
        )
        return TexturedModelData.of(data, 64, 64)
    }

    /** Weg jedes Teils von der Wurzel aus. */
    private val PATHS = mapOf(
        "ray" to "ray",
        "wing_r" to "ray/wing_r",
        "wing_r2" to "ray/wing_r/wing_r2",
        "wing_l" to "ray/wing_l",
        "wing_l2" to "ray/wing_l/wing_l2",
        "tail1" to "ray/tail1",
        "tail2" to "ray/tail1/tail2",
        "tail3" to "ray/tail1/tail2/tail3",
    )

    fun part(root: ModelPart, name: String): ModelPart =
        PATHS.getValue(name).split('/').fold(root) { p, n -> p.getChild(n) }
}
