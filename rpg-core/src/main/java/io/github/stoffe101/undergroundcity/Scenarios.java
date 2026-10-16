package io.github.stoffe101.undergroundcity;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/** The scripted situations run by {@link Playtest}. Each queues steps; nothing here is reachable in normal play. */
final class Scenarios {
    private Scenarios() {}

    static void build(String scenario) {
        switch (scenario) {
            case "input" -> input();
            case "register" -> register();
            default -> throw new IllegalArgumentException("unknown scenario " + scenario);
        }
    }

    /**
     * First login of a brand-new player: the registration screen must open by itself, a mouse path through it (city,
     * profession, confirm) must create the citizen on the server and grant the welcome pack, and the screen must close.
     */
    private static void register() {
        Playtest.queue("the registration screen opens by itself at first login", () -> { },
            () -> Minecraft.getInstance().screen instanceof RegistrationScreen, 600);
        Playtest.wait("let the screen render", 20);
        Playtest.queue("screenshot of step 1", () -> Playtest.screenshot("uc-playtest-register-1"));
        Playtest.wait("settle", 10);
        Playtest.queue("click the second city (Valewood)", () -> {
            Playtest.focusWindow();
            int[] at = ((RegistrationScreen) Minecraft.getInstance().screen).cardCentre(1);
            Playtest.clickGui(at[0], at[1]);
        }, () -> ((RegistrationScreen) Minecraft.getInstance().screen).step() == 1, 100);
        Playtest.queue("click the first profession (Farmer)", () -> {
            int[] at = ((RegistrationScreen) Minecraft.getInstance().screen).cardCentre(0);
            Playtest.clickGui(at[0], at[1]);
        }, () -> ((RegistrationScreen) Minecraft.getInstance().screen).step() == 2, 100);
        Playtest.wait("settle", 10);
        Playtest.queue("screenshot of the confirm step", () -> Playtest.screenshot("uc-playtest-register-3"));
        Playtest.wait("settle", 10);
        Playtest.queue("click CONFIRM", () -> {
            int[] at = ((RegistrationScreen) Minecraft.getInstance().screen).confirmCentre();
            Playtest.clickGui(at[0], at[1]);
        }, () -> !(Minecraft.getInstance().screen instanceof RegistrationScreen), 200);
        Playtest.wait("let the server answer", 20);
        Playtest.queue("the server created the citizen and the welcome pack arrived", () -> {
            var mc = Minecraft.getInstance();
            var server = mc.getSingleplayerServer();
            var citizen = CitizenState.get(server).find(mc.player.getUUID());
            if (citizen.isEmpty()) { Playtest.fail("the server has no citizen record after the click path"); return; }
            Playtest.log("citizen " + citizen.get().city + "/" + citizen.get().career);
            if (!citizen.get().city.equals("valewood") || !citizen.get().career.equals("farmer")) Playtest.fail("the wrong identity was stored: " + citizen.get().city + "/" + citizen.get().career);
            boolean terminal = false, seeds = false;
            for (var stack : mc.player.getInventory().items) {
                if (stack.is(UndergroundCity.TERMINAL_ITEM.get())) terminal = true;
                if (stack.is(net.minecraft.world.item.Items.WHEAT_SEEDS)) seeds = true;
            }
            if (!terminal || !seeds) Playtest.fail("the welcome pack did not arrive (terminal=" + terminal + ", seeds=" + seeds + ")");
        });
    }

    /** Proves the harness itself: an injected W key must move the player. */
    private static void input() {
        final Vec3[] before = new Vec3[1];
        Playtest.queue("go to the surface at Ironhaven", () -> Playtest.command("uc_dev tp ironhaven"), () -> true, 5);
        Playtest.wait("wait for chunks", 200);
        Playtest.queue("press W for three seconds", () -> {
            before[0] = Minecraft.getInstance().player.position();
            Playtest.focusWindow();
            Playtest.keyDown("w");
        }, () -> true, 5);
        Playtest.wait("walk", 60);
        Playtest.queue("release W and compare", () -> {
            Playtest.keyUp("w");
            double moved = Minecraft.getInstance().player.position().distanceTo(before[0]);
            Playtest.log("moved " + String.format("%.1f", moved) + " blocks with the injected W key");
            if (moved < 3.0) Playtest.fail("the injected key did not move the player (" + moved + " blocks)");
        });
    }
}
