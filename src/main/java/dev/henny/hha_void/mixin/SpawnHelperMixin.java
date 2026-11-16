package dev.henny.hha_void.mixin;

import dev.henny.hha_void.boss.ArenaRegistry;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.SpawnHelper;
import net.minecraft.world.biome.SpawnSettings;
import net.minecraft.world.gen.StructureAccessor;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keine natürlichen Monster-Spawns (Endermen) auf einer Arena der Sternentiefe – siehe ArenaRegistry. */
@Mixin(SpawnHelper.class)
public abstract class SpawnHelperMixin {
    @Inject(method = "canSpawn(Lnet/minecraft/server/world/ServerWorld;Lnet/minecraft/entity/SpawnGroup;Lnet/minecraft/world/gen/StructureAccessor;Lnet/minecraft/world/gen/chunk/ChunkGenerator;Lnet/minecraft/world/biome/SpawnSettings$SpawnEntry;Lnet/minecraft/util/math/BlockPos$Mutable;D)Z",
        at = @At("HEAD"), cancellable = true)
    private static void hha_void$noArenaSpawns(ServerWorld world, SpawnGroup group, StructureAccessor structures, ChunkGenerator generator,
                                              SpawnSettings.SpawnEntry entry, BlockPos.Mutable pos, double squaredDistance,
                                              CallbackInfoReturnable<Boolean> cir) {
        if (ArenaRegistry.blocksSpawn(world, group, pos)) cir.setReturnValue(false);
    }
}
