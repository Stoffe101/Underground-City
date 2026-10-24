package io.github.stoffe101.undergroundcity;

import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/** Draws a townsperson with the player model and one of the original generated citizen skins ({@code textures/entity/citizen/c_000..c_199.png}; no player's skin and no third-party pixel). */
@OnlyIn(Dist.CLIENT)
public final class TownspersonRenderer extends HumanoidMobRenderer<TownspersonEntity, PlayerModel<TownspersonEntity>> {
    private static final ResourceLocation[] SKINS = new ResourceLocation[PopulationPlan.SKINS];
    static {
        for (int i = 0; i < SKINS.length; i++) SKINS[i] = ResourceLocation.fromNamespaceAndPath(UndergroundCity.MOD_ID, String.format("textures/entity/citizen/c_%03d.png", i));
    }

    public TownspersonRenderer(EntityRendererProvider.Context context) {
        super(context, new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER), false), 0.5f);
    }

    @Override public ResourceLocation getTextureLocation(TownspersonEntity entity) { return SKINS[Math.floorMod(entity.skin(), SKINS.length)]; }

    /** A person's build (D-162): from a little shorter to a little taller than the model, and a touch broader or slighter, so a crowd is not one height. */
    @Override protected void scale(TownspersonEntity entity, com.mojang.blaze3d.vertex.PoseStack pose, float partialTick) {
        int build = entity.build();
        float height = 0.92f + build * 0.016f, width = 0.95f + (build % 3) * 0.03f;
        pose.scale(width, height, width);
    }
}
