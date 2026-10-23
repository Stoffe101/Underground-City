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
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

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
    private static final long LIMIT_NANOS = Long.getLong("uc.playtest.limitseconds", 1500L) * 1_000_000_000L;

    /** One step: runs {@code action} once, then waits until {@code done} is true (or the step's tick budget is spent). */
    record Step(String name, Runnable action, BooleanSupplier done, int budgetTicks) {}

    private static final Deque<Step> steps = new ArrayDeque<>();
    private static Step current;
    private static int stepTicks;
    private static boolean opened, finished, scenarioBuilt;
    private static final long started = System.nanoTime();
    static final StringBuilder failures = new StringBuilder();
    private static Process helper;
    private static java.io.BufferedWriter helperIn;
    private static java.io.BufferedReader helperOut;

    private Playtest() {}

    /** While false, any screen that opens (the first-login registration screen, above all) is closed so the scenario can move and aim; scenarios that look at screens set it once the world is settled. */
    static volatile boolean keepScreens;

    static boolean enabled() { return SCENARIO != null && !SCENARIO.isBlank(); }
    static void log(String line) { System.out.println("[UC-PLAYTEST] " + line); }
    static void fail(String reason) { failures.append(reason).append("; "); log("FAIL " + reason); }
    static void queue(String name, Runnable action, BooleanSupplier done, int budgetTicks) { steps.add(new Step(name, action, done, budgetTicks)); }
    static void queue(String name, Runnable action) { queue(name, action, () -> true, 1); }
    static void wait(String name, int ticks) { int[] left = {ticks}; queue(name, () -> { }, () -> --left[0] <= 0, ticks + 5); }

    // ------------------------------------------------------------------ input
    /**
     * Real keyboard and mouse input through scripts/xinput_helper.py (X11 XTEST): the JVM is headless (Minecraft sets it), so
     * java.awt.Robot is unavailable, and mods such as Immersive Vehicles poll GLFW's key state, which only genuine X events change.
     */
    private static boolean helper() {
        if (helper != null && helper.isAlive()) return true;
        try {
            java.nio.file.Path script = java.nio.file.Path.of(System.getProperty("uc.xinput", "../scripts/xinput_helper.py")).toAbsolutePath().normalize();
            helper = new ProcessBuilder(System.getProperty("uc.python", "python3"), script.toString()).redirectErrorStream(true).start();
            helperIn = new java.io.BufferedWriter(new java.io.OutputStreamWriter(helper.getOutputStream()));
            helperOut = new java.io.BufferedReader(new java.io.InputStreamReader(helper.getInputStream()));
            return true;
        } catch (java.io.IOException | RuntimeException error) {
            fail("cannot start the X11 input helper: " + error);
            return false;
        }
    }
    static void input(String line) {
        if (!helper()) return;
        try {
            helperIn.write(line + "\n");
            helperIn.flush();
            if (line.equals("quit")) return; // the helper exits without an answer
            String answer = helperOut.readLine();
            if (answer == null || !answer.startsWith("ok")) fail("input '" + line + "' -> " + answer);
        } catch (java.io.IOException error) {
            fail("input helper failed on '" + line + "': " + error);
        }
    }
    static void keyDown(String key) { input("down " + key); }
    static void keyUp(String key) { input("up " + key); }
    static void tap(String key) { input("tap " + key); }
    static void mouseButton(int button, boolean down) { input("button " + button + " " + (down ? 1 : 0)); }
    /** A press and release in one go, read by the game in one input poll (two separate events can be ticks apart under software rendering, and a held use repeats). */
    static void click(int button) { input("click " + button); }
    /** Parks the pointer over the middle of the game window so the (window-manager-less) X keyboard focus is the game. */
    /** A real left click at a GUI-space position (what a player would do with the mouse). */
    static void clickGui(int guiX, int guiY) {
        var window = mc().getWindow();
        double scale = window.getGuiScale();
        input("move " + (int) Math.round(guiX * scale) + " " + (int) Math.round(guiY * scale));
        mouseButton(1, true);
        mouseButton(1, false);
    }
    /** Moves the pointer to a GUI-space position without pressing anything (the game needs a frame to learn where it is before a press). */
    static void moveGui(int guiX, int guiY) {
        double scale = mc().getWindow().getGuiScale();
        input("move " + (int) Math.round(guiX * scale) + " " + (int) Math.round(guiY * scale));
    }
    static void pressLeft() { mouseButton(1, true); mouseButton(1, false); }
    static void focusWindow() {
        var window = mc().getWindow();
        input("move " + (window.getWidth() / 2) + " " + (window.getHeight() / 2));
    }
    static void command(String text) { Minecraft.getInstance().player.connection.sendCommand(text); }
    static Minecraft mc() { return Minecraft.getInstance(); }

    static void screenshot(String name) {
        Screenshot.grab(mc().gameDirectory, name + ".png", mc().getMainRenderTarget(), message -> log("screenshot " + name + ".png"));
    }

    private static final java.util.concurrent.ConcurrentLinkedQueue<String> frameShots = new java.util.concurrent.ConcurrentLinkedQueue<>();

    /**
     * A screenshot of the next frame that is completely drawn. {@link #screenshot} copies whatever the render target holds when it is called, and in a software-rendered
     * client that can be a frame several seconds old (the game ticks many times between frames), which is how a frame taken "under the bridge" showed the start of the sail.
     * Use this for anything that moves.
     */
    static void screenshotNextFrame(String name) { frameShots.add(name); }

    /** The driver whose passenger view is kept ahead of the vehicle; re-applied just before every frame so nothing between ticks can turn the camera. */
    static volatile RoadDriver viewDriver;

    @SubscribeEvent
    public static void beforeFrame(TickEvent.RenderTickEvent event) {
        RoadDriver driver = viewDriver;
        if (driver != null && event.phase == TickEvent.Phase.START) driver.applyView();
        if (driver != null && event.phase == TickEvent.Phase.END) driver.measureView();
        if (event.phase == TickEvent.Phase.END) {
            String name;
            while ((name = frameShots.poll()) != null) screenshot(name);
        }
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
                // the first-player journey starts as a new player does, in survival; the other scenarios start in creative and switch when they need to
                GameType mode = "journey".equals(SCENARIO) || Boolean.getBoolean("uc.playtest.survival") ? GameType.SURVIVAL : GameType.CREATIVE;
                LevelSettings settings = new LevelSettings(name, mode, false, Difficulty.NORMAL, true, new GameRules(), WorldDataConfiguration.DEFAULT);
                mc.createWorldOpenFlows().createFreshLevel(name, settings, new WorldOptions(5908L, true, false), DevWorldLauncher::dimensions);
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
        DevWorldLauncher.acceptExperimentalWarning(mc);
        if (System.nanoTime() - started > LIMIT_NANOS) { fail("timed out in step " + (current == null ? "(none)" : current.name())); finish(); return; }
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null) return;
        // no scenario expects the player to die; a dead player loads no chunks, so every later step would only wait out its budget (journey run 53 drowned and waited ten minutes)
        if (mc.player.isDeadOrDying()) {
            var cause = mc.player.getLastDamageSource();
            fail("the player died" + (cause == null ? "" : " (" + cause.getMsgId() + ")") + " at " + mc.player.blockPosition().toShortString() + " in step " + (current == null ? "(none)" : current.name()));
            finish();
            return;
        }
        if (!scenarioBuilt) {
            scenarioBuilt = true;
            wait("let the world settle", 100);
            try { Scenarios.build(SCENARIO); } catch (RuntimeException error) { fail("scenario " + SCENARIO + " is not available: " + error); finish(); return; }
        }
        if (mc.screen != null && !keepScreens && !"register".equals(SCENARIO) && !SCENARIO.startsWith("terminal")) {
            // The first-login registration screen opens by itself for a new player; scenarios that need to move or aim close it.
            log("closing " + mc.screen.getClass().getSimpleName());
            mc.setScreen(null);
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
        for (String key : List.of("w", "a", "s", "d", "space", "shift", "r", "ctrl")) {
            try { if (helper != null && helper.isAlive()) keyUp(key); } catch (RuntimeException ignored) { }
        }
        try { if (helper != null && helper.isAlive()) { mouseButton(1, false); mouseButton(3, false); input("quit"); } } catch (RuntimeException ignored) { }
        String result = failures.length() == 0 ? "pass" : "FAIL: " + failures;
        log("RESULT " + result);
        try { Files.writeString(mc().gameDirectory.toPath().resolve("uc-playtest-" + SCENARIO + ".txt"), result + "\n"); } catch (java.io.IOException ignored) { }
        // A client that does not leave on its own (a mod's shutdown hook that waits on a thread) must not hold a test run for ever.
        Thread watchdog = new Thread(() -> {
            try { Thread.sleep(60_000); } catch (InterruptedException ignored) { return; }
            System.out.println("[UC-PLAYTEST] the client did not exit within 60 s; halting");
            Runtime.getRuntime().halt(failures.length() == 0 ? 0 : 1);
        }, "uc-playtest-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        mc().execute(() -> mc().stop());
    }
}
