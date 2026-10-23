package io.github.stoffe101.undergroundcity;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/** Client entry points of the lockpicking screen: what the server says about the lock in the player's hands (kept apart so the server never loads client classes). */
@OnlyIn(Dist.CLIENT)
public final class ClientLock {
    private ClientLock() {}

    static void open(String label, int pins, int timeLimit, int holdTicks, float tremor, float phase, boolean rakeable, int spools) {
        Minecraft.getInstance().setScreen(new LockpickScreen(label, pins, timeLimit, holdTicks, tremor, phase).thief(rakeable, spools));
    }

    static void progress(int feel, int pinsSet, int strain, int timeLeft, int serverTick, int spool) {
        if (Minecraft.getInstance().screen instanceof LockpickScreen screen) { screen.update(feel, pinsSet, strain, timeLeft, serverTick); screen.spool(spool); }
    }

    static void finished(int state, String note) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof LockpickScreen screen) screen.finish(LockpickSession.State.values()[Math.max(0, Math.min(state, LockpickSession.State.values().length - 1))], note);
        else if (mc.player != null && !note.isEmpty()) mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal(note), true);
    }
}
