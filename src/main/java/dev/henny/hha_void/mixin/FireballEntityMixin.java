package dev.henny.hha_void.mixin;

import dev.henny.hha_void.boss.VoidBoss;
import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.FireballEntity;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Im Bosskampf sprengen Feuerbälle (auch die eigenen Meteore) keine Blöcke aus der Arena. */
@Mixin(FireballEntity.class)
public abstract class FireballEntityMixin {

    @ModifyArg(
        method = "onCollision",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/World;createExplosion(Lnet/minecraft/entity/Entity;DDDFZLnet/minecraft/world/World$ExplosionSourceType;)V"),
        index = 6
    )
    private World.ExplosionSourceType hha_void$sparePhysicalArena(World.ExplosionSourceType type) {
        Entity self = (Entity) (Object) this;
        return VoidBoss.isBossNearby(self) ? World.ExplosionSourceType.NONE : type;
    }
}
