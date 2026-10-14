package io.github.stoffe101.undergroundcity;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public final class ClientTerminal {
    private ClientTerminal() {}

    public static void open(BlockPos pos, TerminalData data) {
        Minecraft.getInstance().setScreen(new TerminalScreen(pos, data));
    }
}
