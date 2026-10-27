package io.github.stoffe101.undergroundcity;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.List;

/** The client's half of the Technician's trade (D-158): opens the screens the server asks for and passes them what it sent. Decides nothing. */
@OnlyIn(Dist.CLIENT)
final class ClientTech {
    private ClientTech() {}

    static void computer(List<TechWork.Call> calls, List<TechNetwork.DesignRow> designs, boolean technician, int level) {
        Minecraft.getInstance().setScreen(new ComputerScreen(calls, designs, technician, level));
    }

    static void bench(List<TechNetwork.DesignRow> designs, String note) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof BenchScreen open) open.update(designs, note);
        else mc.setScreen(new BenchScreen(designs, note));
    }

    static void trace(TechNetwork.TraceView view) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof TraceScreen open) open.update(view);
        else mc.setScreen(new TraceScreen(view));
    }

    static void patch(TechNetwork.PatchView view) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof PatchScreen open) open.update(view);
        else mc.setScreen(new PatchScreen(view));
    }

    static void note(String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof TraceScreen open) open.note(text);
        else if (mc.player != null) mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal(text), true);
    }

    static void end(boolean done, String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof TraceScreen || mc.screen instanceof PatchScreen) mc.setScreen(null);
        if (mc.player != null) mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal(text), false);
        lastEnd = text;
        lastDone = done;
    }

    /** The last ending, for the scenario. */
    static volatile String lastEnd = "";
    static volatile boolean lastDone;
}
