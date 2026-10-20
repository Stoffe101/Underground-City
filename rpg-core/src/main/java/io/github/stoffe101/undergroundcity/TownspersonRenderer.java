package io.github.stoffe101.undergroundcity;

import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/** Draws a townsperson with the player model and one of the original generated citizen skins ({@code textures/entity/citizen/c_00..c_47.png}; no player's skin and no third-party pixel). */
@OnlyIn(Dist.CLIENT)
public final class TownspersonRenderer extends HumanoidMobRenderer<TownspersonEntity, PlayerModel<TownspersonEntity>> {
    private static final ResourceLocation[] SKINS = new ResourceLocation[PopulationPlan.SKINS];
    static {
        for (int i = 0; i < SKINS.length; i++) SKINS[i] = ResourceLocation.fromNamespaceAndPath(UndergroundCity.MOD_ID, String.format("textures/entity/citizen/c_%02d.png", i));
    }

    public TownspersonRenderer(EntityRendererProvider.Context context) {
        super(context, new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER), false), 0.5f);
    }

    @Override public ResourceLocation getTextureLocation(TownspersonEntity entity) { return SKINS[Math.floorMod(entity.skin(), SKINS.length)]; }
}
