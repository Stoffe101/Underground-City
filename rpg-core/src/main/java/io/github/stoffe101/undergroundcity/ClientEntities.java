package io.github.stoffe101.undergroundcity;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Client-side registration of the renderers of Core's entities (kept apart so a dedicated server never loads a renderer class). */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = UndergroundCity.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ClientEntities {
    private ClientEntities() {}

    @SubscribeEvent
    public static void renderers(EntityRenderersEvent.RegisterRenderers event) { event.registerEntityRenderer(UndergroundCity.TOWNSPERSON.get(), TownspersonRenderer::new); }
}
