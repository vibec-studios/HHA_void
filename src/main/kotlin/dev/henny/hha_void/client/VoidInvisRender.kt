package dev.henny.hha_void.client

import dev.henny.hha.config.HhaConfig
import dev.henny.hha_void.VoidItems
import dev.henny.hha_void.logic.FullInvis
import net.minecraft.client.MinecraftClient
import net.minecraft.client.render.entity.state.BipedEntityRenderState
import net.minecraft.client.render.entity.state.PlayerEntityRenderState

/**
 * Full-Invis (Set-Passiv), Render-Seite: Ist der eigene Spieler mit vollem Void-Set "echt"
 * unsichtbar (Trank, Void Collapse, Notriss – nicht die Nacht-Unsichtbarkeit), wird seine Rüstung
 * auch in der eigenen F5-Ansicht nicht gerendert. Für andere Spieler schickt der Server bereits
 * leere Ausrüstung (FullInvis), der Client weiß bei ihnen nicht, woher die Unsichtbarkeit stammt.
 * Aufgerufen vom ArmorFeatureRenderer-Mixin.
 */
object VoidInvisRender {

    @JvmStatic
    fun shouldHideArmor(state: BipedEntityRenderState): Boolean {
        if (!state.invisible) return false
        if (!HhaConfig.enabled("hha_void.full_invis")) return false
        val self = MinecraftClient.getInstance().player ?: return false
        if (state !is PlayerEntityRenderState || state.id != self.id) return false
        if (!FullInvis.hidesArmor(self)) return false
        return state.equippedHeadStack.isOf(VoidItems.VOID_HELMET) &&
            state.equippedChestStack.isOf(VoidItems.VOID_CHESTPLATE) &&
            state.equippedLegsStack.isOf(VoidItems.VOID_LEGGINGS) &&
            state.equippedFeetStack.isOf(VoidItems.VOID_BOOTS)
    }
}
