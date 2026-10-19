package io.github.stoffe101.undergroundcity.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Path;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A walking creature does not make the server thread wait for a chunk that has not been generated (D-096; {@code docs/technical/lost-cities-stalls.md}).
 *
 * <p>Minecraft 1.20.1's {@code GroundPathNavigation.createPath(BlockPos, int)} starts with {@code level.getBlockState(target)}, which is a blocking chunk read. Its parent class checks that the
 * chunk is loaded first ({@code getChunkNow}) and answers "no path" otherwise; this override does not. A villager that opens a door recomputes the path to the target it remembers, and when that
 * target lies in a chunk that is still waiting in the generation queue the whole server thread parks until the chunk system gets to it: ticks of 30 to 38 seconds were measured at the edge of a
 * bulk chunk request. This injection gives the same answer as the parent: no path now, without touching the chunk.
 *
 * <p>The method is named twice because the development runtime uses the official names and a production server the Searge name ({@code m_7864_}); the build does not generate a refmap for the
 * vanilla targets. {@code defaultRequire} is 0 in the mixin configuration, so a Minecraft version without either name would simply not apply it; the GameTest
 * {@code aWalkingCreatureDoesNotLoadAChunkToFindAPath} and the production boot check ({@code nav_unloaded_chunk}) fail then.
 */
@Mixin(value = GroundPathNavigation.class, remap = false)
public abstract class GroundPathNavigationMixin extends PathNavigation {
    protected GroundPathNavigationMixin(Mob mob, Level level) { super(mob, level); }

    @Inject(method = {"createPath(Lnet/minecraft/core/BlockPos;I)Lnet/minecraft/world/level/pathfinder/Path;", "m_7864_(Lnet/minecraft/core/BlockPos;I)Lnet/minecraft/world/level/pathfinder/Path;"},
            at = @At("HEAD"), cancellable = true, remap = false)
    private void underground_city$noPathIntoAChunkThatIsNotLoaded(BlockPos target, int reach, CallbackInfoReturnable<Path> cir) {
        if (this.level.getChunkSource().getChunkNow(SectionPos.blockToSectionCoord(target.getX()), SectionPos.blockToSectionCoord(target.getZ())) == null) cir.setReturnValue(null);
    }
}
