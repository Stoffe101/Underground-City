package io.github.stoffe101.undergroundcity;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public final class ClientTerminal {
    private ClientTerminal() {}

    /**
     * Opens the terminal. When the server refreshes a terminal that is already open (after a delivery, a purchase or a
     * skill unlock) the tab, filter and selection are carried over instead of snapping back to the first tab.
     */
    public static void open(BlockPos pos, TerminalData data) {
        Minecraft minecraft = Minecraft.getInstance();
        TerminalScreen.Carry carry = minecraft.screen instanceof TerminalScreen old && pos.equals(old.position()) ? old.carry() : null;
        minecraft.setScreen(new TerminalScreen(pos, data, carry));
    }
}
