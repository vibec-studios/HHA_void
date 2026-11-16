// GENERIERT von tools/textures/GenWhale.java - nicht von Hand ändern
package dev.henny.hha_void.client.boss

import net.minecraft.client.model.Dilation
import net.minecraft.client.model.ModelData
import net.minecraft.client.model.ModelPart
import net.minecraft.client.model.ModelPartBuilder
import net.minecraft.client.model.ModelTransform
import net.minecraft.client.model.TexturedModelData

/**
 * Sternenkern im Bauch des Leviathans: 16 Einheiten = 1 Block, Boden bei y = 24, +Y unten.
 * Kristallherz (heart/crystal), Fleischkapsel (petal_*), Sehnen (tendon*, strand*), kreisende Splitter (orbit).
 */
object CoreModelData {
    private const val S = 0.5f

    fun create(): TexturedModelData {
        val data = ModelData()
        val root = data.root
        val p_root = root.addChild(
            "root",
            ModelPartBuilder.create(),
            ModelTransform.of(0f, 24f, 0f, 0f, 0f, 0f),
        )
        val p_collar = p_root.addChild(
            "collar",
            ModelPartBuilder.create()
                .uv(28, 138).cuboid(-10f, -4f, -6f, 20f, 5f, 12f, Dilation.NONE, S, S)
                .uv(80, 64).cuboid(-6f, -4f, -10f, 12f, 5f, 20f, Dilation.NONE, S, S)
                .uv(144, 64).cuboid(-8f, -5f, -8f, 16f, 7f, 16f, Dilation.NONE, S, S)
                .uv(92, 138).cuboid(-7f, 2f, -4f, 14f, 8f, 8f, Dilation.NONE, S, S)
                .uv(56, 96).cuboid(-4f, 2f, -7f, 8f, 8f, 14f, Dilation.NONE, S, S)
                .uv(124, 96).cuboid(-6f, 2f, -6f, 12f, 8f, 12f, Dilation.NONE, S, S),
            ModelTransform.of(0f, -10f, 0f, 0f, 0f, 0f),
        )
        val p_heart = p_root.addChild(
            "heart",
            ModelPartBuilder.create(),
            ModelTransform.of(0f, -29f, 0f, 0f, 0f, 0f),
        )
        val p_crystal = p_heart.addChild(
            "crystal",
            ModelPartBuilder.create()
                .uv(24, 170).cuboid(-1f, -19f, -1f, 2f, 5f, 2f, Dilation.NONE, S, S)
                .uv(112, 156).cuboid(-3f, -14f, -3f, 6f, 5f, 6f, Dilation.NONE, S, S)
                .uv(136, 138).cuboid(-5f, -9f, -5f, 10f, 5f, 10f, Dilation.NONE, S, S)
                .uv(0, 96).cuboid(-7f, -4f, -7f, 14f, 9f, 14f, Dilation.NONE, S, S)
                .uv(176, 138).cuboid(-5f, 5f, -5f, 10f, 5f, 10f, Dilation.NONE, S, S)
                .uv(136, 156).cuboid(-3f, 10f, -3f, 6f, 4f, 6f, Dilation.NONE, S, S)
                .uv(32, 170).cuboid(-1f, 14f, -1f, 2f, 4f, 2f, Dilation.NONE, S, S),
            ModelTransform.of(0f, 0f, 0f, 0f, 0.7854f, 0f),
        )
        val p_flare = p_heart.addChild(
            "flare",
            ModelPartBuilder.create()
                .uv(64, 0).cuboid(-16f, 0f, -16f, 32f, 0f, 32f, Dilation.NONE, S, S)
                .uv(0, 64).cuboid(-16f, -16f, 0f, 32f, 32f, 0f, Dilation.NONE, S, S)
                .uv(0, 0).cuboid(0f, -16f, -16f, 0f, 32f, 32f, Dilation.NONE, S, S),
            ModelTransform.of(0f, 0f, 0f, 0f, 0f, 0f),
        )
        val p_petal_n = p_root.addChild(
            "petal_n",
            ModelPartBuilder.create(),
            ModelTransform.of(0f, -12f, 0f, 0f, 0f, 0f),
        )
        val p_petal_n_lo = p_petal_n.addChild(
            "petal_n_lo",
            ModelPartBuilder.create()
                .uv(114, 119).cuboid(-6f, -16f, -1f, 12f, 16f, 2f, Dilation.NONE, S, S)
                .uv(40, 170).cuboid(-4f, -20f, -1f, 8f, 4f, 2f, Dilation.NONE, S, S),
            ModelTransform.of(0f, 0f, -8f, -0.32f, 0f, 0f),
        )
        val p_petal_n_hi = p_petal_n_lo.addChild(
            "petal_n_hi",
            ModelPartBuilder.create()
                .uv(160, 156).cuboid(-3f, -8f, -1f, 6f, 8f, 2f, Dilation.NONE, S, S)
                .uv(120, 170).cuboid(-1f, -11f, -1f, 2f, 3f, 2f, Dilation.NONE, S, S),
            ModelTransform.of(0f, -20f, 0f, 0.55f, 0f, 0f),
        )
        val p_petal_e = p_root.addChild(
            "petal_e",
            ModelPartBuilder.create(),
            ModelTransform.of(0f, -12f, 0f, 0f, 1.5708f, 0f),
        )
        val p_petal_e_lo = p_petal_e.addChild(
            "petal_e_lo",
            ModelPartBuilder.create()
                .uv(142, 119).cuboid(-6f, -16f, -1f, 12f, 16f, 2f, Dilation.NONE, S, S)
                .uv(60, 170).cuboid(-4f, -20f, -1f, 8f, 4f, 2f, Dilation.NONE, S, S),
            ModelTransform.of(0f, 0f, -8f, -0.32f, 0f, 0f),
        )
        val p_petal_e_hi = p_petal_e_lo.addChild(
            "petal_e_hi",
            ModelPartBuilder.create()
                .uv(176, 156).cuboid(-3f, -8f, -1f, 6f, 8f, 2f, Dilation.NONE, S, S)
                .uv(128, 170).cuboid(-1f, -11f, -1f, 2f, 3f, 2f, Dilation.NONE, S, S),
            ModelTransform.of(0f, -20f, 0f, 0.55f, 0f, 0f),
        )
        val p_petal_s = p_root.addChild(
            "petal_s",
            ModelPartBuilder.create(),
            ModelTransform.of(0f, -12f, 0f, 0f, 3.1416f, 0f),
        )
        val p_petal_s_lo = p_petal_s.addChild(
            "petal_s_lo",
            ModelPartBuilder.create()
                .uv(170, 119).cuboid(-6f, -16f, -1f, 12f, 16f, 2f, Dilation.NONE, S, S)
                .uv(80, 170).cuboid(-4f, -20f, -1f, 8f, 4f, 2f, Dilation.NONE, S, S),
            ModelTransform.of(0f, 0f, -8f, -0.32f, 0f, 0f),
        )
        val p_petal_s_hi = p_petal_s_lo.addChild(
            "petal_s_hi",
            ModelPartBuilder.create()
                .uv(192, 156).cuboid(-3f, -8f, -1f, 6f, 8f, 2f, Dilation.NONE, S, S)
                .uv(136, 170).cuboid(-1f, -11f, -1f, 2f, 3f, 2f, Dilation.NONE, S, S),
            ModelTransform.of(0f, -20f, 0f, 0.55f, 0f, 0f),
        )
        val p_petal_w = p_root.addChild(
            "petal_w",
            ModelPartBuilder.create(),
            ModelTransform.of(0f, -12f, 0f, 0f, 4.7124f, 0f),
        )
        val p_petal_w_lo = p_petal_w.addChild(
            "petal_w_lo",
            ModelPartBuilder.create()
                .uv(0, 138).cuboid(-6f, -16f, -1f, 12f, 16f, 2f, Dilation.NONE, S, S)
                .uv(100, 170).cuboid(-4f, -20f, -1f, 8f, 4f, 2f, Dilation.NONE, S, S),
            ModelTransform.of(0f, 0f, -8f, -0.32f, 0f, 0f),
        )
        val p_petal_w_hi = p_petal_w_lo.addChild(
            "petal_w_hi",
            ModelPartBuilder.create()
                .uv(208, 156).cuboid(-3f, -8f, -1f, 6f, 8f, 2f, Dilation.NONE, S, S)
                .uv(144, 170).cuboid(-1f, -11f, -1f, 2f, 3f, 2f, Dilation.NONE, S, S),
            ModelTransform.of(0f, -20f, 0f, 0.55f, 0f, 0f),
        )
        val p_tendon0 = p_root.addChild(
            "tendon0",
            ModelPartBuilder.create(),
            ModelTransform.of(0f, -6f, 0f, 0f, 0.7854f, 0f),
        )
        val p_tendon0_a = p_tendon0.addChild(
            "tendon0_a",
            ModelPartBuilder.create()
                .uv(172, 96).cuboid(-1.5f, 0f, 0f, 3f, 3f, 16f, Dilation.NONE, S, S),
            ModelTransform.of(0f, 0f, -8f, -0.38f, 0f, 0f),
        )
        val p_tendon0_b = p_tendon0_a.addChild(
            "tendon0_b",
            ModelPartBuilder.create()
                .uv(0, 156).cuboid(-1f, -1f, 0f, 2f, 2f, 12f, Dilation.NONE, S, S),
            ModelTransform.of(0f, 1.5f, 16f, -0.4f, 0f, 0f),
        )
        val p_tendon1 = p_root.addChild(
            "tendon1",
            ModelPartBuilder.create(),
            ModelTransform.of(0f, -6f, 0f, 0f, 2.3562f, 0f),
        )
        val p_tendon1_a = p_tendon1.addChild(
            "tendon1_a",
            ModelPartBuilder.create()
                .uv(0, 119).cuboid(-1.5f, 0f, 0f, 3f, 3f, 16f, Dilation.NONE, S, S),
            ModelTransform.of(0f, 0f, -8f, -0.38f, 0f, 0f),
        )
        val p_tendon1_b = p_tendon1_a.addChild(
            "tendon1_b",
            ModelPartBuilder.create()
                .uv(28, 156).cuboid(-1f, -1f, 0f, 2f, 2f, 12f, Dilation.NONE, S, S),
            ModelTransform.of(0f, 1.5f, 16f, -0.4f, 0f, 0f),
        )
        val p_tendon2 = p_root.addChild(
            "tendon2",
            ModelPartBuilder.create(),
            ModelTransform.of(0f, -6f, 0f, 0f, 3.927f, 0f),
        )
        val p_tendon2_a = p_tendon2.addChild(
            "tendon2_a",
            ModelPartBuilder.create()
                .uv(38, 119).cuboid(-1.5f, 0f, 0f, 3f, 3f, 16f, Dilation.NONE, S, S),
            ModelTransform.of(0f, 0f, -8f, -0.38f, 0f, 0f),
        )
        val p_tendon2_b = p_tendon2_a.addChild(
            "tendon2_b",
            ModelPartBuilder.create()
                .uv(56, 156).cuboid(-1f, -1f, 0f, 2f, 2f, 12f, Dilation.NONE, S, S),
            ModelTransform.of(0f, 1.5f, 16f, -0.4f, 0f, 0f),
        )
        val p_tendon3 = p_root.addChild(
            "tendon3",
            ModelPartBuilder.create(),
            ModelTransform.of(0f, -6f, 0f, 0f, 5.4978f, 0f),
        )
        val p_tendon3_a = p_tendon3.addChild(
            "tendon3_a",
            ModelPartBuilder.create()
                .uv(76, 119).cuboid(-1.5f, 0f, 0f, 3f, 3f, 16f, Dilation.NONE, S, S),
            ModelTransform.of(0f, 0f, -8f, -0.38f, 0f, 0f),
        )
        val p_tendon3_b = p_tendon3_a.addChild(
            "tendon3_b",
            ModelPartBuilder.create()
                .uv(84, 156).cuboid(-1f, -1f, 0f, 2f, 2f, 12f, Dilation.NONE, S, S),
            ModelTransform.of(0f, 1.5f, 16f, -0.4f, 0f, 0f),
        )
        val p_strand0 = p_root.addChild(
            "strand0",
            ModelPartBuilder.create(),
            ModelTransform.of(0f, -44f, 0f, 0f, 0.4f, 0f),
        )
        val p_strand0_a = p_strand0.addChild(
            "strand0_a",
            ModelPartBuilder.create()
                .uv(64, 64).cuboid(-1f, -24f, -1f, 2f, 24f, 2f, Dilation.NONE, S, S),
            ModelTransform.of(0f, 0f, -4f, 0.42f, 0f, 0f),
        )
        val p_strand0_b = p_strand0_a.addChild(
            "strand0_b",
            ModelPartBuilder.create()
                .uv(100, 96).cuboid(-1.5f, -18f, -1.5f, 3f, 18f, 3f, Dilation.NONE, S, S),
            ModelTransform.of(0f, -24f, 0f, -0.2f, 0f, 0f),
        )
        val p_strand1 = p_root.addChild(
            "strand1",
            ModelPartBuilder.create(),
            ModelTransform.of(0f, -44f, 0f, 0f, 3.5416f, 0f),
        )
        val p_strand1_a = p_strand1.addChild(
            "strand1_a",
            ModelPartBuilder.create()
                .uv(72, 64).cuboid(-1f, -24f, -1f, 2f, 24f, 2f, Dilation.NONE, S, S),
            ModelTransform.of(0f, 0f, -4f, 0.42f, 0f, 0f),
        )
        val p_strand1_b = p_strand1_a.addChild(
            "strand1_b",
            ModelPartBuilder.create()
                .uv(112, 96).cuboid(-1.5f, -18f, -1.5f, 3f, 18f, 3f, Dilation.NONE, S, S),
            ModelTransform.of(0f, -24f, 0f, -0.2f, 0f, 0f),
        )
        val p_orbit = p_root.addChild(
            "orbit",
            ModelPartBuilder.create(),
            ModelTransform.of(0f, -29f, 0f, 0f, 0f, 0f),
        )
        val p_shard0 = p_orbit.addChild(
            "shard0",
            ModelPartBuilder.create()
                .uv(0, 170).cuboid(-1f, -4f, -1f, 2f, 8f, 2f, Dilation.NONE, S, S)
                .uv(152, 170).cuboid(-0.5f, -6f, -0.5f, 1f, 2f, 1f, Dilation.NONE, S, S),
            ModelTransform.of(17f, -5f, 0f, 0f, 0f, 0.4f),
        )
        val p_shard1 = p_orbit.addChild(
            "shard1",
            ModelPartBuilder.create()
                .uv(8, 170).cuboid(-1f, -4f, -1f, 2f, 8f, 2f, Dilation.NONE, S, S)
                .uv(156, 170).cuboid(-0.5f, -6f, -0.5f, 1f, 2f, 1f, Dilation.NONE, S, S),
            ModelTransform.of(-8f, 0f, 15f, 0.3f, 2.0944f, 0.4f),
        )
        val p_shard2 = p_orbit.addChild(
            "shard2",
            ModelPartBuilder.create()
                .uv(16, 170).cuboid(-1f, -4f, -1f, 2f, 8f, 2f, Dilation.NONE, S, S)
                .uv(160, 170).cuboid(-0.5f, -6f, -0.5f, 1f, 2f, 1f, Dilation.NONE, S, S),
            ModelTransform.of(-9f, 5f, -15f, 0.6f, 4.1888f, 0.4f),
        )
        return TexturedModelData.of(data, 448, 448)
    }

    /** Weg jedes Teils von der Wurzel aus. */
    private val PATHS = mapOf(
        "root" to "root",
        "collar" to "root/collar",
        "heart" to "root/heart",
        "crystal" to "root/heart/crystal",
        "flare" to "root/heart/flare",
        "petal_n" to "root/petal_n",
        "petal_n_lo" to "root/petal_n/petal_n_lo",
        "petal_n_hi" to "root/petal_n/petal_n_lo/petal_n_hi",
        "petal_e" to "root/petal_e",
        "petal_e_lo" to "root/petal_e/petal_e_lo",
        "petal_e_hi" to "root/petal_e/petal_e_lo/petal_e_hi",
        "petal_s" to "root/petal_s",
        "petal_s_lo" to "root/petal_s/petal_s_lo",
        "petal_s_hi" to "root/petal_s/petal_s_lo/petal_s_hi",
        "petal_w" to "root/petal_w",
        "petal_w_lo" to "root/petal_w/petal_w_lo",
        "petal_w_hi" to "root/petal_w/petal_w_lo/petal_w_hi",
        "tendon0" to "root/tendon0",
        "tendon0_a" to "root/tendon0/tendon0_a",
        "tendon0_b" to "root/tendon0/tendon0_a/tendon0_b",
        "tendon1" to "root/tendon1",
        "tendon1_a" to "root/tendon1/tendon1_a",
        "tendon1_b" to "root/tendon1/tendon1_a/tendon1_b",
        "tendon2" to "root/tendon2",
        "tendon2_a" to "root/tendon2/tendon2_a",
        "tendon2_b" to "root/tendon2/tendon2_a/tendon2_b",
        "tendon3" to "root/tendon3",
        "tendon3_a" to "root/tendon3/tendon3_a",
        "tendon3_b" to "root/tendon3/tendon3_a/tendon3_b",
        "strand0" to "root/strand0",
        "strand0_a" to "root/strand0/strand0_a",
        "strand0_b" to "root/strand0/strand0_a/strand0_b",
        "strand1" to "root/strand1",
        "strand1_a" to "root/strand1/strand1_a",
        "strand1_b" to "root/strand1/strand1_a/strand1_b",
        "orbit" to "root/orbit",
        "shard0" to "root/orbit/shard0",
        "shard1" to "root/orbit/shard1",
        "shard2" to "root/orbit/shard2",
    )

    fun part(root: ModelPart, name: String): ModelPart =
        PATHS.getValue(name).split('/').fold(root) { p, n -> p.getChild(n) }
}
