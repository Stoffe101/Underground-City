package io.github.stoffe101.undergroundcity;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;

/** Puts the civilian counter in front of Q Shop's screen for Core's own shops (see {@link CivilianShopBridge}). Does nothing unless the pinned Q Shop is loaded. */
@Mod.EventBusSubscriber(modid = UndergroundCity.MOD_ID, value = Dist.CLIENT)
public final class CivilianShopHook {
    private static final boolean ENABLED = !"false".equals(System.getProperty("uc.civilianShop", "true"));
    /** Set by the visual-capture harness around the provider's own screens, which are kept as evidence of the provider. */
    static volatile boolean suspended;
    private CivilianShopHook() {}

    @SubscribeEvent
    public static void opening(ScreenEvent.Opening event) {
        if (!ENABLED || suspended || !QShopEconomy.available() || !ModList.get().isLoaded("qshop")) return;
        var replacement = CivilianShopBridge.replacement(event.getNewScreen());
        if (replacement != null) event.setNewScreen(replacement);
    }
}
