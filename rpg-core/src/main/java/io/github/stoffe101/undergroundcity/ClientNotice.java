package io.github.stoffe101.undergroundcity;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/** Client entry point for notices (kept apart so the server never loads client classes). */
@OnlyIn(Dist.CLIENT)
public final class ClientNotice {
    private ClientNotice() {}

    static void show(Notice.Kind kind, String title, String body) {
        Minecraft.getInstance().getToasts().addToast(new UcToast(kind, title, body));
    }
}
