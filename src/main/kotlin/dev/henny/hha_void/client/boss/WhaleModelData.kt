// GENERIERT von tools/textures/GenWhale.java - nicht von Hand ändern
package dev.henny.hha_void.client.boss

import net.minecraft.client.model.Dilation
import net.minecraft.client.model.ModelData
import net.minecraft.client.model.ModelPart
import net.minecraft.client.model.ModelPartBuilder
import net.minecraft.client.model.ModelTransform
import net.minecraft.client.model.TexturedModelData

/**
 * Oruun, der Leviathan: 1 Einheit = 0,3 Blöcke (Renderer skaliert x4,8), +Y unten, Blick nach -Z, links = +X.
 * Ursprung = Körpermitte. Nur die Harpunen haben eine Ruhedrehung; alle Posen setzt [WhaleModel].
 */
object WhaleModelData {
    private const val S = 0.3333f

    fun create(): TexturedModelData {
        val data = ModelData()
        val root = data.root
        val p_root = root.addChild(
            "root",
            ModelPartBuilder.create(),
            ModelTransform.of(0f, 0f, 0f, 0f, 0f, 0f),
        )
        val p_body = p_root.addChild(
            "body",
            ModelPartBuilder.create(),
            ModelTransform.of(0f, 0f, 0f, 0f, 0f, 0f),
        )
        val p_chest = p_body.addChild(
            "chest",
            ModelPartBuilder.create(),
            ModelTransform.of(0f, 0f, 0f, 0f, 0f, 0f),
        )
        val p_chest_skin = p_chest.addChild(
            "chest_skin",
            ModelPartBuilder.create()
                .uv(0, 90).cuboid(-26f, -16f, -18f, 52f, 32f, 36f, Dilation.NONE, S, S)
                .uv(132, 0).cuboid(-22f, -23f, -18f, 44f, 46f, 36f, Dilation.NONE, S, S)
                .uv(0, 0).cuboid(-15f, -27f, -18f, 30f, 54f, 36f, Dilation.NONE, S, S)
                .uv(502, 517).cuboid(-9.5f, -30f, -1.5f, 3f, 3f, 3f, Dilation.NONE, S, S)
                .uv(322, 527).cuboid(-9f, -31f, -1f, 2f, 1f, 2f, Dilation.NONE, S, S)
                .uv(330, 527).cuboid(-8.5f, -33f, -0.5f, 1f, 2f, 1f, Dilation.NONE, S, S)
                .uv(28, 527).cuboid(9f, -29f, 9f, 2f, 2f, 2f, Dilation.NONE, S, S)
                .uv(330, 527).cuboid(9.5f, -31f, 9.5f, 1f, 2f, 1f, Dilation.NONE, S, S)
                .uv(370, 527).cuboid(9.5f, -32f, 9.5f, 1f, 1f, 1f, Dilation.NONE, S, S)
                .uv(322, 527).cuboid(-13f, -28f, 12f, 2f, 1f, 2f, Dilation.NONE, S, S)
                .uv(370, 527).cuboid(-12.5f, -29f, 12.5f, 1f, 1f, 1f, Dilation.NONE, S, S)
                .uv(370, 527).cuboid(-12.5f, -30f, 12.5f, 1f, 1f, 1f, Dilation.NONE, S, S),
            ModelTransform.of(0f, 0f, -4f, 0f, 0f, 0f),
        )
        val p_neck = p_chest.addChild(
            "neck",
            ModelPartBuilder.create(),
            ModelTransform.of(0f, 0f, -20f, 0f, 0f, 0f),
        )
        val p_neck_skin = p_neck.addChild(
            "neck_skin",
            ModelPartBuilder.create()
                .uv(452, 90).cuboid(-25f, -16f, -14f, 50f, 32f, 28f, Dilation.NONE, S, S)
                .uv(408, 0).cuboid(-21f, -22f, -14f, 42f, 44f, 28f, Dilation.NONE, S, S)
                .uv(292, 0).cuboid(-15f, -26f, -14f, 30f, 52f, 28f, Dilation.NONE, S, S)
                .uv(276, 403).cuboid(-11f, 26f, -13f, 22f, 1f, 26f, Dilation.NONE, S, S)
                .uv(308, 517).cuboid(-4f, -27f, -6f, 8f, 1f, 6f, Dilation.NONE, S, S)
                .uv(108, 527).cuboid(-7f, -27f, -9f, 14f, 1f, 2f, Dilation.NONE, S, S)
                .uv(140, 527).cuboid(-7f, -27f, 1f, 14f, 1f, 2f, Dilation.NONE, S, S)
                .uv(128, 517).cuboid(5f, -27f, -7f, 2f, 1f, 8f, Dilation.NONE, S, S)
                .uv(148, 517).cuboid(-7f, -27f, -7f, 2f, 1f, 8f, Dilation.NONE, S, S)
                .uv(28, 527).cuboid(10f, -28f, 5f, 2f, 2f, 2f, Dilation.NONE, S, S)
                .uv(370, 527).cuboid(10.5f, -29f, 5.5f, 1f, 1f, 1f, Dilation.NONE, S, S)
                .uv(370, 527).cuboid(10.5f, -30f, 5.5f, 1f, 1f, 1f, Dilation.NONE, S, S),
            ModelTransform.of(0f, 0f, -12f, 0f, 0f, 0f),
        )
        val p_pn0 = p_neck_skin.addChild(
            "pn0",
            ModelPartBuilder.create()
                .uv(288, 371).cuboid(-2f, 0f, -14f, 4f, 2f, 28f, Dilation.NONE, S, S),
            ModelTransform.of(-10f, 26f, 0f, 0f, 0f, 0f),
        )
        val p_pn1 = p_neck_skin.addChild(
            "pn1",
            ModelPartBuilder.create()
                .uv(352, 371).cuboid(-2f, 0f, -14f, 4f, 2f, 28f, Dilation.NONE, S, S),
            ModelTransform.of(-6f, 26f, 0f, 0f, 0f, 0f),
        )
        val p_pn2 = p_neck_skin.addChild(
            "pn2",
            ModelPartBuilder.create()
                .uv(416, 371).cuboid(-2f, 0f, -14f, 4f, 2f, 28f, Dilation.NONE, S, S),
            ModelTransform.of(-2f, 26f, 0f, 0f, 0f, 0f),
        )
        val p_pn3 = p_neck_skin.addChild(
            "pn3",
            ModelPartBuilder.create()
                .uv(480, 371).cuboid(-2f, 0f, -14f, 4f, 2f, 28f, Dilation.NONE, S, S),
            ModelTransform.of(2f, 26f, 0f, 0f, 0f, 0f),
        )
        val p_pn4 = p_neck_skin.addChild(
            "pn4",
            ModelPartBuilder.create()
                .uv(544, 371).cuboid(-2f, 0f, -14f, 4f, 2f, 28f, Dilation.NONE, S, S),
            ModelTransform.of(6f, 26f, 0f, 0f, 0f, 0f),
        )
        val p_pn5 = p_neck_skin.addChild(
            "pn5",
            ModelPartBuilder.create()
                .uv(608, 371).cuboid(-2f, 0f, -14f, 4f, 2f, 28f, Dilation.NONE, S, S),
            ModelTransform.of(10f, 26f, 0f, 0f, 0f, 0f),
        )
        val p_blow_r = p_neck_skin.addChild(
            "blow_r",
            ModelPartBuilder.create()
                .uv(642, 502).cuboid(0f, -2f, -4f, 5f, 2f, 8f, Dilation.NONE, S, S),
            ModelTransform.of(-5f, -26f, -3f, 0f, 0f, 0f),
        )
        val p_blow_l = p_neck_skin.addChild(
            "blow_l",
            ModelPartBuilder.create()
                .uv(0, 517).cuboid(-5f, -2f, -4f, 5f, 2f, 8f, Dilation.NONE, S, S),
            ModelTransform.of(5f, -26f, -3f, 0f, 0f, 0f),
        )
        val p_throat_glow = p_neck.addChild(
            "throat_glow",
            ModelPartBuilder.create()
                .uv(308, 502).cuboid(-12f, -6f, 0f, 24f, 12f, 1f, Dilation.NONE, S, S),
            ModelTransform.of(0f, 15f, -27f, 0f, 0f, 0f),
        )
        val p_head = p_neck.addChild(
            "head",
            ModelPartBuilder.create()
                .uv(0, 261).cuboid(-24f, -12f, -18f, 48f, 19f, 20f, Dilation.NONE, S, S)
                .uv(0, 215).cuboid(-20f, -19f, -18f, 40f, 26f, 20f, Dilation.NONE, S, S)
                .uv(484, 158).cuboid(-13f, -23f, -18f, 26f, 30f, 20f, Dilation.NONE, S, S)
                .uv(0, 403).cuboid(-23f, -11f, -30f, 46f, 18f, 12f, Dilation.NONE, S, S)
                .uv(530, 261).cuboid(-19f, -17f, -30f, 38f, 24f, 12f, Dilation.NONE, S, S)
                .uv(458, 215).cuboid(-13f, -21f, -30f, 26f, 28f, 12f, Dilation.NONE, S, S)
                .uv(496, 403).cuboid(-21f, -9f, -40f, 42f, 16f, 10f, Dilation.NONE, S, S)
                .uv(488, 336).cuboid(-18f, -15f, -40f, 36f, 22f, 10f, Dilation.NONE, S, S)
                .uv(610, 300).cuboid(-12f, -18f, -40f, 24f, 25f, 10f, Dilation.NONE, S, S)
                .uv(518, 482).cuboid(-18f, -7f, -44f, 36f, 13f, 4f, Dilation.NONE, S, S)
                .uv(292, 459).cuboid(-16f, -11f, -44f, 32f, 17f, 4f, Dilation.NONE, S, S)
                .uv(550, 433).cuboid(-12f, -13f, -44f, 24f, 19f, 4f, Dilation.NONE, S, S)
                .uv(168, 517).cuboid(-25f, -7f, -17f, 1f, 4f, 5f, Dilation.NONE, S, S)
                .uv(546, 502).cuboid(-26f, -9f, -19f, 2f, 2f, 9f, Dilation.NONE, S, S)
                .uv(236, 517).cuboid(-25f, -3f, -18f, 1f, 1f, 7f, Dilation.NONE, S, S)
                .uv(180, 517).cuboid(24f, -7f, -17f, 1f, 4f, 5f, Dilation.NONE, S, S)
                .uv(568, 502).cuboid(24f, -9f, -19f, 2f, 2f, 9f, Dilation.NONE, S, S)
                .uv(252, 517).cuboid(24f, -3f, -18f, 1f, 1f, 7f, Dilation.NONE, S, S)
                .uv(526, 517).cuboid(-24f, 6f, -3f, 3f, 2f, 3f, Dilation.NONE, S, S)
                .uv(640, 517).cuboid(-23.5f, 8f, -2.5f, 2f, 2f, 2f, Dilation.NONE, S, S)
                .uv(334, 527).cuboid(-23f, 10f, -2f, 1f, 1f, 1f, Dilation.NONE, S, S)
                .uv(426, 517).cuboid(21f, 6f, -3f, 3f, 3f, 3f, Dilation.NONE, S, S)
                .uv(640, 517).cuboid(21.5f, 9f, -2.5f, 2f, 2f, 2f, Dilation.NONE, S, S)
                .uv(172, 527).cuboid(22f, 11f, -2f, 1f, 2f, 1f, Dilation.NONE, S, S)
                .uv(526, 517).cuboid(-24f, 6f, -8f, 3f, 2f, 3f, Dilation.NONE, S, S)
                .uv(640, 517).cuboid(-23.5f, 8f, -7.5f, 2f, 2f, 2f, Dilation.NONE, S, S)
                .uv(334, 527).cuboid(-23f, 10f, -7f, 1f, 1f, 1f, Dilation.NONE, S, S)
                .uv(336, 517).cuboid(21f, 6f, -8f, 3f, 4f, 3f, Dilation.NONE, S, S)
                .uv(640, 517).cuboid(21.5f, 10f, -7.5f, 2f, 2f, 2f, Dilation.NONE, S, S)
                .uv(172, 527).cuboid(22f, 12f, -7f, 1f, 2f, 1f, Dilation.NONE, S, S)
                .uv(336, 517).cuboid(-24f, 6f, -13f, 3f, 4f, 3f, Dilation.NONE, S, S)
                .uv(640, 517).cuboid(-23.5f, 10f, -12.5f, 2f, 2f, 2f, Dilation.NONE, S, S)
                .uv(172, 527).cuboid(-23f, 12f, -12f, 1f, 2f, 1f, Dilation.NONE, S, S)
                .uv(336, 517).cuboid(21f, 6f, -13f, 3f, 4f, 3f, Dilation.NONE, S, S)
                .uv(640, 517).cuboid(21.5f, 10f, -12.5f, 2f, 2f, 2f, Dilation.NONE, S, S)
                .uv(172, 527).cuboid(22f, 12f, -12f, 1f, 2f, 1f, Dilation.NONE, S, S)
                .uv(526, 517).cuboid(-24f, 6f, -17.5f, 3f, 2f, 3f, Dilation.NONE, S, S)
                .uv(640, 517).cuboid(-23.5f, 8f, -17f, 2f, 2f, 2f, Dilation.NONE, S, S)
                .uv(334, 527).cuboid(-23f, 10f, -16.5f, 1f, 1f, 1f, Dilation.NONE, S, S)
                .uv(426, 517).cuboid(21f, 6f, -17.5f, 3f, 3f, 3f, Dilation.NONE, S, S)
                .uv(176, 527).cuboid(21.5f, 9f, -17f, 2f, 1f, 2f, Dilation.NONE, S, S)
                .uv(172, 527).cuboid(22f, 10f, -16.5f, 1f, 2f, 1f, Dilation.NONE, S, S)
                .uv(426, 517).cuboid(-23f, 6f, -22.5f, 3f, 3f, 3f, Dilation.NONE, S, S)
                .uv(176, 527).cuboid(-22.5f, 9f, -22f, 2f, 1f, 2f, Dilation.NONE, S, S)
                .uv(172, 527).cuboid(-22f, 10f, -21.5f, 1f, 2f, 1f, Dilation.NONE, S, S)
                .uv(526, 517).cuboid(20f, 6f, -22.5f, 3f, 2f, 3f, Dilation.NONE, S, S)
                .uv(640, 517).cuboid(20.5f, 8f, -22f, 2f, 2f, 2f, Dilation.NONE, S, S)
                .uv(334, 527).cuboid(21f, 10f, -21.5f, 1f, 1f, 1f, Dilation.NONE, S, S)
                .uv(426, 517).cuboid(-23f, 6f, -27f, 3f, 3f, 3f, Dilation.NONE, S, S)
                .uv(640, 517).cuboid(-22.5f, 9f, -26.5f, 2f, 2f, 2f, Dilation.NONE, S, S)
                .uv(172, 527).cuboid(-22f, 11f, -26f, 1f, 2f, 1f, Dilation.NONE, S, S)
                .uv(526, 517).cuboid(20f, 6f, -27f, 3f, 2f, 3f, Dilation.NONE, S, S)
                .uv(640, 517).cuboid(20.5f, 8f, -26.5f, 2f, 2f, 2f, Dilation.NONE, S, S)
                .uv(334, 527).cuboid(21f, 10f, -26f, 1f, 1f, 1f, Dilation.NONE, S, S)
                .uv(426, 517).cuboid(-21f, 6f, -34f, 3f, 3f, 3f, Dilation.NONE, S, S)
                .uv(640, 517).cuboid(-20.5f, 9f, -33.5f, 2f, 2f, 2f, Dilation.NONE, S, S)
                .uv(172, 527).cuboid(-20f, 11f, -33f, 1f, 2f, 1f, Dilation.NONE, S, S)
                .uv(336, 517).cuboid(18f, 6f, -34f, 3f, 4f, 3f, Dilation.NONE, S, S)
                .uv(640, 517).cuboid(18.5f, 10f, -33.5f, 2f, 2f, 2f, Dilation.NONE, S, S)
                .uv(172, 527).cuboid(19f, 12f, -33f, 1f, 2f, 1f, Dilation.NONE, S, S)
                .uv(426, 517).cuboid(-21f, 6f, -38.5f, 3f, 3f, 3f, Dilation.NONE, S, S)
                .uv(176, 527).cuboid(-20.5f, 9f, -38f, 2f, 1f, 2f, Dilation.NONE, S, S)
                .uv(172, 527).cuboid(-20f, 10f, -37.5f, 1f, 2f, 1f, Dilation.NONE, S, S)
                .uv(426, 517).cuboid(18f, 6f, -38.5f, 3f, 3f, 3f, Dilation.NONE, S, S)
                .uv(176, 527).cuboid(18.5f, 9f, -38f, 2f, 1f, 2f, Dilation.NONE, S, S)
                .uv(172, 527).cuboid(19f, 10f, -37.5f, 1f, 2f, 1f, Dilation.NONE, S, S)
                .uv(426, 517).cuboid(-10f, 5f, -44f, 3f, 3f, 3f, Dilation.NONE, S, S)
                .uv(176, 527).cuboid(-9.5f, 8f, -43.5f, 2f, 1f, 2f, Dilation.NONE, S, S)
                .uv(172, 527).cuboid(-9f, 9f, -43f, 1f, 2f, 1f, Dilation.NONE, S, S)
                .uv(426, 517).cuboid(-4.5f, 5f, -44f, 3f, 3f, 3f, Dilation.NONE, S, S)
                .uv(176, 527).cuboid(-4f, 8f, -43.5f, 2f, 1f, 2f, Dilation.NONE, S, S)
                .uv(172, 527).cuboid(-3.5f, 9f, -43f, 1f, 2f, 1f, Dilation.NONE, S, S)
                .uv(426, 517).cuboid(1.5f, 5f, -44f, 3f, 3f, 3f, Dilation.NONE, S, S)
                .uv(176, 527).cuboid(2f, 8f, -43.5f, 2f, 1f, 2f, Dilation.NONE, S, S)
                .uv(172, 527).cuboid(2.5f, 9f, -43f, 1f, 2f, 1f, Dilation.NONE, S, S)
                .uv(426, 517).cuboid(7f, 5f, -44f, 3f, 3f, 3f, Dilation.NONE, S, S)
                .uv(176, 527).cuboid(7.5f, 8f, -43.5f, 2f, 1f, 2f, Dilation.NONE, S, S)
                .uv(172, 527).cuboid(8f, 9f, -43f, 1f, 2f, 1f, Dilation.NONE, S, S)
                .uv(568, 517).cuboid(-10.5f, -23f, -27.5f, 3f, 2f, 3f, Dilation.NONE, S, S)
                .uv(0, 527).cuboid(-10f, -25f, -27f, 2f, 2f, 2f, Dilation.NONE, S, S)
                .uv(346, 527).cuboid(-9.5f, -26f, -26.5f, 1f, 1f, 1f, Dilation.NONE, S, S)
                .uv(294, 527).cuboid(-6f, -22f, -23f, 2f, 1f, 2f, Dilation.NONE, S, S)
                .uv(346, 527).cuboid(-5.5f, -23f, -22.5f, 1f, 1f, 1f, Dilation.NONE, S, S)
                .uv(346, 527).cuboid(-5.5f, -24f, -22.5f, 1f, 1f, 1f, Dilation.NONE, S, S)
                .uv(568, 517).cuboid(9.5f, -20f, -37.5f, 3f, 2f, 3f, Dilation.NONE, S, S)
                .uv(294, 527).cuboid(10f, -21f, -37f, 2f, 1f, 2f, Dilation.NONE, S, S)
                .uv(346, 527).cuboid(10.5f, -22f, -36.5f, 1f, 1f, 1f, Dilation.NONE, S, S)
                .uv(294, 527).cuboid(6f, -19f, -34f, 2f, 1f, 2f, Dilation.NONE, S, S)
                .uv(346, 527).cuboid(6.5f, -20f, -33.5f, 1f, 1f, 1f, Dilation.NONE, S, S)
                .uv(346, 527).cuboid(6.5f, -21f, -33.5f, 1f, 1f, 1f, Dilation.NONE, S, S)
                .uv(0, 527).cuboid(-12f, -20f, -39f, 2f, 2f, 2f, Dilation.NONE, S, S)
                .uv(346, 527).cuboid(-11.5f, -21f, -38.5f, 1f, 1f, 1f, Dilation.NONE, S, S)
                .uv(346, 527).cuboid(-11.5f, -22f, -38.5f, 1f, 1f, 1f, Dilation.NONE, S, S)
                .uv(294, 527).cuboid(2f, -14f, -43f, 2f, 1f, 2f, Dilation.NONE, S, S)
                .uv(346, 527).cuboid(2.5f, -15f, -42.5f, 1f, 1f, 1f, Dilation.NONE, S, S)
                .uv(346, 527).cuboid(2.5f, -16f, -42.5f, 1f, 1f, 1f, Dilation.NONE, S, S)
                .uv(294, 527).cuboid(-7f, -24f, -7f, 2f, 1f, 2f, Dilation.NONE, S, S)
                .uv(346, 527).cuboid(-6.5f, -25f, -6.5f, 1f, 1f, 1f, Dilation.NONE, S, S)
                .uv(346, 527).cuboid(-6.5f, -26f, -6.5f, 1f, 1f, 1f, Dilation.NONE, S, S)
                .uv(568, 517).cuboid(10.5f, -25f, -3.5f, 3f, 2f, 3f, Dilation.NONE, S, S)
                .uv(0, 527).cuboid(11f, -27f, -3f, 2f, 2f, 2f, Dilation.NONE, S, S)
                .uv(346, 527).cuboid(11.5f, -28f, -2.5f, 1f, 1f, 1f, Dilation.NONE, S, S)
                .uv(294, 527).cuboid(9f, -24f, -13f, 2f, 1f, 2f, Dilation.NONE, S, S)
                .uv(346, 527).cuboid(9.5f, -25f, -12.5f, 1f, 1f, 1f, Dilation.NONE, S, S)
                .uv(346, 527).cuboid(9.5f, -26f, -12.5f, 1f, 1f, 1f, Dilation.NONE, S, S)
                .uv(294, 527).cuboid(-13f, -24f, -15f, 2f, 1f, 2f, Dilation.NONE, S, S)
                .uv(346, 527).cuboid(-12.5f, -25f, -14.5f, 1f, 1f, 1f, Dilation.NONE, S, S)
                .uv(580, 517).cuboid(-11.5f, -3.5f, -46f, 3f, 3f, 2f, Dilation.NONE, S, S)
                .uv(302, 527).cuboid(-11f, -3f, -47f, 2f, 2f, 1f, Dilation.NONE, S, S)
                .uv(350, 527).cuboid(-10.5f, -2.5f, -48f, 1f, 1f, 1f, Dilation.NONE, S, S)
                .uv(302, 527).cuboid(11f, 0f, -45f, 2f, 2f, 1f, Dilation.NONE, S, S)
                .uv(350, 527).cuboid(11.5f, 0.5f, -46f, 1f, 1f, 1f, Dilation.NONE, S, S)
                .uv(350, 527).cuboid(11.5f, 0.5f, -47f, 1f, 1f, 1f, Dilation.NONE, S, S)
                .uv(302, 527).cuboid(-15f, 2f, -45f, 2f, 2f, 1f, Dilation.NONE, S, S)
                .uv(350, 527).cuboid(-14.5f, 2.5f, -46f, 1f, 1f, 1f, Dilation.NONE, S, S)
                .uv(350, 527).cuboid(-14.5f, 2.5f, -47f, 1f, 1f, 1f, Dilation.NONE, S, S)
                .uv(302, 527).cuboid(4f, -5f, -45f, 2f, 2f, 1f, Dilation.NONE, S, S)
                .uv(350, 527).cuboid(4.5f, -4.5f, -46f, 1f, 1f, 1f, Dilation.NONE, S, S)
                .uv(8, 527).cuboid(-1.5f, -10.5f, -45f, 3f, 3f, 1f, Dilation.NONE, S, S)
                .uv(302, 527).cuboid(-1f, -10f, -46f, 2f, 2f, 1f, Dilation.NONE, S, S)
                .uv(350, 527).cuboid(-0.5f, -9.5f, -47f, 1f, 1f, 1f, Dilation.NONE, S, S)
                .uv(16, 527).cuboid(24f, 1f, -5f, 1f, 2f, 2f, Dilation.NONE, S, S)
                .uv(354, 527).cuboid(25f, 1.5f, -4.5f, 1f, 1f, 1f, Dilation.NONE, S, S)
                .uv(354, 527).cuboid(26f, 1.5f, -4.5f, 1f, 1f, 1f, Dilation.NONE, S, S)
                .uv(16, 527).cuboid(24f, -2f, -10f, 1f, 2f, 2f, Dilation.NONE, S, S)
                .uv(354, 527).cuboid(25f, -1.5f, -9.5f, 1f, 1f, 1f, Dilation.NONE, S, S)
                .uv(492, 517).cuboid(-26f, 1.5f, -3.5f, 2f, 3f, 3f, Dilation.NONE, S, S)
                .uv(22, 527).cuboid(-27f, 2f, -3f, 1f, 2f, 2f, Dilation.NONE, S, S)
                .uv(358, 527).cuboid(-28f, 2.5f, -2.5f, 1f, 1f, 1f, Dilation.NONE, S, S)
                .uv(22, 527).cuboid(-24f, 3f, -27f, 1f, 2f, 2f, Dilation.NONE, S, S)
                .uv(358, 527).cuboid(-25f, 3.5f, -26.5f, 1f, 1f, 1f, Dilation.NONE, S, S)
                .uv(358, 527).cuboid(-26f, 3.5f, -26.5f, 1f, 1f, 1f, Dilation.NONE, S, S)
                .uv(16, 527).cuboid(23f, -1f, -23f, 1f, 2f, 2f, Dilation.NONE, S, S)
                .uv(354, 527).cuboid(24f, -0.5f, -22.5f, 1f, 1f, 1f, Dilation.NONE, S, S)
                .uv(22, 527).cuboid(-22f, -2f, -36f, 1f, 2f, 2f, Dilation.NONE, S, S)
                .uv(358, 527).cuboid(-23f, -1.5f, -35.5f, 1f, 1f, 1f, Dilation.NONE, S, S),
            ModelTransform.of(0f, -2f, -24f, 0f, 0f, 0f),
        )
        val p_halo_r = p_head.addChild(
            "halo_r",
            ModelPartBuilder.create()
                .uv(580, 336).cuboid(0f, -8f, -8f, 0f, 16f, 16f, Dilation.NONE, S, S),
            ModelTransform.of(-26f, -5f, -14.5f, 0f, 0f, 0f),
        )
        val p_halo_l = p_head.addChild(
            "halo_l",
            ModelPartBuilder.create()
                .uv(612, 336).cuboid(0f, -8f, -8f, 0f, 16f, 16f, Dilation.NONE, S, S),
            ModelTransform.of(26f, -5f, -14.5f, 0f, 0f, 0f),
        )
        val p_jaw = p_head.addChild(
            "jaw",
            ModelPartBuilder.create()
                .uv(372, 403).cuboid(-20f, 12f, -22f, 40f, 5f, 22f, Dilation.NONE, S, S)
                .uv(364, 459).cuboid(-18f, 11f, -38f, 36f, 5f, 16f, Dilation.NONE, S, S)
                .uv(36, 502).cuboid(-15f, 10f, -48f, 30f, 4f, 10f, Dilation.NONE, S, S)
                .uv(26, 517).cuboid(-12f, 9f, -55f, 24f, 3f, 7f, Dilation.NONE, S, S)
                .uv(0, 336).cuboid(-20f, -1f, -22f, 4f, 13f, 22f, Dilation.NONE, S, S)
                .uv(120, 403).cuboid(-18f, -1f, -38f, 4f, 12f, 16f, Dilation.NONE, S, S)
                .uv(468, 459).cuboid(-15f, -1f, -48f, 4f, 11f, 10f, Dilation.NONE, S, S)
                .uv(116, 502).cuboid(-12f, 0f, -53f, 4f, 9f, 5f, Dilation.NONE, S, S)
                .uv(52, 336).cuboid(16f, -1f, -22f, 4f, 13f, 22f, Dilation.NONE, S, S)
                .uv(160, 403).cuboid(14f, -1f, -38f, 4f, 12f, 16f, Dilation.NONE, S, S)
                .uv(496, 459).cuboid(11f, -1f, -48f, 4f, 11f, 10f, Dilation.NONE, S, S)
                .uv(134, 502).cuboid(8f, 0f, -53f, 4f, 9f, 5f, Dilation.NONE, S, S)
                .uv(590, 502).cuboid(-12f, 0f, -55f, 24f, 9f, 2f, Dilation.NONE, S, S)
                .uv(232, 482).cuboid(-8f, 9f, -22f, 16f, 3f, 15f, Dilation.NONE, S, S)
                .uv(600, 403).cuboid(-7f, 8f, -45f, 14f, 3f, 23f, Dilation.NONE, S, S)
                .uv(538, 517).cuboid(-19.5f, -2f, -5.5f, 3f, 2f, 3f, Dilation.NONE, S, S)
                .uv(648, 517).cuboid(-19f, -4f, -5f, 2f, 2f, 2f, Dilation.NONE, S, S)
                .uv(338, 527).cuboid(-18.5f, -5f, -4.5f, 1f, 1f, 1f, Dilation.NONE, S, S)
                .uv(438, 517).cuboid(16.5f, -3f, -5.5f, 3f, 3f, 3f, Dilation.NONE, S, S)
                .uv(648, 517).cuboid(17f, -5f, -5f, 2f, 2f, 2f, Dilation.NONE, S, S)
                .uv(184, 527).cuboid(17.5f, -7f, -4.5f, 1f, 2f, 1f, Dilation.NONE, S, S)
                .uv(438, 517).cuboid(-19.5f, -3f, -10.5f, 3f, 3f, 3f, Dilation.NONE, S, S)
                .uv(188, 527).cuboid(-19f, -4f, -10f, 2f, 1f, 2f, Dilation.NONE, S, S)
                .uv(184, 527).cuboid(-18.5f, -6f, -9.5f, 1f, 2f, 1f, Dilation.NONE, S, S)
                .uv(438, 517).cuboid(16.5f, -3f, -10.5f, 3f, 3f, 3f, Dilation.NONE, S, S)
                .uv(188, 527).cuboid(17f, -4f, -10f, 2f, 1f, 2f, Dilation.NONE, S, S)
                .uv(184, 527).cuboid(17.5f, -6f, -9.5f, 1f, 2f, 1f, Dilation.NONE, S, S)
                .uv(538, 517).cuboid(-19.5f, -2f, -15.5f, 3f, 2f, 3f, Dilation.NONE, S, S)
                .uv(648, 517).cuboid(-19f, -4f, -15f, 2f, 2f, 2f, Dilation.NONE, S, S)
                .uv(338, 527).cuboid(-18.5f, -5f, -14.5f, 1f, 1f, 1f, Dilation.NONE, S, S)
                .uv(348, 517).cuboid(16.5f, -4f, -15.5f, 3f, 4f, 3f, Dilation.NONE, S, S)
                .uv(648, 517).cuboid(17f, -6f, -15f, 2f, 2f, 2f, Dilation.NONE, S, S)
                .uv(184, 527).cuboid(17.5f, -8f, -14.5f, 1f, 2f, 1f, Dilation.NONE, S, S)
                .uv(438, 517).cuboid(-19.5f, -3f, -20.5f, 3f, 3f, 3f, Dilation.NONE, S, S)
                .uv(188, 527).cuboid(-19f, -4f, -20f, 2f, 1f, 2f, Dilation.NONE, S, S)
                .uv(184, 527).cuboid(-18.5f, -6f, -19.5f, 1f, 2f, 1f, Dilation.NONE, S, S)
                .uv(538, 517).cuboid(16.5f, -2f, -20.5f, 3f, 2f, 3f, Dilation.NONE, S, S)
                .uv(648, 517).cuboid(17f, -4f, -20f, 2f, 2f, 2f, Dilation.NONE, S, S)
                .uv(338, 527).cuboid(17.5f, -5f, -19.5f, 1f, 1f, 1f, Dilation.NONE, S, S)
                .uv(438, 517).cuboid(-17.5f, -3f, -26f, 3f, 3f, 3f, Dilation.NONE, S, S)
                .uv(188, 527).cuboid(-17f, -4f, -25.5f, 2f, 1f, 2f, Dilation.NONE, S, S)
                .uv(184, 527).cuboid(-16.5f, -6f, -25f, 1f, 2f, 1f, Dilation.NONE, S, S)
                .uv(438, 517).cuboid(14.5f, -3f, -26f, 3f, 3f, 3f, Dilation.NONE, S, S)
                .uv(188, 527).cuboid(15f, -4f, -25.5f, 2f, 1f, 2f, Dilation.NONE, S, S)
                .uv(184, 527).cuboid(15.5f, -6f, -25f, 1f, 2f, 1f, Dilation.NONE, S, S)
                .uv(438, 517).cuboid(-17.5f, -3f, -30.5f, 3f, 3f, 3f, Dilation.NONE, S, S)
                .uv(188, 527).cuboid(-17f, -4f, -30f, 2f, 1f, 2f, Dilation.NONE, S, S)
                .uv(184, 527).cuboid(-16.5f, -6f, -29.5f, 1f, 2f, 1f, Dilation.NONE, S, S)
                .uv(348, 517).cuboid(14.5f, -4f, -30.5f, 3f, 4f, 3f, Dilation.NONE, S, S)
                .uv(648, 517).cuboid(15f, -6f, -30f, 2f, 2f, 2f, Dilation.NONE, S, S)
                .uv(184, 527).cuboid(15.5f, -8f, -29.5f, 1f, 2f, 1f, Dilation.NONE, S, S)
                .uv(438, 517).cuboid(-17.5f, -3f, -35f, 3f, 3f, 3f, Dilation.NONE, S, S)
                .uv(648, 517).cuboid(-17f, -5f, -34.5f, 2f, 2f, 2f, Dilation.NONE, S, S)
                .uv(184, 527).cuboid(-16.5f, -7f, -34f, 1f, 2f, 1f, Dilation.NONE, S, S)
                .uv(348, 517).cuboid(14.5f, -4f, -35f, 3f, 4f, 3f, Dilation.NONE, S, S)
                .uv(648, 517).cuboid(15f, -6f, -34.5f, 2f, 2f, 2f, Dilation.NONE, S, S)
                .uv(184, 527).cuboid(15.5f, -8f, -34f, 1f, 2f, 1f, Dilation.NONE, S, S)
                .uv(438, 517).cuboid(-14.5f, -3f, -41.5f, 3f, 3f, 3f, Dilation.NONE, S, S)
                .uv(648, 517).cuboid(-14f, -5f, -41f, 2f, 2f, 2f, Dilation.NONE, S, S)
                .uv(184, 527).cuboid(-13.5f, -7f, -40.5f, 1f, 2f, 1f, Dilation.NONE, S, S)
                .uv(538, 517).cuboid(11.5f, -2f, -41.5f, 3f, 2f, 3f, Dilation.NONE, S, S)
                .uv(648, 517).cuboid(12f, -4f, -41f, 2f, 2f, 2f, Dilation.NONE, S, S)
                .uv(338, 527).cuboid(12.5f, -5f, -40.5f, 1f, 1f, 1f, Dilation.NONE, S, S)
                .uv(438, 517).cuboid(-14.5f, -3f, -46f, 3f, 3f, 3f, Dilation.NONE, S, S)
                .uv(188, 527).cuboid(-14f, -4f, -45.5f, 2f, 1f, 2f, Dilation.NONE, S, S)
                .uv(184, 527).cuboid(-13.5f, -6f, -45f, 1f, 2f, 1f, Dilation.NONE, S, S)
                .uv(438, 517).cuboid(11.5f, -3f, -46f, 3f, 3f, 3f, Dilation.NONE, S, S)
                .uv(188, 527).cuboid(12f, -4f, -45.5f, 2f, 1f, 2f, Dilation.NONE, S, S)
                .uv(184, 527).cuboid(12.5f, -6f, -45f, 1f, 2f, 1f, Dilation.NONE, S, S)
                .uv(348, 517).cuboid(-11.5f, -3f, -52f, 3f, 4f, 3f, Dilation.NONE, S, S)
                .uv(550, 517).cuboid(-11f, -6f, -51.5f, 2f, 3f, 2f, Dilation.NONE, S, S)
                .uv(184, 527).cuboid(-10.5f, -8f, -51f, 1f, 2f, 1f, Dilation.NONE, S, S)
                .uv(268, 517).cuboid(-11f, -4f, -55.5f, 3f, 5f, 3f, Dilation.NONE, S, S)
                .uv(648, 517).cuboid(-10.5f, -6f, -55f, 2f, 2f, 2f, Dilation.NONE, S, S)
                .uv(656, 517).cuboid(-10f, -9f, -54.5f, 1f, 3f, 1f, Dilation.NONE, S, S)
                .uv(88, 517).cuboid(-6f, -5f, -56f, 4f, 6f, 4f, Dilation.NONE, S, S)
                .uv(348, 517).cuboid(-5.5f, -9f, -55.5f, 3f, 4f, 3f, Dilation.NONE, S, S)
                .uv(656, 517).cuboid(-4.5f, -12f, -54.5f, 1f, 3f, 1f, Dilation.NONE, S, S)
                .uv(348, 517).cuboid(8.5f, -3f, -52f, 3f, 4f, 3f, Dilation.NONE, S, S)
                .uv(550, 517).cuboid(9f, -6f, -51.5f, 2f, 3f, 2f, Dilation.NONE, S, S)
                .uv(184, 527).cuboid(9.5f, -8f, -51f, 1f, 2f, 1f, Dilation.NONE, S, S)
                .uv(268, 517).cuboid(8f, -4f, -55.5f, 3f, 5f, 3f, Dilation.NONE, S, S)
                .uv(648, 517).cuboid(8.5f, -6f, -55f, 2f, 2f, 2f, Dilation.NONE, S, S)
                .uv(656, 517).cuboid(9f, -9f, -54.5f, 1f, 3f, 1f, Dilation.NONE, S, S)
                .uv(88, 517).cuboid(2f, -5f, -56f, 4f, 6f, 4f, Dilation.NONE, S, S)
                .uv(348, 517).cuboid(2.5f, -9f, -55.5f, 3f, 4f, 3f, Dilation.NONE, S, S)
                .uv(656, 517).cuboid(3.5f, -12f, -54.5f, 1f, 3f, 1f, Dilation.NONE, S, S)
                .uv(308, 527).cuboid(-7f, 4f, -56f, 2f, 2f, 1f, Dilation.NONE, S, S)
                .uv(362, 527).cuboid(-6.5f, 4.5f, -57f, 1f, 1f, 1f, Dilation.NONE, S, S)
                .uv(362, 527).cuboid(-6.5f, 4.5f, -58f, 1f, 1f, 1f, Dilation.NONE, S, S)
                .uv(590, 517).cuboid(5.5f, 2.5f, -57f, 3f, 3f, 2f, Dilation.NONE, S, S)
                .uv(308, 527).cuboid(6f, 3f, -58f, 2f, 2f, 1f, Dilation.NONE, S, S)
                .uv(362, 527).cuboid(6.5f, 3.5f, -59f, 1f, 1f, 1f, Dilation.NONE, S, S)
                .uv(314, 527).cuboid(-1f, 12f, -52f, 2f, 1f, 2f, Dilation.NONE, S, S)
                .uv(366, 527).cuboid(-0.5f, 13f, -51.5f, 1f, 1f, 1f, Dilation.NONE, S, S)
                .uv(366, 527).cuboid(-0.5f, 14f, -51.5f, 1f, 1f, 1f, Dilation.NONE, S, S)
                .uv(600, 517).cuboid(-9.5f, 12f, -51.5f, 3f, 2f, 3f, Dilation.NONE, S, S)
                .uv(314, 527).cuboid(-9f, 14f, -51f, 2f, 1f, 2f, Dilation.NONE, S, S)
                .uv(366, 527).cuboid(-8.5f, 15f, -50.5f, 1f, 1f, 1f, Dilation.NONE, S, S),
            ModelTransform.of(0f, 10f, 4f, 0f, 0f, 0f),
        )
        val p_throat = p_jaw.addChild(
            "throat",
            ModelPartBuilder.create()
                .uv(406, 261).cuboid(-14f, 0f, -18f, 28f, 3f, 34f, Dilation.NONE, S, S)
                .uv(358, 502).cuboid(-10f, -2f, -28f, 20f, 3f, 10f, Dilation.NONE, S, S)
                .uv(380, 336).cuboid(-11f, 3f, -17f, 22f, 1f, 32f, Dilation.NONE, S, S),
            ModelTransform.of(0f, 16f, -20f, 0f, 0f, 0f),
        )
        val p_pj0 = p_throat.addChild(
            "pj0",
            ModelPartBuilder.create()
                .uv(0, 300).cuboid(-2f, 0f, -17f, 4f, 2f, 34f, Dilation.NONE, S, S),
            ModelTransform.of(-10f, 3f, -1f, 0f, 0f, 0f),
        )
        val p_pj1 = p_throat.addChild(
            "pj1",
            ModelPartBuilder.create()
                .uv(76, 300).cuboid(-2f, 0f, -17f, 4f, 2f, 34f, Dilation.NONE, S, S),
            ModelTransform.of(-6f, 3f, -1f, 0f, 0f, 0f),
        )
        val p_pj2 = p_throat.addChild(
            "pj2",
            ModelPartBuilder.create()
                .uv(152, 300).cuboid(-2f, 0f, -17f, 4f, 2f, 34f, Dilation.NONE, S, S),
            ModelTransform.of(-2f, 3f, -1f, 0f, 0f, 0f),
        )
        val p_pj3 = p_throat.addChild(
            "pj3",
            ModelPartBuilder.create()
                .uv(228, 300).cuboid(-2f, 0f, -17f, 4f, 2f, 34f, Dilation.NONE, S, S),
            ModelTransform.of(2f, 3f, -1f, 0f, 0f, 0f),
        )
        val p_pj4 = p_throat.addChild(
            "pj4",
            ModelPartBuilder.create()
                .uv(304, 300).cuboid(-2f, 0f, -17f, 4f, 2f, 34f, Dilation.NONE, S, S),
            ModelTransform.of(6f, 3f, -1f, 0f, 0f, 0f),
        )
        val p_pj5 = p_throat.addChild(
            "pj5",
            ModelPartBuilder.create()
                .uv(380, 300).cuboid(-2f, 0f, -17f, 4f, 2f, 34f, Dilation.NONE, S, S),
            ModelTransform.of(10f, 3f, -1f, 0f, 0f, 0f),
        )
        val p_cheek_r = p_head.addChild(
            "cheek_r",
            ModelPartBuilder.create()
                .uv(0, 433).cuboid(-0.5f, -3f, -14f, 1f, 12f, 14f, Dilation.NONE, S, S),
            ModelTransform.of(-21.5f, 10f, 4f, 0f, 0f, 0f),
        )
        val p_cheek_l = p_head.addChild(
            "cheek_l",
            ModelPartBuilder.create()
                .uv(30, 433).cuboid(-0.5f, -3f, -14f, 1f, 12f, 14f, Dilation.NONE, S, S),
            ModelTransform.of(21.5f, 10f, 4f, 0f, 0f, 0f),
        )
        val p_belly = p_chest.addChild(
            "belly",
            ModelPartBuilder.create(),
            ModelTransform.of(0f, 0f, 12f, 0f, 0f, 0f),
        )
        val p_belly_skin = p_belly.addChild(
            "belly_skin",
            ModelPartBuilder.create()
                .uv(338, 215).cuboid(-25f, -16f, -10f, 50f, 32f, 10f, Dilation.NONE, S, S)
                .uv(152, 158).cuboid(-21f, -22f, -10f, 42f, 44f, 10f, Dilation.NONE, S, S)
                .uv(244, 90).cuboid(-15f, -26f, -10f, 30f, 52f, 10f, Dilation.NONE, S, S)
                .uv(136, 261).cuboid(-23f, -15f, 0f, 46f, 29f, 10f, Dilation.NONE, S, S)
                .uv(384, 158).cuboid(-20f, -21f, 0f, 40f, 41f, 10f, Dilation.NONE, S, S)
                .uv(0, 158).cuboid(-14f, -24f, 0f, 28f, 47f, 10f, Dilation.NONE, S, S)
                .uv(612, 517).cuboid(4.5f, -28f, -6.5f, 3f, 2f, 3f, Dilation.NONE, S, S)
                .uv(28, 527).cuboid(5f, -30f, -6f, 2f, 2f, 2f, Dilation.NONE, S, S)
                .uv(370, 527).cuboid(5.5f, -31f, -5.5f, 1f, 1f, 1f, Dilation.NONE, S, S)
                .uv(28, 527).cuboid(-10f, -26f, 4f, 2f, 2f, 2f, Dilation.NONE, S, S)
                .uv(370, 527).cuboid(-9.5f, -27f, 4.5f, 1f, 1f, 1f, Dilation.NONE, S, S)
                .uv(370, 527).cuboid(-9.5f, -28f, 4.5f, 1f, 1f, 1f, Dilation.NONE, S, S),
            ModelTransform.of(0f, 0f, 8f, 0f, 0f, 0f),
        )
        val p_hip = p_belly.addChild(
            "hip",
            ModelPartBuilder.create()
                .uv(456, 300).cuboid(-21f, -13f, -2f, 42f, 26f, 10f, Dilation.NONE, S, S)
                .uv(120, 215).cuboid(-18f, -18f, -2f, 36f, 36f, 10f, Dilation.NONE, S, S)
                .uv(256, 158).cuboid(-13f, -21f, -2f, 26f, 42f, 10f, Dilation.NONE, S, S)
                .uv(110, 371).cuboid(-19f, -12f, 8f, 38f, 23f, 8f, Dilation.NONE, S, S)
                .uv(248, 261).cuboid(-16f, -16f, 8f, 32f, 31f, 8f, Dilation.NONE, S, S)
                .uv(212, 215).cuboid(-12f, -19f, 8f, 24f, 37f, 8f, Dilation.NONE, S, S)
                .uv(96, 482).cuboid(-4f, -24f, -1f, 8f, 5f, 14f, Dilation.NONE, S, S)
                .uv(104, 517).cuboid(-2f, -26f, 2f, 4f, 2f, 8f, Dilation.NONE, S, S)
                .uv(28, 527).cuboid(-1f, -28f, 5f, 2f, 2f, 2f, Dilation.NONE, S, S)
                .uv(370, 527).cuboid(-0.5f, -29f, 5.5f, 1f, 1f, 1f, Dilation.NONE, S, S)
                .uv(370, 527).cuboid(-0.5f, -30f, 5.5f, 1f, 1f, 1f, Dilation.NONE, S, S)
                .uv(322, 527).cuboid(-8f, -22f, 2f, 2f, 1f, 2f, Dilation.NONE, S, S)
                .uv(370, 527).cuboid(-7.5f, -23f, 2.5f, 1f, 1f, 1f, Dilation.NONE, S, S)
                .uv(370, 527).cuboid(-7.5f, -24f, 2.5f, 1f, 1f, 1f, Dilation.NONE, S, S),
            ModelTransform.of(0f, -1f, 18f, 0f, 0f, 0f),
        )
        val p_tail1 = p_hip.addChild(
            "tail1",
            ModelPartBuilder.create()
                .uv(202, 371).cuboid(-17f, -11f, -2f, 34f, 22f, 9f, Dilation.NONE, S, S)
                .uv(328, 261).cuboid(-15f, -15f, -2f, 30f, 30f, 9f, Dilation.NONE, S, S)
                .uv(276, 215).cuboid(-11f, -17f, -2f, 22f, 34f, 9f, Dilation.NONE, S, S)
                .uv(60, 433).cuboid(-14f, -10f, 7f, 28f, 19f, 7f, Dilation.NONE, S, S)
                .uv(0, 371).cuboid(-12f, -13f, 7f, 24f, 25f, 7f, Dilation.NONE, S, S)
                .uv(560, 300).cuboid(-9f, -15f, 7f, 18f, 29f, 7f, Dilation.NONE, S, S)
                .uv(360, 517).cuboid(-2f, -19f, 0f, 4f, 2f, 5f, Dilation.NONE, S, S)
                .uv(450, 517).cuboid(-2f, -17f, 8f, 4f, 2f, 4f, Dilation.NONE, S, S)
                .uv(322, 527).cuboid(4f, -18f, 2f, 2f, 1f, 2f, Dilation.NONE, S, S)
                .uv(370, 527).cuboid(4.5f, -19f, 2.5f, 1f, 1f, 1f, Dilation.NONE, S, S)
                .uv(370, 527).cuboid(4.5f, -20f, 2.5f, 1f, 1f, 1f, Dilation.NONE, S, S),
            ModelTransform.of(0f, -1f, 16f, 0f, 0f, 0f),
        )
        val p_tail2 = p_tail1.addChild(
            "tail2",
            ModelPartBuilder.create()
                .uv(294, 433).cuboid(-12f, -8f, -2f, 24f, 16f, 8f, Dilation.NONE, S, S)
                .uv(62, 371).cuboid(-8f, -12f, -2f, 16f, 24f, 8f, Dilation.NONE, S, S)
                .uv(566, 459).cuboid(-10f, -7f, 6f, 20f, 14f, 6f, Dilation.NONE, S, S)
                .uv(130, 433).cuboid(-7f, -10f, 6f, 14f, 20f, 6f, Dilation.NONE, S, S)
                .uv(466, 517).cuboid(-1.5f, -14f, 1f, 3f, 2f, 4f, Dilation.NONE, S, S),
            ModelTransform.of(0f, -1f, 14f, 0f, 0f, 0f),
        )
        val p_tail3 = p_tail2.addChild(
            "tail3",
            ModelPartBuilder.create()
                .uv(294, 482).cuboid(-8f, -5f, -2f, 16f, 10f, 8f, Dilation.NONE, S, S)
                .uv(358, 433).cuboid(-5f, -8f, -2f, 10f, 16f, 8f, Dilation.NONE, S, S)
                .uv(598, 482).cuboid(-6f, -4f, 6f, 12f, 8f, 8f, Dilation.NONE, S, S)
                .uv(618, 459).cuboid(-4f, -6f, 6f, 8f, 12f, 8f, Dilation.NONE, S, S),
            ModelTransform.of(0f, 0f, 12f, 0f, 0f, 0f),
        )
        val p_fluke = p_tail3.addChild(
            "fluke",
            ModelPartBuilder.create()
                .uv(606, 433).cuboid(-7f, -4f, -2f, 14f, 8f, 15f, Dilation.NONE, S, S),
            ModelTransform.of(0f, 0f, 12f, 0f, 0f, 0f),
        )
        val p_fluke_r = p_fluke.addChild(
            "fluke_r",
            ModelPartBuilder.create()
                .uv(20, 459).cuboid(-9f, -3f, -2f, 9f, 5f, 17f, Dilation.NONE, S, S)
                .uv(524, 459).cuboid(-13f, -2.5f, 0f, 4f, 4f, 17f, Dilation.NONE, S, S)
                .uv(152, 502).cuboid(-17f, -2.5f, 0f, 4f, 4f, 10f, Dilation.NONE, S, S),
            ModelTransform.of(-5f, 0f, 2f, 0f, 0f, 0f),
        )
        val p_fluke_r2 = p_fluke_r.addChild(
            "fluke_r2",
            ModelPartBuilder.create()
                .uv(140, 482).cuboid(-7f, -1.5f, -4f, 7f, 3f, 16f, Dilation.NONE, S, S)
                .uv(642, 482).cuboid(-12f, -1f, 1f, 5f, 2f, 13f, Dilation.NONE, S, S)
                .uv(192, 517).cuboid(-15f, -0.5f, 6f, 3f, 1f, 8f, Dilation.NONE, S, S),
            ModelTransform.of(-17f, -0.5f, 8f, 0f, 0f, 0f),
        )
        val p_fluke_l = p_fluke.addChild(
            "fluke_l",
            ModelPartBuilder.create()
                .uv(72, 459).cuboid(0f, -3f, -2f, 9f, 5f, 17f, Dilation.NONE, S, S)
                .uv(124, 459).cuboid(9f, -2.5f, 0f, 8f, 4f, 18f, Dilation.NONE, S, S),
            ModelTransform.of(5f, 0f, 2f, 0f, 0f, 0f),
        )
        val p_fluke_l2 = p_fluke_l.addChild(
            "fluke_l2",
            ModelPartBuilder.create()
                .uv(186, 482).cuboid(0f, -1.5f, -4f, 7f, 3f, 16f, Dilation.NONE, S, S)
                .uv(0, 502).cuboid(7f, -1f, 1f, 5f, 2f, 13f, Dilation.NONE, S, S)
                .uv(214, 517).cuboid(12f, -0.5f, 6f, 3f, 1f, 8f, Dilation.NONE, S, S),
            ModelTransform.of(17f, -0.5f, 8f, 0f, 0f, 0f),
        )
        val p_fin_r = p_chest.addChild(
            "fin_r",
            ModelPartBuilder.create()
                .uv(394, 433).cuboid(-16f, -2f, -10f, 18f, 4f, 20f, Dilation.NONE, S, S)
                .uv(196, 527).cuboid(-5f, -1f, -11f, 2f, 2f, 1f, Dilation.NONE, S, S)
                .uv(342, 527).cuboid(-4.5f, -0.5f, -12f, 1f, 1f, 1f, Dilation.NONE, S, S)
                .uv(342, 527).cuboid(-4.5f, -0.5f, -13f, 1f, 1f, 1f, Dilation.NONE, S, S)
                .uv(558, 517).cuboid(-10.5f, -1.5f, -12f, 3f, 3f, 2f, Dilation.NONE, S, S)
                .uv(196, 527).cuboid(-10f, -1f, -13f, 2f, 2f, 1f, Dilation.NONE, S, S)
                .uv(342, 527).cuboid(-9.5f, -0.5f, -14f, 1f, 1f, 1f, Dilation.NONE, S, S),
            ModelTransform.of(-25f, 13f, -12f, 0f, 0f, 0f),
        )
        val p_fin_r2 = p_fin_r.addChild(
            "fin_r2",
            ModelPartBuilder.create()
                .uv(342, 482).cuboid(-13f, -1.5f, -8f, 15f, 3f, 15f, Dilation.NONE, S, S)
                .uv(196, 527).cuboid(-6f, -1f, -9f, 2f, 2f, 1f, Dilation.NONE, S, S)
                .uv(342, 527).cuboid(-5.5f, -0.5f, -10f, 1f, 1f, 1f, Dilation.NONE, S, S)
                .uv(342, 527).cuboid(-5.5f, -0.5f, -11f, 1f, 1f, 1f, Dilation.NONE, S, S)
                .uv(196, 527).cuboid(-11f, -1f, -9f, 2f, 2f, 1f, Dilation.NONE, S, S)
                .uv(342, 527).cuboid(-10.5f, -0.5f, -10f, 1f, 1f, 1f, Dilation.NONE, S, S),
            ModelTransform.of(-16f, 0f, 0f, 0f, 0f, 0f),
        )
        val p_fin_r3 = p_fin_r2.addChild(
            "fin_r3",
            ModelPartBuilder.create()
                .uv(458, 502).cuboid(-9f, -1f, -5f, 11f, 2f, 10f, Dilation.NONE, S, S)
                .uv(378, 517).cuboid(-13f, -0.5f, -2f, 6f, 1f, 6f, Dilation.NONE, S, S),
            ModelTransform.of(-13f, 0f, 0f, 0f, 0f, 0f),
        )
        val p_fin_l = p_chest.addChild(
            "fin_l",
            ModelPartBuilder.create()
                .uv(470, 433).cuboid(-2f, -2f, -10f, 18f, 4f, 20f, Dilation.NONE, S, S)
                .uv(196, 527).cuboid(3f, -1f, -11f, 2f, 2f, 1f, Dilation.NONE, S, S)
                .uv(342, 527).cuboid(3.5f, -0.5f, -12f, 1f, 1f, 1f, Dilation.NONE, S, S)
                .uv(342, 527).cuboid(3.5f, -0.5f, -13f, 1f, 1f, 1f, Dilation.NONE, S, S)
                .uv(558, 517).cuboid(9.5f, -1.5f, -12f, 3f, 3f, 2f, Dilation.NONE, S, S)
                .uv(196, 527).cuboid(10f, -1f, -13f, 2f, 2f, 1f, Dilation.NONE, S, S)
                .uv(342, 527).cuboid(10.5f, -0.5f, -14f, 1f, 1f, 1f, Dilation.NONE, S, S),
            ModelTransform.of(25f, 13f, -12f, 0f, 0f, 0f),
        )
        val p_fin_l2 = p_fin_l.addChild(
            "fin_l2",
            ModelPartBuilder.create()
                .uv(402, 482).cuboid(-2f, -1.5f, -8f, 15f, 3f, 15f, Dilation.NONE, S, S)
                .uv(196, 527).cuboid(4f, -1f, -9f, 2f, 2f, 1f, Dilation.NONE, S, S)
                .uv(342, 527).cuboid(4.5f, -0.5f, -10f, 1f, 1f, 1f, Dilation.NONE, S, S)
                .uv(342, 527).cuboid(4.5f, -0.5f, -11f, 1f, 1f, 1f, Dilation.NONE, S, S),
            ModelTransform.of(16f, 0f, 0f, 0f, 0f, 0f),
        )
        val p_fin_l3 = p_fin_l2.addChild(
            "fin_l3",
            ModelPartBuilder.create()
                .uv(500, 502).cuboid(-2f, -1f, -5f, 11f, 2f, 10f, Dilation.NONE, S, S)
                .uv(402, 517).cuboid(7f, -0.5f, -2f, 6f, 1f, 6f, Dilation.NONE, S, S),
            ModelTransform.of(13f, 0f, 0f, 0f, 0f, 0f),
        )
        val p_heart = p_chest.addChild(
            "heart",
            ModelPartBuilder.create()
                .uv(180, 502).cuboid(-5f, -3f, -5f, 10f, 4f, 10f, Dilation.NONE, S, S)
                .uv(462, 482).cuboid(-7f, 1f, -7f, 14f, 4f, 14f, Dilation.NONE, S, S)
                .uv(418, 502).cuboid(-5f, 5f, -5f, 10f, 3f, 10f, Dilation.NONE, S, S)
                .uv(280, 517).cuboid(-3f, 8f, -3f, 6f, 2f, 6f, Dilation.NONE, S, S)
                .uv(660, 517).cuboid(-1f, 10f, -1f, 2f, 2f, 2f, Dilation.NONE, S, S)
                .uv(202, 527).cuboid(-19f, 0f, -1f, 38f, 1f, 2f, Dilation.NONE, S, S)
                .uv(104, 336).cuboid(-1f, 0f, -17f, 2f, 1f, 34f, Dilation.NONE, S, S)
                .uv(480, 517).cuboid(-11.5f, -1f, -1.5f, 3f, 3f, 3f, Dilation.NONE, S, S)
                .uv(668, 517).cuboid(-11f, 2f, -1f, 2f, 2f, 2f, Dilation.NONE, S, S)
                .uv(282, 527).cuboid(-10.5f, 4f, -0.5f, 1f, 2f, 1f, Dilation.NONE, S, S)
                .uv(480, 517).cuboid(-1.5f, -1f, -11.5f, 3f, 3f, 3f, Dilation.NONE, S, S)
                .uv(286, 527).cuboid(-1f, 2f, -11f, 2f, 1f, 2f, Dilation.NONE, S, S)
                .uv(282, 527).cuboid(-0.5f, 3f, -10.5f, 1f, 2f, 1f, Dilation.NONE, S, S)
                .uv(480, 517).cuboid(8.5f, -1f, -1.5f, 3f, 3f, 3f, Dilation.NONE, S, S)
                .uv(668, 517).cuboid(9f, 2f, -1f, 2f, 2f, 2f, Dilation.NONE, S, S)
                .uv(282, 527).cuboid(9.5f, 4f, -0.5f, 1f, 2f, 1f, Dilation.NONE, S, S)
                .uv(480, 517).cuboid(-1.5f, -1f, 8.5f, 3f, 3f, 3f, Dilation.NONE, S, S)
                .uv(286, 527).cuboid(-1f, 2f, 9f, 2f, 1f, 2f, Dilation.NONE, S, S)
                .uv(282, 527).cuboid(-0.5f, 3f, 9.5f, 1f, 2f, 1f, Dilation.NONE, S, S),
            ModelTransform.of(0f, 27f, -10f, 0f, 0f, 0f),
        )
        val p_heart_flare = p_heart.addChild(
            "heart_flare",
            ModelPartBuilder.create()
                .uv(176, 336).cuboid(-17f, 0f, -17f, 34f, 0f, 34f, Dilation.NONE, S, S)
                .uv(312, 336).cuboid(-17f, -17f, 0f, 34f, 34f, 0f, Dilation.NONE, S, S)
                .uv(176, 90).cuboid(0f, -17f, -17f, 0f, 34f, 34f, Dilation.NONE, S, S),
            ModelTransform.of(0f, 4f, 0f, 0f, 0f, 0f),
        )
        val p_harp1 = p_hip.addChild(
            "harp1",
            ModelPartBuilder.create()
                .uv(116, 403).cuboid(-0.5f, -26f, -0.5f, 1f, 28f, 1f, Dilation.NONE, S, S)
                .uv(514, 517).cuboid(-1f, -4f, -1f, 2f, 4f, 2f, Dilation.NONE, S, S)
                .uv(36, 527).cuboid(-1f, -27f, -1f, 2f, 2f, 2f, Dilation.NONE, S, S)
                .uv(44, 527).cuboid(-1f, -23f, -1f, 2f, 2f, 2f, Dilation.NONE, S, S),
            ModelTransform.of(6f, -21f, 5f, -0.5f, 0f, 0.22f),
        )
        val p_harp1_rope1 = p_harp1.addChild(
            "harp1_rope1",
            ModelPartBuilder.create()
                .uv(304, 517).cuboid(-0.5f, 0f, -0.5f, 1f, 7f, 1f, Dilation.NONE, S, S),
            ModelTransform.of(0f, -22f, 1f, 0f, 0f, 0f),
        )
        val p_harp1_rope2 = p_harp1_rope1.addChild(
            "harp1_rope2",
            ModelPartBuilder.create()
                .uv(304, 517).cuboid(-0.5f, 0f, -0.5f, 1f, 7f, 1f, Dilation.NONE, S, S),
            ModelTransform.of(0f, 7f, 0f, 0f, 0f, 0f),
        )
        val p_harp1_rope3 = p_harp1_rope2.addChild(
            "harp1_rope3",
            ModelPartBuilder.create()
                .uv(522, 517).cuboid(-0.5f, 0f, -0.5f, 1f, 5f, 1f, Dilation.NONE, S, S)
                .uv(52, 527).cuboid(-1f, 5f, -1f, 2f, 2f, 2f, Dilation.NONE, S, S),
            ModelTransform.of(0f, 7f, 0f, 0f, 0f, 0f),
        )
        val p_harp2 = p_chest_skin.addChild(
            "harp2",
            ModelPartBuilder.create()
                .uv(546, 433).cuboid(-0.5f, -21f, -0.5f, 1f, 23f, 1f, Dilation.NONE, S, S)
                .uv(514, 517).cuboid(-1f, -4f, -1f, 2f, 4f, 2f, Dilation.NONE, S, S)
                .uv(44, 527).cuboid(-1f, -18f, -1f, 2f, 2f, 2f, Dilation.NONE, S, S),
            ModelTransform.of(26f, -6f, 8f, -0.35f, 0f, 1.05f),
        )
        val p_harp2_rope1 = p_harp2.addChild(
            "harp2_rope1",
            ModelPartBuilder.create()
                .uv(304, 517).cuboid(-0.5f, 0f, -0.5f, 1f, 7f, 1f, Dilation.NONE, S, S),
            ModelTransform.of(0f, -17f, 1f, 0f, 0f, 0f),
        )
        val p_harp2_rope2 = p_harp2_rope1.addChild(
            "harp2_rope2",
            ModelPartBuilder.create()
                .uv(304, 517).cuboid(-0.5f, 0f, -0.5f, 1f, 7f, 1f, Dilation.NONE, S, S),
            ModelTransform.of(0f, 7f, 0f, 0f, 0f, 0f),
        )
        val p_harp2_rope3 = p_harp2_rope2.addChild(
            "harp2_rope3",
            ModelPartBuilder.create()
                .uv(522, 517).cuboid(-0.5f, 0f, -0.5f, 1f, 5f, 1f, Dilation.NONE, S, S)
                .uv(52, 527).cuboid(-1f, 5f, -1f, 2f, 2f, 2f, Dilation.NONE, S, S),
            ModelTransform.of(0f, 7f, 0f, 0f, 0f, 0f),
        )
        val p_harp3 = p_head.addChild(
            "harp3",
            ModelPartBuilder.create()
                .uv(542, 502).cuboid(-0.5f, -9f, -0.5f, 1f, 11f, 1f, Dilation.NONE, S, S)
                .uv(514, 517).cuboid(-1f, -4f, -1f, 2f, 4f, 2f, Dilation.NONE, S, S),
            ModelTransform.of(9f, -23f, -8f, 0.3f, 0f, 0.4f),
        )
        val p_harp4 = p_chest_skin.addChild(
            "harp4",
            ModelPartBuilder.create()
                .uv(170, 433).cuboid(-0.5f, -23f, -0.5f, 1f, 25f, 1f, Dilation.NONE, S, S)
                .uv(514, 517).cuboid(-1f, -4f, -1f, 2f, 4f, 2f, Dilation.NONE, S, S)
                .uv(36, 527).cuboid(-1f, -24f, -1f, 2f, 2f, 2f, Dilation.NONE, S, S)
                .uv(44, 527).cuboid(-1f, -20f, -1f, 2f, 2f, 2f, Dilation.NONE, S, S),
            ModelTransform.of(-17f, -23f, -5f, -0.4f, 0f, -0.5f),
        )
        val p_harp4_rope1 = p_harp4.addChild(
            "harp4_rope1",
            ModelPartBuilder.create()
                .uv(304, 517).cuboid(-0.5f, 0f, -0.5f, 1f, 7f, 1f, Dilation.NONE, S, S),
            ModelTransform.of(0f, -19f, 1f, 0f, 0f, 0f),
        )
        val p_harp4_rope2 = p_harp4_rope1.addChild(
            "harp4_rope2",
            ModelPartBuilder.create()
                .uv(304, 517).cuboid(-0.5f, 0f, -0.5f, 1f, 7f, 1f, Dilation.NONE, S, S),
            ModelTransform.of(0f, 7f, 0f, 0f, 0f, 0f),
        )
        val p_harp4_rope3 = p_harp4_rope2.addChild(
            "harp4_rope3",
            ModelPartBuilder.create()
                .uv(522, 517).cuboid(-0.5f, 0f, -0.5f, 1f, 5f, 1f, Dilation.NONE, S, S)
                .uv(52, 527).cuboid(-1f, 5f, -1f, 2f, 2f, 2f, Dilation.NONE, S, S),
            ModelTransform.of(0f, 7f, 0f, 0f, 0f, 0f),
        )
        val p_harp5 = p_tail1.addChild(
            "harp5",
            ModelPartBuilder.create()
                .uv(638, 482).cuboid(-0.5f, -13f, -0.5f, 1f, 15f, 1f, Dilation.NONE, S, S)
                .uv(514, 517).cuboid(-1f, -4f, -1f, 2f, 4f, 2f, Dilation.NONE, S, S)
                .uv(44, 527).cuboid(-1f, -10f, -1f, 2f, 2f, 2f, Dilation.NONE, S, S),
            ModelTransform.of(-16f, -4f, 4f, -0.3f, 0f, -1.25f),
        )
        val p_harp5_rope1 = p_harp5.addChild(
            "harp5_rope1",
            ModelPartBuilder.create()
                .uv(304, 517).cuboid(-0.5f, 0f, -0.5f, 1f, 7f, 1f, Dilation.NONE, S, S),
            ModelTransform.of(0f, -9f, 1f, 0f, 0f, 0f),
        )
        val p_harp5_rope2 = p_harp5_rope1.addChild(
            "harp5_rope2",
            ModelPartBuilder.create()
                .uv(522, 517).cuboid(-0.5f, 0f, -0.5f, 1f, 5f, 1f, Dilation.NONE, S, S)
                .uv(52, 527).cuboid(-1f, 5f, -1f, 2f, 2f, 2f, Dilation.NONE, S, S),
            ModelTransform.of(0f, 7f, 0f, 0f, 0f, 0f),
        )
        val p_bulge = p_chest_skin.addChild(
            "bulge",
            ModelPartBuilder.create()
                .uv(76, 158).cuboid(-8f, -13f, -15f, 8f, 26f, 30f, Dilation.NONE, S, S)
                .uv(534, 215).cuboid(-11f, -9f, -11f, 3f, 18f, 22f, Dilation.NONE, S, S)
                .uv(176, 459).cuboid(-13f, -5f, -6f, 2f, 10f, 12f, Dilation.NONE, S, S),
            ModelTransform.of(-22f, 0f, 4f, 0f, 0f, 0f),
        )
        val p_wound = p_chest_skin.addChild(
            "wound",
            ModelPartBuilder.create()
                .uv(328, 158).cuboid(-1f, -12f, -14f, 0f, 24f, 28f, Dilation.NONE, S, S)
                .uv(60, 527).cuboid(-6f, -6f, -8f, 6f, 2f, 2f, Dilation.NONE, S, S)
                .uv(624, 517).cuboid(-8f, -8f, -8f, 2f, 3f, 2f, Dilation.NONE, S, S)
                .uv(76, 527).cuboid(-5f, 2f, -1f, 5f, 2f, 2f, Dilation.NONE, S, S)
                .uv(90, 527).cuboid(-7f, -1f, 6f, 7f, 2f, 2f, Dilation.NONE, S, S)
                .uv(632, 517).cuboid(-9f, -3f, 6f, 2f, 3f, 2f, Dilation.NONE, S, S),
            ModelTransform.of(-26f, 0f, 4f, 0f, 0f, 0f),
        )
        val p_flap_t = p_wound.addChild(
            "flap_t",
            ModelPartBuilder.create()
                .uv(200, 403).cuboid(-1f, 0f, -10f, 1f, 10f, 18f, Dilation.NONE, S, S),
            ModelTransform.of(-1f, -11f, 0f, 0f, 0f, 0f),
        )
        val p_flap_b = p_wound.addChild(
            "flap_b",
            ModelPartBuilder.create()
                .uv(238, 403).cuboid(-1f, -10f, -8f, 1f, 10f, 18f, Dilation.NONE, S, S),
            ModelTransform.of(-1f, 11f, 0f, 0f, 0f, 0f),
        )
        val p_flap_f = p_wound.addChild(
            "flap_f",
            ModelPartBuilder.create()
                .uv(204, 459).cuboid(-1f, -7f, 0f, 1f, 13f, 9f, Dilation.NONE, S, S),
            ModelTransform.of(-1f, 0f, -13f, 0f, 0f, 0f),
        )
        val p_flap_k = p_wound.addChild(
            "flap_k",
            ModelPartBuilder.create()
                .uv(0, 459).cuboid(-1f, -6f, -9f, 1f, 14f, 9f, Dilation.NONE, S, S),
            ModelTransform.of(-1f, 0f, 13f, 0f, 0f, 0f),
        )
        val p_wound_rays = p_wound.addChild(
            "wound_rays",
            ModelPartBuilder.create()
                .uv(174, 433).cuboid(-34f, 0f, -13f, 34f, 0f, 26f, Dilation.NONE, S, S)
                .uv(224, 459).cuboid(-34f, -11f, 0f, 34f, 22f, 0f, Dilation.NONE, S, S),
            ModelTransform.of(-1f, 0f, 0f, 0f, 0f, 0f),
        )
        val p_gorge_c = p_chest_skin.addChild(
            "gorge_c",
            ModelPartBuilder.create()
                .uv(324, 90).cuboid(27f, -15f, -16f, 0f, 30f, 32f, Dilation.NONE, S, S)
                .uv(388, 90).cuboid(-27f, -15f, -16f, 0f, 30f, 32f, Dilation.NONE, S, S)
                .uv(220, 502).cuboid(-15f, 28f, 2f, 30f, 0f, 14f, Dilation.NONE, S, S),
            ModelTransform.of(0f, 0f, 0f, 0f, 0f, 0f),
        )
        val p_gorge_b = p_belly_skin.addChild(
            "gorge_b",
            ModelPartBuilder.create()
                .uv(576, 158).cuboid(26f, -15f, -10f, 0f, 30f, 20f, Dilation.NONE, S, S)
                .uv(616, 158).cuboid(-26f, -15f, -10f, 0f, 30f, 20f, Dilation.NONE, S, S)
                .uv(0, 482).cuboid(-14f, 27f, -10f, 28f, 0f, 20f, Dilation.NONE, S, S),
            ModelTransform.of(0f, 0f, 0f, 0f, 0f, 0f),
        )
        return TexturedModelData.of(data, 2040, 1632)
    }

    /** Weg jedes Teils von der Wurzel aus. */
    private val PATHS = mapOf(
        "root" to "root",
        "body" to "root/body",
        "chest" to "root/body/chest",
        "chest_skin" to "root/body/chest/chest_skin",
        "neck" to "root/body/chest/neck",
        "neck_skin" to "root/body/chest/neck/neck_skin",
        "pn0" to "root/body/chest/neck/neck_skin/pn0",
        "pn1" to "root/body/chest/neck/neck_skin/pn1",
        "pn2" to "root/body/chest/neck/neck_skin/pn2",
        "pn3" to "root/body/chest/neck/neck_skin/pn3",
        "pn4" to "root/body/chest/neck/neck_skin/pn4",
        "pn5" to "root/body/chest/neck/neck_skin/pn5",
        "blow_r" to "root/body/chest/neck/neck_skin/blow_r",
        "blow_l" to "root/body/chest/neck/neck_skin/blow_l",
        "throat_glow" to "root/body/chest/neck/throat_glow",
        "head" to "root/body/chest/neck/head",
        "halo_r" to "root/body/chest/neck/head/halo_r",
        "halo_l" to "root/body/chest/neck/head/halo_l",
        "jaw" to "root/body/chest/neck/head/jaw",
        "throat" to "root/body/chest/neck/head/jaw/throat",
        "pj0" to "root/body/chest/neck/head/jaw/throat/pj0",
        "pj1" to "root/body/chest/neck/head/jaw/throat/pj1",
        "pj2" to "root/body/chest/neck/head/jaw/throat/pj2",
        "pj3" to "root/body/chest/neck/head/jaw/throat/pj3",
        "pj4" to "root/body/chest/neck/head/jaw/throat/pj4",
        "pj5" to "root/body/chest/neck/head/jaw/throat/pj5",
        "cheek_r" to "root/body/chest/neck/head/cheek_r",
        "cheek_l" to "root/body/chest/neck/head/cheek_l",
        "belly" to "root/body/chest/belly",
        "belly_skin" to "root/body/chest/belly/belly_skin",
        "hip" to "root/body/chest/belly/hip",
        "tail1" to "root/body/chest/belly/hip/tail1",
        "tail2" to "root/body/chest/belly/hip/tail1/tail2",
        "tail3" to "root/body/chest/belly/hip/tail1/tail2/tail3",
        "fluke" to "root/body/chest/belly/hip/tail1/tail2/tail3/fluke",
        "fluke_r" to "root/body/chest/belly/hip/tail1/tail2/tail3/fluke/fluke_r",
        "fluke_r2" to "root/body/chest/belly/hip/tail1/tail2/tail3/fluke/fluke_r/fluke_r2",
        "fluke_l" to "root/body/chest/belly/hip/tail1/tail2/tail3/fluke/fluke_l",
        "fluke_l2" to "root/body/chest/belly/hip/tail1/tail2/tail3/fluke/fluke_l/fluke_l2",
        "fin_r" to "root/body/chest/fin_r",
        "fin_r2" to "root/body/chest/fin_r/fin_r2",
        "fin_r3" to "root/body/chest/fin_r/fin_r2/fin_r3",
        "fin_l" to "root/body/chest/fin_l",
        "fin_l2" to "root/body/chest/fin_l/fin_l2",
        "fin_l3" to "root/body/chest/fin_l/fin_l2/fin_l3",
        "heart" to "root/body/chest/heart",
        "heart_flare" to "root/body/chest/heart/heart_flare",
        "harp1" to "root/body/chest/belly/hip/harp1",
        "harp1_rope1" to "root/body/chest/belly/hip/harp1/harp1_rope1",
        "harp1_rope2" to "root/body/chest/belly/hip/harp1/harp1_rope1/harp1_rope2",
        "harp1_rope3" to "root/body/chest/belly/hip/harp1/harp1_rope1/harp1_rope2/harp1_rope3",
        "harp2" to "root/body/chest/chest_skin/harp2",
        "harp2_rope1" to "root/body/chest/chest_skin/harp2/harp2_rope1",
        "harp2_rope2" to "root/body/chest/chest_skin/harp2/harp2_rope1/harp2_rope2",
        "harp2_rope3" to "root/body/chest/chest_skin/harp2/harp2_rope1/harp2_rope2/harp2_rope3",
        "harp3" to "root/body/chest/neck/head/harp3",
        "harp4" to "root/body/chest/chest_skin/harp4",
        "harp4_rope1" to "root/body/chest/chest_skin/harp4/harp4_rope1",
        "harp4_rope2" to "root/body/chest/chest_skin/harp4/harp4_rope1/harp4_rope2",
        "harp4_rope3" to "root/body/chest/chest_skin/harp4/harp4_rope1/harp4_rope2/harp4_rope3",
        "harp5" to "root/body/chest/belly/hip/tail1/harp5",
        "harp5_rope1" to "root/body/chest/belly/hip/tail1/harp5/harp5_rope1",
        "harp5_rope2" to "root/body/chest/belly/hip/tail1/harp5/harp5_rope1/harp5_rope2",
        "bulge" to "root/body/chest/chest_skin/bulge",
        "wound" to "root/body/chest/chest_skin/wound",
        "flap_t" to "root/body/chest/chest_skin/wound/flap_t",
        "flap_b" to "root/body/chest/chest_skin/wound/flap_b",
        "flap_f" to "root/body/chest/chest_skin/wound/flap_f",
        "flap_k" to "root/body/chest/chest_skin/wound/flap_k",
        "wound_rays" to "root/body/chest/chest_skin/wound/wound_rays",
        "gorge_c" to "root/body/chest/chest_skin/gorge_c",
        "gorge_b" to "root/body/chest/belly/belly_skin/gorge_b",
    )

    fun part(root: ModelPart, name: String): ModelPart =
        PATHS.getValue(name).split('/').fold(root) { p, n -> p.getChild(n) }
}
