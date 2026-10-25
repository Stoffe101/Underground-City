package io.github.stoffe101.undergroundcity;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.List;

/** The client's half of the Smith's hands-on work (eighth pass): opens the screens the server asks for and passes them what it sent. Decides nothing. */
@OnlyIn(Dist.CLIENT)
final class ClientSmith {
    private ClientSmith() {}

    static void turn(SmithNetwork.TurnView view) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof TurningScreen open) open.update(view);
        else mc.setScreen(new TurningScreen(view));
    }

    static void drive(SmithNetwork.DriveView view) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof DriveScreen open) open.update(view);
        else mc.setScreen(new DriveScreen(view));
    }

    static void calls(List<SmithWork.Call> calls) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof ServiceCallsScreen open) open.update(calls);
        else mc.setScreen(new ServiceCallsScreen(calls));
    }

    static void end(boolean done, String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof TurningScreen || mc.screen instanceof DriveScreen) mc.setScreen(null);
        if (mc.player != null) mc.player.displayClientMessage(Component.literal(text), false);
        lastEnd = text;
        lastDone = done;
        ends++;
    }

    /** The last ending, for the scenario. */
    static volatile String lastEnd = "";
    static volatile boolean lastDone;
    static volatile int ends;
}
