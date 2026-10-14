package io.github.stoffe101.undergroundcity;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public final class ClientTerminal {
    private ClientTerminal() {}

    public static void open() {
        Minecraft.getInstance().setScreen(new TerminalScreen());
    }
}
