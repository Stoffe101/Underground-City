package io.github.stoffe101.undergroundcity.mixin;

import io.github.stoffe101.undergroundcity.CityRegions;
import io.github.stoffe101.undergroundcity.WorldLayout;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * No vanilla structure starts in or near a city of the archipelago (D-106; {@code docs/technical/archipelago-world.md}).
 *
 * <p>Lost Cities refuses to build a city chunk when a village, a woodland mansion, a pyramid, an igloo, a swamp hut or an outpost starts in it (its own {@code avoidVillages} and
 * {@code avoidStructures} settings, which are on by default), and it does not generate a city chunk it was told to avoid even when the city plan says it is inside the city's circle.
 * The first real-client frames of the archipelago showed the consequence: a meadow with a ruined portal in the middle of Ironhaven, an 80 by 190 block hole in the plan the probes had
 * measured as city (the same hole was found, by the same probe, in the owner's seed-5908 world). This injection answers {@code createStructures} with nothing for a chunk inside the ring around
 * a city, so the city can never be refused by a structure; every structure farther out is generated as the game decides.
 *
 * <p>It only acts on the archipelago's own terrain ({@link WorldLayout#regional}); every other generator, the legacy seed-5908 world included, is untouched. The method is named twice because
 * the development runtime uses the official names and a production server the Searge name ({@code m_255037_}), as in {@link GroundPathNavigationMixin}; {@code defaultRequire} is 0 in the
 * mixin configuration, so a version without either name would simply not apply it, and the boot check and the city probe (a plan that disagrees with the generated chunk) say so.
 */
@Mixin(value = ChunkGenerator.class, remap = false)
public abstract class ChunkGeneratorStructuresMixin {
    @Inject(method = {"createStructures(Lnet/minecraft/core/RegistryAccess;Lnet/minecraft/world/level/chunk/ChunkGeneratorStructureState;Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplateManager;)V",
                      "m_255037_(Lnet/minecraft/core/RegistryAccess;Lnet/minecraft/world/level/chunk/ChunkGeneratorStructureState;Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplateManager;)V"},
            at = @At("HEAD"), cancellable = true, remap = false)
    private void underground_city$noStructuresNearACity(RegistryAccess registries, ChunkGeneratorStructureState state, StructureManager structures, ChunkAccess chunk,
                                                       StructureTemplateManager templates, CallbackInfo ci) {
        if ((Object) this instanceof NoiseBasedChunkGenerator noise && WorldLayout.regional(noise) && CityRegions.INSTANCE.excludesStructures(chunk.getPos())) ci.cancel();
    }
}
