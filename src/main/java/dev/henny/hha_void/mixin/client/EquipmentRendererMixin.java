package dev.henny.hha_void.mixin.client;

import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.render.LayeringTransform;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderSetup;
import net.minecraft.client.render.entity.equipment.EquipmentRenderer;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import java.util.HashMap;
import java.util.Map;

/**
 * Leuchtende Rüstungs-Trims: Die Void-Rüstung hat eine zweite Equipment-Ebene `hha_void:void_glow`
 * (nur die Trim-Pixel, siehe assets/hha_void/equipment/void.json). Diese Ebene wird mit der
 * "eyes"-Pipeline gezeichnet – der von Spinnen-/Endermen-Augen –, ignoriert also das Weltlicht und
 * leuchtet bei Tag und Nacht. Ihre Tönung pulsiert ruhig (hell <-> heller), wie ein langsamer Atemzug.
 *
 * Wichtig: Vanillas RenderLayers.eyes() reicht hier NICHT. Rüstung wird mit
 * LayeringTransform.VIEW_OFFSET_Z_LAYERING gezeichnet (minimal zur Kamera gezogen, gegen Z-Fighting mit
 * dem Körper); eine Ebene ohne dieses Layering läge dahinter und fiele komplett durch den Tiefentest.
 * Deshalb ein eigener RenderLayer: eyes-Pipeline + dasselbe Layering wie die Rüstung.
 *
 * Reine Optik. Die Full-Invis-Fähigkeit bricht bereits ArmorFeatureRenderer.render ab
 * (ArmorFeatureRendererMixin) – dann wird auch diese Ebene nie gezeichnet.
 */
@Mixin(EquipmentRenderer.class)
public abstract class EquipmentRendererMixin {

    private static final String RENDER =
        "render(Lnet/minecraft/client/render/entity/equipment/EquipmentModel$LayerType;Lnet/minecraft/registry/RegistryKey;Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lnet/minecraft/item/ItemStack;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;ILnet/minecraft/util/Identifier;II)V";

    /** Periodendauer des Pulses in Millisekunden */
    @Unique
    private static final long HHA_VOID_PULSE_MS = 2600L;

    /** gesetzt, während die Argumente des submitModel-Aufrufs der Glow-Ebene ausgewertet werden */
    @Unique
    private static boolean hhaVoid$glowPending = false;

    @Unique
    private static final Map<Identifier, RenderLayer> HHA_VOID_GLOW_LAYERS = new HashMap<>();

    @Unique
    private static RenderLayer hhaVoid$glow(Identifier texture) {
        return HHA_VOID_GLOW_LAYERS.computeIfAbsent(texture, t -> RenderLayer.of(
            "hha_void_armor_glow",
            RenderSetup.builder(RenderPipelines.ENTITY_EYES)
                .texture("Sampler0", t)
                .layeringTransform(LayeringTransform.VIEW_OFFSET_Z_LAYERING)
                .translucent()
                .build()
        ));
    }

    /*
     * WrapOperation statt Redirect: HHA_Blood leitet denselben Aufruf per @Redirect um. Zwei Redirects auf
     * dieselbe Stelle schließen sich aus (Mixin-Fehler -> keine Entity-Renderer -> Absturz beim Weltbeitritt).
     * WrapOperation lässt sich mit dem Redirect der anderen Mod verketten.
     */
    @WrapOperation(
        method = RENDER,
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/RenderLayers;armorCutoutNoCull(Lnet/minecraft/util/Identifier;)Lnet/minecraft/client/render/RenderLayer;")
    )
    private RenderLayer hhaVoid$glowLayer(Identifier texture, Operation<RenderLayer> original) {
        if ("hha_void".equals(texture.getNamespace()) && texture.getPath().endsWith("void_glow.png")) {
            hhaVoid$glowPending = true;
            return hhaVoid$glow(texture);
        }
        return original.call(texture);
    }

    @ModifyArg(
        method = RENDER,
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/command/RenderCommandQueue;submitModel(Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/RenderLayer;IIILnet/minecraft/client/texture/Sprite;ILnet/minecraft/client/render/command/ModelCommandRenderer$CrumblingOverlayCommand;)V"),
        index = 6
    )
    private int hhaVoid$glowPulse(int tint) {
        if (!hhaVoid$glowPending) return tint;
        hhaVoid$glowPending = false;
        // ruhiger Puls, ~2,6 s pro Zyklus, nie dunkler als 70 %
        double t = (System.currentTimeMillis() % HHA_VOID_PULSE_MS) / (double) HHA_VOID_PULSE_MS;
        double f = 0.70 + 0.30 * (0.5 + 0.5 * Math.sin(t * Math.PI * 2));
        int v = (int) Math.round(255 * f);
        return 0xFF000000 | (v << 16) | (v << 8) | v;
    }
}
