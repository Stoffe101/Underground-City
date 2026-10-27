package io.github.stoffe101.undergroundcity;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/** Opens a workshop station's screen, or refreshes it in place, from what the server sends (D-163). Display only. */
@OnlyIn(Dist.CLIENT)
final class ClientWorkshop {
    private ClientWorkshop() {}

    /** The last view, for the scenarios. */
    static volatile WorkshopNetwork.View last;

    static void view(WorkshopNetwork.View view) {
        last = view;
        Minecraft mc = Minecraft.getInstance();
        boolean answer = System.currentTimeMillis() - WorkshopNetwork.refreshedAt < 1500;   // most likely the answer to a refresh, not a right click on a station
        if (mc.screen instanceof WorkshopScreen open && open.pos().equals(view.pos())) open.update(view);
        else if (!answer) mc.setScreen(new WorkshopScreen(view));
    }
}
