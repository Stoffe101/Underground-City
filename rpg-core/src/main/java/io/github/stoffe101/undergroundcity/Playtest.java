package io.github.stoffe101.undergroundcity;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.awt.Robot;
import java.awt.event.KeyEvent;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Scripted player for the real client (opt-in {@code -Duc.playtest=<scenario>}; never set by normal launches).
 *
 * <p>The scenarios drive the actual game the way a player does: server commands typed into chat, key presses and mouse
 * clicks injected into the X11 window with {@link java.awt.Robot} (so mods that poll the keyboard directly, such as
 * Immersive Vehicles, see them), and checks on the resulting entities, items and positions. Every step prints a
 * {@code [UC-PLAYTEST]} line and the run ends with {@code [UC-PLAYTEST] RESULT pass|FAIL}. A pass means the scripted
 * input produced the observed result in this environment (a software-rendered client in a virtual display); it is not a
 * human judgement of how the thing feels.</p>
 */
@Mod.EventBusSubscriber(modid = UndergroundCity.MOD_ID, value = Dist.CLIENT)
public final class Playtest {
    static final String SCENARIO = System.getProperty("uc.playtest");
    private static final long LIMIT_NANOS = Long.getLong("uc.playtest.limitSeconds", 1500L) * 1_000_000_000L;

    /** One step: runs {@code action} once, then waits until {@code done} is true (or the step's tick budget is spent). */
    record Step(String name, Runnable action, BooleanSupplier done, int budgetTicks) {}

    private static final Deque<Step> steps = new ArrayDeque<>();
    private static Step current;
    private static int stepTicks;
    private static boolean opened, finished, scenarioBuilt;
    private static final long started = System.nanoTime();
    static final StringBuilder failures = new StringBuilder();
    private static Robot robot;

    private Playtest() {}

    static boolean enabled() { return SCENARIO != null && !SCENARIO.isBlank(); }
    static void log(String line) { System.out.println("[UC-PLAYTEST] " + line); }
    static void fail(String reason) { failures.append(reason).append("; "); log("FAIL " + reason); }
    static void queue(String name, Runnable action, BooleanSupplier done, int budgetTicks) { steps.add(new Step(name, action, done, budgetTicks)); }
    static void queue(String name, Runnable action) { queue(name, action, () -> true, 1); }
    static void wait(String name, int ticks) { int[] left = {ticks}; queue(name, () -> { }, () -> --left[0] <= 0, ticks + 5); }

    // ------------------------------------------------------------------ input
    static Robot robot() {
        if (robot == null) {
            try { robot = new Robot(); } catch (java.awt.AWTException | RuntimeException error) { fail("cannot create an AWT Robot: " + error); }
        }
        return robot;
    }
    static void keyDown(int code) { var r = robot(); if (r != null) r.keyPress(code); }
    static void keyUp(int code) { var r = robot(); if (r != null) r.keyRelease(code); }
    static void click(int button) { var r = robot(); if (r != null) r.mousePress(button); }
    static void release(int button) { var r = robot(); if (r != null) r.mouseRelease(button); }
    static void command(String text) { Minecraft.getInstance().player.connection.sendCommand(text); }
    static Minecraft mc() { return Minecraft.getInstance(); }

    static void screenshot(String name) {
        Screenshot.grab(mc().gameDirectory, name + ".png", mc().getMainRenderTarget(), message -> log("screenshot " + name + ".png"));
    }

    // ------------------------------------------------------------------ lifecycle
    @SubscribeEvent
    public static void render(TickEvent.RenderTickEvent event) {
        if (!enabled() || event.phase != TickEvent.Phase.END || opened) return;
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.screen instanceof TitleScreen || mc.screen instanceof AccessibilityOnboardingScreen)) return;
        opened = true;
        String name = System.getProperty("uc.playtest.world", "UC-Playtest");
        try {
            if (mc.getLevelSource().levelExists(name)) {
                mc.createWorldOpenFlows().loadLevel(mc.screen, name);
            } else {
                LevelSettings settings = new LevelSettings(name, GameType.CREATIVE, false, Difficulty.NORMAL, true, new GameRules(), WorldDataConfiguration.DEFAULT);
                mc.createWorldOpenFlows().createFreshLevel(name, settings, new WorldOptions(5908L, true, false),
                    registries -> registries.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.NORMAL).value().createWorldDimensions());
            }
            log("opening world " + name + " for scenario " + SCENARIO);
        } catch (RuntimeException error) {
            fail("cannot open the world: " + error);
            finish();
        }
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (!enabled() || event.phase != TickEvent.Phase.END || finished || !opened) return;
        Minecraft mc = Minecraft.getInstance();
        if (System.nanoTime() - started > LIMIT_NANOS) { fail("timed out in step " + (current == null ? "(none)" : current.name())); finish(); return; }
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null) return;
        if (!scenarioBuilt) {
            scenarioBuilt = true;
            wait("let the world settle", 100);
            try { Scenarios.build(SCENARIO); } catch (RuntimeException error) { fail("scenario " + SCENARIO + " is not available: " + error); finish(); return; }
        }
        if (current == null) {
            current = steps.poll();
            if (current == null) { finish(); return; }
            stepTicks = 0;
            log("step " + current.name());
            try { current.action().run(); } catch (RuntimeException error) { fail(current.name() + " threw " + error); finish(); return; }
        }
        boolean done;
        try { done = current.done().getAsBoolean(); } catch (RuntimeException error) { fail(current.name() + " check threw " + error); finish(); return; }
        if (done) { current = null; return; }
        if (++stepTicks > current.budgetTicks()) { fail("step '" + current.name() + "' did not complete in " + current.budgetTicks() + " ticks"); current = null; finish(); }
    }

    static void finish() {
        if (finished) return;
        finished = true;
        for (int code : List.of(KeyEvent.VK_W, KeyEvent.VK_A, KeyEvent.VK_S, KeyEvent.VK_D, KeyEvent.VK_SPACE, KeyEvent.VK_SHIFT, KeyEvent.VK_R, KeyEvent.VK_CONTROL)) {
            try { keyUp(code); } catch (RuntimeException ignored) { }
        }
        try { release(java.awt.event.InputEvent.BUTTON1_DOWN_MASK); release(java.awt.event.InputEvent.BUTTON3_DOWN_MASK); } catch (RuntimeException ignored) { }
        String result = failures.length() == 0 ? "pass" : "FAIL: " + failures;
        log("RESULT " + result);
        try { Files.writeString(mc().gameDirectory.toPath().resolve("uc-playtest-" + SCENARIO + ".txt"), result + "\n"); } catch (java.io.IOException ignored) { }
        mc().execute(() -> mc().stop());
    }
}
