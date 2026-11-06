package dev.henny.hha_void.client

import dev.henny.hha.config.HhaConfig
import dev.henny.hha_void.VoidItems
import net.minecraft.client.render.entity.state.BipedEntityRenderState

/**
 * Full-Invis (Set-Passiv), Render-Seite: Trägt eine unsichtbare Entity das
 * volle Void-Set, wird ihre Rüstung gar nicht erst gerendert — für jeden
 * Betrachter, auch für die eigene F5-Ansicht.
 * Aufgerufen vom ArmorFeatureRenderer-Mixin.
 */
object VoidInvisRender {

    @JvmStatic
    fun shouldHideArmor(state: BipedEntityRenderState): Boolean {
        if (!state.invisible) return false
        if (!HhaConfig.enabled("hha_void.full_invis")) return false
        return state.equippedHeadStack.isOf(VoidItems.VOID_HELMET) &&
            state.equippedChestStack.isOf(VoidItems.VOID_CHESTPLATE) &&
            state.equippedLegsStack.isOf(VoidItems.VOID_LEGGINGS) &&
            state.equippedFeetStack.isOf(VoidItems.VOID_BOOTS)
    }
}
