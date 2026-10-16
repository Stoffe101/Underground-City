package io.github.stoffe101.undergroundcity;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

import java.awt.event.KeyEvent;

/** The scripted situations run by {@link Playtest}. Each queues steps; nothing here is reachable in normal play. */
final class Scenarios {
    private Scenarios() {}

    static void build(String scenario) {
        switch (scenario) {
            case "input" -> input();
            default -> throw new IllegalArgumentException("unknown scenario " + scenario);
        }
    }

    /** Proves the harness itself: an injected W key must move the player. */
    private static void input() {
        final Vec3[] before = new Vec3[1];
        Playtest.queue("go to the surface at Ironhaven", () -> Playtest.command("uc_dev tp ironhaven"), () -> true, 5);
        Playtest.wait("wait for chunks", 200);
        Playtest.queue("press W for three seconds", () -> {
            before[0] = Minecraft.getInstance().player.position();
            Playtest.keyDown(KeyEvent.VK_W);
        }, () -> true, 5);
        Playtest.wait("walk", 60);
        Playtest.queue("release W and compare", () -> {
            Playtest.keyUp(KeyEvent.VK_W);
            double moved = Minecraft.getInstance().player.position().distanceTo(before[0]);
            Playtest.log("moved " + String.format("%.1f", moved) + " blocks with the injected W key");
            if (moved < 3.0) Playtest.fail("the injected key did not move the player (" + moved + " blocks)");
        });
    }
}
